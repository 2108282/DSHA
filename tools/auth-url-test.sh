#!/bin/bash
# Dependency-free strict BrowserAuth URL regression test.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_DIR="$REPO_ROOT/app/src/main/java/com/deepseekharness/app"
TEST_DIR="$REPO_ROOT/tools/auth-url-test"

for f in "$JAVA_DIR/DshAuthUrl.java" "$TEST_DIR/AuthUrlTest.java"; do
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
javac -encoding UTF-8 -nowarn -d "$OUT" \
  "$JAVA_DIR/DshAuthUrl.java" "$TEST_DIR/AuthUrlTest.java"
java -cp "$OUT" com.deepseekharness.app.AuthUrlTest
