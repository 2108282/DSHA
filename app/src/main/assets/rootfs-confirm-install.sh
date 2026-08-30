#!/bin/bash
# 安装 rootfs 危险命令确认包装器（DSHA 安全）
# 包装器：DSH_CONFIRM=1 时危险命令先确认（App 弹窗 / 终端交互），否则直接放行（安装流程不受影响）
set -e
DSH_BIN=/root/dsh-bin
mkdir -p "$DSH_BIN"

cat > /root/dsh-confirm.sh <<'EOF2'
#!/bin/bash
# 用法：dsh-confirm.sh [--force] <命令...>
#   --force：所有命令都弹确认（设备 shell 报备用；守卫开时）
#   不带：仅危险命令确认（守卫内部行为由 3090 桥 confirmEnabled 决定）

# ===== 临时授权租期检查（用户前置同意后，租期内免二次弹窗确认）=====
if [ -f /root/.dsh/.auth_lease ]; then
  EXP=$(cat /root/.dsh/.auth_lease 2>/dev/null)
  NOW=$(date +%s)
  if [ -n "$EXP" ] && [ "${NOW:-0}" -lt "${EXP%.*}" ]; then
    exit 0
  fi
fi

# ===== 安全临时文件清理（删除 Download 临时截图/租约文件免弹窗）=====
is_safe_cleanup() {
  local c="$1"
  [[ "$c" =~ ^(rm|unlink)[[:space:]] ]] || return 1
  [[ "$c" =~ -r|-R|\* ]] && return 1
  for arg in $c; do
    [[ "$arg" =~ ^(rm|unlink|-f|-v)$ ]] && continue
    if [[ "$arg" =~ ^/sdcard/Download/[a-zA-Z0-9_\.-]+\.(png|jpg|jpeg|tmp)$ ]] || \
       [[ "$arg" =~ ^/tmp/[a-zA-Z0-9_\.-]+$ ]] || \
       [[ "$arg" == "/root/.dsh/.auth_lease" ]]; then
      continue
    else
      return 1
    fi
  done
  return 0
}

FORCE=0
if [ "$1" = "--force" ]; then FORCE=1; shift; fi
CMD="$*"

if [ "$FORCE" != "1" ] && is_safe_cleanup "$CMD"; then
  exit 0
fi

# 3090 桥有 token 鉴权（防其他 App 冒充 agent 弹确认框）：
# 必须带 X-Token 头（= /root/.dsh/.bridge_token 内容），否则一律 [UNAUTHORIZED] 被拒
TOKEN=$(cat /root/.dsh/.bridge_token 2>/dev/null)
# 桥的监听地址随 App 版本不同（新版绑 127.0.0.1 并附加 [::1]，旧版只绑 [::1]）：
# 两个地址族都试，避免「桥活着但连不上 → 确认弹窗永不出现」。
RES=""
for H in 127.0.0.1 '[::1]'; do
  RES=$(curl -s -m 65 -G "http://$H:3090/confirm" --data-urlencode "cmd=$CMD" --data-urlencode "force=$FORCE" -H "X-Token: $TOKEN" 2>/dev/null)
  # 严格匹配 {"result":"YES"}：宽松的 grep YES 会被响应里的其它字段或命令回显
  # 带偏（吸收上游 PR#24）
  case "$RES" in
    *'"result":"YES"'*) exit 0 ;;
    *'"result":"NO"'*)  echo "已拒绝: $CMD（用户点了拒绝）" >&2; exit 1 ;;
    *'"result":YES'*)   exit 0 ;;  # 兼容老版 App 的非法 JSON 响应
  esac
  [ -n "$RES" ] && break
done
if [ -n "$RES" ]; then
  # 有响应但既不是 YES 也不是 NO：桥说了别的（如 Shizuku 未就绪），一律拒绝
  echo "已拒绝: $CMD" >&2
  echo "  桥返回：$RES" >&2
  exit 1
fi
# 3090 不可达（终端场景未启动服务）：终端内交互确认，10 秒超时默认拒绝
if [ -n "$DSH_INTERACTIVE" ]; then
  echo -n "确认执行 [$CMD] ? [y/N] " >&2
  read -t 10 ans
  case "$ans" in
    y|Y) exit 0 ;;
  esac
fi
# 静默拒绝最难排查 —— 把原因写清楚（吸收上游 PR#24）
echo "已拒绝: $CMD" >&2
if [ -z "$TOKEN" ]; then
  echo "  原因：读不到 /root/.dsh/.bridge_token（App 未生成鉴权令牌）" >&2
else
  echo "  原因：3090 确认桥不可达（127.0.0.1 与 [::1] 都连不上）" >&2
fi
echo "  处理：在 App「配置」页启用 ADB 设备通道，确认桥运行后重试" >&2
exit 1
EOF2
chmod +x /root/dsh-confirm.sh

# 函数级守卫：由 bash 工具 lib 补丁（ensureBashGuardPatch）在每条命令前 source 加载。
cat > /root/dsh-guard.sh <<'EOF2'
# DSHA 危险命令守卫（由 BASH_ENV 注入，勿手动删除）
if [ "${DSH_CONFIRM:-0}" = "1" ] || [ "${DSH_SHELL:-0}" = "1" ]; then
  rm()      { /root/dsh-confirm.sh "rm $*"      && /usr/bin/rm "$@"; }
  rmdir()   { /root/dsh-confirm.sh "rmdir $*"    && /usr/bin/rmdir "$@"; }
  unlink()  { /root/dsh-confirm.sh "unlink $*"   && /usr/bin/unlink "$@"; }
  truncate(){ /root/dsh-confirm.sh "truncate $*" && /usr/bin/truncate "$@"; }
  find()    { for a in "$@"; do if [ "$a" = "-delete" ] || [ "$a" = "-exec" ]; then /root/dsh-confirm.sh "find $*" || return 1; break; fi; done; /usr/bin/find "$@"; }
  dd()      { /root/dsh-confirm.sh "dd $*"      && /usr/bin/dd "$@"; }
  mkfs()    { /root/dsh-confirm.sh "mkfs $*"    && /usr/sbin/mkfs "$@"; }
  mkfs.ext4(){ /root/dsh-confirm.sh "mkfs.ext4 $*" && /usr/sbin/mkfs.ext4 "$@"; }
  mkfs.vfat(){ /root/dsh-confirm.sh "mkfs.vfat $*" && /usr/sbin/mkfs.vfat "$@"; }
  fdisk()   { /root/dsh-confirm.sh "fdisk $*"   && /usr/sbin/fdisk "$@"; }
  reboot()  { /root/dsh-confirm.sh "reboot $*"  && /usr/sbin/reboot "$@"; }
  shutdown(){ /root/dsh-confirm.sh "shutdown $*" && /usr/sbin/shutdown "$@"; }
  halt()    { /root/dsh-confirm.sh "halt $*"    && /usr/sbin/halt "$@"; }
  poweroff(){ /root/dsh-confirm.sh "poweroff $*" && /usr/sbin/poweroff "$@"; }
  wipe()    { /root/dsh-confirm.sh "wipe $*"    && /usr/sbin/wipe "$@"; }
  is_danger_cmd() {
    echo "$1" | grep -qE '(^|[^a-z])(rm|rmdir|unlink|truncate|wipe)([^a-z]|$)|(dd|mkfs|fdisk|format|reboot|shutdown|poweroff|halt)([^a-z]|$)|-delete|base64|\| ?(sh|bash)|eval|sh -c|toybox|pm clear|uninstall'
  }
  adb()     { local FOUND=0 CMDSTR=""; for a in "$@"; do if [ "$FOUND" = "1" ]; then CMDSTR="$CMDSTR $a"; fi; [ "$a" = "shell" ] || [ "$a" = "exec-out" ] || [ "$a" = "exec-in" ] && FOUND=1; done; if [ -n "$CMDSTR" ] && is_danger_cmd "$CMDSTR"; then /root/dsh-confirm.sh "adb shell:$CMDSTR" || return 1; fi; /root/.dsh-real/adb "$@" 2>/dev/null || command adb "$@"; }
fi
EOF2
chmod +x /root/dsh-guard.sh

cat > "$DSH_BIN/adb" <<'EOF2'
#!/bin/bash
SELF=$(basename "$0")
REAL=""
for p in /root/.dsh-real /usr/local/bin /usr/bin /bin /system/bin /data/data/com.termux/files/usr/bin; do
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
[ -z "$REAL" ] && REAL=$(ls /root/.dsh-real/adb /usr/local/bin/adb /usr/bin/adb /system/bin/adb 2>/dev/null | head -1)
if [ -z "$REAL" ]; then
  REAL=$(PATH=/usr/local/bin:/usr/bin:/bin:/system/bin command -v "$SELF" 2>/dev/null | grep -v "^$DSH_BIN" | head -1)
fi
if [ -z "$REAL" ]; then
  echo "找不到真实 $SELF：rootfs 里没装它（App 的 ADB 功能走 Android 侧，不依赖这个）。" >&2
  echo "想看系统日志可以直接用 /system/bin/logcat" >&2
  exit 127
fi
is_danger_cmd() {
  echo "$1" | grep -qE '(^|[^a-z])(rm|rmdir|unlink|truncate|wipe)([^a-z]|$)|(dd|mkfs|fdisk|format|reboot|shutdown|poweroff|halt)([^a-z]|$)|-delete|base64|\| ?(sh|bash)|eval|sh -c|toybox|pm clear|uninstall'
}
if [ "${DSH_CONFIRM:-0}" = "1" ] || [ "${DSH_SHELL:-0}" = "1" ]; then
  FOUND=0; CMDSTR=""
  for a in "$@"; do
    if [ "$FOUND" = "1" ]; then CMDSTR="$CMDSTR $a"; fi
    if [ "$a" = "shell" ] || [ "$a" = "exec-out" ] || [ "$a" = "exec-in" ]; then FOUND=1; fi
  done
  if [ -n "$CMDSTR" ] && is_danger_cmd "$CMDSTR"; then
    /root/dsh-confirm.sh "adb shell:$CMDSTR" || exit 1
  fi
fi
exec "$REAL" "$@"
EOF2
chmod +x "$DSH_BIN/adb"

REAL_ADB=$(command -v adb 2>/dev/null | grep -v dsh-bin | head -1)
if [ -n "$REAL_ADB" ] && [ ! -e /root/.dsh-real/adb ]; then
  mkdir -p /root/.dsh-real
  mv "$REAL_ADB" /root/.dsh-real/adb 2>/dev/null || true
fi

for C in rm rmdir unlink truncate dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe find; do
cat > "$DSH_BIN/$C" <<EOF2
#!/bin/bash
SELF=\$(basename "\$0")
REAL=""
for p in /usr/local/bin /usr/bin /bin /usr/sbin /sbin; do
  if [ -x "\$p/\$SELF" ] && [ "\$p/\$SELF" != "\$0" ]; then REAL="\$p/\$SELF"; break; fi
done
[ -z "\$REAL" ] && REAL=\$(ls /usr/local/bin/\$SELF /usr/bin/\$SELF /bin/\$SELF 2>/dev/null | head -1)
if [ -z "\$REAL" ]; then echo "找不到真实命令: \$SELF" >&2; exit 127; fi
if [ "\${DSH_CONFIRM:-0}" != "1" ] && [ "\${DSH_SHELL:-0}" != "1" ]; then
  exec "\$REAL" "\$@"
fi
if /root/dsh-confirm.sh "\$SELF \$*"; then
  exec "\$REAL" "\$@"
fi
echo "已拒绝: \$SELF \$*" >&2
exit 1
EOF2
chmod +x "$DSH_BIN/$C"
done

echo "OK dsh-bin: $(ls "$DSH_BIN" | tr '\n' ' ')"
echo 12 > "$DSH_BIN/.version"
