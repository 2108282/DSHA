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

# 解压包内所有控制脚本、现场补丁与通用 rootfs-overlay 增量资产到临时运行目录
TMP_STAGE="/tmp/dsha_lite_stage_$$"
mkdir -p "$TMP_STAGE"
unzip -o "$ZIPFILE" 'scripts/*' 'patches/*' 'rootfs-overlay/*' -d "$TMP_STAGE" >&2 2>/dev/null || true

# -------------------------------------------------------------
# 【第 1 步】：是否应用增量补丁与容器更新？(rootfs-overlay 镜像层叠 + patches 补丁)
# -------------------------------------------------------------
if choose_step "【第 1 步】：是否应用增量补丁与容器更新？" "应用增量更新" "跳过，不修改容器"; then
    ui_print "- 正在应用增量更新..."

    # 1.1 通用容器增量层叠 (rootfs-overlay)
    if [ -d "$TMP_STAGE/rootfs-overlay" ] && [ -n "$(ls -A "$TMP_STAGE/rootfs-overlay" 2>/dev/null)" ]; then
        ui_print "  -> 检测到容器通用增量文件 (rootfs-overlay)，正在递归镜像覆盖至 $ROOTFS_DIR ..."
        mkdir -p "$ROOTFS_DIR"
        cp -af "$TMP_STAGE/rootfs-overlay/." "$ROOTFS_DIR/"
        # 自动保障权限：脚本与可执行组件自动赋予 755
        find "$TMP_STAGE/rootfs-overlay" -type f \( -name "*.sh" -o -name "*.js" \) 2>/dev/null | while read -r f; do
            rel_path="${f#$TMP_STAGE/rootfs-overlay/}"
            [ -f "$ROOTFS_DIR/$rel_path" ] && chmod 755 "$ROOTFS_DIR/$rel_path" 2>/dev/null || true
        done
        ui_print "     ✓ 容器通用增量文件层叠覆盖完成！"
    fi

    # 1.2 执行现场增量补丁 (patches/*.sh)
    PATCH_FOUND=0
    for patch in "$TMP_STAGE/patches/"*.sh; do
        [ -f "$patch" ] || continue
        fname=$(basename "$patch")
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
    done
    if [ "$PATCH_FOUND" = "0" ] && [ ! -d "$TMP_STAGE/rootfs-overlay" ]; then
        ui_print "  (未在包内发现增量文件与补丁脚本，已自动跳过)"
    fi
else
    ui_print "- 已跳过增量更新，当前容器环境保持原样。"
fi

# -------------------------------------------------------------
# 【第 2 步】：是否覆盖五大基础控制脚本？(start/stop/status/term/lan-proxy)
# -------------------------------------------------------------
if choose_step "【第 2 步】：是否覆盖五大基础控制脚本？(start/stop/status/term/lan-proxy)" "覆盖基础脚本" "保留当前已有脚本"; then
    ui_print "- 正在覆盖五大基础控制脚本至 $SCRIPTS_DIR ..."
    mkdir -p "$SCRIPTS_DIR" "$MODPATH/scripts"
    for base_script in start.sh stop.sh status.sh term.sh lan-proxy.sh; do
        if [ -f "$TMP_STAGE/scripts/$base_script" ]; then
            cp -f "$TMP_STAGE/scripts/$base_script" "$SCRIPTS_DIR/$base_script"
            cp -f "$TMP_STAGE/scripts/$base_script" "$MODPATH/scripts/$base_script"
            chmod 755 "$SCRIPTS_DIR/$base_script" "$MODPATH/scripts/$base_script"
            ui_print "  ✓ 已覆盖: $base_script"
        fi
    done

    ui_print "✓ 五大基础控制脚本覆盖完毕！"
else
    ui_print "- 已跳过基础脚本覆盖，当前脚本保持原样。"
fi

rm -rf "$TMP_STAGE"
ui_print "-----------------------------------------"
ui_print "✓ DSHA 极速热更新全部处理完成！"
ui_print "*****************************************"
