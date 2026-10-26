#!/usr/bin/env python3
"""Paired local-LAN remote for an explicitly configured, user-owned Fire TV. Python 3.9+."""
import argparse
import asyncio
import contextlib
import importlib.util
from collections import defaultdict, deque
import csv
from datetime import date, datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import mimetypes
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import unquote, urlsplit
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent

def load_device_features():
    spec = importlib.util.spec_from_file_location("nepal_remote_device_features", HERE / "device_features.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FEATURES = load_device_features()


def load_screen_preview():
    spec = importlib.util.spec_from_file_location("nepal_screen_preview", HERE / "screen_preview.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PREVIEW = load_screen_preview()


def load_local_config():
    spec = importlib.util.spec_from_file_location("portable_remote_config", HERE / "config.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CONFIG = load_local_config()

KEYS = {"up": 19, "down": 20, "left": 21, "right": 22, "ok": 23,
        "home": 3, "back": 4, "menu": 82, "play_pause": 85,
        "rewind": 89, "fast_forward": 90, "wake": 224, "sleep": 223,
        "enter": 66, "delete": 67}
# Exact components from the launcher's saved on-device app order.
APPS = {
    "netflix": "com.netflix.ninja/.MainActivity",
    "youtube": "com.amazon.firetv.youtube/dev.cobalt.app.MainActivity",
    "disney": "com.disney.disneyplus/com.bamtechmedia.dominguez.main.MainActivity",
    "paramount": "com.cbs.ott/com.paramount.android.pplus.features.splash.tv.SplashMediatorActivity",
    "hulu": "com.hulu.plus/.SplashActivity",
    "npl": "app.nepal.npltv/.MainActivity",
    "espn": "com.espn.gtv/com.espn.startup.presentation.StartupActivity",
}
TEXT_MAX_LENGTH = 160
TEXT_MAX_SEGMENTS = 4
MONTHS = ("Baisakh", "Jestha", "Ashadh", "Shrawan", "Bhadra", "Ashwin",
          "Kartik", "Mangsir", "Poush", "Magh", "Falgun", "Chaitra")
CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; "
       "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; "
       "manifest-src 'self'; base-uri 'none'; form-action 'self'; "
       "frame-ancestors 'none'; object-src 'none'")


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON member")
        result[key] = value
    return result


def validate_text(value):
    """Fire OS input supports printable ASCII; reject unsupported input before I/O."""
    if not isinstance(value, str) or not 1 <= len(value) <= TEXT_MAX_LENGTH:
        raise ApiError(400, "invalid_text_length")
    if any(not 32 <= ord(character) <= 126 for character in value):
        raise ApiError(400, "unsupported_text_character")
    # Each literal %s needs a separate input process; keep work within the
    # existing command deadline instead of allowing a long remote shell chain.
    if value.count("%s") + 1 > TEXT_MAX_SEGMENTS:
        raise ApiError(400, "split_text_required")
    return value


def text_command(value):
    """Quote for Android's remote POSIX shell, independently of the local argv.

    Android's input tool translates the literal pair %s into a space. Splitting
    only that boundary preserves both literal %s and ordinary spaces. Every
    variable part is shell-quoted; only fixed input-text clauses are executable.
    """
    value = validate_text(value)
    return " && ".join("input text " + shlex.quote(part)
                       for part in re.split(r"(?<=%)(?=s)", value))


class ApiError(Exception):
    def __init__(self, status, code):
        self.status = status
        self.code = code


class WindowLimiter:
    """Bounded memory sliding-window limiter, shared by all request threads."""
    def __init__(self, maximum, seconds, max_keys=512, clock=time.monotonic):
        self.maximum, self.seconds, self.max_keys, self.clock = maximum, seconds, max_keys, clock
        self.events = defaultdict(deque)
        self.lock = threading.Lock()

    def allow(self, key):
        now = self.clock()
        with self.lock:
            for old_key in list(self.events):
                events = self.events[old_key]
                while events and events[0] <= now - self.seconds:
                    events.popleft()
                if not events:
                    del self.events[old_key]
            if key not in self.events and len(self.events) >= self.max_keys:
                return False
            events = self.events[key]
            if len(events) >= self.maximum:
                return False
            events.append(now)
            return True


class Pairing:
    def __init__(self, pin=None, clock=time.monotonic):
        self.pin = pin or f"{secrets.randbelow(1_000_000):06d}"
        self.clock = clock
        self.tokens = {}
        self.lock = threading.Lock()
        self.lifetime = 8 * 60 * 60
        self.pair_ip = WindowLimiter(5, 60, clock=clock)
        self.pair_global = WindowLimiter(30, 60, clock=clock)
        self.auth_failures = WindowLimiter(20, 60, clock=clock)

    def pair(self, pin, client_ip):
        if not self.pair_ip.allow(client_ip) or not self.pair_global.allow("all"):
            raise ApiError(429, "pair_rate_limited")
        if not isinstance(pin, str) or not re.fullmatch(r"\d{6}", pin, flags=re.ASCII):
            raise ApiError(400, "invalid_pin")
        if not secrets.compare_digest(pin, self.pin):
            raise ApiError(401, "incorrect_pin")
        token = secrets.token_urlsafe(32)
        now = self.clock()
        with self.lock:
            self.tokens = {key: expiry for key, expiry in self.tokens.items() if expiry > now}
            if len(self.tokens) >= 32:
                self.tokens.pop(min(self.tokens, key=self.tokens.get))
            self.tokens[hashlib.sha256(token.encode()).digest()] = now + self.lifetime
        return {"token": token, "expiresIn": self.lifetime}

    def authorize(self, header, client_ip):
        if not isinstance(header, str) or not re.fullmatch(r"Bearer [A-Za-z0-9_-]{43}", header):
            if not self.auth_failures.allow(client_ip):
                raise ApiError(429, "auth_rate_limited")
            raise ApiError(401, "pair_required")
        digest = hashlib.sha256(header[7:].encode()).digest()
        with self.lock:
            expiry = self.tokens.get(digest, 0)
            if expiry > self.clock():
                return digest.hex()
            self.tokens.pop(digest, None)
        if not self.auth_failures.allow(client_ip):
            raise ApiError(429, "auth_rate_limited")
        raise ApiError(401, "session_expired")


class AdbBridge:
    """One configured TV, allowlisted actions, and bounded, remote-shell-quoted text."""
    def __init__(self, adb, fire_tv, runner=subprocess.run, clock=time.monotonic):
        self.target = CONFIG.validate_fire_tv(fire_tv)
        self.serial = self.target["host"] + ":5555"
        self.expected_model = self.target["model"]
        self.adb = str(Path(adb).resolve())
        self.runner, self.clock = runner, clock
        self.command_lock = threading.Lock()
        self.control_rate = WindowLimiter(8, 1, clock=clock)
        self.last_probe = float("-inf")
        self.last_reconnect = float("-inf")
        self.online = False

    def _run(self, args, timeout=4):
        import os
        env = dict(os.environ)
        try:
            result = self.runner([self.adb, *args], stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, timeout=timeout, env=env, shell=False)
        except (OSError, subprocess.TimeoutExpired):
            self.online = False
            raise ApiError(503, "tv_unavailable")
        return result.returncode, result.stdout.replace("\r", "").strip()

    def _probe(self, reconnect=True):
        now = self.clock()
        status, output = self._run(["-s", self.serial, "get-state"], timeout=1)
        if (status or output != "device") and reconnect and now - self.last_reconnect >= 10:
            self.last_reconnect = now
            self._run(["connect", self.serial], timeout=2)
            status, output = self._run(["-s", self.serial, "get-state"], timeout=1)
        self.online = status == 0 and output == "device"
        if self.online:
            status, model = self._run(["-s", self.serial, "shell", "getprop", "ro.product.model"], timeout=1)
            self.online = status == 0 and model == self.expected_model
        self.last_probe = now
        return self.online

    def status(self):
        if self.clock() - self.last_probe < 3:
            return self.online
        if not self.command_lock.acquire(blocking=False):
            return self.online
        try:
            try:
                return self._probe()
            except ApiError:
                self.last_probe = self.clock()
                return False
        finally:
            self.command_lock.release()

    def command(self, kind, value):
        if kind == "key" and value in KEYS:
            args = ["shell", "input", "keyevent", str(KEYS[value])]
        elif kind == "app" and value in APPS:
            args = ["shell", "am", "start", "-n", APPS[value]]
        elif kind == "text":
            args = ["shell", text_command(value)]
        elif kind == "wallpaper" and value in FEATURES.WALLPAPER_MODES:
            args = ["shell", "am", "broadcast", "--user", "0", "-n",
                    "com.gathin.tvhome/.WallpaperCommandReceiver", "-a",
                    "com.gathin.tvhome.action.SET_WALLPAPER", "--es", "mode", value]
        else:
            raise ApiError(400, "invalid_control")
        if not self.control_rate.allow("all"):
            raise ApiError(429, "control_rate_limited")
        if not self.command_lock.acquire(blocking=False):
            raise ApiError(429, "control_busy")
        try:
            if self.clock() - self.last_probe >= 3 or not self.online:
                # Reconnection belongs to status, never to a delayed input request.
                # Worst case here is 1s state + 1s model + 4s command.
                if not self._probe(reconnect=False):
                    raise ApiError(503, "tv_unavailable")
            try:
                status, output = self._run(["-s", self.serial, *args], timeout=4)
            except ApiError:
                if kind == "text":
                    # A lost acknowledgement can follow a partial/full send.
                    # Never replay text or expose it in the error response.
                    raise ApiError(503, "text_send_uncertain") from None
                raise
            if status or re.search(r"(?:^|\n)(?:Error:|Exception|SecurityException|java\.)", output):
                raise ApiError(503, "text_send_uncertain" if kind == "text" else "command_failed")
            if kind == "wallpaper" and not re.search(r'Broadcast completed: result=1, data="wallpaper_applied"(?:,|$)', output):
                raise ApiError(503, "wallpaper_unavailable")
        finally:
            self.command_lock.release()

    def device_readings(self):
        """Read only fixed system totals, never reconnect or queue behind controls."""
        if not self.command_lock.acquire(blocking=False):
            return None, None, None
        try:
            try:
                if not self._probe(reconnect=False):
                    return False, None, None
                memory_status, memory = self._run(["-s", self.serial, "shell", "cat", "/proc/meminfo"], timeout=1)
                storage_status, storage = self._run(["-s", self.serial, "shell", "df", "-k", "/data"], timeout=1)
                return True, memory if memory_status == 0 else None, storage if storage_status == 0 else None
            except ApiError:
                return False, None, None
        finally:
            self.command_lock.release()

    def capture_screen_png(self):
        # Identity checks are short; the binary capture never holds the input lock.
        if not self.command_lock.acquire(blocking=False):
            raise ApiError(429, "preview_busy")
        try:
            if not self._probe(reconnect=False):
                raise ApiError(503, "tv_unavailable")
        finally:
            self.command_lock.release()
        import os
        return PREVIEW.capture_png(self.adb, self.serial, dict(os.environ))

    def reconnect(self):
        """A fresh, serialized identity check; inputs are never queued or replayed."""
        if not self.control_rate.allow("all"):
            raise ApiError(429, "control_rate_limited")
        if not self.command_lock.acquire(blocking=False):
            raise ApiError(429, "control_busy")
        try:
            try:
                return self._probe()
            except ApiError:
                self.last_probe = self.clock()
                return False
        finally:
            self.command_lock.release()


class CivilDates:
    def __init__(self, clocks, table=HERE / "data/nepali_calendar.csv"):
        self.clocks = CONFIG.validate_clocks(clocks)
        self.years = []
        try:
            with Path(table).open(newline="") as source:
                for row in csv.DictReader(source):
                    self.years.append((date.fromisoformat(row["baisakh1_ad"]), int(row["bs_year"]),
                                       [int(row[f"m{m:02d}"]) for m in range(1, 13)]))
            self.years.sort(reverse=True)
        except (OSError, ValueError, KeyError):
            self.years = []

    def nepal_date(self, day):
        for start, year, months in self.years:
            offset = (day - start).days
            if offset < 0:
                continue
            for month, length in enumerate(months):
                if offset < length:
                    return f"{offset + 1} {MONTHS[month]} {year} BS"
                offset -= length
            break
        return day.strftime("%b %d, %Y").replace(" 0", " ")

    def status(self, now=None):
        now = now or datetime.now(timezone.utc)
        local = now.astimezone(ZoneInfo(self.clocks["local_timezone"]))
        secondary = now.astimezone(ZoneInfo(self.clocks["secondary_timezone"]))
        local_date = local.strftime("%a, %b %d").replace(" 0", " ")
        secondary_date = (self.nepal_date(secondary.date())
                          if self.clocks["secondary_calendar"] == "bikram_sambat"
                          else secondary.strftime("%b %d, %Y").replace(" 0", " "))
        secondary_time = secondary.strftime("%I:%M %p").lstrip("0")
        return {"localDate": local_date, "localTime": local.strftime("%I:%M %p").lstrip("0"),
                "localLabel": self.clocks["local_label"], "secondaryDate": secondary_date,
                "secondaryTime": secondary_time, "secondaryLabel": self.clocks["secondary_label"],
                "nepal": secondary_date, "sanMateo": local_date, "kathmanduTime": secondary_time}


SAMSUNG_EXPERIMENTAL_KEYS = frozenset(("hdmi1", "hdmi2", "hdmi3", "hdmi4"))
SAMSUNG_KEYS = frozenset(("source", "up", "down", "left", "right", "ok", "back",
                          "volume_up", "volume_down", "mute", "power_off")) | SAMSUNG_EXPERIMENTAL_KEYS
SAMSUNG_PATHS = frozenset(("/api/samsung/pair", "/api/samsung/key"))
SAMSUNG_ERROR_CODES = frozenset((
    "unsafe_token_path", "unsafe_token_file", "invalid_token_file", "invalid_token",
    "token_storage_failed", "identity_unavailable", "identity_too_large", "invalid_identity",
    "tv_unavailable", "wrong_tv_model", "unsupported_auth", "websockets_dependency_missing",
    "connect_timeout", "connection_failed", "not_connected", "connection_closed",
    "invalid_message", "authorization_denied", "too_many_messages", "send_failed",
    "pair_required", "invalid_timeout", "invalid_navigation"))


def load_samsung_module():
    """Fixed local module path also works when tests import server.py by filename."""
    try:
        spec = importlib.util.spec_from_file_location("nepal_samsung_control", HERE / "samsung.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    except Exception:
        # The Fire TV remote remains available if this optional module is absent.
        return None


SAMSUNG_MODULE = load_samsung_module()


class SamsungBridge:
    """Separate from the ADB lock. No implicit pairing, retries, or poll-time TV I/O."""
    def __init__(self, target=None, *, module=SAMSUNG_MODULE, remote_factory=None, store=None,
                 clock=time.monotonic, wall_clock=time.time):
        self.target = CONFIG.validate_samsung(target)
        self.module = module
        self.store = store if store is not None else module.TokenStore(self.target) if module and self.target else None
        self.remote_factory = remote_factory or (module.SamsungRemote if module and self.target else None)
        self.lock = threading.Lock()
        self.pair_rate = WindowLimiter(1, 60, clock=clock)
        self.key_rate = WindowLimiter(4, 1, clock=clock)
        self.needs_pair = False
        self.wall_clock = wall_clock
        self.last_sent = None

    def status(self):
        paired = False
        if self.store is not None and not self.needs_pair:
            try:
                paired = bool(self.store.load())
            except Exception:
                pass
        return {"enabled": self.target is not None, "model": self.target["model"] if self.target else None, "paired": paired,
                "sourceReadback": False, "directHdmi": False, "experimentalHdmi": True,
                "sourceLabels": ({**{key: "HDMI " + key[-1] for key in SAMSUNG_EXPERIMENTAL_KEYS},
                                  **self.target.get("source_labels", {})} if self.target else {}),
                "lastSent": dict(self.last_sent) if self.last_sent is not None else None}

    async def _execute(self, *, pair=False, key=None):
        remote = self.remote_factory(target=self.target, token_store=self.store)
        try:
            async def operation():
                result = await remote.connect(pair=pair, wait_seconds=30 if pair else 3)
                state = result.get("state")
                if pair:
                    if state not in ("authorized", "pending"):
                        raise ApiError(503, "samsung_unavailable")
                    self.needs_pair = state != "authorized"
                    return dict(self.status(), state=state)
                if state != "authorized":
                    self.needs_pair = True
                    code = "samsung_pair_required" if state == "pair_required" else "samsung_authorization_pending"
                    raise ApiError(409, code)
                await remote.navigate(key)
                # In-memory send history is not Samsung acceptance or current-input
                # readback. Failed/pending requests leave the previous entry intact.
                self.last_sent = {"key": key,
                                  "sentAt": datetime.fromtimestamp(self.wall_clock(), timezone.utc)
                                  .isoformat(timespec="seconds").replace("+00:00", "Z"),
                                  "experimental": key in SAMSUNG_EXPERIMENTAL_KEYS,
                                  "inputVerified": False}
                return {"ok": True, "state": "sent", **self.last_sent}
            try:
                # Leave two seconds for close; pairing completes within 30 seconds.
                return await asyncio.wait_for(operation(), timeout=27 if pair else 8)
            except asyncio.TimeoutError:
                if pair:
                    return dict(self.status(), state="pending")
                raise ApiError(503, "samsung_timeout") from None
        finally:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(remote.close(), timeout=2)

    def _call(self, *, pair=False, key=None):
        if self.target is None:
            raise ApiError(409, "samsung_not_configured")
        if not self.lock.acquire(blocking=False):
            raise ApiError(429, "samsung_busy")
        try:
            if not pair and self.needs_pair:
                raise ApiError(409, "samsung_pair_required")
            limiter = self.pair_rate if pair else self.key_rate
            if not limiter.allow("all"):
                raise ApiError(429, "samsung_pair_rate_limited" if pair else "samsung_key_rate_limited")
            if self.module is None or self.remote_factory is None:
                raise ApiError(503, "samsung_dependency_missing")
            if pair:
                self.needs_pair = True
            try:
                return asyncio.run(self._execute(pair=pair, key=key))
            except ApiError:
                raise
            except self.module.SamsungError as error:
                if error.code in ("authorization_denied", "pair_required"):
                    self.needs_pair = True
                code = error.code if error.code in SAMSUNG_ERROR_CODES else "unavailable"
                raise ApiError(503, "samsung_" + code) from None
            except Exception:
                raise ApiError(503, "samsung_unavailable") from None
        finally:
            self.lock.release()

    def pair(self):
        return self._call(pair=True)

    def key(self, value):
        if not isinstance(value, str) or value not in SAMSUNG_KEYS:
            raise ApiError(400, "invalid_samsung_key")
        return self._call(key=value)


class RemoteApp:
    def __init__(self, bridge, hosts, port, dist=HERE / "dist", pairing=None, samsung=None, config=None, device=None, weather=None, preview=None):
        self.bridge, self.hosts, self.port = bridge, set(hosts), port
        self.dist = Path(dist).resolve()
        self.pairing = pairing or Pairing()
        self.config = CONFIG.validate(config)
        self.dates = CivilDates(self.config["clocks"])
        self.samsung = samsung if samsung is not None else SamsungBridge(self.config["samsung"])
        self.status_rate = WindowLimiter(60, 60)
        self.feature_rate = WindowLimiter(30, 60)
        self.preview_rate = WindowLimiter(20, 60)
        self.preview = preview if preview is not None else PREVIEW.ScreenPreview(lambda: self.bridge.capture_screen_png())
        self.device = device if device is not None else FEATURES.DeviceSamples(lambda: self.bridge.device_readings())
        self.weather = weather if weather is not None else FEATURES.WeatherForecast(
            self.config.get("weather"), self.config["clocks"]["local_timezone"])

    def authority(self, raw):
        if not raw or any(c in raw for c in "@/\\#? \t\r\n"):
            raise ApiError(421, "invalid_host")
        try:
            parsed = urlsplit("http://" + raw)
            host = parsed.hostname
            port = parsed.port or 80
        except ValueError:
            raise ApiError(421, "invalid_host")
        if host not in self.hosts or port != self.port:
            raise ApiError(421, "invalid_host")
        return host, port

    def validate_headers(self, headers, method):
        hosts = headers.get_all("Host", [])
        if len(hosts) != 1:
            raise ApiError(400, "invalid_host")
        authority = self.authority(hosts[0])
        if len(list(headers.items())) > 32 or sum(len(k)+len(v)+4 for k,v in headers.items()) > 8192:
            raise ApiError(431, "headers_too_large")
        if len(headers.get_all("Authorization", [])) > 1:
            raise ApiError(400, "invalid_authorization")
        if headers.get("Sec-Fetch-Site") == "cross-site":
            raise ApiError(403, "cross_origin_forbidden")
        origins = headers.get_all("Origin", [])
        if len(origins) > 1 or (method == "POST" and not origins):
            raise ApiError(403, "origin_required")
        if origins:
            try:
                origin = urlsplit(origins[0])
                if origin.scheme != "http" or origin.path or origin.query or origin.fragment or origin.username or origin.password:
                    raise ValueError()
                if self.authority(origin.netloc) != authority:
                    raise ValueError()
            except (ValueError, ApiError):
                raise ApiError(403, "cross_origin_forbidden")
        if headers.get("Transfer-Encoding"):
            raise ApiError(400, "transfer_encoding_unsupported")
        lengths = headers.get_all("Content-Length", [])
        if len(lengths) > 1:
            raise ApiError(400, "invalid_length")
        length = lengths[0] if lengths else "0"
        if not re.fullmatch(r"[0-9]{1,6}", length):
            raise ApiError(400, "invalid_length")
        if int(length) > 2048:
            raise ApiError(413, "body_too_large")
        if method in ("GET", "HEAD") and int(length):
            raise ApiError(400, "unexpected_body")
        return int(length)

    def static(self, raw_path):
        path = unquote(raw_path)
        if "\x00" in path or "\\" in path or any(part.startswith(".") for part in path.split("/") if part):
            raise ApiError(404, "not_found")
        target = (self.dist / ("index.html" if path == "/" else path.lstrip("/"))).resolve()
        if not target.is_relative_to(self.dist) or not target.is_file():
            raise ApiError(404, "not_found")
        if target.suffix.lower() not in {".html", ".css", ".js", ".png", ".jpg", ".jpeg", ".svg", ".woff2", ".ttf", ".ico", ".webmanifest"}:
            raise ApiError(404, "not_found")
        if target.stat().st_size > 8 * 1024 * 1024:
            raise ApiError(413, "asset_too_large")
        mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if target.suffix.lower() == ".webmanifest":
            mime = "application/manifest+json; charset=utf-8"
        if target.suffix in (".js", ".css", ".html", ".svg"):
            mime += "; charset=utf-8"
        return target.read_bytes(), mime

    def get_status(self, token_id):
        if not self.status_rate.allow(token_id):
            raise ApiError(429, "status_rate_limited")
        return {"online": self.bridge.status(),
                "device": {"name": self.config["fire_tv"]["name"], "model": self.config["fire_tv"]["model"],
                           "serial": self.config["fire_tv"]["host"] + ":5555"},
                "date": self.dates.status(), "samsung": self.samsung.status(),
                "serverTime": datetime.now(timezone.utc).isoformat()}


    def preview_frame(self, token_id):
        if not self.preview_rate.allow(token_id):
            raise ApiError(429, "preview_rate_limited")
        try:
            return self.preview.frame()
        except PREVIEW.PreviewError as error:
            raise ApiError(error.status, error.code) from None

    def get_feature(self, name, token_id):
        if not self.feature_rate.allow(token_id):
            raise ApiError(429, "feature_rate_limited")
        return (self.device if name == "device" else self.weather).status()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "NepalRemote"
    sys_version = ""

    def setup(self):
        self.request.settimeout(5)
        super().setup()

    def log_message(self, *_args):
        pass  # Never log pairing bodies, tokens, client identifiers, or URL query strings.

    def send_response(self, code, message=None):
        super().send_response(code, message)

    def respond(self, status, payload, mime="application/json; charset=utf-8", head=False, extra_headers=()):
        body = json.dumps(payload, separators=(",", ":")).encode() if isinstance(payload, dict) else payload
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", CSP)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header("Connection", "close")
        for name, value in extra_headers:
            self.send_header(name, str(value))
        if status == 401:
            self.send_header("WWW-Authenticate", 'Bearer realm="fire-tv-remote"')
        if status == 429:
            self.send_header("Retry-After", "60" if isinstance(payload, dict) and payload.get("error") == "samsung_pair_rate_limited" else "1")
        self.end_headers()
        self.close_connection = True
        if not head:
            self.wfile.write(body)

    def dispatch(self, method):
        try:
            app = self.server.app
            if len(self.path) > 1024 or not self.path.startswith("/") or self.path.startswith("//"):
                raise ApiError(400, "invalid_path")
            length = app.validate_headers(self.headers, method)
            parsed = urlsplit(self.path)
            if parsed.query or parsed.fragment:
                raise ApiError(404, "not_found")
            if method in ("GET", "HEAD"):
                if parsed.path == "/api/status":
                    token = app.pairing.authorize(self.headers.get("Authorization"), self.client_address[0])
                    self.respond(200, app.get_status(token), head=method == "HEAD")
                elif parsed.path in ("/api/device", "/api/weather"):
                    token = app.pairing.authorize(self.headers.get("Authorization"), self.client_address[0])
                    self.respond(200, app.get_feature(parsed.path.rsplit("/", 1)[1], token), head=method == "HEAD")
                elif parsed.path.startswith("/api/"):
                    raise ApiError(404, "not_found")
                else:
                    body, mime = app.static(parsed.path)
                    self.respond(200, body, mime, head=method == "HEAD")
                return
            if method != "POST" or parsed.path not in ("/api/pair", "/api/key", "/api/app", "/api/text", "/api/reconnect", "/api/wallpaper", "/api/preview/frame", *SAMSUNG_PATHS):
                raise ApiError(405, "method_not_allowed")
            if parsed.path != "/api/pair":
                token_id = app.pairing.authorize(self.headers.get("Authorization"), self.client_address[0])
            if self.headers.get_content_type() != "application/json":
                raise ApiError(415, "json_required")
            try:
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError()
                payload = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json_object)
            except (ValueError, UnicodeError):
                raise ApiError(400, "invalid_json")
            if parsed.path == "/api/preview/frame":
                if not isinstance(payload, dict) or payload:
                    raise ApiError(400, "invalid_payload")
                frame, captured_at, duration_ms = app.preview_frame(token_id)
                self.respond(200, frame, "image/jpeg", extra_headers=(
                    ("X-Preview-Captured-At", captured_at),
                    ("X-Preview-Capture-Ms", duration_ms)))
                return
            if parsed.path == "/api/wallpaper":
                if (not isinstance(payload, dict) or set(payload) != {"mode"}
                        or not isinstance(payload["mode"], str) or payload["mode"] not in FEATURES.WALLPAPER_MODES):
                    raise ApiError(400, "invalid_wallpaper_mode")
                app.bridge.command("wallpaper", payload["mode"])
                self.respond(200, {"ok": True, "mode": payload["mode"]})
                return
            if parsed.path == "/api/reconnect":
                if not isinstance(payload, dict) or payload:
                    raise ApiError(400, "invalid_payload")
                self.respond(200, {"ok": True, "online": app.bridge.reconnect()})
                return
            if parsed.path == "/api/samsung/pair":
                if not isinstance(payload, dict) or payload:
                    raise ApiError(400, "invalid_payload")
                self.respond(200, app.samsung.pair())
                return
            if parsed.path == "/api/samsung/key":
                if not isinstance(payload, dict) or set(payload) != {"key"} or not isinstance(payload["key"], str):
                    raise ApiError(400, "invalid_payload")
                if payload["key"] not in SAMSUNG_KEYS:
                    raise ApiError(400, "invalid_samsung_key")
                self.respond(200, app.samsung.key(payload["key"]))
                return
            field = {"/api/pair": "pin", "/api/key": "key", "/api/app": "app", "/api/text": "text"}[parsed.path]
            if not isinstance(payload, dict) or set(payload) != {field} or not isinstance(payload[field], str):
                raise ApiError(400, "invalid_payload")
            if field == "pin":
                self.respond(200, app.pairing.pair(payload[field], self.client_address[0]))
                return
            if field == "text":
                validate_text(payload[field])
                app.bridge.command(field, payload[field])
                self.respond(200, {"ok": True})
                return
            choices = KEYS if field == "key" else APPS
            if payload[field] not in choices:
                raise ApiError(400, "invalid_" + field)
            app.bridge.command(field, payload[field])
            self.respond(200, {"ok": True})
        except ApiError as exc:
            payload = {"error": exc.code}
            if self.path.split("?", 1)[0] in SAMSUNG_PATHS:
                payload["state"] = "error"
            self.respond(exc.status, payload, head=method == "HEAD")
        except (OSError, TimeoutError):
            try:
                self.respond(408, {"error": "request_timeout"}, head=method == "HEAD")
            except OSError:
                pass

    def do_GET(self): self.dispatch("GET")
    def do_HEAD(self): self.dispatch("HEAD")
    def do_POST(self): self.dispatch("POST")
    def do_OPTIONS(self): self.dispatch("OPTIONS")
    def do_PUT(self): self.dispatch("PUT")
    def do_DELETE(self): self.dispatch("DELETE")


class RemoteServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, app):
        self.app = app
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def local_hosts(fire_host, extra=()):
    hosts = {"localhost", "127.0.0.1"}
    hostname = socket.gethostname().lower()
    if re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,252}", hostname):
        hosts.add(hostname)
    for host in extra:
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}\.local", host):
                raise ValueError("Extra hosts must be private/loopback IPv4 addresses or .local names")
            hosts.add(host.lower())
        else:
            if address.version != 4 or not (address.is_private or address.is_loopback):
                raise ValueError("Extra hosts must be private/loopback IPv4 addresses")
            hosts.add(str(address))
    try:
        # Route selection only: UDP connect sends no packet to the TV.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect((CONFIG.private_ipv4(fire_host), 5555))
            hosts.add(route.getsockname()[0])
    except OSError:
        pass
    return hosts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=CONFIG.DEFAULT_CONFIG,
                        help="Private JSON configuration (default: state/config.json)")
    parser.add_argument("--bind", default="0.0.0.0", choices=("0.0.0.0", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--allow-host", action="append", default=[], help="Extra local Host/IP, never a wildcard")
    parser.add_argument("--adb", type=Path, help="ADB executable (default: adb on PATH)")
    args = parser.parse_args()
    try:
        configuration = CONFIG.load(args.config)
    except CONFIG.ConfigError as error:
        parser.error(str(error) + ". Copy config.example.json to state/config.json, edit your explicit "
                     "private TV IP/model and clocks, then chmod 600 the file. No device was contacted.")
    if not 1024 <= args.port <= 65535:
        parser.error("Port must be between 1024 and 65535")
    adb = args.adb or (Path(shutil.which("adb")) if shutil.which("adb") else None)
    if adb is None or not adb.is_file():
        parser.error("ADB was not found. Install Android Platform Tools or supply --adb /absolute/path/to/adb")
    hosts = local_hosts(configuration["fire_tv"]["host"], args.allow_host)
    bridge = AdbBridge(adb, configuration["fire_tv"])
    app = RemoteApp(bridge, hosts, args.port, config=configuration)
    with RemoteServer((args.bind, args.port), app) as server:
        print(configuration["fire_tv"]["name"] + " remote", flush=True)
        for host in sorted(hosts):
            if args.bind == "127.0.0.1" and host != "127.0.0.1":
                continue
            print(f"  http://{host}:{args.port}", flush=True)
        print(f"Pairing PIN: {app.pairing.pin}", flush=True)
        print("Keep this terminal open. Ctrl+C stops the server and expires every session.", flush=True)
        try:
            server.serve_forever(poll_interval=0.5)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
