package com.deepseekharness.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

/** 安装模块：分步安装（rootfs / 基础工具 / Node / harness），多源测速，一键补装 */
public class InstallFragment extends Fragment {

    private HarnessController c;
    private TextView statusText, progressText, errorText, stepStatusText;
    private Button installBtn, uninstallBtn, copyBtn;
    private Button step1Btn, step2Btn, step3Btn, step4Btn;
    private ProgressBar progressBar;
    private AlertDialog sourceDialog;

    private final HarnessController.StateListener stateListener = this::refreshFromState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_install, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        statusText = view.findViewById(R.id.install_status);
        progressText = view.findViewById(R.id.install_progress);
        errorText = view.findViewById(R.id.install_error);
        stepStatusText = view.findViewById(R.id.install_steps);
        installBtn = view.findViewById(R.id.install_btn);
        uninstallBtn = view.findViewById(R.id.install_uninstall);
        copyBtn = view.findViewById(R.id.install_copy);
        progressBar = view.findViewById(R.id.install_progressbar);
        step1Btn = view.findViewById(R.id.install_step1);
        step2Btn = view.findViewById(R.id.install_step2);
        step3Btn = view.findViewById(R.id.install_step3);
        step4Btn = view.findViewById(R.id.install_step4);

        c.addStateListener(stateListener);

        installBtn.setOnClickListener(v -> {
            if (c.getApiKey().isEmpty()) {
                Toast.makeText(requireContext(), "请先在「配置」模块填入 API key", Toast.LENGTH_LONG).show();
                return;
            }
            c.install();
        });

        step1Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_ROOTFS));
        step2Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_TOOLS));
        step3Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_NODE));
        step4Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_HARNESS));

        uninstallBtn.setOnClickListener(v -> {
            c.getProot().uninstall();
            Toast.makeText(requireContext(), "已清除环境", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        copyBtn.setOnClickListener(v -> {
            String err = c.getError();
            if (err == null || err.isEmpty()) {
                Toast.makeText(requireContext(), "当前没有报错内容", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dsh_error", err));
            Toast.makeText(requireContext(), "报错内容已复制", Toast.LENGTH_SHORT).show();
        });

        refreshFromState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (c != null) refreshStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (c != null) c.removeStateListener(stateListener);
    }

    private void refreshFromState() {
        if (!isAdded()) return;
        String err = c.getError();
        if (err != null && !err.isEmpty()) {
            errorText.setVisibility(View.VISIBLE);
            copyBtn.setVisibility(View.VISIBLE);
            errorText.setText(err);
            progressBar.setVisibility(View.GONE);
            progressText.setVisibility(View.GONE);
        } else {
            errorText.setVisibility(View.GONE);
            copyBtn.setVisibility(View.GONE);
            if (c.isBusy()) {
                progressBar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                int p = Math.max(0, Math.min(100, c.getPercent()));
                progressBar.setProgress(p);   // 关键：进度条要真正跟着百分比动
                progressText.setText(c.getStage() + " " + p + "%");
            } else {
                progressBar.setVisibility(View.GONE);
                String msg = c.getMessage();
                if (msg != null && !msg.isEmpty()) {
                    progressText.setVisibility(View.VISIBLE);
                    progressText.setText(msg);
                } else {
                    progressText.setVisibility(View.GONE);
                }
            }
        }
        boolean running = c.isBusy();
        installBtn.setEnabled(!running);
        step1Btn.setEnabled(!running);
        step2Btn.setEnabled(!running);
        step3Btn.setEnabled(!running);
        step4Btn.setEnabled(!running);
        uninstallBtn.setEnabled(!running);
        refreshSteps();
        refreshStatus();
        if (c.isAwaitingSourceChoice()) showSourceDialog();
    }

    /** 测速完成：弹窗让用户自选下载源 */
    private void showSourceDialog() {
        if (sourceDialog != null && sourceDialog.isShowing()) return;
        String[] labels = c.getPendingSourceLabels();
        if (labels.length == 0) return;
        int defaultIdx = Math.max(0, c.getPendingDefaultIndex());
        final int[] sel = {defaultIdx};
        sourceDialog = new AlertDialog.Builder(requireContext())
                .setTitle("选择下载源（已测速）")
                .setSingleChoiceItems(labels, defaultIdx, (d, which) -> sel[0] = which)
                .setPositiveButton("就用这个源", (d, which) -> {
                    c.onSourceChosen(sel[0]);
                    sourceDialog = null;
                })
                .setNegativeButton("自动选最快", (d, which) -> {
                    c.onSourceChosen(-1);
                    sourceDialog = null;
                })
                .setOnCancelListener(d -> {
                    c.onSourceChosen(-1);
                    sourceDialog = null;
                })
                .show();
    }

    /** 更新 4 个步骤的状态显示 */
    private void refreshSteps() {
        step1Btn.setText(stepLabel(HarnessController.STEP_ROOTFS));
        step2Btn.setText(stepLabel(HarnessController.STEP_TOOLS));
        step3Btn.setText(stepLabel(HarnessController.STEP_NODE));
        step4Btn.setText(stepLabel(HarnessController.STEP_HARNESS));
        stepStatusText.setText(
                "① Linux 环境（rootfs）   " + mark(HarnessController.STEP_ROOTFS) + "\n" +
                "② 基础工具（apt）       " + mark(HarnessController.STEP_TOOLS) + "\n" +
                "③ Node.js               " + mark(HarnessController.STEP_NODE) + "\n" +
                "④ deepseek-harness      " + mark(HarnessController.STEP_HARNESS));
    }

    private String mark(int step) {
        if (c.isBusy() && c.getCurrentStep() == step) return "⏳ 进行中";
        return c.isStepDone(step) ? "✅ 已就绪" : "⬜ 未安装";
    }

    private String stepLabel(int step) {
        String name = HarnessController.stepName(step);
        return c.isStepDone(step) ? "重装 " + name : "安装 " + name;
    }

    private void refreshStatus() {
        int done = 0;
        for (int s = HarnessController.STEP_ROOTFS; s <= HarnessController.STEP_HARNESS; s++) {
            if (c.isStepDone(s)) done++;
        }
        if (done == 4) {
            statusText.setText("✅ 全部安装完成\n\n可到「启动」页启动 Web UI。");
            installBtn.setText("重新安装（补装缺失步骤）");
        } else if (done > 0) {
            statusText.setText("🔄 已完成 " + done + "/4 步，可一键补装剩余步骤。");
            installBtn.setText("一键安装剩余步骤");
        } else {
            statusText.setText("📦 尚未安装\n\n点击下方按钮：\n一键安装 = 按顺序补装 4 个步骤\n也可单独安装某一步\n（约需 5~15 分钟，请保持网络畅通）");
            installBtn.setText("一键安装");
        }
    }
}
