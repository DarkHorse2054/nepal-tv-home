"""Offline fixed-target Samsung tests: fake sockets only; never contacts a TV."""
import asyncio
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import types
from unittest.mock import AsyncMock, patch
from fixtures import SAMSUNG
from urllib.parse import parse_qs, urlsplit

SPEC = importlib.util.spec_from_file_location("samsung_remote", Path(__file__).resolve().parents[1] / "samsung.py")
samsung = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(samsung)
IDENTITY = {"device": {"modelName": SAMSUNG["model"], "TokenAuthSupport": "true"}}

CERTIFICATE = b"synthetic approved Samsung leaf"
FINGERPRINT = hashlib.sha256(CERTIFICATE).hexdigest()


class FakePeer:
    def __init__(self, certificate=CERTIFICATE): self.certificate = certificate
    def getpeercert(self, binary_form=False):
        assert binary_form
        return self.certificate


class FakeConnectionBase:
    async def handshake(self, *args, **kwargs):
        self.upgraded = True



class FakeSocket:
    def __init__(self, messages=()):
        self.messages = list(messages)
        self.sent = []
        self.closed = False

    async def recv(self):
        if self.messages:
            return self.messages.pop(0)
        await asyncio.sleep(5)

    async def send(self, payload):
        self.sent.append(json.loads(payload))

    async def close(self):
        self.closed = True


class TokenTests(unittest.TestCase):
    def test_private_atomic_token_and_target_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            store = samsung.TokenStore(SAMSUNG, state_dir=Path(directory) / "state")
            target = store.path
            self.assertIsNone(store.load())
            store.save("123456789", FINGERPRINT)
            self.assertEqual(store.load(), "123456789")
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(target.parent.stat().st_mode), 0o700)
            self.assertEqual(set(json.loads(target.read_text())), {"host", "model", "token", "certificate_sha256"})
            other = samsung.TokenStore(dict(SAMSUNG, host="192.168.1.102"), state_dir=target.parent)
            self.assertNotEqual(other.path, target)
            self.assertIsNone(other.load())
            value = json.loads(target.read_text())
            value["model"] = "WrongModel"
            target.write_text(json.dumps(value))
            with self.assertRaises(samsung.SamsungError):
                store.load()
            target.chmod(0o644)
            with self.assertRaises(samsung.SamsungError) as caught:
                store.load()
            self.assertEqual(caught.exception.code, "unsafe_token_file")

    def test_static_and_symlink_token_files_rejected(self):
        with self.assertRaises(samsung.SamsungError):
            samsung.TokenStore(SAMSUNG, state_dir=samsung.DIST / "state")
        with tempfile.TemporaryDirectory() as directory:
            store = samsung.TokenStore(SAMSUNG, state_dir=Path(directory) / "state")
            store.save("12345", FINGERPRINT)
            original = Path(directory) / "original.json"
            store.path.rename(original)
            store.path.symlink_to(original)
            with self.assertRaises(samsung.SamsungError):
                store.load()
            with self.assertRaises(samsung.SamsungError):
                store.save("12345", FINGERPRINT)


class RemoteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = samsung.TokenStore(SAMSUNG, state_dir=Path(self.temp.name) / "state")
        self.calls = []
        self.socket = FakeSocket()
        self.probes = 0

    async def asyncTearDown(self):
        self.temp.cleanup()

    def identity(self, **options):
        self.probes += 1
        return IDENTITY, FINGERPRINT

    async def connector(self, uri, **kwargs):
        connection = kwargs["create_connection"]()
        connection.transport = types.SimpleNamespace(get_extra_info=lambda name: FakePeer())
        await connection.handshake()
        self.calls.append((uri, kwargs))
        return self.socket

    def client(self, identity=None):
        return samsung.SamsungRemote(SAMSUNG, self.store, _fetch_info=identity or self.identity,
                                     _connector=self.connector, _connection_base=FakeConnectionBase)

    async def test_missing_token_cannot_implicitly_pair_or_probe(self):
        async with self.client() as client:
            self.assertEqual((await client.connect())["state"], "pair_required")
            self.assertEqual(self.calls, [])
            self.assertEqual(self.probes, 0)
            for key in ("source", "hdmi1", "volume_up", "power_off"):
                with self.assertRaises(samsung.SamsungError):
                    await client.navigate(key)
            self.assertEqual(self.socket.sent, [])

    async def test_pair_saves_token_and_exact_tls_transport(self):
        self.socket.messages = [json.dumps({"event": "ms.channel.connect", "data": {"token": "777777"}})]
        async with self.client() as client:
            result = await client.connect(pair=True)
            self.assertEqual(result["state"], "authorized")
            self.assertTrue(result["token_saved"])
            self.assertNotIn("777777", json.dumps(result))
        self.assertEqual(self.store.load(), "777777")
        uri, options = self.calls[0]
        parsed = urlsplit(uri)
        self.assertEqual((parsed.scheme, parsed.hostname, parsed.port, parsed.path),
                         ("wss", SAMSUNG["host"], 8002, "/api/v2/channels/samsung.remote.control"))
        self.assertNotIn("token", parse_qs(parsed.query))
        self.assertIsNone(options["proxy"])
        self.assertEqual(options["max_size"], 16384)
        self.assertLessEqual(options["open_timeout"], 4)
        self.assertTrue(self.socket.closed)

    async def test_saved_auth_and_allowlisted_clicks_only(self):
        self.store.save("888888", FINGERPRINT)
        self.socket.messages = [json.dumps({"event": "ms.channel.connect", "data": {}})]
        async with self.client() as client:
            self.assertEqual((await client.connect())["state"], "authorized")
            for action, key in samsung.NAVIGATION.items():
                result = await client.navigate(action)
                self.assertFalse(result["input_verified"])
                self.assertEqual(self.socket.sent[-1], {"method": "ms.remote.control", "params": {
                    "Cmd": "Click", "DataOfCmd": key, "Option": "false", "TypeOfRemote": "SendRemoteKey"}})
            before = len(self.socket.sent)
            for invalid in ("KEY_HDMI1", "hdmi5", "power", "power_on", "text", "apps", "source;reboot", ["source"]):
                with self.assertRaises(samsung.SamsungError):
                    await client.navigate(invalid)
            self.assertEqual(len(self.socket.sent), before)
        self.assertEqual(parse_qs(urlsplit(self.calls[0][0]).query)["token"], ["888888"])

    async def test_explicit_panel_controls_and_unverified_hdmi_requests(self):
        self.store.save("888888", FINGERPRINT)
        self.socket.messages = [json.dumps({"event": "ms.channel.connect", "data": {}})]
        expected = {"hdmi1": "KEY_HDMI1", "hdmi2": "KEY_HDMI2",
                    "hdmi3": "KEY_HDMI3", "hdmi4": "KEY_HDMI4",
                    "volume_up": "KEY_VOLUP", "volume_down": "KEY_VOLDOWN",
                    "mute": "KEY_MUTE", "power_off": "KEY_POWEROFF"}
        async with self.client() as client:
            state = await client.connect()
            self.assertTrue(state["experimental_hdmi"])
            self.assertFalse(state["direct_hdmi"])
            self.assertFalse(state["source_readback"])
            for action, key in expected.items():
                with self.subTest(action=action):
                    result = await client.navigate(action)
                    self.assertEqual(self.socket.sent[-1], {"method": "ms.remote.control", "params": {
                        "Cmd": "Click", "DataOfCmd": key, "Option": "false", "TypeOfRemote": "SendRemoteKey"}})
                    self.assertEqual(result, {"state": "sent", "action": action, "input_verified": False,
                                              "experimental": action.startswith("hdmi")})
                    self.assertNotIn("active", result)
                    self.assertNotIn("selected", result)

    async def test_failed_send_is_not_reported_as_sent(self):
        self.store.save("888888", FINGERPRINT)
        self.socket.messages = [json.dumps({"event": "ms.channel.connect", "data": {}})]
        async def fail_send(_payload):
            raise OSError("private transport details")
        self.socket.send = fail_send
        async with self.client() as client:
            await client.connect()
            with self.assertRaises(samsung.SamsungError) as caught:
                await client.navigate("hdmi1")
            self.assertEqual(caught.exception.code, "send_failed")
            self.assertFalse(client.authorized)
            self.assertTrue(self.socket.closed)

    async def test_wrong_model_rejected_before_websocket(self):
        async with self.client(lambda **options: ({"device": {"modelName": "OTHER", "TokenAuthSupport": "true"}}, FINGERPRINT)) as client:
            with self.assertRaises(samsung.SamsungError) as caught:
                await client.connect(pair=True)
            self.assertEqual(caught.exception.code, "wrong_tv_model")
        self.assertEqual(self.calls, [])

    async def test_pending_retains_connection_without_authorizing_keys(self):
        async with self.client() as client:
            result = await client.connect(pair=True, wait_seconds=0.01)
            self.assertEqual(result["state"], "pending")
            self.assertIsNone(self.store.load_record())
            self.assertFalse(self.socket.closed)
            with self.assertRaises(samsung.SamsungError):
                await client.navigate("source")
            self.socket.messages = [json.dumps({"event": "ms.channel.connect", "data": {"token": 999999}})]
            self.assertEqual((await client.wait_authorized())["state"], "authorized")
        self.assertTrue(self.socket.closed)

    async def test_denial_and_oversize_close_without_commands(self):
        for message, code in ((json.dumps({"event": "ms.channel.unauthorized"}), "authorization_denied"),
                              ("x" * (samsung.MAX_MESSAGE_BYTES + 1), "invalid_message")):
            self.socket = FakeSocket([message])
            async with self.client() as client:
                with self.assertRaises(samsung.SamsungError) as caught:
                    await client.connect(pair=True)
                self.assertEqual(caught.exception.code, code)
            self.assertTrue(self.socket.closed)
            self.assertEqual(self.socket.sent, [])
            self.assertIsNone(self.store.load_record())

    async def test_legacy_token_requires_explicit_pairing_without_network_or_auto_trust(self):
        self.store.path.parent.mkdir(mode=0o700)
        host = self.store.target["host"] if hasattr(self.store, "target") else samsung.TV_IP
        model = self.store.target["model"] if hasattr(self.store, "target") else samsung.TV_MODEL
        self.store.path.write_text(json.dumps({"host": host, "model": model, "token": "legacy123"}))
        self.store.path.chmod(0o600)
        self.assertIsNone(self.store.load())
        async with self.client() as client:
            self.assertEqual((await client.connect())["state"], "pair_required")
            self.assertEqual(self.probes, 0)
            self.assertEqual(self.calls, [])
        self.socket.messages = [json.dumps({"event": "ms.channel.connect", "data": {"token": "approved456"}})]
        async with self.client() as client:
            self.assertEqual((await client.connect(pair=True))["state"], "authorized")
        self.assertEqual(self.store.load(), "approved456")
        self.assertEqual(self.store.load_record()["certificate_sha256"], FINGERPRINT)
        self.assertNotIn("token", parse_qs(urlsplit(self.calls[0][0]).query))

    async def test_existing_pin_cannot_be_replaced_even_by_explicit_pair(self):
        self.store.save("888888", FINGERPRINT)
        before = self.store.path.read_bytes()
        replacement = hashlib.sha256(b"attacker leaf").hexdigest()
        for pair in (False, True):
            async with self.client(lambda **options: (IDENTITY, replacement)) as client:
                with self.assertRaises(samsung.SamsungError) as caught:
                    await client.connect(pair=pair)
                self.assertEqual(caught.exception.code, "certificate_mismatch")
            self.assertEqual(self.calls, [])
            self.assertEqual(self.store.path.read_bytes(), before)
        with self.assertRaises(samsung.SamsungError) as caught:
            self.store.save("newtoken", replacement)
        self.assertEqual(caught.exception.code, "certificate_mismatch")
        self.assertEqual(self.store.path.read_bytes(), before)

    async def test_websocket_certificate_is_checked_before_token_upgrade(self):
        self.store.save("secretToken", FINGERPRINT)
        upgrades = []
        class Base:
            async def handshake(self, *args, **kwargs): upgrades.append("HTTP Upgrade sent")
        async def connector(uri, **options):
            self.assertIn("secretToken", uri)
            connection = options["create_connection"]()
            connection.transport = types.SimpleNamespace(get_extra_info=lambda name: FakePeer(b"attacker leaf"))
            await connection.handshake()
            raise AssertionError("mismatched peer must not reach an HTTP Upgrade")
        client = self.client()
        client._connector, client._connection_base = connector, Base
        async with client:
            with self.assertRaises(samsung.SamsungError) as caught:
                await client.connect()
            self.assertEqual(caught.exception.code, "certificate_mismatch")
        self.assertEqual(upgrades, [])
        self.assertEqual(self.store.load(), "secretToken")



class MetadataTests(unittest.TestCase):
    def test_redirect_is_rejected_without_reading_or_following(self):
        class Response:
            status = 302
            def read(self, size):
                raise AssertionError("redirect body must not be consumed")
        class Connection:
            instances = []
            def __init__(self, host, port, **kwargs):
                self.target = (host, port, kwargs)
                self.requests = []
                self.closed = False
                self.instances.append(self)
            def connect(self): self.sock = FakePeer()
            def request(self, *args, **kwargs):
                self.requests.append((args, kwargs))
            def getresponse(self):
                return Response()
            def close(self):
                self.closed = True
        with patch.object(samsung.http.client, "HTTPSConnection", Connection):
            with self.assertRaises(samsung.SamsungError) as caught:
                samsung.fetch_info(SAMSUNG, allow_unpinned=True)
        self.assertEqual(caught.exception.code, "identity_unavailable")
        self.assertEqual(len(Connection.instances), 1)
        connection = Connection.instances[0]
        self.assertEqual(connection.target[:2], (SAMSUNG["host"], 8002))
        self.assertEqual(connection.requests[0][0], ("GET", "/api/v2/"))
        self.assertTrue(connection.closed)

    def test_installed_websockets_checks_pin_before_real_base_handshake_and_never_redirects(self):
        try:
            from websockets.asyncio.client import ClientConnection
        except ImportError:
            self.skipTest("Optional websockets dependency is not installed")
        kind = samsung._pinned_connection(FINGERPRINT)
        connection = object.__new__(kind)
        async def check():
            with patch.object(ClientConnection, "handshake", new_callable=AsyncMock) as upgrade:
                connection.transport = types.SimpleNamespace(get_extra_info=lambda name: FakePeer(b"attacker leaf"))
                with self.assertRaises(samsung.SamsungError) as caught:
                    await connection.handshake()
                self.assertEqual(caught.exception.code, "certificate_mismatch")
                upgrade.assert_not_awaited()
                connection.transport = types.SimpleNamespace(get_extra_info=lambda name: FakePeer())
                await connection.handshake()
                upgrade.assert_awaited_once()
        asyncio.run(check())
        connector = object.__new__(samsung._fixed_connector())
        redirect_error = RuntimeError("untrusted redirect")
        self.assertIs(connector.process_redirect(redirect_error), redirect_error)

    def test_https_pin_mismatch_precedes_any_http_request(self):
        requests = []
        class Connection:
            def __init__(self, *args, **kwargs): self.closed = False
            def connect(self): self.sock = FakePeer(b"attacker leaf")
            def request(self, *args, **kwargs): requests.append(args)
            def close(self): self.closed = True
        with patch.object(samsung.http.client, "HTTPSConnection", Connection):
            with self.assertRaises(samsung.SamsungError) as caught:
                samsung.fetch_info(SAMSUNG, expected_fingerprint=FINGERPRINT)
        self.assertEqual(caught.exception.code, "certificate_mismatch")
        self.assertEqual(requests, [])



if __name__ == "__main__":
    unittest.main()
