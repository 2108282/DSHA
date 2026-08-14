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
    };

    /** 判断命令是否属于危险操作 */
    public static boolean isDangerous(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase();
        // adb shell 通道：agent 用 adb shell "rm ..." 在设备上删除——检查 shell 后的命令串
        int ai = c.indexOf("adb");
        if (ai >= 0) {
            int si = c.indexOf("shell", ai);
            if (si >= 0 && si - ai < 40) {
                if (matchesDanger(c.substring(si + 6))) return true;
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
