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

# 确保必要的挂载点存在（精准判重，防止多次打开终端层叠挂载）
mount_if_needed "$ROOTFS/dev" -o bind /dev
mount_if_needed "$ROOTFS/dev/pts" -t devpts devpts
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

# 直接以原生 root 身份进入 bash，默认落位到 /root/内部存储/工作区
if [ $# -eq 0 ]; then
    exec chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root \
        USER=root \
        LOGNAME=root \
        PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        TERM="${TERM:-xterm-256color}" \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        /bin/bash -c 'cd /root/内部存储/工作区 2>/dev/null || cd /root; exec /bin/bash -l'
else
    exec chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root \
        USER=root \
        LOGNAME=root \
        PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        TERM="${TERM:-xterm-256color}" \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        /bin/bash "$@"
fi
