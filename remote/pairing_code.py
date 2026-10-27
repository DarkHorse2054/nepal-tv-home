"""One private, per-install pairing code. Importing this module creates no state."""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import stat
import time

MAX_BYTES = 128
LOCK_TIMEOUT = 3.0
PIN = re.compile(r"[0-9]{6}", re.ASCII)


class PairingCodeError(Exception):
    """A safe startup error; neither the code nor file contents appear in it."""
    def __init__(self, code="pairing_code_storage_unavailable", status=503):
        super().__init__(code)
        self.code, self.status = code, status


def _validate_pin(pin):
    if not isinstance(pin, str) or not PIN.fullmatch(pin):
        raise PairingCodeError("invalid_pairing_code", 400)
    return pin


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON member")
        value[key] = item
    return value


def _invalid_constant(_value):
    raise ValueError("Non-finite JSON value")


def _path(path):
    try:
        value = os.fspath(path)
        if not isinstance(value, str) or not value or "\x00" in value:
            raise ValueError("Invalid state path")
        result = Path(os.path.abspath(value))
        # No state may be written under a public build directory, including aliases
        # reached via '..'. Parent traversal below also refuses every symlink.
        if result.name in ("", ".", "..") or "dist" in result.parts:
            raise ValueError("Public or invalid state path")
        return result
    except (OSError, TypeError, ValueError, RuntimeError):
        raise PairingCodeError() from None


def _parent(path):
    """Walk with directory descriptors, never following a parent symlink."""
    descriptor = None
    try:
        descriptor = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in path.parent.parts[1:]:
            try:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                dir_fd=descriptor)
            except FileNotFoundError:
                try:
                    os.mkdir(part, 0o700, dir_fd=descriptor)
                    os.fsync(descriptor)
                except FileExistsError:
                    pass
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        details = os.fstat(descriptor)
        if (not stat.S_ISDIR(details.st_mode) or details.st_uid != os.getuid()
                or stat.S_IMODE(details.st_mode) != 0o700):
            raise ValueError("Unsafe state directory")
        return descriptor
    except (OSError, ValueError, RuntimeError):
        if descriptor is not None:
            os.close(descriptor)
        raise PairingCodeError() from None


def _safe_file(details):
    return (stat.S_ISREG(details.st_mode) and details.st_uid == os.getuid()
            and details.st_nlink == 1 and stat.S_IMODE(details.st_mode) == 0o600)


def _same_file(parent, name, details):
    current = os.stat(name, dir_fd=parent, follow_symlinks=False)
    return (current.st_dev, current.st_ino) == (details.st_dev, details.st_ino)


@contextlib.contextmanager
def _locked(parent, name):
    # Keep this inode after unlocking: unlinking a lock can split future callers
    # across different locks. The lock never contains a pairing code.
    descriptor = None
    try:
        flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
        try:
            descriptor = os.open(name, flags | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=parent)
            os.fchmod(descriptor, 0o600)
        except FileExistsError:
            descriptor = os.open(name, flags, dir_fd=parent)
        details = os.fstat(descriptor)
        if not _safe_file(details) or details.st_size != 0:
            raise ValueError("Unsafe state lock")
        deadline = time.monotonic() + LOCK_TIMEOUT
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise ValueError("State lock unavailable") from None
                time.sleep(0.01)
        current = os.fstat(descriptor)
        if not _safe_file(current) or current.st_size != 0 or not _same_file(parent, name, details):
            raise ValueError("State lock changed")
        yield
    except (OSError, ValueError, RuntimeError):
        raise PairingCodeError() from None
    finally:
        if descriptor is not None:
            # Closing releases flock, including when the caller raises.
            os.close(descriptor)


def _read(parent, name):
    descriptor = None
    try:
        try:
            descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        except FileNotFoundError:
            return None
        details = os.fstat(descriptor)
        if not _safe_file(details) or not 1 <= details.st_size <= MAX_BYTES:
            raise ValueError("Unsafe pairing-code file")
        with os.fdopen(descriptor, "rb") as source:
            descriptor = None
            raw = source.read(MAX_BYTES + 1)
            if not _safe_file(os.fstat(source.fileno())):
                raise ValueError("Pairing-code file changed")
        if len(raw) > MAX_BYTES or not _same_file(parent, name, details):
            raise ValueError("Pairing-code file changed")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                           parse_constant=_invalid_constant)
        if (not isinstance(value, dict) or set(value) != {"version", "pin"}
                or type(value["version"]) is not int or value["version"] != 1
                or not isinstance(value["pin"], str) or not PIN.fullmatch(value["pin"])):
            raise ValueError("Invalid pairing-code schema")
        return value["pin"]
    except (OSError, ValueError, UnicodeError, OverflowError, RecursionError):
        raise PairingCodeError() from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _create(parent, name, pin):
    temporary = ".pairing-code-" + secrets.token_hex(16)
    descriptor = None
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=parent)
        os.fchmod(descriptor, 0o600)
        raw = json.dumps({"version": 1, "pin": pin}, separators=(",", ":"),
                         allow_nan=False).encode("ascii")
        with os.fdopen(descriptor, "wb") as output:
            descriptor = None
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        try:
            # Unlike replace/rename, link cannot overwrite a pre-existing winner.
            # All module readers hold the lock until the temporary link is removed.
            os.link(temporary, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
        except FileExistsError:
            winner = _read(parent, name)
            if winner is None:
                raise ValueError("Pairing-code file changed")
            return winner
        os.unlink(temporary, dir_fd=parent)
        os.fsync(parent)
        saved = _read(parent, name)
        if saved is None:
            raise ValueError("Pairing-code file changed")
        return saved
    except (OSError, ValueError, UnicodeError, OverflowError):
        raise PairingCodeError() from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        with contextlib.suppress(OSError):
            os.unlink(temporary, dir_fd=parent)


def _load_or_create(path, pin=None):
    parent = _parent(path)
    try:
        with _locked(parent, "." + path.name + ".lock"):
            existing = _read(parent, path.name)
            if existing is not None:
                return existing
            if pin is None:
                pin = f"{secrets.randbelow(1_000_000):06d}"
            return _create(parent, path.name, pin)
    finally:
        os.close(parent)


def load_or_create(path):
    """Return the existing six-digit code, or securely generate it once.

    Existing unsafe or malformed state raises PairingCodeError. It is never
    repaired or rotated automatically. The path must stay outside dist/ and
    must not traverse symlinks; the immediate directory must be owned, mode0700.
    """
    return _load_or_create(_path(path))


def create(path, pin):
    """Adopt a validated code only for an absent store; an existing code wins."""
    return _load_or_create(_path(path), _validate_pin(pin))
