package com.deepseekharness.app;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * 容器里的一个真 PTY 会话：把容器运行时 + bash 挂在伪终端上，让 vim / htop / tmux 跑得起来。
 *
 * <p><b>为什么需要它</b>：旧终端是「{@code execRootfsInteractive()} 起 bash，把 stdout 塞进
 * TextView」。没有 PTY 就没有 {@code TERM}、没有行编辑、没有光标定位 —— 任何 TUI 程序要么
 * 直接报 not a tty，要么画出一屏乱码。这是用户一上手就会撞到的硬伤。
 *
 * <p><b>PTY 与终端模拟没有自己写</b>：用的是 Termux 官方拆出来的 terminal-emulator /
 * terminal-view。这两个子模块是 <b>Apache 2.0</b>（termux-app 整体 GPLv3，但它们不是），
 * 与本项目的 MIT 兼容；aar 里自带 {@code jni/arm64-v8a/libtermux.so}，不需要装 Termux，
 * 里面就是 openpty + fork + execvp 那一套，省掉自己编 pty-bridge。
 *
 * <p><b>启动参数只有一个来源</b>：{@link ProotBootstrap#ptyArgv} / {@link ProotBootstrap#ptyEnv}
 * ——与 {@code execRootfs} 共用同一份构造逻辑。各写一份的话，PTY 里的 shell 会跑在和普通
 * 命令不一样的环境里（少个 PROOT_LOADER 就直接起不来），而这个项目已经在「同一份判断
 * 散落两处」上栽过四次。
 *
 * <p>这个类只管会话与回调转发，不碰任何 View —— UI 侧实现 {@link Listener} 即可，
 * 不必去实现 TerminalSessionClient 那 17 个方法。
 */
final class PtySession implements TerminalSessionClient {

    private static final String TAG = "DSHA-pty";

    /** 回滚缓冲行数：够往上翻几屏日志，又不至于把内存吃掉（每行 80 列约 200 字节）。 */
    private static final int TRANSCRIPT_ROWS = 2000;

    /** UI 侧只关心这几件事。回调都在 PTY 读线程上来，实现方自己 post 到主线程。 */
    interface Listener {
        /** 屏幕内容变了 → 该重绘。 */
        void onOutput();

        /** 标题变了（PS1 与 tmux 都会改），可以显示在标题栏。 */
        void onTitle(String title);

        /** 会话结束（exit / 进程被杀）。 */
        void onExit(int status);

        /** 终端要求把选中内容放进剪贴板。 */
        void onCopy(String text);

        /** 终端要求粘贴（比如 bracketed paste）。 */
        void onPasteRequest();

        /** 响铃：震一下比响一声合适。 */
        void onBell();
    }

    private final Listener listener;
    private volatile TerminalSession session;

    private PtySession(Listener l) {
        this.listener = l;
    }

    /**
     * 起一个会话。
     *
     * @param cols 列数，@param rows 行数 —— 必须是按控件实测字宽算出来的，
     *             瞎给一个值会让 TUI 的边框错位。
     */
    static PtySession start(ProotBootstrap proot, int cols, int rows, Listener l) {
        PtySession ps = new PtySession(l);
        String[] argv = proot.ptyArgv();
        String[] env = proot.ptyEnv();
        // args 就是 argv（含 argv[0]）：查过 termux.c，Java 数组原样转成 argv 后
        // 直接 execvp(cmd, argv)，没有任何加工 —— 与 ProcessBuilder 的行为一致。
        TerminalSession s = new TerminalSession(argv[0], "/", argv, env, TRANSCRIPT_ROWS, ps);
        ps.session = s;
        // initializeEmulator 才真正 fork 出子进程，所以尺寸要在这之前定好
        s.initializeEmulator(Math.max(4, cols), Math.max(2, rows));
        return ps;
    }

    TerminalSession session() {
        return session;
    }

    void write(String s) {
        TerminalSession t = session;
        if (t != null && s != null && !s.isEmpty()) t.write(s);
    }

    void resize(int cols, int rows) {
        TerminalSession t = session;
        if (t != null) t.updateSize(Math.max(4, cols), Math.max(2, rows));
    }

    boolean isRunning() {
        TerminalSession t = session;
        return t != null && t.isRunning();
    }

    /** 结束会话（切页面/退出时调，避免留一个孤儿 bash 在容器里跑）。 */
    void finish() {
        TerminalSession t = session;
        if (t != null) t.finishIfRunning();
    }

    // ==================== TerminalSessionClient ====================

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        listener.onOutput();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        listener.onTitle(changedSession == null ? "" : changedSession.getTitle());
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        listener.onExit(finishedSession == null ? -1 : finishedSession.getExitStatus());
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        listener.onCopy(text);
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        listener.onPasteRequest();
    }

    @Override
    public void onBell(TerminalSession session) {
        listener.onBell();
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        listener.onOutput();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        // 光标闪烁状态变化：重绘由 TerminalView 自己安排，这里不用管
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;    // null = 用库的默认（块状光标）
    }

    // ---- 日志：库里打得很细，统一收口到 logcat，出问题时能看到 PTY 层的动静 ----

    @Override
    public void logError(String tag, String message) {
        android.util.Log.e(TAG, tag + ": " + message);
    }

    @Override
    public void logWarn(String tag, String message) {
        android.util.Log.w(TAG, tag + ": " + message);
    }

    @Override
    public void logInfo(String tag, String message) {
        android.util.Log.i(TAG, tag + ": " + message);
    }

    @Override
    public void logDebug(String tag, String message) {
        android.util.Log.d(TAG, tag + ": " + message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        // Verbose 里是每个字节的读写，开着会把 logcat 冲垮 —— 故意丢掉
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        android.util.Log.w(TAG, tag + ": " + message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        android.util.Log.w(TAG, tag, e);
    }
}
