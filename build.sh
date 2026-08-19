#!/usr/bin/env bash
# deepseekharness 一键构建脚本：构建 debug APK，并复制为带版本号的命名产物。
# 用法：./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# 可通过环境变量覆盖（默认适配本工作区：/workspace 下的 gradle 与 android-sdk）
GRADLE_BIN="${GRADLE_BIN:-/workspace/gradle/bin/gradle}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/android-sdk}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"

if [ ! -x "$GRADLE_BIN" ]; then
    echo "找不到 gradle：$GRADLE_BIN（可用 GRADLE_BIN 环境变量指定）" >&2
    exit 1
fi

VERSION_NAME=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)
VERSION_NAME=${VERSION_NAME:-0.0}
echo "==> 版本: ${VERSION_NAME}"

"$GRADLE_BIN" :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "构建失败：未找到 $APK" >&2
    exit 1
fi

OUT="deepseekharness-arm64-v${VERSION_NAME}.apk"
cp "$APK" "$OUT"
echo "==> 原始产物: $ROOT/$APK"
echo "==> 版本命名: $ROOT/$OUT"
