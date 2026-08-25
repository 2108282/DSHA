package com.deepseekharness.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 容器运行时的「能不能用」体检 —— 专门对付一类现有兜底完全接不住的故障：
 * <b>容器起得来，但里面的 glibc 一调 sysconf 就 abort。</b>
 *
 * <p><b>真实现场</b>（用户反馈）：
 * <pre>
 * Fatal glibc error: ../sysdeps/unix/sysv/linux/sysconf-sigstksz.h:25
 *   (sysconf_sigstksz): assertion failed: minsigstacksize != 0
 * [proroot] SIGSEGV pc=… code=-6   ← 二次崩溃：assert 调 abort，abort 又在信号层炸一次
 * </pre>
 *
 * <p><b>机理。</b> glibc 的 {@code sysconf_sigstksz()} 读 {@code GLRO(dl_minsigstacksize)}
 * 并断言它非 0。这个值只有一个来源：进程启动时从 auxv 读 {@code AT_MINSIGSTKSZ}；
 * 读之前 glibc 会先填架构默认常量，而 <b>arm64 上那个常量是非 0 的</b>
 * （只有 x86 故意填 0，因为它要用 CPUID 自己算 XSAVE 帧大小再回填）。
 * 所以 arm64 上它为 0 只有一种解释：<b>auxv 里存在 AT_MINSIGSTKSZ 这一项但值是 0</b> ——
 * 容器重建 auxv 时把这项带上却填了个零，把 glibc 本来正确的默认值覆盖掉了。
 * 内核压根不提供这一项反而没事（那时用默认常量）。
 * 顺带一提，AT_MINSIGSTKSZ 目前只有 arm64 内核定义，所以这个坑天然只在 arm64 上踩到。
 *
 * <p><b>为什么必须单独处理。</b> Ubuntu 24.04 是 glibc 2.39，{@code _GNU_SOURCE} 下
 * {@code SIGSTKSZ} 宏已经等于 {@code sysconf(_SC_SIGSTKSZ)}，而 <b>Python 解释器初始化
 * 时就会调它</b>（faulthandler 装 sigaltstack）。于是容器里所有 python3 一启动就 abort，
 * 整套自愈体系（selftest / heal-* / restore-merge / backup-prepare / flatten-l2s /
 * adb-shell / fix-stale-bundles 内嵌的 python3）一起哑掉 —— 而 App 察觉不到：
 * 现有降级判据是「运行时文件缺失」和「连续 3 次 Web 启动失败」，这两条都不覆盖
 * 「脚本 abort」，Web 反而起得来（Node 未必走这条路径）。
 *
 * <p>所以探针刻意<b>不用 python</b> —— 它正是受害者。判据全是字符串匹配，
 * 因此这个类不依赖任何 Android API，能进 pure-logic 断言集用真实报错原文回归。
 */
final class RuntimeHealth {

    /** SIGABRT 的退出码：128 + 6。glibc 的 assert 失败走 abort()，就是这个值。 */
    static final int EXIT_SIGABRT = 134;

    static final class Probe {
        /** 容器内的 glibc 起不来（致命，必须换运行时）。 */
        boolean fatal;
        /** 根因已确认：auxv 里的 AT_MINSIGSTKSZ 是 0。 */
        boolean minsigstkszZero;
        /** auxv 里压根没有这一项（正常情况，glibc 会用架构默认常量）。 */
        boolean auxvEntryAbsent;
        /** python3 的退出码，取不到时 -1。 */
        int pythonExit = -1;
        /** 给用户看的一句话结论。 */
        String reason = "";
        /** 下一步该做什么。 */
        String advice = "";

        boolean healthy() {
            return !fatal;
        }
    }

    private RuntimeHealth() {
    }

    /**
     * 探针脚本。刻意只用 coreutils 与 shell 内建：
     * {@code /bin/true} 自己不调 sysconf，所以即便环境已经坏了它也能跑完，
     * 用来把 auxv 打出来。python3 那一段是直接验受害者。
     */
    static String probeScript() {
        return "echo PROBE_BEGIN\n"
                // LD_SHOW_AUXV 让 ld.so 把 auxv 打出来；/bin/true 不碰 sysconf，坏环境下也能跑完
                + "AUX=$(LD_SHOW_AUXV=1 /bin/true 2>&1 | grep -i minsigstksz || true)\n"
                + "if [ -z \"$AUX\" ]; then echo 'AUXV_NO_MINSIGSTKSZ'; else echo \"AUXV: $AUX\"; fi\n"
                // 分两步拿退出码：写成 python3 … | head 的话 $? 是 head 的
                + "PYOUT=$(python3 -c pass 2>&1); PYRC=$?\n"
                + "echo \"PYEXIT=$PYRC\"\n"
                + "echo \"$PYOUT\" | head -4\n"
                + "echo PROBE_END\n";
    }

    // LD_SHOW_AUXV 打的值可能是十六进制（0x1400）也可能是十进制。
    // 只写 (\d+) 会在 "0x1400" 上匹配到开头那个 0 —— 把一台好机器判成坏的，
    // 后果是所有人白白失去 proroot 加速。两种进制都吃。
    //
    // 值那一段用 [^0-9\n] 而不是 \D：\D 吃换行，于是我们自己打的
    // "AUXV_NO_MINSIGSTKSZ\nPYEXIT=0" 会被读成「MINSIGSTKSZ 的值是 0」——
    // 内核压根不提供这一项的正常机器全被判成不兼容。断言集里有这条回归。
    private static final Pattern AUXV_VALUE = Pattern.compile(
            "(?i)minsigstksz[^0-9\\n]{0,12}(0x[0-9a-f]+|\\d+)");
    private static final Pattern PY_EXIT = Pattern.compile("PYEXIT=(-?\\d+)");

    private static long parseAuxvValue(String s) {
        String t = s.trim().toLowerCase(java.util.Locale.US);
        return t.startsWith("0x")
                ? Long.parseLong(t.substring(2), 16)
                : Long.parseLong(t, 10);
    }

    /** 解析探针输出。也能直接吃用户贴过来的崩溃原文（那种情况下没有 PYEXIT 行）。 */
    static Probe parse(String out) {
        Probe p = new Probe();
        if (out == null || out.isEmpty()) {
            p.reason = "探针没有任何输出";
            p.advice = "容器可能压根没起来，先看安装步骤是否完成";
            p.fatal = false;   // 没证据就别乱判死刑：宁可漏报也不误报
            return p;
        }

        Matcher m = PY_EXIT.matcher(out);
        if (m.find()) {
            try {
                p.pythonExit = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        p.auxvEntryAbsent = out.contains("AUXV_NO_MINSIGSTKSZ");
        m = AUXV_VALUE.matcher(out);
        // 探针已经明确说「没有这一项」时不要再去解析值：那行标记里本身带着
        // MINSIGSTKSZ 字样，任何宽松的正则都会在它后面捡到一个数字。
        if (!p.auxvEntryAbsent && m.find()) {
            try {
                // 必须走 parseAuxvValue：LD_SHOW_AUXV 实际打的是 "AT_MINSIGSTKSZ: 0x1400"，
                // 直接 Integer.parseInt("0x1400") 抛 NumberFormatException 被 catch 吞掉，
                // 于是这一路判据在真机上从来没生效过 —— 只是恰好和「健康」的结论重合，
                // 掩盖了 auxv=0x0 而 python3 又取不到退出码时的漏报。
                p.minsigstkszZero = parseAuxvValue(m.group(1)) == 0;
            } catch (NumberFormatException ignored) {
            }
        }

        boolean glibcFatal = out.contains("Fatal glibc error")
                || out.contains("minsigstacksize != 0")
                || out.contains("sysconf_sigstksz");
        boolean aborted = p.pythonExit == EXIT_SIGABRT;

        if (glibcFatal || aborted || p.minsigstkszZero) {
            p.fatal = true;
            if (p.minsigstkszZero || glibcFatal) {
                p.reason = "当前容器运行时给 glibc 传了一个为 0 的 AT_MINSIGSTKSZ，"
                        + "导致容器里所有 Python 程序一启动就 abort";
            } else {
                p.reason = "容器内的 python3 启动即被 abort（退出码 " + p.pythonExit + "）";
            }
            p.advice = "已切回 proot 运行时。这只影响速度，功能不受影响 —— "
                    + "自检、备份、自愈脚本都会恢复正常。";
            return p;
        }

        p.reason = p.auxvEntryAbsent
                ? "运行时正常（auxv 没有 AT_MINSIGSTKSZ，glibc 用架构默认值，这是好事）"
                : "运行时正常";
        p.advice = "";
        return p;
    }

    /** 一行摘要，给活动日志与自检报告用。 */
    static String summary(Probe p) {
        if (p == null) return "";
        if (p.healthy()) return "✅ 容器运行时兼容性：" + p.reason;
        return "❌ 容器运行时不兼容：" + p.reason + "\n→ " + p.advice;
    }
}
