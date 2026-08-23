package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 HTTP 服务（host 侧，端口 3090），把 Shizuku shell 能力桥接给 rootfs 里的助手。
 * rootfs 内的 agent 可用 bash 工具执行：
 *   curl -s "http://127.0.0.1:3090/exec?cmd=<urlencoded>&token=$(cat /root/.dsh/.bridge_token)"
 * 返回 JSON：{"result":"...输出...[EXIT=0]"}
 *
 * 端点：
 *   /health  存活探测（仍需 token），返回 {"result":"OK"}
 *   /exec    执行命令；命中危险命令且开关开启时先确认
 *   /confirm 只问用户、不执行（rootfs 内包装器/adb-shell.py 用）
 *
 * 安全：需确认时前台弹窗 + 通知（允许/拒绝按钮）同时发，60 秒超时默认拒绝。
 */
public final class HttpShellService {

    public static final int PORT = Constants.SHELL_BRIDGE_PORT;
    private static final String CONFIRM_CHANNEL = Constants.CHANNEL_SHELL_CONFIRM;
    private static final int CONFIRM_NOTIF_ID = Constants.NOTIF_SHELL_CONFIRM;
    private static final long CONFIRM_TIMEOUT_S = 60;
    private static final String TAG = "DSHA-Bridge";

    private static volatile HttpShellService instance;
    /** 全局"已有桥在监听"标志。HarnessService 与 DeviceBridgeService 各自 new 一个
     *  实例并都调 start()，实例字段 running 挡不住跨实例的重复启动——第二个实例会
     *  因端口占用绑定失败，进而把活着的那个从 instance 里抹掉（通知按钮全废）。 */
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final Context ctx;
    private volatile CountDownLatch pendingLatch;
    private volatile boolean pendingAllow;
    /** 本实例是否真正持有监听：只有持有者的 stop() 才做清理，
     *  否则第二个（没绑上端口的）实例一被销毁就会把真桥的状态清掉。 */
    private volatile boolean owner;
    /** 每次确认的序号：用来判定一次「允许/拒绝」点击属于哪个请求。
     *  没有它的话，上一个请求残留的弹窗/通知按钮会把授权决定打到下一个请求上。 */
    private final AtomicLong confirmEpoch = new AtomicLong();
    /** 当前挂起的弹窗，确认完成后要主动 dismiss（setCancelable(false) 关不掉它） */
    private volatile androidx.appcompat.app.AlertDialog pendingDialog;
    /** 确认进行中标志：并发确认请求直接拒绝（避免 latch 覆盖导致"点了允许却拒绝"）。
     *  用 AtomicBoolean 而不是 volatile boolean——"检查后置位"必须原子，否则两个
     *  请求线程可能同时通过检查、互相覆盖 pendingLatch。 */
    private final AtomicBoolean confirmBusy = new AtomicBoolean(false);

    /** 双栈监听：Android 上 getLoopbackAddress() 可能只返回 ::1，而 rootfs 内的
     *  客户端脚本大多写死 127.0.0.1 —— 只听一边会让确认链路整条断掉。
     *  volatile：与 workers 一致，避免 start/stop 分处不同线程时读到 stale 值。 */
    private volatile ServerSocket server4;
    private volatile ServerSocket server6;
    /** 每连接一个工作线程：确认请求会挂起最长 60s，串行 handle 会把整个桥堵死。
     *  volatile：accept 线程读、调用 stop() 的线程写。 */
    private volatile ExecutorService workers;
    private volatile boolean running;
    /** 鉴权 token（随机生成，rootfs 内 agent 通过它访问；外部网络无法到达回环） */
    private static volatile String authToken = "";
    /** token 持久化位置（rootfs 内 agent 可读） */
    private static final String TOKEN_FILE = "/root/.dsh/.bridge_token";

    public HttpShellService(Context ctx) {
        this.ctx = ctx;
    }

    public static HttpShellService instance() {
        return instance;
    }

    /** 生成 token 并确保 rootfs 内的 token 文件与内存一致。
     *  每次 start() 都校验文件：rootfs 被重建 / 恢复备份后文件会丢，
     *  若只在首次生成时写，客户端就永久拿不到 token（一律 UNAUTHORIZED）。 */
    private static String ensureToken() {
        if (authToken.isEmpty()) {
            try {
                authToken = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            } catch (Throwable ignored) {
            }
        }
        writeTokenToRootfs(authToken);
        return authToken;
    }

    /** 写 token 到 rootfs（agent 读取用），内容已一致则跳过。
     *  注意：路径是 rootfs 内的 /root/.dsh/.bridge_token，App 写的是
     *  rootfs 实际目录（files/linux/ubuntu/root/.dsh/）。 */
    private static void writeTokenToRootfs(String token) {
        if (token == null || token.isEmpty()) return;
        try {
            HttpShellService self = instance;
            if (self == null) return;
            HarnessController hc = HarnessController.get(self.ctx);
            if (hc == null || hc.getProot() == null) return;
            java.io.File tf = new java.io.File(hc.getProot().getRootfsDir(),
                    TOKEN_FILE.substring(1));
            if (tf.isFile()) {
                try {
                    String cur = new String(java.nio.file.Files.readAllBytes(tf.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (token.equals(cur)) return; // 已一致，不必重写
                } catch (Throwable ignored) {
                }
            }
            if (tf.getParentFile() != null) tf.getParentFile().mkdirs();
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tf)) {
                fo.write(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    public void start() {
        if (running) return;
        // 跨实例守卫：已有桥在监听就直接放弃，不去抢端口、更不碰 instance。
        // （两个 Service 各 new 一个实例，谁先起谁持有）
        if (!STARTED.compareAndSet(false, true)) {
            android.util.Log.i(TAG, "3090 桥已由另一个实例持有，本次 start 跳过");
            return;
        }
        running = true;
        owner = true;
        instance = this;
        ensureToken();
        workers = Executors.newCachedThreadPool(r -> {
            Thread w = new Thread(r, "http-shell-work");
            w.setDaemon(true);
            return w;
        });
        // 安全：仅绑定回环地址，外部网络无法访问！
        // （原来 new ServerSocket(PORT) 默认绑 0.0.0.0 = 局域网可访问 → 严重漏洞）
        // 双栈：IPv4 127.0.0.1 与 IPv6 ::1 都听，客户端写哪个地址都连得上。
        server4 = bindLoopback(loopback4(), "IPv4 127.0.0.1");
        server6 = bindLoopback(loopback6(), "IPv6 [::1]");
        if (server4 == null && server6 == null) {
            running = false;
            owner = false;
            if (instance == this) instance = null; // 只清自己，别抹掉别人的活实例
            ExecutorService pool = workers;
            workers = null;
            if (pool != null) pool.shutdownNow();
            STARTED.set(false);
            android.util.Log.e(TAG, "3090 桥启动失败：IPv4/IPv6 回环都绑不上");
            return;
        }
        if (server4 != null) startAcceptLoop(server4, "http-shell");
        if (server6 != null) startAcceptLoop(server6, "http-shell6");
    }

    /** 127.0.0.1（显式字节，不经名字解析——localhost 在 Android 上会解析到 ::1） */
    private static InetAddress loopback4() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (Throwable e) {
            return null;
        }
    }

    /** ::1（16 字节，末位 1） */
    private static InetAddress loopback6() {
        try {
            return InetAddress.getByAddress(
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        } catch (Throwable e) {
            return null;
        }
    }

    /** 绑一个回环地址。失败只记日志返回 null（部分设备没有 IPv6 栈），
     *  是否致命由调用方判断（两个都绑不上才算启动失败）。
     *  catch Throwable：受限环境下 bind 可能抛 SecurityException，
     *  而 HarnessService.onCreate 那侧没有 try/catch 兜底，逃出去就崩服务。 */
    private ServerSocket bindLoopback(InetAddress addr, String label) {
        if (addr == null) return null;
        ServerSocket s = null;
        try {
            s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(addr, PORT));
            android.util.Log.i(TAG, "3090 桥已监听 " + label);
            return s;
        } catch (Throwable e) {
            closeQuietly(s); // 别泄漏半开的 fd
            android.util.Log.w(TAG, "3090 绑定失败(" + label + ")：" + e);
            return null;
        }
    }

    private void startAcceptLoop(final ServerSocket s, String threadName) {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket client = s.accept();
                    // 交给线程池：确认请求最长挂起 60s，不能占住 accept 循环
                    ExecutorService w = workers;
                    if (w == null) {
                        closeQuietly(client);
                        break;
                    }
                    try {
                        w.execute(() -> handle(client));
                    } catch (Throwable rejected) {
                        closeQuietly(client);
                    }
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        // 非持有者（start 时被跨实例守卫挡掉的那个）什么都不该动：
        // 否则 HarnessService.onDestroy 会把 DeviceBridgeService 那个活桥的状态清掉。
        if (!owner) return;
        owner = false;
        running = false;
        if (instance == this) instance = null;
        closeQuietly(server4);
        closeQuietly(server6);
        server4 = null;
        server6 = null;
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
        // 释放挂起的确认（默认拒绝）
        pendingAllow = false;
        CountDownLatch l = pendingLatch;
        if (l != null) l.countDown();
        dismissConfirmDialog();
        cancelConfirmNotification();
        STARTED.set(false);
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = reader.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";

            // 鉴权：token 必须匹配（通过 ?token= 或 X-Token header）
            String token = authToken;
            if (token.isEmpty()) token = ensureToken();
            String t = queryParam(path, "token");
            boolean authed = !t.isEmpty() && token.equals(t);
            if (!authed) {
                // 也支持 header 传 token（agent 引导用 curl -H）
                try {
                    String hdr;
                    while ((hdr = reader.readLine()) != null && !hdr.isEmpty()) {
                        if (hdr.toLowerCase().startsWith("x-token:")) {
                            if (token.equals(hdr.substring(8).trim())) authed = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // cmd 必须按 & 切分取：原来 indexOf("cmd=") 直接取到串尾，会把后面的
            // &token=xxx 一起当成命令（弹窗里泄露 token，/exec 还会把它当后台任务执行）
            String cmd = "";
            if (path.startsWith("/exec") || path.startsWith("/confirm")) {
                cmd = queryParam(path, "cmd");
            }

            String result;
            if (!authed) {
                result = "[UNAUTHORIZED]";
            } else if (path.startsWith("/health")) {
                result = "OK";
            } else if (cmd.isEmpty()) {
                result = "[NO_CMD]";
            } else if (path.startsWith("/confirm")) {
                // rootfs 内包装器请求的确认：只弹窗，不执行。
                // 客户端主动来问 = 它已判定该命令需要授权，所以这里不再用
                // DangerShellGuard 二次过滤——宿主名单与 rootfs 包装侧的正则是两套
                // 规则，靠它过滤会让"客户端认为危险、App 认为不危险"的命令静默放行。
                result = !confirmEnabled() ? "YES" : (requestUserConfirm(cmd) ? "YES" : "NO");
            } else if (DangerShellGuard.isDangerous(cmd) && confirmEnabled()) {
                result = awaitConfirm(cmd);
            } else {
                result = ShizukuShell.exec(cmd);
            }
            // exec 走 AIDL 跨进程，远端可能回 null；不兜住的话 jsonQuote 抛 NPE
            // 被下面的 catch 吞掉 → 一个字节都不回，客户端只看到"桥无响应"
            if (result == null) result = "[NULL_RESULT]";
            String body = "{\"result\":" + jsonQuote(result) + "}";
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

    /** 取查询参数：按 & 切分后精确匹配键名再 URL 解码；没有则返回空串。 */
    private static String queryParam(String path, String key) {
        if (path == null) return "";
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0 || !pair.substring(0, eq).equals(key)) continue;
            String v = pair.substring(eq + 1);
            try {
                return URLDecoder.decode(v, "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return "";
    }

    private boolean confirmEnabled() {
        return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_CONFIRM_SHELL, true);
    }

    /** 危险命令：挂起等待用户确认（弹窗 + 通知），超时默认拒绝 */
    private String awaitConfirm(String cmd) {
        return requestUserConfirm(cmd) ? ShizukuShell.exec(cmd) : "[USER_REJECTED]";
    }

    /** 只请求用户确认（不执行命令），返回是否允许；/confirm 端点用。
     *  弹窗与通知同时发：只走弹窗的话，Activity 一被 pause 用户就再也看不见，
     *  只能干等 60s 超时（这正是"弹窗出现与否不稳定"的由来）。 */
    private boolean requestUserConfirm(String cmd) {
        if (!confirmBusy.compareAndSet(false, true)) {
            return false; // 已有确认在进行：拒绝新的（避免 pendingLatch 互相覆盖）
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            // epoch 先递增：上一轮残留的弹窗/通知按钮带的是旧 epoch，会被丢弃，
            // 不会把授权决定打到这个新请求上。
            final long myEpoch = confirmEpoch.incrementAndGet();
            pendingAllow = false;   // 先写标志，再发布 latch
            pendingLatch = latch;

            // 通知是权威渠道（前后台都在），前台再叠一个弹窗当快捷方式
            showConfirmNotification(cmd, myEpoch);
            final MainActivity act = MainActivity.current;
            if (act != null) {
                final String prompt = "模型试图在设备上执行：\n" + cmd + "\n\n是否允许？";
                act.runOnUiThread(() -> {
                    // .show() 在正在 finishing 的 Activity 上会抛 BadTokenException，
                    // 这里是主线程，异常不在 handle() 的 catch 范围内 → 会崩 App
                    try {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        pendingDialog = new androidx.appcompat.app.AlertDialog.Builder(act)
                                .setTitle("DSHA 安全确认")
                                .setMessage(prompt)
                                // 必须明确选一个：误触关闭不再被当作拒绝。也不要在
                                // OnDismiss/OnCancel 里 countDown——Activity 被 pause
                                // 导致的 dismiss 会误判成"用户拒绝"，而用户还能从通知里点。
                                .setCancelable(false)
                                .setPositiveButton("允许", (d, w) -> resolveConfirm(true, myEpoch))
                                .setNegativeButton("拒绝", (d, w) -> resolveConfirm(false, myEpoch))
                                .show();
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "确认弹窗弹出失败，仍可从通知确认：" + t);
                    }
                });
            } else if (!notificationsEnabled()) {
                // 后台 + 通知被拒 = 用户看不到任何提示，只能干等 60s 超时被拒。
                // 至少留下日志，别让这变成无从排查的"命令莫名被拒"。
                android.util.Log.w(TAG, "无前台界面且通知权限被拒，确认必然超时拒绝：" + cmd);
            }

            try {
                boolean finished = latch.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
                return finished && pendingAllow;
            } catch (InterruptedException e) {
                return false;
            }
        } finally {
            // 顺序要紧：清理必须全部做完，最后才放开 confirmBusy。
            // 反过来的话，下一个请求会在这之前抢进来发新通知，而
            // cancelConfirmNotification() 用的是固定通知 ID，会把它刚发的通知取消掉。
            pendingLatch = null;
            dismissConfirmDialog();
            cancelConfirmNotification();
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

    /** 通知按钮（ConfirmReceiver）与前台弹窗按钮共用的回调。
     *  epoch 校验 + latch 认领：丢弃迟到的、属于上一个请求的点击。 */
    public void resolveConfirm(boolean allow, long epoch) {
        if (epoch != confirmEpoch.get()) {
            android.util.Log.i(TAG, "忽略过期的确认点击（epoch " + epoch + "）");
            return;
        }
        CountDownLatch l = pendingLatch;
        if (l == null || l.getCount() == 0) return; // 已决或无挂起
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
        String shortCmd = cmd.length() > 100 ? cmd.substring(0, 100) + "…" : cmd;
        Intent allowI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_ALLOW)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        Intent denyI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_DENY)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        // requestCode 必须随 epoch 变化：固定 code + FLAG_UPDATE_CURRENT 会把旧
        // PendingIntent 的 extras 覆盖成新 epoch，残留通知的按钮照样能打到新请求上。
        int base = (int) (epoch & 0x3FFFFFFFL) * 2;
        PendingIntent allowPi = PendingIntent.getBroadcast(ctx, base, allowI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent denyPi = PendingIntent.getBroadcast(ctx, base + 1, denyI,
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

    /** 转成合法 JSON 字符串（含外层双引号）。
     *  原实现只转义、不加引号，输出 {"result":YES} 并不是合法 JSON——
     *  用 JSON 解析器的客户端会解析失败、走异常分支放行，确认因此形同虚设。 */
    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
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
        sb.append('"');
        return sb.toString();
    }
}
