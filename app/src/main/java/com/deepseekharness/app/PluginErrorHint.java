package com.deepseekharness.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 dsh 的启动日志里认出「是哪个插件把 Web 弄挂的」，并翻译成人话。
 *
 * <p>为什么值得单独做：插件类故障的原始报错基本没法读 ——
 * <pre>
 * Error: dsh: plugin tree failed to load: dsh: 1 entry did not activate
 * dsh-device-shell-guide: pending (waiting for service: systemPrompt)
 *     at boot (file:///usr/local/lib/node_modules/@deepseek-ai/dsh/…/index.js:1187:9)
 * </pre>
 * 用户看到的是「Web 打不开」加一屏栈，而真正有用的信息只有中间那半行插件名。
 * 三个用户为此清过数据、重装过 App、清过环境 —— 全都没用，因为问题在一个插件里。
 *
 * <p>刻意不依赖任何 Android API：判据全是字符串匹配，能进 pure-logic 断言集用真实
 * 日志样本回归。这类「靠正则读别人日志」的代码最容易在上游改一句话之后静默失效，
 * 所以每条规则都配了样本。
 *
 * <p>与 selftest.py 里 {@code scan_boot_blockers()} 的关系：那边只认
 * {@code did not activate} 这一种，且只在用户主动跑自检时才看。这里覆盖面更宽，
 * 用在「Web 起不来」的当场。两处判据重叠但不共享代码（一个 Java 一个 Python）——
 * 改动其中一个时记得看另一个。
 */
final class PluginErrorHint {

    /** 官方包不算「出问题的插件」：插件故障时堆栈里几乎全是官方引导代码的路径。 */
    private static final String[] OFFICIAL_PREFIX = {"@deepseek-ai/", "@standard-schema/"};

    static final class Hint {
        /** 插件名；认不出具体是谁时为空串。 */
        String plugin = "";
        /** 发生了什么（人话）。 */
        String what = "";
        /** 怎么办（人话，且必须是用户点得到的动作）。 */
        String fix = "";
        /** 命中的那一行原文，留给愿意深究的人。 */
        String raw = "";
    }

    private PluginErrorHint() {
    }

    // ① 硬依赖：插件声明的服务在当前环境不存在 → entry 永远 pending → 整棵树加载失败
    private static final Pattern PENDING = Pattern.compile(
            "([\\w@/.-]+):\\s*pending\\s*\\(waiting for service[s]?:\\s*([^)]*)\\)");
    // ② bundles 里注册了但解析不到实体
    private static final Pattern UNRESOLVED = Pattern.compile(
            "cannot resolve profile bundle\\s*[\"']([^\"']+)[\"']");
    // ③ 插件读了没声明的服务
    private static final Pattern NO_INJECT = Pattern.compile(
            "cannot get property\\s*[\"']([\\w.-]+)[\"']\\s*without inject");
    // ④ 堆栈里的第三方插件路径（node_modules/<name> 或 DSHA 内置实体 /root/dsha-<name>）
    private static final Pattern NM_PATH = Pattern.compile(
            "node_modules/((?:@[\\w.-]+/)?[\\w.-]+)");
    private static final Pattern DSHA_PATH = Pattern.compile("/root/(dsha-[\\w.-]+)");

    /** 认不出插件问题时返回 null。 */
    static Hint detect(String log) {
        if (log == null || log.isEmpty()) return null;

        Matcher m = PENDING.matcher(log);
        if (m.find()) {
            Hint h = new Hint();
            h.plugin = m.group(1);
            String svc = m.group(2) == null ? "" : m.group(2).trim();
            h.raw = m.group();
            h.what = "插件「" + h.plugin + "」要求的服务" + (svc.isEmpty() ? "" : "（" + svc + "）")
                    + "在当前模式下不存在，于是它一直等不到激活。"
                    + "dsh 认为整棵插件树都没加载成功，所以其它插件跟着一起没起来 —— "
                    + "Web 打不开是这个连带的结果，不是数据坏了。";
            h.fix = "到「设置」页点「自检 & 修补」：能自动改的它会就地改好（把硬依赖改成运行时注入），"
                    + "然后回启动页点「重启」。也可以先到「插件」页把这个插件关掉，先让 Web 起来。";
            return h;
        }

        m = UNRESOLVED.matcher(log);
        if (m.find()) {
            Hint h = new Hint();
            h.plugin = m.group(1);
            h.raw = m.group();
            h.what = "插件「" + h.plugin + "」在配置里注册着，但它的实体找不到了"
                    + "（装了一半、被清理过，或者换设备恢复备份后路径变了）。"
                    + "dsh 启动时解析不到就直接报错退出。";
            h.fix = "点「设置」→「自检 & 修补」：解析不到的会被摘掉、内置的会被补回。"
                    + "如果它是你自己装的插件，到「插件」页重新安装一次即可。";
            return h;
        }

        m = NO_INJECT.matcher(log);
        if (m.find()) {
            Hint h = new Hint();
            String svc = m.group(1);
            h.plugin = guessPluginFromStack(log);
            h.raw = m.group();
            h.what = (h.plugin.isEmpty() ? "某个插件" : "插件「" + h.plugin + "」")
                    + "读了它没有声明的服务（" + svc + "）。dsh 不允许这么做 —— "
                    + "读未声明的服务会直接抛错，而不是返回空值。";
            h.fix = "这是插件自己的问题。点「设置」→「自检 & 修补」看能否自动处理；"
                    + "不行就到「插件」页先禁用它，并把这段报错反馈给插件作者。";
            return h;
        }

        if (log.contains("_pnpmPlaceholder")) {
            Hint h = new Hint();
            h.plugin = guessPluginFromStack(log);
            h.raw = "_pnpmPlaceholder";
            h.what = (h.plugin.isEmpty() ? "有插件" : "插件「" + h.plugin + "」")
                    + "只装了个空壳：pnpm 把下载好的目录改名成了 .ignored_*，"
                    + "原位只剩一个占位的 package.json。dsh 加载到的是空壳，等于插件不存在。";
            h.fix = "点「设置」→「自检 & 修补」，它会把真实目录换回来（空壳挪去 .shell-backup/ 保留）。";
            return h;
        }

        if (log.contains("did not activate")) {
            Hint h = new Hint();
            h.plugin = guessPluginFromStack(log);
            h.raw = "did not activate";
            h.what = "有插件没能激活，dsh 因此判定整棵插件树加载失败 —— "
                    + "所以 Web 起不来的原因往往只是其中一个插件，而不是环境坏了。";
            h.fix = "点「设置」→「自检 & 修补」，报告里会点名是哪个插件卡住。";
            return h;
        }

        if ((log.contains("YAMLException") || log.contains("YAMLSemanticError"))
                && log.contains("cordis.patch")) {
            Hint h = new Hint();
            h.raw = "YAMLException in cordis.patch";
            h.what = "插件层的补丁文件（cordis.patch.yml）语法坏了，dsh 解析配置时直接抛异常。"
                    + "常见于往一个空容器（[] 或 {}）后面追加内容。";
            h.fix = "点「设置」→「自检 & 修补」：它会校验并回滚坏掉的 patch（改前留 .bak）。";
            return h;
        }

        return null;
    }

    /** 从堆栈路径里猜插件名，跳过官方包 —— 插件出问题时堆栈里几乎全是官方引导代码。 */
    private static String guessPluginFromStack(String log) {
        Matcher m = DSHA_PATH.matcher(log);
        if (m.find()) return m.group(1);
        m = NM_PATH.matcher(log);
        while (m.find()) {
            String name = m.group(1);
            if (isOfficial(name) || name.startsWith(".")) continue;
            return name;
        }
        return "";
    }

    private static boolean isOfficial(String name) {
        for (String p : OFFICIAL_PREFIX) {
            if (name.startsWith(p)) return true;
        }
        return name.equals("node_modules") || name.equals("react");
    }

    /** 拼成一段可以直接贴到界面上的提示（认不出返回空串）。 */
    static String describe(String log) {
        Hint h = detect(log);
        if (h == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("⚠ 看起来是插件的问题");
        if (!h.plugin.isEmpty()) sb.append("：").append(h.plugin);
        sb.append("\n").append(h.what).append("\n→ ").append(h.fix);
        return sb.toString();
    }
}
