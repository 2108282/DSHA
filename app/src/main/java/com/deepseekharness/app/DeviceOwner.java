package com.deepseekharness.app;

/**
 * DeviceOwner（设备所有者）激活的纯逻辑：预检判据、命令生成、输出解析。
 *
 * <p><b>为什么值得做。</b>DeviceOwner 是 Android 内置的系统级角色，拿到之后 agent 可以静默
 * 安装/卸载应用、授运行时权限、改系统设置；更要紧的是**持有它的 App 不会被系统杀** ——
 * 保活这件事我们一直在跟各家省电策略斗（Doze 白名单、前台服务、ADB 保活三层），
 * DeviceOwner 是根治的路子。它也不依赖 ADB 连接活着：无线调试会断、要重新配对，
 * 而 DeviceOwner 是持久的系统状态，重启也在。
 *
 * <p><b>为什么不抄 Dhizuku 的代码。</b>那个项目是 GPL-3.0，抄进来会传染整个 App（我们是 MIT）。
 * 思路可以看、结论可以借，代码一行不能要。这个类里的判据是照着上游 issue 和大量真机
 * 反馈自己整理的。
 *
 * <p><b>DSHA 相比 Dhizuku 的一个实际优势</b>：激活要跑 {@code dpm set-device-owner}，
 * Dhizuku 的用户得插电脑或者先装 Shizuku；我们容器里就有 adb、能连自己，
 * 所以整个过程可以在手机上一步走完。
 *
 * <p><b>但门槛很硬，UI 上必须说清</b>（否则用户折腾半天只能恢复出厂）：
 * <ul>
 *   <li>设备上<b>不能有任何账号</b> —— {@code dumpsys account list} 必须是 0。从设置里删掉
 *       往往不够：某些 App（微信、Telegram、厂商服务）仍持有账号引用，得先冻结或卸载；</li>
 *   <li>不能有主用户以外的用户 —— 分身、双开、访客都算（{@code pm list users} 只应剩 user 0）；</li>
 *   <li>部分 ROM 额外设卡：OPPO/ColorOS 只放行白名单与测试签名、Samsung Knox 触发后更麻烦、
 *       ColorOS 16 必须从设置里手工删账号（冻结 App 不算）；</li>
 *   <li>清完账号常常要<b>重启</b>才生效。</li>
 * </ul>
 * 结论：这功能只适合备用机或刚重置过的设备，日常主力机基本没戏。
 */
public final class DeviceOwner {

    private DeviceOwner() {
    }

    /** 我们的 DeviceAdmin 组件（dpm 命令与 DevicePolicyManager 都要用它）。 */
    public static final String PKG = "com.deepseekharness.app";
    public static final String RECEIVER = PKG + "/.DshaDeviceAdminReceiver";

    /**
     * 激活命令。
     *
     * <p>带 {@code --user 0} 是有意的：把权限范围限制在主用户。真机反馈里这一条能绕过一部分
     * 「设备上已有其他用户」的拒绝 —— 不加的话有分身/双开的机器直接失败，而删掉分身用户
     * 会连带卸载里面的应用，代价比限制范围大得多。
     */
    public static String activateCmd() {
        return "dpm set-device-owner --user 0 " + RECEIVER;
    }

    /** 退一步的方案：ProfileOwner。权限比 DeviceOwner 少，但门槛低不少，
     *  有用户报告「set-active-admin 之后 set-profile-owner」在 DeviceOwner 失败的机器上能成。 */
    public static String[] profileOwnerFallbackCmds() {
        return new String[]{
                "dpm set-active-admin --user 0 " + RECEIVER,
                "dpm set-profile-owner --user 0 " + RECEIVER,
        };
    }

    /** 移除命令。UI 上必须给这个入口 —— 上游最常见的求助就是「找不到怎么关，只能恢复出厂」。 */
    public static String removeHint() {
        return "在本页点「撤销设备所有者」；它调用 DevicePolicyManager.clearDeviceOwnerApp()，"
                + "不需要 adb。撤销之后 App 才能正常卸载。";
    }

    /** 预检要跑的两条命令（都是只读的）。 */
    public static String accountProbeCmd() {
        return "dumpsys account list";
    }

    public static String userProbeCmd() {
        return "pm list users";
    }

    /**
     * 从 {@code dumpsys account list} 的输出里数账号。
     *
     * <p>输出形如：
     * <pre>User UserInfo{0:User:4c13}:
     *   Accounts: 0</pre>
     * 多用户时会有多段，全部相加。
     *
     * @return 账号总数；{@code -1} 表示没解析出来 —— 那时别下结论，让用户自己看原文
     */
    public static int parseAccountCount(String dumpsys) {
        if (dumpsys == null || dumpsys.trim().isEmpty()) return -1;
        int total = -1;
        for (String line : dumpsys.split("\r?\n")) {
            String t = line.trim();
            if (!t.startsWith("Accounts:")) continue;
            String num = t.substring("Accounts:".length()).trim();
            // 有的 ROM 会写成 "Accounts: 2 (…)"，只取前面的数字
            int sp = num.indexOf(' ');
            if (sp > 0) num = num.substring(0, sp);
            try {
                int n = Integer.parseInt(num);
                total = (total < 0 ? 0 : total) + n;
            } catch (NumberFormatException ignored) {
                // 解析不了这一行就跳过，别把整次预检废掉
            }
        }
        return total;
    }

    /**
     * 从 {@code pm list users} 的输出里数用户。
     *
     * @return 用户数；{@code -1} 表示没解析出来
     */
    public static int parseUserCount(String out) {
        if (out == null || out.trim().isEmpty()) return -1;
        int n = 0;
        for (String line : out.split("\r?\n")) {
            if (line.contains("UserInfo{")) n++;
        }
        return n == 0 ? -1 : n;
    }

    /** 预检结论。{@code ok=false} 时 {@link #advice} 一定是可执行的下一步，不是「失败了」。 */
    public static final class Precheck {
        public final boolean ok;
        public final String reason;
        public final String advice;

        Precheck(boolean ok, String reason, String advice) {
            this.ok = ok;
            this.reason = reason;
            this.advice = advice;
        }
    }

    /**
     * 按两条探测输出给结论。
     *
     * <p>刻意不做「解析失败就当通过」：拿不到账号数时宁可让用户自己看一眼原文，
     * 也不要贸然去跑 set-device-owner —— 那条命令失败一次会在系统里留下 active admin，
     * 后面反而更难收拾。
     */
    public static Precheck precheck(String accountDump, String userList) {
        int accounts = parseAccountCount(accountDump);
        int users = parseUserCount(userList);

        if (accounts < 0) {
            return new Precheck(false, "读不到账号列表",
                    "在终端里跑一次 `adb shell dumpsys account list`，确认输出里 Accounts 是 0。"
                            + "读不到就先别激活 —— 失败的 set-device-owner 会留下 active admin，更难收拾。");
        }
        if (accounts > 0) {
            return new Precheck(false, "设备上还有 " + accounts + " 个账号",
                    "DeviceOwner 要求账号数为 0。先到「设置 → 账号」把所有账号删掉；"
                            + "删完还显示有的话，说明某些应用仍持有引用（微信、Telegram、厂商服务常见），"
                            + "需要先冻结或卸载它们，然后**重启一次**再回来。");
        }
        if (users > 1) {
            return new Precheck(false, "除主用户外还有 " + (users - 1) + " 个用户",
                    "分身、双开、访客都算。我们的激活命令已经带了 --user 0 把范围限制在主用户，"
                            + "多数机器这样就能过；仍然失败的话要删掉多余用户"
                            + "（`pm remove-user <id>`，注意里面的应用会一起卸载）。");
        }
        return new Precheck(true, "账号 0 个、只有主用户 —— 可以激活",
                "激活后 App 将无法直接卸载，必须先在本页撤销。备用机或刚重置的设备最适合。");
    }

    /** 激活命令的输出里认成功的判据。dpm 成功时会打印 "Success:"。 */
    public static boolean looksActivated(String dpmOutput) {
        if (dpmOutput == null) return false;
        String s = dpmOutput.toLowerCase();
        return s.contains("success") && s.contains("device owner");
    }

    /** 把 dpm 的常见失败原因翻成人话 —— 原文是一大段 Java 堆栈，用户看不出该做什么。 */
    public static String explainFailure(String dpmOutput) {
        if (dpmOutput == null || dpmOutput.trim().isEmpty()) {
            return "命令没有输出 —— 先确认 ADB 通道是通的（配置页「设备与权限」里那个开关）。";
        }
        String s = dpmOutput.toLowerCase();
        if (s.contains("already some accounts")) {
            return "系统说设备上还有账号。从设置里删账号往往不够 —— 有应用仍持有引用，"
                    + "跑 `adb shell dumpsys account list` 看是不是真的 0，把持有账号的应用冻结或卸载后重启再试。";
        }
        if (s.contains("already some users")) {
            return "系统说还有其他用户（分身/双开/访客）。删掉它们，或者接受 ProfileOwner 这个退让方案。";
        }
        if (s.contains("already set") || s.contains("already an admin")) {
            return "已经设过了 —— 强制停止 DSHA 再打开一次，看状态是不是变成「已激活」。";
        }
        if (s.contains("can't set package") || s.contains("cannot set package")) {
            return "系统拒绝了这个包。OPPO/ColorOS 只放行白名单与测试签名的应用，"
                    + "Samsung 在 Knox 触发后也会拒 —— 这两种情况没有软办法。";
        }
        if (s.contains("provisioningprecondition")) {
            return "系统的预置条件检查没过（通常还是账号或用户）。清干净后重启一次再试。";
        }
        return "激活失败。原文：" + dpmOutput.trim();
    }
}
