package com.deepseekharness.app;

/**
 * 已弃用插件的单一权威清单：任何「程序替用户装插件」的路径动手前都要先问它。
 *
 * <p><b>为什么需要它</b>——用户报的「重装后没恢复备份，老 UI 插件自己回来了」就是它治的：
 *
 * <ol>
 *   <li>老备份的 profile 里写着
 *       {@code dependencies["dsh-client-ui-mobile-adapt"] = "link:/root/dsha-mobile-adapt"}；</li>
 *   <li>重装后是全新 rootfs，那个实体不存在（v1.1.8 起内置的是 mobile-nav），
 *       restore-merge.py 判定「源码没了」→ 摘掉依赖，同时把它报进 {@code MISSING_PLUGINS}
 *       （脚本里的原话是「npm 上可能有同名包，交给 App 后台静默试装」）；</li>
 *   <li>{@code autoInstallPluginsSilently} 起一个守护线程 {@code dsh plugin add}，
 *       把这个早已停更的插件从 registry 装回来；</li>
 *   <li>主线程紧接着跑的 {@code migrateLegacyMobileAdapt()} 是毫秒级的 File 判断，
 *       那一刻补装还没落地 —— 它看到干净环境就零成本退出，等于白跑；</li>
 *   <li>补装线程收尾还顺手 {@code ensureBuiltinBundles()} 把注册补齐，旧插件正式复活。</li>
 * </ol>
 *
 * <p>用户点的是自动恢复弹窗，并不觉得那算「恢复备份」，所以现象看起来像「凭空回来」。
 * 两个插件改造同一批 DOM/CSS，同时激活必然打架：抽屉/浮层出两份、事件绑定两遍。
 *
 * <p><b>为什么把清单单独拎出来</b>：「这个插件已经下线了」这条知识原先只有
 * {@code migrateLegacyMobileAdapt()} 一个人知道，而它是「收敛型」的（发现痕迹才动手），
 * 管不住并发跑在它后面的补装线程。这个项目已经栽过四次「同一份判断散落两处」，
 * 所以清单只留一份，由自动补装的执行点去问，别在每条调用链上各写一遍判据。
 *
 * <p>只拦「程序替用户做决定」的路径。用户自己在插件市场点安装不拦 —— 那是他的选择，
 * 但界面会把冲突说清楚（见 PluginFragment）。
 */
final class DeprecatedPlugins {

    private DeprecatedPlugins() {
    }

    /** 旧的内置移动端 UI 适配（Hotsteel2901/dsh-client-ui-mobile-adapt，作者长期停更）。 */
    static final String LEGACY_MOBILE_ADAPT = "dsh-client-ui-mobile-adapt";

    /** 名字 → 给用户看的一句话原因（会原样出现在恢复报告与插件页提示里）。 */
    private static final java.util.Map<String, String> REASONS;

    static {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put(LEGACY_MOBILE_ADAPT,
                "已由内置的 @dsh-external/dsh-mobile-nav 接替（原插件长期停更）；"
                        + "两者改造同一批界面元素，同时启用会出两份抽屉和浮层");
        REASONS = java.util.Collections.unmodifiableMap(m);
    }

    /** 是否已弃用（不区分首尾空白；null 视为否）。 */
    static boolean isDeprecated(String name) {
        return name != null && REASONS.containsKey(name.trim());
    }

    /** 给用户看的原因；不在清单里时返回空串（调用方可直接拼接，不必判空）。 */
    static String reason(String name) {
        if (name == null) return "";
        String r = REASONS.get(name.trim());
        return r == null ? "" : r;
    }
}
