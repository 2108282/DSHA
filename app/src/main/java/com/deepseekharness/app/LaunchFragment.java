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
import android.widget.FrameLayout;
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
    private FrameLayout previewContainer;
    private org.mozilla.geckoview.GeckoView geckoView;
    private org.mozilla.geckoview.GeckoSession geckoSession;
    private org.mozilla.geckoview.GeckoRuntime geckoRuntime;
    private TextView statusText;
    private TextView lanAddrText;
    private Button startBtn, restartBtn, stopBtn;
    private LinearLayout controls;
    private boolean fullscreen = false;
    private boolean polling = false;
    private boolean previewBusy = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            exitFullscreen();
        }
    };

    /** 根据设置初始化预览内核：系统 WebView 或内置 GeckoView；电脑模式设置桌面 UA */
    @SuppressLint("SetJavaScriptEnabled")
    private void initPreview() {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
        boolean useGecko = prefs.getBoolean("gecko_core", false);
        boolean desktopMode = prefs.getBoolean("desktop_mode", false);
        previewContainer.removeAllViews();
        if (useGecko) {
            try {
                webView = null;
                geckoView = new org.mozilla.geckoview.GeckoView(requireContext());
                previewContainer.addView(geckoView,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (geckoRuntime == null) {
                    geckoRuntime = org.mozilla.geckoview.GeckoRuntime.create(requireContext());
                }
                geckoSession = new org.mozilla.geckoview.GeckoSession();
                geckoSession.open(geckoRuntime);
                geckoView.setSession(geckoSession);
            } catch (Throwable e) {
                // GeckoView 初始化失败（如 so 加载异常）回退系统 WebView
                geckoView = null;
                geckoSession = null;
                webView = new WebView(requireContext());
                setupWebView(webView, desktopMode);
                previewContainer.addView(webView,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else {
            geckoView = null;
            geckoSession = null;
            webView = new WebView(requireContext());
            setupWebView(webView, desktopMode);
            previewContainer.addView(webView,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void setupWebView(WebView wv, boolean desktopMode) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        if (desktopMode) {
            wv.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        }
        wv.setWebViewClient(new WebViewClient());
    }

    /** 加载预览 URL（按当前内核分发） */
    private void loadPreview(String url) {
        if (webView != null) {
            webView.loadUrl(url);
        } else if (geckoSession != null) {
            geckoSession.load(new org.mozilla.geckoview.GeckoSession.Loader().uri(url));
        }
    }

    /** 局域网访问地址显示（lan_mode 开启且检测到 IP 时显示，点击复制） */
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
        String addr = "http://" + ip + ":" + LanProxyService.LAN_PORT + "   （App桥，局域网设备可访问）";
        lanAddrText.setText(addr);
        lanAddrText.setVisibility(View.VISIBLE);
        lanAddrText.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("lan", "http://" + ip + ":" + LanProxyService.LAN_PORT));
            Toast.makeText(requireContext(), "局域网地址已复制", Toast.LENGTH_SHORT).show();
        });
    }

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
        previewContainer = view.findViewById(R.id.previewContainer);
        statusText = view.findViewById(R.id.launch_status);
        lanAddrText = view.findViewById(R.id.lan_addr);
        startBtn = view.findViewById(R.id.launch_start);
        restartBtn = view.findViewById(R.id.launch_open);
        stopBtn = view.findViewById(R.id.launch_stop);
        controls = view.findViewById(R.id.launch_controls);

        initPreview();
        updateLanAddr();
        // 刷新看门狗命令文件（覆盖历史坏命令，避免旧 watchdog 用空端口 restart 反复失败）
        try {
            c.ensureWatchdogFiles();
        } catch (Throwable ignored) {
        }

        c.addStateListener(stateListener);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        startBtn.setOnClickListener(v -> {
            if (goExtractIfNeeded()) return;
            if (!c.isHarnessInstalled()) {
                Toast.makeText(requireContext(), "内置环境尚未就绪，请先等解压完成", Toast.LENGTH_LONG).show();
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
            if (goExtractIfNeeded()) return;
            if (!c.isHarnessInstalled()) {
                Toast.makeText(requireContext(), "内置环境尚未就绪，请先等解压完成", Toast.LENGTH_LONG).show();
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
        } else if (goExtractIfNeeded()) {
            statusText.setText("正在打开内置环境解压页…");
        } else if (c.isHarnessInstalled()) {
            statusText.setText("环境已就绪，点「启动」即可。");
        } else {
            statusText.setText("环境未就绪。若刚装好 APK，请杀掉进程再打开一次以进入解压页。");
        }
    }

    /** 有内置包但还没落地 → 进解压页。返回 true 表示已经跳走。 */
    private boolean goExtractIfNeeded() {
        try {
            ProotBootstrap p = c.getProot();
            if (!p.isOfflineExtracted()) {
                startActivity(new Intent(requireContext(), ExtractActivity.class));
                if (getActivity() != null) getActivity().finish();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 轮询检测 WebUI 就绪（HTTP 200），就绪后自动打开预览 */
    private void pollWebReady() {
        if (polling) return;
        polling = true;
        final String url = "http://127.0.0.1:" + c.getPort() + "/";
        new Thread(() -> {
            boolean ok = false;
            for (int i = 0; i < 180; i++) { // 最多约 6 分钟（首次 RC6 启动较慢）
                if (!c.isWebRunning() && !ok) {
                    // 服务进程都没了就别等了（除非刚启动拉起中），放宽到 80 秒后才判死
                    if (i > 40) break;
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
        // 防重入：openPreview → loadPreview → loadUrl 反复触发（多入口 post 叠加）会递归爆栈
        if (previewBusy) return;
        previewBusy = true;
        try {
            String url = "http://127.0.0.1:" + c.getPort() + "/";
            loadPreview(url);
            enterFullscreen();
        } catch (Throwable t) {
            // 预览闪避：内核异常不拖垮 App
            try {
                statusText.setText("预览加载异常，可点「重启」再试");
            } catch (Throwable ignored) {
            }
        } finally {
            previewBusy = false;
        }
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
