#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"
LOG_FILE="$RUN_DIR/dsh-web.log"

PORT="${1:-3080}"

mkdir -p "$RUN_DIR"
chmod 777 "$RUN_DIR" 2>/dev/null || true

# 1. 检查是否已经在运行
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "STATUS:ALREADY_RUNNING PID:$OLD_PID"
        grep -o 'http://127\.0\.0\.1:[0-9]*/?token=[^ ]*' "$LOG_FILE" 2>/dev/null | tail -n 1
        exit 0
    fi
    rm -f "$PID_FILE"
fi

# 2. 解除 Android 12+ 幽灵进程限制
/system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null

# 3. 挂载原生虚拟文件系统
mount_if_needed() {
    target="$1"
    shift
    if ! mountpoint -q "$target"; then
        mkdir -p "$target" 2>/dev/null
        mount "$@" "$target"
    fi
}

mount_if_needed "$ROOTFS/dev" -o bind /dev
mount_if_needed "$ROOTFS/dev/pts" -t devpts devpts
# 挂载 POSIX 共享内存 tmpfs（提升 Python/Node 多进程性能）
mkdir -p "$ROOTFS/dev/shm"
mountpoint -q "$ROOTFS/dev/shm" || mount -t tmpfs tmpfs "$ROOTFS/dev/shm" -o mode=1777 2>/dev/null || true
# 屏蔽物理块设备：防止 Agent 误写底层物理闪存 /dev/block 分区导致手机变砖
mkdir -p "$ROOTFS/dev/block"
mountpoint -q "$ROOTFS/dev/block" || mount -t tmpfs tmpfs "$ROOTFS/dev/block" -o mode=000 2>/dev/null || true
mount_if_needed "$ROOTFS/proc" -t proc proc
mount_if_needed "$ROOTFS/sys" -t sysfs sysfs

# 挂载存储卡
if [ -d "/storage/emulated/0" ]; then
    mount_if_needed "$ROOTFS/sdcard" -o bind /storage/emulated/0
    mount_if_needed "$ROOTFS/storage/emulated/0" -o bind /storage/emulated/0
elif [ -d "/sdcard" ]; then
    mount_if_needed "$ROOTFS/sdcard" -o bind /sdcard
fi

# 4. 修复 DNS 配置
mkdir -p "$ROOTFS/etc"
cat << 'DNS_EOF' > "$ROOTFS/etc/resolv.conf"
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
DNS_EOF

# 5. 部署守护包装器与安全策略
DSH_BIN="$ROOTFS/root/dsh-bin"
mkdir -p "$DSH_BIN"
mkdir -p "$ROOTFS/root/.dsh"
chmod 777 "$ROOTFS/root/.dsh" 2>/dev/null || true
if [ -f "$ROOTFS/root/.dsh/.bridge_token" ]; then
    chmod 666 "$ROOTFS/root/.dsh/.bridge_token" 2>/dev/null || true
fi

# 写入确认交互脚本 (与 3090 交互请求用户确认)
cat << 'CONFIRM_EOF' > "$ROOTFS/root/dsh-confirm.sh"
#!/bin/bash
FORCE=0
if [ "$1" = "--force" ]; then FORCE=1; shift; fi
CMD="$*"
TOKEN=$(cat /root/.dsh/.bridge_token 2>/dev/null)
RES=$(curl -s -m 65 -G "http://127.0.0.1:3090/confirm" --data-urlencode "cmd=$CMD" --data-urlencode "force=$FORCE" -H "X-Token: $TOKEN" 2>/dev/null)
case "$RES" in
  *'"result":"YES"'*|*'"result":YES'*) exit 0 ;;
  *'"result":"NO"'*|*'"result":NO'*)  echo "已拒绝: $CMD（用户在手机端拒绝了该操作）" >&2; exit 1 ;;
  *)
    if [ -n "$DSH_INTERACTIVE" ]; then
      echo -n "确认执行危险操作 [$CMD] ? [y/N] " >&2
      read -t 10 ans
      case "$ans" in y|Y) exit 0 ;; esac
    fi
    echo "已拦截高危操作: $CMD (3090确认服务未就绪或超时)" >&2
    exit 1
    ;;
esac
CONFIRM_EOF
chmod 755 "$ROOTFS/root/dsh-confirm.sh"

# 写入函数级命令守卫
cat << 'GUARD_EOF' > "$ROOTFS/root/dsh-guard.sh"
# DSHA 危险命令守卫
if [ "${DSH_CONFIRM:-0}" = "1" ] || [ "${DSH_SHELL:-0}" = "1" ]; then
  rm()       { /root/dsh-confirm.sh "rm $*" && /usr/bin/rm "$@"; }
  rmdir()    { /root/dsh-confirm.sh "rmdir $*" && /usr/bin/rmdir "$@"; }
  unlink()   { /root/dsh-confirm.sh "unlink $*" && /usr/bin/unlink "$@"; }
  truncate() { /root/dsh-confirm.sh "truncate $*" && /usr/bin/truncate "$@"; }
  dd()       { /root/dsh-confirm.sh "dd $*" && /usr/bin/dd "$@"; }
  mkfs()     { /root/dsh-confirm.sh "mkfs $*" && /usr/sbin/mkfs "$@"; }
  mkfs.ext4(){ /root/dsh-confirm.sh "mkfs.ext4 $*" && /usr/sbin/mkfs.ext4 "$@"; }
  mkfs.vfat(){ /root/dsh-confirm.sh "mkfs.vfat $*" && /usr/sbin/mkfs.vfat "$@"; }
  fdisk()    { /root/dsh-confirm.sh "fdisk $*" && /usr/sbin/fdisk "$@"; }
  reboot()   { /root/dsh-confirm.sh "reboot $*" && /usr/sbin/reboot "$@"; }
  shutdown() { /root/dsh-confirm.sh "shutdown $*" && /usr/sbin/shutdown "$@"; }
  halt()     { /root/dsh-confirm.sh "halt $*" && /usr/sbin/halt "$@"; }
  poweroff() { /root/dsh-confirm.sh "poweroff $*" && /usr/sbin/poweroff "$@"; }
  wipe()     { /root/dsh-confirm.sh "wipe $*" && /usr/sbin/wipe "$@"; }
fi
GUARD_EOF
chmod 755 "$ROOTFS/root/dsh-guard.sh"

# 注入 bashrc 自动加载
if ! grep -q "dsh-guard.sh" "$ROOTFS/root/.bashrc" 2>/dev/null; then
    echo '[ -f /root/dsh-guard.sh ] && source /root/dsh-guard.sh' >> "$ROOTFS/root/.bashrc"
fi

# PATH 级命令守卫包装
for C in rm rmdir unlink truncate dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe; do
cat << 'WRAPPER_EOF' > "$DSH_BIN/$C"
#!/bin/bash
SELF=$(basename "$0")
REAL=""
for p in /usr/local/bin /usr/bin /bin /usr/sbin /sbin; do
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
[ -z "$REAL" ] && REAL=$(ls /usr/local/bin/$SELF /usr/bin/$SELF /bin/$SELF 2>/dev/null | head -1)
if [ -z "$REAL" ]; then echo "找不到真实命令: $SELF" >&2; exit 127; fi
if [ "${DSH_CONFIRM:-0}" != "1" ] && [ "${DSH_SHELL:-0}" != "1" ]; then
  exec "$REAL" "$@"
fi
if /root/dsh-confirm.sh "$SELF $*"; then
  exec "$REAL" "$@"
fi
echo "已拒绝: $SELF $*" >&2
exit 1
WRAPPER_EOF
chmod 755 "$DSH_BIN/$C"
done

# 清空旧日志
> "$LOG_FILE"
mkdir -p "$ROOTFS/root"
ln -sf "$LOG_FILE" "$ROOTFS/root/dsh-web.log" 2>/dev/null || true

# 6. 原生拉起 Node.js DSH Web 服务
chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root \
    USER=root \
    LOGNAME=root \
    PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=xterm-256color \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    DSH_CONFIRM=1 \
    /usr/local/bin/node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port "$PORT" --host 127.0.0.1 > "$LOG_FILE" 2>&1 &

NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
echo -1000 > "/proc/$NEW_PID/oom_score_adj" 2>/dev/null || true

# 7. 等待服务启动并提取鉴权 Token 链接
AUTH_URL=""
for i in 1 2 3 4 5 6; do
    sleep 1
    AUTH_URL=$(grep -o 'http://127\.0\.0\.1:[0-9]*/?token=[^ ]*' "$LOG_FILE" 2>/dev/null | tail -n 1)
    if [ -n "$AUTH_URL" ]; then
        break
    fi
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        echo "STATUS:FAILED"
        cat "$LOG_FILE"
        exit 1
    fi
done

echo "STATUS:STARTED PID:$NEW_PID PORT:$PORT"
if [ -n "$AUTH_URL" ]; then
    echo "=========================================================="
    echo "进入 Web 鉴权链接 (直接在手机浏览器打开):"
    echo "$AUTH_URL"
    echo "=========================================================="
else
    echo "提示: 未能在 6 秒内抓取到 Token 链接，请查看日志: cat $LOG_FILE"
fi
