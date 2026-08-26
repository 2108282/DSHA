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
        // 任务完成通知已改为内置插件 dsh-task-notifier（turn/end 监听更准），
        // 旧 TaskNotifier 轮询停用（否则双重通知）
        // 局域网转发桥：开启局域网模式时，App 侧 0.0.0.0:3081 → 127.0.0.1:3080
        // （绕开官方 0.0.0.0 拦截与 Host 校验，Shizuku 式桥接思路；状态写 /root/dsh-lan.log 可终端查看）
        if (c.isLanMode()) {
            LanProxyService.start(c.getRootfsDirPath(), this, c.getPortInt());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 8+ 的硬性契约：凡是用 startForegroundService() 拉起的服务，
        // **必须在 5 秒内**调用 startForeground()，否则系统抛
        // ForegroundServiceDidNotStartInTimeException 直接杀掉进程。
        //
        // 原来 startForeground 只在 onCreate() 里调了一次。服务**首次**创建时没问题，
        // 但点「重启」时服务已经在跑，onCreate 不会再走，而 onStartCommand 里没有
        // —— 于是必然超时被强杀。用户看到的就是「点重启就闪退」，而且 crash.log
        // 是空的（系统强杀不走 UncaughtExceptionHandler，logcat 里也没有
        // FATAL EXCEPTION），这正是这个 bug 极难定位的原因。
        //
        // 所以这里无条件先把前台通知立起来（重复调用是允许且幂等的），之后再干活。
        try {
            startForeground(NOTIF_ID,
                    buildNotification("DSHA运行中", "Web UI 正在后台保持运行"));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "onStartCommand 里 startForeground 失败: " + e);
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            c.stopWeb();
            stopKeepAlive();
            // 设备桥（127.0.0.1:3090）也一起停 —— 它是独立的后台服务，不跟着前台服务走。
            // 「停止」在用户眼里就是全停：留个监听端口在那儿既费电，也会让覆盖安装时系统
            // 多一个要终止的目标（装 391MB 包时那正是 Session destroyed 的诱因之一，
            // 所以装新包前点一下通知栏这个「停止」就够，不必再去设置页找入口）。
            try {
                stopService(new Intent(this, DeviceBridgeService.class));
            } catch (Throwable ignored) {
            }
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
        // intent 为 null = 系统因 START_STICKY 把服务重建了。这时若用户此前明确停过，
        // 就不该再拉起 Web —— 否则他永远停不掉，只剩「强制停止」这一条路。
        // 前台服务壳留着（保持通知与后续可点启动），但不碰 Web 与保活。
        if (intent == null && !c.shouldAutoStartWeb("服务被系统重建")) {
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
                // 顺手守着设备桥：ADB 通道开着、但那个普通后台服务被系统回收时把它拉回来。
                // 前台服务的存活率高得多，用它当靠山最稳（否则 ADB 能力会静默消失）。
                try {
                    if (DeviceBridgeService.isAdbEnabled(HarnessService.this)
                            && !DeviceBridgeService.isRunning()) {
                        android.util.Log.w("DSHA", "[保活] 设备桥服务不在了，重新拉起");
                        DeviceBridgeService.apply(HarnessService.this);
                    }
                } catch (Throwable ignored) {
                }
                if (isWebUp()) {
                    fail = 0;
                    continue;
                }
                fail++;
                if (fail < KEEPALIVE_MAX_FAIL) continue;
                fail = 0;
                long now = System.currentTimeMillis();
                // 手动停止 / 会话自愈 / 刚停过的冷却期，统一判据在 shouldAutoStartWeb
                if (!c.shouldAutoStartWeb("保活")) continue;
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
        // taskNotifier 已停用（插件方案）
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
