#!/system/bin/sh
# DSHA Native Module - 模块卸载清理脚本
# 保证在管理器中卸载模块时，安全平稳卸载内核挂载与后台守护进程，防止资源泄漏

DATA_DIR="/data/adb/dsha"
SCRIPTS_DIR="$DATA_DIR/scripts"

if [ -f "$SCRIPTS_DIR/stop.sh" ]; then
    sh "$SCRIPTS_DIR/stop.sh" --umount >/dev/null 2>&1 || true
fi

# 清理运行时锁与端口标记
rm -f "$DATA_DIR/run/dsh.pid" "$DATA_DIR/run/port" 2>/dev/null || true

# 注：为保护用户项目数据与代码安全，默认保留 $DATA_DIR/rootfs 底包与工作区。
# 若需彻底清空，请在系统文件管理器中删除 /data/adb/dsha 目录。
