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

        backupBtn.setOnClickListener(v -> {
            // Web UI 正在运行时会实时写对话文件，直接 tar 可能拿到半截数据。
            // 有运行就先提示用户（选择继续备份则由 tar 容忍；推荐先停止）
            if (c.isWebRunning()) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Web UI 正在运行")
                        .setMessage("对话记录可能正在写入。建议先停止 Web UI 再备份，避免备份到半截文件。\n\n仍要继续备份吗？")
                        .setPositiveButton("停止后备份", (d, w) -> {
                            // 用同步深停（等端口关透）再备份，避免异步 stopWeb 期间 tar 到写入中的文件
                            Toast.makeText(requireContext(), "正在停止 Web 并备份…", Toast.LENGTH_SHORT).show();
                            new Thread(() -> {
                                try {
                                    c.stopWebAndWait();
                                } catch (Throwable ignored) {
                                }
                                doBackup();
                            }).start();
                        })
                        .setNegativeButton("直接备份", (d, w) -> {
                            Toast.makeText(requireContext(), "正在备份（可能含写入中的会话）…", Toast.LENGTH_SHORT).show();
                            new Thread(() -> doBackup()).start();
                        })
                        .setNeutralButton("取消", null)
                        .show();
            } else {
                Toast.makeText(requireContext(), "正在备份，请稍候…", Toast.LENGTH_SHORT).show();
                new Thread(() -> doBackup()).start();
            }
        });

        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("重置配置？")
                .setMessage("将删除 settings.yaml 和 .env（对话记录保留），并重新写入 .env。")
                .setPositiveButton("重置", (d, w) -> {
                    String r = c.resetConfig();
                    Toast.makeText(requireContext(), r, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show());

        restoreBtn.setOnClickListener(v ->
                pickBackup.launch(new String[]{"*/*"}));

        // 清理损坏会话（dsh 双进程写导致 seq 重复损坏，官方 #420）
        if (cleanSessionsBtn != null) {
            cleanSessionsBtn.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                    .setTitle("清理损坏会话？")
                    .setMessage("将把无法解码/极小的会话文件移到 .dsh/corrupt-backup/\n"
                            + "（不删除，可恢复）。用于修复「历史加载失败 / resume failed」。\n\n"
                            + "建议先停止 Web UI 再清理。")
                    .setPositiveButton("清理", (d, w) -> {
                        Toast.makeText(requireContext(), "正在清理损坏会话…", Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            String r = c.cleanCorruptSessions();
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                                        r, Toast.LENGTH_LONG).show());
                            }
                        }).start();
                    })
                    .setNegativeButton("取消", null)
                    .show());
        }
    }

    /** 执行备份并展示结果（独立方法，供直接备份/停止后备份复用） */
    private void doBackup() {
        String path = BackupManager.backupToExternal(requireContext(), c);
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (path == null) {
                String why = BackupManager.lastError();
                new AlertDialog.Builder(requireContext())
                        .setTitle("备份失败")
                        .setMessage(why.isEmpty() ? "未知原因，请查看 logcat" : why)
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle("备份完成")
                    .setMessage("已导出到：\n" + path)
                    .setPositiveButton("复制路径", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("backup", path));
                            Toast.makeText(requireContext(), "路径已复制", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("好", null)
                    .show();
        });
    }

    private void restoreBackup(Uri uri) {
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
                .setMessage("将用备份文件覆盖当前的配置和对话记录。\n\n确认恢复？")
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
                // 同步 API key：恢复的 .env 写回 App 配置，避免下次启动被覆盖
                String env = c.getProot().execAndRead(
                        "cat /root/" + c.getWorkdir() + "/.env 2>/dev/null");
                boolean keySynced = false;
                if (env != null) {
                    for (String line : env.split("\n")) {
                        if (line.startsWith("DEEPSEEK_API_KEY=")) {
                            String key = line.substring("DEEPSEEK_API_KEY=".length()).trim();
                            if (!key.isEmpty()) {
                                c.setApiKey(key);
                                keySynced = true;
                            }
                            break;
                        }
                    }
                }
                if (getActivity() == null) return;
                final String msg = result + (keySynced ? "\n· API key 已同步到配置页" : "");
                getActivity().runOnUiThread(() -> {
                    // 用 appCtx（Fragment 可能已 detach，requireContext 会抛）
                    Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(appCtx,
                            "恢复失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (c != null) refreshInfo();
    }

    private void refreshInfo() {
        String envState = c.isHarnessInstalled() ? "✅ 已安装"
                : c.getProot().isInstalled() ? "🔄 环境已就绪" : "📦 未安装";
        infoText.setText("环境状态：" + envState
                + "\n\n工作区（rootfs 内）：/root/" + c.getWorkdir()
                + "\n\n安装完成后该目录即为 deepseek-harness 源码。");
        refreshShareStatus();
    }

    private void refreshShareStatus() {
        shareStatusText.setText("文件提供器已就绪（MT 官方注入，无需 ROOT）\n\n"
                + "用法：MT 管理器 → 侧拉栏 → 添加本地存储 → 选择「DSHA」\n\n"
                + "工作区在：data → files → linux → ubuntu → root → " + c.getWorkdir() + "\n"
                + "配置在：data → files → linux → ubuntu → root → .dsh\n\n"
                + "（若 MT 里看不到内容，先打开本 App 保持进程运行）");
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
