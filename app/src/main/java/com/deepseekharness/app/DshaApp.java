package com.deepseekharness.app;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** 跟随系统亮色 / 暗色。 */
public class DshaApp extends Application {

    private static final java.util.concurrent.atomic.AtomicBoolean CRASH_HOOK_INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 崩溃写入（公共入口，供 Activity 复用，避免每次 onCreate 重新包 handler 造成重复记录）。 */
    public static void writeCrashLog(Context ctx, Throwable t) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "crash.log");
            if (f.exists() && f.length() > 1024 * 1024) {
                // 轮转：超 1MB 移到 .prev（只保留最近一份，防止撑爆私有空间）
                java.io.File prev = new java.io.File(ctx.getFilesDir(), "crash.log.prev");
                //noinspection ResultOfMethodCallIgnored
                prev.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(prev);
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                fos.write(("\n===== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " =====\n"
                        + android.util.Log.getStackTraceString(t) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        mirrorCrashLog(ctx, t);
    }

    /** 把崩溃堆栈同时落到用户拿得到的两个地方。
     *
     *  起因：有用户反馈闪退，我们让他取 crash.log —— 但它在 App 私有目录里，
     *  没有 root 的文件管理器读不到，内置终端也读不到（容器只 bind 了
     *  /dev、/proc、/sys、/storage/emulated/0，App 私有目录不在其中，
     *  而 rootfs 本身就在私有目录里，proot 看不到它的上层）。
     *  结果就是「知道有日志，但拿不出来」，只能靠猜 —— 这一轮就卡在这里。
     *
     *  两个镜像各解决一种取法：
     *   · /sdcard/Documents/dshdata/crash.log —— 文件管理器直接可见、可分享
     *   · rootfs 内 /root/.dsh/crash.log      —— 内置终端 cat 就能看，
     *     容器里的 agent 也能自己读到并帮忙分析
     *
     *  两处都是**尽力而为**：写不进去（缺权限 / rootfs 还没解压）就算了，
     *  绝不能影响主记录，更不能在崩溃路径上再抛一次异常。 */
    private static void mirrorCrashLog(Context ctx, Throwable t) {
        String text = null;
        try {
            text = "\n===== " + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " =====\n" + android.util.Log.getStackTraceString(t) + "\n";
        } catch (Throwable ignored) {
            return;
        }
        // ① 公开目录：文件管理器可见（需要「所有文件访问」，没给就静默跳过）
        try {
            java.io.File pub = new java.io.File(
                    "/storage/emulated/0/Documents/dshdata");
            if (pub.isDirectory() || pub.mkdirs()) {
                appendCapped(new java.io.File(pub, "crash.log"), text);
            }
        } catch (Throwable ignored) {
        }
        // ② rootfs 内：内置终端与容器里的 agent 都能读
        try {
            java.io.File dsh = new java.io.File(ctx.getFilesDir(),
                    "linux/ubuntu/root/.dsh");
            if (dsh.isDirectory()) {
                appendCapped(new java.io.File(dsh, "crash.log"), text);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 追加写，超 512KB 先清空（镜像只为取证，不需要长期历史）。 */
    private static void appendCapped(java.io.File f, String text) throws java.io.IOException {
        if (f.isFile() && f.length() > 512 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
            fos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (CRASH_HOOK_INSTALLED.compareAndSet(false, true)) {
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
                writeCrashLog(this, t);
                if (prev != null) {
                    prev.uncaughtException(thread, t);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
        }
    }
}
