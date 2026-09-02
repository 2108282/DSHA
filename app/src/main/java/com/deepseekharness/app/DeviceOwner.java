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

    /**
     * 从 {@code dumpsys account list} 里理出「哪些应用持有账号」。
     *
     * <p>为什么需要这个：真机上一台日常主力机能有二十个账号，而用户在设置里退掉自己的
     * 登录账号只减掉一个 —— 剩下的都是各家 App 通过 AccountManager 注册的（厂商服务、
     * 微信、支付宝、各种推送 SDK）。只报「还有 19 个」等于让用户瞎猜，把类型和包名列出来
     * 他才知道要冻结谁，以及这件事到底值不值得干。
     *
     * <p>两段信息拼起来：账号行里的 {@code type=xxx} 给出账号类型，
     * {@code RegisteredServicesCache} 段里 {@code AuthenticatorDescription {type=xxx}} 后面
     * 跟着 {@code ComponentInfo{包名/...}} —— 包名才是用户能在设置或冻结工具里找到的东西。
     *
     * @return 可读的一段话；解析不出东西时返回空串（调用方就别显示这一段）
     */
    public static String describeAccountOwners(String dumpsys) {
        if (dumpsys == null || dumpsys.isEmpty()) return "";
        // type → 出现次数
        java.util.LinkedHashMap<String, Integer> types = new java.util.LinkedHashMap<>();
        // type → 包名
        java.util.HashMap<String, String> pkgOf = new java.util.HashMap<>();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("type=([A-Za-z0-9_.\\-]+)").matcher(dumpsys);
        java.util.regex.Matcher svc = java.util.regex.Pattern.compile(
                        "AuthenticatorDescription\\s*\\{type=([A-Za-z0-9_.\\-]+)}[^{]*ComponentInfo\\{([A-Za-z0-9_.\\-]+)/")
                .matcher(dumpsys);
        while (svc.find()) {
            pkgOf.put(svc.group(1), svc.group(2));
        }
        // 账号行里的 type 才算「持有账号」；服务缓存里的 type 只是声明了能力
        for (String line : dumpsys.split("\r?\n")) {
            if (!line.contains("Account {") && !line.trim().startsWith("Account {")) continue;
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("type=([A-Za-z0-9_.\\-]+)").matcher(line);
            if (mm.find()) {
                String t = mm.group(1);
                types.put(t, types.getOrDefault(t, 0) + 1);
            }
        }
        if (types.isEmpty()) {
            // 有些 ROM 的 dumpsys 不打印 Account 明细，只给总数 —— 退一步用服务缓存里的类型，
            // 至少能告诉用户「哪些应用注册了账号服务」，方向是对的
            if (pkgOf.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("这台机器上注册过账号服务的应用（不一定都真的存着账号）：\n");
            int n = 0;
            for (java.util.Map.Entry<String, String> e : pkgOf.entrySet()) {
                if (n++ >= 12) {
                    sb.append("  … 还有更多");
                    break;
                }
                sb.append("  ").append(e.getValue()).append("（").append(e.getKey()).append("）\n");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder("持有账号的应用：\n");
        int n = 0;
        for (java.util.Map.Entry<String, Integer> e : types.entrySet()) {
            if (n++ >= 12) {
                sb.append("  … 还有 ").append(types.size() - 12).append(" 类没列\n");
                break;
            }
            String pkg = pkgOf.get(e.getKey());
            sb.append("  ").append(pkg == null ? e.getKey() : pkg)
                    .append(e.getValue() > 1 ? "（" + e.getValue() + " 个）" : "")
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 从 {@code dumpsys account list} 里取出「持有账号的应用包名」，供自动冻结用。
     *
     * <p>只认服务缓存里映射得出包名的那些 —— 拿不到包名就没法冻，也不该瞎猜。
     * 顺序保持 dumpsys 里的出现顺序，方便和 {@link #describeAccountOwners} 的展示对上。
     */
    public static java.util.List<String> accountHolderPkgs(String dumpsys) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (dumpsys == null || dumpsys.isEmpty()) return new java.util.ArrayList<>(out);
        java.util.HashMap<String, String> pkgOf = new java.util.HashMap<>();
        java.util.regex.Matcher svc = java.util.regex.Pattern.compile(
                        "AuthenticatorDescription\\s*\\{type=([A-Za-z0-9_.\\-]+)}[^{]*ComponentInfo\\{([A-Za-z0-9_.\\-]+)/")
                .matcher(dumpsys);
        while (svc.find()) {
            pkgOf.put(svc.group(1), svc.group(2));
        }
        for (String line : dumpsys.split("\r?\n")) {
            if (!line.contains("Account {")) continue;
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("type=([A-Za-z0-9_.\\-]+)").matcher(line);
            if (mm.find()) {
                String pkg = pkgOf.get(mm.group(1));
                if (pkg != null) out.add(pkg);
            }
        }
        // 有些 ROM 不打印账号明细，只能退一步：把注册过账号服务的应用都算上。
        // 宁可多冻几个（都会解冻），也别因为解析不到而让整条路走不通。
        if (out.isEmpty()) out.addAll(pkgOf.values());
        return new java.util.ArrayList<>(out);
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
            // 实话要说在前面：一台日常主力机能有二十个账号，用户在设置里退掉自己登录的那个
            // 只减掉一个，剩下的全是各家 App 通过 AccountManager 注册的。这种量级不是
            // 「再删删就好了」，而是要冻结十几个应用 —— 与其让人白折腾半天最后恢复出厂，
            // 不如直接讲清楚这条路在这台机器上走不通。
            String extra = accounts >= 5
                    ? "\n\n说实话：" + accounts + " 个账号意味着要冻结十几个应用（厂商服务、社交、"
                    + "支付、推送 SDK 都会注册），日常主力机基本做不到，也不值得为多一层保活折腾到"
                    + "那个程度。真想要这层能力，用备用机或刚重置过的设备更实际。\n"
                    + "现有的保活（前台服务 + 电池白名单 + ADB 分层重连）在多数机器上已经够用。"
                    : "";
            return new Precheck(false, "设备上还有 " + accounts + " 个账号",
                    "DeviceOwner 要求账号数为 0。先到「设置 → 账号」把所有账号删掉；"
                            + "删完还显示有的话，说明某些应用仍持有引用（微信、Telegram、厂商服务常见），"
                            + "需要先冻结或卸载它们，然后**重启一次**再回来。" + extra);
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
