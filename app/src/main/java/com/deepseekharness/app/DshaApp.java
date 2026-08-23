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
