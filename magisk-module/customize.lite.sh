SKIPUNZIP=1
ui_print "*****************************************"
ui_print "      DSHA Native 极速热更新补丁包       "
ui_print "      增量热更新 · 无需重装完整底包      "
ui_print "*****************************************"

DATA_DIR="/data/adb/dsha"
ROOTFS_DIR="$DATA_DIR/rootfs"
SCRIPTS_DIR="$DATA_DIR/scripts"

# 通用音量键交互选择函数 (音量+ 为是返回 0, 音量- 为否返回 1)
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
    ui_print " 请在 15 秒内按手机物理音量键选择："
    ui_print " 【音量 +】: 是 ($opt_yes)"
    ui_print " 【音量 -】: 否 ($opt_no)"
    ui_print " 超时（15秒）默认: 否 ($opt_no)"
    ui_print "-----------------------------------------"
    ui_print ""

    # 清空之前的按键残留事件
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

# 解压模块基础控制文件
mkdir -p "$MODPATH"
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2 2>/dev/null || true
unzip -o "$ZIPFILE" 'action.sh' -d "$MODPATH" >&2 2>/dev/null || true
unzip -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2 2>/dev/null || true
chmod 755 "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh" 2>/dev/null || true

# 解压包内所有脚本到临时运行目录
TMP_SCRIPTS="/tmp/dsha_lite_scripts_$$"
mkdir -p "$TMP_SCRIPTS"
unzip -o "$ZIPFILE" 'scripts/*' -d "$TMP_SCRIPTS" >&2

# -------------------------------------------------------------
# 【第 1 步】：是否执行增量补丁？(除了四大脚本外的所有 .sh 补丁脚本)
# -------------------------------------------------------------
if choose_step "【第 1 步】：是否执行增量补丁？" "执行增量补丁" "跳过，不执行"; then
    ui_print "- 正在扫描并执行增量补丁..."
    PATCH_FOUND=0
    for patch in "$TMP_SCRIPTS/scripts/"*.sh; do
        [ -f "$patch" ] || continue
        fname=$(basename "$patch")
        # 严格排除四大基础脚本，只执行增量补丁
        case "$fname" in
            start.sh|stop.sh|status.sh|term.sh)
                continue
                ;;
            *)
                PATCH_FOUND=1
                ui_print "  -> 正在现场执行增量补丁: $fname ..."
                chmod +x "$patch"
                export ROOTFS_DIR="$ROOTFS_DIR"
                export DATA_DIR="$DATA_DIR"
                if sh "$patch" "$ROOTFS_DIR" "$DATA_DIR"; then
                    ui_print "     ✓ 补丁 $fname 执行成功！"
                else
                    ui_print "     ⚠️ 警告: 补丁 $fname 执行状态非 0，继续下一步。"
                fi
                ;;
        esac
    done
    if [ "$PATCH_FOUND" = "0" ]; then
        ui_print "  (未在包内发现需要执行的增量补丁脚本，已自动跳过)"
    fi
else
    ui_print "- 已跳过增量补丁的执行。"
fi

# -------------------------------------------------------------
# 【第 2 步】：是否覆盖四大基础脚本？(start/stop/status/term)
# -------------------------------------------------------------
if choose_step "【第 2 步】：是否覆盖四大基础控制脚本？(start/stop/status/term)" "覆盖基础脚本" "保留当前已有脚本"; then
    ui_print "- 正在覆盖四大基础控制脚本至 $SCRIPTS_DIR ..."
    mkdir -p "$SCRIPTS_DIR" "$MODPATH/scripts"
    for base_script in start.sh stop.sh status.sh term.sh; do
        if [ -f "$TMP_SCRIPTS/scripts/$base_script" ]; then
            cp -f "$TMP_SCRIPTS/scripts/$base_script" "$SCRIPTS_DIR/$base_script"
            cp -f "$TMP_SCRIPTS/scripts/$base_script" "$MODPATH/scripts/$base_script"
            chmod 755 "$SCRIPTS_DIR/$base_script" "$MODPATH/scripts/$base_script"
            ui_print "  ✓ 已覆盖: $base_script"
        fi
    done
    ui_print "✓ 四大基础控制脚本覆盖完毕！"
else
    ui_print "- 已跳过基础脚本覆盖，当前脚本保持原样。"
fi

rm -rf "$TMP_SCRIPTS"
ui_print "-----------------------------------------"
ui_print "✓ DSHA 极速热更新全部处理完成！"
ui_print "*****************************************"
