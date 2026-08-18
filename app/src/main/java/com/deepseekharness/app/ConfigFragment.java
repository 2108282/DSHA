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
    private CheckBox confirmShellCb, checkUpdateCb, desktopModeCb, lanModeCb, rc6Cb;
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
        saveBtn = view.findViewById(R.id.config_save);
        repoLink = view.findViewById(R.id.config_repo_link);
        setupCommonControls(); // 模式 spiner / 保存 / 关于
        // 工作区（文件/备份恢复/环境管理）→ 二级页面
        TextView workspaceEntry = view.findViewById(R.id.config_workspace_entry);
        if (workspaceEntry != null) {
            workspaceEntry.setOnClickListener(v ->
                    requireActivity().getSupportFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new WorkspaceFragment())
                            .addToBackStack("workspace")
                            .commit());
        }

        // ===== ADB 无线配对入口（通知卡片输码，免 Shizuku，参考 Shizuku 无线配对） =====
        Button adbPairBtn = view.findViewById(R.id.config_adb_pair);
        if (adbPairBtn != null) {
            adbPairBtn.setOnClickListener(v -> {
                try {
                    showAdbPairNotification();
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "无法打开 ADB 配对：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
        // ADB 状态显示（后台查询，不卡 UI）
        TextView adbStatus = view.findViewById(R.id.config_adb_status);
        if (adbStatus != null) {
            new Thread(() -> {
                try {
                    String s = AdbBridge.status(c.getProot());
                    final String st = (s == null || s.trim().isEmpty() || s.contains("ERROR"))
                            ? "ADB 设备通道：未就绪（可点下方按钮无线配对）"
                            : "ADB 设备通道：" + s.trim().substring(0, Math.min(60, s.trim().length()));
                    if (isAdded()) requireActivity().runOnUiThread(() -> adbStatus.setText(st));
                } catch (Throwable ignored) {
                }
            }).start();
        }
    }

    /** 构建「输入配对码」通知卡（RemoteInput，参考 Shizuku 无线配对交互）：
     *  通知栏直接输入 6 位码 → 点「输码配对」→ AdbPairReceiver 后台完成配对 → 结果推回。 */
    private void showAdbPairNotification() {
        try {
            Context ctx = requireContext();
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
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
            nm.notify(3003, b.build());
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "通知创建失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupCommonControls() {
        String[] modes = {"danger-full-access", "workspace-write", "read-only"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, modes);
        modeSpinner.setAdapter(adapter);

        loadConfig();

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
                    .putBoolean("use_rc6", rc6Cb.isChecked()).apply();
            Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
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
