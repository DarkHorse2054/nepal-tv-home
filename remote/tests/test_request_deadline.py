"""Wall-clock read deadlines use in-memory sockets; no listener or device calls."""
import io
import json
import socket
import threading
import time
import types
import unittest
from unittest.mock import patch
import test_server as base

server = base.server


class ManualTimer:
    instances = []

    def __init__(self, seconds, callback, args=()):
        self.seconds, self.callback, self.args = seconds, callback, args
        self.cancelled = False
        self.instances.append(self)

    def start(self): pass
    def cancel(self): self.cancelled = True
    def fire(self):
        # Model a callback already queued at cancellation time.
        self.callback(*self.args)


class DeadlineTests(unittest.TestCase):
    setUp = base.RequestTests.setUp
    tearDown = base.RequestTests.tearDown

    def raw_control(self):
        body = json.dumps({"key": "home"}).encode()
        return (b"POST /api/key HTTP/1.1\r\nHost: 127.0.0.1:8765\r\n"
                b"Origin: http://127.0.0.1:8765\r\nContent-Type: application/json\r\n"
                + b"Authorization: Bearer " + self.token.encode()
                + b"\r\nContent-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)

    def test_trickled_first_line_headers_and_body_have_one_total_read_deadline(self):
        class DripSocket(base.FakeSocket):
            def __init__(self, raw, stage):
                super().__init__(raw)
                self.shutdowns, self.interrupted = [], threading.Event()
                source, owner = self.input, self
                class Input:
                    lines = 0
                    def readline(self, maximum=-1):
                        selected = ((stage == "first_line" and self.lines == 0)
                                    or (stage == "headers" and self.lines == 1))
                        self.lines += 1
                        if selected:
                            # Dripped bytes keep an idle timeout alive, but the total
                            # deadline must still interrupt the read. Returning the
                            # buffered tail also tests rejection at the exact boundary.
                            owner.interrupted.wait(2)
                        return source.readline(maximum)
                    def read(self, maximum=-1):
                        if stage == "body": owner.interrupted.wait(2)
                        return source.read(maximum)
                    def close(self): source.close()
                self.input = Input()
            def shutdown(self, how):
                self.shutdowns.append(how)
                self.interrupted.set()
        for stage in ("first_line", "headers", "body"):
            with self.subTest(stage=stage), patch.object(server, "REQUEST_READ_SECONDS", .03):
                sock = DripSocket(self.raw_control(), stage)
                task = threading.Thread(target=server.Handler,
                    args=(sock, ("127.0.0.1", 45000), types.SimpleNamespace(app=self.app)), daemon=True)
                started = time.monotonic()
                task.start()
                task.join(1)
                self.assertFalse(task.is_alive())
                self.assertLess(time.monotonic() - started, .8)
                self.assertEqual(sock.shutdowns, [socket.SHUT_RD])
                self.assertIn(b" 408 ", sock.output.getvalue().split(b"\r\n", 1)[0])
                self.assertEqual(self.bridge.commands, [])

    def test_cancelled_timer_cannot_interrupt_device_work(self):
        ManualTimer.instances = []
        sock = base.FakeSocket(self.raw_control())
        shutdowns = []
        sock.shutdown = lambda how: shutdowns.append(how)
        def command(kind, value):
            self.assertEqual((kind, value), ("key", "home"))
            self.assertTrue(ManualTimer.instances[0].cancelled)
            ManualTimer.instances[0].fire()
            self.assertEqual(shutdowns, [])
            self.bridge.commands.append((kind, value))
        self.bridge.command = command
        with patch.object(server.threading, "Timer", ManualTimer):
            server.Handler(sock, ("127.0.0.1", 45000), types.SimpleNamespace(app=self.app))
        self.assertEqual(self.bridge.commands, [("key", "home")])
        self.assertIn(b" 200 ", sock.output.getvalue().split(b"\r\n", 1)[0])
        self.assertEqual(ManualTimer.instances[0].seconds, 5)
        self.assertTrue(ManualTimer.instances[0].cancelled)

    def test_old_keepalive_timer_cannot_expire_a_new_request_phase(self):
        ManualTimer.instances = []
        handler = object.__new__(server.Handler)
        shutdowns = []
        handler.request = types.SimpleNamespace(shutdown=lambda how: shutdowns.append(how))
        phases = []
        def handle(instance):
            phases.append(instance._read_phase)
            if len(phases) == 2:
                ManualTimer.instances[0].fire()
                self.assertFalse(instance._read_phase["expired"])
                self.assertTrue(instance._read_phase["active"])
            instance._finish_read_phase()
        with patch.object(server.threading, "Timer", ManualTimer), \
                patch.object(server.BaseHTTPRequestHandler, "handle_one_request", handle):
            handler.handle_one_request()
            handler.handle_one_request()
        self.assertIsNot(phases[0], phases[1])
        self.assertEqual(shutdowns, [])
        self.assertTrue(all(timer.cancelled for timer in ManualTimer.instances))


if __name__ == "__main__":
    unittest.main()
