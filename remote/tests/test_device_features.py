"""Offline samples, forecast validation/cache, and authenticated feature APIs."""
from http.client import HTTPException, IncompleteRead
import json
import subprocess
import types
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
import test_server as base

server = base.server
features = server.FEATURES
SETTINGS = {"latitude": 12.3, "longitude": -45.6, "label": "Example city", "temperature_unit": "celsius"}
MEMORY = "MemTotal: 2048000 kB\nMemFree: 1000 kB\nMemAvailable: 512000 kB\n"
STORAGE = "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/example 1000000 700000 250000 70% /data\n"


def forecast():
    return {"current": {"time": "2026-10-25T12:00", "temperature_2m": 20, "weather_code": 2, "is_day": 1},
            "daily": {"time": ["2026-10-" + str(day) for day in range(25, 32)], "weather_code": [2] * 7,
                      "temperature_2m_max": [22] * 7, "temperature_2m_min": [12] * 7,
                      "precipitation_probability_max": [10] * 7}}


class SampleTests(unittest.TestCase):
    def test_ram_uses_available_and_data_partition_uses_kib(self):
        memory, storage = features.parse_memory(MEMORY), features.parse_storage(STORAGE)
        self.assertEqual(memory["totalBytes"], 2048000 * 1024)
        self.assertEqual(memory["usedPercent"], 75)
        self.assertEqual(storage["availableBytes"], 250000 * 1024)
        self.assertEqual(storage["usedBytes"], 700000 * 1024)
        self.assertEqual(storage["usedPercent"], 70)

    def test_missing_corrupt_or_impossible_samples_never_become_zero(self):
        for raw in (None, "", "MemTotal: 2000 kB\nMemFree: 500 kB", "MemTotal: 1 kB\nMemAvailable: 2 kB",
                    MEMORY + "MemTotal: 1 kB", "x" * 32769):
            self.assertIsNone(features.parse_memory(raw))
        for raw in (None, "", STORAGE.replace("/data", "/system"), STORAGE + STORAGE,
                    STORAGE.replace("250000", "999999"), "x" * 32769):
            self.assertIsNone(features.parse_storage(raw))

    def test_shared_ten_second_cache_and_explicit_stale_last_success(self):
        now, calls = [0.0], []
        response = [True, MEMORY, STORAGE]
        def read():
            calls.append(True)
            return response
        samples = features.DeviceSamples(read, clock=lambda: now[0], wall_clock=lambda: 1000)
        first = samples.status()
        self.assertTrue(first["online"])
        self.assertFalse(first["stale"])
        now[0] = 9
        self.assertEqual(samples.status()["ageSeconds"], 9)
        self.assertEqual(len(calls), 1)
        now[0] = 10
        response[:] = [False, None, None]
        failed = samples.status()
        self.assertFalse(failed["online"])
        self.assertTrue(failed["stale"])
        self.assertEqual(failed["sampledAt"], first["sampledAt"])
        self.assertEqual(failed["memory"], first["memory"])
        failed["memory"]["totalBytes"] = 0
        self.assertNotEqual(samples.status()["memory"]["totalBytes"], 0)
        self.assertEqual(len(calls), 2)

    def test_busy_controls_preserve_samples_without_claiming_new_measurement(self):
        now = [0]
        response = [True, MEMORY, STORAGE]
        samples = features.DeviceSamples(lambda: response, clock=lambda: now[0], wall_clock=lambda: 1000)
        first = samples.status()
        now[0] = 11
        response[:] = [None, None, None]
        busy = samples.status()
        self.assertEqual(busy["memory"], first["memory"])
        self.assertEqual(busy["sampledAt"], first["sampledAt"])
        self.assertTrue(busy["stale"])

    def test_cache_interval_is_rechecked_after_lock_acquisition(self):
        now, calls = [0.0], []
        samples = features.DeviceSamples(lambda: calls.append(True) or (True, MEMORY, STORAGE),
                                         clock=lambda: now[0], wall_clock=lambda: 1000)
        samples.status()
        now[0] = 10
        # Simulate another successful request between the first interval check
        # and this caller taking the shared refresh lock.
        class InterleavedLock:
            def acquire(self, blocking=False):
                samples.last_attempt = samples.last_success = now[0]
                return True
            def release(self): pass
        samples.lock = InterleavedLock()
        self.assertFalse(samples.status()["stale"])
        self.assertEqual(len(calls), 1)

    def bridge(self, runner):
        args = ("/tmp/adb", base.FIRE) if hasattr(base, "FIRE") else ("/tmp/adb",)
        return server.AdbBridge(*args, runner=runner)

    def test_fixed_read_vectors_identity_guard_busy_and_timeout(self):
        calls = []
        model = base.FIRE["model"] if hasattr(base, "FIRE") else server.TV_MODEL
        serial = base.serial() if hasattr(base, "serial") else server.TV_SERIAL
        def run(argv, **options):
            calls.append(argv)
            self.assertFalse(options["shell"])
            self.assertEqual(options["timeout"], 1)
            output = "device" if argv[-1] == "get-state" else model if argv[-1] == "ro.product.model" else MEMORY if argv[-1] == "/proc/meminfo" else STORAGE
            return types.SimpleNamespace(returncode=0, stdout=output)
        bridge = self.bridge(run)
        self.assertEqual(bridge.device_readings(), (True, MEMORY.strip(), STORAGE.strip()))
        self.assertTrue(all(call[1:3] == ["-s", serial] for call in calls))
        self.assertEqual(calls[-2][3:], ["shell", "cat", "/proc/meminfo"])
        self.assertEqual(calls[-1][3:], ["shell", "df", "-k", "/data"])
        calls.clear()
        bridge.command_lock.acquire()
        try:
            self.assertEqual(bridge.device_readings(), (None, None, None))
            self.assertEqual(calls, [])
        finally:
            bridge.command_lock.release()
        def wrong(argv, **_options):
            calls.append(argv)
            return types.SimpleNamespace(returncode=0, stdout="device" if argv[-1] == "get-state" else "WrongModel")
        self.assertEqual(self.bridge(wrong).device_readings(), (False, None, None))
        self.assertFalse(any("/proc/meminfo" in call for call in calls))
        def timeout(*_args, **_options):
            raise subprocess.TimeoutExpired("adb", 1)
        self.assertEqual(self.bridge(timeout).device_readings(), (False, None, None))

    def test_espn_launches_only_the_installed_official_component(self):
        calls = []
        model = base.FIRE["model"] if hasattr(base, "FIRE") else server.TV_MODEL
        def run(argv, **options):
            calls.append(argv)
            self.assertFalse(options["shell"])
            output = "device" if argv[-1] == "get-state" else model if argv[-1] == "ro.product.model" else ""
            return types.SimpleNamespace(returncode=0, stdout=output)
        bridge = self.bridge(run)
        bridge.command("app", "espn")
        self.assertEqual(calls[-1][3:], ["shell", "am", "start", "-n",
            "com.espn.gtv/com.espn.startup.presentation.StartupActivity"])
        before = len(calls)
        for value in ("com.espn.gtv/other.Component", "espn;reboot", "com.espn.gtv", "ESPN"):
            with self.assertRaises(server.ApiError):
                bridge.command("app", value)
        self.assertEqual(len(calls), before)
        self.assertFalse(any("install" in call or "uninstall" in call for call in calls))

    def test_wallpaper_uses_only_protected_fixed_broadcast_and_requires_ack(self):
        calls, acknowledgment = [], ['Broadcast completed: result=1, data="wallpaper_applied"']
        model = base.FIRE["model"] if hasattr(base, "FIRE") else server.TV_MODEL
        def run(argv, **options):
            calls.append(argv)
            self.assertFalse(options["shell"])
            output = "device" if argv[-1] == "get-state" else model if argv[-1] == "ro.product.model" else acknowledgment[0]
            return types.SimpleNamespace(returncode=0, stdout=output)
        bridge = self.bridge(run)
        for mode in ("night", "annapurna", "pashupati"):
            bridge.command("wallpaper", mode)
            self.assertEqual(calls[-1][3:], ["shell", "am", "broadcast", "--user", "0", "-n",
                "com.gathin.tvhome/.WallpaperCommandReceiver", "-a", "com.gathin.tvhome.action.SET_WALLPAPER", "--es", "mode", mode])
        for output in ("Broadcast completed: result=0", 'Broadcast completed: result=-1, data="wallpaper_rejected"', ""):
            acknowledgment[0] = output
            with self.assertRaises(server.ApiError) as caught:
                bridge.command("wallpaper", "auto")
            self.assertEqual(caught.exception.code, "wallpaper_unavailable")
        before = len(calls)
        for value in ("night; reboot", "https://example.com/image", "", "MORNING"):
            with self.assertRaises(server.ApiError):
                bridge.command("wallpaper", value)
        self.assertEqual(len(calls), before)


class WeatherTests(unittest.TestCase):
    def test_disabled_forecast_never_fetches(self):
        def forbidden(_url):
            self.fail("Disabled forecasts cannot contact a provider")
        cache = features.WeatherForecast(None, "UTC", fetcher=forbidden)
        self.assertEqual(cache.status()["state"], "disabled")
        self.assertEqual(cache.status()["daily"], [])

    def test_fixed_endpoint_parameters_shared_cache_stale_and_backoff(self):
        now, calls, failing = [0.0], [], [False]
        def fetch(url):
            calls.append(url)
            if failing[0]:
                raise OSError("offline")
            return forecast()
        cache = features.WeatherForecast(SETTINGS, "America/Los_Angeles", fetcher=fetch,
            clock=lambda: now[0], wall_clock=lambda: 1000)
        first = cache.status()
        self.assertEqual(first["state"], "ready")
        self.assertEqual(first["temperatureUnit"], "C")
        self.assertEqual(len(first["daily"]), 7)
        parsed = urlsplit(calls[0])
        self.assertEqual(parsed.scheme + "://" + parsed.netloc + parsed.path, features.WEATHER_ENDPOINT)
        query = parse_qs(parsed.query)
        self.assertEqual(query["forecast_days"], ["7"])
        self.assertEqual(query["timezone"], ["America/Los_Angeles"])
        self.assertEqual(query["latitude"], ["12.3"])
        first["daily"][0]["high"] = -100
        now[0] = 899
        self.assertEqual(cache.status()["daily"][0]["high"], 22)
        self.assertEqual(len(calls), 1)
        now[0] = 900
        failing[0] = True
        stale = cache.status()
        self.assertEqual(stale["state"], "stale")
        self.assertEqual(stale["sampledAt"], first["sampledAt"])
        now[0] = 959
        cache.status()
        self.assertEqual(len(calls), 2)
        now[0] = 960
        cache.status()
        self.assertEqual(len(calls), 3)

    def test_weather_cache_rechecks_interval_after_lock_and_computes_local_today(self):
        now, calls = [0.0], []
        # 2026-10-25 02:00 UTC is still October 24 in Los Angeles.
        clock = 1792893600
        cache = features.WeatherForecast(SETTINGS, "America/Los_Angeles",
            fetcher=lambda url: calls.append(url) or forecast(), clock=lambda: now[0], wall_clock=lambda: clock)
        self.assertEqual(cache.status()["localDate"], "2026-10-24")
        now[0] = 900
        class InterleavedLock:
            def acquire(self, blocking=False):
                cache.last_attempt = cache.last_success = now[0]
                return True
            def release(self): pass
        cache.lock = InterleavedLock()
        self.assertEqual(cache.status()["state"], "ready")
        self.assertEqual(len(calls), 1)

    def test_unavailable_is_not_zero_temperature_and_has_backoff(self):
        calls = []
        def broken(url):
            calls.append(url)
            raise ValueError("bad response")
        cache = features.WeatherForecast(SETTINGS, "UTC", fetcher=broken)
        result = cache.status()
        self.assertEqual(result["state"], "unavailable")
        self.assertIsNone(result["current"])
        self.assertIsNone(result["sampledAt"])
        cache.status()
        self.assertEqual(len(calls), 1)

    def test_truncated_http_and_deep_json_preserve_cache_or_report_unavailable(self):
        class Context:
            def __enter__(self): return response
            def __exit__(self, *_args): return False
        response = types.SimpleNamespace(status=200,
            headers=types.SimpleNamespace(get_content_type=lambda: "application/json"))
        opener = types.SimpleNamespace(open=lambda request, timeout: Context())
        def incomplete(_size):
            raise IncompleteRead(b'{"current":', 100)
        def invalid_http(_size):
            raise HTTPException("Invalid chunk framing")
        def deep_json(_size):
            return b"[" * 1500 + b"0" + b"]" * 1500
        for broken_read in (incomplete, invalid_http, deep_json):
            with self.subTest(failure=broken_read.__name__), patch.object(features, "build_opener", return_value=opener):
                now = [0.0]
                response.read = lambda _size: json.dumps(forecast()).encode()
                cache = features.WeatherForecast(SETTINGS, "UTC", clock=lambda: now[0], wall_clock=lambda: 1000)
                first = cache.status()
                self.assertEqual(first["state"], "ready")
                now[0] = 900
                response.read = broken_read
                stale = cache.status()
                self.assertEqual(stale["state"], "stale")
                self.assertEqual(stale["sampledAt"], first["sampledAt"])
                self.assertEqual(stale["daily"], first["daily"])
                empty = features.WeatherForecast(SETTINGS, "UTC").status()
                self.assertEqual(empty["state"], "unavailable")
                self.assertIsNone(empty["current"])
                self.assertEqual(empty["daily"], [])

    def test_nullable_values_and_rejection_of_invalid_or_partial_forecasts(self):
        nullable = forecast()
        nullable["daily"]["temperature_2m_max"][0] = None
        self.assertIsNone(features.parse_weather(nullable)[1][0]["high"])
        variants = []
        for field, value in (("time", "bad-time"), ("temperature_2m", float("nan")), ("weather_code", 500), ("is_day", True)):
            sample = forecast()
            sample["current"][field] = value
            variants.append(sample)
        for field, value in (("time", "2026-10-25"), ("temperature_2m_max", float("inf")),
                             ("temperature_2m_min", 100), ("precipitation_probability_max", -1)):
            sample = forecast()
            sample["daily"][field][1] = value
            variants.append(sample)
        short = forecast()
        short["daily"]["time"].pop()
        variants.extend([short, {"error": True}, None])
        for sample in variants:
            with self.subTest(sample=sample), self.assertRaises(ValueError):
                features.parse_weather(sample)

    def test_configuration_rejects_urls_invalid_coords_and_unknown_fields(self):
        for field, value in (("latitude", True), ("latitude", 91), ("latitude", 10 ** 1000), ("longitude", float("inf")),
                             ("longitude", "https://example.com"), ("label", "x\ny"), ("temperature_unit", "kelvin")):
            settings = dict(SETTINGS, **{field: value})
            with self.assertRaises(ValueError):
                features.validate_weather(settings)
        with self.assertRaises(ValueError):
            features.validate_weather(dict(SETTINGS, url="https://example.com"))

    def test_fetch_is_size_bounded_uses_tls_endpoint_and_rejects_redirects(self):
        response = types.SimpleNamespace(status=200)
        response.headers = types.SimpleNamespace(get_content_type=lambda: "application/json")
        sizes = []
        response.read = lambda size: sizes.append(size) or json.dumps(forecast()).encode()
        class Context:
            def __enter__(self): return response
            def __exit__(self, *_args): return False
        opener = types.SimpleNamespace(open=lambda request, timeout: Context())
        with patch.object(features, "build_opener", return_value=opener):
            self.assertEqual(features.fetch_weather(features.WEATHER_ENDPOINT)["current"]["temperature_2m"], 20)
            self.assertEqual(sizes, [65537])
            response.read = lambda size: b"x" * size
            with self.assertRaises(ValueError):
                features.fetch_weather(features.WEATHER_ENDPOINT)
        self.assertIsNone(features.NoRedirects().redirect_request(None, None, None, None, None))


class FeatureRequestTests(unittest.TestCase):
    setUp = base.RequestTests.setUp
    tearDown = base.RequestTests.tearDown
    request = base.RequestTests.request

    def test_reads_require_auth_and_never_drive_default_status(self):
        calls = []
        self.app.device = types.SimpleNamespace(status=lambda: calls.append("device") or {"stale": True})
        self.app.weather = types.SimpleNamespace(status=lambda: calls.append("weather") or {"state": "disabled"})
        self.request(path="/api/status", auth=True)
        self.assertEqual(calls, [])
        for name in ("device", "weather"):
            path = "/api/" + name
            self.assertEqual(self.request(path=path)[0], 401)
            self.assertEqual(calls, [] if name == "device" else ["device"])
            code, _, content = self.request(path=path, auth=True)
            self.assertEqual(code, 200)
            self.assertIsInstance(json.loads(content), dict)
        self.assertEqual(calls, ["device", "weather"])
        self.assertEqual(self.request("POST", "/api/weather", {}, auth=True)[0], 405)

    def test_espn_alias_uses_existing_authentication_and_strict_app_body(self):
        self.assertEqual(self.request("POST", "/api/app", {"app": "espn"})[0], 401)
        for payload in ({"app": "com.espn.gtv/other.Component"}, {"app": "espn", "install": True}):
            self.assertEqual(self.request("POST", "/api/app", payload, auth=True)[0], 400)
        self.assertEqual(self.bridge.commands, [])
        self.assertEqual(self.request("POST", "/api/app", {"app": "espn"}, auth=True)[0], 200)
        self.assertEqual(self.bridge.commands, [("app", "espn")])

    def test_wallpaper_strict_payload_auth_origin_and_fixed_mode(self):
        self.assertEqual(self.request("POST", "/api/wallpaper", {"mode": "night"})[0], 401)
        for payload in ({"mode": "night;reboot"}, {"mode": 3}, {"mode": "night", "url": "x"}, {}, []):
            self.assertEqual(self.request("POST", "/api/wallpaper", payload, auth=True)[0], 400)
        self.assertEqual(self.request("POST", "/api/wallpaper", {"mode": "night"}, auth=True,
            headers=[("Origin", "http://attacker.example")])[0], 403)
        self.assertEqual(self.bridge.commands, [])
        for mode in ("night", "annapurna", "pashupati"):
            code, _, content = self.request("POST", "/api/wallpaper", {"mode": mode}, auth=True)
            self.assertEqual((code, json.loads(content)), (200, {"ok": True, "mode": mode}))
        self.assertEqual(self.bridge.commands, [("wallpaper", mode) for mode in ("night", "annapurna", "pashupati")])

    def test_feature_rate_limit_is_shared_between_endpoints(self):
        self.app.device = self.app.weather = types.SimpleNamespace(status=lambda: {})
        for _ in range(15):
            self.assertEqual(self.request(path="/api/device", auth=True)[0], 200)
            self.assertEqual(self.request(path="/api/weather", auth=True)[0], 200)
        self.assertEqual(self.request(path="/api/device", auth=True)[0], 429)
