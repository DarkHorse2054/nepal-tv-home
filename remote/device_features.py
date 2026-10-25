"""Bounded read-only device samples and an optional cached weather forecast.

Importing this module performs no network or device I/O. Weather uses only the
fixed HTTPS Open-Meteo endpoint; clients cannot supply URLs or shell arguments.
"""
from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
from http.client import HTTPException
import json
import math
import re
import threading
import time
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo

WALLPAPER_MODES = frozenset(("auto", "morning", "dusk", "night"))
WEATHER_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
ATTRIBUTION = {"name": "Open-Meteo", "url": "https://open-meteo.com/"}
MAX_WEATHER_BYTES = 65536


def validate_weather(value):
    if value is None:
        return None
    if not isinstance(value, dict) or set(value) != {"latitude", "longitude", "label", "temperature_unit"}:
        raise ValueError("Weather fields must be latitude, longitude, label and temperature_unit")
    result = dict(value)
    for field, limit in (("latitude", 90), ("longitude", 180)):
        coordinate = value[field]
        if type(coordinate) not in (int, float) or not -limit <= coordinate <= limit or not math.isfinite(coordinate):
            raise ValueError("Weather " + field + " must be a finite geographical coordinate")
    label = value["label"]
    if not isinstance(label, str) or not label.strip() or len(label) > 64 or not label.isprintable():
        raise ValueError("Weather label must be printable and at most 64 characters")
    result["label"] = label.strip()
    if value["temperature_unit"] not in ("fahrenheit", "celsius"):
        raise ValueError("Weather temperature_unit must be fahrenheit or celsius")
    return result


def utc_timestamp(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


def usage(total, available, used=None):
    if total <= 0 or total > 1024 ** 5 or not 0 <= available <= total:
        return None
    used = total - available if used is None else used
    if not 0 <= used <= total or used + available > total:
        return None
    return {"totalBytes": total, "availableBytes": available, "usedBytes": used,
            "usedPercent": round(used * 100 / total, 1)}


def parse_memory(raw):
    if not isinstance(raw, str) or len(raw) > 32768:
        return None
    fields = {}
    for key, value in re.findall(r"^(MemTotal|MemAvailable):\s+([0-9]{1,12})\s+kB\s*$", raw, re.MULTILINE):
        if key in fields:
            return None
        fields[key] = int(value) * 1024
    if set(fields) != {"MemTotal", "MemAvailable"}:
        return None
    return usage(fields["MemTotal"], fields["MemAvailable"])


def parse_storage(raw):
    if not isinstance(raw, str) or len(raw) > 32768:
        return None
    rows = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) >= 6 and parts[-1] == "/data" and re.fullmatch(r"[0-9]{1,3}%", parts[-2]):
            values = parts[-5:-2]
            if all(re.fullmatch(r"[0-9]{1,12}", value) for value in values):
                total, used, available = (int(value) * 1024 for value in values)
                rows.append(usage(total, available, used))
    return rows[0] if len(rows) == 1 else None


class DeviceSamples:
    """One ten-second cache shared by all paired clients; no background polling."""
    def __init__(self, readings, clock=time.monotonic, wall_clock=time.time):
        self.readings, self.clock, self.wall_clock = readings, clock, wall_clock
        self.lock = threading.Lock()
        self.last_attempt = self.last_success = float("-inf")
        self.sampled_at = None
        self.online = False
        self.memory = self.storage = None
        self.failed = True

    def snapshot(self):
        age = max(0, self.clock() - self.last_success) if self.sampled_at else None
        return {"online": self.online, "sampledAt": self.sampled_at,
                "ageSeconds": round(age, 1) if age is not None else None,
                "stale": self.failed or age is None or age >= 10,
                "memory": deepcopy(self.memory), "storage": deepcopy(self.storage)}

    def status(self):
        if self.clock() - self.last_attempt < 10 or not self.lock.acquire(blocking=False):
            return self.snapshot()
        try:
            if self.clock() - self.last_attempt < 10:
                return self.snapshot()  # Another caller may have refreshed before this lock acquisition.
            self.last_attempt = self.clock()
            online, memory, storage = self.readings()
            if online is None:
                return self.snapshot()  # A control already owns ADB; keep the previous sample.
            self.online = bool(online)
            self.failed = not self.online
            if self.online:
                self.memory, self.storage = parse_memory(memory), parse_storage(storage)
                self.last_success, self.sampled_at = self.clock(), utc_timestamp(self.wall_clock())
            return self.snapshot()
        finally:
            self.lock.release()


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def fetch_weather(url):
    request = Request(url, headers={"Accept": "application/json", "User-Agent": "NepalRemote/1.0"})
    # Normal certificate validation remains enabled. A provider redirect cannot
    # turn this fixed-host integration into a request to another destination.
    with build_opener(NoRedirects()).open(request, timeout=3) as response:
        if response.status != 200 or response.headers.get_content_type() != "application/json":
            raise ValueError("Invalid weather response")
        raw = response.read(MAX_WEATHER_BYTES + 1)
    if len(raw) > MAX_WEATHER_BYTES:
        raise ValueError("Weather response too large")
    return json.loads(raw.decode("utf-8"), parse_constant=lambda _value: (_ for _ in ()).throw(ValueError("Nonfinite weather number")))


def weather_number(value, minimum, maximum):
    if value is None:
        return None
    if type(value) not in (int, float) or not minimum <= value <= maximum or not math.isfinite(value):
        raise ValueError("Invalid weather value")
    return value


def weather_code(value):
    if value is None:
        return None
    if type(value) is not int or not 0 <= value <= 99:
        raise ValueError("Invalid weather code")
    return value


def parse_weather(value):
    if not isinstance(value, dict) or value.get("error"):
        raise ValueError("Invalid weather object")
    current, daily = value.get("current"), value.get("daily")
    if not isinstance(current, dict) or not isinstance(daily, dict):
        raise ValueError("Missing weather fields")
    stamp = current.get("time")
    if not isinstance(stamp, str) or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d", stamp):
        raise ValueError("Invalid weather time")
    datetime.fromisoformat(stamp)
    is_day = current.get("is_day")
    if is_day is not None and (type(is_day) is not int or is_day not in (0, 1)):
        raise ValueError("Invalid daylight value")
    parsed_current = {"time": stamp, "temperature": weather_number(current.get("temperature_2m"), -200, 200),
                      "weatherCode": weather_code(current.get("weather_code")),
                      "isDay": bool(is_day) if is_day is not None else None}
    names = ("time", "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max")
    if any(not isinstance(daily.get(name), list) or len(daily[name]) != 7 for name in names):
        raise ValueError("A complete seven-day forecast is required")
    days = []
    previous = None
    for stamp, code, high, low, rain in zip(*(daily[name] for name in names)):
        if not isinstance(stamp, str) or not re.fullmatch(r"\d{4}-\d\d-\d\d", stamp):
            raise ValueError("Invalid forecast date")
        day = date.fromisoformat(stamp)
        if previous is not None and day != previous + timedelta(days=1):
            raise ValueError("Nonconsecutive forecast dates")
        previous = day
        high, low = weather_number(high, -200, 200), weather_number(low, -200, 200)
        if high is not None and low is not None and low > high:
            raise ValueError("Inverted forecast temperature")
        days.append({"date": stamp, "weatherCode": weather_code(code), "high": high, "low": low,
                     "precipitationProbability": weather_number(rain, 0, 100)})
    return parsed_current, days


class WeatherForecast:
    """One optional city forecast, shared cache and failure backoff, no timers."""
    def __init__(self, settings, local_timezone, fetcher=fetch_weather, clock=time.monotonic, wall_clock=time.time):
        self.settings = validate_weather(settings)
        self.timezone = ZoneInfo(local_timezone).key
        self.fetcher, self.clock, self.wall_clock = fetcher, clock, wall_clock
        self.lock = threading.Lock()
        self.last_attempt = self.last_success = float("-inf")
        self.failed = True
        self.sampled_at = None
        self.current, self.daily = None, []

    def snapshot(self):
        age = max(0, self.clock() - self.last_success) if self.sampled_at else None
        enabled = self.settings is not None
        state = "disabled" if not enabled else "unavailable" if self.sampled_at is None else "stale" if self.failed or age >= 900 else "ready"
        return {"enabled": enabled, "state": state,
                "localDate": datetime.fromtimestamp(self.wall_clock(), timezone.utc).astimezone(ZoneInfo(self.timezone)).date().isoformat(),
                "location": self.settings["label"] if enabled else None,
                "temperatureUnit": ("F" if self.settings["temperature_unit"] == "fahrenheit" else "C") if enabled else None,
                "sampledAt": self.sampled_at, "ageSeconds": round(age, 1) if age is not None else None,
                "current": deepcopy(self.current), "daily": deepcopy(self.daily), "attribution": dict(ATTRIBUTION)}

    def status(self):
        if self.settings is None:
            return self.snapshot()
        interval = 60 if self.failed else 900
        if self.clock() - self.last_attempt < interval or not self.lock.acquire(blocking=False):
            return self.snapshot()
        try:
            interval = 60 if self.failed else 900
            if self.clock() - self.last_attempt < interval:
                return self.snapshot()  # Recheck after taking the shared refresh lock.
            self.last_attempt = self.clock()
            query = {"latitude": self.settings["latitude"], "longitude": self.settings["longitude"],
                     "current": "temperature_2m,weather_code,is_day",
                     "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                     "temperature_unit": self.settings["temperature_unit"], "timezone": self.timezone, "forecast_days": 7}
            try:
                current, daily = parse_weather(self.fetcher(WEATHER_ENDPOINT + "?" + urlencode(query)))
                self.current, self.daily = current, daily
                self.last_success, self.sampled_at = self.clock(), utc_timestamp(self.wall_clock())
                self.failed = False
            except (OSError, TimeoutError, ValueError, TypeError, KeyError, HTTPException, RecursionError):
                self.failed = True
            return self.snapshot()
        finally:
            self.lock.release()
