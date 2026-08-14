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

for C in rm dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe; do
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
