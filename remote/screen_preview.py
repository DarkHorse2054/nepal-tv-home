"""One requested screen frame, kept in memory. No recorder or background worker."""
from datetime import datetime, timezone
import io
import struct
import subprocess
import threading
import time
import zlib

try:
    from PIL import Image
except ImportError:
    Image = None

MAX_PNG_BYTES = 32 * 1024 * 1024
MAX_RAW_BYTES = 40_000_016
MAX_JPEG_BYTES = 1024 * 1024
CAPTURE_TIMEOUT = 20
# Raw pixels compress faster than Android's PNG encoder on the tested older stick.
# The whole command is fixed; callers cannot supply a shell fragment or file path.
CAPTURE_COMMAND = ('if command -v gzip >/dev/null 2>&1; then screencap | gzip -1; '
                   'else screencap -p; fi')


class PreviewError(Exception):
    def __init__(self, code, status=503):
        self.code, self.status = code, status


def capture_png(adb, serial, env, *, popen=subprocess.Popen, timeout=CAPTURE_TIMEOUT,
                max_bytes=MAX_PNG_BYTES):
    """Read bounded gzip/raw or fallback PNG bytes; retain the historical API name."""
    process = None
    reader = None
    data = bytearray()
    errors = []
    try:
        process = popen([str(adb), '-s', serial, 'exec-out', 'sh', '-c', CAPTURE_COMMAND],
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                        env=env, shell=False)

        def read_output():
            try:
                while True:
                    chunk = process.stdout.read(65536)
                    if not chunk:
                        break
                    if len(data) + len(chunk) > max_bytes:
                        errors.append('preview_invalid_image')
                        data.clear()
                        process.kill()
                        break
                    data.extend(chunk)
            except OSError:
                errors.append('preview_unavailable')

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise PreviewError('preview_timeout') from None
        reader.join(timeout=1)
        if reader.is_alive():
            raise PreviewError('preview_timeout')
        if errors:
            raise PreviewError(errors[0])
        if process.returncode != 0:
            raise PreviewError('preview_unavailable')
        return bytes(data)
    except OSError:
        raise PreviewError('preview_unavailable') from None
    finally:
        if process is not None:
            if process.poll() is None:
                process.kill()
            process.wait()
            if reader is not None:
                reader.join(timeout=1)
            if process.stdout is not None and (reader is None or not reader.is_alive()):
                process.stdout.close()


def _valid_dimensions(width, height):
    return 0 < width <= 4096 and 0 < height <= 4096 and width * height <= 10_000_000


def _raw_image(raw):
    """Decode one complete gzip member and one exact Android screencap buffer."""
    try:
        decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
        decoded = decoder.decompress(raw, MAX_RAW_BYTES + 1)
    except zlib.error:
        raise PreviewError('preview_invalid_image') from None
    if (len(decoded) > MAX_RAW_BYTES or not decoder.eof
            or decoder.unconsumed_tail or decoder.unused_data or len(decoded) < 12):
        raise PreviewError('preview_invalid_image')
    width, height, pixel_format = struct.unpack('<III', decoded[:12])
    if not _valid_dimensions(width, height) or pixel_format not in (1, 2, 3):
        raise PreviewError('preview_invalid_image')
    pixel_size = 3 if pixel_format == 3 else 4
    pixel_bytes = width * height * pixel_size
    if len(decoded) == 12 + pixel_bytes:
        header_size = 12
    elif len(decoded) == 16 + pixel_bytes:
        header_size = 16  # Newer screencap versions add a uint32 dataspace.
    else:
        raise PreviewError('preview_invalid_image')
    return Image.frombytes('RGB', (width, height), decoded[header_size:],
                           'raw', 'RGB' if pixel_format == 3 else 'RGBX')


def jpeg_frame(raw):
    if Image is None:
        raise PreviewError('preview_dependency_missing')
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_PNG_BYTES:
        raise PreviewError('preview_invalid_image')
    try:
        if raw[:2] == b'\x1f\x8b':
            source = _raw_image(raw)
        else:
            if (len(raw) < 24 or raw[:8] != b'\x89PNG\r\n\x1a\n'
                    or raw[12:16] != b'IHDR'):
                raise PreviewError('preview_invalid_image')
            width, height = struct.unpack('>II', raw[16:24])
            if not _valid_dimensions(width, height):
                raise PreviewError('preview_invalid_image')
            source = Image.open(io.BytesIO(raw), formats=['PNG'])
            if source.size != (width, height) or getattr(source, 'n_frames', 1) != 1:
                source.close()
                raise PreviewError('preview_invalid_image')
        with source:
            source.thumbnail((1280, 720), Image.Resampling.LANCZOS)
            with source.convert('RGB') as frame:
                output = io.BytesIO()
                frame.save(output, format='JPEG', quality=78)
                result = output.getvalue()
        if not result or len(result) > MAX_JPEG_BYTES:
            raise PreviewError('preview_invalid_image')
        return result
    except (OSError, ValueError, SyntaxError, Image.DecompressionBombError):
        raise PreviewError('preview_invalid_image') from None


class ScreenPreview:
    def __init__(self, capture, *, clock=time.monotonic, wall_clock=time.time):
        self.capture, self.clock, self.wall_clock = capture, clock, wall_clock
        self.lock = threading.Lock()
        self.next_capture = float('-inf')

    def frame(self):
        if not self.lock.acquire(blocking=False):
            raise PreviewError('preview_busy', 429)
        attempted = False
        try:
            if self.clock() < self.next_capture:
                raise PreviewError('preview_rate_limited', 429)
            attempted = True
            if Image is None:
                raise PreviewError('preview_dependency_missing')
            started = self.clock()
            captured = datetime.fromtimestamp(self.wall_clock(), timezone.utc).isoformat(timespec='seconds')
            raw = self.capture()
            frame = jpeg_frame(raw)
            return frame, captured, round((self.clock() - started) * 1000)
        finally:
            if attempted:
                self.next_capture = self.clock() + 2
            self.lock.release()
