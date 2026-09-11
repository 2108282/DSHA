package com.deepseekharness.app.util;

/**
 * 全局常量统一管理：端口 / 路径 / SharedPreferences 键 / dsh 版本。
 * 骨架阶段只保留框架不变式，其余（通知 ID、ADB、LAN token 等）按需回填。
 */
public final class Constants {

    private Constants() {
    }

    /** SharedPreferences 文件名（全 App 统一） */
    public static final String PREFS = "deepseekharness";

    // ================= 端口契约（框架不变式，见 AGENTS.md） =================
    /** WebUI 默认端口 */
    public static final int DSH_WEB_PORT = 3080;
    /** App 能力桥（agent 调 Android）端口 */
    public static final int SHELL_BRIDGE_PORT = 3090;
    /** 局域网反向代理端口 */
    public static final int LAN_BRIDGE_PORT = 3081;
    /** ADB 传统连接端口（兜底，非可靠路径） */
    public static final int ADB_DEFAULT_CONNECT_PORT = 5555;

    // ================= 通知 ID（全局唯一，禁止重复） =================
    /** HarnessService 前台服务通知 */
    public static final int NOTIF_HARNESS_SERVICE = 1001;
    /** TaskNotifier 任务完成通知 */
    public static final int NOTIF_TASK = 2002;
    /** 智能体运行中实时状态通知（带停止按钮） */
    public static final int NOTIF_TASK_RUNNING = 2003;
    /** 智能体任务已终止通知（带重新输入框） */
    public static final int NOTIF_TASK_STOPPED = 2004;
    /** 危险命令确认通知（3090 桥） */
    public static final int NOTIF_SHELL_CONFIRM = 3003;
    /** HttpShellService 助手提问通知 */
    public static final int NOTIF_ASK_QUESTION = 3007;
    /** ConfigFragment ADB 配对卡 */
    public static final int NOTIF_ADB_PAIR_CARD = 3101;
    /** AdbPairReceiver 配对结果 */
    public static final int NOTIF_ADB_PAIR_RESULT = 3004;
    /** DeviceBridgeService 配对提醒 */
    public static final int NOTIF_ADB_WATCH = 3005;
    /** DeviceBridgeService 常驻设备桥卡 */
    public static final int NOTIF_ADB_CARD = 3006;

    // ================= 通知渠道 =================
    /** 智能体运行状态通知渠道（带停止任务按钮） */
    public static final String CHANNEL_AGENT_RUNNING = "dsh_agent_channel";
    /** 智能体任务结果与交互渠道（完成/报错/终止，带 RemoteInput 继续对话） */
    public static final String CHANNEL_TASK_RESULT = "dsh_task_result_v2";
    public static final String CHANNEL_TASK = "dsh_task_channel";
    public static final String CHANNEL_ADB_PAIR = "dsh_adbpair_channel";
    public static final String CHANNEL_ADB_WATCH = "dsh_adb_watch_channel";
    public static final String CHANNEL_SHELL_CONFIRM = "dsh_confirm_channel";

    // ================= dsh 版本（出厂内置版本） =================
    /**
     * 当前 APK 内置 dsh 核心版本。
     * 对应上游 deepseek-ai/deepseek-harness 仓库。
     */
    public static final String DSH_VERSION = "0.1.5-rc.2";
    public static final String DSH_RUNTIME_ID = "dsh-v" + DSH_VERSION;
    /** 全局安装路径下的 dsh 入口（容器内路径，见 WebProcSel 的 cmdline 判据）。 */
    public static final String DSH_BIN_JS =
            "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js";

    // ================= SharedPreferences 键（历史键名保持兼容，见 AGENTS.md） =================
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_PORT = "port";
    public static final String KEY_MODEL = "model";
    public static final String KEY_WORKDIR = "workdir";
    public static final String KEY_PERMISSION_MODE = "permission_mode";
    public static final String KEY_WELCOMED = "welcomed";
    /** 强制用 GeckoView 内核（系统 WebView 过旧时兜底）。 */
    public static final String KEY_GECKO_CORE = "gecko_core";
    /** 危险 shell 操作需确认（DSH_CONFIRM）。 */
    public static final String KEY_CONFIRM_SHELL = "confirm_shell";
    /** 允许 root shell（--su 提权）。 */
    public static final String KEY_ALLOW_ROOT_SHELL = "allow_root_shell";
    /** 启动时检查更新。 */
    public static final String KEY_CHECK_UPDATE = "check_update";
    /** 电脑模式（预览用桌面浏览器 UA）。 */
    public static final String KEY_DESKTOP_MODE = "desktop_mode";
    /** 备份是否包含 API key。 */
    public static final String KEY_BACKUP_KEY = "backup_key";
    /** 局域网访问开关。 */
    public static final String KEY_LAN_MODE = "lan_mode";
    /** 局域网桥凭据（256-bit，等长比对，v2 键名）。 */
    public static final String KEY_LAN_TOKEN_V2 = "lan_token_v2";
    /** 容器运行时：proroot / proot。 */
    public static final String KEY_CONTAINER_RUNTIME = "container_runtime";
    /** 自动备份频率（每启动 N 次，0=关）。 */
    public static final String KEY_AUTO_BACKUP = "auto_backup_launches";

    /** 默认工作目录（容器内路径）。 */
    public static final String DEFAULT_WORKDIR = "/root/deepseek-harness";
}
