#!/bin/bash
# 会话损坏自愈（无门槛版）：不再依赖 dsh-web.log 里是否出现错误文案——
# 该错误经 API 响应直接回给前端，常驻进程日志里经常没有，旧版因此从未触发。
# 现在每次调用都全量扫描 JSONL 会话文件，用 fix-session.py 幂等修复：
#   缺 message.id → 补 id（保留对话）；解码失败/极小文件 → 备份隔离；
#   NO_FIX（无缺id问题）→ 保留不动（不误隔离）。
# 适配 JSONL（session.jsonl.zstd）与旧 SQLite（.db）。损坏文件移到 corrupt-backup/（不删除可恢复）。
set -u
HEALED=0
SCANNED=0
LOG=/root/.dsh/heal.log

log() { echo "$1" | tee -a "$LOG"; }

# 修复函数：对单个 zstd 会话文件尝试修复；幂等。
repair_one() {
  local f="$1"
  if [ ! -f "$f" ]; then return; fi
  SCANNED=$((SCANNED + 1))
  # 极小文件必损坏，直接隔离
  local sz
  sz=$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f" 2>/dev/null || wc -c < "$f" 2>/dev/null)
  if [ -n "$sz" ] && [ "$sz" -lt 50 ] 2>/dev/null; then
    local rel="${f#/root/.dsh/sessions/}"
    mkdir -p "/root/.dsh/corrupt-backup/$(dirname "$rel")"
    mv "$f" "/root/.dsh/corrupt-backup/$rel" 2>/dev/null && { log "已隔离(极小 <50B): $rel"; HEALED=1; }
    return
  fi
  if ! python3 -c "import zstandard" 2>/dev/null; then
    # 优先本地 wheel（APK 已注入 /root/.dsh/zstandard-0.25.0-*.whl，离线可用）；
    # pip 缺失/失败时兜底：wheel 就是 zip，直接解压进 site-packages（零依赖）。
    local ZWHL
    ZWHL=$(ls /root/.dsh/zstandard-0.25.0-*.whl 2>/dev/null | head -1)
    if [ -n "$ZWHL" ]; then
      python3 -m pip install --break-system-packages --no-index \
        "$ZWHL" >/tmp/zstd-pip.log 2>&1 || true
      python3 -c "import zstandard" 2>/dev/null || {
        local SITE
        SITE=$(python3 -c "import site; print(site.getsitepackages()[0])" 2>/dev/null)
        [ -z "$SITE" ] && SITE="/usr/local/lib/python3.12/dist-packages"
        mkdir -p "$SITE"
        # 兜底解压 wheel：优先系统 unzip，没有则用 python 自带 zipfile（零依赖）
        if command -v unzip >/dev/null 2>&1; then
          unzip -qo "$ZWHL" -d "$SITE" 2>/dev/null || true
        else
          python3 -m zipfile -e "$ZWHL" "$SITE" 2>/dev/null || \
            python3 -c "import zipfile,shutil; zipfile.ZipFile('$ZWHL').extractall('$SITE')" 2>/dev/null || true
        fi
      }
    fi
    python3 -c "import zstandard" 2>/dev/null || \
      python3 -m pip install --break-system-packages -q zstandard 2>/dev/null \
      || python3 -m pip install -q zstandard 2>/dev/null || true
  fi
  local FIXED
  FIXED=$(python3 /root/.dsh/fix-session.py "$f" 2>&1)
  case "$FIXED" in
    *FIXED:*)
      log "已修复会话($FIXED): $f"
      HEALED=1
      ;;
    *DECODE_FAIL*|*NO_ZSTD*|*NEED_ISOLATE*)
      # 解码失败 / 无 zstd / 存在无法安全修复的结构损坏（如 assistant 缺 model
      # source、tool 缺 callId/合法结果块）：备份隔离（dsh 也无法读），保留可恢复
      local rel="${f#/root/.dsh/sessions/}"
      mkdir -p "/root/.dsh/corrupt-backup/$(dirname "$rel")"
      mv "$f" "/root/.dsh/corrupt-backup/$rel" 2>/dev/null && { log "已隔离(无法修复: ${FIXED%%:*}) : $rel"; HEALED=1; }
      ;;
    *)
      # NO_FIX / NO_FILE / NO_ARGS：无缺 id 问题或其他原因，保留（不误隔离）
      echo "会话无需修复，保留: $f"
      ;;
  esac
}

# 1) JSONL 全量扫描（不依赖日志错误文案；fix 幂等，正常文件秒过 NO_FIX）
mapfile -t FILES < <(find /root/.dsh/sessions -name 'session.jsonl.zstd' -type f 2>/dev/null)
for f in "${FILES[@]:-}"; do
  repair_one "$f"
done

# 2) 旧 SQLite（.db 带 wal/shm）——历史遗留格式兜底
for f in /root/.dsh/*.db /root/.dsh/*.sqlite; do
  [ -f "$f" ] || continue
  TS=$(date +%Y%m%d-%H%M%S)
  mkdir -p /root/.dsh/corrupt-backup
  mv "$f" "/root/.dsh/corrupt-backup/$(basename "$f").corrupt-$TS" 2>/dev/null
  rm -f "$f-wal" "$f-shm" 2>/dev/null
  echo "已隔离 SQLite: $f"; HEALED=1
done 2>/dev/null

if [ "$HEALED" = "1" ]; then
  echo "SESSION_HEALED (scanned=$SCANNED)"
elif [ "$SCANNED" = "0" ]; then
  echo "SESSION_OK"
else
  echo "SESSION_HEALED_NONE"
fi
exit 0