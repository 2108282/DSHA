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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.net.HttpURLConnection;
import java.net.URL;

/** 启动模块：启动/重启/停止 Web UI；启动后自动检测就绪并弹出预览（GeckoView 内核 + 文件上传支持） */
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
    /** 记录上次加载时 web 进程代际，检测到 web 重启则刷新预览；硬重启则重建会话 */
    private long lastWebEpoch = -1;
    private long lastHardEpoch = -1;

    // ===== Web 文件上传支持（onShowFileChooser / Gecko onFilePrompt） =====
    private ValueCallback<Uri[]> mFilePathCallback;
    private org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt mPendingFilePrompt;
    private org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse> mPendingFileResult;
    private final ActivityResultLauncher<String> pickFileLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    uri -> onFilePickResult(uri, null));
    private final ActivityResultLauncher<String> pickFilesLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(),
                    list -> onFilePickResult(null, list));

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            exitFullscreen();
        }
    };

    /** 初始化预览内核：强制内置 GeckoView（更稳、功能全）；异常回退系统 WebView */
    @SuppressLint("SetJavaScriptEnabled")
    private void initPreview() {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
        boolean useGecko = true; // 强制 GeckoView（不读开关）
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
                // 普通模式（恢复磁盘缓存 → WebUI 二次打开秒开）；
                // 插件更新由 host rev（内容哈希）变化驱动 URL 变化，无需禁缓存
                org.mozilla.geckoview.GeckoSessionSettings gsettings =
                        new org.mozilla.geckoview.GeckoSessionSettings.Builder()
                                .usePrivateMode(false)
                                .build();
                geckoSession = new org.mozilla.geckoview.GeckoSession(gsettings);
                geckoSession.open(geckoRuntime);
                geckoView.setSession(geckoSession);
                // 文件上传：GeckoView 走 PromptDelegate.onFilePrompt，否则网页文件选择无反应
                geckoSession.setPromptDelegate(new org.mozilla.geckoview.GeckoSession.PromptDelegate() {
                    @Override
                    public org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse>
                            onFilePrompt(org.mozilla.geckoview.GeckoSession session,
                                         org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt filePrompt) {
                        org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse> result =
                                new org.mozilla.geckoview.GeckoResult<>();
                        mPendingFilePrompt = filePrompt;
                        mPendingFileResult = result;
                        pickFilesLauncher.launch("*/*"); // 多选契约兼容单选
                        return result;
                    }
                });
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
        // 文件上传：无此回调时网页 <input type=file> 点击会被静默忽略
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                mFilePathCallback = filePathCallback;
                String[] accept = fileChooserParams.getAcceptTypes();
                String mime = (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty())
                        ? accept[0] : "*/*";
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pickFilesLauncher.launch(mime);
                } else {
                    pickFileLauncher.launch(mime);
                }
                return true;
            }
        });
    }

    /** 文件选择结果统一分发：WebView 回传 Uri[]，GeckoView confirm()/dismiss() 完成 PromptResponse */
    private void onFilePickResult(Uri single, java.util.List<Uri> multiple) {
        Uri[] uris = null;
        if (multiple != null && !multiple.isEmpty()) {
            uris = multiple.toArray(new Uri[0]);
        } else if (single != null) {
            uris = new Uri[]{single};
        }
        if (mFilePathCallback != null) {
            mFilePathCallback.onReceiveValue(uris);
            mFilePathCallback = null;
        }
        if (mPendingFileResult != null) {
            try {
                if (mPendingFilePrompt != null && uris != null) {
                    mPendingFileResult.complete(mPendingFilePrompt.confirm(getActivity(), uris));
                } else if (mPendingFilePrompt != null) {
                    // 用户取消：必须回 dismiss 响应（传 null 会触发 Gecko NPE → 壳 App 闪退回桌面）
                    mPendingFileResult.complete(mPendingFilePrompt.dismiss());
                } else {
                    mPendingFileResult.complete(null);
                }
            } catch (Throwable e) {
                try {
                    mPendingFileResult.completeExceptionally(e);
                } catch (Throwable ignored) {
                }
            }
            mPendingFilePrompt = null;
            mPendingFileResult = null;
        }
    }

    /** 加载预览 URL（按当前内核分发） */
    private void loadPreview(String url) {
        if (webView != null) {
            webView.loadUrl(url);
        } else if (geckoSession != null) {
            geckoSession.load(new org.mozilla.geckoview.GeckoSession.Loader().uri(url));
        }
    }

    /** 局域网访问地址显示（lan_mode 开启且检测到 IP 时显示直连地址，点击复制） */
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
        String port = c.getPort();
        final String copyAddr = "http://" + ip + ":" + port + "/";
        lanAddrText.setText("局域网访问: " + copyAddr + "  （同 WiFi 设备可打开）");
        lanAddrText.setVisibility(View.VISIBLE);
        lanAddrText.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("lan", copyAddr));
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
        // 未启动 WebUI 时隐藏空预览容器，避免首次进入即白屏
        if (previewContainer != null) previewContainer.setVisibility(View.GONE);
        // 自动后台预启动：进入启动页后 1.5s 静默拉起 web（环境就绪且用户未手动停止时）→ 点启动秒开
        mainHandler.postDelayed(() -> c.maybePrewarmWeb(), 1500);
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
            statusText.setText("正在强重启（先停透 web 再重启应用）…");
            // 强重启：先深停 node（避免孤儿残留占端口）→ 杀 App 进程 → 全新进程拉起
            c.restartAppProcess(requireContext());
        });

        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(i);
            exitFullscreen();
            statusText.setText("已发送停止命令");
        });

        // 切模块回来：如果 Web 还在跑，自动恢复全屏预览（按当前内核分发，避免 webView null）
        if (c.isWebRunning()) {
            if (webView != null) {
                webView.post(this::openPreview);
            } else if (geckoView != null) {
                geckoView.post(this::openPreview);
            } else {
                openPreview();
            }
        } else if (goExtractIfNeeded()) {
            statusText.setText("正在打开内置环境解压页…");
        } else if (c.isHarnessInstalled()) {
            statusText.setText("环境已就绪，点「启动」即可。");
        } else {
            statusText.setText("环境未就绪。若刚装好 APK，请杀掉进程再打开一次以进入解压页。");
        }
    }

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

    /** 轮询检测 WebUI 就绪（HTTP 200），就绪后自动打开预览；构建中/超时给出诊断 */
    private void pollWebReady() {
        if (polling) return;
        polling = true;
        final String url = "http://127.0.0.1:" + c.getPort() + "/";
        new Thread(() -> {
            boolean ok = false;
            long lastHint = 0;
            for (int i = 0; i < 180; i++) { // 最多约 6 分钟
                if (!c.isWebRunning() && !ok) {
                    if (i > 40) break; // 服务进程都没了就别等了
                }
                if (httpOk(url)) {
                    ok = true;
                    break;
                }
                // 自动补构建中：给可见进度（节流 5 秒），避免“启动半天没反应”
                if (c.isBuilding()) {
                    long now = System.currentTimeMillis();
                    if (now - lastHint > 5000) {
                        lastHint = now;
                        final String msg = "检测到构建产物缺失，正在自动构建 deepseek-harness（手机较慢，约需几分钟，请稍候）…";
                        if (isAdded()) mainHandler.post(() -> statusText.setText(msg));
                    }
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
                // 超时：自动读日志给关键报错，而不是只让用户“检查日志”
                final String diag = c.diagnoseWebFailure();
                mainHandler.post(() -> statusText.setText("Web UI 未就绪：\n" + diag));
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
            if (previewContainer != null) previewContainer.setVisibility(View.VISIBLE);
            String url = "http://127.0.0.1:" + c.getPort() + "/";
            loadPreview(url);
            enterFullscreen();
        } catch (Throwable t) {
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
            decor.setBackgroundColor(android.graphics.Color.BLACK);
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
        // web 重启（如插件变更后自动重启）→ 重新加载预览，否则页面还是旧的、插件“加载不出来”
        maybeReloadPreviewIfWebRestarted();
        // 硬重启（等价重启 APP）→ 重建预览内核会话（全新 JS 引擎，插件必然生效）
        maybeRebuildPreviewIfHardRestarted();
    }

    /** 检测 web 进程代际变化：变了且有预览 → 重新 loadUrl（拿最新 manifest/插件） */
    private void maybeReloadPreviewIfWebRestarted() {
        try {
            long e = c.getWebEpoch();
            if (e == lastWebEpoch) return;
            lastWebEpoch = e;
            boolean hasPreview = webView != null || geckoSession != null;
            if (hasPreview && c.isWebRunning()) {
                loadPreview("http://127.0.0.1:" + c.getPort() + "/");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 硬重启检测：重建预览内核（removeAllViews + 新 session + 重载），完全等价冷启动 */
    private void maybeRebuildPreviewIfHardRestarted() {
        try {
            long h = c.getHardRestartEpoch();
            if (h == lastHardEpoch) return;
            lastHardEpoch = h;
            if (h > 0 && previewContainer != null && (webView != null || geckoSession != null)) {
                previewContainer.removeAllViews();
                initPreview();
                previewContainer.setVisibility(View.VISIBLE);
                loadPreview("http://127.0.0.1:" + c.getPort() + "/");
            }
        } catch (Throwable ignored) {
        }
    }
}
