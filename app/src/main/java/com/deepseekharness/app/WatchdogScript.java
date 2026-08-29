package com.deepseekharness.app;

/**
 * 看门狗与重启脚本的<b>文本生成</b>（纯逻辑，不碰 Android API —— 断言在
 * {@code tools/pure-logic-test.sh}，语法由 {@code tools/stop-proc-test.sh} 用
 * {@code bash -n} 真跑一遍）。
 *
 * <p><b>为什么单独抽出来</b>：这两段 shell 是拼字符串拼出来的，写错的后果全是<b>静默</b>的 ——
 * 语法错一处，看门狗启动即退出，Web 崩了再没人拉，而 App 侧什么都看不到；重启脚本
 * （{@code dsh-cmd.txt}）错一处，看门狗以为自己重启了、其实什么都没发生。
 * 这类错误在 Java 编译期完全查不出来，所以放在能写断言、能过 {@code bash -n} 的地方。
 *
 * <p>注入的用户数据（工作区名、API key）沿用原来的引用方式：工作区名夹在单引号里、
 * key 走 {@link ShellQuote}。不要在这里"顺手"改引用方式。
 */
final class WatchdogScript {

    private WatchdogScript() {
    }

    /** 看门狗探测间隔（秒）与判定失联的次数 —— 3 次约 90 秒。 */
    static final int PROBE_INTERVAL_SEC = 30;
    static final int FAIL_THRESHOLD = 3;

    /**
     * 重启脚本（同时写成 {@code dsh-web-restart.sh} 与看门狗读的 {@code dsh-cmd.txt}）。
     *
     * @param apiKeyExportLine 形如 {@code export DEEPSEEK_API_KEY=…\n}，没配就是空串
     * @param permissionModeArg 已经 shell 转义过的权限档位
     * @param workdir           工作区目录名（原样夹在单引号里）
     * @param restartCmd        真正拉起 Web 的那条命令（runCoreCommand 的产物）
     */
    static String restart(String apiKeyExportLine, String permissionModeArg,
                          String workdir, String restartCmd) {
        return "#!/bin/bash\n"
                // 最后一道闸：用户已停止就放弃重启。看门狗重启读的正是这个脚本，
                // 所以即使看门狗漏杀、即使进程判据认不出，拉起这个动作本身也会自己放弃 ——
                // 这条不依赖任何 cmdline 匹配。必须在任何实际动作之前。
                + "if [ -f " + WebProcSel.STOP_SENTINEL + " ]; then\n"
                + "  echo \"$(date '+%F %T') 用户已停止，放弃重启\"\n"
                + "  echo '要手动起：先 rm " + WebProcSel.STOP_SENTINEL + "，或用 App 启动页的「启动」'\n"
                + "  exit 0\n"
                + "fi\n"
                + "export DSH_HOME=/root/.dsh\n"
                + apiKeyExportLine
                + "export DSH_PERMISSION_MODE=" + permissionModeArg + "\n"
                + "export DSH_CONFIRM=1\n"
                + "export BROWSER=true\n"
                // 工作区目录先于 cd 创建：RC6 模式没有源码树，不建目录的话
                // 看门狗重启第一步 cd || exit 1 必失败 → 自动重启形同虚设。
                // 不建 /root/.codex/pets（deepseek-pet 空目录会崩插件树）：
                // 空则删，让插件走「无 pet」正常分支。
                //
                // 工作区名走 ShellQuote（全树唯一的转义实现），不要写回手工的 '…' 包裹：
                // 这个名字用户在配置页能改，里面出现单引号时手工包裹会静默错位 ——
                // bash -n 依然「语法合法」，只是 mkdir/cd 指向了另一个目录，
                // 表现为「看门狗重启永远失败」而没有任何报错。
                // 对不含特殊字符的常规名字，两种写法生成的文本完全一样。
                + "mkdir -p /root/" + ShellQuote.arg(workdir) + " /root/.dsh/plugins 2>/dev/null\n"
                + "[ -d /root/.codex/pets ] && [ -z \"$(ls -A /root/.codex/pets 2>/dev/null)\" ] "
                + "&& rmdir /root/.codex/pets 2>/dev/null || true\n"
                + "cd /root/" + ShellQuote.arg(workdir) + " || exit 1\n"
                + restartCmd + "\n";
    }

    /** 看门狗脚本：WebUI 连续失联 {@link #FAIL_THRESHOLD} 次（约 90 秒）就重启。 */
    static String watchdog(int port) {
        return "#!/bin/bash\n"
                + "# DSHA 看门狗：WebUI 失联 " + FAIL_THRESHOLD + " 次（约 "
                + (FAIL_THRESHOLD * PROBE_INTERVAL_SEC) + " 秒）自动重启\n"
                + "# 幂等 + pid 落盘见 WebProcSel.watchdogGuards；进程判据见 pidsDsh(false)\n"
                + WebProcSel.watchdogGuards()
                + "PORT=" + port + "\n"
                + "FAIL=0\n"
                // 与「停止」共用同一份进程判据，且排除看门狗自己 —— 否则它会杀掉自身。
                // 这里原来是 pkill -f 'bin.js web'：在独立脚本里跑不会自杀，
                // 但仍可能命中 proot，那会把承载整个环境的进程带走。
                + WebProcSel.pidsDsh(false) + "\n"
                + "while true; do\n"
                // 每轮先看闸：用户按了停止就自己退出，不留一个还在数失联次数的进程。
                // 「停止后又被拉起来」这条路上看门狗是头号嫌疑 —— 它是独立 bash 进程，
                // App 侧那些 keepalive_paused / last_web_stop 判据完全管不到它。
                + "  if [ -f " + WebProcSel.STOP_SENTINEL + " ]; then\n"
                + "    echo \"$(date '+%F %T') 用户已停止，看门狗退出\" >> /root/dsh-watchdog.log\n"
                + "    exit 0\n"
                + "  fi\n"
                + "  if curl -s -m 5 -o /dev/null \"http://127.0.0.1:$PORT/\"; then\n"
                + "    FAIL=0\n"
                + "  else\n"
                + "    FAIL=$((FAIL+1))\n"
                + "    echo \"$(date '+%F %T') WebUI 失联 $FAIL 次\" >> /root/dsh-watchdog.log\n"
                + "    if [ \"$FAIL\" -ge " + FAIL_THRESHOLD + " ]; then\n"
                + "      echo \"$(date '+%F %T') WebUI 已失联，自动重启\" >> /root/dsh-watchdog.log\n"
                + "      for p in $(pids_dsh); do kill -TERM \"$p\" 2>/dev/null; done\n"
                + "      # 关键：等端口彻底关闭再重启（旧进程可能还在写 SQLite，\n"
                + "      # 立即重启会双进程写同一会话 → seq 重复 → 会话损坏，官方 #420）\n"
                + "      for i in $(seq 1 20); do\n"
                + "        curl -s -m 2 -o /dev/null http://127.0.0.1:$PORT/ 2>/dev/null && sleep 1 || break\n"
                + "      done\n"
                + "      sleep 1\n"
                + "      nohup bash /root/dsh-cmd.txt >> /root/dsh-watchdog-restart.log 2>&1 &\n"
                + "      FAIL=0\n"
                + "    fi\n"
                + "  fi\n"
                + "  sleep " + PROBE_INTERVAL_SEC + "\n"
                + "done\n";
    }
}
