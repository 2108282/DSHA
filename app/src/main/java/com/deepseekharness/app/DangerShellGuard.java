package com.deepseekharness.app;

/**
 * 危险 shell 命令检测：删除/格式化/卸载/重启等破坏性操作
 * 命中后需要用户确认才允许执行。
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
        return false;
    }
}
