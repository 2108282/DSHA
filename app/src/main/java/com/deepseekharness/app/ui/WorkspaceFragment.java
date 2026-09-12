package com.deepseekharness.app.ui;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.R;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupScope;

import java.io.File;

/**
 * 数据与备份子页：备份（按范围 + 验证）/ 恢复（合并 + 验证）。
 */
public class WorkspaceFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());
    private HarnessController controller;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_workspace, container, false);
        controller = new HarnessController(requireContext());

        v.findViewById(R.id.sub_back).setOnClickListener(x -> getParentFragmentManager().popBackStack());
        v.findViewById(R.id.workspace_backup).setOnClickListener(x -> chooseScopeAndBackup());
        v.findViewById(R.id.workspace_restore).setOnClickListener(x -> confirmRestore());
        v.findViewById(R.id.workspace_location).setOnClickListener(x ->
                Toast.makeText(requireContext(), "备份保存在 Download/DSHA/", Toast.LENGTH_LONG).show());

        // 工作区路径配置
        android.widget.EditText wsPathInput = v.findViewById(R.id.workspace_path);
        if (wsPathInput != null) {
            wsPathInput.setText(controller.config().getWorkdir());
            v.findViewById(R.id.workspace_apply).setOnClickListener(x -> {
                String newWd = wsPathInput.getText().toString().trim();
                if (!newWd.isEmpty()) {
                    controller.config().setWorkdir(newWd);
                    Toast.makeText(requireContext(), "工作区目录已更新：" + newWd, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 文件共享（DocumentsProvider，MT 管理器可发现）
        TextView shareStatus = v.findViewById(R.id.workspace_share_status);
        if (shareStatus != null) {
            shareStatus.setText("文件提供器已就绪（DocumentsProvider，无需 ROOT）\n\n"
                    + "用法：MT 管理器 → 设置 → 添加本地存储 → 通过 DocumentsProvider → 选「DSHA」\n\n"
                    + "容器根在：files → linux → ubuntu → root\n"
                    + "配置在：files → linux → ubuntu → root → .dsh\n\n"
                    + "（若 MT 里看不到，先打开本 App 保持进程运行）");
        }

        // Shizuku 授权（备用 shell 通道）
        v.findViewById(R.id.workspace_shizuku_auth).setOnClickListener(x -> {
            if (!ShizukuShell.isAvailable()) {
                Toast.makeText(requireContext(), "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            ShizukuShell.requestPermission((code, grantResult) -> refreshShizukuStatus());
            refreshShizukuStatus();
        });

        // 清理损坏会话：1.2-alpha 的会话是 packed/zstd，对 DSHA 不透明，照原版隐藏该控制
        View cleanSessions = v.findViewById(R.id.workspace_clean_sessions);
        if (cleanSessions != null) cleanSessions.setVisibility(View.GONE);

        // 重置配置（保留对话记录）
        v.findViewById(R.id.workspace_reset).setOnClickListener(x ->
                new AlertDialog.Builder(requireContext())
                        .setTitle("重置配置？")
                        .setMessage("将删除 settings.yaml 和 .env（对话记录保留），并重新写入 .env。")
                        .setPositiveButton("重置", (d, w) -> {
                            String r = controller.resetConfig();
                            Toast.makeText(requireContext(),
                                    com.deepseekharness.app.util.SensitiveData.redact(r),
                                    Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        // 清除环境（下次启动重新解压）
        v.findViewById(R.id.workspace_clear).setOnClickListener(x ->
                new AlertDialog.Builder(requireContext())
                        .setTitle("清除环境？")
                        .setMessage("将删除整个容器（rootfs），配置与对话保留。\n\n"
                                + "下次启动 App 会重新解压内置环境（约几分钟）。")
                        .setPositiveButton("清除", (d, w) -> {
                            try {
                                controller.stopWeb();
                                controller.proot().uninstall();
                                controller.resetExtraction();
                                Toast.makeText(requireContext(), "已清除环境，下次启动会重新解压",
                                        Toast.LENGTH_LONG).show();
                            } catch (Throwable t) {
                                Toast.makeText(requireContext(), "清除失败：" + t.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show());

        refreshShizukuStatus();
        return v;
    }

    private void refreshShizukuStatus() {
        try {
            TextView status = getView() == null ? null
                    : getView().findViewById(R.id.workspace_shizuku_status);
            if (status == null) return;
            if (!ShizukuShell.isAvailable()) {
                status.setText("Shizuku 未安装/未运行");
            } else if (ShizukuShell.hasPermission() && ShizukuShell.isReady()) {
                status.setText("Shizuku 已授权，设备 shell 通道可用");
            } else if (ShizukuShell.hasPermission()) {
                status.setText("Shizuku 已授权，等待绑定服务…");
            } else {
                status.setText("Shizuku 未授权，点下方授权");
            }
        } catch (Throwable ignored) {
        }
    }

    private void chooseScopeAndBackup() {
        final CharSequence[] choices = new CharSequence[BackupScope.ALL.length];
        for (int i = 0; i < BackupScope.ALL.length; i++) {
            choices[i] = BackupScope.label(BackupScope.ALL[i]) + "\n" + BackupScope.describe(BackupScope.ALL[i]);
        }
        final int[] selected = {0};
        new AlertDialog.Builder(requireContext())
                .setTitle("选择备份范围")
                .setSingleChoiceItems(choices, 0, (d, which) -> selected[0] = which)
                .setPositiveButton("下一步", (d, which) -> confirmBackup(BackupScope.ALL[selected[0]]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmBackup(final int scope) {
        String summary = "即将备份：" + BackupScope.label(scope)
                + "\n" + BackupScope.describe(scope)
                + "\n\n保存为 DSHA-backup-latest.tar.gz（Download/DSHA）。默认不包含 API Key。";
        new AlertDialog.Builder(requireContext())
                .setTitle("确认备份")
                .setMessage(summary)
                .setPositiveButton("开始备份", (d, w) -> doBackup(scope))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doBackup(final int scope) {
        toast("开始备份…");
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String path = BackupManager.backupToExternal(app, controller, scope);
            main.post(() -> {
                if (path == null) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("备份失败")
                            .setMessage(BackupManager.lastError())
                            .setPositiveButton("关闭", null)
                            .show();
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("备份成功（已校验）")
                            .setMessage("已备份 " + BackupScope.label(scope) + "\n\n保存位置：\n" + path
                                    + "\n\n归档已通过条目数与大小校验。")
                            .setPositiveButton("关闭", null)
                            .show();
                }
            });
        }, "dsha-backup").start();
    }

    private final androidx.activity.result.ActivityResultLauncher<String[]> restorePicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) doRestore(uri);
                    });

    private void confirmRestore() {
        new AlertDialog.Builder(requireContext())
                .setTitle("恢复备份")
                .setMessage("选择要恢复的备份文件（Download/DSHA/ 下的 .tar.gz）。\n\n"
                        + "会覆盖当前配置/对话（恢复前会自动把现有 .dsh 挪到 .dsh.pre-restore-* 保留）。\n确定？")
                .setPositiveButton("选择文件", (d, w) -> {
                    android.util.Log.i("DSHA-restore", "选择文件按钮点击，准备 launch");
                    try {
                        restorePicker.launch(new String[]{"*/*"});
                        android.util.Log.i("DSHA-restore", "launch 已调用");
                    } catch (Throwable t) {
                        android.util.Log.e("DSHA-restore", "launch 异常: " + t, t);
                        Toast.makeText(requireContext(), "打开选择器失败：" + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doRestore(Uri backupUri) {
        toast("开始恢复…");
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                // 恢复前先尝试停止后台服务，释放文件句柄
                try { controller.stopWeb(); } catch (Throwable ignored) {}
                String report = BackupManager.restoreFromBackup(app, controller, backupUri);
                main.post(() -> new AlertDialog.Builder(requireContext())
                        .setTitle("恢复完成（已校验）")
                        .setMessage(report + "\n\n建议立即重启服务以加载恢复的数据。")
                        .setPositiveButton("立即重启", (d, w) -> {
                            controller.stopWeb();
                            controller.startWeb(null);
                            Toast.makeText(requireContext(), "正在重启服务…", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("稍后手动启动", null)
                        .show());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> new AlertDialog.Builder(requireContext())
                        .setTitle("恢复失败")
                        .setMessage(msg)
                        .setPositiveButton("关闭", null)
                        .show());
            }
        }, "dsha-restore").start();
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }
}
