package com.deepseekharness.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心控制器：分步安装（rootfs / 基础工具 / Node / deepseek-harness，每步可单独
 * 重装或更新，多镜像自动测速选最快源）、一键补装、Web UI 启停。
 *
 * 进度/错误状态保存在单例中，通过 {@link StateListener} 通知 UI；
 * Fragment 切换时状态不丢失，避免异步回调打到已销毁视图上。
 */
public class HarnessController {

    // ================= 分步安装步骤 =================
    public static final int STEP_ROOTFS = 1;
    public static final int STEP_TOOLS = 2;
    public static final int STEP_NODE = 3;
    public static final int STEP_HARNESS = 4;

    // ================= 下载任务（用于源选择记忆） =================
    public static final int TASK_ROOTFS = 1;
    public static final int TASK_NODE = 2;
    public static final int TASK_HARNESS = 3;

    private static final String PREFS = "deepseekharness";
    private static HarnessController instance;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ProotBootstrap proot;

    // ================= 进度/错误状态（跨 Fragment 保持） =================
    public interface StateListener {
        void onStateChanged();
    }

    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String stage = "";
    private volatile int percent = 0;
    private volatile String message = "";
    private volatile String error = "";
    private volatile boolean busy = false;
    private volatile int currentStep = 0;
    private volatile Process webProcess;

    // ===== 下载源自选（测速 → 弹窗等待用户选择） =====
    private final Object sourceLock = new Object();
    private volatile boolean awaitingSource = false;
    private volatile int sourceChoice = -1;
    private volatile int pendingTask = 0;
    private volatile String[] pendingUrls = null;
    private volatile long[] pendingLat = null;

    public static synchronized HarnessController get(Context ctx) {
        if (instance == null) {
            instance = new HarnessController(ctx.getApplicationContext());
        }
        return instance;
    }

    private HarnessController(Context ctx) {
        this.appContext = ctx;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.proot = new ProotBootstrap(ctx);
    }

    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    public String getStage() { return stage; }
    public int getPercent() { return percent; }
    public String getMessage() { return message; }
    public String getError() { return error; }
    public boolean isBusy() { return busy; }
    /** 当前正在执行的步骤（0 = 空闲） */
    public int getCurrentStep() { return currentStep; }

    private void setState(String stage, int percent, String msg, String err, boolean b) {
        this.stage = stage;
        this.percent = percent;
        this.message = msg;
        this.error = err;
        this.busy = b;
        // 持久化进度，闪退后下次启动可定位中断步骤
        if (!stage.isEmpty()) {
            prefs.edit().putString("last_stage", stage + " " + percent + "%").apply();
        }
        if (err != null && !err.isEmpty()) {
            prefs.edit().putString("last_error", err).apply();
        }
        // 状态可能在 IO 线程变更，回调需切回主线程再通知 UI
        mainHandler.post(() -> {
            for (StateListener l : stateListeners) l.onStateChanged();
        });
    }

    public String getLastStage() { return prefs.getString("last_stage", ""); }
    public String getLastError() { return prefs.getString("last_error", ""); }

    private void setProgress(String stage, int percent) {
        setState(stage, percent, "", "", true);
    }

    /** 生成可读的错误描述（含异常类名与堆栈首帧，便于排查） */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append("\n    at ").append(st[0].toString());
        }
        return sb.toString();
    }

    /** 步骤显示名 */
    public static String stepName(int step) {
        switch (step) {
            case STEP_ROOTFS: return "① Linux 环境（rootfs）";
            case STEP_TOOLS: return "② 基础工具（apt）";
            case STEP_NODE: return "③ Node.js";
            case STEP_HARNESS: return "④ deepseek-harness";
        }
        return "步骤 " + step;
    }

    /** 从 URL 取主机名（进度显示用） */
    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ================= 配置读写 =================
    public String getApiKey() { return prefs.getString("api_key", ""); }
    public void setApiKey(String v) { prefs.edit().putString("api_key", v).apply(); }

    public String getPort() { return prefs.getString("port", "3080"); }
    public void setPort(String v) { prefs.edit().putString("port", v).apply(); }

    public String getModel() { return prefs.getString("model", "deepseek-v4-flash"); }
    public void setModel(String v) { prefs.edit().putString("model", v).apply(); }

    public String getPermissionMode() { return prefs.getString("permission_mode", "danger-full-access"); }
    public void setPermissionMode(String v) { prefs.edit().putString("permission_mode", v).apply(); }

    public String getWorkdir() { return prefs.getString("workdir", "deepseek-harness"); }
    public void setWorkdir(String v) { prefs.edit().putString("workdir", v).apply(); }

    public String effectiveApiKey() {
        return getApiKey();
    }

    public ProotBootstrap getProot() { return proot; }

    /** 是否已安装 deepseek-harness（跟随自定义工作区路径） */
    public boolean isHarnessInstalled() {
        return proot.isHarnessInstalled(getWorkdir());
    }

    /** Web UI 进程是否在运行 */
    public boolean isWebRunning() {
        return webProcess != null && webProcess.isAlive();
    }

    // ================= 分步安装 =================

    /** 一键安装：按顺序补装尚未完成的步骤 */
    public void install() {
        if (busy) return;
        IO.execute(() -> {
            try {
                for (int s = STEP_ROOTFS; s <= STEP_HARNESS; s++) {
                    if (!isStepDone(s)) runInstallStep(s);
                }
                setState("", 100, "全部安装完成，可到「启动」页启动 Web UI", "", false);
            } catch (Throwable e) {
                setState("", 0, "", "安装出错：" + describe(e), false);
            }
        });
    }

    /** 单独执行一个步骤（已完成则视为重装/更新） */
    public void installStep(int step) {
        if (busy) return;
        IO.execute(() -> {
            try {
                runInstallStep(step);
                setState("", 100, "「" + stepName(step) + "」完成", "", false);
            } catch (Throwable e) {
                setState("", 0, "", "安装出错：" + describe(e), false);
            }
        });
    }

    /** 步骤是否已完成（UI 打勾用） */
    public boolean isStepDone(int step) {
        switch (step) {
            case STEP_ROOTFS:
                return proot.isInstalled();
            case STEP_TOOLS:
                return toolsInstalled();
            case STEP_NODE:
                // node 与 npm 都就绪才算完成（npm 缺失会导致 pnpm 安装失败）
                return new File(proot.getRootfsDir(), "usr/local/bin/node").exists()
                        && new File(proot.getRootfsDir(), "usr/local/bin/npm").exists();
            case STEP_HARNESS:
                // 不仅要求 bin.js 存在，还要求 node-pty 编译产物就绪（否则启动 Web UI 必炸）
                return proot.isHarnessInstalled(getWorkdir()) && hasPtyNode();
        }
        return false;
    }

    /** 检查 node-pty 编译产物（pty.node）是否就绪 */
    private boolean hasPtyNode() {
        try {
            File wdDir = new File(proot.getRootfsDir(), "root/" + getWorkdir());
            File pnpmDir = new File(wdDir, "node_modules/.pnpm");
            if (!pnpmDir.isDirectory()) return false;
            File[] ptyDirs = pnpmDir.listFiles((d, n) -> n.startsWith("node-pty@"));
            if (ptyDirs == null) return false;
            for (File d : ptyDirs) {
                File base = new File(d, "node_modules/node-pty");
                if (new File(base, "build/Release/pty.node").isFile()) return true;
                if (new File(base, "prebuilds/linux-arm64/pty.node").isFile()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 检查文件是否为有效的 xz 压缩包（魔数 FD 37 7A 58 5A） */
    public boolean validXz(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 0xfd && in.read() == 0x37 && in.read() == 0x7a
                    && in.read() == 0x58 && in.read() == 0x5a;
        } catch (Exception e) {
            return false;
        }
    }

    /** 检查文件是否为有效的 gzip 包（校验魔数 0x1f 0x8b） */
    private boolean validGzip(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (Exception e) {
            return false;
        }
    }

    /** rootfs 内基础工具是否齐备 */
    private boolean toolsInstalled() {
        try {
            String out = proot.execAndRead(
                    "command -v curl >/dev/null && command -v git >/dev/null && " +
                    "command -v python3 >/dev/null && command -v make >/dev/null && " +
                    "command -v gcc >/dev/null && command -v xz >/dev/null && echo TOOLS_OK || echo TOOLS_MISSING");
            return out != null && out.contains("TOOLS_OK");
        } catch (Throwable e) {
            return false;
        }
    }

    private void runInstallStep(int step) throws Exception {
        currentStep = step;
        try {
            switch (step) {
                case STEP_ROOTFS: installRootfs(); break;
                case STEP_TOOLS: installTools(); break;
                case STEP_NODE: installNode(); break;
                case STEP_HARNESS: installHarness(); break;
                default: throw new Exception("未知步骤：" + step);
            }
        } finally {
            currentStep = 0;
        }
    }

    private void requireRootfs() throws Exception {
        if (!proot.isInstalled()) {
            throw new Exception("前置步骤未完成，请先执行 ① Linux 环境（rootfs）");
        }
    }

    private void requireTools() throws Exception {
        if (!toolsInstalled()) {
            throw new Exception("前置步骤未完成，请先执行 ② 基础工具（apt）");
        }
    }

    /** ① rootfs：测速下载 → 解压 → 冒烟测试（全局进度 0~59） */
    private void installRootfs() throws Exception {
        setProgress("准备 proot 运行时", 2);
        proot.ensureRuntimeFiles();

        File tarball = new File(proot.getRootfsDir().getParentFile(), "rootfs.tar.gz");
        File doneMark = new File(tarball.getAbsolutePath() + ".done");

        // 已有完整下载则跳过；否则断点续传（downloadRootfs 内部 Range 续传）
        boolean haveComplete = doneMark.exists() && tarball.exists()
                && tarball.length() > 15L * 1024 * 1024;
        if (!haveComplete) {
            downloadWithPick(TASK_ROOTFS, ProotBootstrap.ROOTFS_URLS,
                    "下载 Ubuntu rootfs（~30MB）", tarball, 4, 2);
        }

        setProgress("rootfs 下载完成，正在解压（约 5~15 分钟，进度会暂时停住，请勿关闭 App）", 57);
        proot.extractRootfs(tarball);
        proot.setupResolvConf();

        // proot 冒烟测试：确认能进入 rootfs 执行命令
        String smoke = proot.smokeTest();
        if (smoke == null || !smoke.contains("SMOKE_OK")) {
            throw new Exception("proot 进入 rootfs 失败（bash 无法执行）：\n"
                    + (smoke == null ? "" : smoke)
                    + "\n\n[环境诊断]\n" + proot.diagnoseRootfs());
        }

        proot.markInstalled();
        // 解压成功后清理 tarball 与标记，释放空间
        //noinspection ResultOfMethodCallIgnored
        tarball.delete();
        //noinspection ResultOfMethodCallIgnored
        doneMark.delete();
        setProgress("环境安装完成", 59);
    }

    /** ② 基础工具：apt 换国内源 + 安装 curl/git/python3/make/xz（全局进度 60~69） */
    private void installTools() throws Exception {
        requireRootfs();
        // 先把 apt 源换成国内镜像（直连 ports.ubuntu.com 在国内常被重置）
        setProgress("替换 apt 国内源", 60);
        proot.execAndRead(
                "sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; " +
                "s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' " +
                "/etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true");
        try {
            runStep("更新 apt 源", 62, "apt-get update -y");
            runStep("安装基础工具（curl/git/python3/make/gcc/xz）", 65,
                    "apt-get install -y --no-install-recommends curl git python3 make gcc g++ xz-utils");
        } catch (Throwable e) {
            throw new Exception(e.getMessage() + "\n\n[环境诊断]\n" + proot.diagnoseRootfs());
        }
        // ca-certificates 的 postinst 在 proot 下必失败，装完基础工具后单独处理，
        // 强制移除避免 dpkg broken 状态阻塞后续 apt 操作
        try {
            proot.execAndRead(
                "apt-get install -y --no-install-recommends ca-certificates 2>/dev/null || true; " +
                "dpkg --remove --force-remove-reinstreq ca-certificates 2>/dev/null || true; " +
                "dpkg --configure -a 2>/dev/null || true");
        } catch (Throwable ignored) {
        }
        setProgress("基础工具就绪", 69);
    }

    /** ③ Node.js：测速下载到 rootfs /tmp → 解压（全局进度 70~89） */
    private void installNode() throws Exception {
        requireRootfs();
        requireTools();
        File nodePkg = new File(proot.getRootfsDir(), "tmp/node.tar.xz");
        // 完整性检查：大小 ≥40MB 且 xz 魔数正确（防下载中断的截断文件混过检查导致解压 EOF）
        boolean haveGood = nodePkg.exists() && nodePkg.length() >= 40L * 1024 * 1024
                && proot.validXz(nodePkg);
        if (haveGood) {
            setProgress("Node.js 安装包已存在，跳过下载", 71);
        } else {
            if (nodePkg.exists()) {
                //noinspection ResultOfMethodCallIgnored
                nodePkg.delete(); // 清掉截断/损坏的旧包
            }
            downloadWithPick(TASK_NODE, ProotBootstrap.NODE_URLS, "下载 Node.js", nodePkg, 71, 6);
        }
        // 解压失败自动重下一次（npmmirror）再试一次；仍失败则抛错中断
        runStep("安装 Node.js", 88,
                "cd /tmp && (tar -xJf node.tar.xz -C /usr/local --strip-components=1 || "
                        + "(echo '安装包损坏，自动重新下载…'; rm -f node.tar.xz; "
                        + "curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz && "
                        + "tar -xJf node.tar.xz -C /usr/local --strip-components=1))");
        setProgress("Node.js 就绪", 89);
    }

    /** ④ deepseek-harness：预构建包 或 直连源码构建（全局进度 90~100） */
    private void installHarness() throws Exception {
        requireRootfs();
        String apiKey = effectiveApiKey();
        String[] urls = ProotBootstrap.HARNESS_URLS;
        setProgress("测速中…（含直连选项）", 91);
        long[] lat = proot.probeAll(urls, 6000);
        setProgress("请选择安装方式（预构建包 / 直连源码）", 91);
        String[] ordered = waitUserPick(TASK_HARNESS, urls, lat);

        // 选了「直连 GitHub 源码构建」：不走预构建包
        if (ordered[0].startsWith("git://")) {
            installHarnessFromSource();
            return;
        }

        // 预构建好的 deepseek-harness（含 node_modules + 构建产物），直接下载解压，避免设备内存不足 OOM
        File pkg = new File(proot.getRootfsDir().getParentFile(), "dsh.tar.gz");
        File pkgDone = new File(pkg.getAbsolutePath() + ".done");
        // 复用前校验：必须是有效的 gzip 包（防止之前下载被墙/截断留下的假包）
        boolean havePkg = pkgDone.exists() && pkg.exists() && pkg.length() > 1024L * 1024
                && validGzip(pkg);
        if (havePkg) {
            setProgress("预构建包已存在，跳过下载", 92);
        } else {
            boolean ok = false;
            String lastErr = "";
            for (String url : ordered) {
                if (url.startsWith("git://")) continue;
                try {
                    proot.downloadRootfs(url, pkg, p -> {
                        if (p < 0) {
                            setProgress("下载 deepseek-harness 预构建包…（源未提供大小，请耐心等待）",
                                    Math.min(98, 92));
                        } else {
                            setProgress("下载 deepseek-harness 预构建包 " + p + "%（源：" + hostOf(url) + "）",
                                    Math.min(99, 92 + p / 12));
                        }
                    });
                    if (!validGzip(pkg)) {
                        //noinspection ResultOfMethodCallIgnored
                        pkg.delete();
                        throw new Exception("下载内容不是有效的压缩包（可能被墙/劫持），已删除");
                    }
                    ok = true;
                    break;
                } catch (Exception e) {
                    lastErr = e.getMessage();
                }
            }
            if (!ok) {
                throw new Exception("预构建包下载失败（网络被重置？）: " + lastErr
                        + "\n\n可尝试：切换网络 / 开启代理 / 重新选择「直连 GitHub 源码构建」");
            }
        }

        setProgress("下载完成，正在解压 deepseek-harness（大包约 5~15 分钟，进度会暂时停住，请勿关闭 App）", 99);
        String wd = getWorkdir();
        try {
            proot.extractHarness(pkg, new File(proot.getRootfsDir(), "root/" + wd));
        } catch (Exception e) {
            // 解压失败 = 包损坏：删除坏包（避免下次复用），给出明确指引
            //noinspection ResultOfMethodCallIgnored
            pkg.delete();
            //noinspection ResultOfMethodCallIgnored
            pkgDone.delete();
            throw new Exception("预构建包损坏或解压失败：" + e.getMessage()
                    + "\n\n已删除损坏的包，可重试下载，或改选「直连 GitHub 源码构建」（更稳）");
        }

        runStep("写入 API key", 99,
                "cd /root/" + wd + " && printf 'DEEPSEEK_API_KEY=%s\\n' '" + apiKey + "' > .env");
    }

    /** 直连 GitHub 源码构建（clone 多通道 fallback + npmmirror 依赖/headers 源） */
    private void installHarnessFromSource() throws Exception {
        String wd = getWorkdir();
        String apiKey = effectiveApiKey();

        // 源码构建需要完整工具链（gcc/g++ 编译 node-pty 等原生模块），缺失自动补装
        requireRootfs();
        if (!toolsInstalled()) {
            setProgress("自动补装基础工具（gcc/g++ 等）", 91);
            installTools();
        }

        setProgress("启用 pnpm", 92);
        // 不依赖 corepack（新版 Node 常缺失）：直接用 npm 安装 pnpm@11.7.0（与项目 packageManager 匹配）
        // 已安装则跳过（否则 npm 报 EEXIST 导致重装失败）
        runStep("启用 pnpm", 92,
                "(pnpm -v >/dev/null 2>&1 && echo 'pnpm 已就绪，跳过安装') || "
                        + "if command -v npm >/dev/null 2>&1; then "
                        + "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com; "
                        + "else "
                        + "echo 'npm 缺失，自动补装 Node.js'; "
                        + "[ -s /tmp/node.tar.xz ] || curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz; "
                        + "cd /tmp && tar -xJf node.tar.xz -C /usr/local --strip-components=1 && "
                        + "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com; "
                        + "fi");

        setProgress("获取 deepseek-harness 源码", 93);
        runStep("获取 deepseek-harness 源码", 93,
                "cd /root && " +
                "if [ -d " + wd + " ] && [ -f " + wd + "/package.json ]; then " +
                "echo '源码已存在，跳过克隆（尝试增量更新）'; " +
                "(cd " + wd + " && git pull --ff-only 2>/dev/null || true); " +
                "else rm -rf " + wd + " && ( " +
                "git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gitclone.com/github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://ghfast.top/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " ); fi");

        // 应用 WebUI 移动端补丁（移除“打开/收起侧边栏”按钮）；失败不阻塞安装
        try {
            String patchStr = readAsset("webui-sidebar.patch");
            if (!patchStr.isEmpty()) {
                java.io.File patchFile = new java.io.File(proot.getRootfsDir(), "root/dsha-webui.patch");
                patchFile.getParentFile().mkdirs();
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(patchFile)) {
                    fo.write(patchStr.getBytes(StandardCharsets.UTF_8));
                }
                runStep("应用 WebUI 移动端补丁", 93,
                        "cd /root/" + wd + " && " +
                        "(git apply --check /root/dsha-webui.patch 2>/dev/null && " +
                        "git apply /root/dsha-webui.patch && echo '补丁已应用（已移除侧边栏开关按钮）') || " +
                        "echo '补丁跳过（可能已应用或源码已更新）'");
            }
        } catch (Exception ignored) {
        }

        // 应用 bash 安全守卫补丁（bash 工具每次执行前 source dsh-guard.sh，防环境白名单绕过）；失败不阻塞
        try {
            String bgPatch = readAsset("bash-guard.patch");
            if (!bgPatch.isEmpty()) {
                java.io.File bgFile = new java.io.File(proot.getRootfsDir(), "root/dsha-bash-guard.patch");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(bgFile)) {
                    fo.write(bgPatch.getBytes(StandardCharsets.UTF_8));
                }
                runStep("应用 bash 守卫补丁", 93,
                        "cd /root/" + wd + " && " +
                        "(git apply --check /root/dsha-bash-guard.patch 2>/dev/null && " +
                        "git apply /root/dsha-bash-guard.patch && echo 'bash 守卫补丁已应用') || " +
                        "echo 'bash 守卫补丁跳过（可能已应用或源码已更新）'");
            }
        } catch (Exception ignored) {
        }

        // 安装危险命令确认包装器（rootfs 内 rm/dd 等先弹确认，防止 agent/终端误删）
        // 失败必须中断：安全功能没装好，安装不算完成
        {
            String inst = readAsset("rootfs-confirm-install.sh");
            if (!inst.isEmpty()) {
                java.io.File instFile = new java.io.File(proot.getRootfsDir(), "root/install-confirm.sh");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(instFile)) {
                    fo.write(inst.getBytes(StandardCharsets.UTF_8));
                }
                runStep("安装危险命令确认包装器", 93,
                        "bash /root/install-confirm.sh && rm -f /root/install-confirm.sh");
            }
        }

        // 准备 Node headers（已缓存则跳过；node-gyp 现场下载会连 nodejs.org，国内被墙）
        runStep("准备 Node headers", 94,
                "if [ ! -f /root/.cache/node-gyp/24.19.0/include/node/node.h ]; then " +
                "mkdir -p /root/.cache/node-gyp/24.19.0 && cd /root/.cache/node-gyp/24.19.0 && " +
                "(curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL https://cdn.npmmirror.com/binaries/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz) && " +
                "tar -xzf headers.tar.gz --strip-components=1 && rm -f headers.tar.gz && touch .install-stamp; " +
                "else echo 'Node headers 已缓存，跳过下载'; fi");

        // 关键：pnpm 10/11 默认忽略依赖构建脚本（node-pty 的 node-gyp 编译会被跳过），
        // 必须把 node-pty 加入 onlyBuiltDependencies 白名单才会执行
        runStep("允许原生模块构建（node-pty）", 94,
                "cd /root/" + wd + " && " +
                "grep -q 'onlyBuiltDependencies' pnpm-workspace.yaml 2>/dev/null || " +
                "printf '\\nonlyBuiltDependencies:\\n  - node-pty\\n' >> pnpm-workspace.yaml");

        setProgress("安装依赖 pnpm install（npmmirror 源）", 95);
        try {
            // 注意：npm 11 的 `npm config set disturl` 会报 "not a valid npm option"，
            // 所以直接写 .npmrc 文件 + 环境变量（不经过 npm 配置校验）
            runStep("安装依赖 pnpm install", 95,
                    "cd /root/" + wd + " && " +
                    "printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc && " +
                    "pnpm install");
        } catch (Exception e) {
            throw new Exception(e.getMessage() + "\n\n[原生模块编译失败提示]\n"
                    + "1. Node headers 已预下载到 node-gyp 缓存（npmmirror 源），不依赖 nodejs.org\n"
                    + "2. 工具链已自动补装（gcc/g++/make/python3），可重试本步骤\n"
                    + "3. 若仍失败，可能是设备内存不足，可改选「预构建包」方式");
        }

        // 直接调用 node-gyp 编译 node-pty（绕开 npm/pnpm 的构建脚本管理：
        // npm 11 会因 disturl/unsafe-perm 等未知配置报错/警告）
        // node-gyp 使用预缓存的 headers（.install-stamp）+ gcc 编译，产出 build/Release/pty.node
        runStep("编译 node-pty（node-gyp）", 96,
                "cd /root/" + wd + " && " +
                "NP=$(ls -d node_modules/.pnpm/node-pty@*/node_modules/node-pty 2>/dev/null | head -1) && " +
                "cd \"$NP\" && " +
                "GYP=/usr/local/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js && " +
                "if [ ! -f \"$GYP\" ]; then GYP=$(find /usr/local/lib -maxdepth 8 -path '*/node-gyp/bin/node-gyp.js' 2>/dev/null | head -1); fi && " +
                "echo \"node-gyp 路径: $GYP\" && " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node && " +
                "node \"$GYP\" rebuild");

        // 验证 node-pty 编译产物确实生成了（否则启动 Web UI 时必炸）
        runStep("验证 pty.node 产物", 97,
                "cd /root/" + wd + " && " +
                "P=$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node 2>/dev/null | head -1); " +
                "if [ -z \"$P\" ]; then P=$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/prebuilds/linux-arm64/pty.node 2>/dev/null | head -1); fi; " +
                "if [ -z \"$P\" ]; then " +
                "echo 'ERROR: pty.node 未生成，node-pty 目录内容：'; " +
                "ls -la node_modules/.pnpm/node-pty@*/node_modules/node-pty/ 2>/dev/null; " +
                "ls -la node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/ 2>/dev/null; " +
                "exit 1; fi; " +
                "echo \"pty.node 已就绪: $P\"");

        setProgress("构建 deepseek-harness", 97);
        runStep("构建 pnpm run build", 97, "cd /root/" + wd + " && pnpm run build");

        runStep("写入 API key", 99,
                "cd /root/" + wd + " && printf 'DEEPSEEK_API_KEY=%s\\n' '" + apiKey + "' > .env");
        setProgress("deepseek-harness 构建完成", 99);
    }

    // ================= 下载源：测速 + 用户自选 =================

    /** 统一下载流程：测速 → 弹窗自选源 → 下载（失败自动 fallback 其他源） */
    private void downloadWithPick(int task, String[] urls, String what, File dest,
                                  int pBase, int pDiv) throws Exception {
        setProgress(what + "：测速中…（" + urls.length + " 个源并行测速）", pBase);
        long[] lat = proot.probeAll(urls, 6000);
        setProgress(what + "：请在弹窗中选择下载源", pBase + 1);
        String[] ordered = waitUserPick(task, urls, lat);

        boolean ok = false;
        String lastErr = "";
        for (String url : ordered) {
            try {
                proot.downloadRootfs(url, dest, p ->
                        setProgress(what + " " + p + "%（源：" + hostOf(url) + "）",
                                Math.min(99, pBase + 1 + p / pDiv)));
                ok = true;
                break;
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
        }
        if (!ok) {
            throw new Exception(what + " 下载失败: " + lastErr
                    + "\n\n可尝试：切换网络 / 开启代理");
        }
    }

    /** IO 线程阻塞等待用户在 UI 弹窗中选择下载源（2 分钟超时后自动选最快） */
    private String[] waitUserPick(int task, String[] urls, long[] lat) throws Exception {
        pendingTask = task;
        pendingUrls = urls;
        pendingLat = lat;
        sourceChoice = -1;
        awaitingSource = true;
        setState("请选择下载源（测速完成）", percent, "", "", true);
        synchronized (sourceLock) {
            long deadline = System.currentTimeMillis() + 120_000;
            while (awaitingSource) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                sourceLock.wait(remain);
            }
        }
        awaitingSource = false;

        // 确定首选：用户选择优先，否则自动选测速最快的
        int first = -1;
        if (sourceChoice >= 0 && sourceChoice < urls.length) {
            first = sourceChoice;
            prefs.edit().putString("src_" + task, urls[sourceChoice]).apply();
        } else {
            for (int i = 0; i < lat.length; i++) {
                if (lat[i] >= 0 && (first < 0 || lat[i] < lat[first])) first = i;
            }
            if (first < 0) first = 0;
        }
        String[] ordered = new String[urls.length];
        ordered[0] = urls[first];
        int k = 1;
        for (int i = 0; i < urls.length; i++) {
            if (i != first) ordered[k++] = urls[i];
        }
        return ordered;
    }

    /** UI 调用：用户已选择（index>=0 选中项；-1 自动选最快） */
    public void onSourceChosen(int index) {
        sourceChoice = index;
        awaitingSource = false;
        synchronized (sourceLock) {
            sourceLock.notifyAll();
        }
    }

    public boolean isAwaitingSourceChoice() { return awaitingSource; }

    /** 待选源的展示文案（名称 + 延迟；git:// 为直连源码构建选项） */
    public String[] getPendingSourceLabels() {
        if (pendingUrls == null || pendingLat == null) return new String[0];
        String[] labels = new String[pendingUrls.length];
        for (int i = 0; i < pendingUrls.length; i++) {
            String u = pendingUrls[i];
            if (u.startsWith("git://")) {
                labels[i] = "⚡ 直连 GitHub 源码构建（clone + 本地构建，无需预构建包）";
                continue;
            }
            long l = pendingLat[i];
            labels[i] = sourceLabel(u) + (l >= 0 ? "   延迟 " + l + "ms" : "   不可用 ✗");
        }
        return labels;
    }

    /** 弹窗默认选中项：上次选择 > 测速最快 */
    public int getPendingDefaultIndex() {
        String saved = pendingUrls != null
                ? prefs.getString("src_" + pendingTask, "") : "";
        for (int i = 0; pendingUrls != null && i < pendingUrls.length; i++) {
            if (pendingUrls[i].equals(saved)) return i;
        }
        int best = 0;
        for (int i = 1; pendingLat != null && i < pendingLat.length; i++) {
            if (pendingLat[i] >= 0 && (pendingLat[best] < 0 || pendingLat[i] < pendingLat[best])) best = i;
        }
        return best;
    }

    private static String sourceLabel(String url) {
        String h = hostOf(url);
        if (h.startsWith("cdn.npmmirror")) return "npmmirror CDN（" + h + "）";
        if (h.contains("npmmirror")) return "npmmirror（" + h + "）";
        if (h.contains("tuna")) return "清华镜像（" + h + "）";
        if (h.contains("aliyun")) return "阿里云镜像（" + h + "）";
        if (h.contains("huaweicloud")) return "华为云镜像（" + h + "）";
        if (h.contains("tencent")) return "腾讯云镜像（" + h + "）";
        if (h.contains("nju.edu")) return "南京大学镜像（" + h + "）";
        if (h.contains("hit.edu")) return "哈工大镜像（" + h + "）";
        if (h.contains("bfsu")) return "北外镜像（" + h + "）";
        if (h.contains("sjtu")) return "上海交大镜像（" + h + "）";
        if (h.contains("nodejs.org")) return "Node 官方（" + h + "）";
        if (h.contains("cdimage")) return "Ubuntu 官方（" + h + "）";
        if (h.contains("catbox")) return "catbox 网盘（" + h + "）";
        return h;
    }

    /**
     * 执行单个安装步骤：输出重定向到日志文件（避免大量输出走 proot 管道导致崩溃），
     * 失败时输出日志尾部以便定位。
     */
    private void runStep(String stage, int percent, String cmd) throws Exception {
        setProgress(stage, percent);
        String fullCmd = "(" + cmd + ") >/root/dsh-step.log 2>&1"
                + " || { echo '--- 日志尾部 ---'; tail -100 /root/dsh-step.log; exit 1; }";
        proot.execChecked(fullCmd);
    }

    // ================= 脚本与命令 =================
    public String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                appContext.getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildInstallScript() {
        String s = readAsset("install.sh");
        return s.replace("@@API_KEY@@", effectiveApiKey())
                .replace("@@PORT@@", getPort())
                .replace("@@MODEL@@", getModel())
                .replace("@@PERMISSION_MODE@@", getPermissionMode());
    }

    public String startWebCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("cd /root/").append(getWorkdir()).append(" && ")
          .append("export DSH_HOME=/root/.dsh && ")
          .append("export DEEPSEEK_API_KEY=\"").append(effectiveApiKey()).append("\" && ")
          .append("export DSH_PERMISSION_MODE=\"").append(getPermissionMode()).append("\" && ")
          // 危险命令确认：agent 在 rootfs 内的 rm/dd 等操作需用户确认
          // PATH 包装器 + BASH_ENV 函数级守卫（双保险）
          .append("export DSH_CONFIRM=1 && ")
          .append("export BASH_ENV=/root/dsh-guard.sh && ")
          // 日志重定向到 ~/dsh-web.log（与 Termux 模式统一，方便终端 tail 查看）
          .append("node apps/cli/lib/bin.js web > ~/dsh-web.log 2>&1");
        return sb.toString();
    }

    private String stopWebCommand() {
        return "pkill -f 'bin.js web' 2>/dev/null; echo stopped";
    }

    private String statusCommand() {
        return "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:" + getPort() + "/ 2>/dev/null || echo 000";
    }

    // ================= Termux 模式 =================
    public boolean isTermuxInstalled() { return TermuxBridge.isInstalled(appContext); }
    public void openTermuxInstall() { TermuxBridge.openInstall(appContext); }

    private String buildTermuxInstallScript() {
        String s = readAsset("install-termux.sh");
        return s.replace("@@API_KEY@@", effectiveApiKey())
                .replace("@@PERMISSION_MODE@@", getPermissionMode());
    }

    /** 通过 Termux 安装 deepseek-harness */
    public void installViaTermux() {
        setProgress("提交安装任务到 Termux", 5);
        try {
            TermuxBridge.runScript(appContext, buildTermuxInstallScript(), null);
            setState("", 30, "已提交到 Termux 执行，请切到 Termux 查看进度", "", false);
        } catch (Throwable e) {
            setState("", 0, "", "提交失败：" + describe(e), false);
        }
    }

    private String startWebTermuxCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=$HOME/dsh-bin:$PATH && ")
          .append("cd ~/").append(getWorkdir()).append(" && ")
          .append("export DEEPSEEK_API_KEY=\"").append(effectiveApiKey()).append("\" && ")
          .append("export DSH_PERMISSION_MODE=\"").append(getPermissionMode()).append("\" && ")
          .append("nohup node apps/cli/lib/bin.js web > ~/dsh-web.log 2>&1 & echo started");
        return sb.toString();
    }

    /** 通过 Termux 启动 Web UI */
    public void startWebViaTermux() {
        setProgress("正在启动 Web UI", 0);
        try {
            TermuxBridge.runScript(appContext, startWebTermuxCommand(), null);
            setState("", 100, "已提交启动，稍候在「启动」页打开预览", "", false);
        } catch (Throwable e) {
            setState("", 0, "", "启动失败：" + describe(e), false);
        }
    }

    public void stopWebViaTermux() {
        try {
            TermuxBridge.runScript(appContext,
                    "pkill -f 'bin.js web' 2>/dev/null; echo stopped", null);
        } catch (Throwable ignored) {
        }
    }

    // ================= 启动 / 停止 =================
    private static final String GUARD_VERSION = "8";

    /** 确保 rootfs 内危险命令确认包装器已部署（版本不匹配则强制重装，幂等） */
    public void ensureDangerGuard() {
        try {
            // 版本标记：旧版包装器/守卫不升级是之前漏拦截的根因，必须强制刷新
            String ver = proot.execAndRead("cat /root/dsh-bin/.version 2>/dev/null || echo 0");
            if (ver != null && ver.trim().equals(GUARD_VERSION)) return;
            String inst = readAsset("rootfs-confirm-install.sh");
            if (inst.isEmpty()) return;
            // 清掉旧版（含旧 dsh-bin/守卫脚本），避免残留旧包装器
            proot.execChecked("rm -rf /root/dsh-bin /root/dsh-guard.sh /root/dsh-confirm.sh && echo CLEARED");
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/install-confirm.sh");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                fo.write(inst.getBytes(StandardCharsets.UTF_8));
            }
            proot.execChecked("bash /root/install-confirm.sh && rm -f /root/install-confirm.sh");
        } catch (Exception ignored) {
            // 环境未安装等场景静默
        }
    }

    /** 给已构建的 bash 工具 lib 直接打补丁（强制每次执行前加载守卫，不依赖重新 build） */
    public void ensureBashGuardPatch() {
        try {
            String out = proot.execAndRead(
                    "cd /root/" + getWorkdir() + " && "
                    + "F=$(ls packages/shell/bash-local/lib/index.js 2>/dev/null | head -1); "
                    + "if [ -z \"$F\" ]; then echo LIB_MISSING; "
                    + "elif grep -q 'dsh-guard' \"$F\"; then echo LIB_ALREADY; "
                    + "else sed -i 's|command: request\\.command|command: `source /root/dsh-guard.sh 2>/dev/null; ${request.command}`|' \"$F\" "
                    + "&& grep -q 'dsh-guard' \"$F\" && echo LIB_PATCHED || echo LIB_PATCH_FAIL; fi");
        } catch (Exception ignored) {
        }
    }

    public void startWeb() {
        if (webProcess != null && webProcess.isAlive()) {
            return; // 已在运行，避免重复启动
        }
        IO.execute(() -> {
            try {
                setProgress("正在启动 Web UI", 0);
                proot.ensureRuntimeFiles();
                ensureDangerGuard(); // 安全包装器缺失则自动补装
                ensureBashGuardPatch(); // bash 工具 lib 强制加载守卫（不依赖重装）
                Process p = proot.execRootfs(startWebCommand());
                webProcess = p;
                // 阻塞读取输出，保持 proot+node 进程存活（后台 nohup 会被 --kill-on-exit 杀掉）
                String out = proot.drainOutput(p);
                // 输出已重定向到 ~/dsh-web.log，stdout 为空时从文件取尾部
                if (out == null || out.trim().isEmpty()) {
                    out = proot.execAndRead("tail -c 4000 ~/dsh-web.log 2>/dev/null");
                }
                String tail = out.length() > 500 ? out.substring(out.length() - 500) : out;
                setState("", 0, "", "Web UI 意外退出：\n" + tail, false);
            } catch (Throwable e) {
                setState("", 0, "", "启动出错：" + describe(e), false);
            }
        });
    }

    public void stopWeb() {
        IO.execute(() -> {
            try {
                Process p = webProcess;
                if (p != null) {
                    p.destroy();
                    webProcess = null;
                }
                proot.execAndRead(stopWebCommand());
                setState("", 0, "已停止后台服务", "", false);
            } catch (Exception ignored) {
            }
        });
    }

    public void checkStatus() {
        IO.execute(() -> {
            try {
                proot.ensureRuntimeFiles();
                String out = proot.execAndRead(statusCommand());
                setState("", 0, "状态码：" + out.trim(), "", false);
            } catch (Throwable e) {
                setState("", 0, "", "检查失败：" + describe(e), false);
            }
        });
    }

    // ================= 配置备份 / 重置（防死机无法恢复） =================

    private File rootfsFile(String rel) {
        return new File(proot.getRootfsDir(), rel);
    }

    /**
     * 备份关键配置到 App 私有目录（可通过 MT 管理器 data/files/backup 拷出）。
     * 备份内容：.env + 整个 .dsh（含 settings.yaml、对话记录等）。
     * 返回备份目录绝对路径；失败返回 null。
     */
    public String backupConfig() {
        try {
            File backupRoot = new File(appContext.getFilesDir(), "backup");
            File dir = new File(backupRoot, "config-" +
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(new java.util.Date()));
            dir.mkdirs();
            int n = 0;
            File env = rootfsFile("root/" + getWorkdir() + "/.env");
            if (env.isFile()) {
                copyFile(env, new File(dir, "env-" + getWorkdir() + ".txt"));
                n++;
            }
            File dsh = rootfsFile("root/.dsh");
            if (dsh.isDirectory()) {
                copyDir(dsh, new File(dir, "dsh"));
                n++;
            }
            return n > 0 ? dir.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 重置配置：删除 settings.yaml + .env（保留对话记录），并重写 .env。 */
    public String resetConfig() {
        try {
            boolean any = false;
            File settings = rootfsFile("root/.dsh/settings.yaml");
            if (settings.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                settings.delete();
                any = true;
            }
            File env = rootfsFile("root/" + getWorkdir() + "/.env");
            if (env.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                env.delete();
                any = true;
            }
            writeEnvFile();
            return any
                    ? "配置已重置，对话记录已保留\n（.env 已按当前配置重写）"
                    : "没有可重置的配置（.env 已重写）";
        } catch (Exception e) {
            return "重置失败：" + describe(e);
        }
    }

    /** 用当前 App 配置重写 rootfs 内的 .env */
    private void writeEnvFile() throws Exception {
        File env = rootfsFile("root/" + getWorkdir() + "/.env");
        if (env.getParentFile() != null) env.getParentFile().mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(env)) {
            out.write(("DEEPSEEK_API_KEY=" + effectiveApiKey() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void copyDir(File srcDir, File dstDir) throws Exception {
        File[] children = srcDir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                copyDir(c, new File(dstDir, c.getName()));
            } else {
                copyFile(c, new File(dstDir, c.getName()));
            }
        }
    }
}
