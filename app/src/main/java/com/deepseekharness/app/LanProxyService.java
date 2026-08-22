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
 * 在 App 侧监听 0.0.0.0:{@link #LAN_PORT}，把 HTTP 请求转发到本机 127.0.0.1:backendPort，
 * 并把 Host 头重写为 127.0.0.1:backendPort —— 后端 WebUI 的 Host 校验看到的是 loopback，
 * 天然放行，彻底绕开 CLI 的 0.0.0.0 拦截与 trusted-host 限制。支持 keep-alive、chunked、
 * WebSocket 升级（升级后双向透传）与 Location 重写（防重定向回 127.0.0.1）。
 */
public final class LanProxyService {

    private static final String TAG = "DSHA-LanProxy";
    /** LAN 桥访问 token：开启局域网时生成，访问需带 ?token= 或 X-DSHA-Token
     *  （防同 WiFi 任意设备访问 dsh → 官方明确拒绝 0.0.0.0 的原因）。
     *  首次生成后存 prefs（跨重启保持），启动页显示带 token 的地址。 */
    private static volatile String lanToken = "";

    /** 获取 LAN token（首次生成 16 位随机并持久化） */
    public static String getLanToken(android.content.Context ctx) {
        if (!lanToken.isEmpty()) return lanToken;
        try {
            String t = ctx.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getString("lan_token", "");
            if (t.isEmpty()) {
                t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                ctx.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                        .edit().putString("lan_token", t).apply();
            }
            lanToken = t;
        } catch (Throwable ignored) {
        }
        return lanToken;
    }

    /** 校验请求是否带正确 token（?token= / X-DSHA-Token / Cookie: dsha_token=）。
         *  token 只应从 URL 查询串、显式 header 或本站 Cookie 中读取，避免无鉴权放行。 */
        private static boolean tokenOk(String head, String token) {
            if (token == null || token.isEmpty()) return true; // 无 token 配置（旧版兼容）→ 放行
            for (String l : head.split("\\r?\\n")) {
                int i = l.indexOf(':');
                if (i <= 0) continue;
                String key = l.substring(0, i).trim();
                String v = l.substring(i + 1).trim();
                if (key.equalsIgnoreCase("X-DSHA-Token")) {
                    return constantTimeEquals(token, v);
                }
                if (key.equalsIgnoreCase("Cookie")) {
                    for (String c : v.split(";")) {
                        c = c.trim();
                        if (c.startsWith("dsha_token=")) {
                            return constantTimeEquals(token, c.substring("dsha_token=".length()));
                        }
                    }
                }
            }
            // query 参数校验（?token=xxx）
            int tq = head.indexOf("token=");
            if (tq >= 0) {
                String v = head.substring(tq + 6);
                int amp = v.indexOf('&');
                if (amp >= 0) v = v.substring(0, amp);
                int sp = v.indexOf(' ');
                if (sp >= 0) v = v.substring(0, sp);
                if (v.indexOf('\r') >= 0) v = v.substring(0, v.indexOf('\r'));
                return constantTimeEquals(token, v.trim());
            }
            return false;
        }

        private static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) return false;
            int diff = 0;
            for (int i = 0; i < a.length(); i++) {
                char ca = a.charAt(i);
                char cb = i < b.length() ? b.charAt(i) : 0;
                diff |= ca ^ cb;
            }
            diff |= a.length() ^ b.length();
            return diff == 0;
        }
    /** 桥监听端口：WebUI 默认 3080，桥用 3081 避免端口冲突（用户访问 http://<手机IP>:3081/） */
    public static final int LAN_PORT = 3081;
    /** 全局后端端口：每次 start 时用当前配置覆盖（自定义端口场景必须跟随 WebUI）。 */
    public static final int DEFAULT_BACKEND_PORT = 3080;

    private static volatile int backendPort = DEFAULT_BACKEND_PORT;

    private static ServerSocket server;
    private static Thread acceptThread;
    private static volatile boolean running;
    /** 连接处理线程池（限制并发，防线程耗尽） */
    private static java.util.concurrent.ExecutorService pool;
    /** 启动时缓存局域网 IP（仅用于日志/就绪提示；Location 重写已改为实时取
     *  getLanAddress()，WiFi 切换后重定向地址依然正确，不依赖本缓存） */
    private static volatile String lanIp = "";
    /** rootfs 日志路径（终端可 tail /root/dsh-lan.log 查看桥状态） */
    private static volatile String logPath = "";

    private LanProxyService() {}

    /** 兼容旧调用：未传端口时保持默认 3080。 */
    public static synchronized void start(String rootfsDir, android.content.Context ctx) {
        int port;
        try {
            port = HarnessController.get(ctx).getPortInt();
        } catch (Throwable ignored) {
            port = DEFAULT_BACKEND_PORT;
        }
        start(rootfsDir, ctx, port);
    }

    /** 真正的启动入口：后端端口每次取自当前配置。 */
    public static synchronized void start(String rootfsDir, android.content.Context ctx, int backendPortArg) {
        if (running) return;
        int backend = backendPortArg > 0 && backendPortArg <= 65535 ? backendPortArg : DEFAULT_BACKEND_PORT;
        if (backend == LAN_PORT) backend = 3080; // 与桥监听端口冲突时回退默认（配置页已拦截，这里兜底）
        backendPort = backend;
        logPath = rootfsDir + "/root/dsh-lan.log";
        if (ctx != null) getLanToken(ctx); // 初始化 token
        running = true;
        // 连接线程池：固定 8 线程（防局域网扫描/大量连接耗尽），daemon 线程
        pool = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "lanproxy");
            t.setDaemon(true);
            return t;
        });
        lanIp = HarnessController.getLanAddress();
        log("LAN 桥启动中: 0.0.0.0:" + LAN_PORT + " → 127.0.0.1:" + backend + " (LAN IP=" + lanIp + ")");
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

                // ===== LAN 鉴权：无 token 返回 401（防同 WiFi 任意设备访问）=====
                if (!tokenOk(head, lanToken)) {
                    String deny = "HTTP/1.1 401 Unauthorized\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: 30\r\n"
                            + "Connection: close\r\n\r\n"
                            + "Unauthorized: need token";
                    cout.write(deny.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    cout.flush();
                    break;
                }

                // ===== CORS：局域网设备浏览器跨域访问（http://<手机IP>:3081 前端
                // JS 请求 /api/... → Origin 不同）。后端（Host 已重写为 127.0.0.1）
                // 看到 loopback 但 Origin 是局域网 IP → 被 dsh 的 Origin 校验拒绝
                // → 浏览器 ERR_HTTP_RESPONSE_CODE_FAILURE。桥负责放行：
                // 1) OPTIONS 预检直接回 204 + CORS 头（不转发后端）
                // 2) 普通请求转发时附加 CORS 响应头（见 rewriteLocation 处）
                if (reqLine.toUpperCase(java.util.Locale.ROOT).startsWith("OPTIONS ")) {
                    String cors = "HTTP/1.1 204 No Content\r\n"
                            + "Access-Control-Allow-Origin: *\r\n"
                            + "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n"
                            + "Access-Control-Allow-Headers: *\r\n"
                            + "Access-Control-Max-Age: 86400\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n";
                    cout.write(cors.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    cout.flush();
                    break; // 预检结束，关连接
                }

                boolean upgrade = containsIgnoreCase(head, "Upgrade: websocket")
                        || reqLine.contains("HTTP/1.1") && containsIgnoreCase(head, "Connection: Upgrade");

                // 2. 改写 Host 头 → 127.0.0.1:<backendPort>
                String rewritten = rewriteHost(head);
                byte[] headBytes = rewritten.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

                // 3. 连接后端
                try (Socket back = new Socket()) {
                    back.setSoTimeout(120000);
                    back.connect(new InetSocketAddress("127.0.0.1", backendPort), 5000);
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

                    // 响应头转发（Location 重写防跳回 127.0.0.1 + 附加 CORS 头）
                    String outHead = rewriteLocation(rHead);
                    // 附加 CORS 响应头（局域网跨域放行；没有则浏览器拦截 → ERR_HTTP_RESPONSE_CODE_FAILURE）
                    if (!containsIgnoreCase(outHead, "Access-Control-Allow-Origin")) {
                        outHead = outHead.replace("\r\n\r\n",
                                "\r\nAccess-Control-Allow-Origin: *\r\n\r\n");
                    }
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

    /** chunked 透传直到末尾 0 块；单块上限 1MB + 块尾必须 CRLF（畸形流直接结束）。 */
    private static void pipeChunked(InputStream in, OutputStream out) throws IOException {
        final int MAX_CHUNK = 1024 * 1024;
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        while (true) {
            line.reset();
            int b;
            int size = -1;
            while ((b = in.read()) >= 0) {
                line.write(b);
                if (line.size() >= 2 && line.toByteArray()[line.size() - 2] == '\r' && line.toByteArray()[line.size() - 1] == '\n') {
                    try {
                        String h = new String(line.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                        size = Integer.parseInt(h.split(";")[0].trim(), 16);
                    } catch (Exception e) { size = -1; }
                    break;
                }
                if (line.size() > 1024) break;
            }
            if (b < 0) break;
            if (size < 0 || size > MAX_CHUNK) break; // 非法/超大块：中止透传（客户端可重新提交）
            out.write(line.toByteArray());
            if (size == 0) { out.flush(); break; }
            if (size > 0) {
                pipeBytes(in, out, size);
                // 块尾必须 CRLF（EOF 时 -1 不写入，防脏字节 0xff）
                int c1 = in.read(); int c2 = in.read();
                if (c1 < 0 || c2 < 0 || c1 != '\r' || c2 != '\n') break;
                out.write(c1);
                out.write(c2);
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

    /** 重写请求 Host 头为 127.0.0.1:<backendPort>（后端 Host 校验放行） */
        private static String rewriteHost(String head) {
                StringBuilder sb = new StringBuilder();
                boolean hostDone = false;
                boolean first = true;
                for (String l : head.split("\\r?\\n")) {
                    if (l.isEmpty()) { sb.append("\r\n"); continue; }
                    int i = l.indexOf(':');
                    String key = i > 0 ? l.substring(0, i).trim() : "";
                    if (first) {
                        first = false;
                        // 请求行：剥离 token 查询参数（LAN 鉴权 token 不转发给后端）
                        sb.append(stripTokenFromRequestLine(l)).append("\r\n");
                        continue;
                    }
                    if (key.equalsIgnoreCase("Host")) {
                    sb.append("Host: 127.0.0.1:").append(backendPort).append("\r\n");
                    hostDone = true;
                } else if (key.equalsIgnoreCase("Origin")) {
                    // 关键：dsh /api trust fence 要求 Origin.host === Host（严格含端口）。
                    // 桥把 Host 重写成 loopback，但局域网浏览器的 Origin 是
                    // http://<手机IP>:3081 → 不匹配 → 403 → ERR_HTTP_RESPONSE_CODE_FAILURE。
                    // 把 Origin 也重写成 loopback 同源，让后端 trust fence 放行。
                    sb.append("Origin: http://127.0.0.1:").append(backendPort).append("\r\n");
                } else if (key.equalsIgnoreCase("sec-fetch-site")) {
                                // cross-site 标记被 trust fence 直接拒绝 → 改 same-origin
                                sb.append("Sec-Fetch-Site: same-origin\r\n");
                            } else {
                                // 普通头 / 请求行：保留（token 只作为本站鉴权，不回传后端）
                                sb.append(l).append("\r\n");
                            }
            }
            if (!hostDone) sb.insert(0, "Host: 127.0.0.1:" + backendPort + "\r\n");
            return sb.toString();
        }

        /** 请求行里的 ?token=xxx 只用于本站鉴权：转发后端前剥离，避免 token 进后端日志。 */
            private static String stripTokenFromRequestLine(String line) {
                int q = line.indexOf('?');
                if (q < 0) return line;
                String path = line.substring(0, q);
                String query = line.substring(q + 1);
                String cleaned = query.replaceAll("[&]?token=[^&]*", "");
                if (cleaned.endsWith("&")) cleaned = cleaned.substring(0, cleaned.length() - 1);
                if (cleaned.isEmpty()) return path;
                return path + "?" + cleaned;
            }

            /** 响应头里 Location 重写：127.0.0.1:<backendPort> → 局域网IP:3081（防跳回本机）。
             *  每次实时取 IP（WiFi 切换后 IP 变化也能正确重写，不缓存旧值）。 */
        private static String rewriteLocation(String head) {
            if (!containsIgnoreCase(head, "Location:")) return head;
            String ip = HarnessController.getLanAddress();
            if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
            StringBuilder sb = new StringBuilder();
            for (String l : head.split("\\r?\\n")) {
                if (l.isEmpty()) { sb.append("\r\n"); continue; }
                int i = l.indexOf(':');
                if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Location")) {
                    String v = l.substring(i + 1).trim();
                    v = v.replace("http://127.0.0.1:" + backendPort, "http://" + ip + ":" + LAN_PORT);
                    v = v.replace("http://localhost:" + backendPort, "http://" + ip + ":" + LAN_PORT);
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
