package com.deepseekharness.app;

/**
 * 无 Android 依赖的纯逻辑断言集（LanAuth + AssetPath）。用 javac 直接编译运行，
 * 不需要设备、SDK、网络：
 *
 * <pre>bash tools/pure-logic-test.sh</pre>
 *
 * 每条用例都对应一个真实踩过的坑，不是为了凑覆盖率 —— 这些手写的字符串切分出错时
 * 症状都隔着一层（400 / 一直转圈 / 静默泄漏 / 文件落到别处），只靠读代码看不出来。
 */
public final class PureLogicTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- stripTokenFromRequestLine ----------
        // 回归：token 是唯一参数时，原实现把 HTTP 版本一起吃掉（→ "GET /"），
        // 后端 Node parser 当畸形请求直接 400。这是最常见的场景：打开首页。
        eq("strip: 唯一参数", "GET / HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /?token=abc123 HTTP/1.1"));
        eq("strip: 参数在后", "GET /a?x=1 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?x=1&token=abc HTTP/1.1"));
        eq("strip: 参数在前", "GET /a?x=1 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?token=abc&x=1 HTTP/1.1"));
        eq("strip: 参数在中", "GET /a?x=1&y=2 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?x=1&token=abc&y=2 HTTP/1.1"));
        // 回归：[&]?token= 没有参数名边界，csrf_token / api_token 会被连带剥掉
        eq("strip: 不误删同后缀参数", "GET /a?csrf_token=z HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?csrf_token=z HTTP/1.1"));
        eq("strip: 无 query 原样返回", "GET /a HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a HTTP/1.1"));
        eq("strip: 保留 fragment", "GET /a#frag HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?token=t#frag HTTP/1.1"));
        eq("strip: POST 同样处理", "POST /api/x HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("POST /api/x?token=t HTTP/1.1"));
        eq("strip: 只有方法和目标（无版本）", "GET /a",
                LanAuth.stripTokenFromRequestLine("GET /a?token=t"));

        // ---------- queryToken ----------
        eq("query: 正常取值", "abc123", LanAuth.queryToken("GET /?token=abc123 HTTP/1.1"));
        // 回归：原来是整头 indexOf("token=")，xtoken= 的尾部也会命中并取到错误值
        eq("query: 不被同后缀参数误命中", null, LanAuth.queryToken("GET /?xtoken=bad HTTP/1.1"));
        eq("query: 无 query", null, LanAuth.queryToken("GET /a HTTP/1.1"));
        eq("query: 忽略 fragment", "t", LanAuth.queryToken("GET /a?token=t#x HTTP/1.1"));

        // ---------- tokenOk ----------
        final String T = "0123456789abcdef";
        // 回归：原来空 token 直接 return true（放行）。桥监听 0.0.0.0，
        // 放行等于同 WiFi 任意设备都能操作 dsh（agent 可执行 bash）。
        eqi("auth: 空 token 一律拒绝", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /?token=" + T, ""), ""));
        eqi("auth: query 命中 → 需回设 Cookie", LanAuth.AUTH_OK_SET_COOKIE,
                LanAuth.tokenOk(req("GET /?token=" + T, ""), T));
        eqi("auth: Cookie 命中", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "Cookie: dsha_token=" + T), T));
        eqi("auth: Cookie 与后端 dsha_t 共存", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "Cookie: dsha_t=zzz; dsha_token=" + T), T));
        eqi("auth: 显式头命中", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "X-DSHA-Token: " + T), T));
        // 回归：这是原实现「能用」的真正原因，也是 token 泄漏面 ——
        // 用户在 WebUI 里点任何外链，Referer 就把 token 送给对方站点。
        eqi("auth: Referer 里的 token 不算凭据", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /api/x",
                        "Referer: http://192.168.1.5:3081/?token=" + T), T));
        eqi("auth: 换过 token 时旧 Cookie 不挡新地址", LanAuth.AUTH_OK_SET_COOKIE,
                LanAuth.tokenOk(req("GET /?token=" + T, "Cookie: dsha_token=stale"), T));
        eqi("auth: 无凭据", LanAuth.AUTH_DENY, LanAuth.tokenOk(req("GET /", ""), T));
        eqi("auth: 显式头错值", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /", "X-DSHA-Token: wrong"), T));
        eqi("auth: WebSocket 握手带 Cookie 可过", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /api/ws",
                        "Upgrade: websocket\r\nCookie: dsha_token=" + T), T));

        // ---------- queryTokenFromTarget（3090 桥也用这一份）----------
        eq("target: 正常取值", "abc", LanAuth.queryTokenFromTarget("/exec?cmd=ls&token=abc"));
        // 回归：3090 桥原来是 query.indexOf("token=")，xtoken= 的尾部先命中 → 取到 junk 而误拒
        eq("target: 不被同后缀参数抢先", "abc",
                LanAuth.queryTokenFromTarget("/exec?xtoken=junk&token=abc"));
        eq("target: 无 token 参数", null, LanAuth.queryTokenFromTarget("/exec?cmd=ls"));
        eq("target: 无 query", null, LanAuth.queryTokenFromTarget("/exec"));

        // ---------- AssetPath：清单 asset 名当路径用之前的校验 ----------
        // asset 名会被直接拼成覆盖层下的相对路径落盘，带 ../ 就能覆盖 shared_prefs
        // （API key 密文、LAN token 都在里面）或者往 rootfs 里投文件。
        ok("asset: 普通文件名", AssetPath.isSafe("selftest.py"));
        ok("asset: 多级路径", AssetPath.isSafe("device-shell-guide/lib/index.js"));
        ok("asset: 允许点开头", AssetPath.isSafe(".keep"));
        ok("asset: 拒绝上跳", !AssetPath.isSafe("../shared_prefs/deepseekharness.xml"));
        ok("asset: 拒绝中间上跳", !AssetPath.isSafe("a/../../b.py"));
        ok("asset: 拒绝绝对路径", !AssetPath.isSafe("/etc/passwd"));
        ok("asset: 拒绝反斜杠", !AssetPath.isSafe("a\\..\\b"));
        ok("asset: 拒绝空段", !AssetPath.isSafe("a//b.py"));
        ok("asset: 拒绝单点段", !AssetPath.isSafe("./a.py"));
        ok("asset: 拒绝目录形", !AssetPath.isSafe("lib/"));
        ok("asset: 拒绝空串", !AssetPath.isSafe(""));
        ok("asset: 拒绝 null", !AssetPath.isSafe(null));
        ok("asset: 拒绝空格", !AssetPath.isSafe("a b.py"));
        ok("asset: 拒绝 NUL 截断", !AssetPath.isSafe("a.py\u0000.txt"));
        ok("asset: 拒绝超长", !AssetPath.isSafe(new String(new char[300]).replace('\0', 'a')));

        // ---------- BackupInspector：恢复前的备份体检 ----------
        // 它是「这份备份能不能用」的守门人：判错了要么放过一个截断包（用户以为恢复成功，
        // 实际少了一半会话），要么拦住一份好备份（更糟，人在急着恢复数据）。造三种包来验。
        try {
            java.io.File tmpDir = java.nio.file.Files.createTempDirectory("dsha-bi").toFile();

            java.io.File good = new java.io.File(tmpDir, "good.tar.gz");
            writeTarGz(good, new String[][]{
                    {".dsh/settings.yaml", "port: 3080\n"},
                    {".dsh/sessions/a.jsonl", "{\"x\":1}\n"},
                    {".dsh/sessions/b.jsonl", "{\"x\":2}\n"},
                    {".dsh/.dsha-backup-manifest.json", "{\"appVersion\": \"1.1.7\"}"},
            }, true);
            BackupInspector.Info gi = BackupInspector.inspect(good);
            ok("inspect: 正常包可读", gi.readable, "error=" + gi.error);
            ok("inspect: 认出是 DSHA 备份", gi.looksLikeDsha);
            ok("inspect: 数出会话文件", gi.sessionFiles == 2, "实际 " + gi.sessionFiles);
            ok("inspect: 认出清单", gi.hasManifest);
            eq("inspect: 读出备份方版本", "1.1.7", gi.appVersion);

            // 截断：把好包砍掉后 1/3。gzip 的 CRC/长度尾部就是为这种情况准备的。
            byte[] all = java.nio.file.Files.readAllBytes(good.toPath());
            java.io.File cut = new java.io.File(tmpDir, "cut.tar.gz");
            java.nio.file.Files.write(cut.toPath(),
                    java.util.Arrays.copyOf(all, all.length * 2 / 3));
            BackupInspector.Info ci = BackupInspector.inspect(cut);
            ok("inspect: 截断包判为不可用", !ci.readable, "error=" + ci.error);
            ok("inspect: 截断有说明", ci.error != null && !ci.error.isEmpty());

            // 不是 DSHA 备份：能读，但里面没有 .dsh
            java.io.File other = new java.io.File(tmpDir, "other.tar.gz");
            writeTarGz(other, new String[][]{{"photos/1.txt", "hello\n"}}, true);
            BackupInspector.Info oi = BackupInspector.inspect(other);
            ok("inspect: 无关包可读但不认", oi.readable && !oi.looksLikeDsha,
                    "readable=" + oi.readable + " dsha=" + oi.looksLikeDsha);

            // 空文件 / 不存在
            java.io.File empty = new java.io.File(tmpDir, "empty.tar.gz");
            //noinspection ResultOfMethodCallIgnored
            empty.createNewFile();
            ok("inspect: 空文件判为不可用", !BackupInspector.inspect(empty).readable);
            ok("inspect: 不存在的文件不炸",
                    !BackupInspector.inspect(new java.io.File(tmpDir, "nope.tar.gz")).readable);

            deleteRec(tmpDir);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL inspect: 用例本身抛异常 " + t);
        }

        // ---------- PluginErrorHint：把「哪个插件把 Web 弄挂了」从日志里认出来 ----------
        // 用的是用户真实贴过来的报错原文。这类靠正则读别人日志的代码，最容易在上游改一句
        // 话之后静默失效 —— 有样本才知道它还认得。
        {
            String realPending = "Error: dsh: plugin tree failed to load: dsh: 1 entry did not activate\n"
                    + "dsh-device-shell-guide: pending (waiting for service: systemPrompt)\n"
                    + "    at boot (file:///usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                    + "@deepseek-ai/dsh-app-boot/lib/index.js:1187:9)\n";
            PluginErrorHint.Hint h1 = PluginErrorHint.detect(realPending);
            ok("hint: 认出 pending 的插件名", h1 != null && "dsh-device-shell-guide".equals(h1.plugin),
                    h1 == null ? "null" : h1.plugin);
            ok("hint: 提到缺的服务", h1 != null && h1.what.contains("systemPrompt"));
            ok("hint: 给了可点的下一步", h1 != null && h1.fix.contains("自检"));
            ok("hint: describe 可直接上屏",
                    PluginErrorHint.describe(realPending).startsWith("⚠"));

            PluginErrorHint.Hint h2 = PluginErrorHint.detect(
                    "Error: cannot resolve profile bundle \"dsh-client-ui-mobile-adapt\"");
            ok("hint: 认出解析不到的 bundle",
                    h2 != null && "dsh-client-ui-mobile-adapt".equals(h2.plugin),
                    h2 == null ? "null" : h2.plugin);

            PluginErrorHint.Hint h3 = PluginErrorHint.detect(
                    "Error: cannot get property \"systemPrompt\" without inject\n"
                            + "    at file:///root/.dsh/profiles/web/node_modules/dsh-foo-plugin/lib/index.js:12:3\n");
            ok("hint: 从堆栈里猜出第三方插件",
                    h3 != null && "dsh-foo-plugin".equals(h3.plugin),
                    h3 == null ? "null" : h3.plugin);

            // 关键：官方包不能被当成「出问题的插件」—— 插件故障时堆栈里几乎全是官方路径
            PluginErrorHint.Hint h4 = PluginErrorHint.detect(
                    "Error: cannot get property \"x\" without inject\n"
                            + "    at file:///usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js:1:1\n");
            ok("hint: 不把官方包当成肇事插件",
                    h4 != null && h4.plugin.isEmpty(), h4 == null ? "null" : h4.plugin);

            ok("hint: 认出 pnpm 空壳",
                    PluginErrorHint.detect("_pnpmPlaceholder found in dsh-bar") != null);
            ok("hint: 认出坏掉的 patch",
                    PluginErrorHint.detect("YAMLException: bad indentation of /root/.dsh/cordis.patch.yml") != null);
            ok("hint: 正常日志不误报",
                    PluginErrorHint.detect("dsh web listening on http://127.0.0.1:3080") == null);
            ok("hint: 空输入不炸", PluginErrorHint.detect(null) == null
                    && PluginErrorHint.detect("") == null);
        }

        // ---------- RuntimeHealth.parse ----------
        // 判据全是字符串匹配，所以能在这里用真实现场原文回归。
        {
            // 用户贴过来的原文（两段崩溃：assert 调 abort，abort 在信号层再炸一次）
            String real = "Fatal glibc error: ../sysdeps/unix/sysv/linux/sysconf-sigstksz.h:25"
                    + " (sysconf_sigstksz): assertion failed: minsigstacksize != 0\n"
                    + "[proroot] SIGSEGV pc=0x7f addr=0x0 code=-6\n";
            RuntimeHealth.Probe p = RuntimeHealth.parse(real);
            ok("health: 认出用户贴的崩溃原文", !p.healthy());

            // 探针完整输出 —— 坏环境
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV: AT_MINSIGSTKSZ:  0x0\nPYEXIT=134\n"
                    + "Fatal glibc error: sysconf_sigstksz\nPROBE_END\n");
            ok("health: 坏环境判 fatal", !p.healthy());
            ok("health: 认出 auxv 为 0", p.minsigstkszZero);

            // 回归：LD_SHOW_AUXV 打的是十六进制。原实现走 Integer.parseInt("0x1400")
            // 抛异常被吞，minsigstkszZero 一直是 false —— 好机器结论恰好正确，
            // 掩盖了下面那条「auxv=0x0 但 python 退出码取不到」的漏报。
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV: AT_MINSIGSTKSZ:  0x1400\nPYEXIT=0\nPROBE_END\n");
            ok("health: 十六进制 0x1400 不误判", p.healthy() && !p.minsigstkszZero);

            // 修好之后才成立的一条：auxv=0x0，而 python3 压根不在（PYEXIT=127，不是 134）
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV: AT_MINSIGSTKSZ:  0x0\nPYEXIT=127\n"
                    + "bash: python3: command not found\nPROBE_END\n");
            ok("health: auxv=0x0 单独也能判出来", !p.healthy() && p.minsigstkszZero);

            // 十进制 0（有的 ld.so 版本按十进制打）
            p = RuntimeHealth.parse("AUXV: AT_MINSIGSTKSZ: 0\nPYEXIT=0\n");
            ok("health: 十进制 0 也认", !p.healthy());

            // 正常机器：内核压根不提供这一项，glibc 用架构默认常量。
            // 回归：标记行自己带 MINSIGSTKSZ 字样，\D 会跨过换行在 "PYEXIT=0" 上
            // 捡到那个 0，把每一台正常机器都判成不兼容并悄悄切回 proot。
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV_NO_MINSIGSTKSZ\nPYEXIT=0\nPROBE_END\n");
            ok("health: 缺 auxv 项是好事", p.healthy() && p.auxvEntryAbsent);
            ok("health: 缺 auxv 项时不去解析值", !p.minsigstkszZero);

            // 探针自己没跑成：不能据此判死刑，否则所有人白白失去 proroot 加速
            ok("health: 空输出不判死刑", RuntimeHealth.parse("").healthy());
            ok("health: null 不炸", RuntimeHealth.parse(null).healthy());

            // 只有 SIGABRT 才算 python 被打死；其它非零退出码（如 127）不单独构成理由
            p = RuntimeHealth.parse("AUXV_NO_MINSIGSTKSZ\nPYEXIT=1\nSyntaxError\n");
            ok("health: 普通非零退出码不误判", p.healthy());
            eqi("health: 解析退出码", 134,
                    RuntimeHealth.parse("AUXV_NO_MINSIGSTKSZ\nPYEXIT=134\n").pythonExit);

            // 探针脚本自身不能依赖 python（它正是受害者）
            String script = RuntimeHealth.probeScript();
            ok("health: 探针用 LD_SHOW_AUXV 而非 python 取 auxv",
                    script.contains("LD_SHOW_AUXV") && script.contains("/bin/true"));
        }

        // ---------- constantTimeEquals ----------
        ok("ct: 相等", LanAuth.constantTimeEquals("abc", "abc"));
        ok("ct: 前缀不算相等", !LanAuth.constantTimeEquals("abc", "ab"));
        ok("ct: 更长不算相等", !LanAuth.constantTimeEquals("abc", "abcd"));
        ok("ct: null 不相等", !LanAuth.constantTimeEquals("abc", null));

        // ---------- OfflineVersion（离线包标记比大小） ----------
        // 回归：maybeOfferOfflineUpgrade 原先拿「标记不相等」当「有新版」，注释写的却是
        // 「内置 > 已解压」。于是用旧离线包打的本地测试包装上来也弹「发现新版内置环境」，
        // 用户点了升级，rootfs 被重解压成更旧的环境：dsh 从 0.1.1-rc.2 退回 0.1.0-rc.6。
        ok("offline: 修订位往上走算新",
                OfflineVersion.isNewer("dsh-0.1.1-rc.2", "dsh-0.1.0-rc.6"));
        ok("offline: 反过来不算新（这就是降级那条路）",
                !OfflineVersion.isNewer("dsh-0.1.0-rc.6", "dsh-0.1.1-rc.2"));
        ok("offline: 同标记不算新",
                !OfflineVersion.isNewer("dsh-0.1.1-rc.2", "dsh-0.1.1-rc.2"));
        ok("offline: rc 号本身也要比",
                OfflineVersion.isNewer("dsh-0.1.1-rc.10", "dsh-0.1.1-rc.2"));
        // 少了「预发布位」，0.1.1 正式版会因为末尾没数字而被判成比 0.1.1-rc.2 旧
        ok("offline: 正式版比同号 rc 新",
                OfflineVersion.isNewer("dsh-0.1.1", "dsh-0.1.1-rc.9"));
        ok("offline: rc 不比同号正式版新",
                !OfflineVersion.isNewer("dsh-0.1.1-rc.9", "dsh-0.1.1"));
        ok("offline: 10 比 9 大（不是字典序）",
                OfflineVersion.isNewer("dsh-0.1.10-rc.1", "dsh-0.1.9-rc.1"));
        ok("offline: 没有标记的老环境视为最旧",
                OfflineVersion.isNewer("dsh-0.1.1-rc.2", "0"));
        ok("offline: 解析不出版本 → 不算新（拿不准不提示）",
                !OfflineVersion.isNewer("nightly", "dsh-0.1.1-rc.2"));
        ok("offline: 另一侧解析不出也不算新",
                !OfflineVersion.isNewer("dsh-0.1.1-rc.2", "nightly"));
        eqi("offline: 无法比较有专门的返回值", OfflineVersion.NOT_COMPARABLE,
                OfflineVersion.compare("nightly", "dsh-0.1.1-rc.2"));
        ok("offline: 0.1.1.0 与 0.1.1 是同一个版本",
                OfflineVersion.compare("dsh-0.1.1.0", "dsh-0.1.1") == 0);
        // CI 实际写进 assets 的就是纯整数（仓库根 OFFLINE_VERSION，改离线包内容时 +1）。
        // 上面那些 dsh-x.y.z 的用例是防呆：谁再往标记里写版本号，比较也不会错到降级。
        ok("offline: 整数标记 2 比 1 新（老用户升级路径）",
                OfflineVersion.isNewer("2", "1"));
        ok("offline: 装回老离线包不算新（1 vs 2）",
                !OfflineVersion.isNewer("1", "2"));
        ok("offline: 本地包写的 0 谁都不如（0 vs 1）",
                !OfflineVersion.isNewer("0", "1"));
        ok("offline: 从无标记环境升到 2 算新",
                OfflineVersion.isNewer("2", "0"));
        ok("offline: 10 比 9 新（整数不走字典序）",
                OfflineVersion.isNewer("10", "9"));

        // ---------- OverlayLines（悬浮条分行与滚动） ----------
        // 用户报的毛病：悬浮条「最前端文字会不停滚走，可读性很差」。旧实现是拿整段文本
        // 取最后 N 个字符、再交给系统折行 —— 尾部窗口每来一个字就右移一格，折行点跟着变，
        // 于是每一行的内容都在变。下面第一条就是这个类存在的理由。
        {
            String a = "abcdefghij klmnopqrst uvwxyz0123 456789";
            String b = a + " tail more";
            java.util.List<String> la = OverlayLines.wrap(a, 20);
            java.util.List<String> lb = OverlayLines.wrap(b, 20);
            boolean stable = la.size() <= lb.size();
            for (int i = 0; stable && i < la.size() - 1; i++) {   // 最后一行还在长，不算
                if (!la.get(i).equals(lb.get(i))) stable = false;
            }
            ok("overlay: 末尾追加内容不会动到前面的行", stable);
        }
        eqi("overlay: 汉字算两格", 4, OverlayLines.width("中文"));
        eqi("overlay: ASCII 算一格", 4, OverlayLines.width("abcd"));
        ok("overlay: 中文按显示宽度切，30 格 = 15 字一行",
                OverlayLines.wrap("一二三四五六七八九十一二三四五六七八九十", 30).size() == 2);
        eq("overlay: 行数没满就全给", "aaa", OverlayLines.lastLines("aaa", 3, 20));
        // 「已有行数 >= 最大行数时清掉第一行、下面的上移补位」——用户要的就是这句
        eq("overlay: 满了丢最旧一行、其余上移", "cccc\ndddd",
                OverlayLines.lastLines("aaaa bbbb cccc dddd", 2, 4));
        eq("overlay: 命令确认取开头并标省略号", "aaaa …",
                OverlayLines.firstLines("aaaa bbbb cccc", 1, 4));
        eqi("overlay: 长串无空格也不空转（500 字符 / 10 格 = 50 行）", 50,
                OverlayLines.wrap("x".repeat(500), 10).size());
        ok("overlay: 空输入不炸",
                OverlayLines.wrap(null, 20).isEmpty()
                        && OverlayLines.lastLines(null, 3, 20).isEmpty()
                        && OverlayLines.firstLines("", 3, 20).isEmpty());

        // ---------- UserDataPolicy ----------
        // 这一组的重点不是「某个路径判得对不对」，而是**同一份定义派生出的两个列表必须
        // 一一对应**。真机上这两处判断分裂过两次，各造成一次静默故障：
        //   · 桥 token 被还原回来 → 一律 401（现象却是「悬浮窗不显示 + agent 工具调用失败」）
        //   · 内置插件版本标记活过重解压 → 实体随 rootfs 没了却判定「已最新」→ 永不重装
        ok("policy: 桥 token 属本机专属",
                UserDataPolicy.isMachineLocal("root/.dsh/.bridge_token"));
        ok("policy: 内置插件版本标记属本机专属",
                UserDataPolicy.isMachineLocal("root/.dsh/builtin-assets.version"));
        ok("policy: 前导斜杠与 ./ 都认",
                UserDataPolicy.isMachineLocal("/root/.dsh/.bridge_token")
                        && UserDataPolicy.isMachineLocal("./root/.dsh/.bridge_token"));
        ok("policy: 会话是用户数据",
                !UserDataPolicy.isMachineLocal("root/.dsh/sessions/a.json"));
        ok("policy: 配置是用户数据",
                !UserDataPolicy.isMachineLocal("root/.dsh/settings.yaml"));
        ok("policy: 工作区 .env 是用户数据",
                !UserDataPolicy.isMachineLocal("root/deepseek-harness/.env"));
        ok("policy: null 与空串不误判",
                !UserDataPolicy.isMachineLocal(null) && !UserDataPolicy.isMachineLocal(""));
        // 派生列表必须等长且逐项同源 —— tar 用的模式就是去掉 root/ 前缀的同一个路径
        eqi("policy: 两个派生列表等长",
                UserDataPolicy.purgeAfterRestore().length,
                UserDataPolicy.tarExcludePatterns().length);
        String[] purge = UserDataPolicy.purgeAfterRestore();
        String[] pats = UserDataPolicy.tarExcludePatterns();
        boolean paired = purge.length == pats.length;
        for (int i = 0; paired && i < purge.length; i++) {
            if (!purge[i].equals("root/" + pats[i])) paired = false;
        }
        ok("policy: 排除项与清理项逐项同源（定义不可分裂）", paired);
        String tarArgs = UserDataPolicy.tarExcludeArgs();
        ok("policy: tar 参数带引号且以空格结尾（直接拼进命令不粘连）",
                tarArgs.contains("--exclude='.dsh/.bridge_token' ") && tarArgs.endsWith(" "));
        eqi("policy: tar 参数覆盖全部条目",
                pats.length, tarArgs.split("--exclude=", -1).length - 1);
        ok("policy: 清单不为空（清空它等于悄悄关掉这层保护）",
                UserDataPolicy.MACHINE_LOCAL_PATHS.length >= 2);

        // ===== ShellQuote：拼进 bash -c 之前的转义 =====
        // 断言方式刻意不比字符串长相，而是做 round-trip：把转义结果按 POSIX 单引号规则
        // 反解一遍，看 shell 最终会拿到什么。长相对不对不重要，语义对不对才重要。
        String[] nasty = {
                "plain", "with space", "a'b", "''", "'", "a'''b",
                "$(rm -rf /)", "`id`", "a; rm -rf /", "a && b", "a|b", "a\nb",
                "*", "?", "~", "$HOME", "\\", "--flag=va'lue", "中文名",
                "'; rm -rf / #", "$IFS", "a\tb",
        };
        boolean roundTrip = true, singleWord = true;
        String badRt = "", badSw = "";
        for (String s : nasty) {
            String q = ShellQuote.arg(s);
            if (!s.equals(unquoteSingle(q))) { roundTrip = false; badRt = s; }
            if (!isSingleShellWord(q)) { singleWord = false; badSw = s; }
        }
        ok("shellquote: 转义后按单引号规则反解 == 原值（22 个样本）", roundTrip, badRt);
        ok("shellquote: 输出始终是「一个」shell 词，引号外只有 \\' 转义", singleWord, badSw);
        eq("shellquote: 内含单引号拆成 '\\'' ", "'a'\\''b'", ShellQuote.arg("a'b"));
        eq("shellquote: null 给空参数而不是抛异常", "''", ShellQuote.arg(null));
        eq("shellquote: 空串给空参数", "''", ShellQuote.arg(""));
        // 典型注入 payload：转义后 shell 只看见一个字面量参数，命令边界闭不上
        ok("shellquote: 注入 payload 不产生新的命令边界",
                "'; rm -rf / #".equals(unquoteSingle(ShellQuote.arg("'; rm -rf / #")))
                        && isSingleShellWord(ShellQuote.arg("'; rm -rf / #")));

        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 条"
                : "失败 " + fail + " 条（通过 " + pass + "）");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 极简 POSIX 单引号反解：把 {@link ShellQuote#arg} 的输出还原成 shell 实际看到的值。
     *  单引号内一切字面量，引号外只处理反斜杠转义 —— 这就是 sh 对这两种结构的全部规则。 */
    private static String unquoteSingle(String s) {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
            } else if (!inQuote && c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(i + 1));
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 转义结果是否只构成「一个」shell 词：引号外不许出现空白或元字符，
     *  唯一允许的引号外字符是 {@code \} 加一个字符的转义序列。
     *  这条不成立就意味着能拼出新的参数或新的命令 —— 也就是注入。 */
    private static boolean isSingleShellWord(String s) {
        boolean inQuote = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
            } else if (inQuote) {
                i++;                       // 引号内一切字面量
            } else if (c == '\\' && i + 1 < s.length()) {
                i += 2;                    // 引号外唯一合法形态
            } else {
                return false;              // 引号外的裸字符：空格、; 、| 、$ 都算越界
            }
        }
        return !inQuote;                   // 引号必须闭合
    }

    /** 造一个最小可用的 tar.gz：只用到 name/size/typeflag 三个头字段 + 校验和。 */
    private static void writeTarGz(java.io.File out, String[][] entries, boolean withEndBlocks)
            throws Exception {
        try (java.io.OutputStream fo = new java.io.FileOutputStream(out);
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(fo)) {
            for (String[] e : entries) {
                byte[] body = e[1].getBytes("UTF-8");
                byte[] h = new byte[512];
                byte[] name = e[0].getBytes("UTF-8");
                System.arraycopy(name, 0, h, 0, Math.min(name.length, 100));
                put(h, 100, "0000644\0");                       // mode
                put(h, 108, "0000000\0");                       // uid
                put(h, 116, "0000000\0");                       // gid
                put(h, 124, String.format("%011o", body.length) + "\0");
                put(h, 136, String.format("%011o", 0) + "\0");  // mtime
                h[156] = '0';                                   // 普通文件
                put(h, 257, "ustar\0" + "00");
                for (int i = 148; i < 156; i++) h[i] = ' ';     // checksum 先填空格
                int sum = 0;
                for (byte b : h) sum += (b & 0xFF);
                put(h, 148, String.format("%06o", sum) + "\0 ");
                gz.write(h);
                gz.write(body);
                int pad = (512 - body.length % 512) % 512;
                if (pad > 0) gz.write(new byte[pad]);
            }
            if (withEndBlocks) gz.write(new byte[1024]);
        }
    }

    private static void put(byte[] buf, int off, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, b.length);
    }

    private static void deleteRec(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 拼一个最小请求头：请求行 + 可选附加头 + 空行结尾。 */
    private static String req(String reqLine, String extraHeaders) {
        StringBuilder sb = new StringBuilder(reqLine).append(" HTTP/1.1\r\n");
        sb.append("Host: 192.168.1.5:3081\r\n");
        if (!extraHeaders.isEmpty()) sb.append(extraHeaders).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private static void eq(String name, String expected, String actual) {
        boolean good = expected == null ? actual == null : expected.equals(actual);
        report(name, good, String.valueOf(expected), String.valueOf(actual));
    }

    private static void eqi(String name, int expected, int actual) {
        report(name, expected == actual, String.valueOf(expected), String.valueOf(actual));
    }

    private static void ok(String name, boolean good) {
        report(name, good, "true", String.valueOf(good));
    }

    private static void ok(String name, boolean good, String extra) {
        report(name, good, "true", extra);
    }

    private static void report(String name, boolean good, String expected, String actual) {
        if (good) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "\n         期望: " + expected + "\n         实际: " + actual);
        }
    }
}
