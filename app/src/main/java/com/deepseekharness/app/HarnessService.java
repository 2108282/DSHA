package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 前台服务：强后台保活 Web UI。
 *  - startForeground 常驻通知，降低被系统回收概率；
 *  - START_STICKY 被杀后由系统重启；
 *  - 建议引导用户加入电池优化白名单。
 */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.deepseekharness.app.START";
    public static final String ACTION_STOP = "com.deepseekharness.app.STOP";

    private static final String CHANNEL_ID = "dsh_harness_channel";
    private static final int NOTIF_ID = 1001;

    private HarnessController c;
    private HttpShellService shellHttp;
    private final HarnessController.StateListener stateListener = this::refreshNotification;

    @Override
    public void onCreate() {
        super.onCreate();
        c = HarnessController.get(this);
        createChannel();
        c.addStateListener(stateListener);
        startForeground(NOTIF_ID, buildNotification("DSHA运行中", "Web UI 正在后台保持运行"));
        // 桥接 Shizuku shell 能力（rootfs 里的助手可通过 127.0.0.1:3090 执行设备命令）
        shellHttp = new HttpShellService();
        shellHttp.start();
        ShizukuShell.ensureBound(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            c.stopWeb();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startWeb();
        return START_STICKY;
    }

    private void startWeb() {
        c.startWeb();
    }

    private void refreshNotification() {
        if (c.getError() != null && !c.getError().isEmpty()) {
            updateNotification("DSHA启动失败", c.getError());
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            updateNotification("DSHA运行中", "Web UI: http://127.0.0.1:" + c.getPort());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ================= 通知 =================
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DSHA后台服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持 DeepSeek Harness Web UI 后台运行");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, "停止", stopPi)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }

    @Override
    public void onDestroy() {
        c.removeStateListener(stateListener);
        if (shellHttp != null) shellHttp.stop();
        c.stopWeb();
        super.onDestroy();
    }
}
