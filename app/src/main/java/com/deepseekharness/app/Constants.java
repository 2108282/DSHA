package com.deepseekharness.app;

/**
 * 全局常量统一管理：通知 ID / 端口 / 渠道 / SharedPreferences 键。
 * 避免各文件散落的魔法数字互相冲突（曾出现 3003 通知 ID 撞车）。
 */
public final class Constants {

    private Constants() {
    }

    /** SharedPreferences 文件名（全 App 统一） */
    public static final String PREFS = "deepseekharness";

    // ================= 通知 ID（全局唯一，禁止重复） =================
    /** HarnessService 前台服务通知 */
    public static final int NOTIF_HARNESS_SERVICE = 1001;
    /** TaskNotifier 任务完成通知 */
    public static final int NOTIF_TASK = 2002;
    /** HttpShellService 危险命令确认通知 */
    public static final int NOTIF_SHELL_CONFIRM = 3003;
    /** ConfigFragment ADB 配对卡 */
    public static final int NOTIF_ADB_PAIR_CARD = 3101;
    /** AdbPairReceiver 配对结果 */
    public static final int NOTIF_ADB_PAIR_RESULT = 3004;
    /** DeviceBridgeService 配对提醒 */
    public static final int NOTIF_ADB_WATCH = 3005;
    /** DeviceBridgeService 常驻设备桥卡 */
    public static final int NOTIF_ADB_CARD = 3006;

    // ================= 通知渠道 =================
    public static final String CHANNEL_TASK = "dsh_task_channel";
    public static final String CHANNEL_ADB_PAIR = "dsh_adbpair_channel";
    public static final String CHANNEL_ADB_WATCH = "dsh_adb_watch_channel";
    public static final String CHANNEL_SHELL_CONFIRM = "dsh_confirm_channel";

    // ================= 端口 =================
    /** WebUI 默认端口 */
    public static final String DEFAULT_PORT = "3080";
    /** Shizuku HTTP 桥端口（rootfs 内 agent 访问设备 shell） */
    public static final int SHELL_BRIDGE_PORT = 3090;
    /** ADB 传统连接端口 */
    public static final int ADB_DEFAULT_CONNECT_PORT = 5555;

    // ================= SharedPreferences 键 =================
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_PORT = "port";
    public static final String KEY_MODEL = "model";
    public static final String KEY_WORKDIR = "workdir";
    public static final String KEY_PERMISSION_MODE = "permission_mode";
    public static final String KEY_CONFIRM_SHELL = "confirm_shell";
    public static final String KEY_CHECK_UPDATE = "check_update";
    public static final String KEY_DESKTOP_MODE = "desktop_mode";
    public static final String KEY_LAN_MODE = "lan_mode";
    public static final String KEY_USE_RC6 = "use_rc6";
    public static final String KEY_GECKO_CORE = "gecko_core";
    public static final String KEY_ADB_ENABLED = "adb_enabled";
    public static final String KEY_AUTO_BACKUP_LAUNCHES = "auto_backup_launches";
    public static final String KEY_LAUNCH_COUNT = "launch_count";
    public static final String KEY_LAST_VERSION_CODE = "last_version_code";
    public static final String KEY_WELCOMED = "welcomed";
    public static final String KEY_HIDE_BUILTIN = "hide_builtin";
    /** 允许 agent 使用 root shell（--su 提权）；默认关，需配置页手动授权 */
    public static final String KEY_ALLOW_ROOT_SHELL = "allow_root_shell";
    /** 用户选择"忽略"的离线包版本（下次不弹升级提示） */
    public static final String KEY_IGNORED_OFFLINE_VER = "ignored_offline_version";
}
