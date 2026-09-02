package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Shizuku shell 执行封装：通过 UserService 在 root/shell 身份下执行设备命令，
 * 让助手（deepseek-harness agent）无需 root 即可操作设备。
 *
 * 加固点：
 *  - bindUserService 异常不再静默吞掉，打日志（配 manifest 缺失排查根因）；
 *  - 补 onBindingDied / onServiceDisconnected 回调：状态归零 + 延迟自动重绑；
 *  - 监听 Shizuku binder 重启（服务被回收）后自动重绑；
 *  - 授权成功回调内自动触发绑定（解决"授权后桥仍不就绪"的时机 bug）；
 *  - status() 诊断字符串供 3090 /status 端点与开发者排查。
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

    /** 绑定 UserService（进程由 Shizuku 以 root/shell 身份托管） */
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
                    .daemon(false)
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


    /** 通过 Shizuku 底层直接执行命令（绕过所有不稳定的 UserService 握手） */
    private static String execDirect(String cmd) throws Exception {
        IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");
        
        moe.shizuku.server.IShizukuService shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
        
        // 关键点：使用 2>&1 自动把错误流合并到标准输出，避免分别读取两条管道导致死锁
        moe.shizuku.server.IRemoteProcess rp = shizukuService.newProcess(new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
        
        android.os.ParcelFileDescriptor pfd = rp.getInputStream();
        if (pfd == null) throw new IllegalStateException("Remote process InputStream is null");
        
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        
        rp.waitFor();
        int code = rp.exitValue();
        return bos.toString("UTF-8") + "\n[EXIT=" + code + "]";
    }

    /** 通过 UserService 执行 shell 命令并返回输出 */
    public static String exec(String cmd) {
        if (!hasPermission()) {
            return "[NO_SHIZUKU_PERMISSION]";
        }
        IShellService s = shellService;
        if (s == null) {
            ensureBound(appCtx);
            // 如果 UserService 还没好，直接走底层兜底，不阻断请求！
            try {
                return execDirect(cmd);
            } catch (Throwable e) {
                Log.w(TAG, "Direct newProcess failed, status=" + status(), e);
                return "[SHIZUKU_SERVICE_NOT_READY]";
            }
        }
        try {
            return s.exec(cmd);
        } catch (Throwable e) {
            try {
                return execDirect(cmd);
            } catch (Throwable ex) {
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

}
