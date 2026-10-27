#!/usr/bin/env python3
"""Paired local-LAN remote for an explicitly configured, user-owned Fire TV. Python 3.11+."""
import argparse
import asyncio
import base64
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

if sys.version_info < (3, 11):
    raise SystemExit("Python 3.11+ is required; use a maintained Python with remote/requirements.txt.")

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


def load_live_video():
    spec = importlib.util.spec_from_file_location("nepal_live_video", HERE / "live_video.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


LIVE = load_live_video()


def load_fast_input():
    spec = importlib.util.spec_from_file_location("nepal_fast_input", HERE / "fast_input.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FAST_INPUT = load_fast_input()


def load_trusted_devices():
    spec = importlib.util.spec_from_file_location("nepal_trusted_devices", HERE / "trusted_devices.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TRUST = load_trusted_devices()


def load_pairing_code():
    spec = importlib.util.spec_from_file_location("nepal_pairing_code", HERE / "pairing_code.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PAIRING_CODE = load_pairing_code()


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
    "framextv": "app.nepal.framextv/.MainActivity",
}
REQUEST_READ_SECONDS = 5
TEXT_MAX_LENGTH = 160
TEXT_MAX_SEGMENTS = 4
THEATER_MAX_LENGTH = 160
THEATER_ENCODED_MAX_LENGTH = 856
THEATER_ACTION = "app.nepal.framextv.action.REQUEST"
THEATER_EXTRA = "theater_request"
MONTHS = ("Baisakh", "Jestha", "Ashadh", "Shrawan", "Bhadra", "Ashwin",
          "Kartik", "Mangsir", "Poush", "Magh", "Falgun", "Chaitra")
CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; "
       "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; "
       "media-src 'self' blob:; manifest-src 'self'; base-uri 'none'; form-action 'self'; "
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


def validate_theater_request(value):
    """Bounded Unicode text for one native request, never an Android shell string."""
    if not isinstance(value, str) or any(ord(character) < 32 or 127 <= ord(character) <= 159 for character in value):
        raise ApiError(400, "invalid_theater_request")
    clean = value.strip()
    if not 1 <= len(clean) <= THEATER_MAX_LENGTH:
        raise ApiError(400, "invalid_theater_request")
    try:
        clean.encode("utf-8", errors="strict")
    except UnicodeError:
        raise ApiError(400, "invalid_theater_request") from None
    return clean


def theater_payload(value):
    encoded = base64.urlsafe_b64encode(validate_theater_request(value).encode("utf-8")).decode("ascii").rstrip("=")
    if not 1 <= len(encoded) <= THEATER_ENCODED_MAX_LENGTH or not re.fullmatch(r"[A-Za-z0-9_-]+", encoded, re.ASCII):
        raise ApiError(400, "invalid_theater_request")
    return encoded


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
    def __init__(self, pin=None, clock=time.monotonic, trusted=None):
        self.pin = pin or f"{secrets.randbelow(1_000_000):06d}"
        self.clock = clock
        self.trusted = trusted
        self.tokens = {}
        self.token_trust = {}
        self.lock = threading.Lock()
        self.lifetime = 8 * 60 * 60
        self.pair_ip = WindowLimiter(5, 60, clock=clock)
        self.pair_global = WindowLimiter(30, 60, clock=clock)
        self.auth_failures = WindowLimiter(20, 60, clock=clock)
        self.resume_ip = WindowLimiter(20, 60, clock=clock)
        self.resume_global = WindowLimiter(120, 60, clock=clock)

    def _validate_pin(self, pin, client_ip):
        if not self.pair_ip.allow(client_ip) or not self.pair_global.allow("all"):
            raise ApiError(429, "pair_rate_limited")
        if not isinstance(pin, str) or not re.fullmatch(r"\d{6}", pin, flags=re.ASCII):
            raise ApiError(400, "invalid_pin")
        if not secrets.compare_digest(pin, self.pin):
            raise ApiError(401, "incorrect_pin")

    def _mint_locked(self, trusted_cookie=None):
        token = secrets.token_urlsafe(32)
        now = self.clock()
        self.tokens = {key: expiry for key, expiry in self.tokens.items() if expiry > now}
        self.token_trust = {key: value for key, value in self.token_trust.items() if key in self.tokens}
        if len(self.tokens) >= 32:
            oldest = min(self.tokens, key=self.tokens.get)
            self.tokens.pop(oldest)
            self.token_trust.pop(oldest, None)
        digest = hashlib.sha256(token.encode()).digest()
        self.tokens[digest] = now + self.lifetime
        if trusted_cookie is not None:
            self.token_trust[digest] = hashlib.sha256(trusted_cookie.encode()).digest()
        return {"token": token, "expiresIn": self.lifetime}

    def pair(self, pin, client_ip):
        """Legacy API remains an ephemeral eight-hour bearer session."""
        self._validate_pin(pin, client_ip)
        with self.lock:
            return self._mint_locked()

    def _trusted_call(self, method, *args):
        if self.trusted is None:
            raise ApiError(503, "trusted_devices_unavailable")
        try:
            return getattr(self.trusted, method)(*args)
        except TRUST.TrustError as error:
            raise ApiError(error.status, error.code) from None

    def _revoke_trusted_locked(self, raw):
        self._trusted_call("forget", raw)
        cookie_digest = hashlib.sha256(raw.encode()).digest()
        for key, value in list(self.token_trust.items()):
            if value == cookie_digest:
                self.tokens.pop(key, None)
                self.token_trust.pop(key, None)

    def enroll(self, pin, client_ip, remember, previous_cookie=None):
        if not isinstance(remember, bool):
            raise ApiError(400, "invalid_payload")
        self._validate_pin(pin, client_ip)
        if previous_cookie is not None and (not isinstance(previous_cookie, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", previous_cookie, re.ASCII)):
            raise ApiError(401, "trusted_device_invalid")
        with self.lock:
            if previous_cookie is not None:
                self._revoke_trusted_locked(previous_cookie)
            raw = self._trusted_call("remember")[0] if remember else None
            # A durable trust record must exist before acknowledging enrollment.
            result = self._mint_locked(raw)
        result["remembered"] = remember
        return result, TRUST.cookie_header(raw) if remember else TRUST.cookie_header(clear=True)

    def _resume_limit(self, client_ip):
        if not self.resume_ip.allow(client_ip) or not self.resume_global.allow("all"):
            raise ApiError(429, "resume_rate_limited")

    def resume_cookie(self, headers, client_ip, rate_checked=False):
        if not rate_checked:
            self._resume_limit(client_ip)
        try:
            raw = TRUST.parse_cookie(headers)
        except TRUST.TrustError as error:
            raise ApiError(error.status, error.code) from None
        return self._resume(raw)

    def resume(self, raw, client_ip):
        self._resume_limit(client_ip)
        return self._resume(raw)

    def _resume(self, raw):
        if raw is None:
            raise ApiError(401, "trusted_device_missing")
        if not isinstance(raw, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", raw, re.ASCII):
            raise ApiError(401, "trusted_device_invalid")
        with self.lock:
            if not self._trusted_call("valid", raw):
                raise ApiError(401, "trusted_device_expired")
            result = self._mint_locked(raw)
        result["remembered"] = True
        return result

    def forget(self, token_id, raw):
        """Revoke browser trust and its bearer sessions without touching the TV."""
        if not isinstance(token_id, str) or not re.fullmatch(r"[a-f0-9]{64}", token_id, re.ASCII):
            raise ApiError(401, "pair_required")
        digest = bytes.fromhex(token_id)
        if raw is not None and (not isinstance(raw, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", raw, re.ASCII)):
            raise ApiError(401, "trusted_device_invalid")
        with self.lock:
            if self.tokens.get(digest, 0) <= self.clock():
                raise ApiError(401, "session_expired")
            cookie_digest = hashlib.sha256(raw.encode()).digest() if raw is not None else None
            session_trust = self.token_trust.get(digest)
            if raw is not None:
                self._trusted_call("forget", raw)
            if session_trust is not None and session_trust != cookie_digest:
                # A browser may have cleared/replaced its cookie while its
                # bearer remains active. Revoke that durable credential too.
                self._trusted_call("forget_digest", session_trust.hex())
            # The current bearer is always revoked, including an ephemeral one.
            # Also invalidate earlier sessions issued for this browser cookie.
            bound = {value for value in (cookie_digest, session_trust) if value is not None}
            revoke = {key for key, value in self.token_trust.items() if value in bound}
            revoke.add(digest)
            for key in revoke:
                self.tokens.pop(key, None)
                self.token_trust.pop(key, None)
        return {"ok": True}

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
            self.token_trust.pop(digest, None)
        if not self.auth_failures.allow(client_ip):
            raise ApiError(429, "auth_rate_limited")
        raise ApiError(401, "session_expired")


    def active(self, token_id):
        if not isinstance(token_id, str) or not re.fullmatch(r"[a-f0-9]{64}", token_id, re.ASCII):
            return False
        with self.lock:
            return self.tokens.get(bytes.fromhex(token_id), 0) > self.clock()


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
        self.fast_input = None

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
        elif kind == "theater":
            args = ["shell", "am", "start", "-n", APPS["framextv"], "-a", THEATER_ACTION,
                    "--es", THEATER_EXTRA, theater_payload(value)]
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
                # State/model checks take 1s each; Theater also allows a 2s wake
                # before the usual 4s command so a dream cannot cover its UI.
                if not self._probe(reconnect=False):
                    raise ApiError(503, "tv_unavailable")
            if kind == "key" and self.fast_input is not None:
                try:
                    if self.fast_input.send(KEYS[value]):
                        return
                except FAST_INPUT.InputError:
                    # A failed write may already have dispatched the key. Never
                    # resend through the legacy input process after that point.
                    raise ApiError(503, "key_send_uncertain") from None
            try:
                if kind == "theater":
                    wake_status, wake_output = self._run(
                        ["-s", self.serial, "shell", "input", "keyevent", "224"], timeout=2)
                    if wake_status or re.search(r"(?:^|\n)(?:Error:|Exception|SecurityException|java\.)", wake_output):
                        raise ApiError(503, "theater_send_uncertain")
                status, output = self._run(["-s", self.serial, *args], timeout=4)
            except ApiError:
                if kind == "text":
                    # A lost acknowledgement can follow a partial/full send.
                    # Never replay text or expose it in the error response.
                    raise ApiError(503, "text_send_uncertain") from None
                if kind == "theater":
                    raise ApiError(503, "theater_send_uncertain") from None
                raise
            if status or re.search(r"(?:^|\n)(?:Error:|Exception|SecurityException|java\.)", output):
                code = "text_send_uncertain" if kind == "text" else "theater_send_uncertain" if kind == "theater" else "command_failed"
                raise ApiError(503, code)
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

    def live_identity(self):
        # A status probe can overlap Start. Wait briefly without holding the
        # lock, then still verify this exact device before starting capture.
        if not self.command_lock.acquire(timeout=3):
            raise ApiError(429, "live_busy")
        try:
            if not self._probe(reconnect=False):
                raise ApiError(503, "tv_unavailable")
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
SAMSUNG_REVIEW_CODES = frozenset(("certificate_required", "certificate_mismatch",
                                  "certificate_unavailable", "invalid_certificate"))
SAMSUNG_ERROR_CODES = frozenset((
    "unsafe_token_path", "unsafe_token_file", "invalid_token_file", "invalid_token",
    "token_storage_failed", "identity_unavailable", "identity_too_large", "invalid_identity",
    "tv_unavailable", "wrong_tv_model", "unsupported_auth", "websockets_dependency_missing",
    "connect_timeout", "connection_failed", "not_connected", "connection_closed",
    "invalid_message", "authorization_denied", "too_many_messages", "send_failed",
    "pair_required", "invalid_timeout", "invalid_navigation")) | SAMSUNG_REVIEW_CODES


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
                if error.code in SAMSUNG_REVIEW_CODES or error.code in ("authorization_denied", "pair_required"):
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
    def __init__(self, bridge, hosts, port, dist=HERE / "dist", pairing=None, samsung=None, config=None, device=None, weather=None, preview=None, live=None, live_jar=None, ffmpeg=None, fast_input=None):
        self.bridge, self.hosts, self.port = bridge, set(hosts), port
        self.dist = Path(dist).resolve()
        self.pairing = pairing or Pairing()
        self.fast_input = fast_input
        self.config = CONFIG.validate(config)
        self.dates = CivilDates(self.config["clocks"])
        self.samsung = samsung if samsung is not None else SamsungBridge(self.config["samsung"])
        self.status_rate = WindowLimiter(60, 60)
        self.feature_rate = WindowLimiter(30, 60)
        self.preview_rate = WindowLimiter(20, 60)
        self.capture_mode_lock = threading.Lock()
        self.live_start_rate = WindowLimiter(3, 60)
        self.preview = preview if preview is not None else PREVIEW.ScreenPreview(lambda: self.bridge.capture_screen_png())
        import os
        self.live = live if live is not None else LIVE.LiveVideo(
            getattr(self.bridge, "adb", "adb"), self.config["fire_tv"]["host"] + ":5555", live_jar or (HERE / "state/tools/scrcpy-server-v5.0.1"),
            dict(os.environ), lambda: self.bridge.live_identity(), self.pairing.active, ffmpeg=ffmpeg)
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
        online = self.bridge.status()
        if online and self.fast_input is not None:
            self.fast_input.warm()
        return {"online": online,
                "device": {"name": self.config["fire_tv"]["name"], "model": self.config["fire_tv"]["model"],
                           "serial": self.config["fire_tv"]["host"] + ":5555"},
                "date": self.dates.status(), "samsung": self.samsung.status(),
                "serverTime": datetime.now(timezone.utc).isoformat()}


    def preview_frame(self, token_id):
        if not self.capture_mode_lock.acquire(blocking=False):
            raise ApiError(429, "preview_busy")
        try:
            if self.live.active():
                raise ApiError(409, "live_in_use")
            if not self.preview_rate.allow(token_id):
                raise ApiError(429, "preview_rate_limited")
            try:
                return self.preview.frame()
            except PREVIEW.PreviewError as error:
                raise ApiError(error.status, error.code) from None
        finally:
            self.capture_mode_lock.release()

    def get_feature(self, name, token_id):
        if not self.feature_rate.allow(token_id):
            raise ApiError(429, "feature_rate_limited")
        return (self.device if name == "device" else self.weather).status()


    def live_call(self, action, token_id):
        admitted = False
        if action == "start":
            if not self.live_start_rate.allow(token_id):
                raise ApiError(429, "live_rate_limited")
            admitted = self.capture_mode_lock.acquire(blocking=False)
            if not admitted:
                raise ApiError(409, "preview_busy")
        try:
            return getattr(self.live, action)(token_id)
        except LIVE.LiveError as error:
            raise ApiError(error.status, error.code) from None
        finally:
            if admitted:
                self.capture_mode_lock.release()

    def close(self):
        try:
            self.live.close()
        finally:
            if self.fast_input is not None:
                self.fast_input.close()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "NepalRemote"
    sys_version = ""

    def setup(self):
        self.request.settimeout(5)
        super().setup()

    def handle_one_request(self):
        # A socket idle timeout alone can be reset forever by trickled bytes.
        # Each request owns its timer state so an old callback cannot affect a
        # later keep-alive request. All application/device work starts after cancel.
        phase = {"lock": threading.Lock(), "active": True, "expired": False}
        phase["timer"] = threading.Timer(REQUEST_READ_SECONDS, self._expire_read_phase, args=(phase,))
        phase["timer"].daemon = True
        self._read_phase = phase
        phase["timer"].start()
        try:
            super().handle_one_request()
        finally:
            self._cancel_read_phase(phase)

    def _expire_read_phase(self, phase):
        with phase["lock"]:
            if not phase["active"]:
                return
            phase["active"], phase["expired"] = False, True
            try:
                self.request.shutdown(socket.SHUT_RD)
            except OSError:
                pass

    @staticmethod
    def _cancel_read_phase(phase):
        with phase["lock"]:
            phase["active"] = False
            phase["timer"].cancel()

    def _finish_read_phase(self):
        phase = self._read_phase
        with phase["lock"]:
            phase["active"] = False
            phase["timer"].cancel()
            if phase["expired"]:
                raise ApiError(408, "request_timeout")

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
            if method in ("GET", "HEAD"):
                self._finish_read_phase()
            parsed = urlsplit(self.path)
            if parsed.query or parsed.fragment:
                raise ApiError(404, "not_found")
            media = re.fullmatch(r"/api/live/([a-f0-9]{32})/(index\.m3u8|part[0-9]{6}\.ts)", parsed.path, re.ASCII)
            if method in ("GET", "HEAD") and media:
                if self.headers.get("Sec-Fetch-Site") not in (None, "same-origin", "none"):
                    raise ApiError(403, "cross_origin_forbidden")
                referer = self.headers.get("Referer")
                if referer:
                    try:
                        ref = urlsplit(referer)
                        if ref.scheme != "http" or app.authority(ref.netloc) != app.authority(self.headers.get("Host")):
                            raise ValueError()
                    except (ValueError, ApiError):
                        raise ApiError(403, "cross_origin_forbidden") from None
                try:
                    result = app.live.media(media[1], media[2], LIVE.cookie_value(self.headers), self.headers.get("Range"))
                except LIVE.LiveError as error:
                    raise ApiError(error.status, error.code) from None
                self.respond(*result[:3], head=method == "HEAD", extra_headers=result[3])
                return
            if method in ("GET", "HEAD"):
                if parsed.path == "/api/live/status":
                    token = app.pairing.authorize(self.headers.get("Authorization"), self.client_address[0])
                    self.respond(200, app.live.status(token), head=method == "HEAD")
                elif parsed.path == "/api/status":
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
            if method != "POST" or parsed.path not in ("/api/pair", "/api/session/pair", "/api/session/resume", "/api/session/forget", "/api/key", "/api/app", "/api/text", "/api/theater", "/api/reconnect", "/api/wallpaper", "/api/preview/frame", "/api/live/start", "/api/live/keepalive", "/api/live/stop", *SAMSUNG_PATHS):
                raise ApiError(405, "method_not_allowed")
            if parsed.path not in ("/api/pair", "/api/session/pair", "/api/session/resume"):
                token_id = app.pairing.authorize(self.headers.get("Authorization"), self.client_address[0])
            if parsed.path == "/api/session/resume":
                app.pairing._resume_limit(self.client_address[0])
            if self.headers.get_content_type() != "application/json":
                raise ApiError(415, "json_required")
            try:
                raw = self.rfile.read(length)
                self._finish_read_phase()
                if len(raw) != length:
                    raise ValueError()
                payload = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json_object)
            except (ValueError, UnicodeError):
                raise ApiError(400, "invalid_json")
            if parsed.path in ("/api/pair", "/api/session/pair"):
                if (not isinstance(payload, dict) or set(payload) not in ({"pin"}, {"pin", "remember"})
                        or not isinstance(payload["pin"], str)
                        or ("remember" in payload and not isinstance(payload["remember"], bool))):
                    raise ApiError(400, "invalid_payload")
                try:
                    previous = TRUST.parse_cookie(self.headers)
                except TRUST.TrustError as error:
                    if error.status != 401:
                        raise
                    # A correct PIN can replace a damaged HttpOnly cookie.
                    # Resume remains strict; this grants nothing before PIN validation.
                    previous = None
                result, cookie = app.pairing.enroll(payload["pin"], self.client_address[0], payload.get("remember", False), previous)
                self.respond(200, result, extra_headers=(("Set-Cookie", cookie),))
                return
            if parsed.path in ("/api/session/resume", "/api/session/forget"):
                if not isinstance(payload, dict) or payload:
                    raise ApiError(400, "invalid_payload")
                if parsed.path.endswith("/resume"):
                    self.respond(200, app.pairing.resume_cookie(self.headers, self.client_address[0], rate_checked=True))
                else:
                    result = app.pairing.forget(token_id, TRUST.parse_cookie(self.headers))
                    self.respond(200, result, extra_headers=(("Set-Cookie", TRUST.cookie_header(clear=True)),))
                return
            if parsed.path in ("/api/live/start", "/api/live/keepalive", "/api/live/stop"):
                if not isinstance(payload, dict) or payload:
                    raise ApiError(400, "invalid_payload")
                result, cookie = app.live_call(parsed.path.rsplit("/", 1)[1], token_id)
                self.respond(200, result, extra_headers=(("Set-Cookie", cookie),) if cookie else ())
                return
            if parsed.path == "/api/preview/frame":
                if not isinstance(payload, dict) or payload:
                    raise ApiError(400, "invalid_payload")
                frame, captured_at, duration_ms = app.preview_frame(token_id)
                self.respond(200, frame, "image/jpeg", extra_headers=(
                    ("X-Preview-Captured-At", captured_at),
                    ("X-Preview-Capture-Ms", duration_ms)))
                return
            if parsed.path == "/api/theater":
                if (not isinstance(payload, dict) or set(payload) != {"request"}
                        or not isinstance(payload["request"], str)):
                    raise ApiError(400, "invalid_payload")
                request = validate_theater_request(payload["request"])
                app.bridge.command("theater", request)
                self.respond(200, {"ok": True, "state": "request_sent"})
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
            field = {"/api/key": "key", "/api/app": "app", "/api/text": "text"}[parsed.path]
            if not isinstance(payload, dict) or set(payload) != {field} or not isinstance(payload[field], str):
                raise ApiError(400, "invalid_payload")
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
        except (ApiError, TRUST.TrustError) as exc:
            payload = {"error": exc.code}
            if self.path.split("?", 1)[0] in SAMSUNG_PATHS:
                payload["state"] = "error"
            clear_trust = exc.status == 401 and self.path == "/api/session/resume"
            self.respond(exc.status, payload, head=method == "HEAD", extra_headers=(
                (("Set-Cookie", TRUST.cookie_header(clear=True)),) if clear_trust else ()))
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
    parser.add_argument("--scrcpy-server", type=Path, help="Optional verified scrcpy 5.0.1 JAR for fast keys and live video")
    parser.add_argument("--no-fast-input", action="store_true", help="Use the legacy Android input command for keys")
    parser.add_argument("--ffmpeg", type=Path, help="Optional FFmpeg executable for live video")
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
    try:
        pin = PAIRING_CODE.load_or_create(HERE / "state/pairing-code.json")
    except PAIRING_CODE.PairingCodeError:
        parser.error("Cannot load private pairing code. Check remote/state ownership and permissions "
                     "(directory 0700, file 0600).")
    hosts = local_hosts(configuration["fire_tv"]["host"], args.allow_host)
    bridge = AdbBridge(adb, configuration["fire_tv"])
    import os
    fast_input = None if args.no_fast_input else FAST_INPUT.FastInput(
        adb, bridge.serial, args.scrcpy_server or (HERE / "state/tools/scrcpy-server-v5.0.1"),
        dict(os.environ), bridge.live_identity, frozenset(KEYS.values()))
    bridge.fast_input = fast_input
    pairing = Pairing(pin=pin, trusted=TRUST.TrustedDevices(HERE / "state/trusted-devices.json", bridge.serial + "|" + bridge.expected_model))
    app = RemoteApp(bridge, hosts, args.port, config=configuration, pairing=pairing, live_jar=args.scrcpy_server, ffmpeg=args.ffmpeg, fast_input=fast_input)
    import signal
    def terminate_server(_signal, _frame):
        raise KeyboardInterrupt()
    previous_term = signal.signal(signal.SIGTERM, terminate_server)
    with RemoteServer((args.bind, args.port), app) as server:
        print(configuration["fire_tv"]["name"] + " remote", flush=True)
        for host in sorted(hosts):
            if args.bind == "127.0.0.1" and host != "127.0.0.1":
                continue
            print(f"  http://{host}:{args.port}", flush=True)
        print(f"Pairing PIN: {app.pairing.pin}", flush=True)
        print("Keep this terminal open. Stopping expires access sessions; remembered browsers can reconnect for 30 days.", flush=True)
        try:
            server.serve_forever(poll_interval=0.5)
        except KeyboardInterrupt:
            pass
        finally:
            app.close()
            signal.signal(signal.SIGTERM, previous_term)


if __name__ == "__main__":
    main()
