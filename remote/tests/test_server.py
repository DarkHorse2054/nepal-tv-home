"""Security/allowlist tests. No listener is bound and no ADB process is run."""
from datetime import datetime, timezone
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from fixtures import FIRE, SAMSUNG, CLOCKS, configuration, serial

SPEC = importlib.util.spec_from_file_location("remote_server", Path(__file__).resolve().parents[1] / "server.py")
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)


class FakeBridge:
    def __init__(self): self.commands = []; self.status_calls = 0
    def status(self): self.status_calls += 1; return True
    def command(self, kind, value): self.commands.append((kind, value))


class FakeSocket:
    def __init__(self, raw): self.input = io.BytesIO(raw); self.output = io.BytesIO()
    def makefile(self, *_args, **_kwargs): return self.input
    def sendall(self, data): self.output.write(data)
    def settimeout(self, timeout): self.timeout = timeout


class RequestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dist = Path(self.temp.name) / "dist"
        self.dist.mkdir()
        (self.dist / "index.html").write_text("remote")
        (self.dist / "app.js").write_text("'use strict';")
        self.bridge = FakeBridge()
        self.app = server.RemoteApp(self.bridge, {"127.0.0.1"}, 8765,
            dist=self.dist, pairing=server.Pairing(pin="123456"), config=configuration())
        self.token = self.app.pairing.pair("123456", "127.0.0.1")["token"]

    def tearDown(self): self.temp.cleanup()

    def request(self, method="GET", path="/", payload=None, auth=False, headers=(), body=None):
        if body is None:
            body = b"" if payload is None else json.dumps(payload).encode()
        pairs = [("Host", "127.0.0.1:8765")]
        if method == "POST": pairs.append(("Origin", "http://127.0.0.1:8765"))
        if auth: pairs.append(("Authorization", "Bearer " + self.token))
        if payload is not None or body: pairs.extend([("Content-Type", "application/json"), ("Content-Length", str(len(body)))])
        for key, value in headers:
            if not key.startswith("+"):
                pairs = [(k,v) for k,v in pairs if k.lower() != key.lower()]
            pairs.append((key.lstrip("+"), value))
        raw = (f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k,v in pairs) + "\r\n").encode() + body
        sock = FakeSocket(raw)
        server.Handler(sock, ("127.0.0.1", 45000), types.SimpleNamespace(app=self.app))
        head, _, content = sock.output.getvalue().partition(b"\r\n\r\n")
        code = int(head.split(b" ", 2)[1])
        return code, head.decode(), content

    def test_pair_and_authenticated_status(self):
        code, headers, content = self.request("POST", "/api/pair", {"pin":"123456"})
        result = json.loads(content)
        self.assertEqual(code, 200)
        self.assertEqual(len(result["token"]), 43)
        self.assertGreater(result["expiresIn"], 0)
        code, headers, content = self.request(path="/api/status", auth=True)
        status = json.loads(content)
        self.assertTrue(status["online"])
        self.assertEqual(status["device"]["model"], FIRE["model"])
        self.assertTrue({"nepal","sanMateo","kathmanduTime","localLabel","secondaryLabel"} <= set(status["date"]))
        self.assertNotIn("Access-Control-Allow", headers)
        self.assertIn("Cache-Control: no-store", headers)
        self.assertIn("frame-ancestors 'none'", headers)

    def test_unauthenticated_control_never_reaches_bridge(self):
        for path, field, value in (("/api/key","key","home"),("/api/app","app","netflix")):
            self.assertEqual(self.request("POST",path,{field:value})[0],401)
        self.assertEqual(self.request(path="/api/status")[0],401)
        self.assertEqual(self.bridge.commands,[])
        self.assertEqual(self.bridge.status_calls,0)

    def test_csrf_and_dns_rebinding_are_rejected(self):
        self.assertEqual(self.request("POST","/api/key",{"key":"ok"},auth=True,
            headers=[("Origin","https://attacker.example")])[0],403)
        self.assertEqual(self.request("POST","/api/key",{"key":"ok"},auth=True,
            headers=[("Origin", "null")])[0],403)
        self.assertEqual(self.request("POST","/api/key",{"key":"ok"},auth=True,
            headers=[("Sec-Fetch-Site", "cross-site")])[0],403)
        self.assertEqual(self.request(headers=[("Host","attacker.example:8765")])[0],421)
        self.assertEqual(self.request(headers=[("Host","127.0.0.1:80")])[0],421)
        self.assertEqual(self.request(headers=[("+Host","127.0.0.1:8765")])[0],400)
        self.assertEqual(self.bridge.commands,[])

    def test_missing_origin_cannot_pair_or_control(self):
        raw = b'POST /api/pair HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nContent-Type: application/json\r\nContent-Length: 16\r\n\r\n{"pin":"123456"}'
        sock=FakeSocket(raw)
        server.Handler(sock,("127.0.0.1",1),types.SimpleNamespace(app=self.app))
        self.assertTrue(sock.output.getvalue().startswith(b"HTTP/1.1 403"))

    def test_strict_allowlists_and_payloads(self):
        for payload in ({"key":"home;reboot"}, {"key":"text"}, {"key":["home"]},
                        {"key":"home","command":"reboot"}):
            self.assertEqual(self.request("POST","/api/key",payload,auth=True)[0],400)
        for payload in ({"app":"prime"},{"app":"com.android.settings"}):
            self.assertEqual(self.request("POST","/api/app",payload,auth=True)[0],400)
        self.assertEqual(self.bridge.commands,[])
        self.assertEqual(self.request("POST","/api/key",{"key":"home"},auth=True)[0],200)
        self.assertEqual(self.request("POST","/api/app",{"app":"paramount"},auth=True)[0],200)
        self.assertEqual(self.bridge.commands,[("key","home"),("app","paramount")])

    def test_static_traversal_and_private_paths(self):
        secret=Path(self.temp.name)/"secret.txt"
        secret.write_text("private")
        (self.dist/"escape.js").symlink_to(secret)
        for path in ("/../secret.txt","/%2e%2e/secret.txt","/%2E%2E/secret.txt",
                     "/%00index.html","/escape.js","/.env","/server.py","/api/shell","/\\secret"):
            self.assertEqual(self.request(path=path)[0],404,path)
        self.assertEqual(self.request(path="/app.js")[0],200)

    def test_request_body_limits_and_chunking(self):
        self.assertEqual(self.request("POST","/api/key",{"key":"ok"},auth=True,
            headers=[("Content-Length","2049")])[0],413)
        self.assertEqual(self.request("POST","/api/key",{"key":"ok"},auth=True,
            headers=[("Transfer-Encoding","chunked")])[0],400)
        self.assertEqual(self.request("POST","/api/key",{"key":"ok"},auth=True,
            headers=[("+Content-Length","1")])[0],400)
        self.assertEqual(self.request("POST","/api/key",auth=True,body=b"{")[0],400)
        self.assertEqual(self.request("POST","/api/key",auth=True,
            body=b'{"key":"up","key":"home"}')[0],400)
        self.assertEqual(self.request("POST","/api/key",{"key":"up"},auth=True,
            headers=[("+Authorization","Bearer "+self.token)])[0],400)
        self.assertEqual(self.bridge.commands,[])


class PairingTests(unittest.TestCase):
    def test_pin_bruteforce_and_expiry(self):
        now=[0.0]
        pairing=server.Pairing(pin="123456",clock=lambda:now[0])
        token=pairing.pair("123456","own")["token"]
        self.assertTrue(pairing.authorize("Bearer "+token,"own"))
        for _ in range(5):
            with self.assertRaises(server.ApiError) as context: pairing.pair("999999","attacker")
            self.assertEqual(context.exception.status,401)
        with self.assertRaises(server.ApiError) as context: pairing.pair("123456","attacker")
        self.assertEqual(context.exception.status,429)
        now[0]=pairing.lifetime+1
        with self.assertRaises(server.ApiError) as context: pairing.authorize("Bearer "+token,"own")
        self.assertEqual(context.exception.status,401)
        self.assertEqual(pairing.tokens,{})


class BridgeTests(unittest.TestCase):
    def setUp(self): self.calls=[]
    def runner(self,argv,**kwargs):
        self.assertFalse(kwargs["shell"])
        self.assertLessEqual(kwargs["timeout"],5)
        self.calls.append(argv)
        output="device" if argv[-1]=="get-state" else FIRE["model"] if argv[-1]=="ro.product.model" else ""
        return types.SimpleNamespace(returncode=0,stdout=output)

    def test_fixed_home_and_component_vectors(self):
        bridge=server.AdbBridge("/tmp/adb", FIRE,runner=self.runner)
        bridge.command("key","home")
        self.assertEqual(self.calls[-1],["/private/tmp/adb" if Path('/tmp').resolve()==Path('/private/tmp') else "/tmp/adb",
                         "-s",serial(),"shell","input","keyevent","3"])
        bridge.command("app","paramount")
        self.assertEqual(self.calls[-1][-5:],["shell","am","start","-n",server.APPS["paramount"]])
        self.assertFalse(any("com.gathin.tvhome" in arg for call in self.calls for arg in call))
        before=len(self.calls)
        with self.assertRaises(server.ApiError): bridge.command("key","3; reboot")
        with self.assertRaises(server.ApiError): bridge.command("shell","id")
        self.assertEqual(len(self.calls),before)

    def test_busy_rate_limit_and_timeout(self):
        bridge=server.AdbBridge("/tmp/adb", FIRE,runner=self.runner)
        bridge.command_lock.acquire()
        try:
            with self.assertRaises(server.ApiError) as context: bridge.command("key","up")
            self.assertEqual(context.exception.status,429)
            self.assertFalse(self.calls)
        finally: bridge.command_lock.release()
        now=[0.0]
        bridge=server.AdbBridge("/tmp/adb", FIRE,runner=self.runner,clock=lambda:now[0])
        for _ in range(8): bridge.command("key","right")
        with self.assertRaises(server.ApiError) as context: bridge.command("key","right")
        self.assertEqual(context.exception.status,429)
        def timeout(*args,**kwargs): raise subprocess.TimeoutExpired(args[0],2)
        self.assertFalse(server.AdbBridge("/tmp/adb", FIRE,runner=timeout).status())

    def test_reconnect_is_only_fixed_target_and_wrong_model_rejected(self):
        calls=[]
        def offline(argv,**kwargs):
            calls.append(argv)
            return types.SimpleNamespace(returncode=1,stdout="offline")
        bridge=server.AdbBridge("/tmp/adb", FIRE,runner=offline)
        self.assertFalse(bridge.status())
        self.assertEqual([c[1:] for c in calls if c[1]=="connect"],[["connect",serial()]])
        self.assertFalse(any("disconnect" in c for c in calls))
        calls.clear()
        with self.assertRaises(server.ApiError): bridge.command("key","ok")
        self.assertFalse(any("connect" in c for c in calls))
        def other_model(argv,**kwargs):
            return types.SimpleNamespace(returncode=0,stdout="device" if argv[-1]=="get-state" else "WRONG")
        bridge=server.AdbBridge("/tmp/adb", FIRE,runner=other_model)
        with self.assertRaises(server.ApiError) as context: bridge.command("key","ok")
        self.assertEqual(context.exception.status,503)

    def test_total_control_timeout_budget_is_below_frontend_abort(self):
        budgets=[]
        def budgeted_runner(argv,**kwargs):
            budgets.append(kwargs["timeout"])
            return self.runner(argv,**kwargs)
        bridge=server.AdbBridge("/tmp/adb", FIRE,runner=budgeted_runner)
        bridge.command("key","up")
        self.assertEqual(budgets,[1,1,4])
        self.assertLess(sum(budgets),8)


class CalendarTests(unittest.TestCase):
    def test_nepal_new_year_and_san_mateo_dst(self):
        calendar=server.CivilDates(CLOCKS)
        value=calendar.status(datetime(2026,4,14,0,30,tzinfo=timezone.utc))
        self.assertEqual(value["nepal"],"1 Baisakh 2083 BS")
        self.assertEqual(value["sanMateo"],"Mon, Apr 13")
        self.assertEqual(value["kathmanduTime"],"6:15 AM")
        before=calendar.status(datetime(2026,11,1,8,30,tzinfo=timezone.utc))
        after=calendar.status(datetime(2026,11,1,9,30,tzinfo=timezone.utc))
        self.assertEqual(before["sanMateo"],after["sanMateo"])
        self.assertEqual(calendar.nepal_date(datetime(2030,1,1).date()),"Jan 1, 2030")


if __name__=="__main__": unittest.main()
