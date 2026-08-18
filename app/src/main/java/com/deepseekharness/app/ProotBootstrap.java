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

    private void writeFile(File dest, byte[] bytes) {
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(bytes);
        } catch (IOException ignored) {
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

        // libtalloc.so.2（proot 的 NEEDED），jniLibs 里叫 libtalloc.so
        copyExec(findNativeLib("libtalloc.so"), new File(libDir, "libtalloc.so.2"));
        // libandroid-shmem.so（旧版 proot 的 NEEDED）
        copyExec(findNativeLib("libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));
    }

    private byte[] readAsset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** 在 rootfs 内执行 bash 命令 */
    public Process execRootfs(String bashCommand) throws IOException {
        String[] argv = {
                prootPath(),
                "--link2symlink", "-L", "--kill-on-exit",
                "-0",
                "--rootfs=" + rootfsDir.getAbsolutePath(),
                "--cwd=/root",
                "-b", "/dev",
                "-b", "/dev/urandom:/dev/random",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/proc/self/fd:/dev/fd",
                "/bin/bash", "-c", bashCommand
        };
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        pb.environment().put("PROOT_LOADER", findNativeLib("libprootloader.so").getAbsolutePath());
        pb.environment().put("PROOT_LOADER_32", findNativeLib("libprootloader32.so").getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
        pb.environment().put("HOME", "/root");
        // 关键：guest 的 PATH（否则继承 Android 的 /system/bin，找不到 tail/apt 等）
        // 前置 /root/dsh-bin：危险命令确认包装器（DSH_CONFIRM=1 时拦截）
        pb.environment().put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        // 关键：TMPDIR 必须指向 guest 的 /tmp（否则 mktemp 用 Android 的 cache 目录而失败）
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        return pb.start();
    }

    /** 启动交互式 bash 会话（持久进程，可读写 stdin/stdout；cd/export 状态保持，供内置终端使用） */
    public Process execRootfsInteractive() throws IOException {
        String[] argv = {
                prootPath(),
                "--link2symlink", "-L", "--kill-on-exit",
                "-0",
                "--rootfs=" + rootfsDir.getAbsolutePath(),
                "--cwd=/root",
                "-b", "/dev",
                "-b", "/dev/urandom:/dev/random",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/proc/self/fd:/dev/fd",
                "/bin/bash"
        };
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        pb.environment().put("PROOT_LOADER", findNativeLib("libprootloader.so").getAbsolutePath());
        pb.environment().put("PROOT_LOADER_32", findNativeLib("libprootloader32.so").getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
        pb.environment().put("HOME", "/root");
        pb.environment().put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        // 交互终端：危险命令启用确认（App 弹窗优先，交互输入兜底）
        pb.environment().put("DSH_CONFIRM", "1");
        pb.environment().put("DSH_INTERACTIVE", "1");
        return pb.start();
    }

    /** 同步执行 rootfs 命令并返回输出 */
    public String execAndRead(String bashCommand) {
        try {
            Process p = execRootfs(bashCommand);
            String out = readStream(p.getInputStream());
            p.waitFor();
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
            throw new IOException("退出码 " + code + "：\n" + tail);
        }
        return out;
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
            while ((n = in.read(buf)) != -1) {
                raf.write(buf, 0, n);
                downloaded += n;
                // 节流：仅百分比变化时回调（每 64KB 回调会把 UI 线程塞爆导致卡顿）
                if (progress != null) {
                    if (totalBytes > 0) {
                        int pct = (int) (downloaded * 100 / totalBytes);
                        if (pct != lastPct) {
                            lastPct = pct;
                            progress.onProgress(downloaded, totalBytes);
                        }
                    } else if (lastPct != -2) {
                        lastPct = -2; // 源未提供大小：只通知一次"下载中"
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
            if (rootfsDir.exists()) deleteRecursively(rootfsDir);
            rootfsDir.mkdirs();
            TarGzipExtractor.extractAuto(counted, rootfsDir, 0);
            if (!hasBash()) {
                throw new IOException("解压后 rootfs 不完整（缺少 bash）\n" + diagnoseRootfs());
            }
            setupResolvConf();
            markOfflineExtracted();
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
}
