#!/usr/bin/env bash
# DSHA 标准版：JDK 17 + Gradle Wrapper 9.3.1 + SDK 37.0 + Python 3.9+。
# 用法：./build.sh :app:testStandardDebugUnitTest / :app:assembleLowRelease
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

TOOLS="F:/DSHA/_toolchains"

export JAVA_HOME="${JAVA_HOME:-${TOOLS}/jdk-17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${TOOLS}/gradle-user-home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${TOOLS}/android-sdk}"
export ANDROID_HOME="${ANDROID_HOME:-${TOOLS}/android-sdk}"

GRADLE_BIN="${GRADLE_BIN:-${ROOT}/gradlew}"
export DSHA_PYTHON="${DSHA_PYTHON:-python3}"

echo "==> JAVA_HOME=${JAVA_HOME}"
echo "==> gradle : $(command -v java >/dev/null && "$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

if [ "$#" -eq 0 ]; then set -- :app:assembleStandardDebug; fi
exec "$GRADLE_BIN" "$@"
