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
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import androidx.annotation.Nullable;

/**
 * 设备桥服务（普通后台服务，非前台 —— 不 startForeground，杜绝
 * CannotPostForegroundServiceNotificationException 杀进程）。
 *
 * 职责（仅在用户勾选「启用 ADB」后才会启动本服务）：
 *  1. 3090 Shizuku 桥 + Shizuku 绑定；
 *  2. ADB 配对环境后台预热；
 *  3. Nsd 监听无线调试配对弹窗 → 通知里输 6 位码。
 *
 * 通知显示需要 Android 13+ 通知权限；无权限时静默跳过（App 内工作区仍可配对）。
 */
public class DeviceBridgeService extends Service {

    public static final String PREF_ADB = "adb_enabled";

    private static volatile boolean running = false;

    /** 最近一次发现的配对端口（供 AdbPairReceiver 秒级直用） */
    public static volatile int pairPort = 0;
    /** 最近一次发现的配对服务地址（部分 ROM 配对服务只监听 WiFi 接口 IP） */
    public static volatile String pairHost = "";

    public static boolean isAdbEnabled(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean(PREF_ADB, false);
    }

    /** 按开关启停。默认关：不想用 ADB 的人不会被后台扫描/通知拖慢。 */
    public static void apply(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, DeviceBridgeService.class);
        if (isAdbEnabled(app)) {
            try {
                app.startService(i);
            } catch (Throwable ignored) {
            }
        } else {
            try {
                app.stopService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final String WATCH_CHANNEL = "dsh_adb_watch_channel";
    private static final int WATCH_NOTIF_ID = 3005;
    /** 常驻设备桥卡片（普通通知 ongoing —— 非 FGS，永不触发 RemoteServiceException 杀进程） */
    private static final int CARD_NOTIF_ID = 3006;
    private static final long NOTIFY_COOLDOWN_MS = 45000;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairListener;
    private long lastNotifiedAt = 0;
    /** 手动开启无线调试提醒节流 */
    private volatile long lastManualNotifyAt = 0;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!isAdbEnabled(this)) {
            stopSelf();
            return;
        }
        running = true;
        try {
            new HttpShellService(this).start();
        } catch (Throwable ignored) {
        }
        try {
            ShizukuShell.ensureBound(this);
        } catch (Throwable ignored) {
        }
        prewarmAdb();
        postCard();
        startPairWatcher();
        startConnWatcher(); // ADB 连接看门狗：掉线自动重连（参考 Shizuku 生态看门狗）
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY; // 被系统回收不自动重启（避免后台复活合规问题）
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            // 联动关闭 HTTP shell 桥（否则 3090 端口残留监听）
            HttpShellService hs = HttpShellService.instance();
            if (hs != null) hs.stop();
        } catch (Throwable ignored) {
        }
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
            // RemoteInput 必须 FLAG_MUTABLE：IMMUTABLE 收不到通知里输入的内容
            PendingIntent pi = PendingIntent.getBroadcast(this, 23, pairIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
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

    /**
     * ADB 连接看门狗：周期探测 adb-shell 是否可用（参考 Shizuku 生态看门狗思路）。
     * 掉线自动重连：Nsd 重新发现 _adb-tls-connect 连接端口 → 用已有 adbkey 直连（无需重新配对）。
     * 无线调试被系统关闭（_adb-tls-connect 找不到）→ 尝试 Shizuku 自动重开 → 失败通知用户。
     */
    private void startConnWatcher() {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable check = new Runnable() {
            @Override
            public void run() {
                if (!running || !isAdbEnabled(DeviceBridgeService.this)) return;
                new Thread(() -> {
                    try {
                        HarnessController c = HarnessController.get(DeviceBridgeService.this);
                        if (c == null || !c.getProot().isInstalled()) return;
                        // 1. 探测当前连接是否可用
                        String r = c.getProot().execAndRead("python3 /root/.dsh/adb-shell.py id 2>&1 | head -3");
                        if (r != null && r.contains("uid=")) return; // 连接正常
                        // 库缺失（setup 未完成）→ 触发 setup 自愈（注入脚本+装依赖+装包装命令）
                        if (r != null && r.contains("DEPS_MISSING")) {
                            AdbBridge.ensureReady(DeviceBridgeService.this, c.getProot());
                            return;
                        }
                        // 2. 掉线：重新发现 _adb-tls-connect 连接端口
                        int connPort = discoverConnPortSync();
                        if (connPort > 0) {
                            saveConnectPort(connPort);
                            // 3. 用新端口再测一次
                            String r2 = c.getProot().execAndRead("python3 /root/.dsh/adb-shell.py --port " + connPort + " id 2>&1 | head -3");
                            if (r2 != null && r2.contains("uid=")) {
                                android.util.Log.i("DSHA-ADB", "看门狗：已自动重连端口 " + connPort);
                                return;
                            }
                        }
                        // 4. 无线调试可能被关：优先用 WRITE_SECURE_SETTINGS 权限直接开
                        //    （thedjchi/Shizuku 机制，无需 Shizuku）；无权限再试 Shizuku
                        boolean opened = false;
                        try {
                            boolean hasSecure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
                            if (hasSecure) {
                                int cur = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                                if (cur != 1) {
                                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                                    android.util.Log.i("DSHA-ADB", "看门狗：WRITE_SECURE_SETTINGS 自动开启无线调试");
                                    opened = true;
                                } else {
                                    opened = true; // 本来就开着
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        if (!opened && ShizukuShell.isAvailable()) {
                            String out = ShizukuShell.exec("settings put global adb_wifi_enabled 1 2>&1; adb tcpip 5555 2>&1");
                            android.util.Log.i("DSHA-ADB", "看门狗：Shizuku 尝试重开无线调试 → " + out);
                            if (out != null && !out.contains("[NO_") && !out.contains("ERROR")) {
                                opened = true;
                            }
                        }
                        if (opened) {
                            // 等 5s 让 adbd 起来，再重试一次
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                            int p2 = discoverConnPortSync();
                            if (p2 > 0) {
                                saveConnectPort(p2);
                                c.getProot().execAndRead("python3 /root/.dsh/adb-shell.py --port " + p2 + " id 2>&1 | head -1");
                            }
                            return;
                        }
                        // 5. 都失败：低频通知用户手动开（45s 冷却已有）
                        notifyNeedManual(this);
                    } catch (Throwable ignored) {
                    }
                }, "dsha-adb-watchdog").start();
                h.postDelayed(this, 30000); // 30s 周期
            }
        };
        h.postDelayed(check, 15000); // 启动 15s 后首查
    }

    /** 同步发现 _adb-tls-connect 连接端口（0=没发现） */
    private int discoverConnPortSync() {
        final int[] port = new int[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            NsdManager nm = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nm == null) return 0;
            final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
            holder[0] = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    nm.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo s, int e) { done.countDown(); }
                        @Override public void onServiceResolved(NsdServiceInfo s) {
                            port[0] = s.getPort();
                            try { nm.stopServiceDiscovery(holder[0]); } catch (Throwable ignored) { }
                            done.countDown();
                        }
                    });
                }
                @Override public void onServiceLost(NsdServiceInfo info) { }
            };
            nm.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, holder[0]);
            done.await(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) { }
        return port[0];
    }

    /** 写连接端口到 rootfs（adb-shell.py 默认读取） */
    private void saveConnectPort(int port) {
        try {
            HarnessController c = HarnessController.get(this);
            if (c == null || c.getProot() == null) return;
            c.getProot().execAndRead("mkdir -p /root/.dsh/adbkeys && echo " + port + " > /root/.dsh/adbkeys/connect_port");
        } catch (Throwable ignored) { }
    }

    /** 通知用户需要手动开无线调试（低频） */
    private void notifyNeedManual(Runnable self) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastManualNotifyAt < 600000) return; // 10 分钟一次
            lastManualNotifyAt = now;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        WATCH_CHANNEL, "ADB 连接提醒", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent app = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 25, app,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, WATCH_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle("🔌 ADB 连接已断开")
                    .setContentText("已尝试自动重连失败。请打开手机「无线调试」（开发者选项），将自动恢复连接")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            nm.notify(WATCH_NOTIF_ID, b.build());
        } catch (Throwable ignored) { }
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
                                if (port > 0) {
                                    // 缓存真实 host：部分 ROM 配对服务只监听 WiFi 接口，127.0.0.1 连不上
                                    try {
                                        if (serviceInfo.getHost() != null) {
                                            String h = serviceInfo.getHost().getHostAddress();
                                            if (h != null) {
                                                if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
                                                pairHost = h;
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                    onPairServiceFound(port);
                                }
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
            // RemoteInput 必须 FLAG_MUTABLE：IMMUTABLE 收不到通知里输入的内容
            PendingIntent pi = PendingIntent.getBroadcast(this, 23, pairIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
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
