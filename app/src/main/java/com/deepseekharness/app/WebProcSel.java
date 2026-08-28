package com.deepseekharness.app;

/**
 * 「哪些进程算 Web 进程、怎么把它们找出来」的<b>唯一定义</b>（纯逻辑，不碰 Android API ——
 * 断言在 {@code tools/pure-logic-test.sh}）。
 *
 * <p><b>为什么要单独一个类</b>：停止功能已经改坏过三轮，每一轮的病根都在「判据」上，
 * 而判据当时散在四个地方各写一遍 —— 容器内的停止脚本、看门狗脚本、兜底杀、Android 侧扫
 * {@code /proc}。改一处漏三处，症状还都长得一样（点了停止没反应 / 停了又复活），
 * 极难分辨是哪一层没生效。收成一份之后，「该匹配什么」变成可以写断言钉住的东西：
 *
 * <ul>
 *   <li>真机上 dsh 的实际 cmdline（{@code node --expose-internals
 *       /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web}）必须被认出来 ——
 *       它是 {@code readlink -f $(command -v dsh)} 的结果，上游改一次目录结构就会变；</li>
 *   <li>容器启动器（{@code libproot.so} / {@code libproroot}）<b>绝不能</b>被认成目标：
 *       proot 不隔离 PID，容器里 {@code /proc} 看到的是宿主全部进程，杀到它等于把整个
 *       环境连 App 一起带走（现象是点停止/重启十秒后闪退、通知栏一起消失）；</li>
 *   <li>用户自己或 agent 跑的 node 进程<b>不能</b>被误杀（曾经用 {@code pkill -f node}）。</li>
 * </ul>
 */
final class WebProcSel {

    private WebProcSel() {
    }

    /**
     * 「用户已停止」哨兵（容器内路径）。存在即表示：<b>任何容器内的自动拉起路径都必须放弃</b>。
     *
     * <p>App 侧的判据（{@code keepalive_paused} / {@code last_web_stop}）管不到容器里的
     * 拉起者 —— 看门狗和它写的重启脚本是独立的 bash 进程。停止时只要漏杀一个，
     * 它下一轮就把 WebUI 拽回来，用户看到的正是<b>「停止之后 dsh 秒复活」</b>。
     *
     * <p>而「杀得准」本身是在赌 cmdline 长相。哨兵不赌：拉起者每轮自己检查，看见就退出。
     * 删除点只有一处 —— {@code HarnessController.startWebCommand()} 开头，
     * 也就是用户明确要启动的时候。
     */
    static final String STOP_SENTINEL = "/root/.dsha-stopped";

    /**
     * 看门狗启动时的幂等闸 —— 生成成独立片段是为了能被 {@code bash -n} 与
     * {@code tools/stop-proc-test.sh} 覆盖（这段写错的两种后果都很难发现：
     * 要么看门狗<b>永远不启动</b>，Web 崩了没人拉；要么每次启动都多一个实例，
     * 几个看门狗抢着拉 Web）。
     *
     * <p>三层判断，从可靠到勉强：pid 文件 + {@code kill -0}（不读 /proc，不受 hidepid
     * 影响）→ cmdline 核对（挡 pid 回卷复用；读不到就信 {@code kill -0}，宁可多起一个
     * 也不要一个都不起）→ {@code pgrep} 兜底（同会话内有效）。
     */
    static String watchdogGuards() {
        return "_wd_alive() {\n"
            + "  [ -f " + PID_WATCHDOG + " ] || return 1\n"
            + "  _p=$(cat " + PID_WATCHDOG + " 2>/dev/null)\n"
            + "  case \"$_p\" in ''|*[!0-9]*) return 1 ;; esac\n"
            + "  kill -0 \"$_p\" 2>/dev/null || return 1\n"
            + "  if [ -r /proc/$_p/cmdline ]; then\n"
            + "    tr '\\0' ' ' < /proc/$_p/cmdline 2>/dev/null | grep -q dsh-watchdog || return 1\n"
            + "  fi\n"
            + "  return 0\n"
            + "}\n"
            + "if _wd_alive; then exit 0; fi\n"
            + "if pgrep -f '[d]sh-watchdog.sh' >/dev/null 2>&1; then exit 0; fi\n"
            + "echo $$ > " + PID_WATCHDOG + " 2>/dev/null\n";
    }

    /** 端口的 {@code /proc/net/tcp} 表示（大写十六进制、补到 4 位）。 */
    static String portHex(int port) {
        return String.format(java.util.Locale.ROOT, "%04X", port & 0xFFFF);
    }

    /**
     * Web 进程自己写下的 pid 文件（容器内路径）。
     *
     * <p><b>为什么必须有它</b>：前两版停止都在「按 cmdline 找进程」这条路上打转，而那条路
     * 有两个环境性的硬限制，在这台机器上实测确认过：
     * <ul>
     *   <li>{@code /proc/net/tcp} 对非 root App <b>读不到</b>（Permission denied，
     *       Android 10+ 收紧了 /proc/net）—— 所以 {@code ss}/{@code netstat}/端口反查
     *       全都只能拿到空结果，而且是<b>静默</b>的空；</li>
     *   <li>{@code /proc} 只看得到<b>同 uid</b> 的进程（Android 的 hidepid），
     *       跨会话扫描本来就不保证看得见。</li>
     * </ul>
     *
     * <p>pid 文件绕开这两条：启动方就是我们自己，{@code exec} 之前把 {@code $$} 写下来 ——
     * 因为 {@code exec} 用 node 顶替当前 shell、<b>pid 不变</b>，这个数就是 node 的 pid。
     * 停止时直接按 pid 杀，不猜命令行长相、不依赖任何工具。
     *
     * <p>唯一要防的是 pid 复用（Linux pid 会回卷）：杀之前核对
     * {@code /proc/<pid>/cmdline} 的长相，对不上就当这个文件是过期的。
     */
    static final String PID_WEB = "/root/.dsha-web.pid";

    /** 看门狗自己写下的 pid 文件（容器内路径）。理由同 {@link #PID_WEB}。 */
    static final String PID_WATCHDOG = "/root/.dsha-watchdog.pid";

    /** pid 文件相对 rootfs 根的路径 —— Android 侧要用 {@code File} 直接读它。 */
    static String pidFileRel(String guestPath) {
        return guestPath.startsWith("/") ? guestPath.substring(1) : guestPath;
    }

    /**
     * 按 pid 文件找进程的 shell 片段 —— <b>最可靠的一条</b>，不依赖 cmdline 模式匹配。
     *
     * <p>两个文件各有自己的身份核对条件：Web 那个必须长得像 node，看门狗那个必须是
     * 跑着 {@code dsh-watchdog} 的 —— 这样 pid 被复用时不会杀到无关进程。
     * 容器启动器照样排除（杀到 proot/proroot 等于把 App 一起带走）。
     */
    static String pidsFile() {
        return "pids_file() { "
            + "_chk() { "
            +   "case \"$1\" in ''|*[!0-9]*) return 0 ;; esac; "
            // 先判可读再读：进程已经退出时，`< /proc/<pid>/cmdline` 这个**重定向失败**是
            // shell 自己报的错，tr 后面的 2>/dev/null 挡不住它 —— 那行噪音会跟着
            // execAndRead 的输出一起进活动日志（实测见过）。
            +   "[ -r /proc/$1/cmdline ] || return 0; "
            +   "_c=$(tr '\\0' ' ' < /proc/$1/cmdline 2>/dev/null) || return 0; "
            +   "case \"$_c\" in *proot*|*proroot*) return 0 ;; esac; "
            +   "case \"$_c\" in $2) echo \"$1\" ;; esac; "
            + "}; "
            + "_chk \"$(cat " + PID_WEB + " 2>/dev/null)\" '*node*'; "
            + "_chk \"$(cat " + PID_WATCHDOG + " 2>/dev/null)\" '*dsh-watchdog*'; "
            + "}; ";
    }

    /**
     * Android 侧判据：这条 cmdline 该不该被当作「Web 进程」杀掉。
     *
     * <p>顺序有意义：<b>先排除容器启动器</b>，再匹配目标。反过来写的话，
     * proot 的命令行里带着 rootfs 路径与待执行命令，{@code bin.js}、{@code web}
     * 都可能出现在里面 —— 于是第一个被杀的就是承载整个环境的那个进程。
     */
    static boolean looksLikeWeb(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        if (cmdline.contains("libproot.so") || cmdline.contains("libproroot")
                || cmdline.contains("proot")) {
            return false;
        }
        return (cmdline.contains("bin.js") && cmdline.contains("web"))
                || cmdline.contains("dsh web")
                || cmdline.contains("dsh-app-boot")
                || cmdline.contains("dsh-cli")
                || cmdline.contains("dsh-watchdog.sh")
                // 重启脚本：它正在跑意味着「马上会有一个新的 Web 进程」，
                // 停止时漏掉它，等于停完一两秒后又冒出来一个（「秒复活」）
                || cmdline.contains("dsh-cmd.txt")
                || cmdline.contains("dsh-web-restart.sh");
    }

    /**
     * 安全找出 dsh 相关进程的 shell 片段 —— 停止、兜底杀、看门狗三处共用。
     *
     * <p><b>为什么不能用 {@code pkill -f '<模式>'}</b>：这些命令是通过
     * {@code bash -c "<整段脚本>"} 跑的，那条 shell 自己的 cmdline 里<b>包含整段脚本文本</b>，
     * 模式串就在里面 —— pkill 于是把执行它的 shell 一起杀掉。实测输出
     * {@code proot info: vpid 1: terminated with signal 15}：脚本在第一条 pkill 就死了,
     * 后面的 sleep 与 SIGKILL 永远不执行，dsh 当然还活着。<b>「点了停止没反应」就是这么来的。</b>
     *
     * <p>{@code case} 从上往下匹配：先排除含 {@code pids_dsh}/{@code pids_port}
     * （也就是本脚本自己）与 proot/proroot 的，再匹配目标。
     *
     * @param includeWatchdog 是否把看门狗也算进去。看门狗脚本自己用这段找 Web 进程时
     *                        必须传 {@code false}，否则它会杀掉自身
     */
    static String pidsDsh(boolean includeWatchdog) {
        return "pids_dsh() { "
            + "for d in /proc/[0-9]*; do "
            +   "[ -r $d/cmdline ] || continue; "
            +   "c=$(tr '\\0' ' ' < $d/cmdline 2>/dev/null) || continue; "
            +   "case \"$c\" in *pids_dsh*|*pids_port*|*proot*|*proroot*"
            +       (includeWatchdog ? "" : "|*dsh-watchdog*") + ") continue ;; esac; "
            +   "case \"$c\" in "
            +     (includeWatchdog ? "*dsh-watchdog*|" : "")
            +     "*\"bin.js web\"*|*\"dsh web\"*|*dsh-app-boot*|*dsh-cli*"
            +     "|*dsh-cmd.txt*|*dsh-web-restart.sh*) "
            +       "echo \"${d#/proc/}\" ;; "
            +   "esac; "
            + "done; }; ";
    }

    /**
     * 按「谁在监听这个端口」反查 pid 的 shell 片段 —— <b>能用就用的附加层，不是主力</b>。
     *
     * <p><b>先说结论</b>：{@code /proc/net/tcp} 在非 root 的 Android（10+）上大概率
     * <b>读不到</b>（实测 {@code Permission denied}）。所以这一层通常拿到空结果，
     * 而且是静默的空 —— 主力判据是 {@link #pidsFile}（pid 文件），
     * 这里只是「万一读得到就多一层」。同理 {@code ss} / {@code netstat} / {@code lsof}
     * 也全都指望这个文件，别拿它们当兜底。
     *
     * <p>做法：从 {@code /proc/net/tcp[6]} 取 LISTEN（{@code st=0A}）且本地端口相符的
     * socket inode，再在 {@code /proc/<pid>/fd/} 里反查谁持有它。为了不在几百个进程上
     * 白扫 fd，先用 cmdline 粗筛到 node/dsh；proot/proroot 与本脚本自身照样排除。
     */
    static String pidsPort(int port) {
        String hex = portHex(port);
        return "pids_port() { "
            + "_ino=$(awk '$4==\"0A\" && $2 ~ /:" + hex + "$/ {print $10}' "
            +   "/proc/net/tcp /proc/net/tcp6 2>/dev/null); "
            + "[ -z \"$_ino\" ] && return 0; "
            + "for d in /proc/[0-9]*; do "
            +   "[ -r $d/cmdline ] || continue; "
            +   "c=$(tr '\\0' ' ' < $d/cmdline 2>/dev/null) || continue; "
            +   "case \"$c\" in *pids_dsh*|*pids_port*|*proot*|*proroot*) continue ;; esac; "
            +   "case \"$c\" in *node*|*dsh*) ;; *) continue ;; esac; "
            +   "for f in $d/fd/*; do "
            +     "l=$(readlink \"$f\" 2>/dev/null) || continue; "
            +     "for i in $_ino; do "
            +       "if [ \"$l\" = \"socket:[$i]\" ]; then echo \"${d#/proc/}\"; break 2; fi; "
            +     "done; "
            +   "done; "
            + "done; }; ";
    }
}
