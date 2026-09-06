package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;

/**
 * 安装模块子页：环境内置（offline-rootfs 解压），这里做「检查 / 修复」——
 * 六步分别对应 rootfs / 基础工具 / Node / pnpm / dsh / 安全补丁，
 * 每步可单独重跑；一键安装 = 全部检查一遍并自动修复缺项。
 */
public class InstallFragment extends Fragment {

    private HarnessController c;
    private com.deepseekharness.app.runtime.BasicToolsInstaller basicTools;
    private boolean busy;
    private TextView statusText, progressText, errorText, stepStatusText;
    private ProgressBar progressBar;
    private Button step1Btn, step2Btn, step3Btn, step4Btn, step5Btn, step6Btn;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_install, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = new HarnessController(requireContext());
        basicTools = new com.deepseekharness.app.runtime.BasicToolsInstaller(requireContext(), c.proot());
        statusText = view.findViewById(R.id.install_status);
        progressText = view.findViewById(R.id.install_progress);
        errorText = view.findViewById(R.id.install_error);
        stepStatusText = view.findViewById(R.id.install_steps);
        progressBar = view.findViewById(R.id.install_progressbar);
        step1Btn = view.findViewById(R.id.install_step1);
        step2Btn = view.findViewById(R.id.install_step2);
        step3Btn = view.findViewById(R.id.install_step3);
        step4Btn = view.findViewById(R.id.install_step4);
        step5Btn = view.findViewById(R.id.install_step5);
        step6Btn = view.findViewById(R.id.install_step6);

        view.findViewById(R.id.sub_back).setOnClickListener(x -> getParentFragmentManager().popBackStack());
        view.findViewById(R.id.install_btn).setOnClickListener(x -> runAll());
        view.findViewById(R.id.install_copy).setOnClickListener(x -> copyError());
        view.findViewById(R.id.install_crash).setOnClickListener(x ->
                Toast.makeText(requireContext(), "环境为内置离线包，无独立崩溃日志", Toast.LENGTH_SHORT).show());
        view.findViewById(R.id.install_uninstall).setOnClickListener(x -> confirmUninstall());

        step1Btn.setOnClickListener(x -> runStep(1));
        step2Btn.setOnClickListener(x -> runStep(2));
        step3Btn.setOnClickListener(x -> runStep(3));
        step4Btn.setOnClickListener(x -> runStep(4));
        step5Btn.setOnClickListener(x -> runStep(5));
        step6Btn.setOnClickListener(x -> runStep(6));

        refreshOverview();
    }

    // ================= 状态概览 =================

    @Override public void onDestroyView() {
        busy = false;
        super.onDestroyView();
    }

    private void refreshOverview() {
        ProotBootstrap p = c.proot();
        boolean env = p.isEnvironmentReady();
        statusText.setText("环境：" + (env ? "✅ 已就绪" : "⚠️ 未解压/不完整")
                + "\n核心运行环境已内置。第 2 步缺少 curl / git 时会联网补齐，Python 与 pnpm 可离线修复。");
        stepStatusText.setText("按 1—6 顺序检查，也可单独修复其中一步。");
    }

    // ================= 步骤执行 =================

    private void runAll() {
        if (busy) return;
        final View page = getView();
        final android.app.Activity activity = getActivity();
        if (page == null || activity == null) return;
        statusText.setText("一键检查中…");
        showProgress(true);
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            int problems = 0;
            int[] steps = {1, 2, 3, 4, 5, 6};
            for (int i = 0; i < steps.length; i++) {
                final int step = steps[i];
                String[] r = checkStep(step);
                if (!r[1].startsWith("✅")) problems++;
                log.append(r[0]).append("\n");
                final int pct = (i + 1) * 100 / steps.length;
                if (!isAdded()) return;
                activity.runOnUiThread(() -> {
                    if (getView() != page) return;
                    progressBar.setProgress(pct);
                    progressText.setText("第 " + step + " 步：" + r[1]);
                });
            }
            final String report = log.toString();
            final int failures = problems;
            activity.runOnUiThread(() -> {
                if (getView() != page) return;
                showProgress(false);
                statusText.setText(failures == 0 ? "全部 6 步检查通过。" : "检查完成，仍有 " + failures + " 步需要处理。");
                stepStatusText.setText(failures == 0 ? report : "未通过步骤的详细输出见下方，可复制后反馈。");
                showResultError(failures > 0, report);
            });
        }, "install-check").start();
    }

    private void runStep(int step) {
        if (busy) return;
        final View page = getView();
        final android.app.Activity activity = getActivity();
        if (page == null || activity == null) return;
        statusText.setText("正在检查第 " + step + " 步…");
        showProgress(true);
        if (step == 2) progressText.setText("正在检查并修复 curl / git / Python；首次下载可能需要几分钟…");
        new Thread(() -> {
            String[] r = checkStep(step);
            activity.runOnUiThread(() -> {
                if (getView() != page) return;
                showProgress(false);
                statusText.setText("第 " + step + " 步结果：" + r[1]);
                stepStatusText.setText(r[1].startsWith("✅") ? r[0] : "本步未完成，详细输出见下方。");
                showResultError(!r[1].startsWith("✅"), r[0]);
            });
        }, "install-step").start();
    }

    /** 检查并修复某一步；返回 {详细输出, 一句话结论}。 */
    private String[] checkStep(int step) {
        ProotBootstrap p = c.proot();
        try {
            switch (step) {
                case 1: {
                    // ① rootfs：解压标记 + bash 可执行
                    if (p.isEnvironmentReady()) {
                        return new String[]{"① rootfs：已解压，bash 在位。", "✅ 正常"};
                    }
                    if (p.hasOfflineBundle()) {
                        p.extractOfflineBundle((done, total) -> { });
                        return p.isEnvironmentReady()
                                ? new String[]{"① rootfs：重新解压完成。", "✅ 已修复"}
                                : new String[]{"① rootfs：解压后仍不完整。", "❌ 异常"};
                    }
                    return new String[]{"① rootfs：无离线包（APK 是精简包）。", "❌ 缺离线包"};
                }
                case 2: {
                    return basicTools.repair();
                }
                case 3: {
                    // ③ Node.js
                    String v = p.execAndRead("node --version 2>&1 | head -1").trim();
                    return v.startsWith("v")
                            ? new String[]{"③ Node.js：" + v, "✅ 正常"}
                            : new String[]{"③ Node.js 不可用：" + v, "❌ 异常"};
                }
                case 4: {
                    if (!p.ensureBundledPnpm())
                        return new String[]{"④ 离线 pnpm 修复失败，请重试。", "❌ 异常"};
                    String v = p.execAndRead("pnpm --version 2>&1 | head -1").trim();
                    return v.matches("\\d+.*")
                            ? new String[]{"④ pnpm：" + v, "✅ 正常"}
                            : new String[]{"④ pnpm 不可用：" + v, "⚠️ 插件管理需 pnpm"};
                }
                case 5: {
                    // ⑤ deepseek-harness（dsh 版本）
                    String v = p.execAndRead(
                            "node -e \"console.log(require('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json').version)\" 2>&1 | head -1")
                            .trim();
                    return v.matches("[0-9]+\\..*")
                            ? new String[]{"⑤ dsh：" + v, "✅ 正常"}
                            : new String[]{"⑤ dsh 不可用：" + v, "❌ 异常"};
                }
                case 6: {
                    // ⑥ 安全与补丁（resolv.conf DNS + session link→rename + Android 组）
                    p.ensureDshRuntimePatches();
                    p.ensureAndroidGroups();
                    return new String[]{"⑥ 补丁：DNS / session 写入 / Android 组 已核对。", "✅ 已修复"};
                }
                default:
                    return new String[]{"未知步骤", "❌"};
            }
        } catch (Throwable e) {
            String msg = SensitiveData.redact(String.valueOf(e));
            return new String[]{"步骤 " + step + " 异常：" + msg, "❌ " + e.getClass().getSimpleName()};
        }
    }

    private void confirmUninstall() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("清除环境？")
                .setMessage("将删除整个容器（rootfs），配置与对话保留。\n\n"
                        + "下次启动 App 会重新解压内置环境（约几分钟）。")
                .setPositiveButton("清除", (d, w) -> {
                    try {
                        c.stopWeb();
                        c.proot().uninstall();
                        c.resetExtraction();
                        Toast.makeText(requireContext(), "已清除环境，下次启动会重新解压",
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(requireContext(), "清除失败：" + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyError() {
        String text = errorText.getText().toString();
        if (text.isEmpty()) text = stepStatusText.getText().toString();
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DSHA 安装", text));
                Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void showProgress(boolean show) {
        busy = show;
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressBar.setIndeterminate(show);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            errorText.setVisibility(View.GONE);
            progressText.setText("正在检查，请稍候…");
        }
        View view = getView();
        if (view != null) for (int id : new int[]{R.id.install_btn, R.id.install_step1,
                R.id.install_step2, R.id.install_step3, R.id.install_step4, R.id.install_step5,
                R.id.install_step6, R.id.install_uninstall}) view.findViewById(id).setEnabled(!show);
    }

    private void showResultError(boolean failed, String output) {
        errorText.setText(failed ? output : "");
        errorText.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (getView() != null) getView().findViewById(R.id.install_copy).setVisibility(failed ? View.VISIBLE : View.GONE);
    }
}
