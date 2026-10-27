"""Private, stable pairing-code storage. All state and codes are synthetic/offline."""
from concurrent.futures import ThreadPoolExecutor
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "pairing_code.py"
SPEC = importlib.util.spec_from_file_location("nepal_pairing_code_tests", MODULE)
pairing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pairing)


class PairingCodeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        # macOS exposes /var and /tmp through system symlinks. Test an actual
        # directory path; separate tests intentionally exercise unsafe aliases.
        self.root = Path(self.temp.name).resolve()
        self.path = self.root / "state" / "pairing-code.json"
        self.pin = "2" * 6
        self.other = "8" * 6

    def tearDown(self):
        self.temp.cleanup()

    def write(self, raw, *, mode=0o600):
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if isinstance(raw, str):
            raw = raw.encode("utf-8")
        if self.path.exists():
            self.path.chmod(0o600)
        self.path.write_bytes(raw)
        self.path.chmod(mode)

    def record(self, **changes):
        return json.dumps(dict({"version": 1, "pin": self.pin}, **changes))

    def assert_storage_error(self, operation):
        with self.assertRaises(pairing.PairingCodeError) as caught:
            operation()
        self.assertEqual((caught.exception.code, caught.exception.status),
                         ("pairing_code_storage_unavailable", 503))
        self.assertNotIn(self.pin, str(caught.exception))
        self.assertNotIn(str(self.path), str(caught.exception))

    def test_import_does_not_touch_state_or_generate_a_code(self):
        spec = importlib.util.spec_from_file_location("nepal_pairing_code_import_fixture", MODULE)
        module = importlib.util.module_from_spec(spec)
        with patch.object(os, "open", side_effect=AssertionError("Unexpected state I/O")), \
                patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Unexpected RNG")):
            spec.loader.exec_module(module)
        self.assertFalse(self.path.parent.exists())

    def test_first_generation_is_six_ascii_digits_and_private_versioned_json(self):
        with patch.object(pairing.secrets, "randbelow", return_value=7) as generate:
            code = pairing.load_or_create(self.path)
        self.assertEqual(code, "0" * 5 + "7")
        generate.assert_called_once_with(1_000_000)
        self.assertEqual(json.loads(self.path.read_text()), {"version": 1, "pin": code})
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        lock = self.path.with_name("." + self.path.name + ".lock")
        self.assertEqual(lock.read_bytes(), b"")
        self.assertEqual(stat.S_IMODE(lock.stat().st_mode), 0o600)
        self.assertLessEqual(self.path.stat().st_size, pairing.MAX_BYTES)
        self.assertFalse(list(self.path.parent.glob(".pairing-code-*")))

    def test_upper_rng_boundary_stays_six_digits(self):
        with patch.object(pairing.secrets, "randbelow", return_value=999_999):
            self.assertEqual(pairing.load_or_create(self.path), "9" * 6)

    def test_restart_returns_same_code_without_rng_or_file_changes(self):
        code = pairing.create(self.path, self.pin)
        before = self.path.read_bytes()
        details = self.path.stat()
        with patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
            spec = importlib.util.spec_from_file_location("nepal_pairing_restart_fixture", MODULE)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            self.assertEqual(module.load_or_create(self.path), code)
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(self.path.stat().st_ino, details.st_ino)
        self.assertEqual(self.path.stat().st_mtime_ns, details.st_mtime_ns)

    def test_adoption_only_seeds_an_absent_store_and_existing_code_wins(self):
        self.assertEqual(pairing.create(self.path, self.pin), self.pin)
        before = self.path.read_bytes()
        self.assertEqual(pairing.create(self.path, self.other), self.pin)
        self.assertEqual(self.path.read_bytes(), before)

    def test_separate_install_paths_get_independent_codes(self):
        other_path = self.root / "second-install" / "pairing-code.json"
        with patch.object(pairing.secrets, "randbelow", side_effect=[17, 28]):
            first = pairing.load_or_create(self.path)
            second = pairing.load_or_create(other_path)
        self.assertNotEqual(first, second)
        self.assertEqual(pairing.load_or_create(self.path), first)
        self.assertEqual(pairing.load_or_create(other_path), second)

    def test_invalid_adoption_is_rejected_before_any_state_io(self):
        invalid = [None, True, 123456, b"2" * 6, "", "2" * 5, "2" * 7,
                   " " + self.pin, self.pin + "\n", "٢" * 6, "２" * 6, "2" * 5 + "x"]
        with patch.object(pairing.os, "open", side_effect=AssertionError("Unexpected state I/O")):
            for value in invalid:
                with self.subTest(type=type(value).__name__), self.assertRaises(pairing.PairingCodeError) as error:
                    pairing.create(self.path, value)
                self.assertEqual((error.exception.code, error.exception.status), ("invalid_pairing_code", 400))
                if value:
                    self.assertNotIn(str(value), str(error.exception))
        self.assertFalse(self.path.parent.exists())

    def test_public_dist_paths_and_parent_normalization_are_rejected(self):
        paths = [MODULE.parent / "dist" / "pairing-code.json",
                 self.root / "dist" / "state" / "pairing-code.json",
                 self.root / "ordinary" / ".." / "dist" / "pairing-code.json"]
        with patch.object(pairing.os, "open", side_effect=AssertionError("Unexpected state I/O")):
            for path in paths:
                with self.subTest(path=path):
                    self.assert_storage_error(lambda: pairing.load_or_create(path))
        self.assertFalse((self.root / "dist").exists())

    def test_invalid_paths_fail_with_generic_errors(self):
        for path in (None, b"state", "", "bad\x00path", "/"):
            with self.subTest(type=type(path).__name__):
                self.assert_storage_error(lambda: pairing.load_or_create(path))

    def test_unknown_duplicate_trailing_and_invalid_json_never_rotate(self):
        malformed = [b"", b"{", b"[]", b"null", b"{}", b"\xff",
                     self.record(extra=True), self.record(version=True),
                     self.record(version=1.0), self.record(version="1"), self.record(version=2),
                     self.record(pin=None), self.record(pin=True), self.record(pin=22),
                     self.record(pin="٢" * 6), self.record(pin="２" * 6),
                     self.record(pin=self.pin + "\n"), self.record(pin="2" * 5),
                     self.record() + "garbage", self.record() + self.record(),
                     '{"version":1,"version":1,"pin":"' + self.pin + '"}',
                     '{"version":1,"pin":"' + self.pin + '","pin":"' + self.other + '"}',
                     '{"version":NaN,"pin":"' + self.pin + '"}',
                     '{"version":Infinity,"pin":"' + self.pin + '"}',
                     "[" * 64 + "]" * 64]
        for raw in malformed:
            self.write(raw)
            before = self.path.read_bytes()
            with self.subTest(bytes=len(before)), \
                    patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
                self.assert_storage_error(lambda: pairing.load_or_create(self.path))
                self.assert_storage_error(lambda: pairing.create(self.path, self.other))
            self.assertEqual(self.path.read_bytes(), before)

    def test_schema_allows_json_whitespace_but_not_trailing_non_whitespace(self):
        self.write(" \n" + self.record() + "\t\n")
        before = self.path.read_bytes()
        self.assertEqual(pairing.load_or_create(self.path), self.pin)
        self.assertEqual(self.path.read_bytes(), before)

    def test_oversized_input_is_rejected_without_reading_or_rotation(self):
        self.write(self.record() + " " * pairing.MAX_BYTES)
        with patch.object(pairing.os, "fdopen", side_effect=AssertionError("Oversized read")), \
                patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
            self.assert_storage_error(lambda: pairing.load_or_create(self.path))

    def test_file_modes_fail_closed_without_chmod_or_rotation(self):
        for mode in (0o644, 0o640, 0o660, 0o400, 0o700):
            self.write(self.record(), mode=mode)
            with self.subTest(mode=oct(mode)):
                self.assert_storage_error(lambda: pairing.load_or_create(self.path))
            self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), mode)

    def test_parent_modes_and_owner_are_checked_without_repair(self):
        self.write(self.record())
        self.path.parent.chmod(0o755)
        self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o755)
        self.path.parent.chmod(0o700)
        with patch.object(pairing.os, "getuid", return_value=os.getuid() + 1):
            self.assert_storage_error(lambda: pairing.load_or_create(self.path))

    def test_wrong_file_owner_is_rejected_even_with_safe_parent(self):
        self.write(self.record())
        real_fstat = pairing.os.fstat
        def wrong_owner(descriptor):
            value = real_fstat(descriptor)
            if stat.S_ISREG(value.st_mode) and value.st_size:
                fields = list(value)
                fields[4] += 1
                return os.stat_result(fields)
            return value
        with patch.object(pairing.os, "fstat", side_effect=wrong_owner):
            self.assert_storage_error(lambda: pairing.load_or_create(self.path))

    def test_state_symlink_and_hardlink_are_rejected_without_touching_target(self):
        self.write(self.record())
        original = self.path.read_bytes()
        target = self.root / "elsewhere.json"
        self.path.rename(target)
        self.path.symlink_to(target)
        self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual(target.read_bytes(), original)
        self.path.unlink()
        target.rename(self.path)
        os.link(self.path, target)
        self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual(self.path.read_bytes(), original)

    def test_parent_and_ancestor_symlinks_are_never_followed(self):
        self.write(self.record())
        moved = self.root / "moved"
        self.path.parent.rename(moved)
        self.path.parent.symlink_to(moved, target_is_directory=True)
        self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual((moved / self.path.name).read_text(), self.record())
        alias = self.root / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        self.assert_storage_error(lambda: pairing.load_or_create(alias / "nested" / self.path.name))
        self.assertFalse((self.root / "nested").exists())

    def test_state_directory_and_fifo_fail_without_blocking(self):
        self.path.parent.mkdir(mode=0o700)
        self.path.mkdir(mode=0o600)
        self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.path.rmdir()
        os.mkfifo(self.path, mode=0o600)
        self.assert_storage_error(lambda: pairing.load_or_create(self.path))

    def test_unsafe_lock_modes_contents_symlink_hardlink_and_fifo_are_rejected(self):
        self.path.parent.mkdir(mode=0o700)
        lock = self.path.with_name("." + self.path.name + ".lock")
        target = self.root / "lock-target"
        target.write_bytes(b"")
        target.chmod(0o600)
        for kind in ("mode", "contents", "symlink", "hardlink", "fifo", "directory"):
            if kind == "mode":
                lock.touch(mode=0o644)
            elif kind == "contents":
                lock.write_text("not empty")
                lock.chmod(0o600)
            elif kind == "symlink":
                lock.symlink_to(target)
            elif kind == "hardlink":
                os.link(target, lock)
            elif kind == "fifo":
                os.mkfifo(lock, mode=0o600)
            else:
                lock.mkdir(mode=0o600)
            with self.subTest(kind=kind):
                self.assert_storage_error(lambda: pairing.load_or_create(self.path))
                self.assertFalse(self.path.exists())
            lock.rmdir() if kind == "directory" else lock.unlink()

    def test_lock_contention_is_bounded_and_does_not_generate_or_publish(self):
        pairing.create(self.path, self.pin)
        lock = self.path.with_name("." + self.path.name + ".lock")
        with lock.open("rb") as held:
            fcntl.flock(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(pairing, "LOCK_TIMEOUT", 0.02), \
                    patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
                self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual(pairing.load_or_create(self.path), self.pin)

    def test_lock_changed_while_acquiring_is_rejected(self):
        self.path.parent.mkdir(mode=0o700)
        original = pairing.fcntl.flock
        def changed(descriptor, flags):
            result = original(descriptor, flags)
            os.write(descriptor, b"changed")
            return result
        with patch.object(pairing.fcntl, "flock", side_effect=changed), \
                patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Unexpected RNG")):
            self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertFalse(self.path.exists())

    def test_thread_first_creation_reads_one_complete_winner(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            values = list(pool.map(lambda _: pairing.load_or_create(self.path), range(24)))
        self.assertEqual(len(set(values)), 1)
        self.assertEqual(pairing.load_or_create(self.path), values[0])
        self.assertEqual(self.path.stat().st_nlink, 1)

    def test_subprocess_first_creation_and_adoption_race_preserves_one_winner(self):
        script = """import importlib.util,sys
s=importlib.util.spec_from_file_location('pairing_fixture',sys.argv[1])
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
print(m.create(sys.argv[2],sys.argv[3]) if len(sys.argv)>3 else m.load_or_create(sys.argv[2]))
"""
        processes = []
        try:
            for index in range(8):
                args = [sys.executable, "-c", script, str(MODULE), str(self.path)]
                if index % 2:
                    args.append(str(index) * 6)
                processes.append(subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True))
            values = []
            for process in processes:
                output, errors = process.communicate(timeout=15)
                self.assertEqual(process.returncode, 0, errors)
                values.append(output.strip())
            self.assertEqual(len(set(values)), 1)
            self.assertRegex(values[0], r"^[0-9]{6}$")
            self.assertEqual(pairing.load_or_create(self.path), values[0])
            self.assertEqual(self.path.stat().st_nlink, 1)
        finally:
            for process in processes:
                if process.poll() is None:
                    process.kill()
                    process.communicate()

    def test_read_path_disappearance_after_open_is_not_treated_as_absence(self):
        pairing.create(self.path, self.pin)
        original = pairing._same_file
        def disappears(parent, name, details):
            if name == self.path.name:
                raise FileNotFoundError("Fixture disappeared")
            return original(parent, name, details)
        with patch.object(pairing, "_same_file", side_effect=disappears), \
                patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
            self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual(pairing.load_or_create(self.path), self.pin)

    def test_external_publication_race_reads_valid_winner_without_overwrite(self):
        def winner(*_args, **_kwargs):
            self.write(self.record(pin=self.other))
            raise FileExistsError("Another creator won")
        with patch.object(pairing.os, "link", side_effect=winner):
            self.assertEqual(pairing.create(self.path, self.pin), self.other)
        self.assertEqual(pairing.load_or_create(self.path), self.other)
        self.assertFalse(list(self.path.parent.glob(".pairing-code-*")))

    def test_unsafe_external_publication_race_fails_instead_of_overwriting(self):
        def winner(*_args, **_kwargs):
            self.write(self.record(pin=self.other), mode=0o644)
            raise FileExistsError("Another creator won")
        with patch.object(pairing.os, "link", side_effect=winner):
            self.assert_storage_error(lambda: pairing.create(self.path, self.pin))
        self.assertEqual(json.loads(self.path.read_text())["pin"], self.other)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o644)

    def test_prepublication_failures_leave_no_code_or_temporary_and_no_secret_error(self):
        for operation in ("link", "fsync"):
            with self.subTest(operation=operation), \
                    patch.object(pairing.os, operation, side_effect=OSError("Fixture failure " + self.pin)):
                self.assert_storage_error(lambda: pairing.create(self.path, self.pin))
            self.assertFalse(self.path.exists())
            if self.path.parent.exists():
                self.assertFalse(list(self.path.parent.glob(".pairing-code-*")))

    def test_directory_fsync_failure_after_publish_fails_but_never_rotates(self):
        self.path.parent.mkdir(mode=0o700)
        original = pairing.os.fsync
        def fail_directory(descriptor):
            if stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise OSError("Fixture directory sync failure")
            return original(descriptor)
        with patch.object(pairing.os, "fsync", side_effect=fail_directory):
            self.assert_storage_error(lambda: pairing.create(self.path, self.pin))
        self.assertEqual(self.path.stat().st_nlink, 1)
        with patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
            self.assertEqual(pairing.load_or_create(self.path), self.pin)

    def test_interrupted_link_publication_remains_fail_closed(self):
        self.path.parent.mkdir(mode=0o700)
        original = pairing.os.unlink
        def fail_temp(name, *args, **kwargs):
            if str(name).startswith(".pairing-code-"):
                raise OSError("Fixture interrupted temporary unlink")
            return original(name, *args, **kwargs)
        with patch.object(pairing.os, "unlink", side_effect=fail_temp):
            self.assert_storage_error(lambda: pairing.create(self.path, self.pin))
        self.assertEqual(self.path.stat().st_nlink, 2)
        before = self.path.read_bytes()
        with patch.object(pairing.secrets, "randbelow", side_effect=AssertionError("Code rotated")):
            self.assert_storage_error(lambda: pairing.load_or_create(self.path))
        self.assertEqual(self.path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
