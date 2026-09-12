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

import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.SensitiveData;

/**
 * 前台保活服务：让 dsh Web UI 在后台稳定常驻。
 *  - startForeground 常驻通知，降低被系统回收概率；
 *  - START_STICKY 被杀后由系统重启；
 *  - 看门狗：TCP 探测 WebUI 端口，连续失联自动重启（带冷却防风暴）；
 *  - WakeLock/WifiLock 息屏保活（熄屏后 node 不被冻结、局域网桥不断）。
 *
 * 适配重启项目（精简版 HarnessController：startWeb(Consumer) / stopWeb / isWebRunning）。
 */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.deepseekharness.app.START";
    public static final String ACTION_STOP = "com.deepseekharness.app.STOP";

    private static final String CHANNEL_ID = "dsh_harness_channel";
    private static final int NOTIF_ID = 1001;

    private HarnessController c;
    private HttpShellService shellHttp;
    public static volatile HarnessService currentInstance;
    private android.content.BroadcastReceiver screenReceiver;

    // ================= WebUI 监听保活 =================
    private Thread keepAliveThread;
    private volatile boolean keepAliveRunning;
    private final java.util.concurrent.atomic.AtomicLong lastRestartAt =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final long KEEPALIVE_INTERVAL_MS = 60000L;
    private static final long RESTART_COOLDOWN_MS = 120000L;
    private static final int KEEPALIVE_MAX_FAIL = 3;

    /** 息屏保活用的两把锁。 */
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        currentInstance = this;
        c = HarnessController.get(this);
        createChannel();
        try {
            showForegroundNotification();
        } catch (RuntimeException error) {
            android.util.Log.w("DSHA", "前台服务未获系统允许: " + error.getClass().getSimpleName());
            stopSelf();
            return;
        }
        // 3090 桥（agent 调设备能力）随前台服务拉起；跨实例互斥，重复启动安全
        try {
            shellHttp = new HttpShellService(this);
            shellHttp.start();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 8+ 硬性契约：startForegroundService() 拉起的服务必须在 5 秒内 startForeground，
        // 否则被强杀。每次 onStartCommand 无条件先立通知（幂等）。
        try {
            showForegroundNotification();
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "onStartCommand startForeground 失败: "
                    + SensitiveData.redact(String.valueOf(e)));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWebAndSelf();
            return START_NOT_STICKY;
        }
        if (!c.isStarting() && !c.canAutoRestart()) {
            // 用户停止或哨兵仍在时不拉起；不在服务主线程执行 proot 探测。
            return START_STICKY;
        }
        startKeepAlive();
        return START_STICKY;
    }

    private void stopWebAndSelf() {
        stopKeepAlive();
        try {
            c.stopWeb(msg -> { });
        } catch (Throwable ignored) {
        }
        try {
            if (shellHttp != null) shellHttp.stop();
        } catch (Throwable ignored) {
        }
        try {
            stopService(new Intent(this, DeviceBridgeService.class));
        } catch (Throwable ignored) {
        }
        stopForeground(true);
        stopSelf();
    }

    // ================= 息屏保活与动态休眠 =================

    private synchronized void acquireLocks() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean isInteractive = pm != null && pm.isInteractive();
            if (!isInteractive && !isTaskRunning()) {
                // 屏幕熄灭且当前无正在执行的任务：不持锁，允许系统进入深度睡眠（Doze / Suspend）
                return;
            }
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DSHA:web");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DSHA:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "[保活] 取锁失败（不致命）: "
                    + SensitiveData.redact(String.valueOf(t)));
        }
    }

    private synchronized void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }

    private void startScreenWatcher() {
        if (screenReceiver != null) return;
        try {
            screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        // 熄屏：若无后台任务在跑，立即释放 WakeLock 与 WifiLock，系统进入深度休眠
                        if (!isTaskRunning()) {
                            releaseLocks();
                            android.util.Log.i("DSHA", "[保活] 屏幕熄灭且无运行任务，已释放锁进入休眠");
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                        // 亮屏/解锁：重新持锁保证前台极速响应
                        acquireLocks();
                        android.util.Log.i("DSHA", "[保活] 屏幕亮起，已重新持有 WakeLock/WifiLock");
                    }
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "注册屏幕状态监听失败: " + e.getMessage());
        }
    }

    private void stopScreenWatcher() {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {}
            screenReceiver = null;
        }
    }

    private boolean isTaskRunning() {
        return HttpShellService.isTaskActive;
    }

    public void checkAndReleaseLocksIfIdle() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive() && !isTaskRunning()) {
                releaseLocks();
                android.util.Log.i("DSHA", "[保活] 任务结束且处于熄屏状态，已释放锁进入休眠");
            }
        } catch (Throwable ignored) {}
    }

    public static void onTaskStateChanged(Context ctx, boolean running) {
        HarnessService s = currentInstance;
        if (s != null) {
            if (running) {
                s.acquireLocks();
            } else {
                s.checkAndReleaseLocksIfIdle();
            }
        }
    }

    // ================= 看门狗 =================

    private void startKeepAlive() {
        stopKeepAlive();
        acquireLocks();
        startScreenWatcher();
        keepAliveRunning = true;
        keepAliveThread = new Thread(() -> {
            int fail = 0;
            while (keepAliveRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!keepAliveRunning) break;
                if (!c.canAutoRestart()) {
                    synchronized (HarnessService.this) {
                        // 只在用户停止时释放；下次手动启动由 startKeepAlive 重新取锁。
                        if (c.isUserStopped()) releaseLocks();
                    }
                    fail = 0;
                    continue;
                }
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isInteractive() && !isTaskRunning()) {
                    // 屏幕熄灭且无任务在跑：跳过主动探活，让进程与网络协议栈完全休眠
                    fail = 0;
                    continue;
                }
                long generation = c.getWebGeneration();
                // 顺手守着 ADB 设备桥（普通后台服务被回收时拉回来）
                try {
                    if (DeviceBridgeService.isAdbEnabled(HarnessService.this)
                            && !DeviceBridgeService.isRunning()) {
                        DeviceBridgeService.apply(HarnessService.this);
                    }
                } catch (Throwable ignored) {
                }
                if (isWebUp()) {
                    fail = 0;
                    continue;
                }
                // TCP 探测期间可能发生手动启停，不能沿用旧探测结果。
                if (!keepAliveRunning || Thread.currentThread().isInterrupted()
                        || generation != c.getWebGeneration() || !c.canAutoRestart()) {
                    fail = 0;
                    continue;
                }
                fail++;
                if (fail < KEEPALIVE_MAX_FAIL) continue;
                fail = 0;
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastRestartAt.get() != 0 && now - lastRestartAt.get() < RESTART_COOLDOWN_MS) continue;
                // Controller 持有启动门控直到就绪/失败/超时，无需异步返回即释放的第二把锁。
                if (c.restartWebAutomatically(generation, msg -> { })) {
                    lastRestartAt.set(now);
                    android.util.Log.w("DSHA", "[保活] WebUI 连续失联，已提交自动重启");
                }
            }
        }, "dsha-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        stopScreenWatcher();
        releaseLocks();
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
            port = c.config().getPortInt();
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

    @Override
    public void onDestroy() {
        if (currentInstance == this) currentInstance = null;
        stopKeepAlive();
        if (shellHttp != null) {
            try {
                shellHttp.stop();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showForegroundNotification() {
        Notification notification = buildNotification("DSHA运行中", "Web UI 正在后台保持运行");
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, notification);
    }

    // ================= 通知 =================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DSHA后台服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持 DeepSeek Harness Web UI 后台运行");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent intent = new Intent(this, com.deepseekharness.app.ui.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, "停止", stopPi)
                .build();
    }
}
