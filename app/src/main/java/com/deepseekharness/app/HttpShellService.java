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
    private static final int CONFIRM_NOTIF_ID = 3003;
    private static final long CONFIRM_TIMEOUT_S = 60;

    private static volatile HttpShellService instance;

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile CountDownLatch pendingLatch;
    private volatile boolean pendingAllow;

    private ServerSocket server;
    private volatile boolean running;

    public HttpShellService(Context ctx) {
        this.ctx = ctx;
    }

    public static HttpShellService instance() {
        return instance;
    }

    public void start() {
        if (running) return;
        running = true;
        instance = this;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (running) {
                    try {
                        Socket client = server.accept();
                        handle(client);
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "http-shell");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        instance = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        // 释放挂起的确认（默认拒绝）
        CountDownLatch l = pendingLatch;
        if (l != null) l.countDown();
        cancelConfirmNotification();
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = reader.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String cmd = "";
            if (path.startsWith("/exec")) {
                int q = path.indexOf("cmd=");
                if (q >= 0) {
                    cmd = URLDecoder.decode(path.substring(q + 4), "UTF-8");
                }
            }
            String result;
            if (cmd.isEmpty()) {
                result = "[NO_CMD]";
            } else if (path.startsWith("/confirm")) {
                // rootfs 内包装器请求的确认：只弹窗，不执行
                result = (confirmEnabled() && DangerShellGuard.isDangerous(cmd))
                        ? (requestUserConfirm(cmd) ? "YES" : "NO")
                        : "YES";
            } else if (DangerShellGuard.isDangerous(cmd) && confirmEnabled()) {
                result = awaitConfirm(cmd);
            } else {
                result = ShizukuShell.exec(cmd);
            }
            String body = "{\"result\":" + jsonEscape(result) + "}";
            byte[] bodyBytes = body.getBytes("UTF-8");
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            c.getOutputStream().write(head.getBytes("UTF-8"));
            c.getOutputStream().write(bodyBytes);
            c.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    private boolean confirmEnabled() {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean("confirm_shell", true);
    }

    /** 危险命令：挂起等待用户确认（前台弹窗 / 后台通知），超时默认拒绝 */
    private String awaitConfirm(String cmd) {
        return requestUserConfirm(cmd) ? ShizukuShell.exec(cmd) : "[USER_REJECTED]";
    }

    /** 只请求用户确认（不执行命令），返回是否允许；/confirm 端点用 */
    private boolean requestUserConfirm(String cmd) {
        CountDownLatch latch = new CountDownLatch(1);
        pendingLatch = latch;
        pendingAllow = false;

        MainActivity act = MainActivity.current;
        if (act != null) {
            // 前台：App 内弹窗
            final String prompt = "模型试图在设备上执行：\n" + cmd + "\n\n是否允许？";
            act.runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("DSHA 安全确认")
                    .setMessage(prompt)
                    .setPositiveButton("允许", (d, w) -> {
                        pendingAllow = true;
                        CountDownLatch l = pendingLatch;
                        if (l != null) l.countDown();
                    })
                    .setNegativeButton("拒绝", (d, w) -> {
                        CountDownLatch l = pendingLatch;
                        if (l != null) l.countDown();
                    })
                    .setOnCancelListener(d -> {
                        CountDownLatch l = pendingLatch;
                        if (l != null) l.countDown();
                    })
                    .setOnDismissListener(d -> {
                        CountDownLatch l = pendingLatch;
                        if (l != null) l.countDown();
                    })
                    .show());
        } else {
            // 后台：高优先级通知 + 允许/拒绝按钮
            showConfirmNotification(cmd);
        }

        try {
            boolean finished = latch.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
            pendingLatch = null;
            if (!finished) {
                cancelConfirmNotification();
                return false;
            }
            return pendingAllow;
        } catch (InterruptedException e) {
            pendingLatch = null;
            return false;
        }
    }

    /** 通知按钮回调（ConfirmReceiver） */
    public void resolveConfirm(boolean allow) {
        pendingAllow = allow;
        CountDownLatch l = pendingLatch;
        if (l != null) l.countDown();
        cancelConfirmNotification();
    }

    private void showConfirmNotification(String cmd) {
        createConfirmChannel();
        String shortCmd = cmd.length() > 100 ? cmd.substring(0, 100) + "…" : cmd;
        Intent allowI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_ALLOW);
        Intent denyI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_DENY);
        PendingIntent allowPi = PendingIntent.getBroadcast(ctx, 31, allowI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent denyPi = PendingIntent.getBroadcast(ctx, 32, denyI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("DSHA 安全确认")
                .setContentText("模型试图执行：" + shortCmd)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("模型试图在设备上执行：\n" + cmd + "\n\n是否允许？"))
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
