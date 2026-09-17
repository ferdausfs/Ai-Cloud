#!/usr/bin/env bash
# Staged local verification for the 4GB no-swap container.
# Stage 1: all-module unit tests + lintDebug (higher heap)
# Stage 2: assembleDebug (sanity on packaging)
# Stage 3: assembleRelease (lowest heap — mergeDexRelease is the OOM hotspot)
# Usage: scripts/local-verify.sh [test|debug|release|all]   (default: all)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
STAGE="${1:-all}"

export JAVA_HOME=/home/z/jdk17
export PATH="$JAVA_HOME/bin:/home/z/tools/gradle-8.11.1/bin:$PATH"
export ANDROID_HOME=/home/z/android-sdk
export ANDROID_SDK_ROOT=/home/z/android-sdk
GRADLE=/home/z/tools/gradle-8.11.1/bin/gradle
# Prefer local gradle; wrapper only as fallback (network flaky in container).
GRADLE_CMD="$GRADLE"
if [ ! -x "$GRADLE" ]; then GRADLE_CMD="./gradlew"; fi

run_stage_tests() {
  echo "=== STAGE 1: unit tests + lintDebug ==="
  GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1500m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC \
-Dorg.gradle.workers.max=1 -Dkotlin.compiler.execution.strategy=in-process" \
    "$GRADLE_CMD" --no-daemon test lintDebug
}

run_stage_debug() {
  echo "=== STAGE 2: assembleDebug ==="
  GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=460m -XX:+UseSerialGC \
-Dorg.gradle.workers.max=1 -Dkotlin.compiler.execution.strategy=in-process" \
    "$GRADLE_CMD" --no-daemon assembleDebug
}

run_stage_release() {
  echo "=== STAGE 3: assembleRelease ==="
  GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1150m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC \
-Dorg.gradle.workers.max=1 -Dkotlin.compiler.execution.strategy=in-process" \
    "$GRADLE_CMD" --no-daemon assembleRelease
}

case "$STAGE" in
  test) run_stage_tests ;;
  debug) run_stage_debug ;;
  release) run_stage_release ;;
  all) run_stage_tests && run_stage_debug && run_stage_release ;;
  *) echo "unknown stage: $STAGE"; exit 1 ;;
esac
