"""Fast key integration never contacts a device or starts a helper."""
import types
import unittest
import test_server as base

server = base.server


class FakeInput:
    def __init__(self, ready=True, error=None):
        self.ready, self.error = ready, error
        self.sent, self.warmed, self.closed = [], 0, False

    def send(self, code):
        self.sent.append(code)
        if self.error:
            raise self.error
        return self.ready

    def warm(self):
        self.warmed += 1

    def close(self):
        self.closed = True


class FastBridgeTests(unittest.TestCase):
    def bridge(self, ready=True, model=None, error=None):
        calls = []
        expected = base.FIRE['model'] if hasattr(base, 'FIRE') else server.TV_MODEL

        def run(argv, **options):
            calls.append(argv)
            value = 'device' if argv[-1] == 'get-state' else (model or expected) if argv[-1] == 'ro.product.model' else ''
            return types.SimpleNamespace(returncode=0, stdout=value)

        args = ('/unused/adb', base.FIRE) if hasattr(base, 'FIRE') else ('/unused/adb',)
        bridge = server.AdbBridge(*args, runner=run, clock=lambda: 100)
        bridge.fast_input = FakeInput(ready, error)
        return bridge, calls

    def test_ready_fast_keys_skip_android_input_process_after_identity_check(self):
        bridge, calls = self.bridge()
        bridge.command('key', 'home')
        bridge.command('key', 'right')
        self.assertEqual(bridge.fast_input.sent, [3, 22])
        self.assertEqual(len(calls), 2)  # State and model, once; never input.
        self.assertFalse(any('keyevent' in argv for argv in calls))

    def test_unready_fast_channel_falls_back_once_before_any_fast_write(self):
        bridge, calls = self.bridge(ready=False)
        bridge.command('key', 'right')
        self.assertEqual(len([argv for argv in calls if 'keyevent' in argv]), 1)
        self.assertEqual(calls[-1][-4:], ['shell', 'input', 'keyevent', '22'])

    def test_uncertain_fast_write_never_replays_through_legacy_input(self):
        bridge, calls = self.bridge(error=server.FAST_INPUT.InputError('input_send_uncertain'))
        with self.assertRaises(server.ApiError) as caught:
            bridge.command('key', 'right')
        self.assertEqual(caught.exception.code, 'key_send_uncertain')
        self.assertEqual(bridge.fast_input.sent, [22])
        self.assertFalse(any('keyevent' in argv for argv in calls))

    def test_wrong_model_and_invalid_controls_never_reach_fast_channel(self):
        bridge, calls = self.bridge(model='OTHER')
        with self.assertRaises(server.ApiError):
            bridge.command('key', 'right')
        self.assertEqual(bridge.fast_input.sent, [])
        calls.clear()
        for kind, value in [('key', '3; reboot'), ('shell', 'id')]:
            with self.assertRaises(server.ApiError):
                bridge.command(kind, value)
        self.assertEqual(calls, [])
        self.assertEqual(bridge.fast_input.sent, [])

    def test_fast_keys_keep_existing_rate_limit_and_apps_use_legacy_path(self):
        bridge, calls = self.bridge()
        for _ in range(8):
            bridge.command('key', 'right')
        with self.assertRaises(server.ApiError) as caught:
            bridge.command('key', 'right')
        self.assertEqual(caught.exception.code, 'control_rate_limited')
        self.assertEqual(len(bridge.fast_input.sent), 8)
        bridge, calls = self.bridge()
        bridge.command('app', 'paramount')
        bridge.command('text', 'Nepal')
        self.assertEqual(bridge.fast_input.sent, [])
        self.assertEqual(len(calls), 4)


class FastRequestTests(unittest.TestCase):
    setUp = base.RequestTests.setUp
    tearDown = base.RequestTests.tearDown
    request = base.RequestTests.request

    def test_only_authenticated_online_status_warms_control_and_never_sends(self):
        fast = self.app.fast_input = FakeInput()
        self.assertEqual(self.request(path='/api/status')[0], 401)
        self.assertEqual(fast.warmed, 0)
        self.assertEqual(self.request(path='/api/status', auth=True,
                                     headers=[('Origin', 'http://attacker.example')])[0], 403)
        self.assertEqual(fast.warmed, 0)
        self.bridge.status = lambda: False
        self.assertEqual(self.request(path='/api/status', auth=True)[0], 200)
        self.assertEqual(fast.warmed, 0)
        self.bridge.status = lambda: True
        self.assertEqual(self.request(path='/api/status', auth=True)[0], 200)
        self.assertEqual(fast.warmed, 1)
        self.assertEqual(fast.sent, [])
        self.app.close()
        self.assertTrue(fast.closed)


if __name__ == '__main__':
    unittest.main()
