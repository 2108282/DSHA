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
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DSHA:task");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 60 * 1000L); // 单次任务最多持锁 10 分钟防死锁
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
            android.util.Log.w("DSHA", "[保活] 取锁失败: " + SensitiveData.redact(String.valueOf(t)));
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
                        // 熄屏：若无任务在跑，立即释放锁进入深睡
                        if (!isTaskRunning()) {
                            releaseLocks();
                            android.util.Log.i("DSHA", "[保活] 屏幕熄灭且无运行任务，已释放锁进入休眠");
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
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
        Notification notification = buildNotification("DSHA 运行中", "原生 Linux 守护进程与 3090 设备桥保持在线");
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, notification);
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
