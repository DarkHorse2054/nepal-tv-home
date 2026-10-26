"""No TV, listener, real process or saved image is used by these checks."""
import importlib.util
import gzip
import io
from pathlib import Path
import struct
import subprocess
import threading
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('preview_under_test', Path(__file__).resolve().parents[1] / 'screen_preview.py')
preview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preview)


class FakeProcess:
    def __init__(self, data, timeout=False):
        self.stdout = io.BytesIO(data)
        self.returncode = None
        self.timeout = timeout
        self.killed = False
        self.reaped = False
    def wait(self, timeout=None):
        if self.timeout and not self.killed:
            raise subprocess.TimeoutExpired('fixed-adb', timeout)
        self.returncode = -9 if self.killed else 0
        self.reaped = True
        return self.returncode
    def kill(self): self.killed = True
    def poll(self): return self.returncode


class CaptureTests(unittest.TestCase):
    def test_binary_bytes_and_only_fixed_argv_survive(self):
        data = b'\x89PNG\r\n\x1a\n\x00\r\n\xff'
        process = FakeProcess(data)
        calls = []
        def popen(argv, **options):
            calls.append((argv, options))
            return process
        self.assertEqual(preview.capture_png('/fixed/adb', '192.0.2.10:5555', {'SAFE': 'yes'}, popen=popen), data)
        self.assertEqual(calls[0][0], ['/fixed/adb', '-s', '192.0.2.10:5555', 'exec-out', 'sh', '-c', preview.CAPTURE_COMMAND])
        self.assertEqual(preview.CAPTURE_COMMAND,
                         'if command -v gzip >/dev/null 2>&1; then screencap | gzip -1; else screencap -p; fi')
        self.assertFalse(calls[0][1]['shell'])
        self.assertEqual(calls[0][1]['stderr'], subprocess.DEVNULL)
        self.assertTrue(process.stdout.closed)
        self.assertTrue(process.reaped)

    def test_oversized_stdout_kills_and_reaps_the_process(self):
        process = FakeProcess(b'x' * 100)
        with self.assertRaises(preview.PreviewError) as error:
            preview.capture_png('/adb', '192.0.2.10:5555', {}, popen=lambda *a, **k: process, max_bytes=16)
        self.assertEqual(error.exception.code, 'preview_invalid_image')
        self.assertTrue(process.killed)
        self.assertTrue(process.reaped)
        self.assertTrue(process.stdout.closed)

    def test_timeout_kills_and_reaps_without_returning_partial_bytes(self):
        process = FakeProcess(b'partial', timeout=True)
        with self.assertRaises(preview.PreviewError) as error:
            preview.capture_png('/adb', '192.0.2.10:5555', {}, popen=lambda *a, **k: process)
        self.assertEqual(error.exception.code, 'preview_timeout')
        self.assertTrue(process.killed)
        self.assertTrue(process.reaped)

    def test_missing_optional_dependency_never_captures(self):
        captures = []
        with patch.object(preview, 'Image', None):
            with self.assertRaises(preview.PreviewError) as error:
                preview.ScreenPreview(lambda: captures.append(True)).frame()
        self.assertEqual(error.exception.code, 'preview_dependency_missing')
        self.assertEqual(captures, [])


@unittest.skipIf(preview.Image is None, 'Pillow optional; install it to check image decoding')
class FrameTests(unittest.TestCase):
    def png(self, size=(16, 9)):
        output = io.BytesIO()
        with preview.Image.new('RGB', size, (45, 62, 59)) as image:
            image.save(output, format='PNG')
        return output.getvalue()

    def test_resize_preserves_ratio_and_returns_bounded_jpeg(self):
        jpeg = preview.jpeg_frame(self.png((1920, 1080)))
        self.assertLessEqual(len(jpeg), preview.MAX_JPEG_BYTES)
        with preview.Image.open(io.BytesIO(jpeg)) as image:
            self.assertEqual(image.format, 'JPEG')
            self.assertEqual(image.size, (1280, 720))

    def raw_gzip(self, size=(16, 9), pixel_format=1, dataspace=None):
        width, height = size
        header = struct.pack('<III', width, height, pixel_format)
        if dataspace is not None:
            header += struct.pack('<I', dataspace)
        color = bytes((45, 62, 59)) + (b'\xff' if pixel_format != 3 else b'')
        return gzip.compress(header + color * (width * height), compresslevel=1)

    def test_raw_gzip_formats_and_both_header_lengths_preserve_pixels(self):
        for pixel_format in (1, 2, 3):
            for dataspace in (None, 0, 142671872):
                with self.subTest(pixel_format=pixel_format, dataspace=dataspace):
                    jpeg = preview.jpeg_frame(self.raw_gzip(pixel_format=pixel_format, dataspace=dataspace))
                    with preview.Image.open(io.BytesIO(jpeg)) as image:
                        self.assertEqual(image.size, (16, 9))
                        self.assertEqual(image.mode, 'RGB')
                        self.assertTrue(all(abs(actual - expected) < 4 for actual, expected in
                                            zip(image.getpixel((8, 4)), (45, 62, 59))))

    def test_raw_gzip_resizes_to_the_same_jpeg_limit(self):
        jpeg = preview.jpeg_frame(self.raw_gzip((1920, 1080)))
        self.assertLessEqual(len(jpeg), preview.MAX_JPEG_BYTES)
        with preview.Image.open(io.BytesIO(jpeg)) as image:
            self.assertEqual(image.format, 'JPEG')
            self.assertEqual(image.size, (1280, 720))

    def test_raw_header_dimensions_format_and_exact_length_are_checked(self):
        valid = gzip.decompress(self.raw_gzip())
        invalid = [b'', valid[:11], valid[:-1], valid + b'x', valid + b'x' * 5,
                   struct.pack('<III', 0, 9, 1), struct.pack('<III', 5000, 9, 1),
                   struct.pack('<III', 4096, 4096, 1),
                   struct.pack('<III', 16, 9, 4) + valid[12:]]
        for value in invalid:
            with self.subTest(length=len(value)), self.assertRaises(preview.PreviewError) as error:
                preview.jpeg_frame(gzip.compress(value))
            self.assertEqual(error.exception.code, 'preview_invalid_image')

    def test_gzip_truncation_corruption_trailing_and_concatenated_members_are_rejected(self):
        encoded = self.raw_gzip()
        invalid = [encoded[:10], encoded[:-1], encoded[:-8], encoded + b'x',
                   encoded + self.raw_gzip(), encoded[:-8] + b'\x00' * 8]
        for value in invalid:
            with self.subTest(length=len(value)), self.assertRaises(preview.PreviewError) as error:
                preview.jpeg_frame(value)
            self.assertEqual(error.exception.code, 'preview_invalid_image')

    def test_gzip_output_ceiling_rejects_bombs_before_image_allocation(self):
        # A small limit exercises the production bounded-decompress branch without
        # allocating a 40 MB fixture in every CI run.
        encoded = gzip.compress(b'x' * 4096)
        with patch.object(preview, 'MAX_RAW_BYTES', 128), patch.object(preview.Image, 'frombytes') as image:
            with self.assertRaises(preview.PreviewError) as error:
                preview.jpeg_frame(encoded)
        self.assertEqual(error.exception.code, 'preview_invalid_image')
        image.assert_not_called()

    def test_invalid_format_truncated_data_and_dimensions_are_rejected(self):
        raw = self.png()
        invalid = [b'<svg/>', raw[:24], raw[:16] + struct.pack('>II', 100_000, 100_000) + raw[24:],
                   raw[:16] + struct.pack('>II', 4096, 4096) + raw[24:]]
        for value in invalid:
            with self.subTest(size=len(value)), self.assertRaises(preview.PreviewError) as error:
                preview.jpeg_frame(value)
            self.assertEqual(error.exception.code, 'preview_invalid_image')

    def test_global_cooldown_and_timestamp_do_not_retain_a_frame(self):
        now = [10.0]
        captures = []
        sampler = preview.ScreenPreview(lambda: captures.append(True) or self.png(), clock=lambda: now[0], wall_clock=lambda: 0)
        frame, captured, elapsed = sampler.frame()
        self.assertTrue(frame.startswith(b'\xff\xd8'))
        self.assertEqual(captured, '1970-01-01T00:00:00+00:00')
        self.assertEqual(elapsed, 0)
        now[0] = 11
        with self.assertRaises(preview.PreviewError): sampler.frame()
        now[0] = 12
        sampler.frame()
        self.assertEqual(len(captures), 2)
        self.assertFalse(any(isinstance(value, (bytes, bytearray)) for value in vars(sampler).values()))

    def test_second_capture_is_rejected_without_waiting_or_invoking_source(self):
        entered, release = threading.Event(), threading.Event()
        def capture():
            entered.set()
            release.wait(2)
            return self.png()
        sampler = preview.ScreenPreview(capture)
        thread = threading.Thread(target=sampler.frame)
        thread.start()
        self.assertTrue(entered.wait(1))
        try:
            with self.assertRaises(preview.PreviewError) as error: sampler.frame()
            self.assertEqual(error.exception.code, 'preview_busy')
            self.assertEqual(error.exception.status, 429)
        finally:
            release.set()
            thread.join(2)
        self.assertFalse(thread.is_alive())

    def test_slow_transfer_does_not_overstate_frame_freshness(self):
        now = [0]
        def capture():
            now[0] = 10
            return self.png()
        sampler = preview.ScreenPreview(capture, clock=lambda: now[0], wall_clock=lambda: now[0])
        _, captured, duration = sampler.frame()
        self.assertEqual(captured, '1970-01-01T00:00:00+00:00')
        self.assertEqual(duration, 10000)
