#!/usr/bin/env bash
# Release smoke test — guards against startup crashes in the SIGNED release
# APK (the exact failure class shipped in v2.5.0: R8 minification broke a
# reflection path and the app died on launch, undetected by unit/connected
# tests which only ever ran the debug build).
#
# Runs inside the connected-tests job's emulator, after
# connectedDebugAndroidTest. Installs the signed release APK produced by the
# build job, launches the launcher activity, and asserts the process stays
# alive and the crash buffer stays clean.
#
# On pull_request builds there is no signed artifact — the test skips itself.
set -euo pipefail

PKG="dev.repochat"
ACTIVITY="dev.repochat/.MainActivity"
ALIVE_SECONDS=20
LOGCAT_DUMP="release-smoke-logcat.txt"

apk=""
for f in release-apk/*.apk; do
  if [[ -e "$f" ]]; then apk="$f"; break; fi
done

if [[ -z "$apk" ]]; then
  echo "::warning::No signed release APK found (pull_request build) — release smoke test skipped."
  exit 0
fi

echo "Release smoke test on: $apk"

# The debug build (different signature) may be installed by connected tests —
# replace it unconditionally or the release install fails with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE.
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$apk"

adb logcat -c
launch_output="$(adb shell am start -W -n "$ACTIVITY")"
echo "$launch_output"
if ! echo "$launch_output" | grep -q "Status: ok"; then
  echo "::error::Activity failed to start — see output above."
  adb logcat -d > "$LOGCAT_DUMP" || true
  exit 1
fi

polls=$((ALIVE_SECONDS / 2))
for i in $(seq 1 "$polls"); do
  sleep 2
  pid="$(adb shell pidof "$PKG" | tr -d '\r[:space:]')"
  if [[ -z "$pid" ]]; then
    echo "::error::Release process died $((i * 2))s after launch — startup crash reproduced in CI."
    adb logcat -d > "$LOGCAT_DUMP" || true
    grep -A 60 "FATAL EXCEPTION" "$LOGCAT_DUMP" | head -100 || true
    exit 1
  fi
  echo "t=$((i * 2))s: pid=$pid alive"
done

crash_lines="$(adb logcat -d -b crash | grep -c "$PKG" || true)"
if [[ "$crash_lines" != "0" ]]; then
  echo "::error::Crash buffer contains $crash_lines lines mentioning $PKG."
  adb logcat -d -b crash > "$LOGCAT_DUMP" || true
  adb logcat -d -b crash | head -100 || true
  exit 1
fi

# Persist the (empty) crash buffer for post-mortem artifact completeness.
adb logcat -d -b crash > "$LOGCAT_DUMP" || true
echo "Release smoke test PASSED — app stayed alive ${ALIVE_SECONDS}s with a clean crash buffer."
