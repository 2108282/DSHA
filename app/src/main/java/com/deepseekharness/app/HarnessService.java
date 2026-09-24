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

import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SensitiveData;

/**
 * 前台保活服务：
 *  - startForeground 常驻通知，防止 Android LMK 回收进程；
 *  - 维护 3090 设备能力桥（HttpShellService / AppBridge）；
 *  - 动态 WakeLock 调度（仅在长任务执行时持锁，任务结束与熄屏自动休眠）；
 *  - 纯粹的前台保活，不设置误杀守护进程的看门狗。
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

    /** 动态休眠与任务保活使用的锁。 */
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private static volatile long sLastTaskActiveTime = 0L;

    private final android.os.Handler heartBeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable heartBeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                ConfigStore cfg = new ConfigStore(HarnessService.this);
                if (!cfg.isPersistentNotificationEnabled()) {
                    stopForeground(true);
                    stopSelf();
                    return;
                }
                if (c != null && !c.isWebRunning()) {
                    android.util.Log.i("DSHA", "[常驻通知] 检测到底层核心已停止运转，主动撤销常驻通知");
                    stopForeground(true);
                    stopSelf();
                    return;
                }
            } catch (Throwable ignored) {}
            // 仅在亮屏期间每 30 秒轻量确认一次核心存活
            heartBeatHandler.postDelayed(this, 30_000L);
        }
    };

    /** 熄屏超时兜底定时器：防止网络或异常场景下锁死整夜 */
    private final android.os.Handler screenOffTimeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable screenOffTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    long idleTime = System.currentTimeMillis() - sLastTaskActiveTime;
                    if (idleTime >= 600_000L) {
                        android.util.Log.w("DSHA", "[保活] 熄屏超时兜底触发 (600秒无刷新)，强制释放唤醒锁防整夜耗电");
                        HttpShellService.isTaskActive = false;
                        releaseLocks();
                    } else {
                        // 期间有新进度刷新，顺延剩余时间
                        screenOffTimeoutHandler.postDelayed(this, Math.max(10_000L, 600_000L - idleTime));
                    }
                }
            } catch (Throwable ignored) {}
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        currentInstance = this;
        c = HarnessController.get(this);
        createChannel();
        ConfigStore cfg = new ConfigStore(this);
        if (!cfg.isPersistentNotificationEnabled()) {
            stopSelf();
            return;
        }
        try {
            showForegroundNotification();
        } catch (RuntimeException error) {
            android.util.Log.w("DSHA", "前台服务未获系统允许: " + error.getClass().getSimpleName());
            stopSelf();
            return;
        }
        // 3090 桥（Agent 调用设备能力）随前台服务拉起
        ensureBridgeRunning();
    }

    private void ensureBridgeRunning() {
        try {
            if (HttpShellService.instance() == null) {
                shellHttp = new HttpShellService(this);
                shellHttp.start();
            } else {
                shellHttp = HttpShellService.instance();
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "3090 桥启动异常: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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

        ensureBridgeRunning();
        startScreenWatcher();
        return START_STICKY;
    }

    private void stopWebAndSelf() {
        stopScreenWatcher();
        releaseLocks();
        try {
            c.stopWeb(msg -> { });
        } catch (Throwable ignored) {
        }
        try {
            if (shellHttp != null) shellHttp.stop();
        } catch (Throwable ignored) {
        }
        stopForeground(true);
        stopSelf();
    }

    // ================= 动态休眠与按需持锁 =================

    private synchronized void acquireLocks() {
        try {
            if (!isTaskRunning()) {
                // 无后台长任务正在运行：不持锁，允许系统自由深睡
                return;
            }
            sLastTaskActiveTime = System.currentTimeMillis();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DSHA:task");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 60 * 1000L); // 单次任务最多持锁 10 分钟防死锁
            }
            // 仅在局域网模式下才需要申请 WifiLock；本机回环 127.0.0.1 绝不占用 Wi-Fi 硬件，彻底消除射频待机耗电
            boolean isLan = new com.deepseekharness.app.core.ConfigStore(this).isLanMode();
            if (isLan) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                    wifiLock = wm.createWifiLock(
                            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DSHA:wifi");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "[保活] 取锁失败: " + SensitiveData.redact(String.valueOf(t)));
        }
    }

    private synchronized void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {}
        wifiLock = null;
    }

    private synchronized void releaseLocks() {
        try {
            screenOffTimeoutHandler.removeCallbacks(screenOffTimeoutRunnable);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        releaseWifiLock();
        wakeLock = null;
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
                        heartBeatHandler.removeCallbacks(heartBeatRunnable);
                        // 熄屏：无论是否有任务，本机回环均无须占用物理 Wi-Fi 射频芯片，立即放锁让网卡休眠
                        releaseWifiLock();
                        long idleTime = System.currentTimeMillis() - sLastTaskActiveTime;
                        // 若无任务在跑，或任务已超过 5 分钟无任何刷新（防假活/丢包死锁），立即释放 WakeLock 进入系统 Deep Sleep
                        if (!isTaskRunning() || idleTime > 5 * 60 * 1000L) {
                            screenOffTimeoutHandler.removeCallbacks(screenOffTimeoutRunnable);
                            HttpShellService.isTaskActive = false;
                            releaseLocks();
                            android.util.Log.i("DSHA", "[保活] 屏幕熄灭且无活跃任务，已彻底释放全部锁进入深睡");
                        } else {
                            // 熄屏时仍有任务活跃：启动 600 秒超时兜底，防止网络或异常场景下锁死整夜
                            screenOffTimeoutHandler.removeCallbacks(screenOffTimeoutRunnable);
                            screenOffTimeoutHandler.postDelayed(screenOffTimeoutRunnable, 600_000L);
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                        screenOffTimeoutHandler.removeCallbacks(screenOffTimeoutRunnable);
                        heartBeatHandler.removeCallbacks(heartBeatRunnable);
                        heartBeatHandler.postDelayed(heartBeatRunnable, 30_000L);
                        if (isTaskRunning()) {
                            acquireLocks();
                        }
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
        screenOffTimeoutHandler.removeCallbacks(screenOffTimeoutRunnable);
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
            if (!isTaskRunning()) {
                releaseLocks();
                android.util.Log.i("DSHA", "[保活] 任务已结束，已释放全部 WakeLock/WifiLock");
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

    @Override
    public void onDestroy() {
        if (currentInstance == this) currentInstance = null;
        heartBeatHandler.removeCallbacks(heartBeatRunnable);
        stopScreenWatcher();
        releaseLocks();
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
        ConfigStore cfg = new ConfigStore(this);
        if (!cfg.isPersistentNotificationEnabled()) {
            stopForeground(true);
            return;
        }
        Notification notification = buildNotification("DSHA 运行中", "大肥鱼核心运行中");
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, notification);
    }

    public void refreshNotification() {
        try {
            ConfigStore cfg = new ConfigStore(this);
            if (!cfg.isPersistentNotificationEnabled()) {
                stopForeground(true);
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification("DSHA 运行中", "大肥鱼核心运行中"));
            }
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DSHA后台服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持 DeepSeek Harness 原生守护与硬件桥后台运行");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent sheetIntent = new Intent(this, com.deepseekharness.app.ui.QuickChatSheetActivity.class)
                .setAction("com.deepseekharness.app.OPEN_SHEET")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent sheetPi = PendingIntent.getActivity(this, 1, sheetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_whale_logo)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(sheetPi)
                .setOngoing(true)
                .addAction(0, "💬 打开抽屉", sheetPi)
                .addAction(0, "🛑 停止", stopPi);

        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.ic_whale_logo);
            if (bmp != null) b.setLargeIcon(bmp);
        } catch (Throwable ignored) {}

        return b.build();
    }

    // ================= 核心运转与常驻通知联动管理 =================

    /**
     * 根据底层核心运转状态与用户常驻通知开关，同步服务与通知状态。
     */
    public static void checkAndSyncService(Context ctx) {
        if (ctx == null) return;
        Context appCtx = ctx.getApplicationContext();
        ConfigStore cfg = new ConfigStore(appCtx);
        boolean enabled = cfg.isPersistentNotificationEnabled();
        HarnessController controller = HarnessController.get(appCtx);
        boolean running = controller != null && controller.isWebRunning();

        if (enabled && running) {
            startServiceIfNecessary(appCtx);
        } else if (!enabled || !running) {
            stopServiceIfNecessary(appCtx);
        }
    }

    public static void startServiceIfNecessary(Context ctx) {
        try {
            Intent svc = new Intent(ctx, HarnessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "拉起保活常驻通知服务失败: " + t.getMessage());
        }
    }

    public static void stopServiceIfNecessary(Context ctx) {
        try {
            if (currentInstance != null) {
                currentInstance.stopForeground(true);
                currentInstance.stopSelf();
            } else {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIF_ID);
            }
        } catch (Throwable ignored) {}
    }

    public static void syncPersistentNotificationState(Context ctx, boolean enabled) {
        if (enabled) {
            checkAndSyncService(ctx);
        } else {
            stopServiceIfNecessary(ctx);
        }
    }
}
