package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 极简 HTTP 服务（host 侧，端口 3090），把 Shizuku shell 能力桥接给 rootfs 里的助手。
 * rootfs 内的 agent 可用 bash 工具执行：
 *   curl -s "http://127.0.0.1:3090/exec?cmd=<urlencoded>"
 * 返回 JSON：{"result":"...输出...[EXIT=0]"}
 *
 * 安全：命中危险命令（删除/格式化/卸载/重启等）时，若设置开启"需确认"，
 * 前台弹窗 / 后台高优先级通知（允许/拒绝按钮），60 秒超时默认拒绝。
 */
public final class HttpShellService {

    public static final int PORT = 3090;
    private static final String CONFIRM_CHANNEL = "dsh_confirm_channel";
    private static final int CONFIRM_NOTIF_ID = Constants.NOTIF_SHELL_CONFIRM;
    private static final long CONFIRM_TIMEOUT_S = 60;

    /** 确认状态机：互斥锁、epoch 序号、原子认领与结果标志 */
    private final java.util.concurrent.atomic.AtomicBoolean confirmBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong confirmEpoch = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean confirmResolved = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean pendingAllow = false;
    private volatile java.util.concurrent.CountDownLatch pendingLatch;
    private volatile androidx.appcompat.app.AlertDialog pendingDialog;

    private boolean requestUserConfirm(String cmd) {
        if (!confirmBusy.compareAndSet(false, true)) {
            return false; // 已有确认在进行：拒绝新的（避免 pendingLatch 互相覆盖）
        }
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            // epoch 先递增：上一轮残留的弹窗/通知按钮带的是旧 epoch，会被丢弃
            final long myEpoch = confirmEpoch.incrementAndGet();
            pendingAllow = false;
            confirmResolved.set(false);
            pendingLatch = latch;

            showConfirmNotification(cmd, myEpoch);
            OverlayController.askConfirm(ctx, safeDisplay(cmd),
                    () -> resolveConfirm(true, myEpoch),
                    () -> resolveConfirm(false, myEpoch));
            final MainActivity act = MainActivity.current;
            if (act != null) {
                final String prompt = "模型试图在设备上执行：
" + safeDisplay(cmd) + "

是否允许？";
                act.runOnUiThread(() -> {
                    try {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        pendingDialog = new androidx.appcompat.app.AlertDialog.Builder(act)
                                .setTitle("安全确认")
                                .setMessage(prompt)
                                .setCancelable(false)
                                .setPositiveButton("允许", (d, w) -> resolveConfirm(true, myEpoch))
                                .setNegativeButton("拒绝", (d, w) -> resolveConfirm(false, myEpoch))
                                .show();
                    } catch (Throwable t) {
                        android.util.Log.w("DSHA", "确认弹窗弹出失败，仍可从通知确认：" + safeError(t));
                    }
                });
            }

            try {
                boolean finished = latch.await(CONFIRM_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
                return finished && pendingAllow;
            } catch (InterruptedException e) {
                return false;
            }
        } finally {
            pendingLatch = null;
            dismissConfirmDialog();
            cancelConfirmNotification();
            OverlayController.dismissConfirm(ctx);
            confirmBusy.set(false);
        }
    }

    private boolean notificationsEnabled() {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // framework API 24+，比运行时权限检查更准（用户在设置里关掉通知也算）
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable e) {
            return true; // 判断不了就别妄下结论
        }
    }

    /** 通知按钮（ConfirmReceiver）、前台弹窗按钮与悬浮条按钮共用的回调。
     *  epoch 校验 + 原子认领：丢弃迟到的（属于上一个请求的）点击，以及同一轮里后到的那次。 */
    public void resolveConfirm(boolean allow, long epoch) {
        if (epoch != confirmEpoch.get()) {
            android.util.Log.i("DSHA", "忽略过期的确认点击（epoch " + epoch + "）");
            return;
        }
        CountDownLatch l = pendingLatch;
        if (l == null || l.getCount() == 0) return; // 已决或无挂起（快速路径）
        // 真正的认领在这里，且必须原子 —— 上面那个 getCount 检查挡不住两条渠道同时点。
        if (!confirmResolved.compareAndSet(false, true)) return;
        pendingAllow = allow;
        l.countDown();
        dismissConfirmDialog();
        cancelConfirmNotification();
    }

    /** 关掉挂起的弹窗：setCancelable(false) 让它自己关不掉，确认完成后必须主动 dismiss，
     *  否则它会滞留在屏幕上，用户后来点它就把授权打到下一个请求上了。
     *  先把引用摘到局部变量再置 null，这样即使下一个请求已设好新弹窗也不会误关它。 */
    private void dismissConfirmDialog() {
        final androidx.appcompat.app.AlertDialog d = pendingDialog;
        if (d == null) return;
        pendingDialog = null;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    if (d.isShowing()) d.dismiss();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void showConfirmNotification(String cmd, long epoch) {
        createConfirmChannel();
        String displayCmd = safeDisplay(cmd);
        String shortCmd = displayCmd.length() > 100 ? displayCmd.substring(0, 100) + "…" : displayCmd;
        // epoch 随 Intent 带回：残留通知上的旧按钮会因 epoch 过期被丢弃
        Intent allowI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_ALLOW)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        Intent denyI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_DENY)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        PendingIntent allowPi = PendingIntent.getBroadcast(ctx, 31, allowI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent denyPi = PendingIntent.getBroadcast(ctx, 32, denyI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("⚠️ DSHA 安全确认")
                .setContentText("模型试图执行：" + shortCmd)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("模型试图在设备上执行：\n" + displayCmd + "\n\n是否允许？"))
                .addAction(0, "允许", allowPi)
                .addAction(0, "拒绝", denyPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CONFIRM_NOTIF_ID, n);
    }

    private void cancelConfirmNotification() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CONFIRM_NOTIF_ID);
    }

    private void createConfirmChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CONFIRM_CHANNEL, "安全确认",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("模型执行危险操作时的确认提醒");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
