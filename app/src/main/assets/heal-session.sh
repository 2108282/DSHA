#!/bin/bash
# 会话损坏自愈：检测到 SessionPersistenceCorruptionError → 备份并移除损坏的 SQLite
set -u
LOG=/root/dsh-web.log
[ -f "$LOG" ] || { echo "SESSION_OK"; exit 0; }
if ! grep -q "SessionPersistenceCorruptionError" "$LOG" 2>/dev/null; then
  echo "SESSION_OK"; exit 0
fi
FOUND=""
for f in /root/.dsh/*.db /root/.dsh/*.sqlite /root/.dsh/sessions/*.db; do
  [ -f "$f" ] && FOUND="$FOUND $f"
done
if [ -z "$FOUND" ]; then
  echo "SESSION_DB_NOT_FOUND"; exit 0
fi
TS=$(date +%Y%m%d-%H%M%S)
for f in $FOUND; do
  mv "$f" "$f.corrupt-$TS" 2>/dev/null
  rm -f "$f-wal" "$f-shm" 2>/dev/null
  echo "已备份损坏会话库: $f → $f.corrupt-$TS"
done
echo "SESSION_HEALED"
