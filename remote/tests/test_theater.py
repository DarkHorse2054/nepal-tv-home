"""Movie Theater transport/security checks. No listener, ADB process or TV I/O."""
import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from fixtures import FIRE, configuration, serial

SPEC = importlib.util.spec_from_file_location("theater_remote_server", Path(__file__).resolve().parents[1] / "server.py")
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)
MODEL = FIRE["model"]
SERIAL = serial()


def decode_payload(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode("utf-8")


class TheaterBridgeTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.model = MODEL
        self.fail = None
        self.wake_fail = None
        self.asleep = True
        self.timeouts = []
        self.bridge = server.AdbBridge("/fixture/adb", FIRE, runner=self.runner, clock=lambda: 100.0)

    def runner(self, argv, **kwargs):
        self.assertFalse(kwargs["shell"])
        self.assertLessEqual(kwargs["timeout"], 4)
        self.calls.append(argv)
        self.timeouts.append(kwargs["timeout"])
        if argv[3:] == ["shell", "input", "keyevent", "224"]:
            self.assertEqual(kwargs["timeout"], 2)
            self.assertTrue(self.bridge.command_lock.locked())
            if self.wake_fail == "timeout":
                raise subprocess.TimeoutExpired(argv, kwargs["timeout"], output="private wake failure")
            if self.wake_fail == "error":
                return types.SimpleNamespace(returncode=1, stdout="private wake failure")
            if self.wake_fail == "reported_error":
                return types.SimpleNamespace(returncode=0, stdout="Error: private wake failure")
            self.asleep = False
            return types.SimpleNamespace(returncode=0, stdout="")
        if argv[3:6] == ["shell", "am", "start"]:
            self.assertFalse(self.asleep, "Theater must be awake before starting its activity")
            self.assertTrue(self.bridge.command_lock.locked())
            if self.fail == "timeout":
                raise subprocess.TimeoutExpired(argv, kwargs["timeout"], output="private request contents")
            if self.fail == "error":
                return types.SimpleNamespace(returncode=1, stdout="Error: private request contents")
            return types.SimpleNamespace(returncode=0, stdout="Starting: Intent { (has extras) }")
        return types.SimpleNamespace(returncode=0, stdout="device" if argv[-1] == "get-state" else self.model)

    def command_calls(self):
        return [call for call in self.calls if call[3:6] == ["shell", "am", "start"]]

    def wake_calls(self):
        return [call for call in self.calls if call[3:] == ["shell", "input", "keyevent", "224"]]

    def test_theater_wakes_after_identity_check_before_launch_under_one_lock(self):
        self.bridge.command("theater", "Play Inception")
        self.assertEqual([call[3:] for call in self.calls[:3]], [
            ["get-state"], ["shell", "getprop", "ro.product.model"],
            ["shell", "input", "keyevent", "224"]])
        self.assertEqual(self.calls[2][1:], ["-s", SERIAL, "shell", "input", "keyevent", "224"])
        self.assertEqual(len(self.command_calls()), 1)
        self.assertEqual(self.calls[3], self.command_calls()[0])
        self.assertEqual(self.timeouts, [1, 1, 2, 4])
        self.assertFalse(self.asleep)
        self.assertFalse(self.bridge.command_lock.locked())

    def test_failed_or_uncertain_wake_does_not_launch_or_replay_request(self):
        for failure in ("timeout", "error", "reported_error"):
            with self.subTest(failure=failure):
                self.calls.clear()
                self.wake_fail = failure
                self.asleep = True
                with self.assertRaises(server.ApiError) as caught:
                    self.bridge.command("theater", "Private fixture movie request")
                self.assertEqual((caught.exception.status, caught.exception.code), (503, "theater_send_uncertain"))
                self.assertEqual(len(self.wake_calls()), 1)
                self.assertEqual(self.command_calls(), [])
                self.assertTrue(self.asleep)
                self.assertFalse(self.bridge.command_lock.locked())
                self.assertNotIn("Private fixture", str(caught.exception))
                self.assertFalse(any("connect" in call for call in self.calls))

    def test_other_controls_do_not_automatically_wake(self):
        self.asleep = False
        for kind, value in (("key", "home"), ("app", "framextv"), ("text", "Inception")):
            self.bridge.command(kind, value)
        self.assertEqual(self.wake_calls(), [])

    def test_request_uses_fixed_intent_and_only_encoded_text_in_argv(self):
        request = '  Play "Inception"; $(id) `whoami` & <movie> | $HOME  '
        self.bridge.command("theater", request)
        self.assertEqual(len(self.command_calls()), 1)
        command = self.command_calls()[0]
        self.assertEqual(command[1:-1], ["-s", SERIAL, "shell", "am", "start", "-n",
            server.APPS["framextv"], "-a", "app.nepal.framextv.action.REQUEST", "--es", "theater_request"])
        encoded = command[-1]
        self.assertRegex(encoded, r"^[A-Za-z0-9_-]+$")
        self.assertNotIn("=", encoded)
        self.assertNotIn(request.strip(), command)
        self.assertEqual(decode_payload(encoded), request.strip())
        self.assertEqual(len(self.wake_calls()), 1)
        self.assertTrue(all(request.strip() not in part for call in self.calls for part in call))
        self.assertFalse(any("connect" in call for call in self.calls))

    def test_unicode_codepoint_limit_preserves_nepali_emoji_and_joiners(self):
        request = "नेपालको चलचित्र 👨‍👩‍👧"
        self.assertEqual(decode_payload(server.theater_payload(request)), request)
        self.assertEqual(decode_payload(server.theater_payload("🎬" * 160)), "🎬" * 160)
        self.assertLessEqual(len(server.theater_payload("🎬" * 160)), 856)
        self.assertEqual(len(server.theater_payload("🎬" * 160)), 854)
        self.assertEqual(decode_payload(server.theater_payload("  Inception  ")), "Inception")

    def test_invalid_requests_fail_before_any_device_command(self):
        invalid = (None, True, 123, [], {}, "", "   ", "x" * 161, "🎬" * 161,
                   "Inception\n", "\tInception", "The\x00Matrix", "A\x7fB", "A\x85B", "\ud800", "\udfff")
        for request in invalid:
            with self.subTest(request=repr(request)):
                with self.assertRaises(server.ApiError) as caught:
                    self.bridge.command("theater", request)
                self.assertEqual((caught.exception.status, caught.exception.code), (400, "invalid_theater_request"))
        self.assertEqual(self.calls, [])

    def test_every_iso_control_is_rejected_even_when_trimming_would_remove_it(self):
        for value in [*range(32), *range(127, 160)]:
            with self.subTest(codepoint=value):
                with self.assertRaises(server.ApiError):
                    server.theater_payload("Inception" + chr(value))

    def test_wrong_identity_rejects_without_launch_or_reconnection(self):
        self.model = "WrongFixtureTV"
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.command("theater", "Play Inception")
        self.assertEqual((caught.exception.status, caught.exception.code), (503, "tv_unavailable"))
        self.assertEqual(self.command_calls(), [])
        self.assertEqual(self.wake_calls(), [])
        self.assertFalse(any("connect" in call for call in self.calls))

    def test_busy_lock_drops_request_without_waiting_or_replaying(self):
        self.bridge.command_lock.acquire()
        try:
            with self.assertRaises(server.ApiError) as caught:
                self.bridge.command("theater", "Play Inception")
        finally:
            self.bridge.command_lock.release()
        self.assertEqual((caught.exception.status, caught.exception.code), (429, "control_busy"))
        self.assertEqual(self.calls, [])

    def test_theater_uses_existing_shared_control_rate(self):
        for _ in range(8):
            self.bridge.command("theater", "Play Inception")
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.command("theater", "Play Inception")
        self.assertEqual((caught.exception.status, caught.exception.code), (429, "control_rate_limited"))
        self.assertEqual(len(self.command_calls()), 8)

    def test_failed_or_uncertain_launch_is_attempted_once_without_query_disclosure(self):
        for failure in ("timeout", "error"):
            with self.subTest(failure=failure):
                self.calls.clear()
                self.fail = failure
                with self.assertRaises(server.ApiError) as caught:
                    self.bridge.command("theater", "Private fixture movie request")
                self.assertEqual((caught.exception.status, caught.exception.code), (503, "theater_send_uncertain"))
                self.assertEqual(len(self.command_calls()), 1)
                self.assertNotIn("Private fixture", str(caught.exception))


class FakeBridge:
    def __init__(self):
        self.calls = []
        self.error = None

    def command(self, kind, value):
        self.calls.append((kind, value))
        if self.error is not None:
            raise server.ApiError(503, self.error)


class FakeSocket:
    def __init__(self, raw):
        self.input = io.BytesIO(raw)
        self.output = io.BytesIO()

    def makefile(self, *_args, **_kwargs):
        return self.input

    def sendall(self, data):
        self.output.write(data)

    def settimeout(self, _value):
        pass


class TheaterHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.bridge = FakeBridge()
        self.app = server.RemoteApp(self.bridge, {"127.0.0.1"}, 8765, dist=Path(self.temp.name),
                                    pairing=server.Pairing(pin="123456"), config=configuration())
        self.token = self.app.pairing.pair("123456", "127.0.0.1")["token"]

    def tearDown(self):
        self.temp.cleanup()

    def request(self, payload=None, auth=True, method="POST", path="/api/theater", headers=(), body=None):
        if body is None:
            body = json.dumps({"request": "Play Inception"} if payload is None else payload, ensure_ascii=True).encode()
        pairs = [("Host", "127.0.0.1:8765"), ("Origin", "http://127.0.0.1:8765"),
                 ("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
        if auth:
            pairs.append(("Authorization", "Bearer " + self.token))
        for name, value in headers:
            if not name.startswith("+"):
                pairs = [(k, v) for k, v in pairs if k.lower() != name.lower()]
            if value is not None:
                pairs.append((name.lstrip("+"), value))
        raw = (f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in pairs) + "\r\n").encode() + body
        socket = FakeSocket(raw)
        with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()) as stderr:
            server.Handler(socket, ("127.0.0.1", 45000), types.SimpleNamespace(app=self.app))
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(stderr.getvalue(), "")
        head, _, content = socket.output.getvalue().partition(b"\r\n\r\n")
        return int(head.split(b" ", 2)[1]), head.decode(), json.loads(content) if content else None

    def test_valid_request_only_acknowledges_dispatch_without_playing_claim_or_echo(self):
        code, headers, response = self.request({"request": "  Play Inception  "})
        self.assertEqual((code, response), (200, {"ok": True, "state": "request_sent"}))
        self.assertEqual(self.bridge.calls, [("theater", "Play Inception")])
        self.assertNotIn("Inception", json.dumps(response))
        self.assertIn("Cache-Control: no-store", headers)
        self.assertNotIn("Access-Control-Allow", headers)

    def test_unauthenticated_and_cookie_only_requests_never_reach_tv(self):
        self.assertEqual(self.request(auth=False)[0], 401)
        self.assertEqual(self.request(auth=False, headers=[("Cookie", "nepal_trusted=" + "a" * 43)])[0], 401)
        self.assertEqual(self.bridge.calls, [])

    def test_host_origin_and_csrf_guards_apply_to_theater(self):
        for header, expected in ((("Origin", "http://attacker.example"), 403), (("Origin", "null"), 403),
                                 (("Origin", None), 403), (("Host", "attacker.example:8765"), 421),
                                 (("+Host", "127.0.0.1:8765"), 400), (("Sec-Fetch-Site", "cross-site"), 403)):
            with self.subTest(header=header):
                self.assertEqual(self.request(headers=[header])[0], expected)
        self.assertEqual(self.bridge.calls, [])

    def test_exact_json_payload_and_content_validation(self):
        for payload in ({}, {"request": 1}, {"request": None}, {"request": True}, [],
                        {"request": "Play Inception", "component": "other"}):
            self.assertEqual(self.request(payload)[0], 400)
        for request in ("", "   ", "x" * 161, "Play Inception\n", "Play\tInception", "\ud800"):
            self.assertEqual(self.request({"request": request})[0], 400)
        self.assertEqual(self.request(body=b'{"request":"one","request":"two"}')[0], 400)
        self.assertEqual(self.request(headers=[("Content-Type", "text/plain")])[0], 415)
        self.assertEqual(self.bridge.calls, [])

    def test_unicode_160_codepoints_fits_existing_body_limit(self):
        request = "🎬" * 160
        self.assertEqual(self.request({"request": request})[0], 200)
        self.assertEqual(self.bridge.calls, [("theater", request)])

    def test_request_is_post_only_and_query_strings_are_not_accepted(self):
        for method, expected in (("GET", 404), ("HEAD", 404), ("PUT", 405)):
            self.assertEqual(self.request(method=method, body=b"")[0], expected)
        self.assertEqual(self.request(path="/api/theater?request=example")[0], 404)
        self.assertEqual(self.bridge.calls, [])

    def test_transport_failure_returns_uncertain_once_without_replay_or_sensitive_echo(self):
        self.bridge.error = "theater_send_uncertain"
        code, _, response = self.request({"request": "Private fixture movie request"})
        self.assertEqual((code, response), (503, {"error": "theater_send_uncertain"}))
        self.assertEqual(self.bridge.calls, [("theater", "Private fixture movie request")])
        self.assertNotIn("Private fixture", json.dumps(response))


if __name__ == "__main__":
    unittest.main()
