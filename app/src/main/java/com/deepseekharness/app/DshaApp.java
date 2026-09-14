package com.deepseekharness.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 应用入口：全局初始化。
 * 骨架阶段只建一个任务通知渠道；完整版另有配对/确认渠道（见原 Constants.CHANNEL_*）。
 */
public class DshaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        com.deepseekharness.app.ui.ThemeController.apply(this);
        // 保证 3090 桥独立常驻（免 ADB / 免 Shizuku 原生通用通道），不依赖 ADB 开关
        HttpShellService.ensureStarted(this);

        com.deepseekharness.app.core.DiagnosticLog.installCrashHandler(this);
        registerActivityLifecycleCallbacks(new com.deepseekharness.app.ui.ModernAndroidUi());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // 1. 常驻后台服务渠道
                NotificationChannel chService = new NotificationChannel(
                        "dsh_harness_channel", "DSHA后台服务", NotificationManager.IMPORTANCE_LOW);
                chService.setDescription("保持 DeepSeek Harness 原生守护与硬件桥后台运行");
                nm.createNotificationChannel(chService);

                // 2. 运行状态实时胶囊渠道
                NotificationChannel chRunning = new NotificationChannel(
                        Constants.CHANNEL_AGENT_RUNNING, "Agent 运行状态", NotificationManager.IMPORTANCE_DEFAULT);
                chRunning.setDescription("智能体运行中实时操作步骤通知");
                nm.createNotificationChannel(chRunning);

                // 3. 任务结果与交付卡片渠道
                NotificationChannel chResult = new NotificationChannel(
                        Constants.CHANNEL_TASK_RESULT, "任务结果与交互", NotificationManager.IMPORTANCE_HIGH);
                chResult.setDescription("智能体任务完成、异常结束或终止时的结果通知");
                chResult.enableVibration(true);
                chResult.enableLights(true);
                nm.createNotificationChannel(chResult);

                // 4. 安全确认与助手提问渠道
                NotificationChannel chConfirm = new NotificationChannel(
                        Constants.CHANNEL_SHELL_CONFIRM, "安全确认", NotificationManager.IMPORTANCE_HIGH);
                chConfirm.setDescription("模型执行危险操作时的确认提醒与助手提问");
                chConfirm.enableVibration(true);
                chConfirm.enableLights(true);
                nm.createNotificationChannel(chConfirm);
            }
        }
    }
}
