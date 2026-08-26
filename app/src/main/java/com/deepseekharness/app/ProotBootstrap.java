package com.deepseekharness.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * ProotBootstrap — 一体式 Linux 环境管理（PRoot 方案）。
 *
 * 关键设计（参考 openclaw-termux）：
 * proot、loader、libtalloc 伪装成 lib*.so 放入 jniLibs，Android 安装时
 * 自动解压到 nativeLibraryDir（可执行目录，绕过 App 私有目录的 noexec）。
 * 运行时通过 PROOT_LOADER / PROOT_TMP_DIR / LD_LIBRARY_PATH 环境变量
 * 引导 proot 找到 loader 与依赖库，直接 exec nativeLibraryDir/libproot.so。
 */
public class ProotBootstrap {

    public static final String[] ROOTFS_URLS = {
            // 多镜像源（安装时并行测速，弹窗让你自选；全部实测可用）
            "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.hit.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
    };

    /** Node.js arm64 镜像（多源，并行测速 + 自选；全部实测可用） */
    public static final String[] NODE_URLS = {
            "https://mirrors.huaweicloud.com/nodejs/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirrors.aliyun.com/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://cdn.npmmirror.com/binaries/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirror.nju.edu.cn/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirrors.cloud.tencent.com/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirror.sjtu.edu.cn/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz"
    };

    /** deepseek-harness 安装源：预构建包 + 直连 GitHub 源码构建（特殊项 git://） */
    public static final String[] HARNESS_URLS = {
            // 预构建包源已暂停：catbox 匿名站包体被污染(损坏/含 WSL 脚本)，不再信任
            // 一律走「直连 GitHub 源码构建」保证可靠
            "git://github.com/deepseek-ai/deepseek-harness",
    };

    private final Context ctx;
    private final File baseDir;
    private final File rootfsDir;
    private final File libDir;
    private final File tmpDir;
    private final String nativeLibDir;
    private final File markerFile;

    public ProotBootstrap(Context c) {
        ctx = c.getApplicationContext();
        baseDir = new File(ctx.getFilesDir(), "linux");
        rootfsDir = new File(baseDir, "ubuntu");
        libDir = new File(baseDir, "lib");
        tmpDir = new File(baseDir, "tmp");
        nativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;
        markerFile = new File(baseDir, ".installed");
    }

    public File getRootfsDir() { return rootfsDir; }

    /** 硬链接探测结果缓存（null=未探测）。见 {@link #hardlinkSupported()} */
    private static volatile Boolean hardlinkOk = null;

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 支持时 proot 不加 {@code --link2symlink}：该扩展会把 {@code link()} 的目标改写成
     * 「指向临时目录内中间文件（.l2s.*）的符号链接」，而 dsh 新建文件正是用
     * {@code link(临时文件, 目标)} 发布、随后立刻递归删除临时目录 —— 于是新建的文件
     * 100% 变成悬空链接（write 工具报成功但文件读不出来，edit 走 rename 所以不受影响）。
     * Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余。
     *
     * 探测失败（少数 ROM/文件系统真的不支持）时保留扩展，行为与旧版一致。
     */
    private boolean hardlinkSupported() {
        Boolean cached = hardlinkOk;
        if (cached != null) return cached;
        synchronized (ProotBootstrap.class) {
            if (hardlinkOk != null) return hardlinkOk;
            boolean ok = false;
            String detail = "";
            File dir = rootfsDir.isDirectory() ? rootfsDir : baseDir;
            File src = new File(dir, ".dsha-linkprobe");
            File dst = new File(dir, ".dsha-linkprobe.hl");
            try {
                dir.mkdirs();
                src.delete();
                dst.delete();
                java.nio.file.Files.write(src.toPath(), new byte[] { 'o', 'k' });
                java.nio.file.Files.createLink(dst.toPath(), src.toPath());
                ok = dst.isFile() && dst.length() == 2;
                if (!ok) detail = "link 成功但目标不可读";
            } catch (Throwable e) {
                ok = false;
                detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                android.util.Log.w("DSHA", "硬链接探测失败，保留 --link2symlink: " + e);
            } finally {
                src.delete();
                dst.delete();
            }
            hardlinkOk = ok;
            android.util.Log.i("DSHA", "硬链接支持=" + ok + (detail.isEmpty() ? "" : "（" + detail + "）"));
            // 结果落盘，容器里 cat /root/.dsha-hardlink 就能看到判定依据（否则只能抓 logcat）
            try {
                File mark = new File(rootfsDir, "root/.dsha-hardlink");
                if (mark.getParentFile() != null) mark.getParentFile().mkdirs();
                java.nio.file.Files.write(mark.toPath(),
                        ((ok ? "ok" : "no") + (detail.isEmpty() ? "" : " " + detail) + "\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
            return ok;
        }
    }

    /** 不支持真实硬链接时，把 proot 的 l2s 中间文件集中到 rootfs 内固定目录。
     *  默认行为是「就近存放」——存在临时目录里的中间文件会随临时目录被删掉，
     *  正是 dsh write 新建文件变悬空的直接原因。 */
    private void applyL2sEnv(ProcessBuilder pb) {
        if (hardlinkSupported()) return;
        try {
            File l2s = new File(rootfsDir, ".l2s");
            //noinspection ResultOfMethodCallIgnored
            l2s.mkdirs();
            pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    /** proot 运行环境（两个 exec 入口共用，避免两处漂移） */
    private void applyProotEnv(ProcessBuilder pb) {
        ContainerRuntime rt;
        try {
            rt = runtime();
        } catch (Throwable e) {
            rt = new ContainerRuntime.Proot(ctx, findNativeLib("libproot.so"));
        }
        // ── proot 专用变量：**只在 proot 下设** ──
        // 之所以要分开：proroot 是 LD_PRELOAD 方案，对 LD_LIBRARY_PATH 尤其敏感 ——
        // 把它指向 proot 的库目录有可能干扰 proroot 自己的注入链。
        // PROOT_L2S_DIR 更直接：proroot 有自己的 anchor + symlink group 机制，
        // 塞一个 proot 语义的 l2s 目录进去只会添乱。
        // 这几个变量 proroot 压根不读，但「不读」和「不该出现」是两件事 ——
        // 这个项目已经在「两套机制的配置互相污染」上栽过（npmrc 覆盖冲掉 pnpm 修复）。
        if ("proot".equals(rt.id())) {
            pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
            applyL2sEnv(pb);
            pb.environment().put("PROOT_LOADER",
                    findNativeLib("libprootloader.so").getAbsolutePath());
            pb.environment().put("PROOT_LOADER_32",
                    findNativeLib("libprootloader32.so").getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH",
                    libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
        }
        // ── 运行时自己的变量（放在专用变量之后，允许覆盖）──
        try {
            rt.applyEnv(pb, baseDir, libDir, tmpDir);
        } catch (Throwable ignored) {
        }
        // ── 以下与运行时无关，是 guest 侧的环境 ──
        pb.environment().put("HOME", "/root");
        // guest 的 PATH（否则继承 Android 的 /system/bin，找不到 tail/apt 等）；
        // 前置 /root/dsh-bin = 危险命令确认包装器（DSH_CONFIRM=1 时拦截）
        pb.environment().put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        // TMPDIR 必须指向 guest 的 /tmp（否则 mktemp 会用 Android 的 cache 目录而失败）
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
    }

    /** 组装 proot 公共参数（两个 exec 入口共用，避免两处漂移） */
    /** 连续失败到这个次数就强制切回 proot 并清掉用户的选择。
     *  实验功能允许失败，但不能让人卡在一个起不来的环境里反复试。 */
    private static final int PROROOT_FAIL_LIMIT = 3;

    /** 记一次 proroot 启动失败；达到上限就自动切回 proot。返回是否触发了强制回退。 */
    public boolean noteProrootFailure(String why) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "deepseekharness", Context.MODE_PRIVATE);
            if (!"proroot".equals(sp.getString("container_runtime", "proroot"))) return false;
            int n = sp.getInt("proroot_fail_streak", 0) + 1;
            if (n >= PROROOT_FAIL_LIMIT) {
                sp.edit().putString("container_runtime", "proot")
                        .putInt("proroot_fail_streak", 0)
                        .putString("proroot_last_error", why == null ? "" : why)
                        .apply();
                android.util.Log.w("DSHA", "proroot 连续失败 " + n + " 次，已强制切回 proot：" + why);
                HarnessController.get(ctx).logActivity(
                        "运行时强制回退：proroot 连续失败 " + n + " 次已切回 proot —— " + why);
                return true;
            }
            sp.edit().putInt("proroot_fail_streak", n)
                    .putString("proroot_last_error", why == null ? "" : why).apply();
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 启动成功后清零计数 —— 否则偶发失败累积起来也会触发回退。 */
    public void noteProrootSuccess() {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "deepseekharness", Context.MODE_PRIVATE);
            if (sp.getInt("proroot_fail_streak", 0) != 0) {
                sp.edit().putInt("proroot_fail_streak", 0).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前容器运行时。**默认 proroot**（真机实测启动快 5~6 倍）；
     *  它不可用（.so 缺失）时自动降回 proot，连续 3 次启动失败也会强制切回。
     *  也就是说默认值只影响「先试哪个」，不影响「能不能用」。 */
    public ContainerRuntime runtime() {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "deepseekharness", Context.MODE_PRIVATE);
            if ("proroot".equals(sp.getString("container_runtime", "proroot"))) {
                ContainerRuntime pr = new ContainerRuntime.Proroot(
                        ctx, ContainerRuntime.Proroot.defaultDir(ctx));
                if (pr.available()) {
                    pr.prepare();
                    return pr;
                }
                android.util.Log.w("DSHA", "proroot 不可用，本次降回 proot: "
                        + pr.unavailableReason());
                HarnessController.get(ctx).logActivity(
                        "运行时降级：proroot 不可用，本次用 proot —— " + pr.unavailableReason());
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "选择运行时失败，降回 proot: " + e);
        }
        return new ContainerRuntime.Proot(ctx, findNativeLib("libproot.so"));
    }

    /** 只读地看一眼配置里选的是哪个（不做可用性判断，供 UI 显示）。 */
    public String preferredRuntimeId() {
        try {
            return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getString("container_runtime", "proroot");
        } catch (Throwable e) {
            return "proroot";
        }
    }

    private java.util.List<String> baseProotArgv() {
        return runtime().baseArgv(rootfsDir, hardlinkSupported());
    }

    /**
     * PTY 会话的启动参数：{@code argv[0]} 是容器运行时的可执行文件，末尾接 guest 侧要跑的命令。
     *
     * <p>为什么单独开一个方法而不复用 {@link #execRootfs}：JNI 的 {@code createSubprocess}
     * 要的是 {@code (cmd, argv[], env[])} 三件套，而 execRootfs 是把同样的东西塞进
     * ProcessBuilder。两条路必须共用同一份 argv/env 构造逻辑 —— 各写一份的话，PTY 里的
     * shell 就会跑在和普通命令不一样的环境里，而这个项目已经在「同一份判断散落两处」上
     * 栽过四次。
     *
     * <p>argv[0] 保留成可执行文件自身的路径：查过 termux.c，它把 Java 数组原样转成
     * {@code argv} 后直接 {@code execvp(cmd, argv)}，没有任何加工 —— 和 ProcessBuilder 一致。
     */
    public String[] ptyArgv(String... guestCmd) {
        java.util.List<String> argv = baseProotArgv();
        if (guestCmd == null || guestCmd.length == 0) {
            argv.add("/bin/bash");
            argv.add("-l");
        } else {
            java.util.Collections.addAll(argv, guestCmd);
        }
        return argv.toArray(new String[0]);
    }

    /**
     * PTY 会话的环境变量（{@code KEY=VALUE} 形式）。
     *
     * <p>借一个临时 ProcessBuilder 来收集：环境变量的构造分散在 {@link #applyProotEnv}
     * 与 {@link ContainerRuntime#applyEnv} 两处，还随运行时（proot / proroot）分叉。
     * 重抄一份必然漏，而漏掉 PROOT_LOADER 这种就是直接启动失败。
     */
    public String[] ptyEnv() {
        ProcessBuilder probe = new ProcessBuilder("/system/bin/true");
        applyProotEnv(probe);
        java.util.Map<String, String> m = probe.environment();
        java.util.List<String> out = new java.util.ArrayList<>(m.size());
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out.toArray(new String[0]);
    }
    public boolean isInstalled() {
        return hasBash();
    }

    public boolean hasBash() {
        return new File(rootfsDir, "usr/bin/bash").isFile()
                || new File(rootfsDir, "bin/bash").isFile();
    }

    /** 内置包是否已经解压成功过（和「网上分步装了一半」区分开） */
    public boolean isOfflineExtracted() {
        return new File(baseDir, ".offline-extracted").isFile() && hasBash();
    }

    public void markOfflineExtracted() {
        markInstalled();
        File f = new File(baseDir, ".offline-extracted");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(("ok=" + System.currentTimeMillis() + "\n").getBytes());
        } catch (IOException ignored) {
        }
    }

    /** 把 APK 里找包的过程摊开，解压页可以直接显示，避免再猜。 */
    public String diagnoseBundle() {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(versionName()).append('\n');
        sb.append("apk=").append(ctx.getPackageCodePath()).append('\n');
        File apk = new File(ctx.getPackageCodePath());
        sb.append("apkSize=").append(apk.isFile() ? apk.length() : -1).append('\n');
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry hit = findBundleEntry(z);
            sb.append("zipHit=").append(hit == null ? "null" : hit.getName())
                    .append(" size=").append(hit == null ? -1 : hit.getSize()).append('\n');
            int n = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements() && n < 12) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.contains("asset") || name.contains("offline") || name.contains("rootfs")
                        || name.endsWith(".gz")) {
                    sb.append("  ").append(name).append(" ").append(e.getSize()).append('\n');
                    n++;
                }
            }
        } catch (Exception e) {
            sb.append("zipErr=").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append('\n');
        }
        try {
            String[] names = ctx.getAssets().list("");
            sb.append("assets.list=");
            if (names == null) sb.append("null\n");
            else {
                sb.append(names.length).append('\n');
                for (String s : names) sb.append("  ").append(s).append('\n');
            }
        } catch (Exception e) {
            sb.append("assetsErr=").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public boolean isHarnessInstalled(String workdir) {
        return new File(rootfsDir, "root/" + workdir + "/lib/bin.js").exists()
                || new File(rootfsDir, "root/" + workdir + "/apps/cli/lib/bin.js").exists();
    }

    /** 定位 native 库：nativeLibraryDir 优先，找不到则扫描 lib 根目录下各 ABI 子目录 */
    private File findNativeLib(String name) {
        File direct = new File(nativeLibDir, name);
        if (direct.isFile()) return direct;
        File libRoot = new File(nativeLibDir).getParentFile();
        if (libRoot != null && libRoot.isDirectory()) {
            File[] subs = libRoot.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory()) {
                        File f = new File(sub, name);
                        if (f.isFile()) return f;
                    }
                }
            }
        }
        return direct;
    }

    private String prootPath() {
        return findNativeLib("libproot.so").getAbsolutePath();
    }

    private void chmod(File f, int mode) {
        f.setReadable(true, false);
        f.setExecutable(true, false);
        try {
            android.system.Os.chmod(f.getAbsolutePath(), mode);
        } catch (Throwable ignored) {
        }
    }

    private void copyExec(File src, File dst) {
        if (src.isFile() && !dst.exists()) {
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException ignored) {
            }
            chmod(dst, 0755);
        }
    }

    /** 准备运行时：复制依赖库（匹配 SONAME）、创建目录 */
    public void ensureRuntimeFiles() {
        baseDir.mkdirs();
        tmpDir.mkdirs();
        libDir.mkdirs();

        // 这两个是 **proot 的 NEEDED 依赖**，proroot 用不到
        // （它只链 libdl/libc，共享内存靠挂真实目录当 /dev/shm）。
        // 切到 proroot 时就不必复制了 —— 少一份没人用的文件，也少一处将来的困惑来源。
        boolean isProot = true;
        try {
            isProot = "proot".equals(runtime().id());
        } catch (Throwable ignored) {
        }
        if (isProot) {
            // libtalloc.so.2（proot 的 NEEDED），jniLibs 里叫 libtalloc.so
            copyExec(findNativeLib("libtalloc.so"), new File(libDir, "libtalloc.so.2"));
            // libandroid-shmem.so（旧版 proot 的 NEEDED）
            copyExec(findNativeLib("libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));
        }
    }
    /** 在 rootfs 内执行 bash 命令 */
    public Process execRootfs(String bashCommand) throws IOException {
        java.util.List<String> argv = baseProotArgv();
        argv.add("/bin/bash");
        argv.add("-c");
        argv.add(bashCommand);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        applyProotEnv(pb);
        return pb.start();
    }

    /** 启动交互式 bash 会话（持久进程，可读写 stdin/stdout；cd/export 状态保持，供内置终端使用） */
    public Process execRootfsInteractive() throws IOException {
        java.util.List<String> argv = baseProotArgv();
        argv.add("/bin/bash");
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        applyProotEnv(pb);
        // 交互终端：危险命令启用确认（App 弹窗优先，交互输入兜底）
        pb.environment().put("DSH_CONFIRM", "1");
        pb.environment().put("DSH_INTERACTIVE", "1");
        return pb.start();
    }

    /** 同步执行 rootfs 命令并返回输出 */
    /** 执行 rootfs 命令并读回输出（execRootfs 已 redirectErrorStream 合并 stderr）。带超时防卡死（默认 60s）。 */
    public String execAndRead(String bashCommand) {
        return execAndRead(bashCommand, 60_000);
    }

    /** 执行 rootfs 命令并读回输出。timeoutMs 超时强杀防挂起。
     *  输出读在线程里，避免管道写满（>256KB）死锁 + 超时无法中断。 */
    public String execAndRead(String bashCommand, long timeoutMs) {
        try {
            Process p = execRootfs(bashCommand);
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(
                    () -> readStream(p.getInputStream()));
            Thread t = new Thread(task, "exec-read");
            t.setDaemon(true);
            t.start();
            String out;
            try {
                out = task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception te) {
                p.destroyForcibly();
                return "ERROR: 命令执行超时(>" + (timeoutMs / 1000) + "s)，已强杀";
            }
            if (!p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
            return out;
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 同步执行 rootfs 命令，退出码非 0 时抛异常 */
    public String execChecked(String bashCommand) throws IOException {
        Process p = execRootfs(bashCommand);
        String out = readStream(p.getInputStream());
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
        if (code != 0) {
            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
            // 退出码本身就带信息，但用户只看到一个裸数字。127 尤其常见且极易误诊成
            // 网络问题（用户实测：第五步报 127，实际是 npm 不在 PATH 里）。
            String hint = "";
            if (code == 127) {
                hint = "（127 = 命令找不到，通常是某个工具没装成或不在 PATH 里，"
                        + "不是网络问题）";
            } else if (code == 137) {
                hint = "（137 = 被系统 KILL，通常是内存不足）";
            } else if (code == 126) {
                hint = "（126 = 文件在但不可执行，多为权限或架构不匹配）";
            }
            throw new IOException("退出码 " + code + hint + "：\n" + tail);
        }
        return out;
    }

    /** 带「静默超时」的执行：不设总时长上限，只判「多久一个字都不吐」。
     *
     *  为什么不用普通超时：慢设备上 pnpm install 跑二十分钟是正常的，按总时长掐
     *  会把还在正常干活的安装杀掉、然后报「安装失败」—— 这比卡死更糟，因为它把
     *  「慢」误诊成「坏」，用户还会白白重装一遍。真正卡死的特征是长时间没有任何
     *  输出（网络挂起、等一个永远不会来的响应）。
     *
     *  只适用于**会持续打印进度**的命令（pnpm / apt / npm）。tar、xz 这类正常
     *  也会长时间静默，用这个包就是误杀 —— 它们继续走 execChecked。
     *
     *  @param stallMs 允许的最长静默时间；期间只要有输出就重新计时。
     */
    public String execCheckedStall(String bashCommand, long stallMs) throws IOException {
        final Process p = execRootfs(bashCommand);
        final long[] lastTs = {System.currentTimeMillis()};
        final boolean[] killed = {false};
        Thread watchdog = new Thread(() -> {
            while (p.isAlive()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - lastTs[0] > stallMs) {
                    killed[0] = true;
                    p.destroyForcibly();
                    return;
                }
            }
        }, "dsha-stall-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        String out;
        try {
            out = readStreamTracking(p.getInputStream(), lastTs);
        } finally {
            watchdog.interrupt();
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
        String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
        if (killed[0]) {
            throw new IOException("已超过 " + (stallMs / 1000)
                    + " 秒没有任何输出，判定卡死并终止（多为网络挂起）。最后的输出：\n" + tail);
        }
        if (code != 0) {
            throw new IOException("退出码 " + code + "：\n" + tail);
        }
        return out;
    }

    /** 同 readStream，但每收到一批数据就更新时间戳，供静默看门狗判断死活 */
    private String readStreamTracking(InputStream in, long[] lastTs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 256 * 1024;
        try {
            while ((n = in.read(buf)) != -1) {
                lastTs[0] = System.currentTimeMillis();
                if (kept < MAX) {
                    int w = Math.min(n, MAX - kept);
                    bos.write(buf, 0, w);
                    kept += w;
                }
            }
        } catch (IOException e) {
            // 看门狗强杀进程会让读操作抛异常：此时已收到的输出仍有用（含失败现场）
            return bos.toString("UTF-8");
        }
        return bos.toString("UTF-8");
    }

    /** 读取进程输出，最多保留 256KB 防止内存暴涨 */
    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 256 * 1024;
        while ((n = in.read(buf)) != -1) {
            if (kept < MAX) {
                int w = Math.min(n, MAX - kept);
                bos.write(buf, 0, w);
                kept += w;
            }
        }
        return bos.toString("UTF-8");
    }

    /** 阻塞读取进程输出，保持长驻进程存活；进程退出时返回最后一段输出 */
    public String drainOutput(Process p) throws IOException {
        InputStream in = p.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 64 * 1024;
        while ((n = in.read(buf)) != -1) {
            if (kept < MAX) {
                int w = Math.min(n, MAX - kept);
                bos.write(buf, 0, w);
                kept += w;
            }
        }
        return bos.toString("UTF-8");
    }

    /** 冒烟测试：proot 能否直接 exec + 进 rootfs */
    public String smokeTest() {
        ensureRuntimeFiles();
        StringBuilder diag = new StringBuilder();
        diag.append("proot 路径: ").append(prootPath()).append("\n");
        diag.append("nativeLibDir: ").append(nativeLibDir).append("\n");
        try {
            ProcessBuilder pb = new ProcessBuilder(prootPath(), "--version")
                    .redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
            Process p = pb.start();
            String v = readStream(p.getInputStream());
            p.waitFor();
            diag.append("[1] proot --version: ").append(v == null ? "" : v.trim().split("\n")[0]).append("\n");
        } catch (Throwable e) {
            return "PROOT_FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        String out = execAndRead("/bin/echo SMOKE_OK");
        diag.append("[2] rootfs exec: ").append(out == null ? "" : out.trim()).append("\n");
        return diag.toString();
    }

    /** HEAD 请求测下载源延迟；可用返回耗时毫秒，失败返回 -1 */
    public long probeLatency(String url, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "DSHA/1.0.0");
            int code = conn.getResponseCode();
            conn.disconnect();
            return (code == 200 || code == 206)
                    ? System.currentTimeMillis() - start : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 并行测速全部源，返回延迟毫秒数组（-1 表示不可用） */
    public long[] probeAll(String[] urls, int timeoutMs) {
        final long[] lat = new long[urls.length];
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(urls.length);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Math.max(1, urls.length)));
        for (int i = 0; i < urls.length; i++) {
            final int idx = i;
            pool.execute(() -> {
                try {
                    lat[idx] = probeLatency(urls[idx], timeoutMs);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(timeoutMs + 3000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return lat;
    }

    /** 多源测速排序（并行）：延迟短的在前，测速失败（-1）排最后（仍作 fallback） */
    public String[] orderBySpeed(String[] urls) {
        long[] t = probeAll(urls, 6000);
        String[] out = urls.clone();
        for (int i = 0; i < out.length - 1; i++) {
            for (int j = i + 1; j < out.length; j++) {
                if (t[j] >= 0 && (t[i] < 0 || t[j] < t[i])) {
                    String su = out[i]; out[i] = out[j]; out[j] = su;
                    long st = t[i]; t[i] = t[j]; t[j] = st;
                }
            }
        }
        return out;
    }

    /** 下载进度回调：已下载字节 / 总字节（total<=0 表示源未提供大小） */
    public interface DownloadProgress {
        void onProgress(long downloaded, long total);
    }

    /** 下载 rootfs（带进度回调，支持断点续传；完成后写 .done 标记） */
    public void downloadRootfs(String url, File dest, DownloadProgress progress) throws IOException {
        long existing = dest.exists() ? dest.length() : 0L;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(45000);
        conn.setReadTimeout(300000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "DSHA/1.0.0");
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=" + existing + "-");
        }
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        boolean resume = code == 206;
        long contentLen = conn.getContentLengthLong();
        long totalBytes = resume && contentLen > 0 ? existing + contentLen : contentLen;
        try (InputStream in = conn.getInputStream();
             java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dest, "rw")) {
            if (resume) raf.seek(existing); else raf.setLength(0);
            byte[] buf = new byte[65536];
            long downloaded = resume ? existing : 0L;
            int n;
            int lastPct = -1;
            long lastCbAt = 0;
            while ((n = in.read(buf)) != -1) {
                raf.write(buf, 0, n);
                downloaded += n;
                // 节流：百分比变化或每 500ms 回调一次。
                // （每 64KB 回调会把 UI 线程塞爆；只按百分比回调则大文件几秒才更新一次，
                //  速率/剩余时间显示会很迟钝，所以加时间兜底。）
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (totalBytes > 0) {
                        int pct = (int) (downloaded * 100 / totalBytes);
                        if (pct != lastPct || now - lastCbAt >= 500) {
                            lastPct = pct;
                            lastCbAt = now;
                            progress.onProgress(downloaded, totalBytes);
                        }
                    } else if (now - lastCbAt >= 500) {
                        lastCbAt = now; // 源未提供大小：定期回调，界面才能显示已下大小与速率
                        progress.onProgress(downloaded, -1);
                    }
                }
            }
            try (FileInputStream fis = new FileInputStream(dest)) {
                int b0 = fis.read(), b1 = fis.read();
                // 按格式校验魔数：.xz 校验 xz 魔数（FD 37），其余按 gzip（1F 8B）
                boolean xz = url.toLowerCase().contains(".xz") || dest.getName().endsWith(".xz");
                boolean okMagic = xz
                        ? (b0 == 0xfd && b1 == 0x37)
                        : (b0 == 0x1f && b1 == 0x8b);
                if (!okMagic) {
                    dest.delete();
                    throw new IOException("下载内容不是有效的压缩包（可能是错误页面），已清除");
                }
            }
            try (FileOutputStream fo = new FileOutputStream(dest.getAbsolutePath() + ".done")) {
                fo.write(String.valueOf(downloaded).getBytes());
            } catch (IOException ignored) {
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 解压 rootfs（纯 Java 流式） */
    public void extractRootfs(File tarball) throws IOException {
        if (rootfsDir.exists()) {
            deleteRecursively(rootfsDir);
        }
        rootfsDir.mkdirs();
        TarGzipExtractor.extract(tarball, rootfsDir);
        boolean hasBash = new File(rootfsDir, "usr/bin/bash").exists()
                || new File(rootfsDir, "bin/bash").exists();
        if (!hasBash) {
            throw new IOException("解压后 rootfs 不完整（缺少 bash），请清除环境后重试");
        }
    }

    /** 解压预构建包（去掉顶层目录）到 rootfs 的指定目录 */
    public void extractHarness(File tarball, File target) throws IOException {
        if (target.exists()) deleteRecursively(target);
        target.mkdirs();
        TarGzipExtractor.extract(tarball, target, 1);
    }

    public void setupResolvConf() {
        File rc = new File(rootfsDir, "etc/resolv.conf");
        rc.getParentFile().mkdirs();
        if (rc.exists()) rc.delete();
        try (FileOutputStream o = new FileOutputStream(rc)) {
            // 国内 DNS 优先保证基础解析（墙内 8.8.8.8/1.1.1.1 常被污染/不可达）
            o.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes());
        } catch (IOException ignored) {
        }
    }

    public void markInstalled() {
        markerFile.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(markerFile)) {
            o.write(("installed=" + System.currentTimeMillis() + "\n").getBytes());
        } catch (IOException ignored) {
        }
    }

    /** 内置离线包 asset 名称（GitHub Actions 构建时预置的预装 rootfs 整包） */
    public static final String OFFLINE_BUNDLE_ASSET = "offline-rootfs.tar.gz";

    /** 离线包版本标记 asset（与离线包同步 bump；App 对比 rootfs 里的已解压版本，
     *  发现新版 → 提示用户可升级重解压。缺失=老包，按 0 处理不提示）。 */
    public static final String OFFLINE_VERSION_ASSET = "offline-rootfs.version";

    /** APK 内置离线包版本（读 asset；失败/缺失返回 "0"） */
    public String bundledOfflineVersion() {
        try (java.io.InputStream in = ctx.getAssets().open(OFFLINE_VERSION_ASSET)) {
            byte[] buf = new byte[32];
            int n = in.read(buf);
            String s = n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return "0";
        }
    }

    /** rootfs 已解压的离线包版本（写于解压完成；缺失返回 "0"） */
    public String installedOfflineVersion() {
        try {
            java.io.File f = new java.io.File(rootfsDir, "root/.dsh/offline-rootfs.version");
            if (!f.isFile()) return "0";
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 解压完成后记录离线包版本标记（供启动时对比，发现新包可提示升级） */
    private void writeOfflineVersion() {
        try {
            java.io.File f = new java.io.File(rootfsDir, "root/.dsh/offline-rootfs.version");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), bundledOfflineVersion().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "离线包版本标记写入失败，升级提示可能重复出现: " + e);
        }
    }

    /** aapt 会把 .tar.gz 自动解成 .tar，所以这些名字都算内置包。 */
    private static final String[] BUNDLE_NAMES = {
            "offline-rootfs.tar.gz",
            "offline-rootfs.tar",
            "offline-rootfs.bin",
            "offline-rootfs.tgz",
    };

    public boolean hasOfflineBundle() {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(ctx.getPackageCodePath())) {
            if (findBundleEntry(z) != null) return true;
        } catch (Exception ignored) {
        }
        for (String n : BUNDLE_NAMES) {
            try {
                ctx.getAssets().open(n).close();
                return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private java.util.zip.ZipEntry findBundleEntry(java.util.zip.ZipFile z) {
        for (String n : BUNDLE_NAMES) {
            java.util.zip.ZipEntry e = z.getEntry("assets/" + n);
            if (e != null && !e.isDirectory()) return e;
            e = z.getEntry(n);
            if (e != null && !e.isDirectory()) return e;
        }
        java.util.zip.ZipEntry best = null;
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.contains("offline-rootfs") || name.contains("offline_rootfs")) {
                if (best == null || e.getSize() > best.getSize()) best = e;
            }
        }
        return best;
    }

    /**
     * 从 APK 内置包解压预装 rootfs。优先按 zip 条目流式解压（不经 AssetManager，
     * 也不先拷 300MB 到 tmp），失败再回退 assets。
     */
    public void extractOfflineBundle(java.util.function.BiConsumer<Long, Long> onProgress) throws IOException {
        ensureRuntimeFiles();
        java.util.zip.ZipFile apk = null;
        InputStream raw = null;
        long total = 0;
        try {
            apk = new java.util.zip.ZipFile(ctx.getPackageCodePath());
            java.util.zip.ZipEntry e = findBundleEntry(apk);
            if (e != null) {
                raw = apk.getInputStream(e);
                total = e.getSize() > 0 ? e.getSize() : 0;
            }
        } catch (IOException ignored) {
            if (apk != null) {
                try { apk.close(); } catch (IOException ignored2) {}
                apk = null;
            }
        }
        if (raw == null) {
            IOException last = null;
            for (String n : BUNDLE_NAMES) {
                try {
                    raw = ctx.getAssets().open(n);
                    try {
                        total = ctx.getAssets().openFd(n).getLength();
                    } catch (IOException ignored) {
                    }
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (raw == null) {
                throw last != null ? last : new IOException("assets 里也没有离线包");
            }
        }

        final java.util.function.BiConsumer<Long, Long> cb = onProgress;
        final long tot = total;
        InputStream counted = new java.io.FilterInputStream(raw) {
            long done = 0;
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0 && cb != null) {
                    done += n;
                    cb.accept(done, tot);
                }
                return n;
            }
        };

        try {
            // ===== 数据保护：重解压前备份用户数据（.dsh 配置/对话 + 所有工作目录 .env），解压后自动还原 =====
            // 旧 rootfs 存在但 isOfflineExtracted() 判定失败（标记丢失/bash 路径变化）会走到这里，
            // 直接删整个 rootfs 会连对话记录一起丢掉（issue#9 第1条）——必须先备份再删。
            // .env 遍历 /root 下所有子目录（兼容用户自定义 workdir，不只默认 deepseek-harness）。
            java.io.File dataBak = null;
            if (rootfsDir.exists()) {
                java.io.File dshDir = new java.io.File(rootfsDir, "root/.dsh");
                java.io.File rootHome = new java.io.File(rootfsDir, "root");
                java.io.File[] workDirs = rootHome.isDirectory() ? rootHome.listFiles(java.io.File::isDirectory) : null;
                boolean hasData = dshDir.isDirectory() || (workDirs != null && workDirs.length > 0);
                if (hasData) {
                    dataBak = new java.io.File(baseDir, ".data-preserve-" + System.currentTimeMillis());
                    dataBak.mkdirs();
                    if (dshDir.isDirectory()) {
                        copyRecursively(dshDir, new java.io.File(dataBak, "dsh"));
                    }
                    if (workDirs != null) {
                        for (java.io.File d : workDirs) {
                            java.io.File e = new java.io.File(d, ".env");
                            if (e.isFile()) {
                                copyFile(e, new java.io.File(dataBak, "env-" + d.getName()));
                            }
                        }
                    }
                }
            }
            if (rootfsDir.exists()) deleteRecursively(rootfsDir);
            rootfsDir.mkdirs();
            TarGzipExtractor.extractAuto(counted, rootfsDir, 0);
            if (!hasBash()) {
                throw new IOException("解压后 rootfs 不完整（缺少 bash）\n" + diagnoseRootfs());
            }
            setupResolvConf();
            // 解压完成后还原用户数据（.dsh + 所有工作目录 .env）
            if (dataBak != null) {
                try {
                    java.io.File dshDst = new java.io.File(rootfsDir, "root/.dsh");
                    java.io.File dshBak = new java.io.File(dataBak, "dsh");
                    if (dshBak.isDirectory()) {
                        if (!dshDst.exists()) dshDst.mkdirs();
                        copyRecursively(dshBak, dshDst);
                    }
                    // 还原各工作目录 .env（env-<dir> 命名，还原到 root/<dir>/.env）
                    java.io.File[] envBaks = dataBak.listFiles((d, n) -> n.startsWith("env-"));
                    if (envBaks != null) {
                        for (java.io.File eb : envBaks) {
                            String dirName = eb.getName().substring("env-".length());
                            java.io.File envDst = new java.io.File(rootfsDir, "root/" + dirName + "/.env");
                            if (envDst.getParentFile() != null) envDst.getParentFile().mkdirs();
                            copyFile(eb, envDst);
                        }
                    }
                    // .bridge_token 是**本机**的 3090 桥凭据，不算用户数据 —— BackupManager
                    // 备份时就明确排除了它（见那里的注释），而这里却随整个 .dsh 备份还原回来，
                    // 两处对「什么算用户数据」的判断不一致。后果非常隐蔽：
                    // HttpShellService.authToken 是静态字段、只在缓存为空时才读文件，于是
                    // App 校验的是自己内存里那个、插件与 agent skill 拿到的是还原回来的旧的
                    // → 一律 401。表现出来就是「悬浮窗不显示 + agent 工具调用失败」，而不走
                    // 3090 的内置 UI 注入、提示词注入全都好着 —— 真机上就是这么报上来的，
                    // 光看现象根本想不到是 token。
                    // 清掉本机专属的东西：清单来自 UserDataPolicy，与备份的 --exclude 同源。
                    // 数据保护是把 .dsh 整目录复制回来的，所以必须再清一遍 —— 否则旧的桥
                    // token 和内置插件版本标记会活过这次重解压，而它们描述的对象（App 内存里
                    // 的 token、/root/dsha-* 插件实体）已经随 rootfs 一起换掉了。
                    // 这个形状真机上出过两次故障，详见 UserDataPolicy 的类注释。
                    for (String rel : UserDataPolicy.purgeAfterRestore()) {
                        java.io.File stale = new java.io.File(rootfsDir, rel);
                        if (stale.isFile()) {
                            //noinspection ResultOfMethodCallIgnored
                            stale.delete();
                            android.util.Log.i("DSHA", "重解压后清掉本机专属文件: " + rel);
                        }
                    }
                    // 再让 App 侧重新生成桥 token 并写回，两边对齐（dsh 后端自己也缓存 token，
                    // 所以仍需重启 Web 才彻底生效 —— 解压页本来就会重启）
                    HttpShellService.resetTokenAfterRestore();
                    android.util.Log.i("DSHA", "重解压已还原用户数据 (.dsh + 工作区 .env)");
                } catch (Throwable e) {
                    android.util.Log.w("DSHA", "还原用户数据失败: " + e);
                }
                deleteRecursively(dataBak);
            }
            markOfflineExtracted();
            // 记录离线包版本（启动时对比，发现新版可提示升级）
            writeOfflineVersion();
        } finally {
            try { counted.close(); } catch (Exception ignored) {}
            if (apk != null) {
                try { apk.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 诊断 rootfs 关键路径状态 */
    public String diagnoseRootfs() {
        StringBuilder sb = new StringBuilder();
        sb.append("rootfs 路径: ").append(rootfsDir.getAbsolutePath()).append("\n");
        File bash = new File(rootfsDir, "usr/bin/bash");
        sb.append("usr/bin/bash 存在=").append(bash.exists())
          .append(bash.exists() ? " 大小=" + bash.length() : "").append("\n");
        File ld = new File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1");
        sb.append("ld-linux 存在=").append(ld.exists()).append("\n");
        File etc = new File(rootfsDir, "etc/os-release");
        sb.append("etc/os-release 存在=").append(etc.exists()).append("\n");
        sb.append("已安装标记=").append(markerFile.exists());
        return sb.toString();
    }

    public void uninstall() {
        try {
            new ProcessBuilder("/system/bin/rm", "-rf", baseDir.getAbsolutePath())
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            deleteRecursively(baseDir);
        }
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 递归拷贝目录/文件（重解压前数据保护用） */
    private void copyRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录: " + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyRecursively(c, new File(dst, c.getName()));
                }
            }
        } else if (src.isFile()) {
            copyFile(src, dst);
        }
    }

    /** 拷贝单个文件 */
    private void copyFile(File src, File dst) throws IOException {
        if (dst.getParentFile() != null && !dst.getParentFile().exists() && !dst.getParentFile().mkdirs()) {
            throw new IOException("无法创建父目录: " + dst.getParentFile());
        }
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

}
