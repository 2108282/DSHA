#!/bin/bash
# 安装 rootfs 危险命令确认包装器（DSHA 安全）
# 包装器：DSH_CONFIRM=1 时危险命令先确认（App 弹窗 / 终端交互），否则直接放行（安装流程不受影响）
set -e
DSH_BIN=/root/dsh-bin
mkdir -p "$DSH_BIN"

cat > /root/dsh-confirm.sh <<'EOF'
#!/bin/bash
CMD="$*"
RES=$(curl -s -m 6 -G "http://127.0.0.1:3090/confirm" --data-urlencode "cmd=$CMD" 2>/dev/null)
if [ $? -eq 0 ] && [ -n "$RES" ]; then
  echo "$RES" | grep -q YES && exit 0 || exit 1
fi
# 3090 不可达（终端场景未启动服务）：终端内交互确认，10 秒超时默认拒绝
if [ -n "$DSH_INTERACTIVE" ]; then
  echo -n "确认执行 [$CMD] ? [y/N] " >&2
  read -t 10 ans
  case "$ans" in
    y|Y) exit 0 ;;
  esac
fi
echo "已拒绝: $CMD" >&2
exit 1
EOF
chmod +x /root/dsh-confirm.sh

# BASH_ENV 函数级守卫：agent 每次 bash -c 执行前自动加载（非交互 bash 必读 BASH_ENV）
# 函数优先于 PATH 查找，PATH 被覆盖/哈希缓存都无法绕过命令名拦截
cat > /root/dsh-guard.sh <<'EOF'
# DSHA 危险命令守卫（由 BASH_ENV 注入，勿手动删除）
if [ "${DSH_CONFIRM:-0}" = "1" ]; then
  rm()      { /root/dsh-confirm.sh "rm $*"      && /usr/bin/rm "$@"; }
  rmdir()   { /root/dsh-confirm.sh "rmdir $*"    && /usr/bin/rmdir "$@"; }
  unlink()  { /root/dsh-confirm.sh "unlink $*"   && /usr/bin/unlink "$@"; }
  truncate(){ /root/dsh-confirm.sh "truncate $*" && /usr/bin/truncate "$@"; }
  # find 带 -delete 或 -exec rm 时确认（正常 find 不受影响）
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
  # adb shell 通道：设备侧命令含危险操作时确认（command adb 跳过函数查找）
  adb()     { local FOUND=0 CMDSTR=""; for a in "$@"; do if [ "$FOUND" = "1" ]; then CMDSTR="$CMDSTR $a"; fi; [ "$a" = "shell" ] && FOUND=1; done; case "$CMDSTR" in *"rm "*|*"rm -"*|*"dd if="*|*"mkfs"*|*"fdisk"*|*"format"*|*"wipe"*|*"reboot"*|*"shutdown"*|*"uninstall"*|*"pm clear"*) /root/dsh-confirm.sh "adb shell:$CMDSTR" || return 1;; esac; command adb "$@"; }
fi
EOF
chmod +x /root/dsh-guard.sh

# adb 特殊包装：agent 用 adb shell "rm ..." 在设备上删除——检测 shell 后的命令串
cat > "$DSH_BIN/adb" <<'EOF2'
#!/bin/bash
SELF=$(basename "$0")
REAL=""
for p in /usr/local/bin /usr/bin /bin /usr/sbin /sbin /system/bin /data/data/com.termux/files/usr/bin; do
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
[ -z "$REAL" ] && REAL=$(ls /usr/local/bin/adb /usr/bin/adb /system/bin/adb 2>/dev/null | head -1)
if [ -z "$REAL" ]; then echo "找不到真实 adb" >&2; exit 127; fi
if [ "${DSH_CONFIRM:-0}" = "1" ]; then
  FOUND=0; CMDSTR=""
  for a in "$@"; do
    if [ "$FOUND" = "1" ]; then CMDSTR="$CMDSTR $a"; fi
    if [ "$a" = "shell" ]; then FOUND=1; fi
  done
  case "$CMDSTR" in
    *"rm "*|*"rm -"*|*"dd if="*|*"mkfs"*|*"fdisk"*|*"format"*|*"wipe"*|*"reboot"*|*"shutdown"*|*"uninstall"*|*"pm clear"*)
      /root/dsh-confirm.sh "adb shell:$CMDSTR" || exit 1 ;;
  esac
fi
exec "$REAL" "$@"
EOF2
chmod +x "$DSH_BIN/adb"

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
if [ "\${DSH_CONFIRM:-0}" != "1" ]; then
  exec "\$REAL" "\$@"   # 未启用确认（安装流程等）直接放行
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
