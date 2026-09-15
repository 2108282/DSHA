#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"

is_mounted() {
    local target="${1%/}"
    grep -q " $target " /proc/mounts 2>/dev/null || mountpoint -q "$target" 2>/dev/null
}

mount_if_needed() {
    local target="$1"
    shift
    if ! is_mounted "$target"; then
        mkdir -p "$target" 2>/dev/null
        mount "$@" "$target"
    fi
}

# 确保必要的挂载点存在
mount_if_needed "$ROOTFS/dev" -o bind /dev

# 确保 $ROOTFS/dev/pts 与宿主 /dev/pts 共享一致的 bind 挂载
if grep -q " $ROOTFS/dev/pts devpts " /proc/mounts 2>/dev/null; then
    umount -l "$ROOTFS/dev/pts" 2>/dev/null || true
fi
mount_if_needed "$ROOTFS/dev/pts" -o bind /dev/pts

mkdir -p "$ROOTFS/dev/shm"
mount_if_needed "$ROOTFS/dev/shm" -t tmpfs tmpfs -o mode=1777
mkdir -p "$ROOTFS/dev/block"
mount_if_needed "$ROOTFS/dev/block" -t tmpfs tmpfs -o ro,mode=000
mount_if_needed "$ROOTFS/proc" -t proc proc
mount_if_needed "$ROOTFS/sys" -t sysfs sysfs
if [ -d "/storage/emulated/0" ]; then
    mount_if_needed "$ROOTFS/sdcard" -o bind /storage/emulated/0
    mount_if_needed "$ROOTFS/storage/emulated/0" -o bind /storage/emulated/0
elif [ -d "/sdcard" ]; then
    mount_if_needed "$ROOTFS/sdcard" -o bind /sdcard
fi

# 确保手机 Download/DSHA/工作区 存在，并在容器 root 下建立「内部存储」软链接直通
mkdir -p "$ROOTFS/sdcard/Download/DSHA/工作区" 2>/dev/null || true
mkdir -p "$ROOTFS/root" 2>/dev/null || true
rm -f "$ROOTFS/root/内部存储" 2>/dev/null || true
ln -sf /sdcard/Download/DSHA "$ROOTFS/root/内部存储" 2>/dev/null || true

# 恢复标准终端设置
stty sane 2>/dev/null || true

# 智能终端调度：
# 1. 只有在标准输入为字符终端 ([ -t 0 ]) 时才调用 setsid -c；
# 2. 在管道/简易终端 (如 ProcessBuilder / Pipe) 中直接以 bash 运行，绝不执行 setsid 避免 ioctl 退出！
if [ -t 0 ] && [ -x "$ROOTFS/usr/bin/setsid" ]; then
    if [ $# -eq 0 ]; then
        exec chroot "$ROOTFS" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root \
            PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            /usr/bin/setsid -c /bin/bash -c "cd /root && exec /bin/bash +m -l"
    else
        exec chroot "$ROOTFS" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root \
            PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            /usr/bin/setsid -c /bin/bash "$@"
    fi
else
    # 简易终端或非 tty 环境：直接进入交互/执行模式，完全规避 setsid 的 ioctl 致命报错
    if [ $# -eq 0 ]; then
        exec chroot "$ROOTFS" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root \
            PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            /bin/bash +m -l
    else
        exec chroot "$ROOTFS" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root \
            PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            /bin/bash "$@"
    fi
fi
