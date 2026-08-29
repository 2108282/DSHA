package com.deepseekharness.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器运行时抽象：把「怎么进 rootfs 执行命令」这一层独立出来，让 proot 与 proroot
 * 并存、可切换。
 *
 * <p>为什么值得抽象：整个 ProotBootstrap 一千行里，真正与运行时绑定的只有
 * {@code baseProotArgv()} 组装的那串参数和几个环境变量 —— 下载、解压、镜像测速、
 * 离线包处理全都与运行时无关。所以抽象面很窄，rootfs 本身一个字节都不用改。
 *
 * <p>两条实现：
 * <ul>
 *   <li>{@link Proot} —— Termux 的 proot（APK 内置 libproot.so），默认，一直在用；
 *   <li>{@link Proroot} —— coderredlab/proroot，LD_PRELOAD + 二进制补丁，
 *       零 ptrace 开销。实验功能，二进制不内置，用户开启时下载。
 * </ul>
 *
 * <p>proroot 之所以对 DSHA 对症：proot 每个 syscall 要两次上下文切换，而
 * node 启动、pnpm 安装都是 syscall 密集型；它的 v1.2.8 还专门修了
 * link2symlink 的 ENOSPC（{@code rm -rf} 后硬链接组成员没删净），
 * 那正是我们反复处理的悬空 .l2s 链问题的源头。
 *
 * <p>约束：切换只影响「执行命令」这一层，装机路径（解压/安装六步）一律沿用 proot，
 * 免得实验功能把能用的环境搞坏。
 */
public interface ContainerRuntime {

    /** 运行时标识，用于 UI 显示与偏好存储。 */
    String id();

    /** 人类可读名称（带一句话说明）。 */
    String displayName();

    /** 二进制是否齐备、现在就能用。 */
    boolean available();

    /** 缺什么（available() 为 false 时给出可执行的下一步）。 */
    String unavailableReason();

    /**
     * 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。
     * 调用方追加 {@code /bin/bash -c <cmd>} 或 {@code /bin/bash}。
     */
    List<String> baseArgv(File rootfsDir, boolean hardlinkSupported);

    /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
    void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir);

    /** 首次使用前的准备（复制依赖库等）。抛异常表示准备失败，调用方应回退。 */
    void prepare() throws Exception;

    // ==================================================================

    /** 现有实现：Termux proot，APK 内置。 */
    class Proot implements ContainerRuntime {
        private final Context ctx;
        private final File nativeLibProot;

        Proot(Context ctx, File nativeLibProot) {
            this.ctx = ctx;
            this.nativeLibProot = nativeLibProot;
        }

        @Override public String id() { return "proot"; }

        @Override public String displayName() {
            return "proot（内置，稳定）";
        }

        @Override public boolean available() {
            return nativeLibProot != null && nativeLibProot.exists();
        }

        @Override public String unavailableReason() {
            return available() ? "" : "APK 内的 libproot.so 缺失（安装包可能损坏，建议重装）";
        }

        @Override public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
            List<String> argv = new ArrayList<>();
            argv.add(nativeLibProot.getAbsolutePath());
            // 只有文件系统不支持硬链接时才需要 link2symlink 模拟（会破坏 dsh write 工具）
            if (!hardlinkSupported) argv.add("--link2symlink");
            argv.add("-L");
            argv.add("--kill-on-exit");
            argv.add("-0");
            argv.add("--rootfs=" + rootfsDir.getAbsolutePath());
            argv.add("--cwd=/root");
            for (String[] b : BINDS) {
                argv.add("-b");
                argv.add(b.length == 1 ? b[0] : b[0] + ":" + b[1]);
            }
            return argv;
        }

        @Override public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {
            // 由 ProotBootstrap.applyProotEnv 统一处理，这里不重复设置
        }

        @Override public void prepare() {
            // 内置库由 ProotBootstrap.ensureRuntimeFiles 复制，无额外准备
        }
    }

    /** 实验实现：proroot（coderredlab/proroot），LD_PRELOAD 路径翻译，零 ptrace。
     *
     *  <p><b>与 proot 的一个行为差异，改停止/重启相关代码前必须知道</b>：
     *  {@link Proot} 的 argv 里带 {@code --kill-on-exit}（启动器退出时连带收掉容器内的
     *  子进程），而 proroot 这边<b>没有这个参数</b>（LD_PRELOAD 方案里也没有等价机制）。
     *  也就是说在<b>默认运行时</b>下：
     *  <ul>
     *    <li>{@code Process.destroy()} 只杀启动器，容器里的 node 会变成孤儿继续跑、
     *        继续占着 Web 端口；</li>
     *    <li>{@code nohup … &} 起的看门狗同样活得好好的。</li>
     *  </ul>
     *  所以「停止」不能指望杀启动器来传播信号 —— 必须按 pid 精确杀（见
     *  {@link WebProcSel#PID_WEB} 那条链路）。别给 proroot 补 {@code --kill-on-exit}
     *  来「省掉」那些代码：它未必认这个参数，认不出就是启动失败、降级回 proot。
     */
    class Proroot implements ContainerRuntime {
        /** 五个 .so 都得在同一目录，启动器靠 /proc/self/exe 的 dirname 找同伴。 */
        static final String[] LIBS = {
                "libproroot.so",
                "libproroot-runtime.so",
                "libproroot-linker.so",
                "libproroot-stub-loader.so",
                "libproroot-bridge.so",
        };

        private final Context ctx;
        private final File dir;

        Proroot(Context ctx, File dir) {
            this.ctx = ctx;
            this.dir = dir;
        }

        /** 存放目录：APK 的 jniLibs 提取目录。
         *
         *  **不能放 filesDir**：Android 10+ 的 W^X 策略不允许从应用可写目录执行代码，
         *  下载到 filesDir 的 .so 跑不起来。我们现有的 libproot.so 也正是靠 jniLibs
         *  才能执行（build.gradle 里 useLegacyPackaging=true 就是为了让系统把它提取出来）。
         *  andClaw 用 applicationInfo.nativeLibraryDir 是同样的原因。
         *
         *  代价：闭源二进制随发行包分发。所以默认不启用，只在用户显式切换时生效，
         *  而且不参与装机路径 —— 最坏情况是执行命令这一层退回 proot。 */
        static File defaultDir(Context ctx) {
            return new File(ctx.getApplicationInfo().nativeLibraryDir);
        }

        @Override public String id() { return "proroot"; }

        @Override public String displayName() {
            return "proroot（实验，零 ptrace 开销）";
        }

        @Override public boolean available() {
            for (String n : LIBS) {
                File f = new File(dir, n);
                if (!f.isFile() || f.length() == 0) return false;
            }
            return true;
        }

        @Override public String unavailableReason() {
            List<String> missing = new ArrayList<>();
            for (String n : LIBS) {
                File f = new File(dir, n);
                if (!f.isFile() || f.length() == 0) missing.add(n);
            }
            if (missing.isEmpty()) return "";
            return "缺 " + missing.size() + " 个运行时文件（" + missing.get(0)
                    + " 等）—— 这些随 APK 分发，缺失说明安装包不完整，建议重装";
        }

        @Override public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
            // 写法照 andClaw（proroot 作者自己的 App）实测版本，不照 README：
            //  · bind 一律写成 host:guest 显式形式，单参数形式在 proroot 下未必解析；
            //  · 额外把一个真实目录挂成 /dev/shm —— proroot 不带 libandroid-shmem.so
            //    （我们给 proot 复制的那个），POSIX 共享内存要靠真实目录顶；
            //  · --link2symlink 无条件加：Android 文件系统一律需要，
            //    andClaw 那边没有任何条件判断。
            List<String> argv = new ArrayList<>();
            argv.add(new File(dir, "libproroot.so").getAbsolutePath());
            argv.add("-r");
            argv.add(rootfsDir.getAbsolutePath());
            argv.add("-0");
            argv.add("-w");
            argv.add("/root");
            for (String[] b : BINDS) {
                argv.add("-b");
                argv.add(b.length == 1 ? b[0] + ":" + b[0] : b[0] + ":" + b[1]);
            }
            File shm = shmDir();
            //noinspection ResultOfMethodCallIgnored
            shm.mkdirs();
            argv.add("-b");
            argv.add(shm.getAbsolutePath() + ":/dev/shm");
            argv.add("--link2symlink");
            return argv;
        }

        /** /dev/shm 模拟目录（Chromium 之类要 POSIX 共享内存）。放 cacheDir 下，可被系统清理。 */
        File shmDir() {
            return new File(ctx.getCacheDir(), "shm");
        }

        @Override public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {
            // README 说启动器会从自己所在目录自动发现同伴，但 andClaw 里是**显式**设的 ——
            // 跟着显式设，少一个不确定因素。
            pb.environment().put("PROROOT_TMP_DIR", tmpDir.getAbsolutePath());
            pb.environment().put("PROROOT_LIB_PATH",
                    new File(dir, "libproroot-runtime.so").getAbsolutePath());
            pb.environment().put("PROROOT_LINKER_PATH",
                    new File(dir, "libproroot-linker.so").getAbsolutePath());
            pb.environment().put("PROROOT_STUB_LOADER",
                    new File(dir, "libproroot-stub-loader.so").getAbsolutePath());
        }

        @Override public void prepare() throws Exception {
            // jniLibs 由系统提取并已带执行位，不需要也不应该改权限
            for (String n : LIBS) {
                if (!new File(dir, n).isFile()) {
                    throw new IllegalStateException("proroot 运行时缺 " + n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            shmDir().mkdirs();
        }
    }

    /**
     * 两个运行时共用的 bind 列表。写在一处，避免「改了 proot 忘了改 proroot」——
     * 这类同一份配置散落两处的问题，本项目已经吃过好几次亏
     * （补丁标记版本不同步、安置逻辑两套并存）。
     */
    String[][] BINDS = {
            {"/dev"},
            {"/dev/urandom", "/dev/random"},
            {"/proc"},
            {"/sys"},
            {"/proc/self/fd", "/dev/fd"},
            {"/storage/emulated/0", "/sdcard"},
            {"/storage/emulated/0", "/storage/emulated/0"},
    };
}
