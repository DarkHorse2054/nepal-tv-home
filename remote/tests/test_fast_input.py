"""Offline fixed-input and lifecycle tests; every device transport is fake."""
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("nepal_fast_input_tests", Path(__file__).resolve().parents[1] / "fast_input.py")
fast = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fast)


class FakeSocket:
    def __init__(self, first=b"\x00"):
        self.first = first
        self.sent = []
        self.shutdown_event = threading.Event()
        self.closed = False
        self.fail_send = False
        self.handshake_timeouts = 0

    def settimeout(self, value):
        self.timeout = value

    def setsockopt(self, *args):
        raise AssertionError("AF_UNIX must not receive TCP socket options")

    def connect(self, path):
        self.path = path

    def recv(self, size):
        if self.first is not None:
            if self.handshake_timeouts:
                self.handshake_timeouts -= 1
                raise socket.timeout()
            value, self.first = self.first, None
            return value
        if self.shutdown_event.wait(.005):
            return b""
        raise socket.timeout()

    def sendall(self, data):
        self.sent.append(data)
        if self.fail_send:
            raise BrokenPipeError("Synthetic partial write")

    def shutdown(self, how):
        self.shutdown_event.set()

    def close(self):
        self.closed = True
        self.shutdown_event.set()


class FakeProcess:
    def __init__(self):
        self.stdout = io.BytesIO(b"NEPAL_INPUT_PID=1234\n")
        self.returncode = None
        self.terminated = self.killed = 0

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated += 1
        self.returncode = 0

    def kill(self):
        self.killed += 1
        self.returncode = -9

    def wait(self, timeout):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake-process", timeout)
        return self.returncode


class FastInputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir="/tmp")
        self.jar = Path(self.temp.name) / "server"
        self.jar.write_bytes(b"Offline synthetic server bytes")
        self.hash_patch = patch.object(fast, "SERVER_SHA256", hashlib.sha256(self.jar.read_bytes()).hexdigest())
        self.hash_patch.start()
        self.now = [0.0]
        self.calls, self.processes, self.connections, self.managers = [], [], [], []
        self.socket = FakeSocket()

    def tearDown(self):
        for manager in self.managers:
            manager.close()
            if manager.session:
                self.assertTrue(manager.session.done.is_set(), "The worker must retire")
                self.assertFalse(manager.session.thread.is_alive())
        self.hash_patch.stop()
        self.temp.cleanup()

    def runner(self, argv, **kwargs):
        self.assertFalse(kwargs["shell"])
        self.assertLessEqual(kwargs["timeout"], 5)
        self.assertEqual(argv[:3], ["/fixed/adb", "-s", "192.0.2.8:5555"])
        self.calls.append(argv)
        return types.SimpleNamespace(returncode=0, stdout="")

    def popen(self, argv, **kwargs):
        self.assertFalse(kwargs["shell"])
        self.assertTrue(kwargs["start_new_session"])
        process = FakeProcess()
        self.processes.append((argv, process))
        return process

    def connect(self, path, timeout):
        self.connections.append(path)
        self.assertIsInstance(path, str)
        self.assertTrue(path.endswith("/control.sock"))
        self.assertLessEqual(len(os.fsencode(path)), 100)
        self.assertEqual(os.stat(Path(path).parent).st_mode & 0o777, 0o700)
        self.assertLessEqual(timeout, .5)
        return self.socket

    def manager(self, identity=lambda: None, **kwargs):
        params = dict(runner=self.runner, popen=self.popen, socket_factory=self.connect, clock=lambda: self.now[0], temp_root=self.temp.name)
        params.update(kwargs)
        manager = fast.FastInput("/fixed/adb", "192.0.2.8:5555", self.jar, {"FIXED_ENV": "yes"}, identity, (3, 19, 21, 22, 23), **params)
        self.managers.append(manager)
        return manager

    def ready(self, manager):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if manager.session.ready:
                return
            if manager.session.done.is_set():
                self.fail("Worker retired before becoming ready")
            time.sleep(.002)
        self.fail("Worker failed to become ready")

    def test_construction_missing_readiness_and_unknown_codes_do_no_io(self):
        manager = self.manager()
        self.assertFalse(manager.send(22))
        for code in (None, True, "22", "22; reboot", -1, 999):
            with self.assertRaises(fast.InputError) as caught:
                manager.send(code)
            self.assertEqual((caught.exception.code, caught.exception.status), ("invalid_control", 400))
        self.assertEqual((self.calls, self.processes, self.connections), ([], [], []))
        manager.close()
        self.assertFalse(manager.warm())
        self.assertFalse(manager.send(22))

    def test_only_one_worker_and_correct_key_pairs_reuse_connection(self):
        entered, release = threading.Event(), threading.Event()
        def identity():
            entered.set()
            self.assertTrue(release.wait(1))
        manager = self.manager(identity)
        self.assertFalse(manager.warm())
        self.assertTrue(entered.wait(1))
        self.assertFalse(manager.warm())
        self.assertEqual(self.calls, [], "No setup can precede identity verification")
        self.assertFalse(manager.send(22))
        release.set()
        self.ready(manager)
        self.assertTrue(manager.warm())
        self.assertTrue(manager.send(22))
        self.now[0] = 25
        self.assertTrue(manager.send(21))
        self.assertEqual(manager.session.lease, 85)
        self.assertEqual(len(self.processes), 1)
        self.assertEqual(len(self.connections), 1)
        self.assertEqual(self.socket.sent, [struct.pack(">BBIII", 0, 0, code, 0, 0) + struct.pack(">BBIII", 0, 1, code, 0, 0) for code in (22, 21)])
        self.assertTrue(all(len(packet) == 28 for packet in self.socket.sent))

    def test_hash_and_identity_failure_never_push_or_connect_and_apply_cooldown(self):
        for reason in ("hash", "identity"):
            with self.subTest(reason=reason):
                calls_before = len(self.calls)
                manager = self.manager(lambda: False)
                with patch.object(fast, "SERVER_SHA256", "0" * 64 if reason == "hash" else hashlib.sha256(self.jar.read_bytes()).hexdigest()):
                    manager.warm()
                    self.assertTrue(manager.session.done.wait(1))
                self.assertEqual(len(self.calls), calls_before)
                self.assertEqual(self.connections, [])
                old = manager.session
                self.assertFalse(manager.warm())
                self.assertIs(manager.session, old)
                self.now[0] += 11
                manager.close()

    def test_control_only_flags_and_owned_cleanup(self):
        manager = self.manager()
        manager.warm()
        self.ready(manager)
        job = manager.session
        command = self.processes[0][0]
        self.assertEqual(command[:4], ["/fixed/adb", "-s", "192.0.2.8:5555", "shell"])
        for option in ("video=false", "audio=false", "control=true", "power_on=false", "clipboard_autosync=false", "keep_active=false", "cleanup=false", "send_device_meta=false", "send_dummy_byte=true"):
            self.assertIn(option, command[4])
        self.assertNotIn("raw_stream=true", command[4])
        self.assertTrue(any(call[3:] == ["forward", job.local_endpoint, "localabstract:scrcpy_" + job.scid] for call in self.calls))
        self.assertTrue(job.local_endpoint.startswith("localfilesystem:"))
        self.assertFalse(any("tcp:" in str(arg) for call in self.calls for arg in call))
        manager.close()
        self.assertTrue(self.socket.closed)
        self.assertTrue(self.processes[0][1].stdout.closed)
        self.assertTrue(any(call[3:] == ["forward", "--remove", job.local_endpoint] for call in self.calls))
        self.assertFalse(Path(job.directory).exists())
        self.assertTrue(any(call[3:] == ["shell", "rm", "-f", job.remote_jar] for call in self.calls))
        kills = [call[4] for call in self.calls if call[3] == "shell" and "kill " in call[4]]
        self.assertEqual(len(kills), 1)
        self.assertIn("/proc/1234/cmdline", kills[0])
        self.assertIn("scid=" + job.scid, kills[0])
        self.assertNotIn("pkill", kills[0])

    def test_failed_send_is_uncertain_and_must_not_fall_back_or_replay(self):
        manager = self.manager()
        manager.warm()
        self.ready(manager)
        self.socket.fail_send = True
        with self.assertRaises(fast.InputError) as caught:
            manager.send(22)
        self.assertEqual(caught.exception.code, "input_send_uncertain")
        self.assertTrue(manager.session.done.wait(1))
        self.assertEqual(len(self.socket.sent), 1)
        self.assertFalse(manager.send(22))
        manager.warm()
        self.assertEqual(len(self.processes), 1)

    def test_idle_lease_releases_resources_and_warm_refreshes_only_existing_session(self):
        manager = self.manager()
        manager.warm()
        self.ready(manager)
        self.now[0] = 55
        self.assertTrue(manager.warm())
        self.assertEqual(manager.session.lease, 115)
        self.now[0] = 116
        self.assertFalse(manager.send(22))
        self.assertTrue(manager.session.done.wait(1))
        self.assertTrue(self.socket.closed)
        self.assertEqual(self.socket.sent, [])

    def test_eof_process_exit_and_bad_handshake_retire_without_sending(self):
        for reason in ("eof", "process", "handshake"):
            with self.subTest(reason=reason):
                self.socket = FakeSocket(first=b"wrong" if reason == "handshake" else b"\x00")
                manager = self.manager()
                manager.warm()
                if reason != "handshake":
                    self.ready(manager)
                    if reason == "eof":
                        self.socket.shutdown_event.set()
                    else:
                        self.processes[-1][1].returncode = 1
                self.assertTrue(manager.session.done.wait(1))
                self.assertFalse(manager.send(22))
                self.assertTrue(self.socket.closed)
                self.assertEqual(self.socket.sent, [])
                manager.close()
                self.now[0] += 11

    def test_handshake_timeout_retains_same_socket_until_server_ready(self):
        self.socket.handshake_timeouts = 2
        manager = self.manager()
        manager.warm()
        self.ready(manager)
        self.assertEqual(len(self.connections), 1)
        self.assertTrue(manager.send(19))

    def test_close_during_identity_or_push_prevents_late_connection(self):
        for phase in ("identity", "push"):
            with self.subTest(phase=phase):
                entered, release = threading.Event(), threading.Event()
                def pause():
                    entered.set()
                    self.assertTrue(release.wait(1))
                def runner(argv, **kwargs):
                    if argv[3] == "push":
                        pause()
                    return self.runner(argv, **kwargs)
                manager = self.manager(pause if phase == "identity" else lambda: None,
                                       runner=runner if phase == "push" else self.runner)
                manager.warm()
                self.assertTrue(entered.wait(1))
                closer = threading.Thread(target=manager.close)
                closer.start()
                deadline = time.monotonic() + 1
                while not manager.closed and time.monotonic() < deadline:
                    time.sleep(.002)
                release.set()
                closer.join(2)
                self.assertFalse(closer.is_alive())
                self.assertTrue(manager.session.done.is_set())
                self.assertFalse(manager.warm())
                self.assertEqual(self.connections, [])
                self.assertEqual(self.processes, [])

    def test_push_failure_cleans_partial_owned_file_without_starting_server(self):
        def failure(argv, **kwargs):
            self.runner(argv, **kwargs)
            if argv[3] == "push":
                raise subprocess.TimeoutExpired("fake-push", 5)
            return types.SimpleNamespace(returncode=0, stdout="")
        manager = self.manager(runner=failure)
        manager.warm()
        self.assertTrue(manager.session.done.wait(1))
        self.assertEqual(self.processes, [])
        self.assertEqual(self.calls[-1][3:], ["shell", "rm", "-f", manager.session.remote_jar])

    def test_failed_forward_ack_removes_only_exact_owned_unix_mapping(self):
        def failure(argv, **kwargs):
            self.runner(argv, **kwargs)
            if argv[3] == "forward" and argv[4].startswith("localfilesystem:"):
                raise subprocess.TimeoutExpired("fake-forward", 3)
            return types.SimpleNamespace(returncode=0, stdout="")
        manager = self.manager(runner=failure)
        manager.warm()
        self.assertTrue(manager.session.done.wait(1))
        removals = [call[3:] for call in self.calls if call[3:5] == ["forward", "--remove"]]
        self.assertEqual(removals, [["forward", "--remove", manager.session.local_endpoint]])
        self.assertFalse(Path(manager.session.directory).exists())
        self.assertEqual(self.processes, [])

    def test_worker_thread_start_failure_remains_safe_to_close(self):
        manager = self.manager()
        with patch.object(threading.Thread, "start", side_effect=RuntimeError("Synthetic thread limit")):
            self.assertFalse(manager.warm())
        self.assertTrue(manager.session.done.is_set())
        manager.close()
        self.assertEqual((self.calls, self.processes, self.connections), ([], [], []))

    def test_log_thread_start_failure_still_reaps_process_and_owned_resources(self):
        manager = self.manager()
        original = threading.Thread.start
        def start(thread):
            if thread.name == "nepal-input-log":
                raise RuntimeError("Synthetic log thread limit")
            return original(thread)
        with patch.object(threading.Thread, "start", start):
            manager.warm()
            self.assertTrue(manager.session.done.wait(1))
        self.assertTrue(self.processes[0][1].stdout.closed)
        self.assertIsNotNone(self.processes[0][1].poll())
        self.assertTrue(any(call[3:] == ["forward", "--remove", manager.session.local_endpoint] for call in self.calls))
        self.assertEqual(self.calls[-1][3:], ["shell", "rm", "-f", manager.session.remote_jar])

    def test_overlong_unix_path_fails_closed_before_any_push_and_removes_directory(self):
        long_root = Path(self.temp.name) / ("long-" + "x" * 90)
        long_root.mkdir()
        manager = self.manager(temp_root=long_root)
        manager.warm()
        self.assertTrue(manager.session.done.wait(1))
        self.assertEqual((self.calls, self.processes, self.connections), ([], [], []))
        self.assertFalse(Path(manager.session.directory).exists())

    def test_default_connector_uses_only_af_unix_and_closes_failed_socket(self):
        connection = FakeSocket()
        with patch.object(fast.socket, "socket", return_value=connection) as create:
            self.assertIs(fast.unix_connection("/private/control.sock", .25), connection)
            create.assert_called_once_with(socket.AF_UNIX, socket.SOCK_STREAM)
            self.assertEqual(connection.path, "/private/control.sock")
            self.assertEqual(connection.timeout, .25)
        with patch.object(fast.socket, "socket", return_value=connection), \
                patch.object(connection, "connect", side_effect=OSError("No endpoint")):
            with self.assertRaises(OSError):
                fast.unix_connection("/private/missing.sock", .25)
        self.assertTrue(connection.closed)


if __name__ == "__main__":
    unittest.main()
