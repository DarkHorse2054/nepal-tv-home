"""Bounded, private remembered-browser credentials. No I/O happens on import."""
import contextlib
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
import threading
import time

COOKIE_NAME = "nepal_trusted"
LIFETIME = 30 * 24 * 60 * 60
MAX_DEVICES = 32
MAX_BYTES = 32 * 1024
TOKEN = re.compile(r"[A-Za-z0-9_-]{43}", re.ASCII)
DIGEST = re.compile(r"[a-f0-9]{64}", re.ASCII)
COOKIE_PAIR = re.compile(r"[!#$%&'*+\-.^_`|~0-9A-Za-z]+=[\x21\x23-\x2B\x2D-\x3A\x3C-\x5B\x5D-\x7E]*", re.ASCII)


class TrustError(Exception):
    def __init__(self, code, status=503):
        self.code, self.status = code, status


def cookie_header(rawtoken="", clear=False):
    if not clear and (not isinstance(rawtoken, str) or not TOKEN.fullmatch(rawtoken)):
        raise TrustError("trusted_device_invalid", 401)
    return (f"{COOKIE_NAME}={'' if clear else rawtoken}; Path=/api/session/; "
            f"Max-Age={0 if clear else LIFETIME}; HttpOnly; SameSite=Strict")


def parse_cookie(headers):
    values = headers.get_all("Cookie", [])
    if not values:
        return None
    if (len(values) != 1 or not isinstance(values[0], str) or len(values[0]) > 8192
            or re.search(r"[^\x20-\x7E\t]", values[0])):
        raise TrustError("trusted_device_invalid", 401)
    cookies = {}
    for part in values[0].split(";"):
        part = part.strip()
        if not COOKIE_PAIR.fullmatch(part):
            raise TrustError("trusted_device_invalid", 401)
        name, value = part.split("=", 1)
        if name in cookies:
            raise TrustError("trusted_device_invalid", 401)
        cookies[name] = value
    rawtoken = cookies.get(COOKIE_NAME)
    if rawtoken is not None and not TOKEN.fullmatch(rawtoken):
        raise TrustError("trusted_device_invalid", 401)
    return rawtoken


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON member")
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ValueError("Non-finite JSON value")


class TrustedDevices:
    """One companion process owns this store; threads serialize every mutation.

    Only SHA-256 digests and absolute expiry times are persisted. The target is
    hashed too, so changing the configured television cannot inherit old trust.
    Reads always check the current file, allowing local revocation to take effect.
    """
    def __init__(self, path, target, wall_clock=time.time):
        self.path = Path(path).absolute()
        self.dist = Path(__file__).absolute().parent / "dist"
        if self.path.is_relative_to(self.dist):
            raise ValueError("Trusted-device state must stay outside public dist/")
        if not isinstance(target, str) or not 1 <= len(target) <= 4096:
            raise ValueError("Trusted-device target must be a bounded identity")
        self.target = hashlib.sha256(target.encode("utf-8")).hexdigest()
        self.wall_clock = wall_clock
        self.lock = threading.Lock()

    def _now(self):
        value = self.wall_clock()
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise TrustError("trusted_device_storage_unavailable")
        return value

    def _parent(self, create=False):
        try:
            if self.path.parent.resolve().is_relative_to(self.dist.resolve()):
                raise TrustError("trusted_device_storage_unavailable")
            if create:
                self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            descriptor = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        except FileNotFoundError:
            if not create:
                return None
            raise TrustError("trusted_device_storage_unavailable") from None
        except (OSError, RuntimeError):
            raise TrustError("trusted_device_storage_unavailable") from None
        try:
            details = os.fstat(descriptor)
            if (not stat.S_ISDIR(details.st_mode) or details.st_uid != os.getuid()
                    or stat.S_IMODE(details.st_mode) != 0o700):
                raise TrustError("trusted_device_storage_unavailable")
            return descriptor
        except BaseException:
            os.close(descriptor)
            raise

    def _read(self, parent):
        descriptor = None
        try:
            descriptor = os.open(self.path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                 dir_fd=parent)
            details = os.fstat(descriptor)
            if (not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid()
                    or details.st_nlink != 1 or stat.S_IMODE(details.st_mode) != 0o600
                    or not 1 <= details.st_size <= MAX_BYTES):
                raise TrustError("trusted_device_storage_unavailable")
            with os.fdopen(descriptor, "rb") as source:
                descriptor = None
                raw = source.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError("State exceeds limit")
            value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                               parse_constant=_invalid_constant)
            if (not isinstance(value, dict) or set(value) != {"version", "target", "devices"}
                    or type(value["version"]) is not int or value["version"] != 1
                    or not isinstance(value["target"], str) or not DIGEST.fullmatch(value["target"])
                    or not isinstance(value["devices"], dict) or len(value["devices"]) > MAX_DEVICES):
                raise ValueError("Invalid state schema")
            for digest, expiry in value["devices"].items():
                if (not DIGEST.fullmatch(digest) or type(expiry) not in (int, float)
                        or not math.isfinite(expiry) or not 0 < expiry < 2 ** 53):
                    raise ValueError("Invalid state record")
            return value
        except FileNotFoundError:
            return None
        except (OSError, ValueError, UnicodeError, OverflowError, RecursionError):
            raise TrustError("trusted_device_storage_unavailable") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)

    def _load(self, now):
        parent = self._parent()
        if parent is None:
            return {}
        try:
            value = self._read(parent)
        finally:
            os.close(parent)
        if value is None or value["target"] != self.target:
            return {}
        return {digest: expiry for digest, expiry in value["devices"].items() if expiry > now}

    def _save(self, devices):
        temporary = ".trusted-devices-" + secrets.token_hex(16)
        parent = self._parent(create=True)
        descriptor = None
        try:
            # Refuse to overwrite an unsafe existing file, even after a race.
            self._read(parent)
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=parent)
            os.fchmod(descriptor, 0o600)
            raw = json.dumps({"version": 1, "target": self.target, "devices": devices},
                             sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
            if len(raw) > MAX_BYTES:
                raise ValueError("State exceeds limit")
            with os.fdopen(descriptor, "wb") as output:
                descriptor = None
                output.write(raw)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path.name, src_dir_fd=parent, dst_dir_fd=parent)
            os.fsync(parent)
        except (OSError, ValueError, OverflowError):
            raise TrustError("trusted_device_storage_unavailable") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)
            with contextlib.suppress(OSError):
                os.unlink(temporary, dir_fd=parent)
            os.close(parent)

    def remember(self):
        with self.lock:
            now = self._now()
            devices = self._load(now)
            rawtoken = secrets.token_urlsafe(32)
            digest = hashlib.sha256(rawtoken.encode("ascii")).hexdigest()
            if len(devices) >= MAX_DEVICES:
                devices.pop(min(devices, key=devices.get))
            devices[digest] = now + LIFETIME
            self._save(devices)
            return rawtoken, LIFETIME

    def valid(self, rawtoken):
        if not isinstance(rawtoken, str) or not TOKEN.fullmatch(rawtoken):
            return False
        with self.lock:
            digest = hashlib.sha256(rawtoken.encode("ascii")).hexdigest()
            return digest in self._load(self._now())

    def forget(self, rawtoken):
        if not isinstance(rawtoken, str) or not TOKEN.fullmatch(rawtoken):
            return False
        return self.forget_digest(hashlib.sha256(rawtoken.encode("ascii")).hexdigest())

    def forget_digest(self, digest):
        """Revoke a server-bound credential when its browser cookie is absent."""
        if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
            return False
        with self.lock:
            devices = self._load(self._now())
            if digest not in devices:
                return False
            del devices[digest]
            self._save(devices)
            return True

    def clear(self):
        with self.lock:
            self._save({})
