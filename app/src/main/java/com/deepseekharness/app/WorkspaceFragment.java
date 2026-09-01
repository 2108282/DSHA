package com.deepseekharness.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 工作区管理模块：工作目录配置、环境信息、无 ROOT 文件共享（MT 注入文件提供器） */
public class WorkspaceFragment extends Fragment {

    private HarnessController c;
    private final ActivityResultLauncher<String[]> pickBackup =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) restoreBackup(uri);
                    });
    private EditText workdirEdit;
    private TextView infoText, shareStatusText, shizukuStatusText;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_workspace, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        android.widget.TextView appTitle = requireActivity().findViewById(R.id.app_title);
        if (appTitle != null) appTitle.setText("数据与备份");
        prefs = requireContext().getSharedPreferences("deepseekharness", 0);
        workdirEdit = view.findViewById(R.id.workspace_path);
        infoText = view.findViewById(R.id.workspace_info);
        shareStatusText = view.findViewById(R.id.workspace_share_status);
        shizukuStatusText = view.findViewById(R.id.workspace_shizuku_status);
        Button applyBtn = view.findViewById(R.id.workspace_apply);
        Button shizukuAuthBtn = view.findViewById(R.id.workspace_shizuku_auth);
        Button clearBtn = view.findViewById(R.id.workspace_clear);
        Button backupBtn = view.findViewById(R.id.workspace_backup);
        Button restoreBtn = view.findViewById(R.id.workspace_restore);
        Button locationBtn = view.findViewById(R.id.workspace_location);
        Button cleanSessionsBtn = view.findViewById(R.id.workspace_clean_sessions);
        Button resetBtn = view.findViewById(R.id.workspace_reset);
        SubPageBack.bind(this, view);

        workdirEdit.setText(c.getWorkdir());
        refreshInfo();

        applyBtn.setOnClickListener(v -> {
            String wd = workdirEdit.getText().toString().trim();
            if (!wd.isEmpty()) {
                c.setWorkdir(wd);
                refreshInfo();
                Toast.makeText(requireContext(), "工作区已更新", Toast.LENGTH_SHORT).show();
            }
        });

        shizukuAuthBtn.setOnClickListener(v -> {
            if (!ShizukuShell.isAvailable()) {
                Toast.makeText(requireContext(), "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            ShizukuShell.requestPermission((code, grantResult) -> refreshShizukuStatus());
            refreshShizukuStatus();
        });

        clearBtn.setOnClickListener(v -> {
            c.getProot().uninstall();
            refreshInfo();
            Toast.makeText(requireContext(), "已清除环境", Toast.LENGTH_SHORT).show();
        });

        backupBtn.setOnClickListener(v -> confirmAndBackup());

        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("重置配置？")
                .setMessage("将删除 settings.yaml 和 .env（对话记录保留），并重新写入 .env。")
                .setPositiveButton("重置", (d, w) -> {
                    String r = c.resetConfig();
                    Toast.makeText(requireContext(), SensitiveData.redact(r), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show());

        restoreBtn.setOnClickListener(v ->
                pickBackup.launch(new String[]{"*/*"}));
        if (locationBtn != null) locationBtn.setOnClickListener(v ->
                Toast.makeText(requireContext(),
                        "保存位置：Download/DSHA/（公共下载目录）", Toast.LENGTH_LONG).show());

        // 清理损坏会话（dsh 双进程写导致 seq 重复损坏，官方 #420）
        if (cleanSessionsBtn != null) {
            if (HarnessController.EXPECTED_RUNTIME_ID.equals(c.runtimeId())) {
                // Alpha sessions can be packed/zstd and must remain opaque to
                // DSHA.  Keep the legacy control out of this runtime entirely.
                cleanSessionsBtn.setVisibility(View.GONE);
                return;
            }
            cleanSessionsBtn.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                    .setTitle("清理损坏会话？")
                    .setMessage("将把无法解码/极小的会话文件移到 .dsh/corrupt-backup/\n"
                            + "（不删除，可恢复）。用于修复「历史加载失败 / resume failed」。\n\n"
                            + "建议先停止 Web UI 再清理。")
                    .setPositiveButton("清理", (d, w) -> {
                        Toast.makeText(requireContext(), "正在清理损坏会话…", Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            String r = c.cleanCorruptSessions();
                            // lambda 真正执行时 Fragment 可能已 detach，
                            // requireContext() 会抛 IllegalStateException ——
                            // 所以先取好 application context 再进 lambda。
                            android.app.Activity actC = getActivity();
                            final android.content.Context ctxC =
                                    actC != null ? actC.getApplicationContext() : null;
                            if (actC != null && ctxC != null) {
                                actC.runOnUiThread(() -> Toast.makeText(ctxC,
                                        SensitiveData.redact(r), Toast.LENGTH_LONG).show());
                            }
                        }).start();
                    })
                    .setNegativeButton("取消", null)
                    .show());
        }
    }

    /** 新建入口先选择范围；所有新包仍统一原子发布为 latest。 */
    private void confirmAndBackup() {
        final CharSequence[] choices = new CharSequence[BackupScope.ALL.length];
        for (int i = 0; i < BackupScope.ALL.length; i++) {
            int scope = BackupScope.ALL[i];
            choices[i] = BackupScope.label(scope) + "\n" + BackupScope.describe(scope);
        }
        final int[] selected = {0};
        new AlertDialog.Builder(requireContext())
                .setTitle("选择备份范围")
                .setSingleChoiceItems(choices, 0, (dialog, which) -> selected[0] = which)
                .setPositiveButton("下一步", (dialog, which) ->
                        confirmBackupScope(BackupScope.ALL[selected[0]]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmBackupScope(final int scope) {
        final String summary = "即将备份：" + BackupScope.label(scope)
                + "\n" + BackupScope.describe(scope)
                + "\n\n新备份固定保存为 DSHA-backup-latest.tar.gz。默认不包含 API Key，不删除当前数据。";
        if (c.isWebRunning()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("确认备份")
                    .setMessage(summary + "\n\nWeb UI 正在运行，对话可能正在写入。建议先停止 Web UI 再备份。")
                    .setPositiveButton("停止后备份", (d, w) -> {
                        // 用同步深停（等端口关透）再备份，避免异步 stopWeb 期间 tar 到写入中的文件
                        Toast.makeText(requireContext(), "正在停止 Web 并备份…", Toast.LENGTH_SHORT).show();
                        final Context appCtx = requireContext().getApplicationContext();
                        new Thread(() -> {
                            try {
                                c.stopWebAndWait();
                            } catch (Throwable ignored) {
                            }
                            doBackup(appCtx, scope);
                        }).start();
                    })
                    .setNegativeButton("直接备份", (d, w) -> {
                        Toast.makeText(requireContext(), "正在备份（可能含写入中的会话）…", Toast.LENGTH_SHORT).show();
                        final Context appCtx = requireContext().getApplicationContext();
                        new Thread(() -> doBackup(appCtx, scope)).start();
                    })
                    .setNeutralButton("取消", null)
                    .show();
        } else {
            new AlertDialog.Builder(requireContext())
                    .setTitle("确认备份")
                    .setMessage(summary)
                    .setPositiveButton("开始备份", (d, w) -> {
                        Toast.makeText(requireContext(), "正在备份，请稍候…", Toast.LENGTH_SHORT).show();
                        final Context appCtx = requireContext().getApplicationContext();
                        new Thread(() -> doBackup(appCtx, scope)).start();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /** 执行选定范围的 latest 备份并展示结果。 */
    private void doBackup(Context appCtx, int scope) {
        String path = BackupManager.backupToExternal(appCtx, c, scope);
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // 弹窗必须用 Activity context，而这一刻 Fragment 可能已经 detach ——
            // requireContext() 那时会抛 IllegalStateException（在 UI 线程上抛 = 闪退）。
            if (!isAdded() || getActivity() == null) return;
            if (path == null) {
                String why = BackupManager.lastError();
                new AlertDialog.Builder(requireContext())
                        .setTitle("备份失败")
                        .setMessage(SensitiveData.redact(
                                why.isEmpty() ? "未知原因，请查看 logcat" : why))
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle("备份完成")
                    .setMessage(SensitiveData.redact(
                            BackupScope.label(scope) + " 已导出到：\n" + path))
                    .setPositiveButton("复制路径", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("backup",
                                    SensitiveData.redact(path)));
                            Toast.makeText(requireContext(), "路径已复制", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("好", null)
                    .show();
        });
    }

    private void restoreBackup(Uri uri) {
        int guessedScope = BackupScope.fromFileName(uri == null ? "" : String.valueOf(uri));
        String impact = BackupScope.restoreImpact(guessedScope);
        // Web 在跑时恢复会覆盖正在写入的 .dsh（对话可能损坏/丢失）：
        // 先深停再恢复，比"建议"更可靠（弹窗文案已说明）
        if (c.isWebRunning()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Web UI 正在运行")
                    .setMessage("恢复备份会覆盖对话记录，建议先停止 Web UI 再恢复。\n\n是否停止 Web 并恢复？")
                    .setPositiveButton("停止并恢复", (d, w) -> doRestoreWithStop(uri))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("恢复备份？")
                .setMessage("将用备份文件恢复数据。预计影响：" + impact + "。\n\n确认恢复？")
                .setPositiveButton("恢复", (d, w) -> doRestore(uri))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 停止 Web 后恢复（后台线程深停 → 恢复） */
    private void doRestoreWithStop(final Uri uri) {
        Toast.makeText(requireContext(), "正在停止 Web 并恢复，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                c.stopWebAndWait();
            } catch (Throwable ignored) {
            }
            doRestore(uri);
        }).start();
    }

    private void doRestore(Uri uri) {
        // 开头 Toast 必须主线程（doRestoreWithStop 从后台线程调本方法会 NPE！
        // 崩溃报告：Can't toast on a thread that has not called Looper.prepare()）
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isAdded()) Toast.makeText(requireContext(), "正在恢复，请稍候…", Toast.LENGTH_SHORT).show();
            });
        }
        // 提前取 context（doRestoreWithStop 从后台线程调本方法时，
        // requireContext() 在 Fragment detach 后会抛异常——用 try 兜底）
        final android.content.Context appCtx;
        try {
            appCtx = requireContext().getApplicationContext();
        } catch (Throwable e) {
            return; // Fragment 已销毁，放弃恢复
        }
        new Thread(() -> {
            try {
                File tmp = new File(c.getProot().getRootfsDir(), "root/.dsha-restore-src.tar.gz");
                if (tmp.getParentFile() != null) tmp.getParentFile().mkdirs();
                // 显式判空：openInputStream 返回 null（权限/文件损坏）时给友好提示
                InputStream in = appCtx.getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("无法打开所选文件（可能权限不足或文件已损坏）");
                }
                try (InputStream ins = in;
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = ins.read(buf)) != -1) out.write(buf, 0, n);
                }
                // 统一走 Java 宽松解压器（与 HarnessController.restoreFromBackup 一致）：
                // GNU tar 会把文件名含逗号/引号的正常备份误判损坏（issue#9），
                // extractLenient 只拦真正的路径穿越
                // 统一交给 HarnessController.restoreFromBackup（宽容恢复）：
                // 布局识别（.dsh 在包内任意层级）、工作目录名重映射、本机路径插件重建、
                // bundle 预检（解析不了的先摘掉，保证 dsh web 能启动）。
                final String result = c.restoreFromBackup(tmp);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                // API key 同步由 HarnessController.restoreFromBackup 统一负责，且只在
                // restore-merge.py 明确输出 RESTORE_DSH_COMMITTED 后执行；这里仅展示结果，
                // 避免校验/rename 失败时再次读取或改写旧 .dsh。
                final String msg = SensitiveData.redact(result);
                // 恢复是耗时操作，用户很容易在等待期间切走页面或退出 ——
                // 那时 Fragment 已 detach，getActivity() 返回 null，
                // 这里原来直接解引用，正是「操作到一半闪退」的来源。
                // 拿不到 Activity 就退回用 appCtx 直接弹（Toast 不需要 Activity），
                // 保证结果仍然告知用户，而不是静默丢掉。
                android.app.Activity actR = getActivity();
                if (actR != null && !actR.isFinishing()) {
                    actR.runOnUiThread(() -> Toast.makeText(appCtx, msg,
                            Toast.LENGTH_LONG).show());
                } else {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(
                            () -> Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(appCtx,
                            "恢复失败：" + SensitiveData.redact(e.getMessage()),
                            Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (c != null) refreshInfo();
    }

    @Override
    public void onDestroyView() {
        android.app.Activity activity = getActivity();
        if (activity != null) {
            android.widget.TextView appTitle = activity.findViewById(R.id.app_title);
            if (appTitle != null) appTitle.setText("设置");
        }
        super.onDestroyView();
    }

    private void refreshInfo() {
        String envState = c.isHarnessInstalled() ? "✅ 已安装"
                : c.getProot().isInstalled() ? "🔄 环境已就绪" : "📦 未安装";
        infoText.setText(SensitiveData.redact("环境状态：" + envState
                + "\n\n工作区（rootfs 内）：/root/" + c.getWorkdir()
                + "\n\n安装完成后该目录即为 deepseek-harness 源码。"));
        refreshShareStatus();
    }

    private void refreshShareStatus() {
        shareStatusText.setText(SensitiveData.redact("文件提供器已就绪（MT 官方注入，无需 ROOT）\n\n"
                + "用法：MT 管理器 → 侧拉栏 → 添加本地存储 → 选择「DSHA」\n\n"
                + "工作区在：data → files → linux → ubuntu → root → " + c.getWorkdir() + "\n"
                + "配置在：data → files → linux → ubuntu → root → .dsh\n\n"
                + "（若 MT 里看不到内容，先打开本 App 保持进程运行）"));
        refreshShizukuStatus();
    }

    private void refreshShizukuStatus() {
        if (shizukuStatusText == null) return;
        if (!ShizukuShell.isAvailable()) {
            shizukuStatusText.setText("Shizuku 未安装或未启动\n（装好 Shizuku 后，在这里授权）");
        } else if (ShizukuShell.hasPermission()) {
            shizukuStatusText.setText("✅ Shizuku 已授权，助手可执行设备 shell 命令");
        } else {
            shizukuStatusText.setText("Shizuku 已就绪，点击「授权 Shizuku」");
        }
    }
}
