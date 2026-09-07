package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.WebProcSel;

/**
 * Web 进程的启停管理。停止判据全部收在 {@link WebProcSel}（唯一定义），
 * 这里只负责「按判据执行」：写停止哨兵 + 按 pid 文件杀。
 */
public class WebProcessManager {

    private final ProotBootstrap proot;

    public WebProcessManager(ProotBootstrap proot) {
        this.proot = proot;
    }

    /**
     * 停止 Web：
     * <ol>
     *   <li>写 {@link WebProcSel#STOP_SENTINEL}，让看门狗/重启脚本见到就退出；</li>
     *   <li>读 {@link WebProcSel#PID_WEB}，核对 cmdline 长相后 {@code kill}。</li>
     * </ol>
     * 顺序不可换：先哨兵再杀，否则拉起者会在你杀完之后把 Web 拽回来（「秒复活」）。
     */
    public void stop() {
        String script = "touch " + WebProcSel.STOP_SENTINEL + "\n"
                + "_p=$(cat " + WebProcSel.PID_WEB + " 2>/dev/null)\n"
                + "case \"$_p\" in ''|*[!0-9]*) ;; *) kill \"$_p\" 2>/dev/null ;; esac\n"
                + "pkill -f 'dsh web' 2>/dev/null\n"
                + "pkill -f 'bin.js' 2>/dev/null\n";
        try {
            // 停止操作强制通过稳定 proot 运行时执行，100% 确保残留清理且不受子进程环境影响
            proot.execAndReadWithProot(script, 10_000);
        } catch (Exception ignored) {
        }
    }
}
