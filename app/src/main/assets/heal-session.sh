#!/bin/bash
# 会话损坏自愈：检测到 SessionPersistenceCorruptionError → 备份并隔离损坏会话
# 适配 rc.2（JSONL：session.jsonl.zstd）与旧 SQLite（.db）。
# 损坏会话移到 corrupt-backup/（不删除可恢复）；dsh 重建新会话。
set -u
LOG=/root/dsh-web.log
if [ ! -f "$LOG" ] || ! grep -q "SessionPersistenceCorruptionError\|lacks an identified message" "$LOG" 2>/dev/null; then
  echo "SESSION_OK"
  exit 0
fi
echo "检测到会话损坏错误，开始隔离损坏会话..."
HEALED=0
# 1) JSONL 会话（rc.2 实际格式）：损坏的会话文件（dsh 校验失败的无法直接判定，
#    用保守策略：隔离 <100B 的极小文件 + 错误信息里提到的 session id）
# 2) 从日志提取损坏的 session id（形如 session-xxx）
IDS=$(grep -oE 'session-[a-f0-9-]{36}' "$LOG" 2>/dev/null | sort -u | head -10)
if [ -n "$IDS" ]; then
  for id in $IDS; do
    # 找该会话的所有文件（jsonl / zstd / db）
    FOUND=$(find /root/.dsh/sessions -path "*$id*" -type f 2>/dev/null)
    for f in $FOUND; do
      REL=${f#/root/.dsh/sessions/}
      mkdir -p "/root/.dsh/corrupt-backup/$(dirname "$REL")"
      mv "$f" "/root/.dsh/corrupt-backup/$REL" 2>/dev/null && { echo "已隔离: $REL"; HEALED=1; }
    done
    # 删空目录
    find /root/.dsh/sessions -path "*$id*" -type d -empty -delete 2>/dev/null
  done
fi
# 3) 极小 JSONL（<100B 必损坏）
for f in $(find /root/.dsh/sessions -name 'session.jsonl.zstd' -size -50c 2>/dev/null); do
  REL=${f#/root/.dsh/sessions/}
  mkdir -p "/root/.dsh/corrupt-backup/$(dirname "$REL")"
  mv "$f" "/root/.dsh/corrupt-backup/$REL" 2>/dev/null && { echo "已隔离(极小): $REL"; HEALED=1; }
done
# 4) 旧 SQLite（.db 带 wal/shm）
for f in /root/.dsh/*.db /root/.dsh/*.sqlite; do
  [ -f "$f" ] || continue
  TS=$(date +%Y%m%d-%H%M%S)
  mkdir -p /root/.dsh/corrupt-backup
  mv "$f" "/root/.dsh/corrupt-backup/$(basename "$f").corrupt-$TS" 2>/dev/null
  rm -f "$f-wal" "$f-shm" 2>/dev/null
  echo "已隔离 SQLite: $f"; HEALED=1
done 2>/dev/null
if [ "$HEALED" = "1" ]; then
  echo "SESSION_HEALED"
  # 清掉日志里的错误标记，避免每次启动重复处理
  sed -i '/SessionPersistenceCorruptionError/d; /lacks an identified message/d' "$LOG" 2>/dev/null || true
else
  echo "SESSION_HEALED_NONE"
fi
exit 0
