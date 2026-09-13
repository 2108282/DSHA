#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"

# 确保必要的挂载点存在
mountpoint -q "$ROOTFS/dev" || mount -o bind /dev "$ROOTFS/dev"
mountpoint -q "$ROOTFS/dev/pts" || mount -t devpts devpts "$ROOTFS/dev/pts"
mkdir -p "$ROOTFS/dev/shm"
mountpoint -q "$ROOTFS/dev/shm" || mount -t tmpfs tmpfs "$ROOTFS/dev/shm" -o mode=1777 2>/dev/null || true
mkdir -p "$ROOTFS/dev/block"
mountpoint -q "$ROOTFS/dev/block" || mount -t tmpfs tmpfs "$ROOTFS/dev/block" -o mode=000 2>/dev/null || true
mountpoint -q "$ROOTFS/proc" || mount -t proc proc "$ROOTFS/proc"
mountpoint -q "$ROOTFS/sys" || mount -t sysfs sysfs "$ROOTFS/sys"
mountpoint -q "$ROOTFS/sdcard" || mount -o bind /storage/emulated/0 "$ROOTFS/sdcard" 2>/dev/null || mount -o bind /sdcard "$ROOTFS/sdcard" 2>/dev/null || true

# 直接以原生 root 身份进入 bash
exec chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root \
    USER=root \
    LOGNAME=root \
    PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=xterm-256color \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    /bin/bash "$@"
