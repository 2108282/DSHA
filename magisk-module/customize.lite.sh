SKIPUNZIP=1
ui_print "*****************************************"
ui_print "      DSHA Native 极速热更新补丁包       "
ui_print "      增量热更新 · 无需重装完整底包      "
ui_print "*****************************************"

DATA_DIR="/data/adb/dsha"
ROOTFS_DIR="$DATA_DIR/rootfs"
SCRIPTS_DIR="$DATA_DIR/scripts"

choose_step() {
    local title="$1"
    local opt_yes="$2"
    local opt_no="$3"
    local timeout=15
    local start_time=$(date +%s)

    ui_print ""
    ui_print "-----------------------------------------"
    ui_print " $title"
    ui_print "-----------------------------------------"
    ui_print " 请在 15 秒内按物理音量键选择："
    ui_print " 【音量 +】: 是 ($opt_yes)"
    ui_print " 【音量 -】: 否 ($opt_no)"
    ui_print " 超时（15秒）默认: 否 ($opt_no)"
    ui_print "-----------------------------------------"
    ui_print ""

    timeout 0.3 getevent -l >/dev/null 2>&1 || true

    while true; do
        local now=$(date +%s)
        local elapsed=$((now - start_time))
        if [ $elapsed -ge $timeout ]; then
            ui_print "⏱ 超时未按键，默认选择: 否 ($opt_no)"
            return 1
        fi

        local events=$(timeout 1 getevent -l 2>/dev/null || true)
        case "$events" in
            *KEY_VOLUMEUP*|*"0001 0073"*|*"0001 0073 00000001"*|*key_volumeup*)
                ui_print "👉 已按下【音量 +】: 选择「是 ($opt_yes)」"
                return 0
                ;;
            *KEY_VOLUMEDOWN*|*"0001 0072"*|*"0001 0072 00000001"*|*key_volumedown*)
                ui_print "👉 已按下【音量 -】: 选择「否 ($opt_no)」"
                return 1
                ;;
        esac
    done
}

# 0. 热升级安全防护：若正在运行，先停止旧进程
if [ -f "$SCRIPTS_DIR/stop.sh" ] && [ -f "$DATA_DIR/run/dsh.pid" ]; then
    ui_print "- 检测到 DSHA 正在运行，正在平稳停止旧进程以保证安全更新..."
    sh "$SCRIPTS_DIR/stop.sh" --umount >/dev/null 2>&1 || true
fi

# 确保模块基础元数据与服务文件安装
mkdir -p "$MODPATH"
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2 2>/dev/null || true
unzip -o "$ZIPFILE" 'action.sh' -d "$MODPATH" >&2 2>/dev/null || true
unzip -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2 2>/dev/null || true
chmod 755 "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh" 2>/dev/null || true

# 【第一步】：是否更新增量补丁 (前端/插件/代码修复等)
UPDATE_OVERLAY=0
if choose_step "【第 1 步】：是否更新增量业务补丁？(前端/插件/修复代码)" "更新增量补丁" "跳过，保持不变"; then
    UPDATE_OVERLAY=1
fi

if [ "$UPDATE_OVERLAY" = "1" ]; then
    if [ ! -d "$ROOTFS_DIR" ]; then
        ui_print "⚠️ 警告: 未找到 $ROOTFS_DIR 底包环境，无法应用增量补丁！"
    else
        ui_print "- 正在解压并热更新增量补丁到 $ROOTFS_DIR ..."
        unzip -o "$ZIPFILE" 'overlay/*' -d /tmp/dsha_overlay_tmp >&2
        if [ -d "/tmp/dsha_overlay_tmp/overlay" ]; then
            cp -rf /tmp/dsha_overlay_tmp/overlay/* "$ROOTFS_DIR/"
            rm -rf /tmp/dsha_overlay_tmp
            
            # 为更新的插件补齐 installed 标记与软链接
            for p in "$ROOTFS_DIR/root/dsha-"*; do
                [ -d "$p" ] || continue
                pname=$(basename "$p")
                touch "$ROOTFS_DIR/root/${pname}-installed"
                ln -sfn "/root/$pname" "$ROOTFS_DIR/root/.dsh/profiles/web/node_modules/dsh-${pname#dsha-}" 2>/dev/null || true
                ln -sfn "/root/$pname" "$ROOTFS_DIR/usr/local/lib/node_modules/dsh-${pname#dsha-}" 2>/dev/null || true
            done
            ui_print "✓ 增量业务补丁热更新完毕！"
        else
            ui_print "ℹ️ 本更新包中未包含增量补丁，跳过。"
        fi
    fi
else
    ui_print "- 已跳过增量业务补丁更新。"
fi

# 【第二步】：是否覆盖四大基础脚本
UPDATE_BASE=0
if choose_step "【第 2 步】：是否覆盖四大基础控制脚本？(start/stop/status/term)" "覆盖基础脚本" "保留当前已有脚本"; then
    UPDATE_BASE=1
fi

if [ "$UPDATE_BASE" = "1" ]; then
    ui_print "- 正在覆盖四大基础控制脚本至 $SCRIPTS_DIR ..."
    mkdir -p "$MODPATH/scripts" "$SCRIPTS_DIR"
    unzip -o "$ZIPFILE" 'scripts/*' -d "$MODPATH" >&2
    cp -rf "$MODPATH/scripts/"* "$SCRIPTS_DIR/"
    chmod 755 "$MODPATH/scripts/"*.sh "$SCRIPTS_DIR/"*.sh 2>/dev/null || true
    ui_print "✓ 四大基础控制脚本已成功更新！"
else
    ui_print "- 已保留当前已有的基础控制脚本，未做改动。"
fi

ui_print "-----------------------------------------"
ui_print "✓ DSHA 热更新处理完成！"
ui_print "*****************************************"
