package com.deepseekharness.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 静默检查更新：查询 GitHub Releases 最新版本号。
 * 直连失败（被墙）时 fallback ghfast.top 代理；全部失败静默返回 null。
 */
public final class UpdateChecker {

    private static final String[] URLS = {
            // 只以 GitHub Releases latest 为准（不要用 main/VERSION——它只反映代码
            // 当前版本不代表已发布，会导致版本判断错乱）
            "https://api.github.com/repos/qiannianhuanxiang/DSHA/releases/latest",
            // 代理 fallback（API 可能被代理拒，放最后兜底）
            "https://ghfast.top/https://api.github.com/repos/qiannianhuanxiang/DSHA/releases/latest"
    };

    private UpdateChecker() {
    }

    /** 查询最新版本号（vX.Y.Z），失败返回 null */
    public static String checkLatestVersion() {
        for (String url : URLS) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                // UA 取构建版本，避免每次发版都要手工同步这里（曾长期停在 1.1.4）
                conn.setRequestProperty("User-Agent", "DSHA/" + BuildConfig.VERSION_NAME);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                        if (sb.length() > 262144) break;
                    }
                }
                conn.disconnect();
                String tag = extractTag(sb.toString());
                if (tag != null && tag.matches("v?\\d+(\\.\\d+)*")) return tag;
                // 兼容纯文本（VERSION 文件：单行 "v1.0.11"）
                String plain = sb.toString().trim();
                if (plain.matches("v?\\d+(\\.\\d+)*")) return plain;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String extractTag(String json) {
        int i = json.indexOf("\"tag_name\"");
        if (i < 0) return null;
        int s = json.indexOf('"', i + 10);
        if (s < 0) return null;
        int e = json.indexOf('"', s + 1);
        if (e < 0) return null;
        return json.substring(s + 1, e);
    }

    /** 比较最新版（v1.2.3）是否比当前（1.0.0）新 */
    public static boolean isNewer(String latestTag, String current) {
        String[] a = latestTag.replaceFirst("^v", "").split("\\.");
        String[] b = current.replaceFirst("^v", "").split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? parseInt(a[i]) : 0;
            int y = i < b.length ? parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** 取版本段的数值。
     *
     *  原来是 replaceAll("[^0-9]", "") —— 把段里所有非数字**删掉再拼起来**，
     *  于是预发布号被拼成了一个巨大的数：
     *      1.1.7-rc81  →  段 "7-rc81"  →  "781"  →  当成 1.1.781
     *  这个版本比任何正式版都「新」，用户从此**永远收不到更新提示**。
     *  我们真用过 1.1.7-rc81 这种版本名，也正在用 1.1.7-fix。
     *
     *  改成只取前导数字，遇到非数字就停：
     *      "7-rc81" → 7      "7-fix" → 7      "9" → 9
     *  这样带后缀的版本与同号正式版比较结果相等（不会互相提示更新），
     *  与更高版本比较则正常判新。 */
    private static int parseInt(String s) {
        if (s == null) return 0;
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (Exception e) {
            return 0;   // 段长到溢出 int：当 0 处理，不如实报也不误判成最新
        }
    }
}
