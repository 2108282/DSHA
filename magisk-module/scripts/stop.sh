#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"
PORT_FILE="$RUN_DIR/port"

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

# 1. 优先按记录的 PID 终止
if [ -f "$PID_FILE" ]; then
    MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$MAIN_PID" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
        kill -15 "$MAIN_PID" 2>/dev/null
        for i in 1 2 3; do
            kill -0 "$MAIN_PID" 2>/dev/null || break
            sleep 0.1 2>/dev/null || sleep 1
        done
        kill -9 "$MAIN_PID" 2>/dev/null || true
    fi
    rm -f "$PID_FILE" 2>/dev/null || true
fi
rm -f "$PORT_FILE" 2>/dev/null || true

# 2. 毫秒级精准按命令特征清理残留 Node / DSH 进程（避免循环遍历 /proc 的巨大开销）
pkill -f 'node /usr/local/lib/node_modules/@deepseek-ai/dsh' 2>/dev/null || true
pkill -f 'dsh web' 2>/dev/null || true
pkill -f 'bin.js web' 2>/dev/null || true

# 3. 只有传入 --umount 或 --all 时才卸载内核挂载点（卸载/重新安装模块时使用）
if [ "$1" = "--umount" ] || [ "$1" = "--all" ]; then
    pkill -9 -f "$ROOTFS" 2>/dev/null || true
    sleep 0.2 2>/dev/null || sleep 1

    clean_umount "$ROOTFS/storage/emulated/0"
    clean_umount "$ROOTFS/sdcard"
    clean_umount "$ROOTFS/dev/block"
    clean_umount "$ROOTFS/dev/shm"
    clean_umount "$ROOTFS/dev/pts"
    clean_umount "$ROOTFS/dev"
    clean_umount "$ROOTFS/proc"
    clean_umount "$ROOTFS/sys"
fi

# 动态同步 KernelSU / Magisk 模块描述状态为已停止
for p_mod in "/data/adb/modules/dsha_native/module.prop" \
             "/data/adb/modules_update/dsha_native/module.prop"; do
    if [ -f "$p_mod" ]; then
        sed -i 's|^description=.*|description=[🔴 已停止] DSHA 原生 Linux chroot 极速运行时，按需启停，0 虚拟化损耗，0 待机偷跑。|' "$p_mod" 2>/dev/null || true
    fi
done

echo "STATUS:STOPPED"
exit 0
