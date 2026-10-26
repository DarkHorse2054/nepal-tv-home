"""Authenticated Samsung HTTP/bridge tests. Fake clients only; no listener or TV I/O."""
import asyncio
import json
import types
import unittest
from unittest.mock import patch
import test_server as base
from fixtures import SAMSUNG

server = base.server


class FakeSamsung:
    def __init__(self):
        self.calls = []
        self.paired = False
        self.error = None

    def status(self):
        return {"model": SAMSUNG["model"], "paired": self.paired,
                "sourceReadback": False, "directHdmi": False,
                "experimentalHdmi": True, "lastSent": None}

    def pair(self):
        self.calls.append(("pair",))
        if self.error:
            raise self.error
        return dict(self.status(), state="pending")

    def key(self, value):
        self.calls.append(("key", value))
        if self.error:
            raise self.error
        return {"ok": True, "state": "sent", "key": value, "inputVerified": False}


class SamsungRequestTests(unittest.TestCase):
    request = base.RequestTests.request
    tearDown = base.RequestTests.tearDown

    def setUp(self):
        base.RequestTests.setUp(self)
        self.samsung = FakeSamsung()
        self.app.samsung = self.samsung

    def test_authentication_and_origin_required_before_any_samsung_action(self):
        for path, payload in (("/api/samsung/pair", {}), ("/api/samsung/key", {"key": "source"}),
                              ("/api/samsung/key", {"key": "hdmi1"}),
                              ("/api/samsung/key", {"key": "power_off"})):
            self.assertEqual(self.request("POST", path, payload)[0], 401)
            self.assertEqual(self.request("POST", path, payload, auth=True,
                                         headers=[("Origin", "http://attacker.example")])[0], 403)
            self.assertEqual(self.request("POST", path, payload, auth=True,
                                         headers=[("Sec-Fetch-Site", "cross-site")])[0], 403)
        self.assertEqual(self.samsung.calls, [])

    def test_pair_requires_exact_empty_object_and_keys_use_separate_allowlist(self):
        for payload in ([], {"pin": "123456"}, {"key": "source"}, {"extra": 1}):
            self.assertEqual(self.request("POST", "/api/samsung/pair", payload, auth=True)[0], 400)
        for payload in ({"key": "KEY_HDMI1"}, {"key": "hdmi5"}, {"key": "home"}, {"key": "power"},
                        {"key": "power_on"}, {"key": "volume_up;reboot"},
                        {"key": "source;reboot"}, {"key": ["source"]},
                        {"key": "source", "host": "10.0.0.1"}):
            self.assertEqual(self.request("POST", "/api/samsung/key", payload, auth=True)[0], 400)
        self.assertEqual(self.samsung.calls, [])

    def test_explicit_pair_and_navigation_contract(self):
        code, headers, content = self.request("POST", "/api/samsung/pair", {}, auth=True)
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(content)["state"], "pending")
        for key in server.SAMSUNG_KEYS:
            code, _, content = self.request("POST", "/api/samsung/key", {"key": key}, auth=True)
            self.assertEqual(code, 200)
            self.assertFalse(json.loads(content)["inputVerified"])
        self.assertEqual(self.samsung.calls[0], ("pair",))
        self.assertEqual(len(self.samsung.calls), 1 + len(server.SAMSUNG_KEYS))
        self.assertEqual(self.bridge.commands, [])

    def test_status_uses_local_pair_state_and_never_samsung_controls(self):
        self.samsung.paired = True
        code, _, content = self.request(path="/api/status", auth=True)
        status = json.loads(content)["samsung"]
        self.assertEqual(code, 200)
        self.assertEqual(status, {"model": SAMSUNG["model"], "paired": True,
                                  "sourceReadback": False, "directHdmi": False,
                                  "experimentalHdmi": True, "lastSent": None})
        self.assertEqual(self.samsung.calls, [])

    def test_error_state_and_pair_retry_after(self):
        self.samsung.error = server.ApiError(429, "samsung_pair_rate_limited")
        code, headers, content = self.request("POST", "/api/samsung/pair", {}, auth=True)
        self.assertEqual(code, 429)
        self.assertIn("Retry-After: 60", headers)
        self.assertEqual(json.loads(content), {"state": "error", "error": "samsung_pair_rate_limited"})
        self.samsung.error = server.ApiError(503, "samsung_dependency_missing")
        code, _, content = self.request("POST", "/api/samsung/key", {"key": "source"}, auth=True)
        self.assertEqual(code, 503)
        self.assertEqual(json.loads(content)["state"], "error")

    def test_nonpost_routes_cannot_pair_or_control(self):
        self.assertEqual(self.request(path="/api/samsung/pair", auth=True)[0], 404)
        self.assertEqual(self.request("HEAD", "/api/samsung/key", auth=True)[0], 404)
        self.assertEqual(self.request("PUT", "/api/samsung/pair", {}, auth=True)[0], 405)
        self.assertEqual(self.samsung.calls, [])


class FakeStore:
    def __init__(self):
        self.token = None
        self.loads = 0
    def load(self):
        self.loads += 1
        return self.token


class SamsungBridgeTests(unittest.TestCase):
    def setUp(self):
        self.now = [0.0]
        self.store = FakeStore()
        self.clients = []
        self.state = None
        self.failure = None
        self.navigate_failure = None
        self.wall_time = [1792454400.0]
        owner = self
        class Remote:
            def __init__(self, target, token_store):
                self.store = token_store
                self.calls = []
                self.closed = False
                owner.clients.append(self)
            async def connect(self, **kwargs):
                self.calls.append(("connect", kwargs))
                if owner.failure:
                    raise owner.failure
                state = owner.state or ("authorized" if kwargs["pair"] or self.store.token else "pair_required")
                if kwargs["pair"] and state == "authorized":
                    self.store.token = "private-test-token"
                return {"state": state}
            async def navigate(self, value):
                self.calls.append(("navigate", value))
                if owner.navigate_failure:
                    raise owner.navigate_failure
            async def close(self):
                self.closed = True
        self.bridge = server.SamsungBridge(SAMSUNG, store=self.store, remote_factory=Remote,
                                          clock=lambda: self.now[0], wall_clock=lambda: self.wall_time[0])

    def test_fixed_key_lists_match_transport_and_no_generic_commands(self):
        self.assertEqual(server.SAMSUNG_KEYS, frozenset(server.SAMSUNG_MODULE.COMMANDS))
        self.assertEqual(server.SAMSUNG_EXPERIMENTAL_KEYS, frozenset(("hdmi1", "hdmi2", "hdmi3", "hdmi4")))
        self.assertTrue({"volume_up", "volume_down", "mute", "power_off"} <= server.SAMSUNG_KEYS)
        self.assertFalse({"power", "power_on", "hdmi", "text", "KEY_HDMI1"} & server.SAMSUNG_KEYS)

    def test_status_labels_do_not_probe_or_imply_selected_source(self):
        self.assertEqual(self.bridge.status()["sourceLabels"],
                         {"hdmi1": "HDMI 1", "hdmi2": "HDMI 2", "hdmi3": "HDMI 3", "hdmi4": "HDMI 4"})
        bridge = server.SamsungBridge(dict(SAMSUNG, source_labels={"hdmi2": "Console"}),
                                      store=self.store, remote_factory=self.bridge.remote_factory)
        state = bridge.status()
        self.assertEqual(state["sourceLabels"]["hdmi2"], "Console")
        self.assertEqual(state["sourceLabels"]["hdmi1"], "HDMI 1")
        self.assertIsNone(state["lastSent"])
        self.assertFalse(state["sourceReadback"])
        self.assertEqual(self.clients, [])
        self.assertEqual(server.SamsungBridge(None).status()["sourceLabels"], {})

    def test_send_history_is_local_unverified_and_only_changes_after_success(self):
        self.store.token = "test"
        self.assertIsNone(self.bridge.status()["lastSent"])
        result = self.bridge.key("hdmi2")
        self.assertEqual(result, {"ok": True, "state": "sent", "key": "hdmi2",
                                  "sentAt": "2026-10-20T00:00:00Z", "experimental": True,
                                  "inputVerified": False})
        self.assertEqual(self.clients[-1].calls[-1], ("navigate", "hdmi2"))
        state = self.bridge.status()
        expected = {name: result[name] for name in ("key", "sentAt", "experimental", "inputVerified")}
        self.assertEqual(state["lastSent"], expected)
        self.assertFalse(state["directHdmi"])
        self.assertFalse(state["sourceReadback"])
        state["lastSent"]["key"] = "tampered"
        self.assertEqual(self.bridge.status()["lastSent"], expected)
        self.navigate_failure = server.SAMSUNG_MODULE.SamsungError("send_failed")
        self.wall_time[0] += 1
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("hdmi3")
        self.assertEqual(caught.exception.code, "samsung_send_failed")
        self.assertEqual(self.bridge.status()["lastSent"], expected)
        self.navigate_failure = None
        result = self.bridge.key("volume_up")
        self.assertFalse(result["experimental"])
        self.assertEqual(result["sentAt"], "2026-10-20T00:00:01Z")
        self.assertEqual(self.bridge.status()["lastSent"]["key"], "volume_up")
        self.assertTrue(self.clients[-1].closed)

    def test_status_reads_store_only_and_pair_rate_is_global(self):
        self.assertFalse(self.bridge.status()["paired"])
        self.assertEqual(self.clients, [])
        first = self.bridge.pair()
        self.assertEqual(first["state"], "authorized")
        self.assertTrue(first["paired"])
        self.assertNotIn("private-test-token", json.dumps(first))
        self.assertTrue(self.clients[-1].closed)
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.pair()
        self.assertEqual(caught.exception.code, "samsung_pair_rate_limited")
        self.now[0] = 60
        self.assertEqual(self.bridge.pair()["state"], "authorized")

    def test_key_never_initiates_pairing_or_retries(self):
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("source")
        self.assertEqual(caught.exception.code, "samsung_pair_required")
        self.assertEqual(len(self.clients), 1)
        self.assertEqual(self.clients[0].calls, [("connect", {"pair": False, "wait_seconds": 3})])
        self.assertTrue(self.clients[0].closed)
        self.assertIsNone(self.store.token)

    def test_key_rate_and_busy_lock_are_bounded(self):
        self.store.token = "test"
        for _ in range(4):
            self.assertEqual(self.bridge.key("source")["state"], "sent")
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("left")
        self.assertEqual(caught.exception.code, "samsung_key_rate_limited")
        self.bridge.lock.acquire()
        try:
            with self.assertRaises(server.ApiError) as caught:
                self.bridge.key("ok")
            self.assertEqual(caught.exception.code, "samsung_busy")
        finally:
            self.bridge.lock.release()
        self.assertEqual(len(self.clients), 4)

    def test_allowlist_rejects_before_opening_or_consuming_rate(self):
        for key in ("KEY_SOURCE", "KEY_HDMI1", "text", "power", ["source"]):
            with self.assertRaises(server.ApiError) as caught:
                self.bridge.key(key)
            self.assertEqual(caught.exception.status, 400)
        self.assertEqual(self.clients, [])

    def test_pending_and_fixed_error_mapping(self):
        self.state = "pending"
        result = self.bridge.pair()
        self.assertEqual(result["state"], "pending")
        self.assertFalse(result["paired"])
        self.now[0] = 60
        self.failure = server.SAMSUNG_MODULE.SamsungError("secret-must-not-escape")
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.pair()
        self.assertEqual(caught.exception.code, "samsung_unavailable")
        self.assertTrue(self.clients[-1].closed)

    def test_certificate_errors_are_stable_and_block_controls_without_discarding_saved_auth(self):
        for code in ("certificate_required", "certificate_mismatch", "certificate_unavailable", "invalid_certificate"):
            with self.subTest(code=code):
                self.bridge.needs_pair = False
                self.store.token = "saved-test-authorization"
                self.failure = server.SAMSUNG_MODULE.SamsungError(code)
                before = len(self.clients)
                with self.assertRaises(server.ApiError) as caught:
                    self.bridge.key("source")
                self.assertEqual(caught.exception.code, "samsung_" + code)
                self.assertFalse(self.bridge.status()["paired"])
                self.assertEqual(self.store.token, "saved-test-authorization")
                self.assertEqual(len(self.clients), before + 1)
                self.assertTrue(self.clients[-1].closed)
                self.assertFalse(any(call[0] == "navigate" for call in self.clients[-1].calls))
                self.failure = None
                with self.assertRaises(server.ApiError) as blocked:
                    self.bridge.key("source")
                self.assertEqual(blocked.exception.code, "samsung_pair_required")
                self.assertEqual(len(self.clients), before + 1)
                self.now[0] += 1

    def test_rejected_saved_auth_stays_unpaired_until_authorized_pair(self):
        self.store.token = "saved-but-rejected"
        self.assertTrue(self.bridge.status()["paired"])
        self.failure = server.SAMSUNG_MODULE.SamsungError("authorization_denied")
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("source")
        self.assertEqual(caught.exception.code, "samsung_authorization_denied")
        self.assertFalse(self.bridge.status()["paired"])
        self.assertEqual(self.store.token, "saved-but-rejected")
        before = len(self.clients)
        self.failure = None
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("source")
        self.assertEqual(caught.exception.code, "samsung_pair_required")
        self.assertEqual(len(self.clients), before)
        self.state = "pending"
        result = self.bridge.pair()
        self.assertEqual(result["state"], "pending")
        self.assertFalse(result["paired"])
        self.assertFalse(self.bridge.status()["paired"])
        self.assertEqual(self.store.token, "saved-but-rejected")
        self.now[0] = 60
        self.state = None
        result = self.bridge.pair()
        self.assertEqual(result["state"], "authorized")
        self.assertTrue(result["paired"])
        self.assertTrue(self.bridge.status()["paired"])

    def test_saved_token_pair_required_state_does_not_restore_paired_status(self):
        self.store.token = "saved-but-invalid"
        self.state = "pair_required"
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("source")
        self.assertEqual(caught.exception.code, "samsung_pair_required")
        self.assertFalse(self.bridge.status()["paired"])
        self.assertEqual(self.store.token, "saved-but-invalid")

    def test_pending_explicit_pair_does_not_trust_an_old_saved_token(self):
        self.store.token = "old-session"
        self.state = "pending"
        self.assertTrue(self.bridge.status()["paired"])
        result = self.bridge.pair()
        self.assertEqual(result["state"], "pending")
        self.assertFalse(result["paired"])
        self.assertFalse(self.bridge.status()["paired"])
        self.assertEqual(self.store.token, "old-session")

    def test_optional_dependency_failure_is_fixed_503(self):
        bridge = server.SamsungBridge(SAMSUNG, module=None)
        self.assertFalse(bridge.status()["paired"])
        with self.assertRaises(server.ApiError) as caught:
            bridge.pair()
        self.assertEqual((caught.exception.status, caught.exception.code), (503, "samsung_dependency_missing"))

    def test_operation_and_close_budgets_and_timeout_states(self):
        budgets = []
        original = asyncio.wait_for
        async def record(awaitable, timeout):
            budgets.append(timeout)
            return await original(awaitable, timeout)
        with patch.object(server.asyncio, "wait_for", record):
            self.bridge.pair()
            self.bridge.key("source")
        self.assertEqual(budgets, [27, 2, 8, 2])
        self.now[0] = 60
        self.failure = asyncio.TimeoutError()
        self.assertEqual(self.bridge.pair()["state"], "pending")
        self.assertFalse(self.bridge.status()["paired"])
        self.failure = None
        self.now[0] = 120
        self.assertEqual(self.bridge.pair()["state"], "authorized")
        self.failure = asyncio.TimeoutError()
        with self.assertRaises(server.ApiError) as caught:
            self.bridge.key("source")
        self.assertEqual(caught.exception.code, "samsung_timeout")
        self.assertTrue(self.clients[-1].closed)


if __name__ == "__main__":
    unittest.main()
