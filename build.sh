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

# 增量更新清单 + 签名：改过 assets 里任何脚本都必须重新生成，否则客户端比对到的是旧
# sha256 —— 要么以为没更新，要么下载后校验不过。放在构建前自动做，不靠人记。
# 没有 keystore 的环境（外部贡献者）会自动跳过签名，构建照常。
if command -v python3 >/dev/null 2>&1; then
  python3 tools/gen-runtime-manifest.py || echo "（清单生成失败，继续构建）"
  bash tools/sign-runtime-manifest.sh || echo "（清单签名失败，继续构建）"
fi

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
