#!/bin/sh
set -eu
REMOTE_DIRECTORY=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
REMOTE_PYTHON=${NEPAL_REMOTE_PYTHON:-python3}
exec "$REMOTE_PYTHON" -B "$REMOTE_DIRECTORY/server.py" "$@"
