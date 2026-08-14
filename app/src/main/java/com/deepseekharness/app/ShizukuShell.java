package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/**
 * Shizuku shell 执行封装：通过 UserService 在 root/shell 身份下执行设备命令，
 * 让助手（deepseek-harness agent）无需 root 即可操作设备。
 */
public final class ShizukuShell {

    private static volatile IShellService shellService;
    private static volatile boolean binding = false;

    private ShizukuShell() {}

    /** Shizuku 服务是否可用 */
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

    /** 请求 Shizuku 权限（结果通过 listener 回调） */
    public static void requestPermission(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(9527);
        } catch (Throwable ignored) {
        }
    }

    /** 绑定 UserService（进程由 Shizuku 以 root/shell 身份托管） */
    public static void ensureBound(Context ctx) {
        if (binding || shellService != null) return;
        if (!hasPermission()) return;
        binding = true;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(ctx, ShellService.class))
                    .daemon(false)
                    .version(1);
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    shellService = IShellService.Stub.asInterface(binder);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    shellService = null;
                    binding = false;
                }
            });
        } catch (Throwable ignored) {
            binding = false;
        }
    }

    /** 通过 UserService 执行 shell 命令并返回输出 */
    public static String exec(String cmd) {
        if (!hasPermission()) {
            return "[NO_SHIZUKU_PERMISSION]";
        }
        IShellService s = shellService;
        if (s == null) {
            return "[SHIZUKU_SERVICE_NOT_READY]";
        }
        try {
            return s.exec(cmd);
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
