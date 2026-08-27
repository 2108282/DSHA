package com.deepseekharness.app;

/**
 * 插件市场每一行的<b>列语义</b> —— 唯一定义。
 *
 * <p><b>为什么必须有这个类</b>：市场行是个裸 {@code String[]}，列的含义只写在注释里，
 * 而填这个数组的有两条数据源（{@code plugins.json} 与 {@code PLUGINS-ALL.md}），
 * 读它的又是一堆按下标取值的 UI 代码。同一个位置在两条路上塞了不同东西，两次都出了故障：
 *
 * <ul>
 *   <li>{@code it[4]}：一条路填分类、另一条填 npm 包名 → 安装时把分类名（"ui"）
 *       当包名去 npm 装；</li>
 *   <li>{@code it[3]}：一条路填兼容性标记、另一条填收录日期 → 「仅显示兼容」筛选器
 *       一条也筛不掉，点了跟没点一样。</li>
 * </ul>
 *
 * 两次都是「注释写了、人没照着做」。下标换成有名字的常量之后，
 * {@code row[COMPAT] = 日期} 这种写法在代码里就显形了。
 *
 * <p>另外注意：<b>已装插件的行不是这个语义</b>（那边 {@code [1]} 是启用状态而不是星标）。
 * 两者只在各自的分支里使用，别混。
 */
final class MarketCol {

    /** 插件名（也是市场里的显示名）。 */
    static final int NAME = 0;
    /** GitHub 星标数，字符串形式。 */
    static final int STARS = 1;
    /** 仓库 owner。 */
    static final int OWNER = 2;
    /** 兼容性标记。{@code ⏳待定} / {@code 未测} 表示未知 —— 「仅显示兼容」读这一列。 */
    static final int COMPAT = 3;
    /** 分类（给用户看的名字，中文优先）—— 筛选菜单按这一列的实际值动态列出。 */
    static final int CATEGORY = 4;
    /** 描述。 */
    static final int DESC = 5;
    /** 仓库 URL。 */
    static final int URL = 6;
    /** npm 包名。<b>只有 plugins.json 那条路有第 8 列</b>，Markdown 那条只有 7 列。 */
    static final int NPM = 7;

    /** 两条数据源都保证有的列数。 */
    static final int BASE_WIDTH = 7;

    private MarketCol() {
    }

    /** 取一列，越界或为 null 都给空串（读侧不必到处判空）。 */
    static String at(String[] row, int col) {
        if (row == null || col < 0 || col >= row.length || row[col] == null) return "";
        return row[col].trim();
    }

    /** 这一行有没有 npm 包名（只有 plugins.json 那条路会有）。 */
    static String npmOf(String[] row) {
        String v = at(row, NPM);
        return PluginSpec.isPackageName(v) ? v : "";
    }

    /**
     * 行的形状自检。<b>专门用来挡住「往某一列塞了别的东西」这类错</b>：
     * <ul>
     *   <li>列数至少 {@link #BASE_WIDTH}；</li>
     *   <li>名字非空；</li>
     *   <li><b>兼容性列不能是日期形状</b>（{@code 2026-08-14}）—— 这正是踩过的那次；</li>
     *   <li><b>星标列必须是数字</b>（否则排序会静默乱掉）。</li>
     * </ul>
     */
    static boolean isSaneRow(String[] row) {
        if (row == null || row.length < BASE_WIDTH) return false;
        if (at(row, NAME).isEmpty()) return false;
        if (looksLikeDate(at(row, COMPAT))) return false;
        String stars = at(row, STARS);
        if (!stars.isEmpty() && !stars.matches("[0-9,]+")) return false;
        return true;
    }

    /** {@code 2026-08-14} / {@code 2026/08/14} 这类形状。 */
    static boolean looksLikeDate(String s) {
        return s != null && s.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*");
    }

    /**
     * <b>已装插件</b>行的列语义 —— 跟市场行完全不同，别混。
     *
     * <p>最容易踩的是 {@code [1]}：市场行那是<b>星标数</b>，已装行是<b>启用状态</b>
     * （{@code "启用"} / {@code "禁用"}）。两套语义各自只在 adapter 的对应分支里使用。
     */
    static final class Installed {
        /** 插件包名。 */
        static final int NAME = 0;
        /** {@code "启用"} 或 {@code "禁用"}。 */
        static final int STATE = 1;
        /** 简介：内置插件用 App 侧的中文，其余读 package.json 的 description。 */
        static final int DESC = 2;
        /** 列数。 */
        static final int WIDTH = 3;

        private Installed() {
        }
    }
}
