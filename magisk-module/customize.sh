SKIPUNZIP=1
ui_print "*****************************************"
ui_print "      DSHA Native Chroot 极速运行时      "
ui_print "      适配: Android 12+ (KernelSU/Magisk) "
ui_print "*****************************************"

DATA_DIR="/data/adb/dsha"
ROOTFS_DIR="$DATA_DIR/rootfs"
SCRIPTS_DIR="$DATA_DIR/scripts"

# 1. 解压模块控制脚本
ui_print "- 正在安装控制脚本..."
mkdir -p "$MODPATH/scripts"
mkdir -p "$SCRIPTS_DIR"
mkdir -p "$DATA_DIR/run"

unzip -o "$ZIPFILE" 'scripts/*' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2

cp -rf "$MODPATH/scripts/"* "$SCRIPTS_DIR/"
chmod 755 "$MODPATH/service.sh"
chmod 755 "$MODPATH/scripts/"*.sh
chmod 755 "$SCRIPTS_DIR/"*.sh

# 2. 处理 RootFS 底包解压
mkdir -p "$ROOTFS_DIR"

if [ -f "$ROOTFS_DIR/usr/local/bin/node" ]; then
    ui_print "- 检测到已存在现成的 DSH 环境，保留当前用户数据与配置。"
    ui_print "- 如需全新重新部署，请手动清空 $ROOTFS_DIR 后重刷。"
else
    # 检查 zip 中是否存在 rootfs.tar.gz
    if unzip -l "$ZIPFILE" | grep -q "rootfs.tar.gz"; then
        ui_print "- 正在解压 DSH 原生运行时底包至 $ROOTFS_DIR ..."
        ui_print "- 此过程需要约 1~2 分钟，请勿息屏或退出..."
        unzip -p "$ZIPFILE" rootfs.tar.gz | tar -xz -C "$ROOTFS_DIR"
        ui_print "- 底包解压完毕！"
    elif [ -f "/sdcard/Download/DSHA/rootfs.tar.gz" ]; then
        ui_print "- 发现本地 /sdcard/Download/DSHA/rootfs.tar.gz，正在解压..."
        tar -xzf "/sdcard/Download/DSHA/rootfs.tar.gz" -C "$ROOTFS_DIR"
        ui_print "- 本地底包解压完毕！"
    else
        ui_print "⚠️ 未在刷机包内发现 rootfs.tar.gz，也未在 Download/DSHA 找到底包。"
        ui_print "⚠️ 请在安装后将 rootfs.tar.gz 放入 Download/DSHA 或解压至 $ROOTFS_DIR。"
    fi
fi

# 确保 rootfs 基础目录权限正确
chmod 755 "$ROOTFS_DIR/bin" 2>/dev/null || true
chmod 755 "$ROOTFS_DIR/usr/bin" 2>/dev/null || true
chmod 777 "$ROOTFS_DIR/tmp" 2>/dev/null || true
mkdir -p "$ROOTFS_DIR/root/.dsh"
chmod 777 "$ROOTFS_DIR/root/.dsh" 2>/dev/null || true

ui_print "-----------------------------------------"
ui_print "安装成功！本模块开机不自启，0 功耗占用。"
ui_print "通过 DSHA App 即可一键拉起或停止。"
ui_print "终端快速进入命令: su -c /data/adb/dsha/scripts/term.sh"
ui_print "*****************************************"
