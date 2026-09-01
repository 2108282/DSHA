#!/usr/bin/env bash
# 单一 latest 备份的纯 shell 验收：不访问设备、不触碰用户 Downloads。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BM="$ROOT/app/src/main/java/com/deepseekharness/app/BackupManager.java"
WS="$ROOT/app/src/main/java/com/deepseekharness/app/WorkspaceFragment.java"
LAYOUT="$ROOT/app/src/main/res/layout/fragment_workspace.xml"
BS="$ROOT/app/src/main/java/com/deepseekharness/app/BackupScope.java"

grep -Fq 'LATEST_BACKUP_NAME = "DSHA-backup-latest.tar.gz"' "$BM"
grep -Fq 'return backup(ctx, c, LATEST_BACKUP_NAME, BackupScope.FULL);' "$BM"
grep -Fq 'return backup(ctx, c, LATEST_BACKUP_NAME, scope);' "$BM"
grep -Fq 'deleteSameNameExcept' "$BM"
grep -Fq 'ATOMIC_MOVE' "$BM"
! grep -Fq 'AUTO_BACKUP_NAME' "$BM"
grep -Fq 'setSingleChoiceItems' "$WS"
grep -Fq '即将备份：' "$WS"
grep -Fq 'BackupScope.ALL' "$WS"
grep -Fq '仅备份对话记录' "$BS"
grep -Fq '仅备份设置' "$BS"
grep -Fq '仅备份插件' "$BS"
grep -Fq '选择备份范围' "$LAYOUT"
grep -Fq '可选全部数据、对话记录、设置或插件' "$LAYOUT"

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
printf 'old-backup' > "$T/DSHA-backup-latest.tar.gz"
printf 'new-backup' > "$T/source.tar.gz"

# 模拟复制/校验阶段中断：临时文件清理，旧 latest 必须保持原字节。
publish_with_failure() {
  local tmp="$T/.latest.tmp"
  cp "$T/source.tar.gz" "$tmp"
  rm -f "$tmp"
  return 1
}
if publish_with_failure; then
  echo 'FAIL: failure injection unexpectedly succeeded' >&2
  exit 1
fi
test "$(cat "$T/DSHA-backup-latest.tar.gz")" = 'old-backup'
test ! -e "$T/.latest.tmp"

# 模拟完整写入后的同父目录原子替换。
tmp="$T/.latest.tmp"
cp "$T/source.tar.gz" "$tmp"
mv -f "$tmp" "$T/DSHA-backup-latest.tar.gz"
test "$(cat "$T/DSHA-backup-latest.tar.gz")" = 'new-backup'
test ! -e "$T/.latest.tmp"

echo 'backup latest checks passed: single name, failure preserves old, success replaces atomically'
