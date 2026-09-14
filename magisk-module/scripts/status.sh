#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"
PORT_FILE="$RUN_DIR/port"
LOG_FILE="$RUN_DIR/dsh-web.log"

PORT="3088"
[ -f "$PORT_FILE" ] && PORT=$(cat "$PORT_FILE" 2>/dev/null)
case "$PORT" in ''|*[!0-9]*) PORT=3088 ;; esac

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        echo "STATUS:RUNNING PID:$PID PORT:$PORT"
        TOKEN_FILE="$ROOTFS/root/.dsh/.bridge_token"
        [ -s "$TOKEN_FILE" ] && echo "BRIDGE_TOKEN:$(cat "$TOKEN_FILE" 2>/dev/null)"
        AUTH_URL=$(grep -o "http://127\.0\.0\.1:${PORT}/?token=[^ ]*" "$LOG_FILE" 2>/dev/null | tail -n 1)
        [ -z "$AUTH_URL" ] && AUTH_URL=$(grep -o 'http://127\.0\.0\.1:[0-9]*/?token=[^ ]*' "$LOG_FILE" 2>/dev/null | tail -n 1)
        [ -n "$AUTH_URL" ] && echo "URL:$AUTH_URL"
        exit 0
    fi
fi
echo "STATUS:STOPPED"
exit 1
