#!/usr/bin/env python3
"""Bounded local Samsung controls, with unverified discrete-HDMI test keys."""
import argparse
import asyncio
import base64
import contextlib
import http.client
import hashlib
import importlib.util
import json
import logging
import os
from pathlib import Path
import re
import secrets
import ssl
import stat
from urllib.parse import urlencode

CLIENT_NAME = "Local TV Remote"
HERE = Path(__file__).resolve().parent
DIST = (HERE / "dist").resolve()
def load_local_config():
    spec = importlib.util.spec_from_file_location("portable_remote_config", HERE / "config.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CONFIG = load_local_config()

MAX_INFO_BYTES = 16384
MAX_MESSAGE_BYTES = 16384
NAVIGATION = {"source": "KEY_SOURCE", "up": "KEY_UP", "down": "KEY_DOWN",
              "left": "KEY_LEFT", "right": "KEY_RIGHT", "ok": "KEY_ENTER",
              "back": "KEY_RETURN"}
# These are fixed remote key requests, not an input-selection or readback API.
# A successful socket write cannot establish whether a TV accepts an HDMI key.
EXPERIMENTAL_HDMI_KEYS = frozenset(("hdmi1", "hdmi2", "hdmi3", "hdmi4"))
COMMANDS = {**NAVIGATION,
            "hdmi1": "KEY_HDMI1", "hdmi2": "KEY_HDMI2",
            "hdmi3": "KEY_HDMI3", "hdmi4": "KEY_HDMI4",
            "volume_up": "KEY_VOLUP", "volume_down": "KEY_VOLDOWN",
            "mute": "KEY_MUTE", "power_off": "KEY_POWEROFF"}

# WebSocket debug logs may include the URL's token query. Keep this transport quiet.
TRANSPORT_LOG = logging.getLogger("nepal.samsung.private_transport")
TRANSPORT_LOG.addHandler(logging.NullHandler())
TRANSPORT_LOG.propagate = False
TRANSPORT_LOG.setLevel(logging.CRITICAL + 1)


class SamsungError(Exception):
    """Public errors contain fixed codes only, never tokens or remote responses."""
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def validated_target(value):
    try:
        target = CONFIG.validate_samsung(value)
    except CONFIG.ConfigError:
        raise SamsungError("invalid_target") from None
    if target is None:
        raise SamsungError("not_configured")
    return target


def _valid_token(value):
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        return None
    value = str(value)
    return value if re.fullmatch(r"[A-Za-z0-9_-]{1,512}", value, flags=re.ASCII) else None


def _valid_fingerprint(value):
    return value if isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value, re.ASCII) else None


def _peer_fingerprint(peer, expected=None, *, allow_unpinned=False):
    if not expected and not allow_unpinned:
        raise SamsungError("certificate_required")
    try:
        certificate = peer.getpeercert(binary_form=True)
    except (AttributeError, OSError, ValueError):
        raise SamsungError("certificate_unavailable") from None
    if not certificate:
        raise SamsungError("certificate_unavailable")
    fingerprint = hashlib.sha256(certificate).hexdigest()
    if expected and not secrets.compare_digest(fingerprint, expected):
        raise SamsungError("certificate_mismatch")
    return fingerprint


def _pinned_connection(expected, base=None):
    if base is None:
        try:
            from websockets.asyncio.client import ClientConnection
        except ImportError:
            raise SamsungError("websockets_dependency_missing") from None
        base = ClientConnection

    class PinnedConnection(base):
        async def handshake(self, *args, **kwargs):
            # TLS is established, but no HTTP Upgrade (and therefore no token URL)
            # is sent until this exact connection presents the approved leaf.
            _peer_fingerprint(self.transport.get_extra_info("ssl_object"), expected)
            return await super().handshake(*args, **kwargs)

    return PinnedConnection


def _fixed_connector():
    try:
        from websockets.asyncio.client import connect
    except ImportError:
        raise SamsungError("websockets_dependency_missing") from None

    class FixedEndpointConnect(connect):
        def process_redirect(self, error):
            return error  # The configured TV endpoint may never redirect credentials elsewhere.

    return FixedEndpointConnect


def fetch_info(target, *, expected_fingerprint=None, allow_unpinned=False):
    """Read one fixed TLS URL. HTTPConnection never follows redirects or proxies."""
    target = validated_target(target)
    connection = http.client.HTTPSConnection(
        target["host"], target["port"], timeout=2, context=ssl._create_unverified_context())
    try:
        connection.connect()
        fingerprint = _peer_fingerprint(connection.sock, expected_fingerprint, allow_unpinned=allow_unpinned)
        connection.request("GET", "/api/v2/", headers={"Accept": "application/json"})
        response = connection.getresponse()
        if response.status != 200:
            raise SamsungError("identity_unavailable")
        body = response.read(MAX_INFO_BYTES + 1)
        if len(body) > MAX_INFO_BYTES:
            raise SamsungError("identity_too_large")
        try:
            value = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeError):
            raise SamsungError("invalid_identity")
        if not isinstance(value, dict):
            raise SamsungError("invalid_identity")
        return value, fingerprint
    except SamsungError:
        raise
    except (OSError, http.client.HTTPException):
        raise SamsungError("tv_unavailable") from None
    finally:
        connection.close()


class TokenStore:
    def __init__(self, target, state_dir=CONFIG.STATE):
        self.target = validated_target(target)
        self.path = Path(state_dir) / ("samsung-" + CONFIG.fingerprint(self.target) + ".json")
        if self.path.resolve().is_relative_to(DIST):
            raise SamsungError("unsafe_token_path")

    def load_record(self):
        try:
            descriptor = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        except FileNotFoundError:
            return None
        except OSError:
            raise SamsungError("unsafe_token_file") from None
        try:
            details = os.fstat(descriptor)
            if (not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid()
                    or stat.S_IMODE(details.st_mode) != 0o600 or details.st_size > 2048):
                raise SamsungError("unsafe_token_file")
            with os.fdopen(descriptor, "rb") as source:
                descriptor = None
                value = json.loads(source.read(2049))
            if not isinstance(value, dict) or value.get("host") != self.target["host"] or value.get("model") != self.target["model"]:
                raise SamsungError("invalid_token_file")
            token = _valid_token(value.get("token"))
            if not token:
                raise SamsungError("invalid_token_file")
            fingerprint = value.get("certificate_sha256")
            if fingerprint is not None and not _valid_fingerprint(fingerprint):
                raise SamsungError("invalid_token_file")
            return {"token": token, "certificate_sha256": fingerprint}
        except (ValueError, UnicodeError, OSError):
            raise SamsungError("invalid_token_file") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)

    def load(self):
        record = self.load_record()
        # A legacy token is not proof of the peer's identity. Upgrade only through
        # an explicit approval, never by silently trusting the next LAN response.
        return record["token"] if record and record["certificate_sha256"] else None

    def save(self, token, certificate_sha256):
        token = _valid_token(token)
        if not token:
            raise SamsungError("invalid_token")
        fingerprint = _valid_fingerprint(certificate_sha256)
        if not fingerprint:
            raise SamsungError("invalid_certificate")
        previous = self.load_record()
        if previous and previous["certificate_sha256"] and not secrets.compare_digest(previous["certificate_sha256"], fingerprint):
            raise SamsungError("certificate_mismatch")
        parent = self.path.parent
        temporary = None
        descriptor = None
        try:
            if parent.is_symlink() or self.path.is_symlink():
                raise SamsungError("unsafe_token_path")
            parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            details = parent.stat()
            if details.st_uid != os.getuid() or not stat.S_ISDIR(details.st_mode):
                raise SamsungError("unsafe_token_path")
            parent.chmod(0o700)
            temporary = parent / (".samsung-token-" + secrets.token_hex(8))
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                descriptor = None
                json.dump({"host": self.target["host"], "model": self.target["model"], "token": token,
                           "certificate_sha256": fingerprint}, output,
                          separators=(",", ":"))
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
        except SamsungError:
            raise
        except OSError:
            raise SamsungError("token_storage_failed") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)
            if temporary is not None:
                with contextlib.suppress(FileNotFoundError):
                    temporary.unlink()


class SamsungRemote:
    """Only connect(pair=True) may initiate a new TV approval request."""
    def __init__(self, target, token_store=None, *, _fetch_info=None, _connector=None, _connection_base=None):
        self.target = validated_target(target)
        self.store = token_store or TokenStore(self.target)
        self._fetch_info = _fetch_info or (lambda **options: fetch_info(self.target, **options))
        self._connector = _connector
        self._connection_base = _connection_base
        self._fingerprint = None
        self._socket = None
        self._token = None
        self.authorized = False

    def state(self, value):
        return {"state": value, "model": self.target["model"], "token_saved": bool(self._token),
                "source_readback": False, "direct_hdmi": False, "experimental_hdmi": True}

    async def connect(self, *, pair=False, wait_seconds=3):
        if not 0 < wait_seconds <= 45:
            raise SamsungError("invalid_timeout")
        if self.authorized and self._socket is not None:
            return self.state("authorized")
        if self._socket is None:
            record = self.store.load_record()
            previous_pin = record["certificate_sha256"] if record else None
            self._token = record["token"] if record and previous_pin and not pair else None
            if self._token is None and not pair:
                return self.state("pair_required")
            try:
                info, self._fingerprint = await asyncio.wait_for(asyncio.to_thread(
                    self._fetch_info, expected_fingerprint=previous_pin,
                    allow_unpinned=bool(pair and not previous_pin)), timeout=3)
                if not _valid_fingerprint(self._fingerprint):
                    raise SamsungError("certificate_unavailable")
                if previous_pin and not secrets.compare_digest(previous_pin, self._fingerprint):
                    raise SamsungError("certificate_mismatch")
                device = info.get("device", {}) if isinstance(info, dict) else {}
                if device.get("modelName") != self.target["model"]:
                    raise SamsungError("wrong_tv_model")
                if str(device.get("TokenAuthSupport", "")).lower() != "true":
                    raise SamsungError("unsupported_auth")
                name = base64.b64encode(CLIENT_NAME.encode("utf-8")).decode("ascii")
                query = {"name": name}
                if self._token:
                    query["token"] = self._token
                uri = "wss://" + self.target["host"] + ":" + str(self.target["port"]) + "/api/v2/channels/samsung.remote.control?" + urlencode(query)
                connector = self._connector or _fixed_connector()
                connection_type = _pinned_connection(self._fingerprint, self._connection_base)
                self._socket = await asyncio.wait_for(connector(
                    uri, ssl=ssl._create_unverified_context(), proxy=None,
                    open_timeout=4, close_timeout=2, max_size=MAX_MESSAGE_BYTES,
                    max_queue=4, compression=None, ping_interval=None, logger=TRANSPORT_LOG,
                    create_connection=connection_type),
                    timeout=5)
            except SamsungError:
                raise
            except asyncio.TimeoutError:
                raise SamsungError("connect_timeout") from None
            except Exception:
                raise SamsungError("connection_failed") from None
        return await self.wait_authorized(wait_seconds=wait_seconds)

    async def wait_authorized(self, *, wait_seconds=3):
        if self.authorized:
            return self.state("authorized")
        if self._socket is None or not 0 < wait_seconds <= 45:
            raise SamsungError("not_connected")
        deadline = asyncio.get_running_loop().time() + wait_seconds
        for _ in range(64):
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                return self.state("pending")
            try:
                message = await asyncio.wait_for(self._socket.recv(), remaining)
            except asyncio.TimeoutError:
                return self.state("pending")
            except Exception:
                await self.close()
                raise SamsungError("connection_closed") from None
            try:
                if isinstance(message, bytes):
                    message = message.decode("utf-8")
                if not isinstance(message, str) or len(message.encode("utf-8")) > MAX_MESSAGE_BYTES:
                    raise ValueError()
                value = json.loads(message)
                if not isinstance(value, dict):
                    raise ValueError()
            except (ValueError, UnicodeError):
                await self.close()
                raise SamsungError("invalid_message") from None
            event = value.get("event")
            if event in ("ms.channel.unauthorized", "ms.channel.timeOut", "ms.error"):
                await self.close()
                raise SamsungError("authorization_denied")
            if event == "ms.channel.connect":
                data = value.get("data", {})
                if not isinstance(data, dict):
                    await self.close()
                    raise SamsungError("invalid_message")
                token = _valid_token(data.get("token"))
                if token:
                    try:
                        self.store.save(token, self._fingerprint)
                    except SamsungError:
                        await self.close()
                        raise
                    self._token = token
                self.authorized = True
                return self.state("authorized")
        await self.close()
        raise SamsungError("too_many_messages")

    async def navigate(self, action):
        if not isinstance(action, str) or action not in COMMANDS:
            raise SamsungError("invalid_navigation")
        if not self.authorized or self._socket is None:
            raise SamsungError("pair_required")
        payload = {"method": "ms.remote.control", "params": {
            "Cmd": "Click", "DataOfCmd": COMMANDS[action],
            "Option": "false", "TypeOfRemote": "SendRemoteKey"}}
        try:
            await asyncio.wait_for(self._socket.send(json.dumps(payload, separators=(",", ":"))), timeout=2)
        except Exception:
            await self.close()
            raise SamsungError("send_failed") from None
        return {"state": "sent", "action": action, "input_verified": False,
                "experimental": action in EXPERIMENTAL_HDMI_KEYS}

    async def close(self):
        socket, self._socket = self._socket, None
        self.authorized = False
        if socket is not None:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(socket.close(), timeout=3)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        await self.close()


async def _main(args, configuration):
    async with SamsungRemote(configuration["samsung"]) as remote:
        result = await remote.connect(pair=args.command == "pair",
                                      wait_seconds=30 if args.command == "pair" else 3)
        if args.command != "nav" or result["state"] != "authorized":
            return result
        results = []
        for index, action in enumerate(args.actions):
            if index:
                await asyncio.sleep(0.5)
            results.append(await remote.navigate(action))
        return {"state": "sent", "actions": [entry["action"] for entry in results],
                "input_verified": False, "experimental": any(entry["experimental"] for entry in results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=CONFIG.DEFAULT_CONFIG)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("pair", help="Request on-TV approval once; wait at most 30 seconds")
    sub.add_parser("connect", help="Check saved authorization; never initiate new pairing")
    nav = sub.add_parser("nav", help="Send up to eight fixed Samsung control clicks; HDMI keys are unverified")
    nav.add_argument("actions", nargs="+", choices=tuple(COMMANDS))
    args = parser.parse_args()
    if args.command == "nav" and len(args.actions) > 8:
        parser.error("At most eight navigation clicks per invocation")
    try:
        configuration = CONFIG.load(args.config)
    except CONFIG.ConfigError as error:
        parser.error(str(error) + ". Copy and edit config.example.json outside dist/, then chmod 600 it.")
    try:
        print(json.dumps(asyncio.run(_main(args, configuration)), separators=(",", ":")))
    except SamsungError as error:
        print(json.dumps({"state": "error", "error": error.code}, separators=(",", ":")))
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
