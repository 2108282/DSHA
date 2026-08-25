package com.deepseekharness.app;

/**
 * LanAuth 的断言集。用 javac 直接编译运行，不需要设备、SDK、网络：
 *
 * <pre>bash tools/lan-auth-test.sh</pre>
 *
 * 每条用例都对应一个真实踩过的坑，不是为了凑覆盖率 —— 局域网桥的凭据判定和请求行
 * 改写全是手写字符串切分，出错时的表现是「页面能开但转圈」「后端 400」这类隔一层的
 * 症状，只靠读代码看不出来。
 */
public final class LanAuthTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- stripTokenFromRequestLine ----------
        // 回归：token 是唯一参数时，原实现把 HTTP 版本一起吃掉（→ "GET /"），
        // 后端 Node parser 当畸形请求直接 400。这是最常见的场景：打开首页。
        eq("strip: 唯一参数", "GET / HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /?token=abc123 HTTP/1.1"));
        eq("strip: 参数在后", "GET /a?x=1 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?x=1&token=abc HTTP/1.1"));
        eq("strip: 参数在前", "GET /a?x=1 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?token=abc&x=1 HTTP/1.1"));
        eq("strip: 参数在中", "GET /a?x=1&y=2 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?x=1&token=abc&y=2 HTTP/1.1"));
        // 回归：[&]?token= 没有参数名边界，csrf_token / api_token 会被连带剥掉
        eq("strip: 不误删同后缀参数", "GET /a?csrf_token=z HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?csrf_token=z HTTP/1.1"));
        eq("strip: 无 query 原样返回", "GET /a HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a HTTP/1.1"));
        eq("strip: 保留 fragment", "GET /a#frag HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?token=t#frag HTTP/1.1"));
        eq("strip: POST 同样处理", "POST /api/x HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("POST /api/x?token=t HTTP/1.1"));
        eq("strip: 只有方法和目标（无版本）", "GET /a",
                LanAuth.stripTokenFromRequestLine("GET /a?token=t"));

        // ---------- queryToken ----------
        eq("query: 正常取值", "abc123", LanAuth.queryToken("GET /?token=abc123 HTTP/1.1"));
        // 回归：原来是整头 indexOf("token=")，xtoken= 的尾部也会命中并取到错误值
        eq("query: 不被同后缀参数误命中", null, LanAuth.queryToken("GET /?xtoken=bad HTTP/1.1"));
        eq("query: 无 query", null, LanAuth.queryToken("GET /a HTTP/1.1"));
        eq("query: 忽略 fragment", "t", LanAuth.queryToken("GET /a?token=t#x HTTP/1.1"));

        // ---------- tokenOk ----------
        final String T = "0123456789abcdef";
        // 回归：原来空 token 直接 return true（放行）。桥监听 0.0.0.0，
        // 放行等于同 WiFi 任意设备都能操作 dsh（agent 可执行 bash）。
        eqi("auth: 空 token 一律拒绝", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /?token=" + T, ""), ""));
        eqi("auth: query 命中 → 需回设 Cookie", LanAuth.AUTH_OK_SET_COOKIE,
                LanAuth.tokenOk(req("GET /?token=" + T, ""), T));
        eqi("auth: Cookie 命中", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "Cookie: dsha_token=" + T), T));
        eqi("auth: Cookie 与后端 dsha_t 共存", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "Cookie: dsha_t=zzz; dsha_token=" + T), T));
        eqi("auth: 显式头命中", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "X-DSHA-Token: " + T), T));
        // 回归：这是原实现「能用」的真正原因，也是 token 泄漏面 ——
        // 用户在 WebUI 里点任何外链，Referer 就把 token 送给对方站点。
        eqi("auth: Referer 里的 token 不算凭据", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /api/x",
                        "Referer: http://192.168.1.5:3081/?token=" + T), T));
        eqi("auth: 换过 token 时旧 Cookie 不挡新地址", LanAuth.AUTH_OK_SET_COOKIE,
                LanAuth.tokenOk(req("GET /?token=" + T, "Cookie: dsha_token=stale"), T));
        eqi("auth: 无凭据", LanAuth.AUTH_DENY, LanAuth.tokenOk(req("GET /", ""), T));
        eqi("auth: 显式头错值", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /", "X-DSHA-Token: wrong"), T));
        eqi("auth: WebSocket 握手带 Cookie 可过", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /api/ws",
                        "Upgrade: websocket\r\nCookie: dsha_token=" + T), T));

        // ---------- queryTokenFromTarget（3090 桥也用这一份）----------
        eq("target: 正常取值", "abc", LanAuth.queryTokenFromTarget("/exec?cmd=ls&token=abc"));
        // 回归：3090 桥原来是 query.indexOf("token=")，xtoken= 的尾部先命中 → 取到 junk 而误拒
        eq("target: 不被同后缀参数抢先", "abc",
                LanAuth.queryTokenFromTarget("/exec?xtoken=junk&token=abc"));
        eq("target: 无 token 参数", null, LanAuth.queryTokenFromTarget("/exec?cmd=ls"));
        eq("target: 无 query", null, LanAuth.queryTokenFromTarget("/exec"));

        // ---------- constantTimeEquals ----------
        ok("ct: 相等", LanAuth.constantTimeEquals("abc", "abc"));
        ok("ct: 前缀不算相等", !LanAuth.constantTimeEquals("abc", "ab"));
        ok("ct: 更长不算相等", !LanAuth.constantTimeEquals("abc", "abcd"));
        ok("ct: null 不相等", !LanAuth.constantTimeEquals("abc", null));

        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 条"
                : "失败 " + fail + " 条（通过 " + pass + "）");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 拼一个最小请求头：请求行 + 可选附加头 + 空行结尾。 */
    private static String req(String reqLine, String extraHeaders) {
        StringBuilder sb = new StringBuilder(reqLine).append(" HTTP/1.1\r\n");
        sb.append("Host: 192.168.1.5:3081\r\n");
        if (!extraHeaders.isEmpty()) sb.append(extraHeaders).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private static void eq(String name, String expected, String actual) {
        boolean good = expected == null ? actual == null : expected.equals(actual);
        report(name, good, String.valueOf(expected), String.valueOf(actual));
    }

    private static void eqi(String name, int expected, int actual) {
        report(name, expected == actual, String.valueOf(expected), String.valueOf(actual));
    }

    private static void ok(String name, boolean good) {
        report(name, good, "true", String.valueOf(good));
    }

    private static void report(String name, boolean good, String expected, String actual) {
        if (good) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "\n         期望: " + expected + "\n         实际: " + actual);
        }
    }
}
