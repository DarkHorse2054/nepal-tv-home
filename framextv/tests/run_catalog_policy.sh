#!/bin/sh
set -eu

theater_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
theater_sources="$theater_dir/app/src/main/java/app/nepal/framextv"
theater_checks=$(mktemp -d "${TMPDIR:-/tmp}/theater-catalog-policy.XXXXXX")
trap 'rm -rf "$theater_checks"' EXIT HUP INT TERM

if [ -n "${JAVA_HOME:-}" ]; then
  theater_javac="$JAVA_HOME/bin/javac"
  theater_java="$JAVA_HOME/bin/java"
else
  theater_javac=javac
  theater_java=java
fi

# Android's compile-only stubs supply JSONObject/JSONArray signatures. The checks
# execute only the shipped pure policy; no stub method is called at runtime.
theater_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$theater_sdk" ] || [ ! -f "$theater_sdk/platforms/android-34/android.jar" ]; then
  echo 'Set ANDROID_HOME to an SDK containing platforms/android-34/android.jar.' >&2
  exit 2
fi
theater_android="$theater_sdk/platforms/android-34/android.jar"
"$theater_javac" -encoding UTF-8 -cp "$theater_android" -d "$theater_checks" \
  "$theater_sources/CatalogClient.java" "$theater_sources/CatalogPolicy.java" \
  "$theater_dir/tests/CatalogPolicyChecks.java"
"$theater_java" -cp "$theater_checks:$theater_android" app.nepal.framextv.CatalogPolicyChecks
