#!/system/bin/sh
# DSHA Native Module - Passive Service (No Auto-start)
# 本模块遵循按需启动（On-demand）原则，开机不自启 DSH 服务，保持 0 功耗。

# 仅在系统启动完成时解除 Android 12+ 幽灵进程上限
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 3
done

/system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null

# 确保运行时状态目录存在
mkdir -p /data/adb/dsha/run
chmod 777 /data/adb/dsha/run 2>/dev/null || true
