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
    public static final String ACTION_RESTART = "com.deepseekharness.app.RESTART";

    private static final String CHANNEL_ID = "dsh_harness_channel";
    private static final int NOTIF_ID = 1001;

    private HarnessController c;
    private HttpShellService shellHttp;
    private TaskNotifier taskNotifier;
    private final HarnessController.StateListener stateListener = this::refreshNotification;

    // ================= WebUI 监听保活 =================
    private Thread keepAliveThread;
    private volatile boolean keepAliveRunning;
    private final java.util.concurrent.atomic.AtomicBoolean restarting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong lastRestartAt = new java.util.concurrent.atomic.AtomicLong(0);
    private static final long KEEPALIVE_INTERVAL_MS = 15000L;  // 探测间隔
    private static final long RESTART_COOLDOWN_MS = 120000L;   // 重启冷却：2 分钟内不重复拉起
    private static final int KEEPALIVE_MAX_FAIL = 3;           // 连续失败次数阈值

    @Override
    public void onCreate() {
        super.onCreate();
        c = HarnessController.get(this);
        createChannel();
        c.addStateListener(stateListener);
        startForeground(NOTIF_ID, buildNotification("DSHA运行中", "Web UI 正在后台保持运行"));
        // 桥接 Shizuku shell 能力（rootfs 里的助手可通过 127.0.0.1:3090 执行设备命令）
        shellHttp = new HttpShellService(this);
        shellHttp.start();
        ShizukuShell.ensureBound(this);
        // 任务完成通知：agent 干活结束后提醒
        taskNotifier = new TaskNotifier(this, c);
        taskNotifier.start();
        // 局域网转发桥：开启局域网模式时，App 侧 0.0.0.0:3081 → 127.0.0.1:3080
        // （绕开官方 0.0.0.0 拦截与 Host 校验，Shizuku 式桥接思路；状态写 /root/dsh-lan.log 可终端查看）
        if (c.isLanMode()) {
            LanProxyService.start(c.getRootfsDirPath());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            c.stopWeb();
            stopKeepAlive();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            // 软重启：深停 → 等端口关透 → 重新拉起（不再杀 App 进程「闪退」重启）
            c.restartWeb();
            startKeepAlive();
            return START_STICKY;
        }
        startWeb();
        startKeepAlive();
        return START_STICKY;
    }

    private void startWeb() {
        c.startWeb();
    }

    /** 启动保活监听：TCP 探测 WebUI 端口，连续失联自动重启（带冷却防风暴） */
    private void startKeepAlive() {
        stopKeepAlive();
        keepAliveRunning = true;
        keepAliveThread = new Thread(() -> {
            int fail = 0;
            while (keepAliveRunning) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!keepAliveRunning) break;
                if (isWebUp()) {
                    fail = 0;
                    continue;
                }
                fail++;
                if (fail < KEEPALIVE_MAX_FAIL) continue;
                fail = 0;
                long now = System.currentTimeMillis();
                if (c.isKeepAlivePaused()) {
                    // 用户手动停止过、尚未手动/预启动：keepAlive 不自动拉起（尊重用户）
                    continue;
                }
                if (now - lastRestartAt.get() < RESTART_COOLDOWN_MS) continue; // 冷却期，等它自己缓过来
                lastRestartAt.set(now);
                if (restarting.compareAndSet(false, true)) {
                    try {
                        android.util.Log.w("DSHA", "[保活] WebUI 连续失联，自动重启");
                        c.startWeb();
                    } catch (Throwable ignored) {
                    } finally {
                        restarting.set(false);
                    }
                }
            }
        }, "dsha-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        keepAliveRunning = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
    }

    /** TCP 探测 127.0.0.1:<port> 是否可达（proot 与宿主共享网络栈） */
    private boolean isWebUp() {
        int port;
        try {
            port = Integer.parseInt(c.getPort());
        } catch (Exception e) {
            return false;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshNotification() {
        if (c.getError() != null && !c.getError().isEmpty()) {
            updateNotification("DSHA启动失败", c.getError());
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            updateNotification("DSHA运行中", "Web UI: http://127.0.0.1:" + c.getPort());
        }
    }

    public void onDestroy() {
        c.removeStateListener(stateListener);
        stopKeepAlive();
        if (shellHttp != null) shellHttp.stop();
        if (taskNotifier != null) taskNotifier.stop();
        LanProxyService.stop();
        c.stopWeb();
        super.onDestroy();
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

}
