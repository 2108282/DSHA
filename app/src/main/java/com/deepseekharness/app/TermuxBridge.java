package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/**
 * Termux 桥接：通过 RUN_COMMAND Intent 在 Termux 内执行命令。
 * 普通 App 的 SELinux 上下文不允许 proot 所需的 ptrace，因此一体式 proot 方案
 * 在非 root 环境下不可行；改由 Termux（具备正确 SELinux 上下文）承载 Linux 环境。
 */
public final class TermuxBridge {

    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private TermuxBridge() {}

    /** Termux 是否已安装 */
    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** 引导安装 Termux（F-Droid 优先，回退 GitHub） */
    public static void openInstall(Context ctx) {
        String[] urls = {
                "https://f-droid.org/repo/com.termux_118.apk",
                "https://github.com/termux/termux-app/releases/latest"
        };
        for (String url : urls) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    /** 打开 Termux 主界面 */
    public static void openApp(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 在 Termux 内后台执行一段 bash 脚本。
     * @param script  要执行的脚本内容（作为 bash -c 的参数）
     * @param workdir Termux 内的工作目录（可为 null）
     */
    public static void runScript(Context ctx, String script, String workdir) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE));
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", script});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                workdir != null ? workdir : TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 1);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法启动 Termux 命令服务: " + e.getMessage());
        }
    }
}
