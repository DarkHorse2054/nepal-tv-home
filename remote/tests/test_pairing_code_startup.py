"""Exercise real startup/PIN persistence with fake app, ADB and listener objects."""
from contextlib import ExitStack, redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import re
import secrets
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("pairing_code_startup_server",
    Path(__file__).resolve().parents[1] / "server.py")
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)


def configuration():
    return {"schema_version": 1,
            "fire_tv": {"host": "192.168.50.123", "port": 5555,
                        "model": "FixtureTV", "name": "Fixture television"},
            "samsung": None,
            "clocks": {"local_timezone": "UTC", "local_label": "Local",
                       "secondary_timezone": "Asia/Kathmandu", "secondary_label": "Secondary",
                       "secondary_calendar": "gregorian"}}


class StartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        # The storage contract rejects symlink traversal, including macOS /var.
        self.here = Path(self.temp.name).resolve() / "installed-remote"
        self.here.mkdir(mode=0o700)
        self.path = self.here / "state/pairing-code.json"
        self.adb = self.here / "fake-adb"
        self.adb.touch(mode=0o600)
        self.apps = []

    def tearDown(self):
        self.temp.cleanup()

    def launch(self, *, config_path=None, port=None, adb=None, store_error=None):
        args = ["server.py", "--adb", str(adb or self.adb), "--no-fast-input", "--bind", "127.0.0.1"]
        if config_path is not None:
            args += ["--config", str(config_path)]
        if port is not None:
            args += ["--port", str(port)]
        stdout, stderr = io.StringIO(), io.StringIO()
        bridge = types.SimpleNamespace(serial="192.168.50.123:5555", expected_model="FixtureTV",
            live_identity=Mock(side_effect=AssertionError("No device probe allowed")))

        def make_app(*_args, **kwargs):
            app = types.SimpleNamespace(pairing=kwargs["pairing"], close=Mock())
            self.apps.append(app)
            return app

        with ExitStack() as stack:
            stack.enter_context(patch.object(server, "HERE", self.here))
            stack.enter_context(patch.object(server.sys, "argv", args))
            hosts = stack.enter_context(patch.object(server, "local_hosts", return_value={"127.0.0.1"}))
            adb_bridge = stack.enter_context(patch.object(server, "AdbBridge", return_value=bridge))
            app_factory = stack.enter_context(patch.object(server, "RemoteApp", side_effect=make_app))
            listener = stack.enter_context(patch.object(server, "RemoteServer"))
            listener.return_value.__enter__.return_value.serve_forever.side_effect = KeyboardInterrupt()
            fast = stack.enter_context(patch.object(server.FAST_INPUT, "FastInput"))
            stack.enter_context(patch.object(server.subprocess, "run", side_effect=AssertionError("No subprocess allowed")))
            stack.enter_context(patch.object(server.socket, "socket", side_effect=AssertionError("No network socket allowed")))
            if hasattr(server, "CONFIG"):
                stack.enter_context(patch.object(server.CONFIG, "load", return_value=configuration()))
            if store_error is not None:
                load = stack.enter_context(patch.object(server.PAIRING_CODE, "load_or_create", side_effect=store_error))
            else:
                load = stack.enter_context(patch.object(server.PAIRING_CODE, "load_or_create",
                                                       wraps=server.PAIRING_CODE.load_or_create))
            caught = None
            with redirect_stdout(stdout), redirect_stderr(stderr):
                try:
                    server.main()
                except SystemExit as error:
                    caught = error.code
        return {"stdout": stdout.getvalue(), "stderr": stderr.getvalue(), "exit": caught,
                "hosts": hosts, "bridge": adb_bridge, "app": app_factory,
                "listener": listener, "fast": fast, "load": load}

    def assert_failed_before_collaborators(self, outcome):
        self.assertEqual(outcome["exit"], 2)
        self.assertIn("Cannot load private pairing code", outcome["stderr"])
        self.assertIn("directory 0700, file 0600", outcome["stderr"])
        self.assertEqual(outcome["stdout"], "")
        for name in ("hosts", "bridge", "app", "listener", "fast"):
            outcome[name].assert_not_called()

    def test_main_restarts_preserve_install_code_but_not_access_sessions(self):
        first = self.launch()
        self.assertIsNone(first["exit"])
        self.assertEqual(first["stderr"], "")
        self.assertTrue(self.path.is_file())
        saved = json.loads(self.path.read_text())
        first_app = self.apps[-1]
        self.assertEqual(first_app.pairing.pin, saved["pin"])
        self.assertRegex(first_app.pairing.pin, r"^[0-9]{6}$")
        self.assertEqual(re.search(r"Pairing PIN: ([0-9]{6})", first["stdout"])[1], saved["pin"])
        token = first_app.pairing.pair(first_app.pairing.pin, "fixture-client")["token"]
        second = self.launch()
        second_app = self.apps[-1]
        self.assertIsNone(second["exit"])
        self.assertEqual(second["stderr"], "")
        self.assertIsNot(first_app.pairing, second_app.pairing)
        self.assertEqual(second_app.pairing.pin, saved["pin"])
        self.assertEqual(json.loads(self.path.read_text()), saved)
        self.assertEqual(re.search(r"Pairing PIN: ([0-9]{6})", second["stdout"])[1], saved["pin"])
        with self.assertRaises(server.ApiError) as error:
            second_app.pairing.authorize("Bearer " + token, "fixture-client")
        self.assertEqual(error.exception.status, 401)
        self.assertTrue(second_app.pairing.authorize("Bearer " +
            second_app.pairing.pair(saved["pin"], "fixture-client")["token"], "fixture-client"))
        for outcome in (first, second):
            outcome["load"].assert_called_once_with(self.path)
            outcome["listener"].assert_called_once()
            outcome["fast"].assert_not_called()
        first_app.close.assert_called_once()
        second_app.close.assert_called_once()

    def test_corrupt_or_unsafe_existing_state_never_rotates_or_starts(self):
        self.path.parent.mkdir(mode=0o700)
        pin = f"{secrets.randbelow(1_000_000):06d}"
        for kind in ("corrupt", "unsafe-file", "unsafe-directory"):
            with self.subTest(kind=kind):
                raw = ("private corrupt data " + pin) if kind == "corrupt" else json.dumps({"version": 1, "pin": pin})
                self.path.write_text(raw)
                self.path.chmod(0o644 if kind == "unsafe-file" else 0o600)
                self.path.parent.chmod(0o755 if kind == "unsafe-directory" else 0o700)
                try:
                    outcome = self.launch()
                    self.assert_failed_before_collaborators(outcome)
                    self.assertNotIn(pin, outcome["stderr"])
                    self.assertNotIn("private corrupt data", outcome["stderr"])
                    self.assertEqual(self.path.read_text(), raw)
                finally:
                    self.path.parent.chmod(0o700)
                    self.path.chmod(0o600)

    def test_store_error_details_are_not_repeated_in_startup_output(self):
        secret = secrets.token_urlsafe(24)
        outcome = self.launch(store_error=server.PAIRING_CODE.PairingCodeError(secret))
        self.assert_failed_before_collaborators(outcome)
        self.assertNotIn(secret, outcome["stderr"])
        self.assertFalse(self.path.exists())

    def test_invalid_setup_fails_before_creating_pairing_state(self):
        for options in ({"port": 0}, {"adb": self.here / "missing-adb"}):
            with self.subTest(options=options):
                outcome = self.launch(**options)
                self.assertEqual(outcome["exit"], 2)
                outcome["load"].assert_not_called()
                for name in ("hosts", "bridge", "app", "listener", "fast"):
                    outcome[name].assert_not_called()
                self.assertFalse(self.path.exists())

    def test_pairing_constructor_remains_ephemeral_without_startup(self):
        with patch.object(server.PAIRING_CODE, "load_or_create", side_effect=AssertionError("Unexpected persistence")):
            pairing = server.Pairing()
        self.assertRegex(pairing.pin, r"^[0-9]{6}$")
        self.assertFalse(self.path.exists())

    @unittest.skipUnless(hasattr(server, "CONFIG"), "Portable configuration applies only to the public server")
    def test_config_path_changes_do_not_move_the_install_pairing_code(self):
        first_config = self.here / "separate-config/first.json"
        second_config = self.here / "another-config/second.json"
        first = self.launch(config_path=first_config)
        code = self.apps[-1].pairing.pin
        second = self.launch(config_path=second_config)
        self.assertEqual(self.apps[-1].pairing.pin, code)
        first["load"].assert_called_once_with(self.path)
        second["load"].assert_called_once_with(self.path)
        self.assertFalse(first_config.parent.exists())
        self.assertFalse(second_config.parent.exists())


if __name__ == "__main__":
    unittest.main()
