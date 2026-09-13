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

# 1. 终止主进程（先 SIGTERM 释放文件锁，后 SIGKILL 确保终止）
if [ -f "$PID_FILE" ]; then
    MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$MAIN_PID" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
        kill -15 "$MAIN_PID" 2>/dev/null
        for i in 1 2 3; do
            kill -0 "$MAIN_PID" 2>/dev/null || break
            usleep 300000 2>/dev/null || sleep 1
        done
        kill -9 "$MAIN_PID" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
fi

# 2. 终止 chroot 内的所有残留子进程（root 或 cwd 位于 ROOTFS 下的所有进程）
for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
    root_link=$(readlink "/proc/$pid/root" 2>/dev/null)
    cwd_link=$(readlink "/proc/$pid/cwd" 2>/dev/null)
    case "$root_link" in
        "$ROOTFS"*) kill -9 "$pid" 2>/dev/null || true ;;
        *)
            case "$cwd_link" in
                "$ROOTFS"*) kill -9 "$pid" 2>/dev/null || true ;;
            esac
            ;;
    esac
done

# 3. 等待子进程与文件描述符平稳释放（防僵尸进程阻塞 umount）
for i in 1 2 3 4; do
    has_proc=0
    for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
        root_link=$(readlink "/proc/$pid/root" 2>/dev/null)
        if [ "$root_link" = "$ROOTFS" ]; then
            has_proc=1
            break
        fi
    done
    [ "$has_proc" = "0" ] && break
    usleep 300000 2>/dev/null || sleep 1
done

# 4. 彻底卸载所有内核挂载点（多层循环卸载，杜绝层叠泄漏，实现 0 开销）
clean_umount "$ROOTFS/storage/emulated/0"
clean_umount "$ROOTFS/sdcard"
clean_umount "$ROOTFS/dev/block"
clean_umount "$ROOTFS/dev/shm"
clean_umount "$ROOTFS/dev/pts"
clean_umount "$ROOTFS/dev"
clean_umount "$ROOTFS/proc"
clean_umount "$ROOTFS/sys"

echo "STATUS:STOPPED"
exit 0
