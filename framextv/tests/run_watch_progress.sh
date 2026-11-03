#!/bin/sh
set -eu

theater_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
theater_sources="$theater_dir/app/src/main/java/app/nepal/framextv"
theater_checks=$(mktemp -d "${TMPDIR:-/tmp}/theater-watch-progress.XXXXXX")
trap 'rm -rf "$theater_checks"' EXIT HUP INT TERM

if [ -n "${JAVA_HOME:-}" ]; then
  theater_javac="$JAVA_HOME/bin/javac"
  theater_java="$JAVA_HOME/bin/java"
else
  theater_javac=javac
  theater_java=java
fi

"$theater_javac" -encoding UTF-8 -d "$theater_checks" \
  "$theater_sources/EmbedSpec.java" "$theater_sources/WatchProgress.java" \
  "$theater_dir/tests/WatchProgressChecks.java"
"$theater_java" -cp "$theater_checks" app.nepal.framextv.WatchProgressChecks
