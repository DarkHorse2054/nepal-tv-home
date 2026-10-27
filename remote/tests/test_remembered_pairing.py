"""Remembered-browser security/lifecycle tests with no listener or TV I/O."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import types
import unittest
from unittest import mock
from fixtures import configuration

SPEC = importlib.util.spec_from_file_location("remembered_remote_server", Path(__file__).resolve().parents[1] / "server.py")
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)

TARGET = "192.0.2.42:5555|Fixture-TV"


class PairingLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "state/trusted-devices.json"
        self.now = [100.0]
        self.wall = [1_800_000_000.0]
        self.pairing = self.new_pairing()

    def tearDown(self):
        self.temp.cleanup()

    def new_pairing(self):
        store = server.TRUST.TrustedDevices(self.path, TARGET, wall_clock=lambda: self.wall[0])
        return server.Pairing(pin="123456", clock=lambda: self.now[0], trusted=store)

    def enroll(self):
        result, cookie = self.pairing.enroll("123456", "owner", True)
        return result, cookie.split(";", 1)[0].split("=", 1)[1]

    def assert_error(self, operation, status, code):
        with self.assertRaises(server.ApiError) as raised:
            operation()
        self.assertEqual((raised.exception.status, raised.exception.code), (status, code))

    def test_restart_keeps_trust_but_requires_new_access_token(self):
        old, raw = self.enroll()
        restarted = self.new_pairing()
        self.assert_error(lambda: restarted.authorize("Bearer " + old["token"], "owner"), 401, "session_expired")
        resumed = restarted.resume(raw, "owner")
        self.assertTrue(resumed["remembered"])
        self.assertEqual(resumed["expiresIn"], 8 * 60 * 60)
        self.assertNotEqual(resumed["token"], old["token"])
        self.assertTrue(restarted.authorize("Bearer " + resumed["token"], "owner"))
        self.assertNotIn(raw, self.path.read_text())
        self.assertNotIn(old["token"], self.path.read_text())
        self.assertNotIn('"123456"', self.path.read_text())

    def test_eight_hour_expiry_can_resume_without_pin(self):
        result, raw = self.enroll()
        self.now[0] += result["expiresIn"]
        self.wall[0] += result["expiresIn"]
        self.assert_error(lambda: self.pairing.authorize("Bearer " + result["token"], "owner"), 401, "session_expired")
        resumed = self.pairing.resume(raw, "owner")
        self.assertTrue(self.pairing.authorize("Bearer " + resumed["token"], "owner"))

    def test_thirty_day_expiry_is_absolute_not_extended_by_resume(self):
        _, raw = self.enroll()
        self.wall[0] += 29 * 24 * 60 * 60
        self.pairing.resume(raw, "owner")
        self.wall[0] += 24 * 60 * 60
        self.assert_error(lambda: self.pairing.resume(raw, "owner"), 401, "trusted_device_expired")

    def test_forget_revokes_every_access_session_bound_to_cookie(self):
        first, raw = self.enroll()
        second = self.pairing.resume(raw, "owner")
        unrelated = self.pairing.pair("123456", "other")
        identifier = self.pairing.authorize("Bearer " + second["token"], "owner")
        self.pairing.forget(identifier, raw)
        self.assertFalse(self.pairing.active(identifier))
        for token in (first["token"], second["token"]):
            self.assert_error(lambda token=token: self.pairing.authorize("Bearer " + token, "owner"), 401, "session_expired")
        self.assertTrue(self.pairing.authorize("Bearer " + unrelated["token"], "other"))
        self.assert_error(lambda: self.new_pairing().resume(raw, "owner"), 401, "trusted_device_expired")

    def test_unchecking_remember_revokes_old_trust_and_sessions(self):
        first, raw = self.enroll()
        result, cookie = self.pairing.enroll("123456", "owner", False, raw)
        self.assertFalse(result["remembered"])
        self.assertIn("Max-Age=0", cookie)
        self.assert_error(lambda: self.new_pairing().resume(raw, "owner"), 401, "trusted_device_expired")
        self.assert_error(lambda: self.pairing.authorize("Bearer " + first["token"], "owner"), 401, "session_expired")
        self.assertTrue(self.pairing.authorize("Bearer " + result["token"], "owner"))

    def test_forget_without_cookie_still_revokes_durable_session_trust(self):
        first, raw = self.enroll()
        second = self.pairing.resume(raw, "owner")
        identifier = self.pairing.authorize("Bearer " + first["token"], "owner")
        self.pairing.forget(identifier, None)
        self.assert_error(lambda: self.new_pairing().resume(raw, "owner"), 401, "trusted_device_expired")
        self.assert_error(lambda: self.pairing.authorize("Bearer " + second["token"], "owner"), 401, "session_expired")

    def test_forget_mismatched_cookie_revokes_both_credentials(self):
        first, first_raw = self.enroll()
        second, second_raw = self.enroll()
        identifier = self.pairing.authorize("Bearer " + first["token"], "owner")
        self.pairing.forget(identifier, second_raw)
        for raw in (first_raw, second_raw):
            self.assert_error(lambda raw=raw: self.new_pairing().resume(raw, "owner"), 401, "trusted_device_expired")
        self.assert_error(lambda: self.pairing.authorize("Bearer " + second["token"], "owner"), 401, "session_expired")

    def test_wrong_pin_cannot_revoke_existing_remembered_browser(self):
        first, raw = self.enroll()
        self.assert_error(lambda: self.pairing.enroll("999999", "attacker", False, raw), 401, "incorrect_pin")
        self.assertTrue(self.pairing.trusted.valid(raw))
        self.assertTrue(self.pairing.authorize("Bearer " + first["token"], "owner"))

    def test_enrollment_resume_and_forget_fail_closed_on_store_error(self):
        error = server.TRUST.TrustError("trusted_devices_unavailable", 503)
        with mock.patch.object(self.pairing.trusted, "remember", side_effect=error):
            self.assert_error(lambda: self.pairing.enroll("123456", "owner", True), 503, "trusted_devices_unavailable")
        self.assertEqual(self.pairing.tokens, {})
        first, raw = self.enroll()
        identifier = self.pairing.authorize("Bearer " + first["token"], "owner")
        with mock.patch.object(self.pairing.trusted, "valid", side_effect=error):
            self.assert_error(lambda: self.pairing.resume(raw, "owner"), 503, "trusted_devices_unavailable")
        self.assertEqual(len(self.pairing.tokens), 1)
        with mock.patch.object(self.pairing.trusted, "forget", side_effect=error):
            self.assert_error(lambda: self.pairing.forget(identifier, raw), 503, "trusted_devices_unavailable")
        self.assertTrue(self.pairing.active(identifier))

    def test_legacy_pairing_stays_ephemeral_and_no_trust_io(self):
        pairing = server.Pairing(pin="123456")
        result = pairing.pair("123456", "owner")
        self.assertEqual(set(result), {"token", "expiresIn"})
        self.assertFalse(self.path.exists())
        self.assertTrue(pairing.authorize("Bearer " + result["token"], "owner"))
        self.assert_error(lambda: pairing.enroll("123456", "owner", True), 503, "trusted_devices_unavailable")

    def test_resume_racing_forget_cannot_leave_a_live_bearer(self):
        first, raw = self.enroll()
        identifier = self.pairing.authorize("Bearer " + first["token"], "owner")
        entered, release = threading.Event(), threading.Event()
        outcome = {}
        original_valid = self.pairing.trusted.valid

        def delayed_valid(value):
            entered.set()
            if not release.wait(2):
                raise AssertionError("Test did not release the resume operation")
            return original_valid(value)

        def resume():
            try:
                outcome["resumed"] = self.pairing.resume(raw, "owner")
            except Exception as error:
                outcome["resume_error"] = error

        def forget():
            try:
                self.pairing.forget(identifier, raw)
            except Exception as error:
                outcome["forget_error"] = error

        with mock.patch.object(self.pairing.trusted, "valid", side_effect=delayed_valid):
            resume_thread = threading.Thread(target=resume)
            forget_thread = threading.Thread(target=forget)
            resume_thread.start()
            self.assertTrue(entered.wait(2))
            forget_thread.start()
            release.set()
            resume_thread.join(2)
            forget_thread.join(2)
        self.assertFalse(resume_thread.is_alive())
        self.assertFalse(forget_thread.is_alive())
        self.assertNotIn("resume_error", outcome)
        self.assertNotIn("forget_error", outcome)
        resumed = outcome["resumed"]
        self.assert_error(lambda: self.pairing.authorize("Bearer " + resumed["token"], "owner"), 401, "session_expired")
        self.assertFalse(self.pairing.trusted.valid(raw))

    def test_resume_rate_limits_both_per_ip_and_globally(self):
        for _ in range(20):
            self.assert_error(lambda: self.pairing.resume(None, "same-ip"), 401, "trusted_device_missing")
        self.assert_error(lambda: self.pairing.resume(None, "same-ip"), 429, "resume_rate_limited")
        pairing = self.new_pairing()
        for index in range(120):
            self.assert_error(lambda index=index: pairing.resume(None, str(index)), 401, "trusted_device_missing")
        self.assert_error(lambda: pairing.resume(None, "new-ip"), 429, "resume_rate_limited")
        self.now[0] += 60
        self.assert_error(lambda: pairing.resume(None, "new-ip"), 401, "trusted_device_missing")


class FakeBridge:
    def __init__(self):
        self.calls = []

    def command(self, kind, value):
        self.calls.append((kind, value))

    def status(self):
        self.calls.append(("status", None))
        return True


class FakeSocket:
    def __init__(self, raw):
        self.input = io.BytesIO(raw)
        self.output = io.BytesIO()

    def makefile(self, *_args, **_kwargs):
        return self.input

    def sendall(self, data):
        self.output.write(data)

    def settimeout(self, value):
        pass


class RememberedHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        path = Path(self.temp.name)
        self.bridge = FakeBridge()
        self.trusted = server.TRUST.TrustedDevices(path / "state/trusted-devices.json", TARGET)
        self.app = server.RemoteApp(self.bridge, {"127.0.0.1"}, 8765, dist=path,
                                    pairing=server.Pairing(pin="123456", trusted=self.trusted), config=configuration())

    def tearDown(self):
        self.temp.cleanup()

    def request(self, path, payload=None, token=None, cookie=None, method="POST", headers=(), body=None):
        if body is None:
            body = json.dumps({} if payload is None else payload).encode()
        pairs = [("Host", "127.0.0.1:8765"), ("Origin", "http://127.0.0.1:8765"),
                 ("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
        if token is not None:
            pairs.append(("Authorization", "Bearer " + token))
        if cookie is not None:
            pairs.append(("Cookie", cookie))
        for key, value in headers:
            if not key.startswith("+"):
                pairs = [(k, v) for k, v in pairs if k.lower() != key.lower()]
            pairs.append((key.lstrip("+"), value))
        raw = (f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in pairs) + "\r\n").encode() + body
        sock = FakeSocket(raw)
        server.Handler(sock, ("127.0.0.1", 45000), types.SimpleNamespace(app=self.app))
        head, _, content = sock.output.getvalue().partition(b"\r\n\r\n")
        return int(head.split(b" ", 2)[1]), head.decode(), json.loads(content)

    def enroll(self):
        code, head, result = self.request("/api/session/pair", {"pin": "123456", "remember": True})
        self.assertEqual(code, 200)
        cookie = next(line.split(": ", 1)[1].split(";", 1)[0] for line in head.split("\r\n") if line.lower().startswith("set-cookie:"))
        return result, cookie

    def test_remembered_cookie_resumes_without_allowing_cookie_only_controls(self):
        enrolled, cookie = self.enroll()
        self.assertTrue(enrolled["remembered"])
        code, head, resumed = self.request("/api/session/resume", cookie=cookie)
        self.assertEqual(code, 200)
        self.assertNotIn("Set-Cookie", head)  # Resume does not extend the 30-day deadline.
        self.assertTrue(resumed["remembered"])
        for path, payload in (("/api/key", {"key": "ok"}), ("/api/app", {"app": "netflix"}), ("/api/text", {"text": "fixture"})):
            self.assertEqual(self.request(path, payload, cookie=cookie)[0], 401)
        self.assertEqual(self.bridge.calls, [])
        self.assertEqual(self.request("/api/key", {"key": "ok"}, token=resumed["token"])[0], 200)
        self.assertEqual(self.bridge.calls, [("key", "ok")])

    def test_resume_and_forget_require_same_host_origin_and_post(self):
        enrolled, cookie = self.enroll()
        for path in ("/api/session/pair", "/api/session/resume", "/api/session/forget"):
            payload = {"pin": "123456", "remember": True} if path.endswith("pair") else {}
            for header, expected in ((("Origin", "http://attacker.example"), 403),
                                     (("Origin", "null"), 403), (("Host", "attacker.example:8765"), 421),
                                     (("Sec-Fetch-Site", "cross-site"), 403),
                                     (("+Host", "127.0.0.1:8765"), 400)):
                self.assertEqual(self.request(path, payload, token=enrolled["token"], cookie=cookie, headers=[header])[0], expected)
            self.assertEqual(self.request(path, payload, token=enrolled["token"], cookie=cookie, method="GET", body=b"")[0], 404)
        self.assertEqual(self.bridge.calls, [])

    def test_exact_payloads_cookie_errors_and_missing_credentials(self):
        for payload in ({"pin": "123456", "remember": 1}, {"pin": "123456", "remember": "true"},
                        {"pin": "123456", "remember": True, "host": "other"}, [], {"remember": True}):
            self.assertEqual(self.request("/api/session/pair", payload)[0], 400)
        for payload in ({"extra": True}, [], "", None):
            # Explicit null is encoded through raw bytes; helper's None means {}.
            body = b"null" if payload is None else None
            self.assertEqual(self.request("/api/session/resume", payload, body=body)[0], 400)
        self.assertEqual(self.request("/api/session/resume")[2]["error"], "trusted_device_missing")
        code, head, result = self.request("/api/session/resume", cookie="nepal_trusted=bad")
        self.assertEqual((code, result["error"]), (401, "trusted_device_invalid"))
        self.assertIn("Max-Age=0", head)
        self.assertEqual(self.request("/api/session/forget")[0], 401)
        self.assertEqual(self.bridge.calls, [])

    def test_forget_clears_cookie_and_revokes_all_bound_bearers(self):
        first, cookie = self.enroll()
        _, _, second = self.request("/api/session/resume", cookie=cookie)
        code, head, result = self.request("/api/session/forget", token=second["token"], cookie=cookie)
        self.assertEqual((code, result), (200, {"ok": True}))
        self.assertIn("Max-Age=0", head)
        self.assertIn("HttpOnly", head)
        self.assertIn("SameSite=Strict", head)
        self.assertIn("Path=/api/session/", head)
        for token in (first["token"], second["token"]):
            self.assertEqual(self.request("/api/key", {"key": "ok"}, token=token)[0], 401)
        self.assertEqual(self.request("/api/session/resume", cookie=cookie)[0], 401)
        self.assertEqual(self.bridge.calls, [])

    def test_unchecked_alias_pairing_revokes_cookie_and_legacy_payload_works(self):
        first, cookie = self.enroll()
        code, head, result = self.request("/api/session/pair", {"pin": "123456", "remember": False}, cookie=cookie)
        self.assertEqual(code, 200)
        self.assertFalse(result["remembered"])
        self.assertIn("Max-Age=0", head)
        self.assertEqual(self.request("/api/key", {"key": "ok"}, token=first["token"])[0], 401)
        self.assertEqual(self.request("/api/session/resume", cookie=cookie)[0], 401)
        code, head, legacy = self.request("/api/pair", {"pin": "123456"})
        self.assertEqual(code, 200)
        self.assertEqual(len(legacy["token"]), 43)
        self.assertFalse(legacy["remembered"])
        self.assertIn("Max-Age=0", head)

    def test_store_failure_never_acknowledges_enrollment_or_clears_forget_cookie(self):
        error = server.TRUST.TrustError("trusted_devices_unavailable", 503)
        with mock.patch.object(self.trusted, "remember", side_effect=error):
            code, head, result = self.request("/api/session/pair", {"pin": "123456", "remember": True})
        self.assertEqual(code, 503)
        self.assertNotIn("Set-Cookie", head)
        self.assertNotIn("token", result)
        enrolled, cookie = self.enroll()
        with mock.patch.object(self.trusted, "forget", side_effect=error):
            code, head, _ = self.request("/api/session/forget", token=enrolled["token"], cookie=cookie)
        self.assertEqual(code, 503)
        self.assertNotIn("Set-Cookie", head)
        self.assertTrue(self.app.pairing.authorize("Bearer " + enrolled["token"], "owner"))

    def test_malformed_resume_payloads_and_cookies_share_bounded_rate_limit(self):
        for index in range(20):
            if index % 2:
                code, _, _ = self.request("/api/session/resume", {"extra": True})
                self.assertEqual(code, 400)
            else:
                code, _, _ = self.request("/api/session/resume", cookie="nepal_trusted=bad")
                self.assertEqual(code, 401)
        self.assertEqual(self.request("/api/session/resume")[0], 429)

    def test_correct_pin_recovers_corrupt_cookie_while_wrong_pin_changes_nothing(self):
        code, head, result = self.request("/api/session/pair", {"pin": "999999", "remember": True}, cookie="nepal_trusted=damaged")
        self.assertEqual((code, result["error"]), (401, "incorrect_pin"))
        self.assertNotIn("Set-Cookie", head)
        self.assertEqual(self.app.pairing.tokens, {})
        self.assertFalse(self.trusted.path.exists())
        code, head, result = self.request("/api/session/pair", {"pin": "123456", "remember": True}, cookie="nepal_trusted=damaged")
        self.assertEqual(code, 200)
        self.assertTrue(result["remembered"])
        cookie = next(line.split(": ", 1)[1].split(";", 1)[0] for line in head.split("\r\n") if line.lower().startswith("set-cookie:"))
        self.assertNotIn("damaged", cookie)
        self.assertEqual(self.request("/api/session/resume", cookie=cookie)[0], 200)


if __name__ == "__main__":
    unittest.main()
