#!/usr/bin/env python3
"""Manually install the fixed, verified public scrcpy server used by Live video."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("nepal_live_tools", HERE / "live_video.py")
LIVE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LIVE)
URL = "https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Download and verify the pinned official public server")
    args = parser.parse_args()
    if not args.download:
        parser.error("Use --download explicitly. The remote server never downloads tools automatically.")
    directory = HERE / "state/tools"
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    if directory.is_symlink() or not directory.resolve().is_relative_to(HERE):
        parser.error("Tool directory must stay inside this remote's private state directory")
    os.chmod(directory, 0o700)
    destination = directory / "scrcpy-server-v5.0.1"
    temporary = None
    try:
        request = Request(URL, headers={"User-Agent": "NepalRemote-Setup/1.0"})
        with urlopen(request, timeout=15) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
        if len(raw) > 4 * 1024 * 1024 or hashlib.sha256(raw).hexdigest() != LIVE.SERVER_SHA256:
            raise ValueError("Downloaded server did not match the pinned SHA-256; nothing was installed")
        with tempfile.NamedTemporaryFile(dir=directory, prefix=".server-", delete=False) as output:
            temporary = Path(output.name)
            os.chmod(temporary, 0o600)
            output.write(raw)
        os.replace(temporary, destination)
        temporary = None
        print("Verified scrcpy 5.0.1 server installed in remote/state/tools/.")
        print("Live video also needs FFmpeg on PATH, --ffmpeg, or the optional imageio-ffmpeg Python package.")
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + "\n")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
