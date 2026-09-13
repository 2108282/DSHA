#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"
LOG_FILE="$RUN_DIR/dsh-web.log"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        echo "STATUS:RUNNING PID:$PID"
        TOKEN_FILE="$ROOTFS/root/.dsh/.bridge_token"
        [ -s "$TOKEN_FILE" ] && echo "BRIDGE_TOKEN:$(cat "$TOKEN_FILE" 2>/dev/null)"
        AUTH_URL=$(grep -o 'http://127\.0\.0\.1:[0-9]*/?token=[^ ]*' "$LOG_FILE" 2>/dev/null | tail -n 1)
        [ -n "$AUTH_URL" ] && echo "URL:$AUTH_URL"
        exit 0
    fi
fi
echo "STATUS:STOPPED"
exit 1
