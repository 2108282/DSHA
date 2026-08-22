package com.deepseekharness.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
// GeckoView（内置浏览器内核兜底：系统 WebView 过旧时前端 JS 崩 → 白屏转圈）
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/** 启动页：状态 + 日志。点「进入」才在本 App 的 WebView 里打开，不跳系统浏览器。 */
public class LaunchFragment extends Fragment {

    private HarnessController c;
    private TextView runDot, runState, statusText, lanAddrText, logText;
    private ScrollView logScroll;
    private Button startBtn;
    private View homePane, webPane;
    private FrameLayout webBox;
    private WebView webView;

    private boolean webReady = false;
    private boolean starting = false;
    private boolean enterWhenReady = false;
    private boolean insideWeb = false;
    private String lastLog = "";
    /** 日志文件指纹（size+mtime），未变化则跳过重读（每 1.5s 轮询时省一次文件 IO） */
    private long lastLogSize = -1;
    private long lastLogMtime = -1;

    private ValueCallback<Uri[]> filePathCallback;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tickOnce;
    private final HarnessController.StateListener stateListener = this::refreshHint;

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                    filePathCallback = null;
                }
            });

    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            closeWeb();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_launch, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        homePane = view.findViewById(R.id.launch_home);
        webPane = view.findViewById(R.id.launch_web);
        webBox = view.findViewById(R.id.launch_web_box);
        runDot = view.findViewById(R.id.launch_run_dot);
        runState = view.findViewById(R.id.launch_run_state);
        statusText = view.findViewById(R.id.launch_status);
        lanAddrText = view.findViewById(R.id.lan_addr);
        logText = view.findViewById(R.id.launch_log);
        logScroll = view.findViewById(R.id.launch_log_scroll);
        startBtn = view.findViewById(R.id.launch_start);
        Button restartBtn = view.findViewById(R.id.launch_open);
        Button stopBtn = view.findViewById(R.id.launch_stop);

        updateLanAddr();
        applyRunUi(false);
        refreshHint();
        c.addStateListener(stateListener);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backToHome);

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
            // 用户主动启动：清除"手动停止"标记（否则 keepAlive/预启动会一直不拉起）
            requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("keepalive_paused", false).apply();
            if (webReady) {
                openWeb();
                return;
            }
            if (goExtractIfNeeded()) return;
            if (!c.getProot().isOfflineExtracted()) {
                Toast.makeText(requireContext(), "内置环境尚未就绪，请先等解压完成", Toast.LENGTH_LONG).show();
                return;
            }
            starting = true;
            enterWhenReady = true;
            applyRunUi(false);
            statusText.setText("正在启动，起来后直接进入…");
            Intent i = new Intent(requireContext(), HarnessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
        });

        restartBtn.setOnClickListener(v -> {
            if (goExtractIfNeeded()) return;
            closeWeb();
            starting = true;
            enterWhenReady = true; // 重启完成后自动回到预览页
            applyRunUi(false);
            statusText.setText("正在重启…");
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
        });

        stopBtn.setOnClickListener(v -> {
            closeWeb();
            starting = false;
            enterWhenReady = false;
            webReady = false;
            applyRunUi(false);
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(i);
            statusText.setText("正在停止…");
        });

        if (goExtractIfNeeded()) {
            statusText.setText("正在打开内置环境解压页…");
        } else if (c.getProot().isOfflineExtracted()) {
            statusText.setText("环境已就绪。点「启动」起来后会直接进入。");
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
                if (up && enterWhenReady && !insideWeb) {
                    enterWhenReady = false;
                    openWeb();
                }
                if (!insideWeb && log != null && !log.equals(lastLog)) {
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

    @SuppressLint("SetJavaScriptEnabled")
    private void openWeb() {
        if (insideWeb) return;
        insideWeb = true;
        homePane.setVisibility(View.GONE);
        webPane.setVisibility(View.VISIBLE);
        backToHome.setEnabled(true);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(false);
        }
        boolean useGecko = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("gecko_core", false);
        // 自动检测：系统 WebView 过旧（Chrome < 118，前端需要 AbortSignal.any/timeout）
        // → 强制用 GeckoView（内置内核，版本新）。用户也可手动开 gecko_core。
        if (!useGecko) {
            try {
                String ua = WebSettings.getDefaultUserAgent(requireContext());
                java.util.regex.Matcher cm = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua);
                if (cm.find() && Integer.parseInt(cm.group(1)) < 118) {
                    android.util.Log.w("DSHA", "系统 WebView 过旧 (Chrome/" + cm.group(1)
                            + " < 118)，自动切换 GeckoView");
                    useGecko = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (useGecko) {
            // GeckoView 兜底：系统 WebView 过旧（Chrome<118）时前端 JS 崩
            try {
                // 已有 GeckoView（child>0）→ 复用并刷新；否则新建
                GeckoView gv = null;
                for (int i = 0; i < webBox.getChildCount(); i++) {
                    if (webBox.getChildAt(i) instanceof GeckoView) {
                        gv = (GeckoView) webBox.getChildAt(i);
                        break;
                    }
                }
                if (gv == null) {
                    GeckoRuntime runtime = GeckoRuntime.getDefault(requireContext());
                    gv = new GeckoView(requireContext());
                    webBox.addView(gv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                GeckoSession gs = gv.getSession() != null
                        ? gv.getSession() : new GeckoSession();
                if (gv.getSession() == null) {
                    gs.open(GeckoRuntime.getDefault(requireContext()));
                    gv.setSession(gs);
                }
                gs.loadUri(uiUrl());
                return; // GeckoView 加载，不走 WebView
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "GeckoView 启动失败，回退 WebView: " + e);
            }
        }
        if (webView == null) {
            webView = new WebView(requireContext());
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            // 现代前端特性：混合内容（http 页面加载资源）+ 数据库 + 多窗口
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            ws.setDatabaseEnabled(true);
            ws.setSupportMultipleWindows(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);
            boolean desktop = requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean("desktop_mode", false);
            if (desktop) {
                ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            }
            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                                 FileChooserParams params) {
                    filePathCallback = cb;
                    String[] accept = params.getAcceptTypes();
                    String mime = (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty())
                            ? accept[0] : "*/*";
                    pickFile.launch(mime);
                    return true;
                }
            });
            webBox.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        webView.loadUrl(uiUrl());
    }

    private void closeWeb() {
        if (!insideWeb) return;
        insideWeb = false;
        webPane.setVisibility(View.GONE);
        homePane.setVisibility(View.VISIBLE);
        backToHome.setEnabled(false);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
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
        // 注意：局域网访问走 App 侧 LanProxyService 桥（端口 3081 → 后端 3080），
        // 不是直连 3080（直连需要 lan-bind 补丁成功且 CLI 放行 0.0.0.0，不可靠）
        final String copyAddr = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/";
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

    private String readWebLogTail() {
        try {
            File f = new File(c.getProot().getRootfsDir(), "root/dsh-web.log");
            if (!f.isFile() || f.length() == 0) return "";
            // 指纹未变：跳过重读（省 IO；日志不写时每 1.5s 轮询零成本）
            if (f.lastModified() == lastLogMtime && f.length() == lastLogSize) return lastLog;
            lastLogMtime = f.lastModified();
            lastLogSize = f.length();
            long len = f.length();
            long start = Math.max(0, len - 24000);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                // readFully 可能因日志被截断抛 EOF：改用尽力读
                int off = 0;
                while (off < buf.length) {
                    int n = raf.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                String s = new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
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
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
        if (webView != null) {
            webBox.removeAllViews();
            webView.destroy();
            webView = null;
        }
    }
}
