SKIPUNZIP=1
ui_print "*****************************************"
ui_print "      DSHA Native Chroot 极速运行时      "
ui_print "      适配: Android 12+ (KernelSU/Magisk) "
ui_print "*****************************************"

DATA_DIR="/data/adb/dsha"
ROOTFS_DIR="$DATA_DIR/rootfs"
SCRIPTS_DIR="$DATA_DIR/scripts"

# 音量键交互选择函数：按音量+ 覆盖，按音量- 保留，不执行备份
choose_overwrite() {
    local timeout=15
    local start_time=$(date +%s)

    ui_print ""
    ui_print "*****************************************"
    ui_print "    检测到已存在现成的 DSH 运行环境"
    ui_print "-----------------------------------------"
    ui_print " 请在 15 秒内按手机物理音量键进行选择："
    ui_print " 【音量 +】: 彻底覆盖全新安装（清空旧环境，不备份）"
    ui_print " 【音量 -】: 保留现有数据与配置（跳过覆盖）"
    ui_print "-----------------------------------------"
    ui_print " 超时（15秒）默认: 自动选择【音量 -】(保留数据)"
    ui_print "*****************************************"
    ui_print ""

    # 预先清空之前的残留按键事件
    timeout 0.3 getevent -l >/dev/null 2>&1 || true

    while true; do
        local now=$(date +%s)
        local elapsed=$((now - start_time))
        if [ $elapsed -ge $timeout ]; then
            ui_print "⏱ 超时未按键，默认选择: 保留现有数据（不覆盖）"
            return 1
        fi

        local events=$(timeout 1 getevent -l 2>/dev/null || true)
        case "$events" in
            *KEY_VOLUMEUP*|*"0001 0073"*|*"0001 0073 00000001"*|*key_volumeup*)
                ui_print "👉 已按下【音量 +】: 选择「彻底覆盖全新安装（不备份）」"
                return 0
                ;;
            *KEY_VOLUMEDOWN*|*"0001 0072"*|*"0001 0072 00000001"*|*key_volumedown*)
                ui_print "👉 已按下【音量 -】: 选择「保留现有数据（不覆盖）」"
                return 1
                ;;
        esac
    done
}

# 0. 热升级安全防护：若检测到旧实例正在运行，先平稳停止以保证原子覆盖
if [ -f "$SCRIPTS_DIR/stop.sh" ] && [ -f "$DATA_DIR/run/dsh.pid" ]; then
    ui_print "- 检测到 DSHA 正在运行，正在平稳停止旧进程以保证安全更新..."
    sh "$SCRIPTS_DIR/stop.sh" --umount >/dev/null 2>&1 || true
fi

# 1. 解压模块控制脚本与操作按钮
ui_print "- 正在安装控制脚本与操作按钮..."
mkdir -p "$MODPATH/scripts"
mkdir -p "$SCRIPTS_DIR"
mkdir -p "$DATA_DIR/run"

unzip -o "$ZIPFILE" 'scripts/*' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'action.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2

# 清空旧脚本，确保无废弃遗留脚本，实现 100% 干净覆盖
rm -f "$SCRIPTS_DIR"/*.sh 2>/dev/null || true
cp -rf "$MODPATH/scripts/"* "$SCRIPTS_DIR/"

chmod 755 "$MODPATH/service.sh" 2>/dev/null || true
chmod 755 "$MODPATH/action.sh" 2>/dev/null || true
chmod 755 "$MODPATH/uninstall.sh" 2>/dev/null || true
chmod 755 "$MODPATH/scripts/"*.sh 2>/dev/null || true
chmod 755 "$SCRIPTS_DIR/"*.sh 2>/dev/null || true

# 2. 处理 RootFS 底包解压与覆盖策略（音量键交互，不要备份）
mkdir -p "$ROOTFS_DIR"

FORCE_CLEAN=0

if [ -f "$ROOTFS_DIR/usr/local/bin/node" ]; then
    if [ -f "/sdcard/Download/DSHA/.clean_install" ] || [ -f "/data/media/0/Download/DSHA/.clean_install" ]; then
        FORCE_CLEAN=1
        rm -f "/sdcard/Download/DSHA/.clean_install" "/data/media/0/Download/DSHA/.clean_install" 2>/dev/null || true
        ui_print "- 检测到静默全新安装标记 (.clean_install)，直接执行彻底覆盖（不备份）。"
    elif [ -f "/sdcard/Download/DSHA/.keep_data" ] || [ -f "/data/media/0/Download/DSHA/.keep_data" ]; then
        FORCE_CLEAN=0
        rm -f "/sdcard/Download/DSHA/.keep_data" "/data/media/0/Download/DSHA/.keep_data" 2>/dev/null || true
        ui_print "- 检测到静默保留标记 (.keep_data)，直接跳过覆盖。"
    else
        # 弹出音量键交互选择
        if choose_overwrite; then
            FORCE_CLEAN=1
        else
            FORCE_CLEAN=0
        fi
    fi
else
    # 首次部署
    FORCE_CLEAN=1
    ui_print "- 首次部署运行环境，正在准备解压底包..."
fi

if [ "$FORCE_CLEAN" = "1" ]; then
    ui_print "- 正在清空旧运行环境（不备份，直接清理）..."

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
        ui_print "- 正在从刷机包内解压全新 DSH 原生运行时底包至 $ROOTFS_DIR ..."
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
else
    ui_print "- 已跳过底包覆盖，当前用户数据、已装软件包与配置已完整保留！"
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
ui_print "或通过 DSHA App / 浏览器网页 随时拉起与管理。"
ui_print "终端快速进入命令: su -c /data/adb/dsha/scripts/term.sh"
ui_print "*****************************************"
