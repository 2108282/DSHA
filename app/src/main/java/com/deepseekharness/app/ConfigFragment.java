package com.deepseekharness.app;

import android.os.Bundle;
import android.os.Build;
import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.app.PendingIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.fragment.app.Fragment;

/** 配置模块：API key / 端口 / 模型 / 沙箱模式 */
public class ConfigFragment extends Fragment {

    private HarnessController c;
    private EditText apiKeyEdit, portEdit, modelEdit;
    private Spinner modeSpinner;
    private CheckBox confirmShellCb, checkUpdateCb, desktopModeCb, lanModeCb, rc6Cb, geckoCb, adbCb, rootShellCb;
    private EditText autoBackupEdit;
    private Button saveBtn;
    private TextView repoLink;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        apiKeyEdit = view.findViewById(R.id.config_api_key);
        portEdit = view.findViewById(R.id.config_port);
        modelEdit = view.findViewById(R.id.config_model);
        modeSpinner = view.findViewById(R.id.config_mode);
        confirmShellCb = view.findViewById(R.id.config_confirm_shell);
        checkUpdateCb = view.findViewById(R.id.config_check_update);
        desktopModeCb = view.findViewById(R.id.config_desktop_mode);
        lanModeCb = view.findViewById(R.id.config_lan_mode);
        rc6Cb = view.findViewById(R.id.config_rc6);
        autoBackupEdit = view.findViewById(R.id.config_auto_backup);
        geckoCb = view.findViewById(R.id.config_gecko_core);
        adbCb = view.findViewById(R.id.config_adb_enable);
        rootShellCb = view.findViewById(R.id.config_root_shell);
        saveBtn = view.findViewById(R.id.config_save);
        repoLink = view.findViewById(R.id.config_repo_link);
        SubPageBack.bind(this, view);
        setupCommonControls(); // 模式 spinner / 保存 / 关于
        // 工作区（文件/备份恢复/环境管理）→ 二级页面
        TextView workspaceEntry = view.findViewById(R.id.config_workspace_entry);
        if (workspaceEntry != null) {
            workspaceEntry.setOnClickListener(v ->
                    requireActivity().getSupportFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new WorkspaceFragment())
                            .addToBackStack("workspace")
                            .commit());
        }

        Button batteryBtn = view.findViewById(R.id.config_battery_opt);
        if (batteryBtn != null) {
            refreshBatteryOptState(view);
            batteryBtn.setOnClickListener(v -> requestIgnoreBatteryOpt());
        }
        Button adbPairBtn = view.findViewById(R.id.config_adb_pair);
        if (adbPairBtn != null) {
            adbPairBtn.setOnClickListener(v -> {
                if (!DeviceBridgeService.isAdbEnabled(requireContext())) {
                    Toast.makeText(requireContext(), "先勾选「启用 ADB」并保存", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    DeviceBridgeService.apply(requireContext());
                    showAdbPairNotification();
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "无法打开 ADB 配对：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
        refreshAdbStatus();
        startAdbStatusPolling(); // 设置页在前台时每秒检测 ADB 运行状态
    }

    // ================= ADB 运行状态实时检测（每秒；仅本页前台时） =================
    private final android.os.Handler adbStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable adbStatusTick = this::pollAdbStatus;
    private boolean adbPolling = false;

    /** 启动每秒轮询（Fragment 可见时）。实际探测一次要起 proot 子进程（1~3s），
     *  1s 太频繁 → 5s 一次（线程异步不卡 UI，但降低 CPU/电量消耗） */
    private static final long ADB_POLL_MS = 5000;

    /** 启动 ADB 状态轮询（Fragment 可见时） */
    private void startAdbStatusPolling() {
        if (adbPolling) return;
        adbPolling = true;
        adbStatusHandler.postDelayed(adbStatusTick, 1000);
    }

    /** 停止轮询（Fragment 不可见时，省电） */
    private void stopAdbStatusPolling() {
        adbPolling = false;
        adbStatusHandler.removeCallbacks(adbStatusTick);
    }

    /** 每 5 秒执行：查询 ADB 实际运行状态并刷新 UI */
    private void pollAdbStatus() {
        if (!adbPolling) return;
        if (!isAdded() || getView() == null) {
            stopAdbStatusPolling();
            return;
        }
        final TextView adbStatus = getView().findViewById(R.id.config_adb_status);
        if (adbStatus == null) { stopAdbStatusPolling(); return; }
        if (!DeviceBridgeService.isAdbEnabled(requireContext())) {
            adbStatus.setText("ADB 已关闭。不用无线调试就保持关闭。");
            adbStatusHandler.postDelayed(adbStatusTick, ADB_POLL_MS);
            return;
        }
        new Thread(() -> {
            try {
                // 探测 adb 是否真实可用（用 rootfs 里的 adb-shell 实际跑一下，最准）
                String r = c.getProot().execAndRead("DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py id 2>&1 | head -2");
                final boolean connected = r != null && r.contains("uid=");
                final String detail = r == null ? "" : r.replace("\n", " ").trim();
                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    TextView tv = getView() != null ? getView().findViewById(R.id.config_adb_status) : null;
                    if (tv == null) return;
                    if (connected) {
                        tv.setTextColor(requireContext().getColor(R.color.ok));
                        tv.setText("● ADB 运行中（已连接，uid=2000 shell）\n" + (detail.length() > 80 ? detail.substring(0, 80) : detail));
                    } else {
                        tv.setTextColor(requireContext().getColor(R.color.warn));
                        tv.setText("○ ADB 未连接（无线调试可能未开启）\n点下方「无线配对」或查看手机「开发者选项→无线调试」");
                    }
                    // 3090 桥绑定失败（端口被别的应用占了）时一并摊开说——否则表现出来
                    // 只是「确认弹窗不出现 / agent 调什么都超时」，很难定位
                    String bridgeErr = HttpShellService.bindError();
                    if (bridgeErr != null && !bridgeErr.isEmpty()) {
                        tv.setTextColor(requireContext().getColor(R.color.err));
                        tv.setText(tv.getText() + "\n⚠ 命令桥未启动：" + bridgeErr);
                    }
                });
            } catch (Throwable ignored) {
            }
            if (adbPolling) adbStatusHandler.postDelayed(adbStatusTick, ADB_POLL_MS);
        }, "adb-status-poll").start();
    }

    /** 电池优化白名单：系统休眠会冻结后台网络，不放行的话 ADB 保活等于白做 */
    private void refreshBatteryOptState(View root) {
        if (root == null) return;
        Button btn = root.findViewById(R.id.config_battery_opt);
        TextView hint = root.findViewById(R.id.config_battery_opt_hint);
        if (btn == null) return;
        boolean ignoring = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            ignoring = pm != null
                    && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        } catch (Throwable ignored) {
        }
        if (ignoring) {
            btn.setText("🔋 已关闭电池优化 ✓");
            if (hint != null) {
                hint.setText("已在白名单内：系统休眠不会再冻结 ADB 连接与后台自愈。");
                hint.setTextColor(requireContext().getColor(R.color.ok));
            }
        } else {
            btn.setText("🔋 关闭电池优化（保活必做）");
            if (hint != null) {
                hint.setText("系统休眠会冻结后台网络，ADB 连接因此断掉且无法自动恢复。加入白名单后保活才真正生效。");
                hint.setTextColor(requireContext().getColor(R.color.warn));
            }
        }
    }

    private void requestIgnoreBatteryOpt() {
        String pkg = requireContext().getPackageName();
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
                // 已放行：跳系统列表页，用户可以自己核对或撤销
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            }
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + pkg)));
        } catch (Throwable e) {
            // 部分 ROM 屏蔽了直接申请：退回系统电池设置页
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                Toast.makeText(requireContext(),
                        "请手动到系统设置 → 电池 → 应用耗电管理里放行 DSHA", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 危险命令守卫的完整性提示（吸收上游 PR#24）。
     *  bash 工具补丁靠 sed 匹配 dsh 已构建的代码，而 dsh 走「始终最新 RC」自动升级，
     *  上游一改代码这层保险就静默降级 —— 必须让用户看得见，
     *  否则会以为确认仍是「PATH 包装器 + 函数级守卫」双保险。 */
    private void showGuardStatus() {
        View v = getView();
        TextView tv = v == null ? null : v.findViewById(R.id.config_guard_status);
        if (tv == null || c == null) return;
        String st = c.guardPatchState();
        if ("unknown".equals(st)) {
            tv.setVisibility(View.GONE); // 还没启动过 Web，无从判断，不必吓人
            return;
        }
        tv.setVisibility(View.VISIBLE);
        if ("ok".equals(st)) {
            tv.setTextColor(requireContext().getColor(R.color.ok));
            tv.setText("● 守卫完整：PATH 包装器 + bash 工具补丁");
        } else {
            tv.setTextColor(requireContext().getColor(R.color.warn));
            tv.setText("○ bash 工具补丁未生效（dsh 可能已升级改动代码）\n"
                    + "危险命令仍会被 PATH 包装器拦截并弹确认，只是少一层兜底。\n"
                    + "详情见容器内 /root/dsh-guard-patch.log");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startAdbStatusPolling();
        refreshBatteryOptState(getView());
        showGuardStatus(); // 上次启动 Web 时补丁可能已失配，切回本页就刷新
        // 回到配置页顺手催一次 ADB 探测：用户往往就是来看连上没有的
        DeviceBridgeService.kickNow(requireContext(), "打开配置页");
    }

    @Override
    public void onPause() {
        super.onPause();
        stopAdbStatusPolling();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAdbStatusPolling();
    }

    private void refreshAdbStatus() {
        // 已由 pollAdbStatus 每秒轮询替代（保留空方法避免调用点改动）
    }

    /** root shell 授权标记：授权 → 写 /root/.dsh/allow-root-shell（adb-shell.py 检查）；
     *  取消 → 删标记。rootfs 未就绪时静默。 */
    private void applyRootShellMark() {
        try {
            final boolean allow = c.isRootShellAllowed();
            new Thread(() -> {
                try {
                    if (c.getProot().isInstalled()) {
                        if (allow) {
                            c.getProot().execAndRead(
                                    "mkdir -p /root/.dsh && touch /root/.dsh/allow-root-shell && echo ok");
                        } else {
                            c.getProot().execAndRead("rm -f /root/.dsh/allow-root-shell && echo ok");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "root-shell-mark").start();
        } catch (Throwable ignored) {
        }
    }

    /** 守卫开关标记：confirm_shell=true → 写 /root/.dsh/confirm-shell-enabled
     *  （adb-shell 包装据此对设备命令弹确认）；false → 删标记（只口头报备）。 */
    private void applyConfirmShellMark() {
        try {
            final boolean enabled = confirmShellCb != null && confirmShellCb.isChecked();
            new Thread(() -> {
                try {
                    if (c.getProot().isInstalled()) {
                        if (enabled) {
                            c.getProot().execAndRead(
                                    "mkdir -p /root/.dsh && touch /root/.dsh/confirm-shell-enabled && echo ok");
                        } else {
                            c.getProot().execAndRead("rm -f /root/.dsh/confirm-shell-enabled && echo ok");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "confirm-shell-mark").start();
        } catch (Throwable ignored) {
        }
    }

    /** 构建「输入配对码」通知卡（RemoteInput，参考 Shizuku 无线配对交互）：
     *  通知栏直接输入 6 位码 → 点「输码配对」→ AdbPairReceiver 后台完成配对 → 结果推回。 */
    private void showAdbPairNotification() {
        try {
            Context ctx = requireContext();
            // Android 13+：无通知权限直接 notify 会抛 SecurityException → 先引导授权
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(ctx, "需要通知权限才能显示配对卡片，请在系统弹窗中允许", Toast.LENGTH_LONG).show();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                return;
            }
            String CH = "dsh_adbpair_channel";
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "ADB 无线配对",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Intent intent = new Intent(ctx, AdbPairReceiver.class).setAction(AdbPairReceiver.ACTION_PAIR);
            RemoteInput ri = new RemoteInput.Builder(AdbPairReceiver.EXTRA_CODE)
                    .setLabel("6 位配对码")
                    .build();
            // RemoteInput 必须用 FLAG_MUTABLE：IMMUTABLE 的 PendingIntent 收不到输入内容
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                    R.drawable.ic_launch, "输码配对", pi)
                    .addRemoteInput(ri)
                    .build();
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle("🔐 ADB 无线配对")
                    .setContentText("请在手机「开发者选项→无线调试」点「使用配对码配对设备」，把 6 位码填到下面")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("1. 手机「开发者选项 → 无线调试」→「使用配对码配对设备」\n"
                                    + "2. 记下 6 位配对码\n"
                                    + "3. 点下方「输码配对」，在通知栏直接输入配对码"))
                    .addAction(action)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            nm.notify(Constants.NOTIF_ADB_PAIR_CARD, b.build());
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "通知创建失败（可先到系统设置允许通知权限）：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setupCommonControls() {
        String[] modes = {"danger-full-access", "workspace-write", "read-only"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, modes);
        // 关闭态用 simple_spinner_item、展开态才用 dropdown_item：
        // 两处都传 dropdown_item 会让关闭态也带上勾选标记的留白，和右侧箭头挤在一起
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);

        loadConfig();
        if (rootShellCb != null) rootShellCb.setChecked(c.isRootShellAllowed());

        saveBtn.setOnClickListener(v -> {
            c.setApiKey(apiKeyEdit.getText().toString().trim());
            c.setPort(portEdit.getText().toString().trim());
            c.setModel(modelEdit.getText().toString().trim());
            c.setPermissionMode((String) modeSpinner.getSelectedItem());
            requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("confirm_shell", confirmShellCb.isChecked())
                    .putBoolean("check_update", checkUpdateCb.isChecked())
                    .putBoolean("desktop_mode", desktopModeCb.isChecked())
                    .putBoolean("lan_mode", lanModeCb.isChecked())
                    .putBoolean("use_rc6", rc6Cb.isChecked())
                    .putBoolean("gecko_core", geckoCb != null && geckoCb.isChecked())
                    .putBoolean(DeviceBridgeService.PREF_ADB, adbCb != null && adbCb.isChecked())
                    .putInt("auto_backup_launches", parseAutoBackup())
                    .apply();
            c.setRootShellAllowed(rootShellCb != null && rootShellCb.isChecked());
            applyRootShellMark();
            applyConfirmShellMark();
            DeviceBridgeService.apply(requireContext());
            refreshAdbStatus();
            Toast.makeText(requireContext(),
                    (adbCb != null && adbCb.isChecked()) ? "配置已保存（ADB 已开）" : "配置已保存（ADB 已关）",
                    Toast.LENGTH_SHORT).show();
        });

        // 关于入口：点版本号弹「关于」对话框（GitHub / QQ 群）
        // 版本号动态显示（与应用信息一致）
        if (repoLink != null) {
            try {
                String v = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                repoLink.setText("DSHA v" + v);
            } catch (Exception ignored) {
            }
            repoLink.setOnClickListener(v -> AboutDialog.show(requireContext()));
        }
    }

    /** 解析"每启动 N 次自动备份"输入（0=关闭，非法回退 5） */
    private int parseAutoBackup() {
        try {
            int n = Integer.parseInt(autoBackupEdit.getText().toString().trim());
            return Math.max(0, Math.min(n, 999));
        } catch (Exception e) {
            return 5;
        }
    }

    private void loadConfig() {
        apiKeyEdit.setText(c.getApiKey());
        portEdit.setText(c.getPort());
        modelEdit.setText(c.getModel());
        String mode = c.getPermissionMode();
        int idx = 0;
        if ("workspace-write".equals(mode)) idx = 1;
        else if ("read-only".equals(mode)) idx = 2;
        modeSpinner.setSelection(idx);
        confirmShellCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("confirm_shell", true));
        checkUpdateCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("check_update", true));
        desktopModeCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("desktop_mode", false));
        rc6Cb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("use_rc6", true));
        lanModeCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false));
        if (geckoCb != null) {
            geckoCb.setChecked(requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean("gecko_core", false));
        }
        if (adbCb != null) {
            adbCb.setChecked(DeviceBridgeService.isAdbEnabled(requireContext()));
        }
        if (autoBackupEdit != null) {
            int n = requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getInt("auto_backup_launches", 5);
            autoBackupEdit.setText(String.valueOf(n));
        }
        if (repoLink != null) {
            try {
                String v = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                repoLink.setText("DSHA v" + v);
            } catch (Exception ignored) {
            }
            repoLink.setOnClickListener(v -> AboutDialog.show(requireContext()));
        }
    }
}
