package com.deepseekharness.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 「3080 上那位到底还能不能服务」这个判断的唯一实现。
 *
 * <p>为什么不能只看 TCP 连得上：{@code connect()} 成功只说明有进程 bind 了这个端口，
 * 说明不了它还答话。至少三种情况会连得上却不能用 —— 端口被别的进程占了、node 卡死在
 * 事件循环里、dsh 还在启动没进 listen 回调。这三种都必须走「杀干净重来」，
 * 而一个还在正常服务的 dsh 则应该<b>直接接管</b>：那套清场最坏要付
 * 4+3+1+4=12 秒，用户看到的就是「点了启动，二十来秒才进去」。
 *
 * <p>判据故意放得很宽：只要对方按 HTTP 回一句状态行就算健康。dsh 在没带 token 时
 * 可能回 302 或 401，那同样证明它活着并且在处理请求 —— 用「必须 200」会把这些
 * 正常情况误判成坏的，又变回杀掉重来。
 *
 * <p>抽成纯 java.net 的类是为了能测：{@code tools/web-probe-test.sh} 真起三种服务端
 *（正常 HTTP、连得上但不答话、没人监听）跑一遍。
 */
final class WebProbe {

    private WebProbe() {
    }

    /** 建连最多等这么久 —— 本机回环，连不上基本是立刻拒绝，不需要给多。 */
    private static final int CONNECT_MS = 800;

    /**
     * @param port      要探的端口
     * @param timeoutMs 等对方回话的上限
     * @return 对方回了 HTTP 状态行才是 true
     */
    static boolean servesHttp(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), Math.min(CONNECT_MS, timeoutMs));
            s.setSoTimeout(Math.max(200, timeoutMs));
            OutputStream out = s.getOutputStream();
            // Connection: close —— 别让 keep-alive 把连接留在半开状态
            out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[16];
            int n = 0;
            // 状态行开头 8 个字节就够判断，但 read 不保证一次给满
            while (n < 8) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 8) return false;
            return new String(buf, 0, n, StandardCharsets.US_ASCII).startsWith("HTTP/1.");
        } catch (Throwable t) {
            // 超时、连接被拒、读到 EOF 都归一到「不能用」
            return false;
        }
    }
}
