"""Validated local configuration. Importing this module never probes a device."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

HERE = Path(__file__).resolve().parent
DIST = (HERE / "dist").resolve()
STATE = HERE / "state"
DEFAULT_CONFIG = STATE / "config.json"
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in
                         ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
MODEL_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9 ._()+-]{0,95}", re.ASCII)
SOURCE_ALIASES = frozenset(("hdmi1", "hdmi2", "hdmi3", "hdmi4"))


class ConfigError(ValueError):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ConfigError("Duplicate configuration member")
        result[key] = value
    return result


def private_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value) if isinstance(value, str) else None
    except ipaddress.AddressValueError:
        address = None
    if address is None or not any(address in network for network in PRIVATE_NETWORKS):
        raise ConfigError("Target host must be an explicit RFC1918 IPv4 address")
    return str(address)


def label(value, field):
    if not isinstance(value, str) or not value.strip() or len(value) > 64 or not value.isprintable():
        raise ConfigError(field + " must be a nonempty printable label of at most 64 characters")
    return value.strip()


def target(value, *, samsung=False):
    keys = {"host", "port", "model"} | (set() if samsung else {"name"})
    if not isinstance(value, dict) or set(value) != keys:
        raise ConfigError("Target fields must be " + ", ".join(sorted(keys)))
    host = private_ipv4(value["host"])
    port = 8002 if samsung else 5555
    if type(value["port"]) is not int or value["port"] != port:
        raise ConfigError("Samsung port must be 8002" if samsung else "Fire TV ADB port must be 5555")
    model = value["model"]
    if not isinstance(model, str) or not MODEL_PATTERN.fullmatch(model):
        raise ConfigError("Expected model must be a plain model identifier, at most 96 characters")
    result = {"host": host, "port": port, "model": model}
    if not samsung:
        result["name"] = label(value["name"], "fire_tv.name")
    return result


def validate_fire_tv(value):
    return target(value)


def validate_samsung(value):
    if value is None:
        return None
    required = {"host", "port", "model"}
    if not isinstance(value, dict) or not required <= set(value) or set(value) - required - {"source_labels"}:
        raise ConfigError("Samsung fields must be host, port, model and optional source_labels")
    result = target({key: value[key] for key in required}, samsung=True)
    if "source_labels" in value:
        labels = value["source_labels"]
        if not isinstance(labels, dict) or set(labels) - SOURCE_ALIASES:
            raise ConfigError("source_labels accepts only hdmi1, hdmi2, hdmi3 and hdmi4 labels")
        result["source_labels"] = {key: label(text, "source_labels." + key) for key, text in labels.items()}
    return result


def validate_clocks(value):
    keys = {"local_timezone", "local_label", "secondary_timezone", "secondary_label", "secondary_calendar"}
    if not isinstance(value, dict) or set(value) != keys:
        raise ConfigError("Clock configuration fields are incomplete or unknown")
    result = dict(value)
    for key in ("local_timezone", "secondary_timezone"):
        zone = value[key]
        if not isinstance(zone, str) or len(zone) > 80:
            raise ConfigError(key + " must be an IANA timezone")
        try:
            ZoneInfo(zone)
        except (ZoneInfoNotFoundError, ValueError):
            raise ConfigError(key + " must be an available IANA timezone") from None
    for key in ("local_label", "secondary_label"):
        result[key] = label(value[key], key)
    if value["secondary_calendar"] not in ("gregorian", "bikram_sambat"):
        raise ConfigError("secondary_calendar must be gregorian or bikram_sambat")
    return result


def validate(value):
    if not isinstance(value, dict) or set(value) != {"schema_version", "fire_tv", "clocks", "samsung"}:
        raise ConfigError("Configuration must contain schema_version, fire_tv, clocks and samsung")
    if type(value["schema_version"]) is not int or value["schema_version"] != 1:
        raise ConfigError("Only configuration schema_version 1 is supported")
    return {"schema_version": 1, "fire_tv": validate_fire_tv(value["fire_tv"]),
            "clocks": validate_clocks(value["clocks"]), "samsung": validate_samsung(value["samsung"])}


def fingerprint(value):
    # Display labels must not invalidate an authorization for the same target.
    identity = {key: value[key] for key in ("host", "port", "model")}
    return hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:20]


def load(path=DEFAULT_CONFIG):
    path = Path(path)
    if path.resolve().is_relative_to(DIST):
        raise ConfigError("Configuration must stay outside public dist/")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        raise ConfigError("Configuration is missing") from None
    except OSError:
        raise ConfigError("Configuration could not be opened safely") from None
    try:
        details = os.fstat(descriptor)
        if (not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid()
                or stat.S_IMODE(details.st_mode) != 0o600 or details.st_size > 16384):
            raise ConfigError("Configuration must be an owned regular file with mode 0600, at most 16 KiB")
        with os.fdopen(descriptor, "rb") as source:
            descriptor = None
            value = json.loads(source.read(16385), object_pairs_hook=unique_object)
        return validate(value)
    except (UnicodeError, json.JSONDecodeError):
        raise ConfigError("Configuration must contain valid JSON") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
