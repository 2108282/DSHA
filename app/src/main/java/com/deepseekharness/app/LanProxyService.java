package com.deepseekharness.app;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 局域网转发桥（Shizuku 式思路的轻量版）：
 * 在 App 侧监听 0.0.0.0:{@link #LAN_PORT}，把 HTTP 请求转发到本机 127.0.0.1:{@link #BACKEND_PORT}，
 * 并把 Host 头重写为 127.0.0.1:{@link #BACKEND_PORT} —— 后端 WebUI 的 Host 校验看到的是 loopback，
 * 天然放行，彻底绕开 CLI 的 0.0.0.0 拦截与 trusted-host 限制。支持 keep-alive、chunked、
 * WebSocket 升级（升级后双向透传）与 Location 重写（防重定向回 127.0.0.1）。
 */
public final class LanProxyService {

    private static final String TAG = "DSHA-LanProxy";
    /** 桥监听端口：WebUI 默认 3080，桥用 3081 避免端口冲突（用户访问 http://<手机IP>:3081/） */
    public static final int LAN_PORT = 3081;
    /** 后端 WebUI 地址 */
    public static final int BACKEND_PORT = 3080;

    private static ServerSocket server;
    private static Thread acceptThread;
    private static volatile boolean running;
    /** 连接处理线程池（限制并发，防线程耗尽） */
    private static java.util.concurrent.ExecutorService pool;
    /** 供 Location 重写用的局域网 IP（可随时刷新） */
    private static volatile String lanIp = "";
    /** rootfs 日志路径（终端可 tail /root/dsh-lan.log 查看桥状态） */
    private static volatile String logPath = "";

    private LanProxyService() {}

    public static synchronized void start(String rootfsDir) {
        if (running) return;
        logPath = rootfsDir + "/root/dsh-lan.log";
        running = true;
        // 连接线程池：固定 8 线程（防局域网扫描/大量连接耗尽），daemon 线程
        pool = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "lanproxy");
            t.setDaemon(true);
            return t;
        });
        lanIp = HarnessController.getLanAddress();
        log("LAN 桥启动中: 0.0.0.0:" + LAN_PORT + " → 127.0.0.1:" + BACKEND_PORT + " (LAN IP=" + lanIp + ")");
        acceptThread = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", LAN_PORT));
                log("LAN 桥已就绪 ✓ 访问地址: http://" + (lanIp.isEmpty() ? "<手机IP>" : lanIp) + ":" + LAN_PORT + "/");
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setSoTimeout(120000);
                        // 固定线程池：限制并发连接线程数（防局域网扫描/大量连接耗尽线程）
                        pool.execute(() -> handle(client));
                    } catch (IOException e) {
                        if (running) log("accept 异常: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                log("桥启动失败: " + e.getMessage());
                running = false;
            } finally {
                closeQuietly(server);
                server = null;
            }
        }, "lanproxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public static synchronized void stop() {
        if (running) {
            log("LAN 桥已停止");
        }
        running = false;
        if (acceptThread != null) acceptThread.interrupt();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        closeQuietly(server);
        server = null;
    }

    public static boolean isRunning() { return running; }

    /** 状态日志：同时写 logcat 与 rootfs /root/dsh-lan.log（App 终端 tail 可见） */
    private static void log(String msg) {
        Log.i(TAG, msg);
        if (!logPath.isEmpty()) {
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(logPath, true)) {
                String line = "[" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date())
                        + "] " + msg + "\n";
                fo.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    // ================= 单连接处理 =================

    private static void handle(Socket client) {
        String clientIp = client.getInetAddress() == null ? "" : client.getInetAddress().getHostAddress();
        log("连接来自: " + clientIp);
        try (Socket clientSock = client) {
            InputStream cin = clientSock.getInputStream();
            OutputStream cout = clientSock.getOutputStream();
            byte[] reqHead = new byte[65536];
            while (running) {
                // 1. 读请求头（到 \r\n\r\n）
                int headLen = readHeader(cin, reqHead);
                if (headLen <= 0) break; // EOF / 超时
                String head = new String(reqHead, 0, headLen, java.nio.charset.StandardCharsets.ISO_8859_1);
                int nl = head.indexOf('\n');
                if (nl < 0) break; // 畸形请求头：无换行直接断开，防 substring 越界
                String reqLine = head.substring(0, nl).trim();
                if (reqLine.isEmpty()) break;
                boolean upgrade = containsIgnoreCase(head, "Upgrade: websocket")
                        || reqLine.contains("HTTP/1.1") && containsIgnoreCase(head, "Connection: Upgrade");

                // 2. 改写 Host 头 → 127.0.0.1:3080
                String rewritten = rewriteHost(head);
                byte[] headBytes = rewritten.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

                // 3. 连接后端
                try (Socket back = new Socket()) {
                    back.setSoTimeout(120000);
                    back.connect(new InetSocketAddress("127.0.0.1", BACKEND_PORT), 5000);
                    InputStream bin = back.getInputStream();
                    OutputStream bout = back.getOutputStream();
                    bout.write(headBytes);
                    bout.flush();
                    // 请求体透传（Content-Length 部分；chunked 请求体也按 chunked 转发）
                    long bodyLen = contentLength(rewritten);
                    if (bodyLen > 0) {
                        pipeBytes(cin, bout, bodyLen);
                    } else if (containsIgnoreCase(head, "Transfer-Encoding: chunked")) {
                        pipeChunked(cin, bout);
                    }

                    // 4. 读响应头
                    byte[] respHead = new byte[65536];
                    int rhLen = readHeader(bin, respHead);
                    if (rhLen <= 0) break;
                    String rHead = new String(respHead, 0, rhLen, java.nio.charset.StandardCharsets.ISO_8859_1);
                    boolean upgraded = rHead.startsWith("HTTP/1.1 101") || containsIgnoreCase(rHead, "Upgrade: websocket");

                    // 响应头转发（Location 重写防跳回 127.0.0.1）
                    String outHead = rewriteLocation(rHead);
                    cout.write(outHead.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    cout.flush();

                    if (upgraded) {
                        // WebSocket：双向透传直到关闭
                        pumpBidirectional(cin, cout, bin, bout);
                        break;
                    }
                    // 普通响应体
                    long cl = contentLength(rHead);
                    boolean chunked = containsIgnoreCase(rHead, "Transfer-Encoding: chunked");
                    boolean closeConn = containsIgnoreCase(rHead, "Connection: close");
                    if (cl > 0) {
                        pipeBytes(bin, cout, cl);
                    } else if (chunked) {
                        pipeChunked(bin, cout);
                    } else {
                        // 无长度：流式转发直到后端 EOF（SSE/长连接）
                        pumpStream(bin, cout);
                    }
                    if (closeConn) break;
                    // keep-alive：继续下一请求
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= IO 工具 =================

    /** 读头部直到 \r\n\r\n（或 \n\n），返回字节数；EOF 返回 -1；超长截断后放行 */
    private static int readHeader(InputStream in, byte[] buf) throws IOException {
        int pos = 0, matched = 0;
        while (pos < buf.length) {
            int b = in.read();
            if (b < 0) return pos == 0 ? -1 : pos;
            buf[pos++] = (byte) b;
            if (matched == 0 && b == '\r') matched = 1;
            else if (matched == 1 && b == '\n') matched = 2;
            else if (matched == 2 && b == '\r') matched = 3;
            else if (matched == 3 && b == '\n') return pos;
            else if (matched == 2 && b == '\n') return pos; // 兼容 \n\n
            else matched = 0;
        }
        return pos;
    }

    private static void pipeBytes(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            out.write(buf, 0, r);
            left -= r;
        }
        out.flush();
    }

    /** chunked 透传直到末尾 0 块 */
    private static void pipeChunked(InputStream in, OutputStream out) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        while (true) {
            line.reset();
            int b;
            int size = -1;
            while ((b = in.read()) >= 0) {
                line.write(b);
                if (line.size() >= 2 && line.toByteArray()[line.size() - 2] == '\r' && line.toByteArray()[line.size() - 1] == '\n') {
                    try {
                        size = Integer.parseInt(new String(line.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).trim().split(";")[0], 16);
                    } catch (Exception e) { size = -1; }
                    break;
                }
                if (line.size() > 1024) break;
            }
            if (b < 0) break;
            out.write(line.toByteArray());
            if (size == 0) { out.flush(); break; }
            if (size > 0) {
                pipeBytes(in, out, size);
                // 块尾 CRLF（EOF 时 -1 不写入，防脏字节 0xff）
                int c1 = in.read(); int c2 = in.read();
                if (c1 >= 0) out.write(c1);
                if (c2 >= 0) out.write(c2);
            }
        }
        out.flush();
    }

    private static void pumpStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) {
            out.write(buf, 0, r);
            out.flush();
        }
    }

    private static void pumpBidirectional(InputStream aIn, OutputStream aOut, InputStream bIn, OutputStream bOut) {
        Thread t1 = new Thread(() -> { try { pumpStream(aIn, bOut); } catch (Throwable ignored) {} });
        Thread t2 = new Thread(() -> { try { pumpStream(bIn, aOut); } catch (Throwable ignored) {} });
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        try { t1.join(60000); } catch (InterruptedException ignored) {}
        try { t2.join(60000); } catch (InterruptedException ignored) {}
    }

    private static long contentLength(String head) {
        for (String l : head.split("\r?\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try { return Long.parseLong(l.substring(i + 1).trim()); } catch (Exception e) { return 0; }
            }
        }
        return 0;
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        int idx = s.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT));
        return idx >= 0;
    }

    /** 重写请求 Host 头为 127.0.0.1:端口（后端 Host 校验放行） */
    private static String rewriteHost(String head) {
        StringBuilder sb = new StringBuilder();
        boolean hostDone = false;
        for (String l : head.split("\r?\n")) {
            if (l.isEmpty()) { sb.append("\r\n"); continue; }
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Host")) {
                sb.append("Host: 127.0.0.1:").append(BACKEND_PORT).append("\r\n");
                hostDone = true;
            } else {
                sb.append(l).append("\r\n");
            }
        }
        if (!hostDone) sb.insert(0, "Host: 127.0.0.1:" + BACKEND_PORT + "\r\n");
        return sb.toString();
    }

    /** 响应头里 Location 重写：127.0.0.1:3080 → 局域网IP:3081（防跳回本机）。
     *  每次实时取 IP（WiFi 切换后 IP 变化也能正确重写，不缓存旧值）。 */
    private static String rewriteLocation(String head) {
        if (!containsIgnoreCase(head, "Location:")) return head;
        String ip = HarnessController.getLanAddress();
        if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
        StringBuilder sb = new StringBuilder();
        for (String l : head.split("\r?\n")) {
            if (l.isEmpty()) { sb.append("\r\n"); continue; }
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Location")) {
                String v = l.substring(i + 1).trim();
                v = v.replace("http://127.0.0.1:" + BACKEND_PORT, "http://" + ip + ":" + LAN_PORT);
                v = v.replace("http://localhost:" + BACKEND_PORT, "http://" + ip + ":" + LAN_PORT);
                sb.append("Location: ").append(v).append("\r\n");
            } else {
                sb.append(l).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
