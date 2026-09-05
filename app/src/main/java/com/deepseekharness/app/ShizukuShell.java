package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import rikka.shizuku.Shizuku;

/**
 * Shizuku shell 执行封装：通过双轨协同（execDirect 直通主力 + UserService 特权守护）
 * 在 root/shell 身份下执行设备命令，让助手（deepseek-harness agent）无需 root 即可操作设备。
 *
 * 加固点：
 *  - 双轨协同：日常命令走轻量高效的原生进程管道 execDirect，带 PATH 兜底、stdin 即时关闭与 30s 防卡死强退保护（带 rp.destroy 彻底终结远程进程与本地线程泄漏）；
 *  - UserService 守护单例化：daemon(true) + 固化 tag("dsha_shell")，根除生命周期波动导致的多开泄漏；
 *  - 协同 AIDL destroy(16777114/16777115) 契约，确保服务端回收时旧进程能安全自杀退出；
 *  - bindUserService 异常不再静默吞掉，打日志；
 *  - 补 onBindingDied / onServiceDisconnected 回调：状态归零 + 延迟自动重绑；
 *  - 监听 Shizuku binder 重启后自动重绑；
 *  - 授权成功回调内自动触发绑定。
 */
public final class ShizukuShell {

    private static final String TAG = "ShizukuShell";

    private static volatile Context appCtx;
    private static volatile IShellService shellService;
    private static volatile boolean binding = false;
    private static volatile long bindingStartedAt = 0L;
    private static volatile boolean binderListenerAttached = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile long lastRetryAt = 0L;
    private static final long RETRY_DELAY_MS = 4000L;
    private static final long RETRY_COOLDOWN_MS = 10000L;

    private ShizukuShell() {
    }

    /** 初始化：缓存 Application context 并挂 Shizuku binder 重启监听（幂等）。 */
    public static void init(Context ctx) {
        if (appCtx == null && ctx != null) {
            appCtx = ctx.getApplicationContext();
        }
        attachBinderListener();
    }

    /** Shizuku 服务是否可用（binder 存活） */
    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 是否已获得 Shizuku 权限 */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** UserService 是否已就绪（3090 桥可用的判断依据） */
    public static boolean isReady() {
        return shellService != null;
    }

    /** 供 3090 /status 端点的诊断字符串 */
    public static String status() {
        String perm;
        try {
            int p = Shizuku.checkSelfPermission();
            perm = p == PackageManager.PERMISSION_GRANTED ? "granted" : "denied(" + p + ")";
        } catch (Throwable e) {
            perm = "err:" + e.getClass().getSimpleName();
        }
        return "binder=" + isAvailable()
                + ",permission=" + perm
                + ",bound=" + (shellService != null)
                + ",binding=" + binding;
    }

    /** 请求 Shizuku 权限；授权成功回调自动触发绑定（联动修复）。
     *  一次性语义：add 监听 → requestPermission → 回调后立即 remove。
     *  （Shizuku 13.x 没有带 listener 的 requestPermission 重载，只有 add/remove 配对；
     *   旧实现 add 后从不 remove → 每次请求都永久累积一个监听器 → 泄漏 + 重复回调） */
    public static void requestPermission(Shizuku.OnRequestPermissionResultListener listener) {
        final Shizuku.OnRequestPermissionResultListener[] holder = new Shizuku.OnRequestPermissionResultListener[1];
        holder[0] = (code, result) -> {
            // 一次性：先移除自身（防泄漏）
            try {
                Shizuku.removeRequestPermissionResultListener(holder[0]);
            } catch (Throwable ignored) {
            }
            try {
                if (listener != null) listener.onRequestPermissionResult(code, result);
            } finally {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    ensureBound(appCtx);
                }
            }
        };
        try {
            Shizuku.addRequestPermissionResultListener(holder[0]);
            Shizuku.requestPermission(9527);
        } catch (Throwable ignored) {
            // 添加/请求失败：立刻移除，避免残留
            try {
                Shizuku.removeRequestPermissionResultListener(holder[0]);
            } catch (Throwable ignored2) {
            }
        }
    }

    /** 绑定 UserService（进程由 Shizuku 以 root/shell 身份托管，作为特权单例守护） */
    public static void ensureBound(Context ctx) {
        init(ctx);
        attachBinderListener();
        long now = System.currentTimeMillis();
        if ((binding && now - bindingStartedAt < 6000L) || shellService != null) return;
        if (appCtx == null) return;
        if (!hasPermission()) {
            Log.w(TAG, "ensureBound skip: no Shizuku permission yet");
            return;
        }
        binding = true;
        bindingStartedAt = now;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
                    .daemon(true)
                    .tag("dsha_shell")
                    .processNameSuffix("shizuku")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    shellService = IShellService.Stub.asInterface(binder);
                    binding = false;
                    Log.i(TAG, "UserService connected: " + name);
                    try {
                        binder.linkToDeath(() -> {
                            Log.w(TAG, "UserService binder died");
                            shellService = null;
                            retryBindSoon();
                        }, 0);
                    } catch (Throwable ignored2) {
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.w(TAG, "UserService disconnected");
                    shellService = null;
                    binding = false;
                    retryBindSoon();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    Log.w(TAG, "UserService binding died");
                    shellService = null;
                    binding = false;
                    retryBindSoon();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    Log.e(TAG, "UserService null binding (ShellService 未实现正确?)");
                    binding = false;
                }
            });
        } catch (Throwable e) {
            // 根因可见：manifest 未注册 / 组件缺失 / Shizuku 异常等都在这暴露
            Log.e(TAG, "bindUserService failed: " + e, e);
            binding = false;
        }
    }

    /**
     * 延迟重绑（绑定断开 / binding died / Shizuku binder 重启后调用）。
     * 带 10s 冷却，避免连接频繁断开时重绑风暴。
     */
    private static void retryBindSoon() {
        long now = System.currentTimeMillis();
        if (now - lastRetryAt < RETRY_COOLDOWN_MS) return;
        lastRetryAt = now;
        mainHandler.postDelayed(() -> ensureBound(appCtx), RETRY_DELAY_MS);
    }

    /** 挂 Shizuku 服务重启监听：服务被回收后 binder 恢复时自动重绑（幂等） */
    private static void attachBinderListener() {
        if (binderListenerAttached || appCtx == null) return;
        try {
            if (Shizuku.isPreV11()) {
                Shizuku.addBinderReceivedListener(() -> retryBindSoon());
            } else {
                Shizuku.addBinderReceivedListenerSticky(() -> retryBindSoon());
            }
            binderListenerAttached = true;
            Log.i(TAG, "binder received listener attached");
        } catch (Throwable e) {
            Log.w(TAG, "attachBinderListener failed", e);
        }
    }

    /**
     * 通过 Shizuku 底层直接执行命令（轻量原生管道，0MB 常驻内存，毫秒级直达）。
     * 内置 PATH 环境变量兜底、stdin 立即关闭、256KB 内存上限与 30 秒硬超时强退保护（带 rp.destroy 斩除远程进程）。
     */
    private static String execDirect(String cmd) throws Exception {
        IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");

        moe.shizuku.server.IShizukuService shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder);

        // 环境变量兜底 + 错误流合并
        String wrappedCmd = "export PATH=$PATH:/system/bin:/system/xbin:/vendor/bin; " + cmd + " 2>&1";
        moe.shizuku.server.IRemoteProcess rp = shizukuService.newProcess(new String[]{"sh", "-c", wrappedCmd}, null, null);

        // 1. 立即关闭标准输入，防止交互式命令或需 EOF 的脚本无休止死等
        try {
            ParcelFileDescriptor outPfd = rp.getOutputStream();
            if (outPfd != null) {
                new ParcelFileDescriptor.AutoCloseOutputStream(outPfd).close();
            }
        } catch (Throwable ignored) {}

        ParcelFileDescriptor inPfd = rp.getInputStream();
        if (inPfd == null) {
            try { rp.destroy(); } catch (Throwable ignored) {}
            throw new IllegalStateException("Remote process InputStream is null");
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        final int MAX_BYTES = 256 * 1024;

        // 异步读取与等待，施加 30s 严格超时，绝不允许无限期挂死
        FutureTask<Integer> task = new FutureTask<>(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(inPfd)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bos.size() < MAX_BYTES) {
                        bos.write(buf, 0, Math.min(n, MAX_BYTES - bos.size()));
                    }
                }
            }
            return rp.waitFor();
        });

        Thread workerThread = new Thread(task, "dsha-shizuku-exec");
        workerThread.start();

        try {
            int code = task.get(30, TimeUnit.SECONDS);
            return bos.toString("UTF-8") + "\n[EXIT=" + code + "]";
        } catch (TimeoutException te) {
            task.cancel(true);
            // 2. 核心补齐：超时强制杀死远程进程，打破子线程 Binder 阻塞，彻底解决远程孤儿与本地线程泄漏
            try { rp.destroy(); } catch (Throwable ignored) {}
            try { inPfd.close(); } catch (Throwable ignored) {}
            return bos.toString("UTF-8") + "\n[EXIT=timeout] 命令执行超时(30s)已主动强退";
        } catch (Throwable e) {
            task.cancel(true);
            try { rp.destroy(); } catch (Throwable ignored) {}
            try { inPfd.close(); } catch (Throwable ignored) {}
            throw e;
        }
    }

    /**
     * 执行设备 shell 命令：双轨协同。
     * 第一优先级走极速、零常驻、防卡死的 execDirect 原生管道；
     * 若未就绪或出现异常，顺位无缝流转至 UserService 特权守护进程兜底。
     */
    public static String exec(String cmd) {
        if (!hasPermission()) {
            return "[NO_SHIZUKU_PERMISSION]";
        }

        // 1. 优先走轻快直达的原生管道
        try {
            return execDirect(cmd);
        } catch (Throwable t) {
            Log.w(TAG, "execDirect 管道未通，顺位转由 UserService 执行: " + t.getMessage());
        }

        // 2. 顺位走 UserService 特权单例兜底
        IShellService s = shellService;
        if (s != null) {
            try {
                return s.exec(cmd);
            } catch (Throwable e) {
                Log.e(TAG, "UserService 也未执行成功: " + e.getMessage());
            }
        } else {
            ensureBound(appCtx);
        }

        return "[SHIZUKU_SERVICE_NOT_READY]";
    }

}
