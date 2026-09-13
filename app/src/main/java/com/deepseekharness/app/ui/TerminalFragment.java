/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  android.os.Bundle
 *  android.os.Handler
 *  android.os.Looper
 *  android.view.LayoutInflater
 *  android.view.View
 *  android.view.ViewGroup
 *  android.view.ViewParent
 *  android.widget.EditText
 *  android.widget.ScrollView
 *  android.widget.TextView
 *  android.widget.Toast
 *  androidx.annotation.NonNull
 *  androidx.annotation.Nullable
 *  androidx.fragment.app.Fragment
 *  com.deepseekharness.app.R$id
 *  com.deepseekharness.app.R$layout
 */
package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.PtyTerminalFragment;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class TerminalFragment
extends Fragment {
    private HarnessController c;
    private EditText inputEdit;
    private TextView outputText;
    private ScrollView scrollView;
    private static volatile Process shell;
    private static volatile boolean running;
    private static volatile Thread readerThread;
    private static volatile boolean shellStarting;
    private static final StringBuilder buffer;
    private static volatile TextView boundOutput;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        this.c = new HarnessController(this.requireContext());
        this.inputEdit = (EditText)view.findViewById(R.id.term_input);
        this.outputText = (TextView)view.findViewById(R.id.term_output);
        this.scrollView = (ScrollView)view.findViewById(R.id.term_scroll);
        boundOutput = this.outputText;
        TextView ctrlcBtn = (TextView)view.findViewById(R.id.term_ctrlc);
        ctrlcBtn.setOnClickListener(v -> {
            Process p = shell;
            if (p != null && Compat.isAlive(p)) {
                try {
                    p.getOutputStream().write(3);
                    p.getOutputStream().flush();
                }
                catch (IOException iOException) {
                    // empty catch block
                }
            }
        });
        TextView clearBtn = (TextView)view.findViewById(R.id.term_clear);
        clearBtn.setOnClickListener(v -> {
            buffer.setLength(0);
            this.outputText.setText((CharSequence)"Ubuntu 24.04 \u00b7 \u56de\u8f66\u6267\u884c \u00b7 \u4e2d\u6b62 \u00b7 exit \u9000\u51fa\n");
        });
        View ptyBtn = view.findViewById(R.id.term_pty);
        if (ptyBtn != null) {
            ptyBtn.setOnClickListener(v -> this.switchToPty());
        }
        this.inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == 4 || actionId == 2 || actionId == 6 || event != null && event.getKeyCode() == 66) {
                this.sendCommand();
                return true;
            }
            return false;
        });
        String show = buffer.length() == 0 ? "" : SensitiveData.redact(buffer.toString());
        this.outputText.setText((CharSequence)(show.isEmpty() ? "Ubuntu 24.04 \u00b7 \u56de\u8f66\u6267\u884c \u00b7 \u4e2d\u6b62 \u00b7 exit \u9000\u51fa" : show));
        this.scrollView.post(() -> this.scrollView.fullScroll(130));
        this.startShell();
    }

    private void switchToPty() {
        this.requireContext().getSharedPreferences("deepseekharness", 0).edit().putBoolean("term_pty", true).apply();
        try {
            int containerId = ((ViewGroup)this.requireView().getParent()).getId();
            this.getParentFragmentManager().beginTransaction().replace(containerId, (Fragment)new PtyTerminalFragment()).commit();
        }
        catch (Throwable e) {
            Toast.makeText((Context)this.requireContext(), (CharSequence)"\u8bf7\u9000\u51fa\u7ec8\u7aef\u9875\u518d\u8fdb\u6765", (int)0).show();
        }
    }

    private void startShell() {
        Process p = shell;
        if (p != null && Compat.isAlive(p) && readerThread != null && readerThread.isAlive()) {
            return;
        }
        if (shellStarting) {
            return;
        }
        shellStarting = true;
        new Thread(() -> {
            try {
                if (!this.c.proot().isEnvironmentReady()) {
                    this.mainHandler.post(() -> this.appendLine("未检测到 /data/adb/dsha 原生模块或未授予 Root 权限，请在授权管理器中允许"));
                    return;
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                int n;
                shell = this.c.proot().execRootfsInteractive();
                running = true;
                InputStreamReader reader = new InputStreamReader(shell.getInputStream(), StandardCharsets.UTF_8);
                char[] cbuf = new char[4096];
                while (running && (n = reader.read(cbuf)) != -1) {
                    String chunk = TerminalFragment.stripAnsi(new String(cbuf, 0, n));
                    this.mainHandler.post(() -> this.appendRaw(chunk));
                }
                this.mainHandler.post(() -> this.appendLine("\n[\u4f1a\u8bdd\u5df2\u9000\u51fa]"));
            }
            catch (Exception e) {
                this.mainHandler.post(() -> this.appendLine("\u7ec8\u7aef\u542f\u52a8\u5931\u8d25\uff1a" + SensitiveData.redact(String.valueOf(e))));
            }
            finally {
                shellStarting = false;
            }
        }, "term-read").start();
    }

    private void sendCommand() {
        String cmd = this.inputEdit.getText().toString().trim();
        if (cmd.isEmpty()) {
            return;
        }
        this.inputEdit.setText((CharSequence)"");
        this.appendLine("$ " + cmd);
        Process p = shell;
        if (p == null || !Compat.isAlive(p)) {
            this.appendLine("\u4f1a\u8bdd\u672a\u8fd0\u884c\uff0c\u6b63\u5728\u91cd\u542f\u2026");
            this.startShell();
            return;
        }
        try {
            p.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
        }
        catch (IOException e) {
            this.appendLine("\u53d1\u9001\u5931\u8d25\uff1a" + SensitiveData.redact(String.valueOf(e)));
        }
    }

    private void appendLine(String s) {
        this.appendRaw(s + "\n");
    }

    private void appendRaw(String s) {
        TextView out;
        if (s == null || s.isEmpty()) {
            return;
        }
        s = SensitiveData.redact(s);
        buffer.append(s);
        if (buffer.length() > 300000) {
            buffer.delete(0, buffer.length() - 100000);
        }
        if ((out = boundOutput) == null) {
            return;
        }
        String show = buffer.length() > 100000 ? "\u2026\uff08\u8f93\u51fa\u8fc7\u957f\u5df2\u622a\u65ad\uff09\n" + buffer.substring(buffer.length() - 100000) : buffer.toString();
        out.setText((CharSequence)show);
        ScrollView sv = this.scrollView;
        if (sv != null) {
            sv.post(() -> sv.fullScroll(130));
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "").replaceAll("\\x1B\\][^\\x07]*\\x07", "").replaceAll("\\x1B[()][0-9A-B]", "");
    }

    public void onDestroyView() {
        super.onDestroyView();
        boundOutput = null;
    }

    public static void shutdownShell() {
        Process p = shell;
        if (p != null) {
            try {
                p.getOutputStream().write("exit\n".getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
            }
            catch (IOException iOException) {
                // empty catch block
            }
            try {
                Compat.destroy(p);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        running = false;
        shell = null;
        shellStarting = false;
    }

    public static void inject(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String safeText = SensitiveData.redact(text);
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            Class<TerminalFragment> clazz = TerminalFragment.class;
            synchronized (TerminalFragment.class) {
                ViewParent p;
                TextView out;
                buffer.append(safeText);
                if (!safeText.endsWith("\n")) {
                    buffer.append('\n');
                }
                if (buffer.length() > 300000) {
                    buffer.delete(0, buffer.length() - 100000);
                }
                if ((out = boundOutput) == null) {
                    // ** MonitorExit[var1_1] (shouldn't be in output)
                    return;
                }
                String show = buffer.length() > 100000 ? "\u2026\uff08\u8f93\u51fa\u8fc7\u957f\u5df2\u622a\u65ad\uff09\n" + buffer.substring(buffer.length() - 100000) : buffer.toString();
                out.setText((CharSequence)show);
                for (p = out.getParent(); p != null && !(p instanceof ScrollView); p = p.getParent()) {
                }
                if (p instanceof ScrollView) {
                    ScrollView sv = (ScrollView)p;
                    sv.post(() -> sv.fullScroll(130));
                }
                // ** MonitorExit[var1_1] (shouldn't be in output)
                return;
            }
        });
    }

    static {
        running = false;
        shellStarting = false;
        buffer = new StringBuilder();
    }
}
