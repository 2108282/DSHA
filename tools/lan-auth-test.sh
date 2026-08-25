#!/bin/bash
# 跑 LanAuth（局域网桥的凭据判定 / 请求行改写）的断言集。
#
# 为什么单独一个脚本而不是接进 gradle：这部分逻辑刻意不依赖 Android API，用 javac
# 直接编译两个文件就能跑完，秒级、离线、不占 SDK 与 gradle 缓存。手机上的工作区
# 跑一次完整 gradle 要几分钟且常把会话拖死，改一行字符串处理不该付那个代价。
#
# 用法：bash tools/lan-auth-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO_ROOT/app/src/main/java/com/deepseekharness/app/LanAuth.java"
TEST="$REPO_ROOT/tools/lan-auth-test/LanAuthTest.java"

for f in "$SRC" "$TEST"; do
  if [ ! -f "$f" ]; then
    echo "找不到 $f" >&2
    exit 1
  fi
done

if ! command -v javac >/dev/null 2>&1; then
  echo "没有 javac（需要 JDK 17+）" >&2
  exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -nowarn -d "$OUT" "$SRC" "$TEST"
java -cp "$OUT" com.deepseekharness.app.LanAuthTest
