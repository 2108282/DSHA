package com.deepseekharness.app;

/**
 * 插件安装前的存档点 —— 装坏能退回。
 *
 * <p><b>为什么需要它</b>：插件安装这条链上出过的事故几乎都是「装完之后 profile 被写坏」，
 * 而不是「安装本身报错」—— 自指依赖让后续所有安装 ELOOP、装成了 monorepo 的管理包、
 * 装进来但缺构建产物成了空壳、{@code .npmrc} 被写坏导致联网安装全部 404。
 * 这些以前只能靠事后追根因、再写一段自愈去修。存档点是事前保险：装之前留一份，
 * 装坏了一键退回。
 *
 * <p><b>存什么</b>（刻意只存小东西，create 要快到不影响安装体验）：
 * <ul>
 *   <li>{@code profiles/web/package.json} —— 插件注册的全部事实
 *       （dependencies + dsh.profile.bundles）；</li>
 *   <li>{@code profiles/web/cordis.patch.yml} —— patch 层的启用/禁用状态；</li>
 *   <li>{@code node_modules} 的<b>顶层条目清单</b> —— 只存名字，不存内容。</li>
 * </ul>
 *
 * <p><b>还原做什么</b>：把那两个文件写回去，再删掉「快照之后新出现的顶层条目」。
 * 快照里已有的条目一律不动 —— 宁可少删也不误删用户原有的东西。
 *
 * <p><b>还原不了什么</b>（说清楚，别让人以为它是时间机器）：
 * <ul>
 *   <li>scope 目录只按顶层记（{@code @dsh-external} 而不是它下面每个包）。所以往一个
 *       <b>已存在</b>的 scope 里装的包，还原时不会被删 —— 偏保守的一侧；</li>
 *   <li>pnpm store 是跨 profile 的共享缓存，不回滚，也不需要回滚；</li>
 *   <li>安装过程若顺带升级了某个共享依赖的内容，那部分回不去。</li>
 * </ul>
 *
 * <p>只保留最近 {@link #KEEP} 份，多的自动删 —— 存档点本身很小（两个文件 + 一份清单），
 * 但没上限的东西早晚会变成问题。
 */
final class PluginSavepoint {

    /** 存档点根目录（容器内路径）。 */
    private static final String ROOT = "/root/.dsha-savepoints";

    /** profile 目录（容器内路径）。 */
    private static final String PROFILE = "/root/.dsh/profiles/web";

    /** 保留几份。 */
    private static final int KEEP = 5;

    private PluginSavepoint() {
    }

    /**
     * 打一个存档点。
     *
     * @param what 这次要装的是什么（写进 what.txt，还原时告诉用户「退回的是装 X 之前」）
     * @return 存档点 id（时间戳），失败返回 null —— <b>失败不该阻断安装</b>，
     *         没有保险总比装不了强，只是记一条日志
     */
    static String create(ProotBootstrap proot, HarnessController host, String what) {
        try {
            String id = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String s = ROOT + "/" + id;
            String r = proot.execAndRead(
                    "set -e; mkdir -p " + s + "; "
                    + "cp " + PROFILE + "/package.json " + s + "/package.json 2>/dev/null || true; "
                    + "cp " + PROFILE + "/cordis.patch.yml " + s + "/patch.yml 2>/dev/null || true; "
                    // ls 的排序受 locale 影响，而 restore 那边的 comm 要求两侧同序 ——
                    // 强制 LC_ALL=C sort，两边都用同一个规则，否则会漏删或误删
                    + "ls -1 " + PROFILE + "/node_modules 2>/dev/null "
                    + "| LC_ALL=C sort > " + s + "/nm.list || true; "
                    + "printf '%s' " + ShellQuote.arg(what == null ? "" : what)
                    + " > " + s + "/what.txt; "
                    // 只留最近 KEEP 份：按名字排序（时间戳命名，字典序=时间序），砍掉多出来的
                    + "ls -1d " + ROOT + "/*/ 2>/dev/null | sort | head -n -" + KEEP
                    + " | xargs -r rm -rf; "
                    + "test -s " + s + "/package.json && echo SAVEPOINT_OK || echo SAVEPOINT_EMPTY",
                    60_000);
            if (r != null && r.contains("SAVEPOINT_OK")) return id;
            // package.json 都没存下来的存档点是假的保险，不如没有 —— 删掉并说明
            proot.execAndRead("rm -rf " + s, 20_000);
            android.util.Log.w("DSHA", "存档点没建起来（profile 可能还没初始化）: " + r);
            return null;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "建存档点失败（不阻断安装）: " + t);
            return null;
        }
    }

    /** 最近一份存档点的信息，给 UI 显示；没有则返回 null。 */
    static String latestInfo(ProotBootstrap proot) {
        try {
            String r = proot.execAndRead(
                    "d=$(ls -1d " + ROOT + "/*/ 2>/dev/null | sort | tail -1); "
                    + "test -n \"$d\" || { echo NONE; exit 0; }; "
                    + "echo \"ID=$(basename $d)\"; "
                    + "echo \"WHAT=$(cat $d/what.txt 2>/dev/null)\"", 30_000);
            if (r == null || r.contains("NONE")) return null;
            String id = grab(r, "ID=");
            String what = grab(r, "WHAT=");
            if (id.isEmpty()) return null;
            String when = id.length() == 15
                    ? id.substring(4, 6) + "-" + id.substring(6, 8) + " "
                      + id.substring(9, 11) + ":" + id.substring(11, 13)
                    : id;
            return what.isEmpty() ? when : when + "（装 " + what + " 之前）";
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 还原到最近一个存档点。
     *
     * @return 给用户看的结果
     */
    static String restore(ProotBootstrap proot, HarnessController host) {
        try {
            String r = proot.execAndRead(
                    "d=$(ls -1d " + ROOT + "/*/ 2>/dev/null | sort | tail -1); "
                    + "test -n \"$d\" || { echo NO_SAVEPOINT; exit 0; }; "
                    + "test -s $d/package.json || { echo BAD_SAVEPOINT; exit 0; }; "
                    + "cp $d/package.json " + PROFILE + "/package.json; "
                    + "test -f $d/patch.yml && cp $d/patch.yml " + PROFILE + "/cordis.patch.yml; "
                    // 删掉存档之后新出现的顶层条目；comm -13 = 只在右边（现在）有的
                    + "if [ -s $d/nm.list ]; then "
                    +   "ls -1 " + PROFILE + "/node_modules 2>/dev/null "
                    +     "| LC_ALL=C sort > /tmp/nm.now; "
                    +   "LC_ALL=C comm -13 $d/nm.list /tmp/nm.now | while IFS= read -r n; do "
                    +     "[ -n \"$n\" ] && rm -rf " + PROFILE + "/node_modules/\"$n\" "
                    +     "&& echo \"REMOVED:$n\"; "
                    +   "done; rm -f /tmp/nm.now; "
                    + "fi; "
                    + "echo \"RESTORED:$(basename $d)\"", 120_000);
            if (r == null) return "还原失败：容器没有响应";
            if (r.contains("NO_SAVEPOINT")) return "没有可用的存档点";
            if (r.contains("BAD_SAVEPOINT")) return "存档点不完整（缺 package.json），没有动任何东西";
            if (!r.contains("RESTORED:")) return "还原失败：" + r.trim();
            java.util.List<String> removed = new java.util.ArrayList<>();
            for (String line : r.split("\n")) {
                if (line.startsWith("REMOVED:")) removed.add(line.substring(8).trim());
            }
            StringBuilder msg = new StringBuilder("已退回到这次安装之前");
            if (!removed.isEmpty()) {
                msg.append("，删掉了新装进来的 ").append(removed.size()).append(" 项：")
                   .append(String.join("、", removed));
            }
            msg.append("\n注册信息（package.json 与 patch 开关）已还原。");
            // package.json 变了要 dsh 重新 reconcile 才生效，patch 那层是 HMR ——
            // 这里刻意不说「刷新页面即可」，因为依赖表变动确实需要重启引擎
            msg.append("\n依赖表变动需要重启 WebUI 才会生效（patch 开关不用）。");
            host.logActivity("插件安装已撤销：" + msg);
            return msg.toString();
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "还原存档点失败: " + t);
            return "还原失败：" + t;
        }
    }

    private static String grab(String out, String key) {
        for (String line : out.split("\n")) {
            if (line.startsWith(key)) return line.substring(key.length()).trim();
        }
        return "";
    }
}
