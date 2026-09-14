SKIPUNZIP=1
ui_print "*****************************************"
ui_print "      DSHA Native Chroot 极速运行时      "
ui_print "      适配: Android 12+ (KernelSU/Magisk) "
ui_print "*****************************************"

DATA_DIR="/data/adb/dsha"
ROOTFS_DIR="$DATA_DIR/rootfs"
SCRIPTS_DIR="$DATA_DIR/scripts"

# 0. 热升级安全防护：若检测到旧实例正在运行，先平稳停止以保证原子覆盖
if [ -f "$SCRIPTS_DIR/stop.sh" ] && [ -f "$DATA_DIR/run/dsh.pid" ]; then
    ui_print "- 检测到 DSHA 正在运行，正在平稳停止旧进程以保证安全更新..."
    sh "$SCRIPTS_DIR/stop.sh" --umount >/dev/null 2>&1 || true
fi

# 1. 解压模块控制脚本与 WebUI 控制面板
ui_print "- 正在安装控制脚本与操作界面..."
mkdir -p "$MODPATH/scripts"
mkdir -p "$MODPATH/webroot"
mkdir -p "$SCRIPTS_DIR"
mkdir -p "$DATA_DIR/run"

unzip -o "$ZIPFILE" 'scripts/*' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'action.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'webroot/*' -d "$MODPATH" >&2

# 清空旧脚本，确保无废弃遗留脚本，实现 100% 干净覆盖
rm -f "$SCRIPTS_DIR"/*.sh 2>/dev/null || true
cp -rf "$MODPATH/scripts/"* "$SCRIPTS_DIR/"

chmod 755 "$MODPATH/service.sh" 2>/dev/null || true
chmod 755 "$MODPATH/action.sh" 2>/dev/null || true
chmod 755 "$MODPATH/uninstall.sh" 2>/dev/null || true
chmod 755 "$MODPATH/scripts/"*.sh 2>/dev/null || true
chmod 755 "$SCRIPTS_DIR/"*.sh 2>/dev/null || true

# 2. 处理 RootFS 底包解压与覆盖策略
mkdir -p "$ROOTFS_DIR"

FORCE_CLEAN=0
if [ -f "/sdcard/Download/DSHA/.clean_install" ] || [ -f "/data/media/0/Download/DSHA/.clean_install" ]; then
    FORCE_CLEAN=1
    rm -f "/sdcard/Download/DSHA/.clean_install" "/data/media/0/Download/DSHA/.clean_install" 2>/dev/null || true
    ui_print "- 检测到全新安装标记 (.clean_install)，将重置运行环境。"

    # 关键防变砖与防误删内部存储安全检查：在删除旧 rootfs 之前，必须严密卸载其下的所有子挂载点
    for m in $(grep "$ROOTFS_DIR" /proc/mounts 2>/dev/null | awk '{print $2}' | sort -r); do
        umount -l "$m" 2>/dev/null || true
    done

    if grep -q "$ROOTFS_DIR" /proc/mounts 2>/dev/null; then
        ui_print "⚠️ 警告: 检测到挂载点未能完全脱钩，跳过递归删除以保护内部存储安全！"
    else
        rm -rf "$ROOTFS_DIR"
        mkdir -p "$ROOTFS_DIR"
    fi
fi

if [ "$FORCE_CLEAN" = "0" ] && [ -f "$ROOTFS_DIR/usr/local/bin/node" ]; then
    ui_print "- 检测到已存在现成的 DSH 环境，保留当前用户数据与配置（保活更新）。"
    ui_print "- 如需彻底重装，请在 Download/DSHA 放入 .clean_install 文件后重刷。"
else
    # 检查 zip 中是否存在 rootfs.tar.gz
    LOCAL_TAR=""
    for p in "/sdcard/Download/DSHA/rootfs.tar.gz" \
             "/data/media/0/Download/DSHA/rootfs.tar.gz" \
             "/sdcard/Download/DSHA/dsha-ksu-project/release/rootfs.tar.gz" \
             "/data/media/0/Download/DSHA/dsha-ksu-project/release/rootfs.tar.gz"; do
        if [ -f "$p" ]; then
            LOCAL_TAR="$p"
            break
        fi
    done

    if unzip -l "$ZIPFILE" 2>/dev/null | grep -q "rootfs.tar.gz"; then
        ui_print "- 正在从刷机包内解压 DSH 原生运行时底包至 $ROOTFS_DIR ..."
        ui_print "- 此过程需要约 1~2 分钟，请勿息屏或退出..."
        unzip -p "$ZIPFILE" rootfs.tar.gz | tar -xz -C "$ROOTFS_DIR"
        ui_print "- 底包解压完毕！"
    elif [ -n "$LOCAL_TAR" ]; then
        ui_print "- 发现本地底包: $LOCAL_TAR"
        ui_print "- 正在解压至 $ROOTFS_DIR ..."
        tar -xzf "$LOCAL_TAR" -C "$ROOTFS_DIR"
        ui_print "- 本地底包解压完毕！"
    else
        ui_print "⚠️ 未在刷机包内发现 rootfs.tar.gz，也未在本地发现底包。"
        ui_print "⚠️ 请将 rootfs.tar.gz 放入 Download/DSHA/ 后重新刷入，或手动解压至 $ROOTFS_DIR。"
    fi
fi

# 确保 rootfs 基础目录与挂载保护点结构正确
chmod 755 "$ROOTFS_DIR/bin" 2>/dev/null || true
chmod 755 "$ROOTFS_DIR/usr/bin" 2>/dev/null || true
chmod 777 "$ROOTFS_DIR/tmp" 2>/dev/null || true
mkdir -p "$ROOTFS_DIR/dev/block"
mkdir -p "$ROOTFS_DIR/dev/shm"
mkdir -p "$ROOTFS_DIR/dev/pts"
mkdir -p "$ROOTFS_DIR/proc"
mkdir -p "$ROOTFS_DIR/sys"
mkdir -p "$ROOTFS_DIR/sdcard"
mkdir -p "$ROOTFS_DIR/root/.dsh"
chmod 777 "$ROOTFS_DIR/root/.dsh" 2>/dev/null || true
mkdir -p "$ROOTFS_DIR/sdcard/Download/DSHA/工作区" 2>/dev/null || true
rm -f "$ROOTFS_DIR/root/内部存储" 2>/dev/null || true
ln -sf /sdcard/Download/DSHA "$ROOTFS_DIR/root/内部存储" 2>/dev/null || true

ui_print "-----------------------------------------"
ui_print "安装成功！本模块开机不自启，0 功耗占用。"
ui_print "支持通过 KernelSU/APatch 模块「操作」按钮一键启停，"
ui_print "或通过 DSHA App / WebUI 随时拉起与管理。"
ui_print "终端快速进入命令: su -c /data/adb/dsha/scripts/term.sh"
ui_print "*****************************************"
