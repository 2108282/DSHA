package com.deepseekharness.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import androidx.annotation.Nullable;

/**
 * 设备桥服务（普通后台服务，非前台 —— 不 startForeground，杜绝
 * CannotPostForegroundServiceNotificationException 杀进程）。
 *
 * 职责：
 *  1. 3090 Shizuku 桥 + Shizuku 绑定（App 打开即有设备命令能力）；
 *  2. ADB 配对环境后台预热（首次配对秒级完成，配对码不过期）；
 *  3. Nsd 持续监听「无线调试配对弹窗」出现 → 弹一张带 RemoteInput 的
 *     「🔐 输码配对」通知：用户直接在通知卡片输入 6 位码即完成配对
 *     （不离开通知栏，码从出现到输完只隔几秒，几乎不会失效）。
 *
 * 通知显示需要 Android 13+ 通知权限；无权限时静默跳过（App 内工作区仍可配对）。
 */
public class DeviceBridgeService extends Service {

    private static volatile boolean running = false;

    /** 最近一次发现的配对端口（供 AdbPairReceiver 秒级直用） */
    public static volatile int pairPort = 0;

    private static final String WATCH_CHANNEL = "dsh_adb_watch_channel";
    private static final int WATCH_NOTIF_ID = 3005;
    /** 常驻设备桥卡片（普通通知 ongoing —— 非 FGS，永不触发 RemoteServiceException 杀进程） */
    private static final int CARD_NOTIF_ID = 3006;
    private static final long NOTIFY_COOLDOWN_MS = 45000;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairListener;
    private long lastNotifiedAt = 0;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        try {
            // 3090 Shizuku 桥（HttpShellService 自带 running 单例防重复启动）
            new HttpShellService(this).start();
        } catch (Throwable ignored) {
        }
        try {
            ShizukuShell.ensureBound(this);
        } catch (Throwable ignored) {
        }
        prewarmAdb();
        postCard();        // 常驻通知卡（像 Shizuku：卡片上直接输配对码）
        startPairWatcher(); // 配对弹窗出现 → 弹高优先级提醒
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY; // 被系统回收不自动重启（避免后台复活合规问题）
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (nsd != null && pairListener != null) {
                nsd.stopServiceDiscovery(pairListener);
            }
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CARD_NOTIF_ID);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** ADB 配对环境后台预就绪（幂等，已装则秒回） */
    private void prewarmAdb() {
        try {
            final HarnessController c = HarnessController.get(this);
            if (c == null || !c.getProot().isInstalled()) return;
            new Thread(() -> {
                try {
                    AdbBridge.ensureReady(DeviceBridgeService.this, c.getProot());
                } catch (Throwable ignored) {
                }
            }, "dsha-adb-prewarm").start();
        } catch (Throwable ignored) {
        }
    }

    /** 常驻设备桥卡片：卡片上直接输配对码（RemoteInput，普通通知无 FGS 崩溃风险） */
    private void postCard() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return; // 无权限静默（App 内工作区仍可配对）
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        WATCH_CHANNEL, "ADB 配对",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent app = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent appPi = PendingIntent.getActivity(this, 24, app,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            RemoteInput ri = new RemoteInput.Builder(AdbPairReceiver.EXTRA_CODE)
                    .setLabel("6 位配对码")
                    .build();
            Intent pairIntent = new Intent(this, AdbPairReceiver.class)
                    .setAction(AdbPairReceiver.ACTION_PAIR);
            PendingIntent pi = PendingIntent.getBroadcast(this, 23, pairIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Action action =
                    new NotificationCompat.Action.Builder(0, "🔐 输码配对", pi)
                            .addRemoteInput(ri)
                            .build();
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, WATCH_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle("DSHA 设备桥 · 输码配对")
                    .setContentText("点「🔐 输码配对」直接在通知里输 6 位码")
                    .setContentIntent(appPi)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .addAction(action);
            nm.notify(CARD_NOTIF_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    /** 持续监听无线调试配对服务（弹窗打开时 adbd 会广播 _adb-tls-pairing） */
    private void startPairWatcher() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            pairListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    try {
                        nsd.resolveService(info, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            }

                            @Override
                            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                                int port = serviceInfo.getPort();
                                if (port > 0) onPairServiceFound(port);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, pairListener);
        } catch (Throwable ignored) {
        }
    }

    /** 配对弹窗出现：缓存端口 + 高亮提醒（含 RemoteInput 就地输入） */
    private void onPairServiceFound(int port) {
        pairPort = port;
        long now = System.currentTimeMillis();
        if (now - lastNotifiedAt < NOTIFY_COOLDOWN_MS) return; // 去重
        lastNotifiedAt = now;
        // Android 13+ 无通知权限：静默（App 内工作区仍可配对）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        WATCH_CHANNEL, "ADB 配对提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            RemoteInput ri = new RemoteInput.Builder(AdbPairReceiver.EXTRA_CODE)
                    .setLabel("6 位配对码")
                    .build();
            Intent pairIntent = new Intent(this, AdbPairReceiver.class)
                    .setAction(AdbPairReceiver.ACTION_PAIR);
            PendingIntent pi = PendingIntent.getBroadcast(this, 23, pairIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Action action =
                    new NotificationCompat.Action.Builder(0, "🔐 输码配对", pi)
                            .addRemoteInput(ri)
                            .build();
            Intent app = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent appPi = PendingIntent.getActivity(this, 24, app,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, WATCH_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle("🔐 ADB 配对进行中")
                    .setContentText("点「输码配对」直接在通知里输入 6 位码（端口已自动捕获）")
                    .setContentIntent(appPi)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("无线调试配对弹窗已打开（端口 " + port + " 已捕获）。\n"
                                    + "直接在通知里输入屏幕上的 6 位配对码，无需离开通知栏。"))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .addAction(action);
            nm.notify(WATCH_NOTIF_ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
