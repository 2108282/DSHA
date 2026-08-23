package com.deepseekharness.app;

/**
 * 危险 shell 命令检测：删除/格式化/卸载/重启等破坏性操作
 * 命中后需要用户确认才允许执行。
 */
/*
 * 定位与局限（重要，别让后来人误解这道守卫的作用）：
 *
 * 它防的是「误操作」和「明显的恶意命令」，**不是**定向攻击。shell 的表达能力决定了
 * 任何黑名单都能绕过：变量拼接、编码执行、间接调用……追是追不完的。真正的防线是
 * 另外三层 —— 3090 桥的 token 鉴权（未授权者进不来）、关键操作的用户确认（要人真的
 * 点一下）、以及 proot 容器边界（碰不到宿主系统）。
 *
 * 所以这里的取舍是「宁可漏判，不可滥判」：把 > 和 mv 整体判危会让确认弹窗变成噪音，
 * 用户几次之后就学会无脑点允许 —— 那时连真正危险的命令也拦不住了。只在指向关键
 * 路径时才判危，就是这个原因。
 */
public final class DangerShellGuard {

    private DangerShellGuard() {
    }

    private static final String[] PATTERNS = {
            // 删除
            "rm -rf", "rm -r", "rm -f", "rm -",
            "rmdir", "unlink", "truncate", "-delete",
            "wipe", "erase",
            // 格式化/分区/底层写入
            "dd if=", "mkfs", "mkfs.", "fdisk", "format",
            "fastboot", "flash", "recovery",
            // 关机/重启
            "reboot", "shutdown", "poweroff", "halt",
            // 应用管理（破坏性）
            "pm uninstall", "pm clear", "pm reset",
            "sm format", "settings reset",
            // 混淆/绕过模式
            "base64", "| sh", "| bash", "sh -c", "toybox", "eval",
            // 权限破坏：文件还在但用不了了，比删掉更难自查
            "chmod 000", "chattr +i",
    };

    /** 覆盖或移动这些路径等同于毁掉环境/用户数据，即便命令本身不在黑名单里。
     *  单独列出来是为了不把 > 和 mv 整体判危 —— agent 平时就在写文件、挪文件。 */
    private static final String[] CRITICAL_PATHS = {
            "/root/.dsh", "/usr/local/lib/node_modules", "/usr/local/bin",
            "/etc/", "/usr/lib", "/system/", "/data/data",
    };

    /** 判断命令是否属于危险操作 */
    public static boolean isDangerous(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase();
        // adb shell 通道：检查 shell 后的命令串是否含危险操作/混淆绕过（普通操作不拦）
        int ai = c.indexOf("adb");
        if (ai >= 0) {
            // 按关键字实际长度切（"shell"=5 / "exec-out"=8 / "exec-in"=7），
            // 旧实现固定 si+6 会把 exec-out/exec-in 切成 'ut ...'/'n ...' 导致漏检/误检
            int si = c.indexOf("shell", ai);
            int kwLen = 5;
            int ei = c.indexOf("exec-out", ai);
            if (ei >= 0 && (si < 0 || ei < si)) { si = ei; kwLen = 8; }
            int ii = c.indexOf("exec-in", ai);
            if (ii >= 0 && (si < 0 || ii < si)) { si = ii; kwLen = 7; }
            if (si >= 0 && si - ai < 40) {
                if (matchesDanger(c.substring(si + kwLen))) return true;
            }
        }
        return matchesDanger(c);
    }

    private static boolean matchesDanger(String c) {
        for (String p : PATTERNS) {
            if (c.contains(p)) return true;
        }
        // 单独 rm 文件（rm 后跟空格或命令结尾），如 rm /sdcard/xxx
        if (c.matches(".*\\brm(\\s|$).*")) return true;
        // 覆盖写或移动关键路径：`> /root/.dsh/x` 能悄悄清空文件，
        // `mv /root/.dsh /tmp` 能让整套环境凭空消失，两者都不含任何黑名单词
        if (c.contains(">") || c.matches(".*\\bmv(\\s|$).*")) {
            for (String path : CRITICAL_PATHS) {
                if (c.contains(path)) return true;
            }
        }
        // 分隔符混淆：rm${IFS}-rf、r''m 这类写法能躲过上面所有字面匹配。
        // 逐个模式去追是追不完的，不如把「出现这类构造」本身当成可疑 ——
        // 正常命令几乎用不到它们。
        if (c.contains("${ifs}") || c.contains("$ifs")) return true;
        if (c.matches(".*[a-z]'\\s*'[a-z].*")) return true;
        return false;
    }
}
