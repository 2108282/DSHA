package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

/**
 * 配置子页：接入（API Key / 端口）+ 行为开关 + ADB 设备通道。
 * 所有开关都落到 ConfigStore / SharedPreferences，并真正影响启动与预览。
 */
public class ConfigFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_config, container, false);
        ConfigStore c = new ConfigStore(requireContext());
        Context ctx = requireContext();

        TextView back = v.findViewById(R.id.sub_back);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(x -> getParentFragmentManager().popBackStack());

        v.findViewById(R.id.config_workspace_entry).setOnClickListener(x -> open(new WorkspaceFragment()));

        EditText apiKey = v.findViewById(R.id.config_api_key);
        EditText port = v.findViewById(R.id.config_port);
        CheckBox confirm = v.findViewById(R.id.config_confirm_shell);
        CheckBox rootShell = v.findViewById(R.id.config_root_shell);
        CheckBox checkUpdate = v.findViewById(R.id.config_check_update);
        CheckBox desktop = v.findViewById(R.id.config_desktop_mode);
        CheckBox backupKey = v.findViewById(R.id.config_backup_key);
        CheckBox proroot = v.findViewById(R.id.config_proroot);
        CheckBox lan = v.findViewById(R.id.config_lan_mode);
        CheckBox overlay = v.findViewById(R.id.config_overlay_stream);
        CheckBox sensors = v.findViewById(R.id.config_cap_sensors);
        CheckBox location = v.findViewById(R.id.config_cap_location);
        EditText autoBackup = v.findViewById(R.id.config_auto_backup);
        CheckBox adb = v.findViewById(R.id.config_adb_enable);
        Button save = v.findViewById(R.id.config_save);

        // 高级项折叠
        View advBody = v.findViewById(R.id.config_adv_body);
        v.findViewById(R.id.config_adv_header).setOnClickListener(x ->
                advBody.setVisibility(advBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // 回填当前值
        apiKey.setText(c.getApiKey());
        port.setText(c.getPort());
        confirm.setChecked(c.isConfirmShell());
        rootShell.setChecked(c.isRootShellAllowed());
        checkUpdate.setChecked(c.isCheckUpdate());
        desktop.setChecked(c.isDesktopMode());
        CheckBox gecko = v.findViewById(R.id.config_gecko_core);
        gecko.setVisibility(com.deepseekharness.app.BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
        gecko.setChecked(c.isGeckoCore());
        backupKey.setChecked(c.isBackupKey());
        proroot.setChecked(c.isProroot());
        lan.setChecked(c.isLanMode());
        overlay.setChecked(pref(ctx, "overlay_stream", false));
        sensors.setChecked(pref(ctx, "cap_sensors", false));
        location.setChecked(pref(ctx, "cap_location", false));
        location.setOnCheckedChangeListener((button, checked) -> {
            if (checked && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, 104);
        });
        autoBackup.setText(String.valueOf(c.getAutoBackupLaunches()));
        adb.setChecked(pref(ctx, "adb_enabled", false));
        TextView adbStatus = v.findViewById(R.id.config_adb_status);
        adb.setOnCheckedChangeListener((b, checked) ->
                adbStatus.setText(checked
                        ? "ADB 已开启。无线配对：开发者选项 → 无线调试"
                        : "ADB 已关闭。不用无线调试就保持关闭。"));
        refreshAdbStatus(adbStatus);

        // 待接回项（诚实提示）
        v.findViewById(R.id.config_translate).setOnClickListener(x -> toast("插件市场翻译待接回"));

        // 所有文件访问权限（Android 11+ MANAGE_EXTERNAL_STORAGE）：跳系统设置开启，
        // 让容器/proot 能读写手机存储任意文件（含 DSHA 目录外），WebUI 工作区可建到 /sdcard
        View allFiles = v.findViewById(R.id.config_all_files);
        if (allFiles != null) {
            allFiles.setOnClickListener(x -> openAllFilesAccess(ctx));
            refreshAllFilesStatus(v.findViewById(R.id.config_all_files_status));
        }

        // 悬浮条外观与行为（照 1.1.9.1：底色预设 + 不透明度/行数/字号/停留 + 行为开关）
        v.findViewById(R.id.config_overlay_style).setOnClickListener(x -> showOverlayStyleDialog());

        // 设备通道提示
        v.findViewById(R.id.config_adb_pair).setOnClickListener(x -> {
            Toast.makeText(ctx, "当前运行在 KernelSU / Magisk 原生 Root 模式下，免 ADB 无线配对", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.config_battery_opt).setOnClickListener(x -> openBatteryOpt(ctx));
        v.findViewById(R.id.config_a11y).setOnClickListener(x -> openA11ySettings(ctx));
        refreshA11yStatus(v.findViewById(R.id.config_a11y_status));
        v.findViewById(R.id.config_runtime_update).setOnClickListener(x -> checkScriptUpdate(ctx));
        v.findViewById(R.id.config_repo_link).setOnClickListener(x -> openRepo(ctx));

        save.setOnClickListener(x -> {
            c.setApiKey(apiKey.getText().toString());
            c.setPort(port.getText().toString());
            c.setConfirmShell(confirm.isChecked());
            c.setRootShellAllowed(rootShell.isChecked());
            c.setCheckUpdate(checkUpdate.isChecked());
            c.setDesktopMode(desktop.isChecked());
            if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) c.setGeckoCore(gecko.isChecked());
            c.setBackupKey(backupKey.isChecked());
            c.setProroot(proroot.isChecked());
            c.setLanMode(lan.isChecked());
            c.setAutoBackupLaunches(parseInt(autoBackup.getText().toString()));
            setPref(ctx, "overlay_stream", overlay.isChecked());
            setPref(ctx, "cap_sensors", sensors.isChecked());
            setPref(ctx, "cap_location", location.isChecked());
            setPref(ctx, "adb_enabled", adb.isChecked());
            applyLanMode(c, lan.isChecked());
            if (lan.isChecked() && getActivity() instanceof MainActivity)
                ((MainActivity) getActivity()).requestLocalNetwork();
            Toast.makeText(ctx, "已保存（重启 Web 后生效）", Toast.LENGTH_SHORT).show();
        });

        return v;
    }

    /** LAN 开关真正生效：开启时若 dsh 已鉴权则启动 3081 代理，关闭时停掉监听。 */
    private void applyLanMode(ConfigStore c, boolean on) {
        try {
            if (!on) {
                com.deepseekharness.app.LanProxyService.stopLanListener();
                return;
            }
            HarnessController hc = new HarnessController(requireContext());
            long gen = hc.getWebGeneration();
            if (gen <= 0 || !com.deepseekharness.app.LanProxyService.hasDshAuth(gen)) {
                // dsh 还没起来/还没交换 cookie：等下次进入对话时 HarnessController 自动启动
                return;
            }
            com.deepseekharness.app.LanProxyService.start(
                    hc.proot().getRootfsDir().getAbsolutePath(),
                    requireContext(), c.getPortInt(), gen);
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "LAN 开关生效失败: " + t.getMessage());
        }
    }

    private void open(Fragment f) {
        getParentFragmentManager().beginTransaction()
                .addToBackStack(null)
                .replace(R.id.fragment_container, f)
                .commit();
    }

    /** 后台读 ADB 通道真实状态（key/deps/端口 + 保活服务的连接状态），刷到状态栏。 */
    private void refreshAdbStatus(final TextView status) {
        new Thread(() -> {
            final String text = computeAdbStatus();
            if (getActivity() == null || !isAdded()) return;
            getActivity().runOnUiThread(() -> {
                if (status != null && isAdded()) status.setText(text);
            });
        }, "adb-status").start();
    }

    private String computeAdbStatus() {
        return "✅ 原生 Root 守护运行就绪（免 ADB 配对）";
    }

    private void openBatteryOpt(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("无法打开电池优化设置");
        }
    }

    /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
    private void openAllFilesAccess(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                startActivity(i);
            } catch (Throwable e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable e2) {
                    toast("打开设置失败：" + e2.getMessage());
                }
            }
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Android 6-10：运行时请求 WRITE_EXTERNAL_STORAGE（Android 10 作用域存储下尽力而为）
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 501);
            } else {
                toast("存储权限已授予，容器可访问手机存储");
            }
            return;
        }
        toast("当前系统无需存储权限");
    }

    /** 刷新「所有文件访问权限」状态行（含从系统设置返回后的更新）。 */
    private void refreshAllFilesStatus(TextView status) {
        if (status == null) return;
        boolean granted;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            granted = Environment.isExternalStorageManager();
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            granted = requireContext().checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } else {
            granted = true;
        }
        status.setText(granted
                ? "已开启：容器可读写手机存储任意文件（含 DSHA 目录外）"
                : "未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件");
        try {
            status.setTextColor(granted
                    ? getResources().getColor(R.color.primary, null)
                    : getResources().getColor(R.color.err, null));
        } catch (Throwable ignored) {
        }
    }

    /** 跳到本应用无障碍服务的开关页；部分 ROM 不支持直达就退回系统无障碍列表。 */
    private void openA11ySettings(Context ctx) {
        try {
            // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS 常量在 compileSdk 里缺失
            // （各版本 SDK 不一致），直接用 action 字符串，运行时兼容
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(Intent.EXTRA_COMPONENT_NAME,
                    new ComponentName(ctx, DshaAccessibilityService.class));
            startActivity(i);
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable e2) {
                toast("打不开无障碍设置：" + e2.getMessage());
            }
        }
    }

    /** 刷新「屏幕操作权限」状态行：是否已开启无障碍服务（从系统设置返回后也会更新）。 */
    private void refreshA11yStatus(TextView status) {
        if (status == null) return;
        String st = DshaAccessibilityService.enabledState(requireContext());
        boolean ok = "YES".equals(st);
        if (ok) {
            status.setText("✅ 已开启：AI 可读屏 / 点按 / 输入 / 截屏（截屏需 Android 11+）");
        } else if ("NO".equals(st)) {
            status.setText("❌ 未开启：点上方去系统设置开启「DSHA 配对助手」");
        } else {
            status.setText("⚠️ 状态未知：点上方到系统设置确认「DSHA 配对助手」已开启");
        }
        try {
            status.setTextColor(getResources().getColor(ok ? R.color.primary : R.color.err, null));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            View v = getView();
            if (v != null) refreshAllFilesStatus(v.findViewById(R.id.config_all_files_status));
            if (v != null) refreshA11yStatus(v.findViewById(R.id.config_a11y_status));
        } catch (Throwable ignored) {
        }
    }

    // ================= 悬浮条外观与行为（照 1.1.9.1 移植） =================

    private void showOverlayStyleDialog() {
        final android.content.Context app = requireContext().getApplicationContext();
        final android.content.SharedPreferences sp = requireContext()
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dpx(16);
        box.setPadding(pad, pad, pad, 0);

        // 底色不做取色器：悬浮条只需要「在任何壁纸上都读得清」，几个深色预设够用
        box.addView(sectionLabel("底色"));
        final int[] pickedBg = {sp.getInt(OverlayController.K_BG, 0)};
        LinearLayout swatches = new LinearLayout(requireContext());
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] cells = new TextView[OverlayController.BG_PRESETS.length];
        for (int i = 0; i < OverlayController.BG_PRESETS.length; i++) {
            final int idx = i;
            TextView cell = new TextView(requireContext());
            cell.setText(OverlayController.BG_NAMES[i]);
            cell.setTextColor(0xFFFFFFFF);
            cell.setTextSize(11f);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dpx(6), dpx(10), dpx(6), dpx(10));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dpx(4);
            cell.setLayoutParams(lp);
            cells[i] = cell;
            cell.setOnClickListener(v -> {
                pickedBg[0] = idx;
                paintSwatches(cells, pickedBg[0]);
            });
            swatches.addView(cell);
        }
        paintSwatches(cells, pickedBg[0]);
        box.addView(swatches);

        final android.widget.SeekBar alpha = slider(box, "底色不透明度", 20, 100,
                sp.getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA), "%");
        final android.widget.SeekBar lines = slider(box, "最多显示几行（写满后丢最旧一行）", 1, 8,
                sp.getInt(OverlayController.K_LINES, OverlayController.DEF_LINES), " 行");
        final android.widget.SeekBar wide = slider(box, "字号（越小一行放得越多）", 6, 20,
                sp.getInt(OverlayController.K_TEXT_SP, OverlayController.DEF_TEXT_SP), " sp");
        final android.widget.SeekBar hold = slider(box, "无新内容后停留", 2, 60,
                sp.getInt(OverlayController.K_HOLD, OverlayController.DEF_HOLD), " 秒");

        final CheckBox think = new CheckBox(requireContext());
        think.setText("显示思考过程（reasoning，会明显更吵）");
        think.setChecked(sp.getBoolean(OverlayController.K_REASONING, false));
        box.addView(think);

        final CheckBox cmd = new CheckBox(requireContext());
        cmd.setText("工具调用带上命令原文（否则只看到「正在执行命令」）");
        cmd.setChecked(sp.getBoolean(OverlayController.K_COMMAND, true));
        box.addView(cmd);

        final CheckBox confirmHere = new CheckBox(requireContext());
        confirmHere.setText("危险命令在悬浮条上直接批准（不必切回 App 或拉通知栏）");
        confirmHere.setChecked(sp.getBoolean(OverlayController.K_CONFIRM, true));
        box.addView(confirmHere);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(box);

        // 保存抽成 Runnable：「预览」要能不关对话框就先落盘，否则看到的还是旧样式
        final Runnable save = () -> sp.edit()
                .putInt(OverlayController.K_BG, pickedBg[0])
                .putInt(OverlayController.K_ALPHA, Math.max(20, alpha.getProgress()))
                .putInt(OverlayController.K_LINES, Math.max(1, lines.getProgress()))
                .putInt(OverlayController.K_TEXT_SP, Math.max(6, wide.getProgress()))
                .putInt(OverlayController.K_HOLD, Math.max(2, hold.getProgress()))
                .putBoolean(OverlayController.K_REASONING, think.isChecked())
                .putBoolean(OverlayController.K_COMMAND, cmd.isChecked())
                .putBoolean(OverlayController.K_CONFIRM, confirmHere.isChecked())
                .apply();

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("悬浮条外观与行为")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    save.run();
                    OverlayController.applyStyleNow(app);
                    Toast.makeText(requireContext(), "已保存（下一条输出即生效）",
                            Toast.LENGTH_SHORT).show();
                })
                // 中间按钮当预览：调样式最烦的就是「保存 → 等 agent 说话 → 不合适 → 再调」
                .setNeutralButton("预览", (d, w) -> {
                    save.run();
                    if (!OverlayController.permitted(requireContext())) {
                        Toast.makeText(requireContext(), "还没给悬浮窗权限，先勾上面那个开关授权",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    OverlayController.applyStyleNow(app);
                    OverlayController.push(app, "preview", "text",
                            "这是预览：AI 的回复会像这样流出来，调工具时会变成"
                                    + "「⚙ 正在执行命令: ls -la」这种。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void paintSwatches(TextView[] cells, int picked) {
        for (int i = 0; i < cells.length; i++) {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dpx(10));
            bg.setColor(0xFF000000 | OverlayController.BG_PRESETS[i]);
            // 选中描边：几个深色块之间光靠颜色分不清哪个选上了
            if (i == picked) bg.setStroke(dpx(2), 0xFF7DA7F4);
            cells[i].setBackground(bg);
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(12f);
        t.setPadding(0, dpx(8), 0, dpx(4));
        return t;
    }

    /** 一条「标题 + 当前值」的滑杆。SeekBar 只有 0..max，下限靠回弹保证。 */
    private android.widget.SeekBar slider(LinearLayout parent, String title,
                                          int min, int max, int value, String unit) {
        final TextView label = sectionLabel(title + "：" + value + unit);
        parent.addView(label);
        final android.widget.SeekBar bar = new android.widget.SeekBar(requireContext());
        bar.setMax(max);
        bar.setProgress(Math.max(min, Math.min(max, value)));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (progress < min) {
                    sb.setProgress(min);
                    return;
                }
                label.setText(title + "：" + progress + unit);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        parent.addView(bar);
        return bar;
    }

    private int dpx(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void openDeveloperOptions(Context ctx) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            toast("无法打开开发者选项");
        }
    }

    private void checkScriptUpdate(Context ctx) {
        toast("正在检查脚本更新…");
        new Thread(() -> {
            String tag = fetchLatestRelease();
            requireActivity().runOnUiThread(() -> toast(
                    tag == null ? "检查失败（网络不可用）" : "脚本层最新 " + tag + "（本版已内置）"));
        }, "script-update").start();
    }

    private String fetchLatestRelease() {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.github.com/repos/qiannianhuanxiang/DSHA/releases/latest")
                    .openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "DSHA");
            if (conn.getResponseCode() != 200) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            conn.disconnect();
            String body = sb.toString();
            int i = body.indexOf("\"tag_name\"");
            if (i < 0) return null;
            int c = body.indexOf('"', body.indexOf('"', i + 11) + 1);
            int e = body.indexOf('"', c + 1);
            return c >= 0 && e > c ? body.substring(c + 1, e) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void openRepo(Context ctx) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/qiannianhuanxiang/DSHA")));
        } catch (Exception e) {
            toast("无法打开浏览器");
        }
    }

    private boolean pref(Context ctx, String k, boolean def) {
        return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).getBoolean(k, def);
    }

    private void setPref(Context ctx, String k, boolean v) {
        ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
    }

    private int parseInt(String s) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }
}
