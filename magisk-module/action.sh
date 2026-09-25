#!/system/bin/sh
# DSHA Native Chroot - KernelSU / APatch / Magisk 模块操作按钮 (Action Button)
# 点击按钮自动在「启动」与「停止」之间无缝切换，动态联动 module.prop 状态显示。

MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "." ] && MODDIR="/data/adb/modules/dsha_native"

DATA_DIR="/data/adb/dsha"
SCRIPTS_DIR="$DATA_DIR/scripts"
RUN_DIR="$DATA_DIR/run"
PID_FILE="$RUN_DIR/dsh.pid"
PORT_FILE="$RUN_DIR/port"
LOG_FILE="$RUN_DIR/dsh-web.log"
PROP_FILE="$MODDIR/module.prop"
TOKEN_FILE="$DATA_DIR/rootfs/root/.dsh/.bridge_token"

run_root_mm() {
    local cmd="$1"
    if command -v su >/dev/null 2>&1 && su -mm -c "true" 2>/dev/null; then
        su -mm -c "$cmd"
    elif [ -x "/data/adb/magisk/magisk" ]; then
        /data/adb/magisk/magisk su -mm -c "$cmd" 2>/dev/null || /data/adb/magisk/magisk su -c "$cmd"
    elif [ -x "/data/adb/ksu/bin/su" ]; then
        /data/adb/ksu/bin/su -mm -c "$cmd" 2>/dev/null || /data/adb/ksu/bin/su -c "$cmd"
    elif [ -x "/data/adb/ap/bin/su" ]; then
        /data/adb/ap/bin/su -mm -c "$cmd" 2>/dev/null || /data/adb/ap/bin/su -c "$cmd"
    elif command -v su >/dev/null 2>&1; then
        su -c "$cmd"
    else
        sh -c "$cmd"
    fi
}

update_prop_status() {
    local status_tag="$1"
    if [ -f "$PROP_FILE" ]; then
        sed -i "s|^description=.*|description=[${status_tag}] DSHA 原生 Linux chroot 极速运行时，按需启停。|" "$PROP_FILE" 2>/dev/null || true
    fi
}

# 1. 检测当前运行状态
IS_RUNNING=0
CUR_PID=""
if [ -f "$PID_FILE" ]; then
    CUR_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$CUR_PID" ] && kill -0 "$CUR_PID" 2>/dev/null; then
        IS_RUNNING=1
    fi
fi

if [ "$IS_RUNNING" = "0" ] && [ -f "$SCRIPTS_DIR/status.sh" ]; then
    STAT_OUT=$(run_root_mm "$SCRIPTS_DIR/status.sh" 2>/dev/null || true)
    if echo "$STAT_OUT" | grep -q "STATUS:RUNNING"; then
        IS_RUNNING=1
        CUR_PID=$(echo "$STAT_OUT" | grep -o "PID:[0-9]*" | cut -d: -f2)
    fi
fi

# 2. 已在运行 -> 执行停止
if [ "$IS_RUNNING" = "1" ]; then
    PORT="3080"
    [ -f "$PORT_FILE" ] && PORT=$(cat "$PORT_FILE" 2>/dev/null)
    case "$PORT" in ''|*[!0-9]*) PORT=3080 ;; esac

    echo "========================================="
    echo "       DSHA Native 核心运行时管理"
    echo "========================================="
    echo "[当前状态] 🟢 运行中 (PID: ${CUR_PID:-未知}, 端口: ${PORT})"
    echo "[操作指令] 正在停止 DSHA 核心守护进程..."

    if [ -f "$SCRIPTS_DIR/stop.sh" ]; then
        run_root_mm "$SCRIPTS_DIR/stop.sh"
    elif [ -n "$CUR_PID" ]; then
        kill -15 "$CUR_PID" 2>/dev/null || true
        sleep 0.5
        kill -9 "$CUR_PID" 2>/dev/null || true
        rm -f "$PID_FILE" "$PORT_FILE" 2>/dev/null || true
    fi

    update_prop_status "🔴 已停止"

    echo "[执行结果] 🔴 DSHA 核心服务已成功停止！"
    echo "[状态提示] 内核挂载与内存已恢复 0 功耗占用。"
    echo "========================================="
    exit 0
fi

# 3. 未在运行 -> 执行启动
PORT="3080"
[ -f "$PORT_FILE" ] && PORT=$(cat "$PORT_FILE" 2>/dev/null)
case "$PORT" in ''|*[!0-9]*) PORT=3080 ;; esac

echo "========================================="
echo "       DSHA Native 核心运行时管理"
echo "========================================="
echo "[当前状态] 🔴 未运行"
echo "[操作指令] 正在启动 DSHA 核心服务 (端口: ${PORT})..."

if [ ! -f "$SCRIPTS_DIR/start.sh" ]; then
    echo "[错误] 核心启动脚本不存在: $SCRIPTS_DIR/start.sh"
    echo "请检查模块是否完整安装。"
    echo "========================================="
    exit 1
fi

START_OUT=$(run_root_mm "$SCRIPTS_DIR/start.sh $PORT")
echo "$START_OUT"

# 轮询获取鉴权链接与端口
AUTH_URL=""
for i in 1 2 3 4 5; do
    AUTH_URL=$(grep -o "http://127\.0\.0\.1:${PORT}/?token=[^ ]*" "$LOG_FILE" 2>/dev/null | tail -n 1)
    [ -z "$AUTH_URL" ] && AUTH_URL=$(grep -o 'http://127\.0\.0\.1:[0-9]*/?token=[^ ]*' "$LOG_FILE" 2>/dev/null | tail -n 1)
    [ -n "$AUTH_URL" ] && break
    sleep 0.3 2>/dev/null || sleep 1
done

if [ -z "$AUTH_URL" ] && [ -f "$TOKEN_FILE" ]; then
    TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null)
    [ -n "$TOKEN" ] && AUTH_URL="http://127.0.0.1:${PORT}/?token=${TOKEN}"
fi

update_prop_status "🟢 运行中 :${PORT}"

echo "-----------------------------------------"
echo "[执行结果] 🟢 DSHA 核心服务启动成功！"
echo "[监听端口] ${PORT}"
if [ -n "$AUTH_URL" ]; then
    echo "[Web 链接] ${AUTH_URL}"
fi
echo "========================================="
echo "提示: 可随时再次点击本按钮一键停止服务。"

# 发送轻量 Toast 提醒
curl -s -m 2 "http://127.0.0.1:3095/app/toast?text=DSHA+Native+核心服务已就绪" >/dev/null 2>&1 || true

exit 0
