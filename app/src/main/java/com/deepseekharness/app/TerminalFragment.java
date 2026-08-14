package com.deepseekharness.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
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
 * cd / export 等状态保持（真终端体验），无需 Termux。
 */
public class TerminalFragment extends Fragment {

    private HarnessController c;
    private EditText inputEdit;
    private TextView outputText;
    private ScrollView scrollView;
    private Process shell;
    private volatile boolean running = false;
    private final StringBuilder buffer = new StringBuilder();
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
        Button sendBtn = view.findViewById(R.id.term_send);

        sendBtn.setOnClickListener(v -> sendCommand());
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

        appendLine("DSHA 内置终端 · Ubuntu 24.04 (rootfs)");
        appendLine("输入命令回车执行；Ctrl+C 中止；exit 退出会话");
        if (!c.isHarnessInstalled()) {
            appendLine("⚠️ 环境未安装，请先到「安装」页完成安装");
            return;
        }
        startShell();
    }

    private void startShell() {
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

    private void appendRaw(String s) {
        if (outputText == null) return;
        if (buffer.length() > 300000) buffer.setLength(0);
        buffer.append(s);
        String show = buffer.length() > 100000
                ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                : buffer.toString();
        outputText.setText(show);
        if (scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
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
        running = false;
        Process p = shell;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
            shell = null;
        }
    }
}
