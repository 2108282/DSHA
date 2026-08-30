package com.deepseekharness.app;

/**
 * 插件市场搜索的**唯一判据**：多词 AND，跨「名称 / 描述 / 作者 / 分类 / npm 包名」匹配。
 *
 * <p>为什么要有它：原来市场搜索只比 {@link MarketCol#NAME} 一列。索引里两千多条，
 * 插件名多半是 {@code dsh-xxx} 这种缩写，用户真正记得的往往是「干什么的」（描述）
 * 或者「谁写的」（作者）—— 搜「主题」「翻译」「语音」一条都出不来，看起来就像市场里
 * 根本没有这类插件。这份索引的描述取的是 {@code description.zh}（中文优先，见
 * {@code PluginController.parsePluginsJson}），所以按描述搜中文是能命中的。
 *
 * <p>三条取舍：
 * <ul>
 *   <li><b>多词 AND</b>：{@code "翻译 插件"} 要两个词都命中。OR 在两千多条里等于没筛。</li>
 *   <li><b>不匹配 URL 列</b>：每条都是 {@code https://github.com/…}，搜 {@code github}
 *       会命中全部；owner 与 name 已经单独覆盖了仓库信息。</li>
 *   <li><b>不匹配星标与兼容性列</b>：那是数字与标记（{@code ⏳待定}），命中了也没有意义，
 *       反而会让「搜 5」这种输入莫名匹配一堆。</li>
 * </ul>
 *
 * <p>短行要容得下：Markdown 那条老索引只有 7 列（没有 {@link MarketCol#NPM}），
 * 越界访问会让整个市场页崩在过滤循环里。
 */
final class MarketSearch {

    private MarketSearch() {
    }

    /** 空查询（复用，避免每次按键都新建数组）。 */
    static final String[] NO_TERMS = new String[0];

    /**
     * 把查询串切成小写词。空白分隔、忽略连续空白与两端空白，空查询返回 {@link #NO_TERMS}。
     *
     * <p>{@code Locale.ROOT}：土耳其语环境下 {@code "I".toLowerCase()} 会变成点上无 i 的
     * {@code ı}，跟索引里的 {@code i} 对不上 —— 用户只会觉得搜索坏了。
     */
    static String[] terms(String query) {
        if (query == null) return NO_TERMS;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return NO_TERMS;
        String[] raw = q.split("\\s+");
        int n = 0;
        for (String s : raw) {
            if (!s.isEmpty()) n++;
        }
        if (n == 0) return NO_TERMS;
        if (n == raw.length) return raw;
        String[] out = new String[n];
        int i = 0;
        for (String s : raw) {
            if (!s.isEmpty()) out[i++] = s;
        }
        return out;
    }

    /** 一行是否命中全部词。空词表一律通过（没搜就不筛）。 */
    static boolean matches(String[] row, String[] terms) {
        if (terms == null || terms.length == 0) return true;
        if (row == null) return false;
        String hay = haystack(row);
        for (String t : terms) {
            if (t == null || t.isEmpty()) continue;
            if (!hay.contains(t)) return false;
        }
        return true;
    }

    /** 参与匹配的列拼成一份小写文本。列之间加换行，避免跨列拼出不存在的词。 */
    private static String haystack(String[] row) {
        StringBuilder sb = new StringBuilder(192);
        append(sb, row, MarketCol.NAME);
        append(sb, row, MarketCol.DESC);
        append(sb, row, MarketCol.OWNER);
        append(sb, row, MarketCol.CATEGORY);
        append(sb, row, MarketCol.NPM);
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static void append(StringBuilder sb, String[] row, int col) {
        if (col < 0 || col >= row.length) return;
        String v = row[col];
        if (v == null || v.isEmpty()) return;
        sb.append(v).append('\n');
    }
}
