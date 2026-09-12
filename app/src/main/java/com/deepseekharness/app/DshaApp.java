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
            nm.createNotificationChannel(new NotificationChannel(
                    "dsh_task_channel", "任务通知", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
