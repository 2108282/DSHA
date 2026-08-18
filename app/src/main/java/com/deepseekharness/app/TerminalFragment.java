package com.deepseekharness.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内置终端：直接挂到 proot 的持久 bash 会话上。
 * 历史输出与 bash 会话跨页面保留（静态持有，切页/返回不清空），仅「清理」按钮手动清空。
 */
public class TerminalFragment extends Fragment {

    private HarnessController c;
    private EditText inputEdit;
    private TextView outputText;
    private ScrollView scrollView;

    // ===== 静态：跨 Fragment 重建保留 =====
    private static volatile Process shell;
    private static volatile boolean running = false;
    private static volatile Thread readerThread;
    private static final StringBuilder buffer = new StringBuilder();
    private static volatile TextView boundOutput; // 当前绑定的输出视图；null=无界面（会话继续后台跑）

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        inputEdit = view.findViewById(R.id.term_input);
        outputText = view.findViewById(R.id.term_output);
        scrollView = view.findViewById(R.id.term_scroll);
        // 绑定当前 UI（历史/后续输出写到这里）
        boundOutput = outputText;

        TextView ctrlcBtn = view.findViewById(R.id.term_ctrlc);
        ctrlcBtn.setOnClickListener(v -> {
            Process p = shell;
            if (p != null && p.isAlive()) {
                try {
                    p.getOutputStream().write(3); // Ctrl+C
                    p.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            }
        });
        TextView clearBtn = view.findViewById(R.id.term_clear);
        clearBtn.setOnClickListener(v -> {
            buffer.setLength(0);
            outputText.setText("Ubuntu 24.04 · 回车执行 · 中止 · exit 退出\n");
        });
        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendCommand();
                return true;
            }
            return false;
        });

        // 重绘历史（静态 buffer 切页后仍在）
        String show = buffer.length() == 0 ? "" : buffer.toString();
        outputText.setText(show.isEmpty() ? "Ubuntu 24.04 · 回车执行 · 中止 · exit 退出" : show);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        if (!c.isHarnessInstalled()) {
            appendLine("环境未安装，请先到「安装」页完成安装");
            return;
        }
        startShell();
    }

    private void startShell() {
        c.ensureDangerGuard(); // 危险确认包装器缺失则自动补装（装新 APK 后无需重装第 4 步）
        Process p = shell;
        if (p != null && p.isAlive() && readerThread != null && readerThread.isAlive()) {
            return; // 会话已在后台跑，本页只是重新绑定输出视图
        }
        new Thread(() -> {
            try {
                shell = c.getProot().execRootfsInteractive();
                running = true;
                byte[] buf = new byte[8192];
                InputStream in = shell.getInputStream();
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    final String chunk = stripAnsi(new String(buf, 0, n, StandardCharsets.UTF_8));
                    mainHandler.post(() -> appendRaw(chunk));
                }
                mainHandler.post(() -> appendLine("\n[会话已退出]"));
            } catch (Exception e) {
                mainHandler.post(() -> appendLine("终端启动失败：" + e.getMessage()));
            }
        }, "term-read").start();
    }

    private void sendCommand() {
        String cmd = inputEdit.getText().toString().trim();
        if (cmd.isEmpty()) return;
        inputEdit.setText("");
        appendLine("$ " + cmd);
        Process p = shell;
        if (p == null || !p.isAlive()) {
            appendLine("会话未运行，正在重启…");
            startShell();
            return;
        }
        try {
            p.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
        } catch (IOException e) {
            appendLine("发送失败：" + e.getMessage());
        }
    }

    private void appendLine(String s) {
        appendRaw(s + "\n");
    }

    /** 始终写静态 buffer（切页后继续累积，否则历史丢）；有绑定视图才刷 UI */
    private void appendRaw(String s) {
        if (s == null || s.isEmpty()) return;
        buffer.append(s);
        if (buffer.length() > 300000) buffer.setLength(0); // 过长截断，仍可手动清理
        TextView out = boundOutput;
        if (out == null) return;
        String show = buffer.length() > 100000
                ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                : buffer.toString();
        out.setText(show);
        ScrollView sv = scrollView;
        if (sv != null) {
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 去掉 ANSI 转义序列（保留可读文本） */
    private static String stripAnsi(String s) {
        return s.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "")
                .replaceAll("\\x1B\\][^\\x07]*\\x07", "")
                .replaceAll("\\x1B[()][0-9A-B]", "");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 只解绑视图，不杀会话、不清 buffer —— 换页/返回历史保留
        boundOutput = null;
    }
}
