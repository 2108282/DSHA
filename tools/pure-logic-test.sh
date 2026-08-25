#!/bin/bash
# 跑那几个「无 Android 依赖」的纯逻辑类的断言集：
#   LanAuth   —— 局域网桥/3090 桥的凭据判定与请求行改写
#   AssetPath —— 增量更新清单里 asset 名当路径用之前的校验
#
# 为什么单独一个脚本而不是接进 gradle：这些类刻意不碰 Android API，用 javac 编几个
# 文件就能跑完，秒级、离线、不占 SDK 与 gradle 缓存。手机上的工作区跑一次完整 gradle
# 要几分钟且常把会话拖死，改一行字符串处理不该付那个代价。
#
# 用法：bash tools/pure-logic-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_DIR="$REPO_ROOT/app/src/main/java/com/deepseekharness/app"
SRCS=("$JAVA_DIR/LanAuth.java" "$JAVA_DIR/AssetPath.java" "$JAVA_DIR/BackupInspector.java" "$JAVA_DIR/PluginErrorHint.java")
TEST="$REPO_ROOT/tools/pure-logic-test/PureLogicTest.java"

for f in "${SRCS[@]}" "$TEST"; do
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

javac -encoding UTF-8 -nowarn -d "$OUT" "${SRCS[@]}" "$TEST"
java -cp "$OUT" com.deepseekharness.app.PureLogicTest
