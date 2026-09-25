package com.deepseekharness.app.util;

import java.util.regex.Pattern;

/**
 * dsh 1.2 BrowserAuth 启动路径打印的 URL 的严格解析器。
 *
 * <p>值被当作「已序列化的不透明 URL」解析，不做 URL 解码、不接受调用方提供的 host，
 * 只认 127.0.0.1 loopback；端口允许自定义（配置页可改 Web 端口）。
 * dsh 用 {@code Buffer.toString('base64url')} 生成进程 token。
 */
public final class DshAuthUrl {

    /** 默认 loopback base URL（端口未自定义时）。 */
    public static final String LOOPBACK_BASE_URL = "http://127.0.0.1:3080/";
    /** 默认的完整 BrowserAuth URL 前缀。 */
    public static final String AUTH_URL_PREFIX = LOOPBACK_BASE_URL + "?token=";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** 严格 URL：loopback 任意端口 + 43 位 base64url token。 */
    private static final Pattern AUTH_URL = Pattern.compile(
            "^(http://127\\.0\\.0\\.1:\\d+/)\\?token=([A-Za-z0-9_-]{43})$");
    /** 官方输出是完整一行；不接受内嵌标记。 */
    private static final Pattern STARTUP = Pattern.compile(
            "^dsh web: (\\S+)(?:[ \\t][^\\r\\n]*)?$", Pattern.MULTILINE);
    /** 宽松兜底：直接从输出里找鉴权 URL，不要求「dsh web: 」前缀；token 长度放宽。 */
    private static final Pattern ANY_URL = Pattern.compile(
            "http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]{20,120}");

    private DshAuthUrl() {
    }

    /** 解析后的不可变启动信息。token 永不写盘、不写日志。 */
    public static final class Parsed {
        /** 含实际端口的 base URL，如 {@code http://127.0.0.1:3080/}。 */
        public final String loopbackBaseUrl;
        public final String authUrl;
        public final String token;

        private Parsed(String loopbackBaseUrl, String token) {
            this.loopbackBaseUrl = loopbackBaseUrl;
            this.token = token;
            this.authUrl = loopbackBaseUrl + "?token=" + token;
        }
    }

    /**
     * 解析一个精确的 dsh 启动 URL，任何不匹配返回 {@code null}。
     * 语法严格是 {@code http://127.0.0.1:<port>/?token=<base64url-token>}。
     */
    public static Parsed parse(String candidate) {
        if (candidate == null || candidate.isEmpty()) return null;
        if (!candidate.equals(candidate.trim())) return null;
        java.util.regex.Matcher m = AUTH_URL.matcher(candidate);
        if (!m.find()) return null;
        return new Parsed(m.group(1), m.group(2));
    }

    /** 从 dsh 官方 stdout/stderr 提取并校验 URL。 */
    public static Parsed fromStartupOutput(String output) {
        if (output == null || output.isEmpty()) return null;
        java.util.regex.Matcher matcher = STARTUP.matcher(output);
        Parsed found = null;
        while (matcher.find()) {
            Parsed parsed = parse(matcher.group(1));
            if (parsed == null) return null;
            if (found == null) {
                found = parsed;
            } else if (!found.authUrl.equals(parsed.authUrl)) {
                return null;
            }
        }
        return found;
    }

    /** 从任意输出里找鉴权链接：先严格（官方输出行），失败再宽松扫 URL。 */
    public static String findAny(String output) {
        if (output == null) return null;
        Parsed p = fromStartupOutput(output);
        if (p != null) return p.authUrl;
        java.util.regex.Matcher m = ANY_URL.matcher(output);
        return m.find() ? m.group() : null;
    }

    /** 把鉴权 token 打码（落盘前脱敏），端口无关。 */
    public static String redact(String s) {
        if (s == null) return null;
        return s.replaceAll("(token=)[A-Za-z0-9_-]{20,120}", "$1***");
    }

    /**
     * 从 HTTP 响应头里挑 dsh-auth-* cookie（不假设它是第一个 Set-Cookie）。
     */
    public static String extractCookie(
            java.util.Map<String, ? extends java.util.List<String>> headers) {
        if (headers == null) return null;
        for (java.util.Map.Entry<String, ? extends java.util.List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            java.util.List<String> values = e.getValue();
            if (values == null) continue;
            for (String value : values) {
                String pair = extractCookieValue(value);
                if (pair != null) return pair;
            }
        }
        return null;
    }

    /**
     * 解析单条 Set-Cookie，返回 dsh-auth-* 的 {@code name=value}，否则 null。
     * Expires 属性自带逗号，按逗号切段后每段取第一个 {@code ;} 之前的 name=value 对。
     */
    public static String extractCookieValue(String setCookie) {
        if (setCookie == null) return null;
        for (String seg : setCookie.split(",")) {
            int semi = seg.indexOf(';');
            String pair = (semi >= 0 ? seg.substring(0, semi) : seg).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq);
            if (name.startsWith("dsh-auth-") && name.matches("dsh-auth-[A-Za-z0-9_-]+")) {
                return pair;
            }
        }
        return null;
    }

    /** 从鉴权 URL 中安全提取端口号，解析失败返回 -1 */
    public static int extractPort(String url) {
        if (url == null || url.isEmpty()) return -1;
        try {
            java.net.URI uri = new java.net.URI(url);
            int p = uri.getPort();
            if (p > 0) return p;
        } catch (Throwable ignored) {}
        java.util.regex.Matcher m = Pattern.compile("127\\.0\\.0\\.1:(\\d+)").matcher(url);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Throwable ignored) {}
        }
        return -1;
    }
}
