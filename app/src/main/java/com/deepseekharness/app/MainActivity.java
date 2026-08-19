package com.deepseekharness.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** 当前前台 Activity（HttpShellService 用它弹确认框）；null = 不在前台 */
    public static volatile MainActivity current = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 崩溃捕获：写日志到 files/crash.log，并继续交给系统默认 handler（保留 DropBox 崩溃报告）
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "crash.log");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                    fos.write(("\n===== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " =====\n"
                            + android.util.Log.getStackTraceString(t) + "\n").getBytes());
                }
            } catch (Exception ignored) {
            }
            // 转交系统默认 handler（否则系统 CrashReport/DropBox 收不到，只剩我们自己写的日志）
            if (prev != null) {
                prev.uncaughtException(thread, t);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });

        // 首次启动进入引导页
        SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        if (!prefs.getBoolean("welcomed", false)) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        if (!getIntent().getBooleanExtra("skip_extract", false)) {
            ProotBootstrap proot = new ProotBootstrap(this);
            if (!proot.isOfflineExtracted()) {
                startActivity(new Intent(this, ExtractActivity.class));
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);

        // 沉浸式全屏（隐藏状态栏 + 系统导航栏）
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemUI();

        requestPermissions();
        requestBatteryOptimization();
        maybeShowBackupReminder();
        maybeCheckUpdate();
        // 拉起设备桥服务（普通后台服务，无 FGS 崩溃风险）：
        // 3090 桥 + Shizuku + ADB 预热 + 配对弹窗监听/通知输码配对
        ensureDeviceBridge();

        BottomNavigationView nav = findViewById(R.id.bottom_nav);

        if (savedInstanceState == null) {
            switchFragment(new LaunchFragment());
        }

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment f;
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
            } else if (id == R.id.nav_install) {
                f = new InstallFragment();
            } else if (id == R.id.nav_config) {
                f = new ConfigFragment();
            } else if (id == R.id.nav_plugins) {
                f = new PluginFragment();
            } else {
                f = new TerminalFragment();
            }
            switchFragment(f);
            return true;
        });
        // 首页 = 启动页（与内测版一致）
        nav.setSelectedItemId(R.id.nav_launch);
    }

    private void switchFragment(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, f)
                .commit();
    }

    /** 显示/隐藏底部导航栏（WebView 全屏时隐藏） */
    public void setBottomNavVisible(boolean visible) {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) nav.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 自动申请所需权限：通知（前台服务需要）+ 电池优化白名单（保活） */
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    /** 拉起设备桥服务（普通后台服务，不 startForeground → 永不触发 FGS 杀进程崩溃） */
    private void ensureDeviceBridge() {
        if (isFinishing() || isDestroyed()) return;
        if (DeviceBridgeService.isRunning()) return;
        try {
            startService(new Intent(this, DeviceBridgeService.class));
            android.util.Log.i("DSHA", "device bridge started");
        } catch (Throwable e) {
            android.util.Log.e("DSHA", "start device bridge failed: " + e, e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        TaskNotifier.appInForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        current = null;
        TaskNotifier.appInForeground = false;
    }

    private void requestBatteryOptimization() {
        // 电池优化白名单（保活更稳，跳转系统设置让用户一键允许）
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    // ================= 检查更新 =================
    /** 后台静默检查 GitHub Releases；发现新版弹窗（取消 = 本次忽略该版本） */
    private void maybeCheckUpdate() {
        final SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        if (!prefs.getBoolean("check_update", true)) return;
        final String ignored = prefs.getString("ignored_version", "");
        final String current;
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return;
        }
        new Thread(() -> {
            String tag = UpdateChecker.checkLatestVersion();
            if (tag == null || tag.equals(ignored)) return;
            if (!UpdateChecker.isNewer(tag, current)) return;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("发现新版本 " + tag)
                    .setMessage("当前版本 v" + current + "\n是否前往下载？")
                    .setPositiveButton("更新", (d, w) -> AboutDialog.openBrowser(
                            this, "https://github.com/qiannianhuanxiang/DSHA/releases/latest"))
                    .setNegativeButton("取消", (d, w) -> prefs.edit()
                            .putString("ignored_version", tag).apply())
                    .show());
        }).start();
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ================= 备份提醒 =================
    // 提醒频率分级：默认每 6 次 → 勾选"少提醒我"依次升级为 15 / 30 / 100 次
    private static final int[] REMIND_INTERVALS = {6, 15, 30, 100};

    private void maybeShowBackupReminder() {
        SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        int count = prefs.getInt("launch_count", 0) + 1;
        int level = prefs.getInt("reminder_level", 0);
        int last = prefs.getInt("last_reminded", 0);
        prefs.edit().putInt("launch_count", count).apply();
        int interval = REMIND_INTERVALS[Math.min(level, REMIND_INTERVALS.length - 1)];
        if (count - last < interval) return;

        View box = LayoutInflater.from(this).inflate(R.layout.dialog_remind_backup, null);
        CheckBox lessCb = box.findViewById(R.id.remind_less);
        String[] labels = {
                "少提醒我（改为每 15 次提醒）",
                "少提醒我（改为每 30 次提醒）",
                "少提醒我（改为每 100 次提醒）"
        };
        if (level < labels.length) {
            lessCb.setText(labels[level]);
        } else {
            lessCb.setVisibility(View.GONE);
        }
        new AlertDialog.Builder(this)
                .setTitle("建议备份数据")
                .setMessage("已启动 " + count + " 次，建议把配置和对话记录导出到\n"
                        + "Download/DSHA 备份，防止意外丢失。")
                .setView(box)
                .setPositiveButton("立即备份", (d, w) -> {
                    confirmReminder(prefs, level, lessCb, count);
                    startBackup();
                })
                .setNegativeButton("取消", (d, w) ->
                        confirmReminder(prefs, level, lessCb, count))
                .show();
    }

    private void confirmReminder(SharedPreferences prefs, int level,
                                 CheckBox lessCb, int count) {
        if (lessCb != null && lessCb.isChecked()) {
            prefs.edit().putInt("reminder_level", level + 1).apply();
        }
        prefs.edit().putInt("last_reminded", count).apply();
    }

    /** 后台执行全量备份，完成后弹窗告知目录并可复制路径 */
    private void startBackup() {
        Toast.makeText(this, "正在备份，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String path = BackupManager.backupToExternal(this, HarnessController.get(this));
            runOnUiThread(() -> {
                if (path == null) {
                    Toast.makeText(this, "备份失败：环境可能未安装或空间不足", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("备份完成")
                        .setMessage("已导出到：\n" + path)
                        .setPositiveButton("复制路径", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("backup", path));
                                Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("好", null)
                        .show();
            });
        }).start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }
}
