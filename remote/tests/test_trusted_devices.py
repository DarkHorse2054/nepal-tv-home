"""Remembered-browser storage and cookie checks; no server or device is used."""
from concurrent.futures import ThreadPoolExecutor
from email.message import Message
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("nepal_trusted_device_tests",
    Path(__file__).resolve().parents[1] / "trusted_devices.py")
trusted = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(trusted)


class CookieTests(unittest.TestCase):
    def headers(self, *values):
        headers = Message()
        for value in values:
            headers.add_header("Cookie", value)
        return headers

    def test_cookie_is_persistent_http_only_strict_host_only_and_narrowly_scoped(self):
        token = "a" * 43
        cookie = trusted.cookie_header(token)
        self.assertEqual(cookie, "nepal_trusted=" + token +
            "; Path=/api/session/; Max-Age=2592000; HttpOnly; SameSite=Strict")
        self.assertNotIn("Domain", cookie)
        self.assertNotIn("token=", cookie)
        self.assertEqual(trusted.cookie_header(clear=True),
            "nepal_trusted=; Path=/api/session/; Max-Age=0; HttpOnly; SameSite=Strict")
        for token in (None, "", "a" * 42, "a" * 44, "a" * 42 + "+", "x\r\nCookie: y"):
            with self.subTest(token=token), self.assertRaises(trusted.TrustError) as error:
                trusted.cookie_header(token)
            self.assertEqual((error.exception.code, error.exception.status),
                             ("trusted_device_invalid", 401))

    def test_missing_trust_is_not_an_invalid_cookie(self):
        self.assertIsNone(trusted.parse_cookie(self.headers()))
        self.assertIsNone(trusted.parse_cookie(self.headers("unrelated=ok")))
        self.assertEqual(trusted.parse_cookie(self.headers("unrelated=ok; nepal_trusted=" + "b" * 43)), "b" * 43)

    def test_duplicates_and_malformed_cookie_are_rejected(self):
        cases = (("nepal_trusted=" + "a" * 43, "other=value"),
                 ("nepal_trusted=" + "a" * 43 + "; nepal_trusted=" + "b" * 43,),
                 ("other=one; other=two",), ("broken",), ("",),
                 ("nepal_trusted=",), ("nepal_trusted=" + "a" * 42,),
                 ("nepal_trusted=" + "a" * 42 + "+",),
                 ("nepal_trusted=" + "a" * 43 + "\r\n",),
                 ("other=bad value",), ("other=bad\r\nvalue",), ("x=" + "a" * 8192,))
        for values in cases:
            with self.subTest(values=values), self.assertRaises(trusted.TrustError) as error:
                trusted.parse_cookie(self.headers(*values))
            self.assertEqual(error.exception.status, 401)


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "state" / "trusted-devices.json"
        self.now = [1_750_000_000.0]
        self.store = self.new_store()

    def tearDown(self):
        self.temp.cleanup()

    def new_store(self, target="fixture-television"):
        return trusted.TrustedDevices(self.path, target, wall_clock=lambda: self.now[0])

    def write_raw(self, raw):
        self.path.parent.mkdir(mode=0o700, exist_ok=True)
        self.path.write_text(raw)
        self.path.chmod(0o600)

    def record(self, **overrides):
        value = {"version": 1, "target": self.store.target,
                 "devices": {"a" * 64: self.now[0] + trusted.LIFETIME}}
        return dict(value, **overrides)

    def assert_storage_error(self, operation):
        with self.assertRaises(trusted.TrustError) as error:
            operation()
        self.assertEqual((error.exception.code, error.exception.status),
                         ("trusted_device_storage_unavailable", 503))

    def test_constructor_and_missing_reads_create_no_state(self):
        with patch.object(trusted.os, "open", side_effect=AssertionError("Unexpected I/O")):
            self.new_store()
        self.assertFalse(self.path.parent.exists())
        self.assertFalse(self.store.valid("a" * 43))
        self.assertFalse(self.store.forget("a" * 43))
        self.assertFalse(self.path.parent.exists())

    def test_public_dist_path_is_rejected_without_creating_it(self):
        with self.assertRaises(ValueError):
            trusted.TrustedDevices(Path(trusted.__file__).parent / "dist" / "forbidden.json", "fixture")

    def test_remember_persists_only_digests_with_private_permissions(self):
        token, seconds = self.store.remember()
        self.assertRegex(token, r"^[A-Za-z0-9_-]{43}$")
        self.assertEqual(seconds, 30 * 24 * 60 * 60)
        raw = self.path.read_text()
        self.assertNotIn(token, raw)
        self.assertNotIn("fixture-television", raw)
        value = json.loads(raw)
        digest = hashlib.sha256(token.encode()).hexdigest()
        self.assertEqual(value, {"version": 1, "target": self.store.target,
                                "devices": {digest: self.now[0] + seconds}})
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        self.assertTrue(self.store.valid(token))

    def test_trust_survives_restart_but_reads_do_not_extend_absolute_expiry(self):
        token, seconds = self.store.remember()
        original = self.path.read_bytes()
        self.now[0] += 8 * 60 * 60 + 1
        self.assertTrue(self.new_store().valid(token))
        self.assertEqual(self.path.read_bytes(), original)
        self.now[0] += seconds - (8 * 60 * 60 + 1)
        self.assertFalse(self.new_store().valid(token))

    def test_changed_target_cannot_inherit_or_revoke_old_trust(self):
        old, _ = self.store.remember()
        before = self.path.read_bytes()
        changed = self.new_store("different-fixture-television")
        self.assertFalse(changed.valid(old))
        self.assertFalse(changed.forget(old))
        self.assertEqual(self.path.read_bytes(), before)
        replacement, _ = changed.remember()
        self.assertTrue(changed.valid(replacement))
        self.assertFalse(self.store.valid(old))
        self.assertFalse(self.store.valid(replacement))
        self.assertEqual(len(json.loads(self.path.read_text())["devices"]), 1)

    def test_forget_is_durable_and_does_not_revoke_other_devices(self):
        first, _ = self.store.remember()
        second, _ = self.store.remember()
        self.assertTrue(self.store.forget(first))
        self.assertFalse(self.store.forget(first))
        self.assertFalse(self.new_store().valid(first))
        self.assertTrue(self.new_store().valid(second))
        self.store.clear()
        self.assertFalse(self.new_store().valid(second))

    def test_invalid_raw_tokens_never_authorize_or_trigger_disk_access(self):
        with patch.object(trusted.os, "open", side_effect=AssertionError("Unexpected I/O")):
            for token in (None, b"a" * 43, "", "a" * 42, "a" * 44, "a" * 42 + "+"):
                self.assertFalse(self.store.valid(token))
                self.assertFalse(self.store.forget(token))

    def test_server_bound_digest_can_durably_revoke_without_the_raw_cookie(self):
        token, _ = self.store.remember()
        other, _ = self.store.remember()
        digest = hashlib.sha256(token.encode("ascii")).hexdigest()
        self.assertTrue(self.store.forget_digest(digest))
        self.assertFalse(self.store.forget_digest(digest))
        self.assertFalse(self.new_store().valid(token))
        self.assertTrue(self.new_store().valid(other))
        with patch.object(trusted.os, "open", side_effect=AssertionError("Unexpected I/O")):
            for invalid in (None, token, digest.upper(), "a" * 63, "a" * 65, "g" * 64):
                self.assertFalse(self.store.forget_digest(invalid))

    def test_oldest_device_is_evicted_and_records_are_bounded(self):
        tokens = []
        for _ in range(trusted.MAX_DEVICES + 1):
            token, _ = self.store.remember()
            tokens.append(token)
            self.now[0] += 1
        self.assertFalse(self.store.valid(tokens[0]))
        self.assertTrue(all(self.store.valid(token) for token in tokens[1:]))
        self.assertEqual(len(json.loads(self.path.read_text())["devices"]), trusted.MAX_DEVICES)

    def test_new_enrollment_prunes_expired_records(self):
        old, _ = self.store.remember()
        self.now[0] += trusted.LIFETIME
        new, _ = self.store.remember()
        self.assertFalse(self.store.valid(old))
        self.assertTrue(self.store.valid(new))
        self.assertEqual(len(json.loads(self.path.read_text())["devices"]), 1)

    def test_parallel_enrollment_preserves_every_record_under_the_limit(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            tokens = list(pool.map(lambda _: self.store.remember()[0], range(16)))
        self.assertEqual(len(set(tokens)), 16)
        self.assertTrue(all(self.new_store().valid(token) for token in tokens))

    def test_unsafe_file_modes_and_owner_are_rejected(self):
        token, _ = self.store.remember()
        self.path.chmod(0o644)
        self.assert_storage_error(lambda: self.store.valid(token))
        self.assert_storage_error(self.store.remember)
        self.path.chmod(0o600)
        with patch.object(trusted.os, "getuid", return_value=os.getuid() + 1):
            self.assert_storage_error(lambda: self.store.valid(token))

    def test_unsafe_parent_is_rejected_instead_of_relaxing_file_safety(self):
        token, _ = self.store.remember()
        self.path.parent.chmod(0o755)
        self.assert_storage_error(lambda: self.store.valid(token))
        self.assert_storage_error(self.store.remember)
        self.path.parent.chmod(0o700)

    def test_symlink_file_and_parent_are_never_followed(self):
        token, _ = self.store.remember()
        original = self.path.read_bytes()
        other = Path(self.temp.name) / "elsewhere.json"
        self.path.rename(other)
        self.path.symlink_to(other)
        self.assert_storage_error(lambda: self.store.valid(token))
        self.assert_storage_error(self.store.remember)
        self.assertEqual(other.read_bytes(), original)
        self.path.unlink()
        other.rename(self.path)
        old_parent = Path(self.temp.name) / "moved-state"
        self.path.parent.rename(old_parent)
        self.path.parent.symlink_to(old_parent, target_is_directory=True)
        self.assert_storage_error(lambda: self.store.valid(token))
        self.assert_storage_error(self.store.remember)

    def test_hardlinks_directories_and_fifos_are_rejected_without_blocking(self):
        token, _ = self.store.remember()
        alias = Path(self.temp.name) / "alias.json"
        os.link(self.path, alias)
        self.assert_storage_error(lambda: self.store.valid(token))
        alias.unlink()
        self.path.unlink()
        self.path.mkdir(mode=0o600)
        self.assert_storage_error(lambda: self.store.valid(token))
        self.path.rmdir()
        os.mkfifo(self.path, mode=0o600)
        self.assert_storage_error(lambda: self.store.valid(token))

    def test_corrupt_unknown_duplicate_and_nonfinite_state_fail_closed(self):
        invalid = ["", "{", "[]", "null", "{}",
                   json.dumps(self.record(extra=True)),
                   json.dumps(self.record(version=True)),
                   json.dumps(self.record(target="not-a-digest")),
                   json.dumps(self.record(devices=[])),
                   json.dumps(self.record(devices={"a" * 64: True})),
                   json.dumps(self.record(devices={"a" * 64: 0})),
                   json.dumps(self.record(devices={"bad": 1_750_000_001})),
                   json.dumps(self.record(devices={"a" * 64: float("nan")})),
                   json.dumps(self.record(devices={"a" * 64: float("inf")})),
                   json.dumps(self.record(devices={"a" * 64: 2 ** 53})),
                   json.dumps(self.record(devices={f"{value:064x}": 1_750_000_001
                                                 for value in range(trusted.MAX_DEVICES + 1)})),
                   '{"version":1,"version":1,"target":"' + self.store.target + '","devices":{}}',
                   '[' * 2000 + ']' * 2000,
                   "x" * (trusted.MAX_BYTES + 1)]
        for raw in invalid:
            with self.subTest(raw=raw[:100]):
                self.write_raw(raw)
                self.assert_storage_error(lambda: self.store.valid("a" * 43))
                self.assert_storage_error(self.store.remember)

    def test_failed_atomic_write_returns_no_credential_and_cleans_its_temp_file(self):
        old, _ = self.store.remember()
        original = self.path.read_bytes()
        with patch.object(trusted.os, "replace", side_effect=OSError("Synthetic disk failure")):
            self.assert_storage_error(self.store.remember)
        self.assertEqual(self.path.read_bytes(), original)
        self.assertTrue(self.new_store().valid(old))
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_failed_fsync_preserves_old_state_and_closes_and_removes_temp(self):
        old, _ = self.store.remember()
        original = self.path.read_bytes()
        with patch.object(trusted.os, "fsync", side_effect=OSError("Synthetic disk failure")):
            self.assert_storage_error(self.store.remember)
        self.assertEqual(self.path.read_bytes(), original)
        self.assertTrue(self.new_store().valid(old))
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_failed_revoke_preserves_original_trust_and_reports_failure(self):
        token, _ = self.store.remember()
        with patch.object(trusted.os, "replace", side_effect=OSError("Synthetic disk failure")):
            self.assert_storage_error(lambda: self.store.forget(token))
        self.assertTrue(self.new_store().valid(token))


if __name__ == "__main__":
    unittest.main()
