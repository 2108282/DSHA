#!/system/bin/sh
# ============================================================
# DSHA Native - 局域网反向代理守护管理器 (LAN Reverse Proxy Manager)
# 监听 0.0.0.0:3081 转发至 127.0.0.1:3080
# 支持操作: start | stop | restart | status | token [new_token]
# ============================================================

ACTION="${1:-status}"

# 环境自适应：检测是在宿主环境还是在容器内部运行
if [ -d "/data/adb/dsha/rootfs" ]; then
    ROOTFS="/data/adb/dsha/rootfs"
    RUN_DIR="/data/adb/dsha/run"
    IN_CONTAINER=0
else
    ROOTFS=""
    RUN_DIR="/data/adb/dsha/run"
    [ -d "$RUN_DIR" ] || RUN_DIR="/root/.dsh"
    IN_CONTAINER=1
fi

PID_FILE="$RUN_DIR/lan-proxy.pid"
ENABLED_FILE="$RUN_DIR/lan_enabled"
PORT_FILE="$RUN_DIR/port"
LAN_LOG="$RUN_DIR/lan-proxy.log"

if [ "$IN_CONTAINER" = "0" ]; then
    PROXY_JS="$ROOTFS/root/.dsh/dsha-lan-proxy.js"
    TOKEN_FILE="$ROOTFS/root/.dsh/.bridge_token"
    LAN_TOKEN_FILE="$ROOTFS/root/.dsh/.lan_token"
else
    PROXY_JS="/root/.dsh/dsha-lan-proxy.js"
    TOKEN_FILE="/root/.dsh/.bridge_token"
    LAN_TOKEN_FILE="/root/.dsh/.lan_token"
fi

mkdir -p "$RUN_DIR" 2>/dev/null || true

get_current_token() {
    if [ -s "$LAN_TOKEN_FILE" ]; then
        cat "$LAN_TOKEN_FILE" 2>/dev/null | tr -d " \n\r"
    elif [ -s "$TOKEN_FILE" ]; then
        cat "$TOKEN_FILE" 2>/dev/null | tr -d " \n\r"
    else
        echo ""
    fi
}

is_running() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            return 0
        fi
    fi
    # 双重检查进程名
    if pgrep -f "dsha-lan-proxy.js" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

do_status() {
    if is_running; then
        PID=$(cat "$PID_FILE" 2>/dev/null)
        [ -z "$PID" ] && PID=$(pgrep -f "dsha-lan-proxy.js" 2>/dev/null | head -1)
        TOK=$(get_current_token)
        echo "STATUS:RUNNING PID:${PID:-未知} PORT:3081"
        [ -n "$TOK" ] && echo "TOKEN:$TOK"
        exit 0
    else
        echo "STATUS:STOPPED"
        exit 1
    fi
}

do_stop() {
    rm -f "$ENABLED_FILE" 2>/dev/null || true
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            kill -15 "$PID" 2>/dev/null
            for i in 1 2 3; do
                kill -0 "$PID" 2>/dev/null || break
                sleep 0.1 2>/dev/null || sleep 1
            done
            kill -9 "$PID" 2>/dev/null || true
        fi
        rm -f "$PID_FILE" 2>/dev/null || true
    fi
    pkill -9 -f "dsha-lan-proxy.js" >/dev/null 2>&1 || true
    echo "STATUS:STOPPED"
    exit 0
}

do_token() {
    NEW_TOK="${2:-}"
    if [ -n "$NEW_TOK" ]; then
        # 写入新 Token
        echo -n "$NEW_TOK" > "$LAN_TOKEN_FILE" 2>/dev/null || true
        chmod 666 "$LAN_TOKEN_FILE" 2>/dev/null || true
        # 保持与 bridge_token 同步，确保 3080 内部校验 100% 通过
        echo -n "$NEW_TOK" > "$TOKEN_FILE" 2>/dev/null || true
        chmod 666 "$TOKEN_FILE" 2>/dev/null || true
        echo "TOKEN:$NEW_TOK"
        exit 0
    else
        TOK=$(get_current_token)
        if [ -z "$TOK" ]; then
            # 自动生成 32 位安全 Token
            TOK=$(tr -dc "a-zA-Z0-9" < /dev/urandom 2>/dev/null | head -c 32 || echo "dsha_lan_$(date +%s)")
            echo -n "$TOK" > "$LAN_TOKEN_FILE" 2>/dev/null || true
            chmod 666 "$LAN_TOKEN_FILE" 2>/dev/null || true
            echo -n "$TOK" > "$TOKEN_FILE" 2>/dev/null || true
            chmod 666 "$TOKEN_FILE" 2>/dev/null || true
        fi
        echo "TOKEN:$TOK"
        exit 0
    fi
}

do_start() {
    touch "$ENABLED_FILE" 2>/dev/null || true

    if is_running; then
        PID=$(cat "$PID_FILE" 2>/dev/null)
        [ -z "$PID" ] && PID=$(pgrep -f "dsha-lan-proxy.js" 2>/dev/null | head -1)
        TOK=$(get_current_token)
        echo "STATUS:ALREADY_RUNNING PID:${PID:-未知} PORT:3081"
        [ -n "$TOK" ] && echo "TOKEN:$TOK"
        exit 0
    fi

    # 获取 DSH 实际运行的主端口
    DSH_PORT="3080"
    [ -f "$PORT_FILE" ] && DSH_PORT=$(cat "$PORT_FILE" 2>/dev/null | tr -d " \n\r")
    case "$DSH_PORT" in ""|*[!0-9]*) DSH_PORT=3080 ;; esac

    # 确保 proxy 脚本存在
    if [ ! -f "$PROXY_JS" ]; then
        echo "ERROR: 找不到代理脚本 $PROXY_JS" >&2
        exit 1
    fi

    # 清空或初始化日志
    > "$LAN_LOG" 2>/dev/null || true

    if [ "$IN_CONTAINER" = "0" ]; then
        # 宿主环境下，通过 chroot 隔离拉起 Node 代理
        chroot "$ROOTFS" /usr/bin/env -i \
            HOME=/root \
            USER=root \
            LOGNAME=root \
            PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            SSL_CERT_FILE=/usr/lib/ssl/cert.pem \
            LANG=C.UTF-8 \
            LC_ALL=C.UTF-8 \
            LAN_PORT=3081 \
            DSH_PORT="$DSH_PORT" \
            nice -n 10 /usr/local/bin/node /root/.dsh/dsha-lan-proxy.js > "$LAN_LOG" 2>&1 &
        NEW_PID=$!
    else
        # 容器内直接拉起
        LAN_PORT=3081 DSH_PORT="$DSH_PORT" nice -n 10 /usr/local/bin/node /root/.dsh/dsha-lan-proxy.js > "$LAN_LOG" 2>&1 &
        NEW_PID=$!
    fi

    echo "$NEW_PID" > "$PID_FILE" 2>/dev/null || true
    echo -800 > "/proc/$NEW_PID/oom_score_adj" 2>/dev/null || true

    # 调度纳入系统后台组
    if [ -d "/dev/cpuctl/background" ]; then
        echo "$NEW_PID" > /dev/cpuctl/background/cgroup.procs 2>/dev/null || true
    fi
    if [ -d "/dev/cpuset/background" ]; then
        echo "$NEW_PID" > /dev/cpuset/background/cgroup.procs 2>/dev/null || true
    fi

    # 轮询等待端口就绪（最多 3 秒）
    STARTED=0
    for i in 1 2 3 4 5 6; do
        if kill -0 "$NEW_PID" 2>/dev/null; then
            if grep -q "已启动" "$LAN_LOG" 2>/dev/null; then
                STARTED=1
                break
            fi
        else
            break
        fi
        sleep 0.5
    done

    if [ "$STARTED" = "1" ] || kill -0 "$NEW_PID" 2>/dev/null; then
        TOK=$(get_current_token)
        echo "STATUS:STARTED PID:$NEW_PID PORT:3081"
        [ -n "$TOK" ] && echo "TOKEN:$TOK"
        exit 0
    else
        echo "STATUS:FAILED" >&2
        cat "$LAN_LOG" >&2
        rm -f "$PID_FILE" 2>/dev/null || true
        exit 1
    fi
}

case "$ACTION" in
    start)
        do_start
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_stop
        do_start
        ;;
    status)
        do_status
        ;;
    token)
        do_token "$@"
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|token [new_token]}" >&2
        exit 1
        ;;
esac
