"""Leased video checks use fake children/sockets and temporary synthetic media only."""
import hashlib
from http.client import HTTPMessage
import io
import json
import os
from pathlib import Path
import subprocess
import socket
import time
import tempfile
import threading
import types
import unittest
from unittest.mock import patch
import test_server as base

server = base.server
live = server.LIVE
OWNER = "a" * 64
OTHER = "b" * 64


class ControlledLive(live.LiveVideo):
    def __init__(self, now=None, parents=None):
        self.now = now if now is not None else [0.0]
        self.parents = parents if parents is not None else {OWNER: True, OTHER: True}
        self.workers = 0
        self.started = threading.Event()
        super().__init__("/fake/adb", "192.168.1.10:5555", "/missing/server", {},
                         lambda: None, lambda owner: self.parents.get(owner, False), clock=lambda: self.now[0])
    def _dependencies(self): return "/fake/ffmpeg"
    def _worker(self, job, _binary):
        self.workers += 1
        job.state = "ready"
        self.started.set()
        job.stop.wait(5)
        job.state = "stopped"
        job.done.set()


class LeaseTests(unittest.TestCase):
    def test_import_status_and_missing_tools_never_contact_device(self):
        calls = []
        manager = live.LiveVideo("/adb", "192.168.1.10:5555", "/missing/server", {},
            lambda: calls.append("identity"), lambda owner: True,
            runner=lambda *a, **k: calls.append("adb"))
        self.assertEqual(manager.status(OWNER), {"state": "stopped", "videoOnly": True})
        self.assertFalse(manager.active())
        with self.assertRaises(live.LiveError) as caught:
            manager.start(OWNER)
        self.assertEqual(caught.exception.code, "live_server_missing")
        self.assertEqual(calls, [])

    def test_local_jar_hash_is_required_before_optional_ffmpeg(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "server"
            jar.write_bytes(b"public synthetic server")
            manager = live.LiveVideo("/adb", "192.168.1.10:5555", jar, {}, lambda: None, lambda owner: True)
            with self.assertRaises(live.LiveError) as caught:
                manager._dependencies()
            self.assertEqual(caught.exception.code, "live_server_invalid")
            with patch.object(live, "SERVER_SHA256", hashlib.sha256(jar.read_bytes()).hexdigest()), \
                    patch.object(live, "ffmpeg_executable", return_value=None):
                with self.assertRaises(live.LiveError) as caught:
                    manager._dependencies()
                self.assertEqual(caught.exception.code, "live_dependency_missing")

    def test_one_owner_idempotent_start_and_other_owner_cannot_stop(self):
        manager = ControlledLive()
        try:
            first, cookie = manager.start(OWNER)
            self.assertTrue(manager.started.wait(1))
            again, repeated = manager.start(OWNER)
            self.assertEqual(first["playlist"], again["playlist"])
            self.assertEqual(cookie, repeated)
            self.assertEqual(manager.workers, 1)
            self.assertIn("HttpOnly; SameSite=Strict", cookie)
            self.assertNotIn("Domain=", cookie)
            self.assertNotIn(manager.session.cookie, first["playlist"])
            self.assertEqual(len(manager.session.identifier), 32)
            with self.assertRaises(live.LiveError) as caught:
                manager.start(OTHER)
            self.assertEqual((caught.exception.code, caught.exception.status), ("live_in_use", 409))
            with self.assertRaises(live.LiveError) as caught:
                manager.stop(OTHER)
            self.assertEqual(caught.exception.status, 403)
            self.assertTrue(manager.active())
            stopped, clear = manager.stop(OWNER)
            self.assertEqual(stopped["state"], "stopped")
            self.assertIn("Max-Age=0", clear)
            self.assertTrue(manager.session.done.is_set())
        finally:
            manager.close()

    def test_status_does_not_extend_lease_keepalive_does_and_hard_limit_wins(self):
        manager = ControlledLive()
        try:
            manager.start(OWNER)
            self.assertTrue(manager.started.wait(1))
            manager.now[0] = 10
            manager.status(OWNER)
            self.assertEqual(manager.session.lease, 30)
            manager.keepalive(OWNER)
            self.assertEqual(manager.session.lease, 40)
            manager.now[0] = 41
            self.assertEqual(manager.keepalive(OWNER)[0]["state"], "stopped")
            self.assertTrue(manager.session.stop.is_set())
            self.assertFalse(manager.active())
        finally:
            manager.close()
        manager = ControlledLive()
        try:
            manager.start(OWNER)
            self.assertTrue(manager.started.wait(1))
            manager.session.lease = 10000
            manager.now[0] = live.MAX_SECONDS
            self.assertFalse(manager.active())
        finally:
            manager.close()

    def test_parent_session_expiry_invalidates_cookie_media_and_stream(self):
        manager = ControlledLive()
        try:
            manager.start(OWNER)
            self.assertTrue(manager.started.wait(1))
            manager.parents[OWNER] = False
            self.assertFalse(manager.active())
            job = manager.session
            with self.assertRaises(live.LiveError) as caught:
                manager.media(job.identifier, "index.m3u8", job.cookie)
            self.assertEqual(caught.exception.status, 401)
        finally:
            manager.close()


class MediaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.manager = live.LiveVideo("/adb", "192.168.1.10:5555", "/server", {}, lambda: None, lambda owner: owner == OWNER,
                                     clock=lambda: 1)
        self.job = live.Session(OWNER, 0)
        self.job.state = "ready"
        self.job.directory = self.temp.name
        self.manager.session = self.job
        self.path = Path(self.temp.name) / "part000000.ts"
        self.path.write_bytes(b"synthetic video bytes")
        self.path.chmod(0o600)
        playlist = Path(self.temp.name) / "index.m3u8"
        playlist.write_text("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\npart000000.ts\n")
        playlist.chmod(0o600)
    def tearDown(self): self.temp.cleanup()
    def media(self, name="part000000.ts", cookie=None, raw_range=None):
        return self.manager.media(self.job.identifier, name, self.job.cookie if cookie is None else cookie, raw_range)

    def test_cookie_is_required_and_does_not_replace_api_bearer_auth(self):
        headers = HTTPMessage()
        with self.assertRaises(live.LiveError): live.cookie_value(headers)
        headers.add_header("Cookie", "other=value; nepal_live=" + self.job.cookie)
        self.assertEqual(live.cookie_value(headers), self.job.cookie)
        headers.add_header("Cookie", "nepal_live=" + self.job.cookie)
        with self.assertRaises(live.LiveError): live.cookie_value(headers)
        with self.assertRaises(live.LiveError): self.media(cookie="x" * 43)
        with self.assertRaises(live.LiveError):
            self.manager.media("0" * 32, "index.m3u8", self.job.cookie)

    def test_bounded_regular_file_assets_ranges_and_no_lease_renewal(self):
        code, body, mime, headers = self.media(raw_range="bytes=0-8")
        self.assertEqual((code, body, mime), (206, b"synthetic", "video/mp2t"))
        self.assertIn(("Content-Range", "bytes 0-8/21"), headers)
        self.assertEqual(self.media(raw_range="bytes=-5")[1], b"bytes")
        self.assertEqual(self.media("index.m3u8")[2], "application/vnd.apple.mpegurl")
        self.assertEqual(self.job.lease, 30)
        for value in ("bytes=99-", "bytes=5-1", "bytes=0-1,4-9", "garbage", "bytes=-0"):
            with self.subTest(value=value), self.assertRaises(live.LiveError) as caught:
                self.media(raw_range=value)
            self.assertEqual(caught.exception.status, 416)

    def test_path_symlink_permissions_and_rolling_buffer_limits(self):
        for name in ("../secret", "part000000.ts.tmp", "part1.ts", "http://example.com/a.ts"):
            with self.assertRaises(live.LiveError): self.media(name)
        self.path.chmod(0o644)
        with self.assertRaises(live.LiveError): self.media()
        self.path.unlink()
        self.path.symlink_to(Path(self.temp.name) / "index.m3u8")
        with self.assertRaises(live.LiveError): self.media()
        self.path.unlink()
        for i in range(17):
            p = Path(self.temp.name) / f"part{i:06d}.ts"
            p.write_bytes(b"x")
        with self.assertRaises(live.LiveError) as caught:
            self.manager._check_buffer(self.job)
        self.assertEqual(caught.exception.code, "live_buffer_limit")


class TransportTests(unittest.TestCase):
    def manager(self, **options):
        return live.LiveVideo("/fixed/adb", "192.168.1.10:5555", "/verified/server", {"SAFE": "yes"},
                              lambda: None, lambda owner: True, **options)

    def test_live_identity_waits_for_status_lock_then_freshly_checks_fixed_model(self):
        model = base.FIRE["model"] if hasattr(base, "FIRE") else server.TV_MODEL
        serial = base.serial() if hasattr(base, "serial") else server.TV_SERIAL
        calls, errors = [], []
        def run(argv, **options):
            calls.append(argv)
            value = "device" if argv[-1] == "get-state" else model
            return types.SimpleNamespace(returncode=0, stdout=value)
        args = ("/fixed/adb", base.FIRE) if hasattr(base, "FIRE") else ("/fixed/adb",)
        bridge = server.AdbBridge(*args, runner=run)
        attempting = threading.Event()
        class ContendedLock:
            def __init__(self):
                self.lock = threading.Lock()
                self.lock.acquire()
                self.timeout = None
            def acquire(self, *, timeout):
                self.timeout = timeout
                attempting.set()
                return self.lock.acquire(timeout=timeout)
            def release(self): self.lock.release()
        guard = ContendedLock()
        bridge.command_lock = guard
        def check():
            try: bridge.live_identity()
            except Exception as error: errors.append(error)
        worker = threading.Thread(target=check)
        worker.start()
        self.assertTrue(attempting.wait(1))
        self.assertEqual(calls, [], "No device I/O runs until the existing status probe releases its lock")
        self.assertEqual(guard.timeout, 3)
        guard.release()
        worker.join(1)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual([call[1:] for call in calls], [
            ["-s", serial, "get-state"], ["-s", serial, "shell", "getprop", "ro.product.model"]])
        self.assertFalse(guard.lock.locked())

    def test_live_identity_contention_deadline_and_wrong_model_do_not_reconnect_or_capture(self):
        calls = []
        def run(argv, **options):
            calls.append(argv)
            return types.SimpleNamespace(returncode=0, stdout="device" if argv[-1] == "get-state" else "WrongModel")
        args = ("/fixed/adb", base.FIRE) if hasattr(base, "FIRE") else ("/fixed/adb",)
        bridge = server.AdbBridge(*args, runner=run)
        class UnavailableLock:
            def acquire(self, *, timeout):
                self.timeout = timeout
                return False
        guard = UnavailableLock()
        bridge.command_lock = guard
        with self.assertRaises(server.ApiError) as caught:
            bridge.live_identity()
        self.assertEqual((caught.exception.status, caught.exception.code), (429, "live_busy"))
        self.assertEqual(guard.timeout, 3)
        self.assertEqual(calls, [])
        bridge.command_lock = threading.Lock()
        with self.assertRaises(server.ApiError) as caught:
            bridge.live_identity()
        self.assertEqual(caught.exception.code, "tv_unavailable")
        self.assertFalse(any("connect" in argv or "push" in argv or "forward" in argv or "input" in argv for argv in calls))
        self.assertFalse(bridge.command_lock.locked())

    def test_process_arguments_are_fixed_no_control_wake_audio_or_public_listener(self):
        manager, job = self.manager(), live.Session(OWNER, 0)
        job.directory = "/private/ephemeral/session"
        command = manager._command(job)
        self.assertEqual(command[:4], ["/fixed/adb", "-s", "192.168.1.10:5555", "shell"])
        self.assertIn("power_on=false", command[-1])
        self.assertIn("control=false", command[-1])
        self.assertIn("audio=false", command[-1])
        self.assertIn("raw_stream=true", command[-1])
        self.assertIn("video_codec_options=i-frame-interval=1", command[-1])
        ffmpeg = manager._ffmpeg_command(job, "/fixed/ffmpeg")
        for value in ("libx264", "fps=20", "zerolatency", "delete_segments+temp_file+independent_segments"):
            self.assertIn(value, ffmpeg)
        self.assertNotIn("-c:v copy", " ".join(ffmpeg))
        self.assertNotIn("0.0.0.0", " ".join(command + ffmpeg))

    def test_sparse_h264_low_delay_decoder_flags_apply_before_input(self):
        manager, job = self.manager(), live.Session(OWNER, 0)
        job.directory = "/private/ephemeral/session"
        command = manager._decoder_command("/fixed/ffmpeg")
        decoder, output = command[:command.index("-i")], command[command.index("-i") + 2:]
        expected = {"-probesize": "32768", "-analyzeduration": "100000", "-fpsprobesize": "0",
                    "-threads": "1", "-flags": "low_delay", "-fflags": "+genpts"}
        for name, value in expected.items():
            self.assertIn(name, decoder)
            self.assertEqual(decoder[decoder.index(name) + 1], value)
            self.assertNotIn(name, output)
        # Direct I/O and probing-packet disposal lost initial codec data in live checks.
        self.assertNotIn("-avioflags", command)
        self.assertNotIn("-blocksize", command)
        self.assertFalse(any("nobuffer" in value for value in command))
        self.assertIn("passthrough", output)
        self.assertIn("pad=1280:720", " ".join(output))
        encoder = manager._ffmpeg_command(job, "/fixed/ffmpeg")
        before_input = encoder[:encoder.index("-i")]
        for name, value in (("-f", "rawvideo"), ("-pixel_format", "yuv420p"),
                            ("-video_size", "1280x720"), ("-framerate", "20")):
            self.assertEqual(before_input[before_input.index(name) + 1], value)
        self.assertIn("zerolatency", encoder)
        self.assertIn("fps=20", encoder)

    def test_connect_retries_initial_eof_then_keeps_actual_h264_socket(self):
        class Connection:
            def __init__(self, payload): self.payload = payload; self.closed = False
            def settimeout(self, timeout): pass
            def recv(self, maximum): return self.payload
            def close(self): self.closed = True
        first, second = Connection(b""), Connection(b"\x00\x00\x00\x01h264")
        connections, addresses = [first, second], []
        def connect(address, timeout):
            addresses.append((address, timeout))
            return connections.pop(0)
        manager = self.manager(socket_factory=connect)
        job = live.Session(OWNER, manager.clock()); job.port = 12345
        connection, payload = manager._connect(job)
        self.assertIs(connection, second)
        self.assertTrue(first.closed)
        self.assertEqual(payload, second.payload)
        self.assertTrue(all(address == ("127.0.0.1", 12345) for address, timeout in addresses))

    def test_connect_retains_accepted_socket_while_waiting_for_first_encoded_frame(self):
        now, calls = [0.0], []
        class Connection:
            def __init__(self): self.reads = 0; self.closed = False
            def settimeout(self, timeout): pass
            def recv(self, maximum):
                self.reads += 1
                if self.reads == 1:
                    now[0] += .5
                    raise socket.timeout()
                return b"\x00\x00\x00\x01ready-h264"
            def close(self): self.closed = True
        connection = Connection()
        def connect(address, timeout):
            calls.append(address)
            return connection
        manager = self.manager(clock=lambda: now[0], socket_factory=connect)
        job = live.Session(OWNER, 0); job.port = 12345
        accepted, first = manager._connect(job)
        self.assertIs(accepted, connection)
        self.assertEqual(connection.reads, 2)
        self.assertEqual(first, b"\x00\x00\x00\x01ready-h264")
        self.assertFalse(connection.closed)
        self.assertEqual(calls, [("127.0.0.1", 12345)])

    def test_first_frame_deadline_closes_slow_socket_without_reconnecting(self):
        now, calls = [0.0], []
        class Connection:
            closed = False
            def settimeout(self, timeout): self.timeout = timeout
            def recv(self, maximum):
                now[0] += self.timeout
                raise socket.timeout()
            def close(self): self.closed = True
        connection = Connection()
        def connect(address, timeout):
            calls.append(address)
            return connection
        manager = self.manager(clock=lambda: now[0], socket_factory=connect)
        job = live.Session(OWNER, 0); job.port = 12345
        with self.assertRaises(live.LiveError) as caught:
            manager._connect(job)
        self.assertEqual(caught.exception.code, "live_start_timeout")
        self.assertTrue(connection.closed)
        self.assertEqual(len(calls), 1)
        self.assertEqual(now[0], 12)

    def test_sparse_source_repeats_one_complete_frame_and_stop_joins_all_pipeline_threads(self):
        class Connection:
            def __init__(self): self.first = True; self.closed = False
            def settimeout(self, value): pass
            def recv(self, maximum):
                if self.closed: return b""
                if self.first:
                    self.first = False
                    return b"\x00\x00\x00\x01synthetic-h264"
                time.sleep(.001)
                raise socket.timeout()
            def shutdown(self, how): self.closed = True
            def close(self): self.closed = True
        class FrameOutput:
            def __init__(self, stopped): self.offset = 0; self.stopped = stopped
            def readinto(self, target):
                if self.offset >= live.FRAME_BYTES:
                    self.stopped.wait(3)
                    return 0
                # Partial reads must be assembled, never published as a short frame.
                length = min(len(target), 65536, live.FRAME_BYTES - self.offset)
                target[:length] = b"y" * length
                self.offset += length
                return length
            def close(self): self.stopped.set()
        class Process:
            def __init__(self, stdout=b""):
                self.stdout = io.BytesIO(stdout)
                self.stderr = io.BytesIO()
                self.stdin = None
                self.returncode = None
                self.stopped = threading.Event()
            def poll(self): return self.returncode
            def terminate(self): self.returncode = -15; self.stopped.set()
            def wait(self, timeout): return self.returncode
        commands, processes, encoded_frames, source_chunks = [], [], [], []
        def run(argv, **options):
            commands.append(argv)
            value = "12345" if argv[-2:] == ["tcp:0", "localabstract:scrcpy_" + manager.session.scid] else ""
            return types.SimpleNamespace(returncode=0, stdout=value)
        def popen(argv, **options):
            self.assertFalse(options["shell"])
            if "app_process" in argv[-1]:
                process = Process(b"NEPAL_PID=321\n")
            else:
                self.assertEqual(options["umask"], 0o077)
                process = Process()
                is_decoder = argv[-1] == "pipe:1"
                if is_decoder:
                    process.stdout = FrameOutput(process.stopped)
                class Input:
                    def write(self, data):
                        if is_decoder:
                            source_chunks.append(bytes(data))
                        else:
                            encoded_frames.append((len(data), data[0], data[-1]))
                            folder = Path(manager.session.directory)
                            for name, content in (("index.m3u8", b"#EXTM3U\npart000000.ts\n"), ("part000000.ts", b"fake media")):
                                path = folder / name
                                path.write_bytes(content)
                                path.chmod(0o600)
                    def flush(self): pass
                    def close(self): pass
                process.stdin = Input()
            processes.append(process)
            return process
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "public-server"
            jar.write_bytes(b"synthetic verified jar")
            manager = live.LiveVideo("/fixed/adb", "192.168.1.10:5555", jar, {}, lambda: None,
                lambda owner: True, runner=run, popen=popen, socket_factory=lambda *a, **k: Connection(), temp_root=directory)
            with patch.object(live, "SERVER_SHA256", hashlib.sha256(jar.read_bytes()).hexdigest()), \
                    patch.object(live, "ffmpeg_executable", return_value="/fake/ffmpeg"):
                try:
                    manager.start(OWNER)
                    deadline = time.monotonic() + 2
                    while len(encoded_frames) < 4 and time.monotonic() < deadline:
                        time.sleep(.005)
                    self.assertEqual(manager.status(OWNER)["state"], "ready")
                    job = manager.session
                    self.assertEqual(job.decoded_frames, 1)
                    self.assertEqual(len(source_chunks), 1)
                    self.assertGreaterEqual(len(encoded_frames), 4)
                    self.assertEqual(set(encoded_frames), {(live.FRAME_BYTES, ord("y"), ord("y"))})
                    folder = Path(job.directory)
                    self.assertEqual(folder.stat().st_mode & 0o777, 0o700)
                    self.assertEqual(manager.media(job.identifier, "part000000.ts", job.cookie)[1], b"fake media")
                    manager.stop(OWNER)
                    self.assertTrue(job.connection.closed)
                    self.assertFalse(folder.exists())
                    self.assertIsNone(job.latest_frame)
                    self.assertTrue(all(process.returncode is not None for process in processes))
                    for thread in (job.thread, job.source_thread, job.frame_thread,
                                   job.decoder_log_thread, job.encoder_log_thread, job.log_thread):
                        self.assertFalse(thread.is_alive())
                    self.assertTrue(any(argv[-3:] == ["forward", "--remove", "tcp:12345"] for argv in commands))
                    self.assertTrue(any(argv[-4:] == ["shell", "rm", "-f", job.remote_jar] for argv in commands))
                    self.assertFalse(any("connect" in argv or "input" in argv for argv in commands))
                finally:
                    manager.close()

    def test_source_eof_and_short_decoded_frame_fail_instead_of_streaming_stale_frame(self):
        manager = self.manager()
        for failure in ("source", "frame"):
            with self.subTest(failure=failure):
                job = live.Session(OWNER, manager.clock())
                job.connection = types.SimpleNamespace(recv=lambda maximum: b"", shutdown=lambda how: None, close=lambda: None)
                job.decoder = types.SimpleNamespace(stdin=io.BytesIO(), stdout=io.BytesIO(b"short"), poll=lambda: 0)
                job.latest_frame = bytearray(live.FRAME_BYTES)
                if failure == "source":
                    manager._feed_source(job, b"h264")
                    self.assertEqual(job.error, "live_connection_lost")
                else:
                    manager._read_frames(job)
                    self.assertEqual(job.error, "live_process_failed")
                    self.assertEqual(job.decoded_frames, 0)
                self.assertTrue(job.stop.is_set())

    def test_watchdog_interrupts_both_codecs_when_no_first_frame_arrives(self):
        manager = self.manager(clock=lambda: 13)
        job = live.Session(OWNER, 0)
        job.startup_deadline = 12
        terminated = []
        for name in ("server", "decoder", "encoder"):
            setattr(job, name, types.SimpleNamespace(poll=lambda: None,
                terminate=lambda name=name: terminated.append(name)))
        manager._watch(job)
        self.assertEqual(job.error, "live_start_timeout")
        self.assertTrue(job.stop.is_set())
        self.assertEqual(set(terminated), {"server", "decoder", "encoder"})

    def test_cleanup_terminates_reaps_children_and_only_our_forward_jar_pid(self):
        class Process:
            def __init__(self): self.returncode = None; self.terminated = False; self.reaped = False
            def poll(self): return self.returncode
            def terminate(self): self.terminated = True; self.returncode = -15
            def wait(self, timeout): self.reaped = True; return self.returncode
        commands = []
        def run(argv, **options):
            self.assertFalse(options["shell"])
            self.assertEqual(options["timeout"], 1)
            commands.append(argv)
            return types.SimpleNamespace(returncode=0)
        manager = self.manager(runner=run)
        job = live.Session(OWNER, 0); job.port = 12345; job.remote_pid = 321
        job.server, job.decoder, job.encoder = Process(), Process(), Process()
        with tempfile.TemporaryDirectory() as directory:
            job.directory = str(Path(directory) / "owned"); Path(job.directory).mkdir(mode=0o700)
            manager._cleanup(job)
            self.assertFalse(Path(job.directory).exists())
        self.assertTrue(job.server.terminated and job.server.reaped)
        self.assertTrue(job.encoder.terminated and job.encoder.reaped)
        self.assertTrue(job.decoder.terminated and job.decoder.reaped)
        self.assertEqual(commands[1][-3:], ["forward", "--remove", "tcp:12345"])
        self.assertEqual(commands[2][-4:], ["shell", "rm", "-f", job.remote_jar])
        self.assertIn("scid=" + job.scid, commands[0][-1])
        self.assertNotIn("killall", commands[0][-1])
        manager._cleanup(job)
        self.assertEqual(len(commands), 3)


class LiveRequestTests(unittest.TestCase):
    setUp = base.RequestTests.setUp
    tearDown = base.RequestTests.tearDown
    request = base.RequestTests.request

    def fake_live(self):
        calls = []
        result = {"state": "starting", "playlist": "/api/live/" + "a" * 32 + "/index.m3u8", "videoOnly": True}
        def control(action):
            return lambda owner: (calls.append((action, owner)) or dict(result, state="stopped" if action == "stop" else "starting"),
                                  "nepal_live=" + "b" * 43 + "; Path=/api/live/" + "a" * 32 + "/; Max-Age=40; HttpOnly; SameSite=Strict")
        self.app.live = types.SimpleNamespace(start=control("start"), keepalive=control("keepalive"), stop=control("stop"),
            active=lambda: False, status=lambda owner: calls.append(("status", owner)) or result,
            media=lambda *args: calls.append(("media", args)) or (200, b"#EXTM3U\n", "application/vnd.apple.mpegurl", []))
        return calls

    def test_authenticated_controls_exact_body_and_get_head_never_start(self):
        calls = self.fake_live()
        for action in ("start", "keepalive", "stop"):
            path = "/api/live/" + action
            self.assertEqual(self.request("POST", path, {})[0], 401)
            self.assertEqual(self.request("POST", path, {"serial": "other"}, auth=True)[0], 400)
            self.assertEqual(self.request("POST", path, [], auth=True)[0], 400)
            self.assertEqual(self.request(path=path, auth=True)[0], 404)
            self.assertEqual(self.request("HEAD", path, auth=True)[0], 404)
        self.assertEqual(calls, [])
        code, headers, body = self.request("POST", "/api/live/start", {}, auth=True)
        self.assertEqual(code, 200)
        self.assertIn("Set-Cookie: nepal_live=", headers)
        self.assertTrue(json.loads(body)["videoOnly"])
        self.assertEqual(self.request(path="/api/live/status", auth=True)[0], 200)
        self.assertEqual(self.request(path="/api/live/status")[0], 401)

    def test_media_cookie_scope_origin_metadata_head_and_no_bearer_substitution(self):
        calls = self.fake_live()
        path = "/api/live/" + "a" * 32 + "/index.m3u8"
        cookie = ("Cookie", "nepal_live=" + "b" * 43)
        self.assertEqual(self.request(path=path, auth=True)[0], 401)
        for headers in ([cookie, ("Origin", "http://attacker.example")],
                        [cookie, ("Referer", "http://attacker.example/page")],
                        [cookie, ("Sec-Fetch-Site", "same-site")],
                        [cookie, ("Sec-Fetch-Site", "cross-site")]):
            self.assertEqual(self.request(path=path, headers=headers)[0], 403)
        self.assertEqual(calls, [])
        code, headers, body = self.request(path=path, headers=[cookie])
        self.assertEqual((code, body), (200, b"#EXTM3U\n"))
        self.assertIn("Cache-Control: no-store", headers)
        self.assertEqual(self.request("HEAD", path, headers=[cookie])[2], b"")
        self.assertTrue(all(name == "media" for name, _ in calls))
        self.assertEqual(self.request(path=path + "?token=hidden", headers=[cookie])[0], 404)

    def test_preview_and_live_start_have_atomic_admission(self):
        self.fake_live()
        self.app.capture_mode_lock.acquire()
        try:
            self.assertEqual(self.request("POST", "/api/live/start", {}, auth=True)[0], 409)
            self.assertEqual(self.request("POST", "/api/preview/frame", {}, auth=True)[0], 429)
        finally:
            self.app.capture_mode_lock.release()
        self.app.live.active = lambda: True
        self.assertEqual(self.request("POST", "/api/preview/frame", {}, auth=True)[0], 409)

    def test_parent_session_lookup_expires_without_renewal(self):
        now = [0.0]
        pairing = server.Pairing(pin="123456", clock=lambda: now[0])
        token = pairing.pair("123456", "owner")["token"]
        identifier = pairing.authorize("Bearer " + token, "owner")
        self.assertTrue(pairing.active(identifier))
        self.assertFalse(pairing.active(token))
        now[0] = pairing.lifetime
        self.assertFalse(pairing.active(identifier))
