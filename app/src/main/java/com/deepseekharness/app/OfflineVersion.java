package com.deepseekharness.app;

/**
 * 离线包版本标记的比较（纯逻辑、不碰 Android API —— 断言在 tools/pure-logic-test.sh）。
 *
 * <p>标记就是 CI 写进 {@code assets/offline-rootfs.version} 的那一行，形如
 * {@code dsh-0.1.1-rc.2}（拿打进包里的 dsh 版本命名）；很老的包可能是纯数字，
 * 没有标记时按 {@code "0"} 处理。
 *
 * <p><b>为什么必须真的比大小</b>：{@code maybeOfferOfflineUpgrade} 原先拿「标记不相等」
 * 当「有新版」，注释写的却是「内置 &gt; 已解压」。于是用旧离线包打出来的本地测试包装到
 * 手机上也会弹「发现新版内置环境」，用户点了升级，rootfs 被重解压成更旧的环境 ——
 * dsh 从 0.1.1-rc.2 退回 0.1.0-rc.6，还得自己再更新回去。
 *
 * <p>两种错的代价不对称：漏提示只是晚点拿到新环境（下次 APK 更新还会提），误提示是把
 * 用户已经在用的环境弄旧。所以这里的规则是<b>拿不准就说「不是更新」</b>：
 * 解析不出版本号时返回 {@link #NOT_COMPARABLE}，调用方据此闭嘴。
 */
final class OfflineVersion {

    private OfflineVersion() {
    }

    /** 无法比较（任一侧解析不出版本号）。故意用 MIN_VALUE：调用方写 {@code > 0} 时天然安全。 */
    static final int NOT_COMPARABLE = Integer.MIN_VALUE;

    /**
     * 把标记解析成可逐位比较的键：{@code [主, 次, 修订, 是否正式版, 预发布号]}。
     *
     * <p>第 4 位是预发布位，正式版 1、rc/beta/alpha/pre 是 0 —— 少了这一位，
     * {@code 0.1.1}（正式）会被判成比 {@code 0.1.1-rc.2} 旧，因为它没有末尾那个数字。
     *
     * <p>解析不出数字时返回空数组（比如空串、纯文字标记）。
     */
    static int[] key(String tag) {
        if (tag == null) return new int[0];
        String s = tag.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return new int[0];
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(s);
        if (!m.find()) return new int[0];
        String core = m.group(1);
        String rest = s.substring(m.end(1));
        int[] out = new int[5];
        String[] parts = core.split("\\.");
        // 主版本只取三位：0.1.1 与 0.1.1.0 是同一个版本，多余位不参与比较
        for (int i = 0; i < 3 && i < parts.length; i++) {
            out[i] = parseInt(parts[i]);
        }
        boolean pre = rest.matches(".*(rc|alpha|beta|pre).*");
        out[3] = pre ? 0 : 1;
        if (pre) {
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("(\\d+)").matcher(rest);
            out[4] = pm.find() ? parseInt(pm.group(1)) : 0;
        }
        return out;
    }

    /** 比较两个标记：正数 a 更新、0 同版本、负数 a 更旧；无法比较返回 {@link #NOT_COMPARABLE}。 */
    static int compare(String a, String b) {
        int[] ka = key(a);
        int[] kb = key(b);
        if (ka.length == 0 || kb.length == 0) return NOT_COMPARABLE;
        for (int i = 0; i < ka.length; i++) {
            if (ka[i] != kb[i]) return ka[i] < kb[i] ? -1 : 1;
        }
        return 0;
    }

    /** a 是否<b>确实</b>比 b 新。无法比较时返回 false —— 拿不准不提示。 */
    static boolean isNewer(String a, String b) {
        int c = compare(a, b);
        return c != NOT_COMPARABLE && c > 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
