package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务完成通知器：监控 rootfs 内会话文件的写入活动。
 * 检测到会话有写入 = agent 正在干活（活跃）；
 * 连续静默 30 秒 = 任务完成 → 发系统通知提醒。
 * App 在前台（用户正在看预览）时不打扰。
 */
public class TaskNotifier {

    public static final String CHANNEL_ID = "dsh_task_channel";
    private static final int NOTIF_ID = 2002;
    private static final long POLL_MS = 4000;
    private static final long IDLE_MS = 30000;

    /** App 是否在前台（MainActivity 维护）；前台时不发通知 */
    public static volatile boolean appInForeground = false;

    private final Context ctx;
    private final HarnessController c;
    private ScheduledExecutorService executor;
    private long lastActive = 0;
    private boolean armed = false;

    public TaskNotifier(Context ctx, HarnessController c) {
        this.ctx = ctx;
        this.c = c;
    }

    public void start() {
        if (executor != null) return;
        createChannel();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::tick, 5, POLL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        armed = false;
        lastActive = 0;
    }

    private void tick() {
        try {
            if (!c.isWebRunning() || appInForeground) return;
            // 检测最近几秒内 /root/.dsh 下是否有文件被写入（会话活动）
            String out = c.getProot().execAndRead(
                    "find /root/.dsh -type f -newermt '-" + (POLL_MS / 1000 + 1)
                            + " seconds' 2>/dev/null | head -3");
            boolean active = out != null && !out.trim().isEmpty();
            long now = System.currentTimeMillis();
            if (active) {
                lastActive = now;
                armed = true;
            } else if (armed && now - lastActive >= IDLE_MS) {
                armed = false;
                notifyDone();
            }
        } catch (Exception ignored) {
            // 网络抖动/进程重启期间静默跳过
        }
    }

    private void notifyDone() {
        Intent intent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("DSHA · 任务完成")
                .setContentText("智能体已结束任务，点击查看结果")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "任务完成提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("智能体任务完成时通知");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
