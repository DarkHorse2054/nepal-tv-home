"""Optional, leased Fire TV video. No stream starts on import or status reads."""
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import stat
import subprocess
import tempfile
import threading
import time

SERVER_VERSION = "5.0.1"
SERVER_SHA256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
COOKIE_NAME = "nepal_live"
LEASE_SECONDS = 30
MAX_SECONDS = 15 * 60
MAX_DISK_BYTES = 16 * 1024 * 1024
MAX_FILES = 16
FRAME_WIDTH, FRAME_HEIGHT, FRAME_RATE = 1280, 720, 20
FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * 3 // 2
ASSET_NAME = re.compile(r"(?:index\.m3u8|part[0-9]{6}\.ts)", re.ASCII)


class LiveError(Exception):
    def __init__(self, code, status=503):
        self.code, self.status = code, status


def ffmpeg_executable(explicit=None):
    if explicit:
        return str(Path(explicit).resolve()) if Path(explicit).is_file() else None
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, OSError, RuntimeError):
        return shutil.which("ffmpeg")


def cookie_header(identifier, token, clear=False):
    return (f"{COOKIE_NAME}={'' if clear else token}; Path=/api/live/{identifier}/; "
            f"Max-Age={0 if clear else 40}; HttpOnly; SameSite=Strict")


def cookie_value(headers):
    values = headers.get_all("Cookie", [])
    if len(values) != 1:
        raise LiveError("live_unauthorized", 401)
    matches = [part.strip().partition("=")[2] for part in values[0].split(";")
               if part.strip().partition("=")[0] == COOKIE_NAME]
    if len(matches) != 1 or not re.fullmatch(r"[A-Za-z0-9_-]{43}", matches[0], re.ASCII):
        raise LiveError("live_unauthorized", 401)
    return matches[0]


def range_bounds(raw, size):
    if raw is None:
        return 0, size - 1, 200
    match = re.fullmatch(r"bytes=([0-9]{0,12})-([0-9]{0,12})", raw, re.ASCII)
    if not match or not any(match.groups()) or size <= 0:
        raise LiveError("live_invalid_range", 416)
    left, right = match.groups()
    if not left:
        length = int(right)
        if length <= 0:
            raise LiveError("live_invalid_range", 416)
        return max(0, size - length), size - 1, 206
    start, end = int(left), min(int(right), size - 1) if right else size - 1
    if start >= size or start > end:
        raise LiveError("live_invalid_range", 416)
    return start, end, 206


class Session:
    def __init__(self, owner, now):
        self.owner = owner
        self.identifier = secrets.token_hex(16)
        self.cookie = secrets.token_urlsafe(32)
        self.scid = f"{secrets.randbits(31):08x}"
        self.state, self.error = "starting", None
        self.created, self.lease = now, now + LEASE_SECONDS
        self.stop = threading.Event()
        self.done = threading.Event()
        self.directory = None
        self.port = None
        self.connection = None
        self.server = self.decoder = self.encoder = None
        self.remote_pid = None
        self.log_thread = None
        self.encoder_log_thread = self.decoder_log_thread = None
        self.source_thread = self.frame_thread = None
        self.frame_lock = threading.Lock()
        self.latest_frame = None
        self.decoded_frames = self.source_bytes = 0
        self.startup_deadline = self.last_output_at = None
        self.diagnostic = bytearray()
        self.decoder_diagnostic = bytearray()
        self.thread = None
        self.watchdog = None
        self.cleanup_lock = threading.Lock()
        self.cleaned = False
        self.remote_jar = f"/data/local/tmp/nepal-live-{self.identifier}.jar"

    def snapshot(self):
        result = {"state": self.state, "videoOnly": True}
        if self.state in ("starting", "ready"):
            result["playlist"] = f"/api/live/{self.identifier}/index.m3u8"
        if self.error:
            result["error"] = self.error
        return result


class LiveVideo:
    """One owner, fixed device/arguments, bounded child lifetime and HLS buffer."""
    def __init__(self, adb, serial, jar, env, identity, active, *, ffmpeg=None,
                 runner=subprocess.run, popen=subprocess.Popen, clock=time.monotonic,
                 temp_root=None, socket_factory=socket.create_connection):
        self.adb, self.serial, self.jar = str(adb), serial, Path(jar)
        self.env, self.identity, self.parent_active = dict(env), identity, active
        self.ffmpeg = ffmpeg
        self.runner, self.popen, self.clock = runner, popen, clock
        self.temp_root, self.socket_factory = temp_root, socket_factory
        self.lock = threading.RLock()
        self.operation = threading.Lock()
        self.session = None
        self.closed = False

    def _dependencies(self):
        try:
            if self.jar.is_symlink() or self.jar.stat().st_size > 4 * 1024 * 1024:
                raise LiveError("live_server_missing")
            digest = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        except OSError:
            raise LiveError("live_server_missing") from None
        if digest != SERVER_SHA256:
            raise LiveError("live_server_invalid")
        binary = ffmpeg_executable(self.ffmpeg)
        if not binary:
            raise LiveError("live_dependency_missing")
        return binary

    def _valid(self, job):
        now = self.clock()
        return (not job.stop.is_set() and self.parent_active(job.owner)
                and now < job.lease and now - job.created < MAX_SECONDS)

    def start(self, owner):
        if not self.operation.acquire(blocking=False):
            raise LiveError("live_busy", 409)
        try:
            with self.lock:
                if self.closed:
                    raise LiveError("live_unavailable")
                job = self.session
                if job and job.state in ("starting", "ready") and self._valid(job):
                    if job.owner != owner:
                        raise LiveError("live_in_use", 409)
                    return job.snapshot(), cookie_header(job.identifier, job.cookie)
            if job and not job.done.is_set():
                self._stop_job(job)
            binary = self._dependencies()
            job = Session(owner, self.clock())
            with self.lock:
                self.session = job
            job.thread = threading.Thread(target=self._worker, args=(job, binary), daemon=True,
                                          name="nepal-live-video")
            job.thread.start()
            job.watchdog = threading.Thread(target=self._watch, args=(job,), daemon=True, name="nepal-live-lease")
            job.watchdog.start()
            return job.snapshot(), cookie_header(job.identifier, job.cookie)
        finally:
            self.operation.release()

    def status(self, owner):
        with self.lock:
            job = self.session
            if not job or job.owner != owner:
                return {"state": "stopped", "videoOnly": True}
            if not self._valid(job) and job.state in ("starting", "ready"):
                job.stop.set()
                return {"state": "stopped", "videoOnly": True}
            return job.snapshot()

    def keepalive(self, owner):
        with self.lock:
            job = self.session
            if not job:
                return {"state": "stopped", "videoOnly": True}, None
            if job.owner != owner:
                raise LiveError("live_not_owner", 403)
            if not self._valid(job) or job.state not in ("starting", "ready"):
                job.stop.set()
                return {"state": "stopped", "videoOnly": True}, cookie_header(job.identifier, "", True)
            job.lease = min(self.clock() + LEASE_SECONDS, job.created + MAX_SECONDS)
            return job.snapshot(), cookie_header(job.identifier, job.cookie)

    def stop(self, owner):
        if not self.operation.acquire(blocking=False):
            raise LiveError("live_busy", 409)
        try:
            with self.lock:
                job = self.session
                if job and job.owner != owner:
                    raise LiveError("live_not_owner", 403)
            if job:
                self._stop_job(job)
            return {"state": "stopped", "videoOnly": True}, cookie_header(job.identifier, "", True) if job else None
        finally:
            self.operation.release()

    def active(self):
        with self.lock:
            return bool(self.session and self.session.state in ("starting", "ready") and self._valid(self.session))

    def _stop_job(self, job):
        job.stop.set()
        self._interrupt(job)
        if job.thread is not None and job.thread is not threading.current_thread():
            job.done.wait(12)
        if not job.done.is_set():
            self._cleanup(job)
            job.done.set()
        job.state = "stopped"
        job.error = None

    def close(self):
        with self.operation:
            self.closed = True
            if self.session:
                self._stop_job(self.session)

    def _adb(self, job, arguments, timeout=3):
        if job.stop.is_set():
            raise LiveError("live_stopped")
        try:
            result = self.runner([self.adb, "-s", self.serial, *arguments], stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=timeout, env=self.env, shell=False, text=True)
        except (OSError, subprocess.TimeoutExpired):
            raise LiveError("live_unavailable") from None
        if result.returncode:
            raise LiveError("live_unavailable")
        return result.stdout.strip()

    def _command(self, job):
        options = [SERVER_VERSION, f"scid={job.scid}", "log_level=error", "video=true", "audio=false",
            "control=false", "cleanup=false", "power_on=false", "raw_stream=true", "tunnel_forward=true",
            "video_codec=h264", "max_size=1280", "max_fps=20", "video_bit_rate=2000000",
            "video_codec_options=i-frame-interval=1"]
        command = ("echo NEPAL_PID=$$; CLASSPATH=" + shlex.quote(job.remote_jar)
                   + " exec app_process / com.genymobile.scrcpy.Server "
                   + " ".join(shlex.quote(value) for value in options))
        return [self.adb, "-s", self.serial, "shell", command]

    def _consume_server_log(self, job):
        try:
            while True:
                line = job.server.stdout.readline(4096)
                if not line:
                    break
                match = re.fullmatch(rb"NEPAL_PID=([0-9]{1,8})\r?\n", line)
                if match:
                    job.remote_pid = int(match[1])
        except OSError:
            pass

    @staticmethod
    def _consume_diagnostic(process, tail):
        try:
            while True:
                chunk = process.stderr.read(4096)
                if not chunk:
                    break
                tail.extend(chunk)
                del tail[:-8192]
        except (OSError, ValueError):
            pass  # Bounded diagnostic tails stay private in memory, never in HTTP responses.

    def _fail_pipeline(self, job, code):
        with self.lock:
            if job.stop.is_set() or job.done.is_set():
                return
            job.error = code
            job.stop.set()
        self._interrupt(job)

    def _feed_source(self, job, first):
        try:
            chunk = first
            while self._valid(job):
                if chunk:
                    job.decoder.stdin.write(chunk)
                    job.decoder.stdin.flush()
                    job.source_bytes += len(chunk)
                try:
                    chunk = job.connection.recv(65536)
                except socket.timeout:
                    chunk = None
                    continue
                if not chunk:
                    self._fail_pipeline(job, "live_connection_lost")
                    return
        except (OSError, ValueError):
            self._fail_pipeline(job, "live_connection_lost")

    def _read_frames(self, job):
        try:
            while self._valid(job):
                # Two bounded buffers: the published latest frame and the frame being read.
                frame = bytearray(FRAME_BYTES)
                view, offset = memoryview(frame), 0
                while offset < FRAME_BYTES and self._valid(job):
                    count = job.decoder.stdout.readinto(view[offset:])
                    if not count:
                        self._fail_pipeline(job, "live_process_failed")
                        return
                    offset += count
                if offset != FRAME_BYTES or not self._valid(job):
                    return
                with job.frame_lock:
                    job.latest_frame = frame
                    job.decoded_frames += 1
                del frame, view
        except (OSError, ValueError):
            self._fail_pipeline(job, "live_process_failed")

    def _connect(self, job):
        deadline = self.clock() + 12
        connection = None
        try:
            while self.clock() < deadline and self._valid(job):
                try:
                    if connection is None:
                        connection = self.socket_factory(("127.0.0.1", job.port), timeout=.4)
                    connection.settimeout(min(.5, max(.001, deadline - self.clock())))
                    first = connection.recv(65536)
                    if first:
                        connection.settimeout(.5)
                        accepted, connection = connection, None
                        return accepted, first
                except socket.timeout:
                    # An accepted video socket may be waiting for MediaCodec.
                    # Closing it here can terminate scrcpy's only video connection.
                    continue
                except OSError:
                    pass
                if connection is not None:
                    connection.close()
                    connection = None
                job.stop.wait(.15)  # Actual EOF/connect failure may mean the forward is not ready yet.
            raise LiveError("live_start_timeout")
        finally:
            if connection is not None:
                connection.close()

    @staticmethod
    def _decoder_command(binary):
        return [binary, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-threads", "1", "-flags", "low_delay", "-fflags", "+genpts",
            "-probesize", "32768", "-analyzeduration", "100000", "-fpsprobesize", "0",
            "-f", "h264", "-i", "pipe:0", "-vf",
            "scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2,"
            "pad=1280:720:(ow-iw)/2:(oh-ih)/2",
            # Rawvideo's frame-threaded output otherwise holds a completed frame
            # until another arrives, which can be minutes on a quiet TV screen.
            "-pix_fmt", "yuv420p", "-threads:v", "1", "-fps_mode", "passthrough",
            "-f", "rawvideo", "-flush_packets", "1", "pipe:1"]

    def _ffmpeg_command(self, job, binary):
        directory = Path(job.directory)
        return [binary, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "1280x720",
            "-framerate", str(FRAME_RATE), "-i", "pipe:0",
            "-vf", "fps=20", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-g", "20", "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-an", "-t", str(MAX_SECONDS),
            "-f", "hls", "-hls_time", "1", "-hls_list_size", "5", "-hls_delete_threshold", "1",
            "-hls_flags", "delete_segments+temp_file+independent_segments", "-hls_allow_cache", "0",
            "-hls_segment_filename", str(directory / "part%06d.ts"), str(directory / "index.m3u8")]

    def _check_buffer(self, job):
        entries = list(Path(job.directory).iterdir())
        if len(entries) > MAX_FILES:
            raise LiveError("live_buffer_limit")
        total = 0
        for entry in entries:
            try:
                detail = entry.lstat()
            except FileNotFoundError:
                continue  # FFmpeg may retire a completed segment between listing and stat.
            if not stat.S_ISREG(detail.st_mode) or detail.st_uid != os.getuid():
                raise LiveError("live_invalid_media")
            total += detail.st_size
        if total > MAX_DISK_BYTES:
            raise LiveError("live_buffer_limit")

    def _watch(self, job):
        while not job.done.wait(.5):
            if not self._valid(job):
                job.stop.set()
                self._interrupt(job)
                return
            if (job.startup_deadline is not None and job.state == "starting"
                    and self.clock() >= job.startup_deadline):
                self._fail_pipeline(job, "live_start_timeout")
                return
            if job.last_output_at is not None and self.clock() - job.last_output_at >= 12:
                self._fail_pipeline(job, "live_process_failed")
                return
            if job.directory:
                try:
                    self._check_buffer(job)
                except (OSError, LiveError):
                    if job.stop.is_set() or job.done.is_set():
                        return
                    job.error = "live_buffer_limit"
                    job.stop.set()
                    self._interrupt(job)
                    return

    def _worker(self, job, binary):
        try:
            self.identity()
            if not self._valid(job):
                return
            job.directory = tempfile.mkdtemp(prefix="nepal-live-", dir=self.temp_root)
            os.chmod(job.directory, 0o700)
            self._adb(job, ["push", str(self.jar), job.remote_jar], timeout=5)
            port = self._adb(job, ["forward", "tcp:0", "localabstract:scrcpy_" + job.scid])
            if not re.fullmatch(r"[0-9]{1,5}", port) or not 1 <= int(port) <= 65535:
                raise LiveError("live_unavailable")
            job.port = int(port)
            job.server = self.popen(self._command(job), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                env=self.env, shell=False, start_new_session=True)
            job.log_thread = threading.Thread(target=self._consume_server_log, args=(job,), daemon=True)
            job.log_thread.start()
            job.connection, first = self._connect(job)
            if not self._valid(job):
                return
            job.startup_deadline = self.clock() + 12
            job.decoder = self.popen(self._decoder_command(binary), stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env, shell=False,
                start_new_session=True, umask=0o077)
            if not self._valid(job):
                return
            job.encoder = self.popen(self._ffmpeg_command(job, binary), stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=self.env, shell=False,
                start_new_session=True, umask=0o077)
            job.decoder_log_thread = threading.Thread(target=self._consume_diagnostic,
                args=(job.decoder, job.decoder_diagnostic), daemon=True, name="nepal-live-decoder-log")
            job.encoder_log_thread = threading.Thread(target=self._consume_diagnostic,
                args=(job.encoder, job.diagnostic), daemon=True, name="nepal-live-encoder-log")
            job.source_thread = threading.Thread(target=self._feed_source, args=(job, first),
                daemon=True, name="nepal-live-source")
            job.frame_thread = threading.Thread(target=self._read_frames, args=(job,),
                daemon=True, name="nepal-live-frames")
            for thread in (job.decoder_log_thread, job.encoder_log_thread, job.source_thread, job.frame_thread):
                thread.start()
            tick = self.clock()
            while self._valid(job):
                if any(process.poll() is not None for process in (job.server, job.decoder, job.encoder)):
                    raise LiveError("live_process_failed")
                # A quiet Home screen emits no new H264 packets. Repeat the latest complete
                # decoded frame on a wall-clock cadence so HLS time still advances.
                with job.frame_lock:
                    if job.latest_frame is not None:
                        job.encoder.stdin.write(job.latest_frame)
                        job.encoder.stdin.flush()
                        job.last_output_at = self.clock()
                self._check_buffer(job)
                if (Path(job.directory) / "index.m3u8").is_file():
                    job.state = "ready"
                elif self.clock() >= job.startup_deadline:
                    raise LiveError("live_start_timeout")
                tick = max(tick + 1 / FRAME_RATE, self.clock())
                job.stop.wait(max(0, tick - self.clock()))
        except LiveError as error:
            if not job.stop.is_set():
                job.error = error.code
        except Exception:
            if not job.stop.is_set():
                job.error = "live_unavailable"
        finally:
            self._cleanup(job)
            job.state = "error" if job.error else "stopped"
            job.done.set()

    @staticmethod
    def _interrupt(job):
        if job.connection:
            try:
                job.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                job.connection.close()
            except OSError:
                pass
        for process in (job.encoder, job.decoder, job.server):
            if process is not None and process.poll() is None:
                try:
                    process.terminate()
                except OSError:
                    pass

    def _cleanup(self, job):
        with job.cleanup_lock:
            if job.cleaned:
                return
            job.cleaned = True
            self._cleanup_once(job)

    def _cleanup_once(self, job):
        job.stop.set()
        self._interrupt(job)
        for process in (job.encoder, job.decoder, job.server):
            if process is None:
                continue
            try:
                process.wait(timeout=1)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    process.kill()
                    process.wait(timeout=1)
                except (OSError, subprocess.TimeoutExpired):
                    pass  # Continue retiring the other owned resources even if reaping fails.
            for stream in (getattr(process, "stdin", None), getattr(process, "stdout", None), getattr(process, "stderr", None)):
                if stream:
                    try:
                        stream.close()
                    except (OSError, ValueError):
                        pass
        for thread in (job.source_thread, job.frame_thread, job.log_thread,
                       job.decoder_log_thread, job.encoder_log_thread):
            if thread and thread is not threading.current_thread():
                thread.join(timeout=1)
        job.latest_frame = None
        commands = []
        if job.remote_pid:
            needle = "scid=" + job.scid
            command = (f"if tr '\\000' ' ' </proc/{job.remote_pid}/cmdline 2>/dev/null "
                       + "| grep -F " + shlex.quote(needle) + f" >/dev/null; then kill {job.remote_pid}; fi")
            commands.append(["shell", command])
        if job.port:
            commands.append(["forward", "--remove", "tcp:" + str(job.port)])
        commands.append(["shell", "rm", "-f", job.remote_jar])
        for command in commands:
            try:
                self.runner([self.adb, "-s", self.serial, *command], stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL, timeout=1, env=self.env, shell=False)
            except (OSError, subprocess.TimeoutExpired):
                pass
        if job.directory:
            shutil.rmtree(job.directory, ignore_errors=True)

    def media(self, identifier, name, cookie, raw_range=None):
        with self.lock:
            job = self.session
            if (not job or identifier != job.identifier or not secrets.compare_digest(cookie, job.cookie)
                    or job.state != "ready" or not self._valid(job)):
                raise LiveError("live_unauthorized", 401)
            if not ASSET_NAME.fullmatch(name) or not job.directory:
                raise LiveError("live_not_found", 404)
            path = Path(job.directory) / name
        descriptor = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            details = os.fstat(descriptor)
            limit = 65536 if name == "index.m3u8" else 4 * 1024 * 1024
            if (not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid()
                    or stat.S_IMODE(details.st_mode) != 0o600 or not 0 < details.st_size <= limit):
                raise LiveError("live_invalid_media")
            start, end, code = range_bounds(raw_range, details.st_size)
            with os.fdopen(descriptor, "rb") as source:
                descriptor = None
                source.seek(start)
                data = source.read(end - start + 1)
            if len(data) != end - start + 1:
                raise LiveError("live_invalid_media")
            headers = [("Accept-Ranges", "bytes")]
            if code == 206:
                headers.append(("Content-Range", f"bytes {start}-{end}/{details.st_size}"))
            mime = "application/vnd.apple.mpegurl" if name.endswith(".m3u8") else "video/mp2t"
            return code, data, mime, headers
        except FileNotFoundError:
            raise LiveError("live_not_found", 404) from None
        except OSError:
            raise LiveError("live_invalid_media") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)
