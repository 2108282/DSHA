package com.deepseekharness.app;

/**
 * 「为激活 DeviceOwner 临时冻结应用」这件事的记录与判据。
 *
 * <p><b>这套流程最大的风险不是激活失败，是冻结之后没解冻。</b>用户的推送、支付、社交全停着，
 * 而他不知道为什么、更不知道怎么恢复 —— 那比多一层保活的收益严重得多。所以：
 * <ul>
 *   <li>要冻的清单先<b>落盘</b>再动手，落在 rootfs 里而不是 SharedPreferences ——
 *       后者「清除数据」就没了，而清数据恰恰是用户遇到问题时的第一个动作；</li>
 *   <li>每次启动都检查一遍残留，有就立刻解冻（{@code thawLeftovers}）；</li>
 *   <li>冻结用 {@code pm disable-user} 而不是卸载，它是可逆的。</li>
 * </ul>
 *
 * <p><b>刻意不做的事：不重启设备。</b>上游教程里「清完账号重启一次再激活」的说法很常见，
 * 但同一批讨论里也有人冻着 Google 服务重启之后开不了机。自动化流程绝不能带用户走这条路 ——
 * 我们只在不重启的前提下试一次，不成就如实报告。
 */
public final class FrozenApps {

    private FrozenApps() {
    }

    /** 记录文件（rootfs 内相对路径）。 */
    public static final String FILE = "root/.dsh/.dsha-frozen-apps.json";

    /**
     * 绝不冻结的包：冻了会让手机当场不可用，或者让我们自己失联。
     *
     * <p>{@code com.android.shell} 是 adb 命令的执行者 —— 冻了它，解冻命令自己也发不出去，
     * 这是唯一一个「冻错了就没救」的包。{@code com.android.settings} 是用户最后的自救入口。
     */
    private static final String[] NEVER = {
            "com.deepseekharness.app",
            "android",
            "com.android.shell",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.providers.contacts",
            "com.android.keychain",
    };

    /** 这个包能不能冻。 */
    public static boolean freezable(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim();
        if (p.isEmpty() || p.indexOf('.') < 0) return false;
        for (String n : NEVER) {
            if (n.equals(p)) return false;
        }
        // com.android.providers.* 之外的 com.android.* 大多也是系统件，但其中确实有持账号的
        // （例如部分 ROM 的同步服务），一律排除会让激活永远差一个账号 —— 所以只挡明确列出的。
        return true;
    }

    /**
     * 高风险但通常必须冻的包。冻它们不会让手机不可用，但会让依赖它们的应用当场报错，
     * 而且<b>冻着重启有变砖的报告</b>。UI 上要单独点出来。
     */
    public static boolean highRisk(String pkg) {
        if (pkg == null) return false;
        return pkg.startsWith("com.google.android.gms")
                || pkg.startsWith("com.google.android.gsf")
                || pkg.equals("com.xiaomi.account")
                || pkg.equals("com.huawei.hwid")
                || pkg.startsWith("com.samsung.android.mobileservice");
    }

    /** 记录序列化：一行一个包名的极简格式。JSON 库都不用 —— 越简单的格式越不会在
     *  「需要它救命」的时候解析失败。 */
    public static String serialize(java.util.List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.trim().isEmpty()) continue;
            sb.append(p.trim()).append('\n');
        }
        return sb.toString();
    }

    public static java.util.List<String> parse(String txt) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (txt == null) return out;
        for (String line : txt.split("\r?\n")) {
            String t = line.trim();
            if (!t.isEmpty() && t.indexOf('.') > 0) out.add(t);
        }
        return out;
    }

    /** 冻结一个包的命令。{@code --user 0} 与激活命令保持一致。 */
    public static String freezeCmd(String pkg) {
        return "pm disable-user --user 0 " + pkg;
    }

    /** 解冻命令。 */
    public static String thawCmd(String pkg) {
        return "pm enable " + pkg;
    }

    /** 解冻是否成功：pm enable 成功会打印 "new state: enabled"。 */
    public static boolean thawed(String out) {
        if (out == null) return false;
        String s = out.toLowerCase();
        return s.contains("enabled") || s.contains("new state");
    }
}
