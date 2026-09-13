#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"

# 1. 终止主进程
if [ -f "$PID_FILE" ]; then
    MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$MAIN_PID" ]; then
        kill -15 "$MAIN_PID" 2>/dev/null
        sleep 1
        kill -9 "$MAIN_PID" 2>/dev/null
    fi
    rm -f "$PID_FILE"
fi

# 2. 终止 chroot 内的所有残留子进程（node, git, python, bash 等）
for pid in $(ls /proc | grep -E '^[0-9]+$'); do
    root_link=$(readlink /proc/$pid/root 2>/dev/null)
    if [ "$root_link" = "$ROOTFS" ]; then
        kill -9 "$pid" 2>/dev/null
    fi
done

# 3. 干净卸载所有内核挂载点（释放文件锁与内存，实现 0 开销）
umount -l "$ROOTFS/storage/emulated/0" 2>/dev/null || true
umount -l "$ROOTFS/sdcard" 2>/dev/null || true
umount -l "$ROOTFS/dev/block" 2>/dev/null || true
umount -l "$ROOTFS/dev/shm" 2>/dev/null || true
umount -l "$ROOTFS/dev/pts" 2>/dev/null || true
umount -l "$ROOTFS/dev" 2>/dev/null || true
umount -l "$ROOTFS/proc" 2>/dev/null || true
umount -l "$ROOTFS/sys" 2>/dev/null || true

echo "STATUS:STOPPED"
exit 0
