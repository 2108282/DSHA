package com.deepseekharness.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.net.HttpURLConnection;
import java.net.URL;

/** 启动模块：启动/重启/停止 Web UI；启动后自动检测就绪并弹出预览 */
public class LaunchFragment extends Fragment {

    private HarnessController c;
    private WebView webView;
    private TextView statusText;
    private Button startBtn, restartBtn, stopBtn;
    private LinearLayout controls;
    private boolean fullscreen = false;
    private boolean polling = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            exitFullscreen();
        }
    };

    private final HarnessController.StateListener stateListener = this::refreshFromState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_launch, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        webView = view.findViewById(R.id.webview);
        statusText = view.findViewById(R.id.launch_status);
        startBtn = view.findViewById(R.id.launch_start);
        restartBtn = view.findViewById(R.id.launch_open);
        stopBtn = view.findViewById(R.id.launch_stop);
        controls = view.findViewById(R.id.launch_controls);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        c.addStateListener(stateListener);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        startBtn.setOnClickListener(v -> {
            if (!c.isHarnessInstalled()) {
                Toast.makeText(requireContext(), "请先在「安装」模块完成安装", Toast.LENGTH_LONG).show();
                return;
            }
            // 通过前台服务启动：强保活 + 后台运行（切走不杀）
            Intent i = new Intent(requireContext(), HarnessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
            statusText.setText("正在启动 Web UI，检测到就绪后自动打开预览…");
            pollWebReady();
        });

        restartBtn.setOnClickListener(v -> {
            if (!c.isHarnessInstalled()) {
                Toast.makeText(requireContext(), "请先完成安装", Toast.LENGTH_LONG).show();
                return;
            }
            exitFullscreen();
            statusText.setText("正在重启 Web UI…");
            Intent stop = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(stop);
            // 稍等进程退出，再重新拉起
            mainHandler.postDelayed(() -> {
                Intent i = new Intent(requireContext(), HarnessService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(i);
                } else {
                    requireContext().startService(i);
                }
                statusText.setText("正在重启 Web UI，检测到就绪后自动打开预览…");
                pollWebReady();
            }, 1500);
        });

        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(i);
            exitFullscreen();
            statusText.setText("已发送停止命令");
        });

        // 切模块回来：如果 Web 还在跑，自动恢复全屏预览
        if (c.isWebRunning()) {
            webView.post(this::openPreview);
        } else {
            statusText.setText("提示：先到「安装」页完成安装，再回到这里启动。");
        }
    }

    /** 轮询检测 WebUI 就绪（HTTP 200），就绪后自动打开预览 */
    private void pollWebReady() {
        if (polling) return;
        polling = true;
        final String url = "http://127.0.0.1:" + c.getPort() + "/";
        new Thread(() -> {
            boolean ok = false;
            for (int i = 0; i < 60; i++) { // 最多约 2 分钟
                if (!c.isWebRunning() && !ok) {
                    // 服务进程都没了就别等了（除非刚重启还在拉起中，给它几轮机会）
                    if (i > 20) break;
                }
                if (httpOk(url)) {
                    ok = true;
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            polling = false;
            if (ok && isAdded()) {
                mainHandler.post(this::openPreview);
            } else if (isAdded()) {
                mainHandler.post(() ->
                        statusText.setText("等待 Web UI 就绪超时，可点「重启」再试，或检查日志"));
            }
        }).start();
    }

    private boolean httpOk(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void openPreview() {
        String url = "http://127.0.0.1:" + c.getPort() + "/";
        webView.loadUrl(url);
        enterFullscreen();
    }

    private void enterFullscreen() {
        fullscreen = true;
        backCallback.setEnabled(true);
        controls.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(false);
        }
        View decor = getActivity() != null ? getActivity().getWindow().getDecorView() : null;
        if (decor != null) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void exitFullscreen() {
        fullscreen = false;
        backCallback.setEnabled(false);
        controls.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (c != null) c.removeStateListener(stateListener);
        // 退出 Fragment 时恢复底部导航
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
    }

    private void refreshFromState() {
        if (!isAdded()) return;
        if (c.getError() != null && !c.getError().isEmpty()) {
            statusText.setText(c.getError());
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            statusText.setText(c.getMessage());
        } else if (c.isBusy()) {
            statusText.setText(c.getStage());
        }
    }
}
