#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"

is_mounted() {
    local target="${1%/}"
    grep -q " $target " /proc/mounts 2>/dev/null || mountpoint -q "$target" 2>/dev/null
}

clean_umount() {
    local target="$1"
    while is_mounted "$target"; do
        umount -l "$target" 2>/dev/null || break
    done
}

# 1. 优先按 PID 精准终止主进程与属于该容器的直接子进程（毫秒级完成，杜绝遍历 /proc 的巨大卡死开销）
if [ -f "$PID_FILE" ]; then
    MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$MAIN_PID" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
        # 仅杀该主进程派生的子进程（绝不误伤其他容器或宿主进程）
        pkill -9 -P "$MAIN_PID" 2>/dev/null || true
        kill -15 "$MAIN_PID" 2>/dev/null
        for i in 1 2 3; do
            kill -0 "$MAIN_PID" 2>/dev/null || break
            sleep 0.1 2>/dev/null || sleep 1
        done
        kill -9 "$MAIN_PID" 2>/dev/null || true
    fi
    rm -f "$PID_FILE" 2>/dev/null || true
fi
rm -f "$RUN_DIR/port" 2>/dev/null || true

# 2. 仅在显式传入 --umount 时卸载内核挂载点（普通停止服务绝不卸载挂载，保证其他操作与环境稳定）
if [ "$1" = "--umount" ]; then
    clean_umount "$ROOTFS/storage/emulated/0"
    clean_umount "$ROOTFS/sdcard"
    clean_umount "$ROOTFS/dev/block"
    clean_umount "$ROOTFS/dev/shm"
    clean_umount "$ROOTFS/dev/pts"
    clean_umount "$ROOTFS/dev"
    clean_umount "$ROOTFS/proc"
    clean_umount "$ROOTFS/sys"
fi

echo "STATUS:STOPPED"
exit 0
