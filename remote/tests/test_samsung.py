"""Offline fixed-target Samsung tests: fake sockets only; never contacts a TV."""
import asyncio
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
from fixtures import SAMSUNG
from urllib.parse import parse_qs, urlsplit

SPEC = importlib.util.spec_from_file_location("samsung_remote", Path(__file__).resolve().parents[1] / "samsung.py")
samsung = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(samsung)
IDENTITY = {"device": {"modelName": SAMSUNG["model"], "TokenAuthSupport": "true"}}


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
            store.save("123456789")
            self.assertEqual(store.load(), "123456789")
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(target.parent.stat().st_mode), 0o700)
            self.assertEqual(set(json.loads(target.read_text())), {"host", "model", "token"})
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
            store.save("12345")
            original = Path(directory) / "original.json"
            store.path.rename(original)
            store.path.symlink_to(original)
            with self.assertRaises(samsung.SamsungError):
                store.load()
            with self.assertRaises(samsung.SamsungError):
                store.save("12345")


class RemoteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = samsung.TokenStore(SAMSUNG, state_dir=Path(self.temp.name) / "state")
        self.calls = []
        self.socket = FakeSocket()
        self.probes = 0

    async def asyncTearDown(self):
        self.temp.cleanup()

    def identity(self):
        self.probes += 1
        return IDENTITY

    async def connector(self, uri, **kwargs):
        self.calls.append((uri, kwargs))
        return self.socket

    def client(self, identity=None):
        return samsung.SamsungRemote(SAMSUNG, self.store, _fetch_info=identity or self.identity,
                                     _connector=self.connector)

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
        self.store.save("888888")
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
        self.store.save("888888")
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
        self.store.save("888888")
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
        async with self.client(lambda: {"device": {"modelName": "OTHER", "TokenAuthSupport": "true"}}) as client:
            with self.assertRaises(samsung.SamsungError) as caught:
                await client.connect(pair=True)
            self.assertEqual(caught.exception.code, "wrong_tv_model")
        self.assertEqual(self.calls, [])

    async def test_pending_retains_connection_without_authorizing_keys(self):
        async with self.client() as client:
            result = await client.connect(pair=True, wait_seconds=0.01)
            self.assertEqual(result["state"], "pending")
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
            def request(self, *args, **kwargs):
                self.requests.append((args, kwargs))
            def getresponse(self):
                return Response()
            def close(self):
                self.closed = True
        with patch.object(samsung.http.client, "HTTPSConnection", Connection):
            with self.assertRaises(samsung.SamsungError) as caught:
                samsung.fetch_info(SAMSUNG)
        self.assertEqual(caught.exception.code, "identity_unavailable")
        self.assertEqual(len(Connection.instances), 1)
        connection = Connection.instances[0]
        self.assertEqual(connection.target[:2], (SAMSUNG["host"], 8002))
        self.assertEqual(connection.requests[0][0], ("GET", "/api/v2/"))
        self.assertTrue(connection.closed)


if __name__ == "__main__":
    unittest.main()
