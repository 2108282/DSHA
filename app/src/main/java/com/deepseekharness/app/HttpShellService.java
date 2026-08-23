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
    /** IPv6 回环监听（兼容脚本用 localhost 解析成 ::1 的场景；绑不上则忽略） */
    private ServerSocket server6;
    private volatile boolean running;
    /** 连接处理线程池（请求可能阻塞等用户确认 60s，必须并发处理，否则一个确认卡死全部请求） */
    private java.util.concurrent.ExecutorService pool;
    /** 鉴权 token（随机生成，rootfs 内 agent 通过它访问；外部网络无法到达 127.0.0.1）。
     *  每次 start 都会和 rootfs 文件对账：文件存在则沿用，缺失/内容异常则轮换重写，
     *  防止重解压 rootfs 后内存 token 与文件不一致导致 agent 无法认证。 */
    private static volatile String authToken = "";
    /** token 持久化位置（rootfs 内 agent 可读，建议 0600） */
    private static final String TOKEN_FILE = "/root/.dsh/.bridge_token";

    public HttpShellService(Context ctx) {
        this.ctx = ctx;
    }

    public static HttpShellService instance() {
        return instance;
    }

    private static java.io.File tokenFileIfPossible() {
        try {
            HarnessController hc = HarnessController.get(instance().ctx);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                return new java.io.File(hc.getProot().getRootfsDir(), "root/.dsh/.bridge_token");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读取 rootfs 内 token 文件（只读，不修改内容）。 */
    private static String readTokenFromFile(java.io.File tf) {
        if (tf == null || !tf.isFile()) return null;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(tf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty() || s.length() > 128) return null;
            // 只允许可安全放入 URL/Header 的一半字符，拒绝换行等脏内容
            if (!s.matches("[A-Za-z0-9_-]+")) return null;
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 生成/对账 token（rootfs 文件优先；缺失或无效则轮换并写入）。
     *  注：token 属于 rootfs 内 agent 访问 3090 桥的共享凭据，不做 0600 之外的额外加密。 */
    private static String ensureToken() {
        synchronized (HttpShellService.class) {
            java.io.File tf = tokenFileIfPossible();
            String fromFile = readTokenFromFile(tf);
            if (fromFile != null && !fromFile.isEmpty()) {
                authToken = fromFile;
                return authToken;
            }
            // 无文件或内容无效 → 轮换（不能用旧内存值，否则 agent 读到的文件永远不会出现）
            String t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            authToken = t;
            if (tf != null) {
                try {
                    if (tf.getParentFile() != null) tf.getParentFile().mkdirs();
                    java.nio.file.Files.write(tf.toPath(), t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try {
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                        java.nio.file.Files.setPosixFilePermissions(tf.toPath(),
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                }
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
                // 安全：仅绑定回环（loopback），外部网络无法访问！
                // 关键：必须显式绑 IPv4 127.0.0.1 —— InetAddress.getLoopbackAddress()
                // 在 Android（IPv6 优先）上返回 ::1，桥只监听 [::1]:3090，而 rootfs 内
                // 所有客户端（adb-shell.py / dsh-confirm.sh / 内置插件）都连 127.0.0.1
                // → Connection refused → 确认弹窗永不出现，命令被判 USER_REJECTED。
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), PORT));
                acceptLoop(server);
            } catch (IOException ignored) {
            }
        }, "http-shell-accept");
        t.setDaemon(true);
        t.start();
        // 附加监听 [::1]:3090：脚本/插件若用 localhost（可能解析成 IPv6）也能命中。
        // 绑不上（无 IPv6 栈/被占）时静默跳过，IPv4 主监听已足够。
        Thread t6 = new Thread(() -> {
            try {
                server6 = new ServerSocket();
                server6.setReuseAddress(true);
                server6.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("::1"), PORT));
                acceptLoop(server6);
            } catch (Throwable ignored) {
            }
        }, "http-shell-accept6");
        t6.setDaemon(true);
        t6.start();
    }

    /** 接受连接并分发到线程池（IPv4/IPv6 两个监听共用） */
    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket client = ss.accept();
                client.setSoTimeout(120_000);
                java.util.concurrent.ExecutorService p = pool;
                if (p == null) {
                    try { client.close(); } catch (IOException ignored) { }
                    return;
                }
                p.execute(() -> handle(client));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    public void stop() {
        running = false;
        instance = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (server6 != null) server6.close();
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

    /** 校验查询串/头中的 token（常量时间比较 + URL 解码容错） */
    private static boolean tokenMatch(String presented) {
        String token = authToken.isEmpty() ? ensureToken() : authToken;
        return token != null && !token.isEmpty() && constantTimeEquals(token, presented);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        int diff = a.length() ^ b.length();
        for (int i = 0; i < a.length(); i++) {
            char ca = a.charAt(i);
            char cb = i < b.length() ? b.charAt(i) : 0;
            diff |= ca ^ cb;
        }
        return diff == 0;
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
            // 只认 query 中的 token（EXCLUSIVE：跳过 path 其他位置的 token=）
            int qm = path.indexOf('?');
            String query = qm >= 0 ? path.substring(qm + 1) : "";
            int tq = query.indexOf("token=");
            if (tq >= 0) {
                t = query.substring(tq + 6);
                int amp = t.indexOf('&');
                if (amp >= 0) t = t.substring(0, amp);
                try { t = URLDecoder.decode(t, "UTF-8"); } catch (Exception ignored) { }
                if (!t.isEmpty()) authed = tokenMatch(t.trim());
            }
            if (!authed) {
                // 也支持 header 传 token（agent 引导用 curl -H）
                try {
                    String hdr;
                    while ((hdr = reader.readLine()) != null && !hdr.isEmpty()) {
                        if (hdr.toLowerCase().startsWith("x-token:")) {
                            String hv = hdr.substring(8).trim();
                            if (!hv.isEmpty() && tokenMatch(hv)) authed = true;
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
                // force=1（adb-shell 报备）→ 所有命令都确认；否则仅危险命令
                boolean force = path.contains("force=1");
                boolean needConfirm = force || (confirmEnabled() && DangerShellGuard.isDangerous(cmd));
                result = needConfirm ? (requestUserConfirm(cmd) ? "YES" : "NO") : "YES";
            } else if (DangerShellGuard.isDangerous(cmd) && confirmEnabled()) {
                result = awaitConfirm(cmd);
            } else {
                result = ShizukuShell.exec(cmd);
            }
            // 关键：result 必须包引号 —— 旧实现输出 {"result":YES} 是非法 JSON，
            // 客户端（adb-shell.py 判 '"YES"' in body / agent 用 json 解析）全部失效：
            // 用户点「允许」也会被当成拒绝。
            String body = "{\"result\":\"" + jsonEscape(result) + "\"}";
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
            // App 前台时不发通知（用户正看着页面，不打扰）——与 TaskNotifier 抑制一致
            if (TaskNotifier.appInForeground) return "FOREGROUND_SKIP";
            String q = queryOf(path);
            String title = getParam(q, "title", "DSHA 通知");
            String text = getParam(q, "text", "");
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
            final String text = getParam(queryOf(path), "text", "");
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

    /** /app/readfile?path= ：读外部文件（文本，限制 256KB）。路径如 /sdcard/Download/x.txt
     *  安全：禁止读凭据文件（.env / .bridge_token / settings.yaml —— 含 API key/对话密钥）。 */
    private String appReadFile(String path) {
        try {
            String p = getParam(queryOf(path), "path", "");
            if (p.isEmpty()) return "NO_PATH";
            String lower = p.toLowerCase();
            if (lower.endsWith("/.env") || lower.contains("/.env/")
                    || lower.contains(".bridge_token") || lower.contains("settings.yaml")) {
                return "FORBIDDEN: 凭据文件不可读（.env/.bridge_token/settings.yaml）";
            }
            java.io.File f = new java.io.File(p);
            // 只允许读取外部存储（/sdcard 或 /storage/emulated/0）：
            // 否则 agent 可绕过过滤直接读 App 私有目录（SharedPreferences 里含 API key）
            String canon;
            try {
                canon = f.getCanonicalPath();
            } catch (Exception e) {
                return "FORBIDDEN: 路径无法解析（" + p + "）";
            }
            boolean external = canon.startsWith("/sdcard/") || canon.startsWith("/storage/emulated/0/");
            if (!external && !canon.startsWith("/sdcard") && !canon.startsWith("/storage/emulated/0")) {
                return "FORBIDDEN: 仅允许读取 /sdcard 外部存储（" + p + "）";
            }
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

/** 从（仅含 query 的）查询串提取参数。调用方务必先截取 '?' 之后的内容。 */
    private static String getParam(String q, String key, String def) {
        try {
            int i = q.indexOf(key + "=");
            if (i < 0) return def;
            String v = q.substring(i + key.length() + 1);
            int amp = v.indexOf('&');
            if (amp >= 0) v = v.substring(0, amp);
            int frag = v.indexOf('#');
            if (frag >= 0) v = v.substring(0, frag);
            return URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return def;
        }
    }

    // 便捷包装：路径中取 query 部分
    private static String queryOf(String path) {
        int i = path.indexOf('?');
        return i >= 0 ? path.substring(i + 1) : "";
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
