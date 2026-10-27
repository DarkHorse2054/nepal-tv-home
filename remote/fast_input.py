"""Leased control-only scrcpy transport. Import and construction perform no I/O."""
import contextlib
import hashlib
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time

SERVER_VERSION = "5.0.1"
SERVER_SHA256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
LEASE_SECONDS, RETRY_SECONDS = 60, 10


def unix_connection(path, timeout):
    """A filesystem socket inside an owner-only directory, never a TCP listener."""
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        connection.settimeout(timeout)
        connection.connect(path)
        return connection
    except Exception:
        connection.close()
        raise


class InputError(Exception):
    def __init__(self, code, status=503):
        self.code, self.status = code, status


class _Session:
    def __init__(self, now):
        self.scid = f"{secrets.randbits(31):08x}"
        self.remote_jar = "/data/local/tmp/nepal-input-" + secrets.token_hex(16) + ".jar"
        self.lease = now + LEASE_SECONDS
        self.stop, self.done = threading.Event(), threading.Event()
        self.ready = False
        self.push_attempted = False
        self.forward_attempted = False
        self.directory = self.socket_path = self.local_endpoint = None
        self.connection = self.process = self.remote_pid = None
        self.thread = self.log_thread = None


class FastInput:
    """One fixed device and key allowlist; successful writes are not input readback.

    Only an authenticated, visible remote may call warm(). A false send result
    permits the caller's legacy path; an attempted write must never be replayed.
    """
    def __init__(self, adb, serial, jar, env, identity, allowed_codes, *,
                 runner=subprocess.run, popen=subprocess.Popen,
                 socket_factory=unix_connection, clock=time.monotonic, temp_root=None):
        self.adb, self.serial, self.jar = str(adb), serial, Path(jar)
        self.env, self.identity, self.allowed_codes = dict(env), identity, frozenset(allowed_codes)
        self.runner, self.popen, self.socket_factory, self.clock = runner, popen, socket_factory, clock
        self.temp_root = temp_root
        self.lock = threading.RLock()
        self.session, self.closed, self.retry_after = None, False, float("-inf")

    def _valid(self, job):
        return not self.closed and not job.stop.is_set() and self.clock() < job.lease

    def warm(self):
        with self.lock:
            if self.closed:
                return False
            job = self.session
            if job and not job.done.is_set():
                if self._valid(job):
                    job.lease = self.clock() + LEASE_SECONDS
                    return job.ready
                job.stop.set()
                return False  # Retire the old owner before creating another one.
            if self.clock() < self.retry_after:
                return False
            job = self.session = _Session(self.clock())
            job.thread = threading.Thread(target=self._worker, args=(job,), daemon=True, name="nepal-fast-input")
            try:
                job.thread.start()
            except RuntimeError:
                self.retry_after = self.clock() + RETRY_SECONDS
                job.stop.set()
                job.done.set()
            return False

    def send(self, code):
        if type(code) is not int or code not in self.allowed_codes:
            raise InputError("invalid_control", 400)
        # The lock serializes pairs, but never waits for an injection acknowledgement.
        with self.lock:
            job = self.session
            if not job or not job.ready or not self._valid(job):
                return False
            packet = struct.pack(">BBIII", 0, 0, code, 0, 0) + struct.pack(">BBIII", 0, 1, code, 0, 0)
            try:
                job.connection.sendall(packet)
            except (OSError, ValueError):
                job.ready = False
                job.stop.set()
                self.retry_after = self.clock() + RETRY_SECONDS
                self._interrupt(job)
                raise InputError("input_send_uncertain") from None
            job.lease = self.clock() + LEASE_SECONDS
            return True

    def close(self):
        with self.lock:
            self.closed = True
            job = self.session
            if job:
                job.ready = False
                job.stop.set()
        if job:
            self._interrupt(job)
            if job.thread.ident is not None and job.thread is not threading.current_thread():
                job.thread.join(timeout=15)

    def _check(self, job):
        if not self._valid(job):
            raise InputError("input_stopped")

    def _dependencies(self):
        try:
            if self.jar.is_symlink() or self.jar.stat().st_size > 4 * 1024 * 1024:
                raise InputError("input_server_invalid")
            if hashlib.sha256(self.jar.read_bytes()).hexdigest() != SERVER_SHA256:
                raise InputError("input_server_invalid")
        except OSError:
            raise InputError("input_server_missing") from None

    def _adb(self, job, args, timeout=3):
        self._check(job)
        result = self.runner([self.adb, "-s", self.serial, *args], stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=timeout, env=self.env, shell=False, text=True)
        if result.returncode:
            raise InputError("input_unavailable")
        return result.stdout.strip()

    def _command(self, job):
        options = [SERVER_VERSION, "scid=" + job.scid, "log_level=error", "video=false", "audio=false",
            "control=true", "power_on=false", "clipboard_autosync=false", "cleanup=false",
            "tunnel_forward=true", "send_device_meta=false", "send_dummy_byte=true", "keep_active=false"]
        command = ("echo NEPAL_INPUT_PID=$$; CLASSPATH=" + shlex.quote(job.remote_jar)
            + " exec app_process / com.genymobile.scrcpy.Server " + " ".join(shlex.quote(value) for value in options))
        return [self.adb, "-s", self.serial, "shell", command]

    @staticmethod
    def _read_log(job):
        try:
            while True:
                line = job.process.stdout.readline(4096)
                if not line:
                    break
                match = re.fullmatch(rb"NEPAL_INPUT_PID=([0-9]{1,8})\r?\n", line)
                if match:
                    job.remote_pid = int(match[1])
                # Discard all diagnostics; no unbounded pipe or private log grows.
        except (OSError, ValueError):
            pass

    def _connect(self, job):
        deadline = self.clock() + 8
        connection = None
        try:
            while self.clock() < deadline and self._valid(job):
                if job.process.poll() is not None:
                    break
                try:
                    if connection is None:
                        connection = self.socket_factory(job.socket_path, timeout=.25)
                        connection.settimeout(.5)
                    first = connection.recv(1)
                    if first == b"\x00":
                        return connection
                    if first:
                        raise InputError("input_invalid_handshake")
                    connection.close()
                    connection = None
                except socket.timeout:
                    continue  # Keep an accepted socket while its server finishes startup.
                except OSError:
                    if connection:
                        connection.close()
                    connection = None
                job.stop.wait(.05)
            raise InputError("input_start_timeout")
        except Exception:
            if connection:
                connection.close()
            raise

    def _worker(self, job):
        try:
            self._dependencies()
            self._check(job)
            if self.identity() is False:
                raise InputError("input_wrong_device")
            self._check(job)
            job.directory = tempfile.mkdtemp(prefix="nepal-input-", dir=self.temp_root)
            Path(job.directory).chmod(0o700)
            job.socket_path = str(Path(job.directory) / "control.sock")
            # macOS sockaddr_un has a short path limit. Fail before any push.
            if len(job.socket_path.encode()) > 100:
                raise InputError("input_socket_path_invalid")
            job.local_endpoint = "localfilesystem:" + job.socket_path
            self._check(job)
            job.push_attempted = True
            self._adb(job, ["push", str(self.jar), job.remote_jar], timeout=5)
            self._check(job)
            job.forward_attempted = True
            self._adb(job, ["forward", job.local_endpoint, "localabstract:scrcpy_" + job.scid])
            self._check(job)
            job.process = self.popen(self._command(job), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    env=self.env, shell=False, start_new_session=True)
            job.log_thread = threading.Thread(target=self._read_log, args=(job,), daemon=True, name="nepal-input-log")
            job.log_thread.start()
            job.connection = self._connect(job)
            with self.lock:
                self._check(job)
                job.ready = True
            while self._valid(job) and job.process.poll() is None:
                try:
                    # No device messages are expected with clipboard synchronization off.
                    # EOF, unsolicited data or a process exit retires this connection.
                    job.connection.recv(1)
                    raise InputError("input_connection_lost")
                except socket.timeout:
                    continue
            if self._valid(job):
                raise InputError("input_process_failed")
        except Exception:
            with self.lock:
                if self._valid(job):
                    self.retry_after = self.clock() + RETRY_SECONDS
        finally:
            with self.lock:
                job.ready = False
                job.stop.set()
            try:
                self._cleanup(job)
            finally:
                job.done.set()

    @staticmethod
    def _interrupt(job):
        if job.connection:
            with contextlib.suppress(OSError):
                job.connection.shutdown(socket.SHUT_RDWR)
        if job.process:
            with contextlib.suppress(OSError):
                job.process.terminate()

    def _cleanup(self, job):
        self._interrupt(job)
        if job.connection:
            with contextlib.suppress(OSError):
                job.connection.close()
        if job.process:
            try:
                job.process.wait(timeout=1)
            except (OSError, subprocess.TimeoutExpired):
                with contextlib.suppress(OSError, subprocess.TimeoutExpired):
                    job.process.kill()
                    job.process.wait(timeout=1)
            if job.process.stdout:
                with contextlib.suppress(OSError):
                    job.process.stdout.close()
        if job.log_thread and job.log_thread.ident is not None:
            job.log_thread.join(timeout=1)
        commands = []
        if job.remote_pid:
            command = (f"if tr '\\000' ' ' </proc/{job.remote_pid}/cmdline 2>/dev/null | grep -F "
                + shlex.quote("scid=" + job.scid) + f" >/dev/null; then kill {job.remote_pid}; fi")
            commands.append(["shell", command])
        if job.forward_attempted:
            # This known, unique endpoint can be removed even after a lost ACK.
            commands.append(["forward", "--remove", job.local_endpoint])
        if job.push_attempted:
            commands.append(["shell", "rm", "-f", job.remote_jar])
        for command in commands:
            with contextlib.suppress(OSError, subprocess.TimeoutExpired):
                self.runner([self.adb, "-s", self.serial, *command], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=1, env=self.env, shell=False)
        if job.directory:
            shutil.rmtree(job.directory, ignore_errors=True)
