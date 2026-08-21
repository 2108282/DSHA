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

    private static volatile HttpShellService instance;

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile CountDownLatch pendingLatch;
    private volatile boolean pendingAllow;
    /** 确认进行中标志：并发确认请求直接拒绝（避免 latch 覆盖导致"点了允许却拒绝"） */
    private volatile boolean confirmBusy = false;

    private ServerSocket server;
    private volatile boolean running;
    /** 连接处理线程池（请求可能阻塞等用户确认 60s，必须并发处理，否则一个确认卡死全部请求） */
    private java.util.concurrent.ExecutorService pool;
    /** 鉴权 token（随机生成，rootfs 内 agent 通过它访问；外部网络无法到达 127.0.0.1） */
    private static volatile String authToken = "";
    /** token 持久化位置（rootfs 内 agent 可读） */
    private static final String TOKEN_FILE = "/root/.dsh/.bridge_token";

    public HttpShellService(Context ctx) {
        this.ctx = ctx;
    }

    public static HttpShellService instance() {
        return instance;
    }

    /** 生成/读取鉴权 token，并写入 rootfs（agent 读取用）。
     *  注意：路径是 rootfs 内的 /root/.dsh/.bridge_token，App 写的是
     *  rootfs 实际目录（files/linux/ubuntu/root/.dsh/）。 */
    private static String ensureToken() {
        if (!authToken.isEmpty()) return authToken;
        synchronized (HttpShellService.class) {
            // 双检：并发请求可能同时进 ensureToken，不加锁会生成两个 token，
            // 一个覆盖另一个 → 内存 token 与 rootfs 文件不一致 → agent 认证失败
            if (!authToken.isEmpty()) return authToken;
            try {
                String t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
                authToken = t;
                // 写入 rootfs（通过 HarnessController 拿 rootfs 路径；失败不影响运行）
                try {
                    HarnessController hc = HarnessController.get(instance().ctx);
                    if (hc != null && hc.getProot() != null) {
                        java.io.File tf = new java.io.File(hc.getProot().getRootfsDir(),
                                "root/.dsh/.bridge_token");
                        if (tf.getParentFile() != null) tf.getParentFile().mkdirs();
                        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tf)) {
                            fo.write(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            return authToken;
        }
    }

    public void start() {
        if (running) return;
        running = true;
        instance = this;
        ensureToken();
        // 固定小线程池：请求可能挂起等用户确认（60s），串行处理会互相阻塞
        pool = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http-shell");
            t.setDaemon(true);
            return t;
        });
        Thread t = new Thread(() -> {
            try {
                // 安全：仅绑定 127.0.0.1（loopback），外部网络无法访问！
                // （原来 new ServerSocket(PORT) 默认绑 0.0.0.0 = 局域网可访问 → 严重漏洞）
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getLoopbackAddress(), PORT));
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setSoTimeout(120_000);
                        pool.execute(() -> handle(client));
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "http-shell-accept");
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
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
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
            if (path.startsWith("/exec") || path.startsWith("/confirm")) {
                int q = path.indexOf("cmd=");
                if (q >= 0) {
                    // 关键：cmd= 值要截断到 &（查询串可能有多个参数，如
                    // /exec?cmd=ls&token=xxx）。旧实现 substring(q+4) 取到末尾，
                    // cmd 会变成 "ls&token=xxx" → 执行错命令！
                    String cv = path.substring(q + 4);
                    int amp = cv.indexOf('&');
                    if (amp >= 0) cv = cv.substring(0, amp);
                    cmd = URLDecoder.decode(cv, "UTF-8");
                }
            }
            // 鉴权：token 必须匹配（通过 ?token= 或 X-Token header）
            boolean authed = false;
            String t = "";
            int tq = path.indexOf("token=");
            if (tq >= 0) {
                t = path.substring(tq + 6);
                int amp = t.indexOf('&');
                if (amp >= 0) t = t.substring(0, amp);
                try { t = URLDecoder.decode(t, "UTF-8"); } catch (Exception ignored) { }
            }
            String token = authToken.isEmpty() ? ensureToken() : authToken;
            if (!t.isEmpty() && token.equals(t)) authed = true;
            if (!authed) {
                // 也支持 header 传 token（agent 引导用 curl -H）
                try {
                    String hdr;
                    while ((hdr = reader.readLine()) != null && !hdr.isEmpty()) {
                        if (hdr.toLowerCase().startsWith("x-token:")) {
                            String hv = hdr.substring(8).trim();
                            if (token.equals(hv)) authed = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            String result;
            if (!authed) {
                result = "[UNAUTHORIZED]";
            } else if (path.startsWith("/app/notify")) {
                // agent 通过 App 发通知栏提醒（App 层交互）
                result = appNotify(path);
            } else if (path.startsWith("/app/toast")) {
                // agent 弹 App 内 Toast
                result = appToast(path);
            } else if (path.startsWith("/app/readfile")) {
                // agent 读外部文件（rootfs 挂载 /sdcard 的补充；支持路径参数）
                result = appReadFile(path);
            } else if (cmd.isEmpty()) {
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

    // ================= App 层交互端点（agent 通过 3090 桥调用） =================

    /** /app/notify?title=&text= ：发通知栏提醒 */
    private String appNotify(String path) {
        try {
            String title = getParam(path, "title", "DSHA 通知");
            String text = getParam(path, "text", "");
            if (text.isEmpty()) return "NO_TEXT";
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NO_SERVICE";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "dsh_agent_channel", "Agent 通知",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("智能体通过 App 发送的通知");
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "dsh_agent_channel")
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);
            nm.notify(2002, b.build());
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** /app/toast?text= ：弹 App 内 Toast */
    private String appToast(String path) {
        try {
            final String text = getParam(path, "text", "");
            if (text.isEmpty()) return "NO_TEXT";
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** /app/readfile?path= ：读外部文件（文本，限制 256KB）。路径如 /sdcard/Download/x.txt */
    private String appReadFile(String path) {
        try {
            String p = getParam(path, "path", "");
            if (p.isEmpty()) return "NO_PATH";
            java.io.File f = new java.io.File(p);
            if (!f.isFile()) return "NOT_FOUND: " + p;
            if (f.length() > 256 * 1024) return "TOO_LARGE: " + f.length();
            byte[] bytes = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** 从查询串提取参数（已 URL 解码） */
    private static String getParam(String path, String key, String def) {
        try {
            int i = path.indexOf(key + "=");
            if (i < 0) return def;
            String v = path.substring(i + key.length() + 1);
            int amp = v.indexOf('&');
            if (amp >= 0) v = v.substring(0, amp);
            return URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return def;
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
        if (confirmBusy) return false; // 已有确认在进行：拒绝新的（避免状态覆盖）
        confirmBusy = true;
        try {
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
        } finally {
            confirmBusy = false;
            pendingLatch = null;
            cancelConfirmNotification();
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
                .setContentTitle("⚠️ DSHA 安全确认")
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
