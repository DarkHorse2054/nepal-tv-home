"""Portable configuration regressions. Synthetic targets, no device/network I/O."""
from contextlib import redirect_stderr
from copy import deepcopy
import io
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import test_server as base
from fixtures import FIRE, SAMSUNG, configuration

server = base.server
config = server.CONFIG


class ConfigurationTests(unittest.TestCase):
    def test_optional_weather_is_backwards_compatible_and_has_no_location_default(self):
        value = configuration()
        self.assertNotIn("weather", config.validate(value))
        value["weather"] = None
        self.assertIsNone(config.validate(value)["weather"])
        value["weather"] = {"latitude": 12.3, "longitude": -45.6, "label": " Example city ",
                            "temperature_unit": "fahrenheit"}
        self.assertEqual(config.validate(value)["weather"]["label"], "Example city")
        for field, bad in (("latitude", True), ("latitude", -91), ("longitude", 181),
                           ("longitude", float("nan")), ("label", "x\ny"),
                           ("temperature_unit", "kelvin")):
            broken = deepcopy(value)
            broken["weather"][field] = bad
            with self.subTest(field=field, bad=bad), self.assertRaises(config.ConfigError):
                config.validate(broken)
        value["weather"]["url"] = "https://example.com"
        with self.assertRaises(config.ConfigError):
            config.validate(value)

    def test_valid_configuration_and_disabled_samsung(self):
        value = configuration()
        result = config.validate(value)
        self.assertEqual(result, value)
        value["samsung"] = None
        self.assertIsNone(config.validate(value)["samsung"])

    def test_source_labels_cannot_change_target_identity_or_add_commands(self):
        original = config.fingerprint(SAMSUNG)
        legacy = hashlib.sha256(json.dumps(SAMSUNG, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:20]
        self.assertEqual(original, legacy)
        value = configuration()
        value["samsung"]["source_labels"] = {"hdmi1": " Fire TV ", "hdmi2": "Console"}
        result = config.validate(value)["samsung"]
        self.assertEqual(result["source_labels"], {"hdmi1": "Fire TV", "hdmi2": "Console"})
        self.assertEqual(config.fingerprint(result), original)
        self.assertNotEqual(config.fingerprint(dict(result, host="192.168.1.102")), original)
        for labels in ([], None, {"hdmi5": "Unused"}, {"KEY_HDMI1": "One"},
                       {"hdmi1": ""}, {"hdmi1": "x\ny"}, {"hdmi1": "x" * 65},
                       {"hdmi1": 1}, {"hdmi1": "\u202ehidden"}):
            value["samsung"]["source_labels"] = labels
            with self.subTest(labels=labels), self.assertRaises(config.ConfigError):
                config.validate(value)
        value["samsung"]["source_labels"] = {"hdmi1": "One"}
        value["samsung"]["commands"] = {"hdmi1": "KEY_SERVICE"}
        with self.assertRaises(config.ConfigError):
            config.validate(value)

    def test_only_rfc1918_literal_targets_and_fixed_ports(self):
        for host in ("", "127.0.0.1", "169.254.1.2", "8.8.8.8", "0.0.0.0",
                     "224.0.0.251", "localhost", "tv.local", "https://192.168.1.2",
                     "192.168.1.100:5555", "192.168.1.100;reboot", "::1"):
            for field in ("fire_tv", "samsung"):
                with self.subTest(host=host, field=field):
                    value = configuration()
                    value[field]["host"] = host
                    with self.assertRaises(config.ConfigError):
                        config.validate(value)
        for field, bad in (("fire_tv", 5556), ("samsung", 8001), ("fire_tv", True)):
            value = configuration()
            value[field]["port"] = bad
            with self.assertRaises(config.ConfigError):
                config.validate(value)
        for host in ("10.1.2.3", "172.16.2.3", "172.31.2.3", "192.168.2.3"):
            self.assertEqual(config.private_ipv4(host), host)

    def test_model_schema_and_arbitrary_component_rejection(self):
        for model in ("", "Model\nAnother", "model;reboot", "x/" , "x" * 97):
            value = configuration()
            value["fire_tv"]["model"] = model
            with self.assertRaises(config.ConfigError):
                config.validate(value)
        for version in (True, "1", 2):
            value = configuration()
            value["schema_version"] = version
            with self.assertRaises(config.ConfigError):
                config.validate(value)
        value = configuration()
        value["apps"] = {"custom": "arbitrary/component"}
        with self.assertRaises(config.ConfigError):
            config.validate(value)

    def test_timezone_and_labels_are_explicit_and_validated(self):
        value = configuration()
        value["clocks"].update(local_timezone="UTC", local_label="Home",
                               secondary_timezone="Europe/London", secondary_label="Away",
                               secondary_calendar="gregorian")
        clock = server.CivilDates(config.validate(value)["clocks"])
        status = clock.status()
        self.assertEqual(status["localLabel"], "Home")
        self.assertEqual(status["secondaryLabel"], "Away")
        self.assertEqual(status["nepal"], status["secondaryDate"])
        self.assertEqual(status["sanMateo"], status["localDate"])
        for field, invalid in (("local_timezone", "Invalid/Zone"), ("local_label", "x\ny"),
                               ("secondary_calendar", "unsupported")):
            broken = configuration()
            broken["clocks"][field] = invalid
            with self.assertRaises(config.ConfigError):
                config.validate(broken)

    def test_private_config_permissions_symlinks_duplicates_and_public_dist(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text(json.dumps(configuration()))
            path.chmod(0o600)
            self.assertEqual(config.load(path), configuration())
            path.chmod(0o644)
            with self.assertRaises(config.ConfigError):
                config.load(path)
            path.chmod(0o600)
            duplicate = json.dumps(configuration()).replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1')
            path.write_text(duplicate)
            with self.assertRaises(config.ConfigError):
                config.load(path)
            alias = Path(directory) / "alias.json"
            alias.symlink_to(path)
            with self.assertRaises(config.ConfigError):
                config.load(alias)
        with self.assertRaises(config.ConfigError):
            config.load(config.DIST / "config.json")

    def test_missing_configuration_fails_before_network_or_adb(self):
        error = io.StringIO()
        with patch.object(server.sys, "argv", ["server.py", "--config", "/missing/config.json"]), \
             patch.object(server.CONFIG, "load", side_effect=config.ConfigError("Configuration is missing")), \
             patch.object(server, "local_hosts") as hosts, \
             patch.object(server, "AdbBridge") as bridge, \
             patch.object(server, "RemoteServer") as listener, redirect_stderr(error):
            with self.assertRaises(SystemExit) as caught:
                server.main()
        self.assertEqual(caught.exception.code, 2)
        self.assertIn("Copy config.example.json", error.getvalue())
        hosts.assert_not_called()
        bridge.assert_not_called()
        listener.assert_not_called()

    def test_configured_fire_tv_identity_is_used_for_every_argument_vector(self):
        target = dict(FIRE, host="172.20.1.20", model="DifferentModel")
        calls = []
        def runner(argv, **kwargs):
            calls.append(argv)
            value = "device" if argv[-1] == "get-state" else target["model"] if argv[-1] == "ro.product.model" else ""
            return type("Result", (), {"returncode": 0, "stdout": value})()
        bridge = server.AdbBridge("/tmp/adb", target, runner=runner)
        bridge.command("key", "home")
        self.assertTrue(all(argv[1:3] == ["-s", "172.20.1.20:5555"] for argv in calls))
        self.assertEqual(calls[-1][-3:], ["input", "keyevent", "3"])

    def test_unconfigured_samsung_cannot_probe_or_pair(self):
        bridge = server.SamsungBridge()
        self.assertFalse(bridge.status()["enabled"])
        self.assertFalse(bridge.status()["paired"])
        with self.assertRaises(server.ApiError) as caught:
            bridge.pair()
        self.assertEqual(caught.exception.code, "samsung_not_configured")


if __name__ == "__main__":
    unittest.main()
