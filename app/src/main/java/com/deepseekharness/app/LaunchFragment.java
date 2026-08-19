package com.deepseekharness.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/** 启动模块：启停 dsh，运行中点「进入」用系统浏览器打开；下面刷 Web 日志。 */
public class LaunchFragment extends Fragment {

    private HarnessController c;
    private TextView runDot, runState, statusText, lanAddrText, logText;
    private ScrollView logScroll;
    private Button startBtn, restartBtn, stopBtn;

    private boolean webReady = false;
    private boolean starting = false;
    private String lastLog = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tickOnce;

    private final HarnessController.StateListener stateListener = this::refreshHint;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_launch, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        runDot = view.findViewById(R.id.launch_run_dot);
        runState = view.findViewById(R.id.launch_run_state);
        statusText = view.findViewById(R.id.launch_status);
        lanAddrText = view.findViewById(R.id.lan_addr);
        logText = view.findViewById(R.id.launch_log);
        logScroll = view.findViewById(R.id.launch_log_scroll);
        startBtn = view.findViewById(R.id.launch_start);
        restartBtn = view.findViewById(R.id.launch_open);
        stopBtn = view.findViewById(R.id.launch_stop);

        updateLanAddr();
        applyRunUi(false);
        refreshHint();

        c.addStateListener(stateListener);
        mainHandler.postDelayed(() -> new Thread(() -> {
            try {
                c.ensureWatchdogFiles();
            } catch (Throwable ignored) {
            }
            try {
                c.maybePrewarmWeb();
            } catch (Throwable ignored) {
            }
        }, "dsha-prewarm").start(), 1500);

        startBtn.setOnClickListener(v -> {
            if (webReady) {
                AboutDialog.openBrowser(requireContext(), uiUrl());
                return;
            }
            if (goExtractIfNeeded()) return;
            if (!c.getProot().isOfflineExtracted()) {
                Toast.makeText(requireContext(), "内置环境尚未就绪，请先等解压完成", Toast.LENGTH_LONG).show();
                return;
            }
            starting = true;
            applyRunUi(false);
            statusText.setText("正在启动…");
            Intent i = new Intent(requireContext(), HarnessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
        });

        restartBtn.setOnClickListener(v -> {
            if (goExtractIfNeeded()) return;
            starting = true;
            applyRunUi(false);
            statusText.setText("正在强重启…");
            c.restartAppProcess(requireContext());
        });

        stopBtn.setOnClickListener(v -> {
            starting = false;
            webReady = false;
            applyRunUi(false);
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(i);
            statusText.setText("已发送停止命令");
        });

        if (goExtractIfNeeded()) {
            statusText.setText("正在打开内置环境解压页…");
        } else if (c.getProot().isOfflineExtracted()) {
            statusText.setText("环境已就绪。");
        } else {
            statusText.setText("环境未就绪。若刚装好 APK，请杀掉进程再打开一次以进入解压页。");
        }

        mainHandler.post(tick);
    }

    private void tickOnce() {
        if (!isAdded()) return;
        new Thread(() -> {
            final boolean up = httpOk(uiUrl());
            final String log = readWebLogTail();
            if (!isAdded()) return;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (up) starting = false;
                webReady = up;
                applyRunUi(up);
                if (log != null && !log.equals(lastLog)) {
                    lastLog = log;
                    logText.setText(log.isEmpty() ? "还没有日志。" : log);
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
                mainHandler.postDelayed(tick, 1500);
            });
        }, "dsha-launch-tick").start();
    }

    private void applyRunUi(boolean up) {
        if (up) {
            runDot.setTextColor(requireContext().getColor(R.color.ok));
            runState.setText("DSH 运行中");
            startBtn.setText("进入");
        } else if (starting || c.isWebRunning()) {
            runDot.setTextColor(requireContext().getColor(R.color.warn));
            runState.setText("DSH 启动中");
            startBtn.setText("启动");
        } else {
            runDot.setTextColor(requireContext().getColor(R.color.text_muted));
            runState.setText("DSH 未运行");
            startBtn.setText("启动");
        }
    }

    private void refreshHint() {
        if (!isAdded() || statusText == null) return;
        if (c.getError() != null && !c.getError().isEmpty()) {
            statusText.setText(c.getError());
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            statusText.setText(c.getMessage());
        } else if (c.isBusy()) {
            statusText.setText(c.getStage());
        }
    }

    private String uiUrl() {
        return "http://127.0.0.1:" + c.getPort() + "/";
    }

    private void updateLanAddr() {
        boolean lan = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        if (!lan) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }
        String ip = HarnessController.getLanAddress();
        if (ip == null) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }
        final String copyAddr = "http://" + ip + ":" + c.getPort() + "/";
        lanAddrText.setText("局域网访问: " + copyAddr + "  （同 WiFi 设备可打开）");
        lanAddrText.setVisibility(View.VISIBLE);
        lanAddrText.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("lan", copyAddr));
            Toast.makeText(requireContext(), "局域网地址已复制", Toast.LENGTH_SHORT).show();
        });
    }

    private boolean goExtractIfNeeded() {
        try {
            if (!c.getProot().isOfflineExtracted()) {
                startActivity(new Intent(requireContext(), ExtractActivity.class));
                if (getActivity() != null) getActivity().finish();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean httpOk(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** 直接读宿主上的 rootfs 文件，不进 proot，免得卡界面。 */
    private String readWebLogTail() {
        try {
            File f = new File(c.getProot().getRootfsDir(), "root/dsh-web.log");
            if (!f.isFile() || f.length() == 0) return "";
            long len = f.length();
            long start = Math.max(0, len - 24000);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                String s = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                if (start > 0) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0 && nl + 1 < s.length()) s = s.substring(nl + 1);
                }
                return s;
            }
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(tick);
        if (c != null) c.removeStateListener(stateListener);
    }
}
