package com.deepseekharness.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 插件市场描述的自动翻译。
 *
 * <p>市场索引里的插件描述基本都是英文，而点进详情才是用户真正想读懂的时刻 —— 所以翻译
 * 挂在详情弹窗打开时触发，而不是在列表里整页翻（那样一次要翻几十条，费钱又慢）。
 *
 * <p>走 OpenAI 兼容的 {@code /chat/completions}。默认复用 dsh 那把 API Key 和 DeepSeek
 * 官方端点，用户不用另配；也可以在配置页单独填端点/模型/Key（比如指向本地模型）。
 *
 * <p><b>刻意不带思考过程</b>：默认模型是 deepseek-chat 而不是 reasoner。翻译这种任务
 * 思考链没有收益，只会拖慢首字并多花钱；万一用户自己配了 reasoner，响应里的
 * {@code reasoning_content} 我们也直接丢掉。
 *
 * <p>译文按内容哈希缓存在独立的 SharedPreferences 文件里。同一个插件反复点开只翻一次，
 * 而市场索引刷新后描述若有变化，哈希变了自然会重翻。
 */
final class Translator {

    /** 开关，默认关 —— 翻译要花钱，不能替用户决定。 */
    static final String K_ENABLED = "translate_market";
    /** 端点根地址（不含 /chat/completions）。留空 = DeepSeek 官方。 */
    static final String K_BASE = "translate_base_url";
    /** 模型名。留空 = deepseek-chat。 */
    static final String K_MODEL = "translate_model";
    /** 独立 API Key。留空 = 复用 dsh 那把。 */
    static final String K_KEY = "translate_api_key";

    static final String DEF_BASE = "https://api.deepseek.com";
    /** 默认模型。deepseek-v4-flash：翻译这种活要的是快和便宜，不需要思考链。 */
    static final String DEF_MODEL = "deepseek-v4-flash";

    /** 缓存单独一个文件，别和主偏好混在一起 —— 它会长到几百条，也不该进备份。 */
    private static final String CACHE_PREFS = "dsha_translate_cache";
    /** 超过这个条数就清空重来。LRU 不值得为几 KB 文本上：重翻的代价只是几分钱。 */
    private static final int CACHE_MAX = 500;

    private Translator() {
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(K_ENABLED, false);
    }

    /** 缓存命中就返回译文，否则 null。UI 线程可以直接调。 */
    static String cached(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        return ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .getString(hash(text), null);
    }

    /**
     * 翻译（阻塞，调用方自己开线程）。失败返回 null —— 调用方保持原文显示即可，
     * 翻不出来不该让用户看不到内容。
     */
    static String translate(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String hit = cached(ctx, text);
        if (hit != null) return hit;

        SharedPreferences sp = prefs(ctx);
        String base = trimTail(sp.getString(K_BASE, "").trim());
        if (base.isEmpty()) base = DEF_BASE;
        String model = sp.getString(K_MODEL, "").trim();
        if (model.isEmpty()) model = DEF_MODEL;
        String key = sp.getString(K_KEY, "").trim();
        if (key.isEmpty()) {
            try {
                key = HarnessController.get(ctx).getApiKey();
            } catch (Throwable ignored) {
            }
        }
        if (key == null || key.isEmpty()) return null;   // 没 key 就别白跑一趟网络

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);

            String body = "{\"model\":" + jsonStr(model)
                    + ",\"stream\":false,\"temperature\":0.2,\"max_tokens\":800"
                    + ",\"messages\":[{\"role\":\"system\",\"content\":"
                    + jsonStr("你是技术文档翻译。把用户给的软件插件描述翻译成简体中文。"
                            + "只输出译文本身，不要加解释、不要加引号、不要重复原文。"
                            + "保留代码标识符、命令、包名、URL 原样不译。")
                    + "},{\"role\":\"user\",\"content\":" + jsonStr(text) + "}]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code != 200) {
                // 故意不打 resp 全文：里头可能回显请求内容，而请求头带着 Bearer
                android.util.Log.w("DSHA-translate", "翻译请求失败 HTTP " + code);
                return null;
            }
            String out = pickContent(resp);
            if (out == null || out.trim().isEmpty()) return null;
            out = out.trim();
            put(ctx, text, out);
            return out;
        } catch (Throwable e) {
            android.util.Log.w("DSHA-translate", "翻译失败: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- 内部 ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    }

    private static void put(Context ctx, String src, String out) {
        SharedPreferences c = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        if (c.getAll().size() >= CACHE_MAX) c.edit().clear().apply();
        c.edit().putString(hash(src), out).apply();
    }

    /** 用内容哈希做键：描述改了自然重翻，不用额外记版本。 */
    private static String hash(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("t_");
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable e) {
            return "t_" + Integer.toHexString(s.trim().hashCode());
        }
    }

    private static String trimTail(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 从响应里取 choices[0].message.content。用 org.json（Android 自带，不引依赖）。 */
    private static String pickContent(String resp) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(resp);
            org.json.JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return null;
            org.json.JSONObject msg = ch.getJSONObject(0).optJSONObject("message");
            if (msg == null) return null;
            // reasoning_content 是思考过程（用户若配了 reasoner 会有），直接忽略
            return msg.optString("content", "");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
