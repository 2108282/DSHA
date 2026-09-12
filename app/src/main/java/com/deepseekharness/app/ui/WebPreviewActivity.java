package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.WebPreviewPolicy;

/** 标准版预览：系统 WebView、异步鉴权、文件选择与可恢复的加载错误。 */
public class WebPreviewActivity extends AppCompatActivity implements WebFullscreenUi.Host {
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_COOKIE = "cookie";
    // 检查真实页面能力，包括上游 polyfill 的结果，不凭伪装 UA 判断。
    private static final String CAPABILITY_CHECK = "(function(){var m=[];"
            + "if(!('noModule' in document.createElement('script')))m.push('JavaScript modules');"
            + "['Promise','fetch','WebSocket','TextEncoder','ReadableStream','AbortController']"
            + ".forEach(function(k){if(typeof window[k]==='undefined')m.push(k);});"
            + "if(typeof AbortSignal==='undefined'||typeof AbortSignal.any!=='function')m.push('AbortSignal.any');"
            + "if(typeof AbortSignal==='undefined'||typeof AbortSignal.timeout!=='function')m.push('AbortSignal.timeout');"
            + "return m.join(', ');})()";

    private FrameLayout container;
    private View errorPanel;
    private TextView errorTitle;
    private TextView errorDetail;
    private ProgressBar progress;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String authUrl;
    private String authCookie;
    private String baseUrl;
    private String browserInfo = "系统 WebView 版本未知";
    private boolean pageFailed;
    private boolean authRetried;
    private WebDownloads downloads;
    private WebBlobDownload blobDownload;
    private final java.util.ArrayList<java.io.File> uploads = new java.util.ArrayList<>();

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                ValueCallback<Uri[]> callback = fileCallback;
                fileCallback = null;
                if (callback == null) return;
                Uri[] selected = WebChromeClient.FileChooserParams.parseResult(
                        result.getResultCode(), result.getData());
                if (selected == null && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selected = new Uri[]{uri};
                    } else if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        if (count > 0) {
                            selected = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                selected[i] = result.getData().getClipData().getItemAt(i).getUri();
                            }
                        }
                    }
                }
                if (selected == null) {
                    callback.onReceiveValue(null);
                    return;
                }
                final Uri[] chosen = selected;
                final Context app = getApplicationContext();
                new Thread(() -> {
                    java.util.ArrayList<java.io.File> copied = new java.util.ArrayList<>();
                    try {
                        copied = WebUploads.copy(app, java.util.Arrays.asList(chosen));
                        Uri[] local = new Uri[copied.size()];
                        for (int i = 0; i < local.length; i++) {
                            local[i] = androidx.core.content.FileProvider.getUriForFile(
                                    app, app.getPackageName() + ".updates", copied.get(i));
                        }
                        final java.util.ArrayList<java.io.File> ready = copied;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (webView == null || isFinishing() || isDestroyed()) {
                                WebUploads.clean(ready);
                                callback.onReceiveValue(null);
                            } else {
                                uploads.addAll(ready);
                                callback.onReceiveValue(local);
                            }
                        });
                    } catch (Exception error) {
                        WebUploads.clean(copied);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            callback.onReceiveValue(null);
                            Toast.makeText(app, "上传失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }, "web-file-import").start();
            });

    public static Intent intent(Context ctx, String url, String cookie) {
        return new Intent(ctx, WebPreviewActivity.class)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_COOKIE, cookie);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        downloads = new WebDownloads(this, savedInstanceState);
        setContentView(R.layout.activity_web_preview);
        WebFullscreenUi.install(this);
        container = findViewById(R.id.web_container);
        errorPanel = findViewById(R.id.web_error_panel);
        errorTitle = findViewById(R.id.web_error_title);
        errorDetail = findViewById(R.id.web_error_detail);
        progress = findViewById(R.id.web_progress);
        findViewById(R.id.web_retry).setOnClickListener(v -> loadSession());
        findViewById(R.id.web_error_browser).setOnClickListener(v -> openExternal(authUrl));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        authUrl = getIntent().getStringExtra(EXTRA_URL);
        authCookie = getIntent().getStringExtra(EXTRA_COOKIE);
        baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
        if (baseUrl == null) {
            authUrl = null;
            showError("对话地址无效", "请返回启动页，重新进入对话。");
            return;
        }
        if (PreviewFallback.preferred(this) && PreviewFallback.open(this, authUrl, authCookie)) return;
        loadSession();
    }

    private void loadSession() {
        if (baseUrl == null || isFinishing() || isDestroyed()) return;
        destroyWebView();
        pageFailed = false;
        authRetried = false;
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        try {
            WebView view = new WebView(this);
            webView = view;
            boolean dark = ThemeController.isDark(this);
            int themeBg = dark ? Color.parseColor("#10141B") : Color.parseColor("#F7F8FB");
            container.setBackgroundColor(themeBg);
            view.setBackgroundColor(themeBg);
            PackageInfo provider = android.os.Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
            browserInfo = provider == null ? "系统 WebView 版本未知"
                    : provider.packageName + " " + provider.versionName;
            Log.i("DSHA", "标准版预览内核: " + browserInfo);
            com.deepseekharness.app.core.DiagnosticLog.record(this, "WEB_ENGINE", browserInfo);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            // 网页只能获取用户选择后复制到专属 FileProvider 的 URI。
            settings.setAllowContentAccess(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            if (getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                    .getBoolean(Constants.KEY_DESKTOP_MODE, false)) {
                settings.setUserAgentString(WebPreviewPolicy.desktopUserAgent(settings.getUserAgentString()));
            }
            view.setWebViewClient(new PreviewClient());
            view.setWebChromeClient(new PreviewChromeClient());
            view.setDownloadListener((url, agent, disposition, mime, length) -> {
                if (!WebPreviewPolicy.sameService(baseUrl, view.getUrl())) return;
                String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    if (blobDownload == null) blobDownload = new WebBlobDownload(view, downloads.model);
                    blobDownload.start(baseUrl, url, name);
                    return;
                }
                downloads.start(baseUrl, url, CookieManager.getInstance().getCookie(url), name, length, null);
            });
            container.addView(view, new FrameLayout.LayoutParams(-1, -1));
            // 确保总电闸处于推上状态，彻底消除从抽屉切入全屏时被全局 pauseTimers 冻结卡死
            view.resumeTimers();
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(view, false);
            if (authCookie != null && !authCookie.isEmpty()) {
                // setCookie 是异步的：完成后才加载，避免首次进入偶发未认证。
                cookies.setCookie(baseUrl, authCookie + "; Path=/; HttpOnly; SameSite=Lax", ok -> {
                    if (webView != view || isFinishing() || isDestroyed()) return;
                    view.resumeTimers();
                    view.loadUrl(Boolean.TRUE.equals(ok) ? baseUrl : authUrl);
                });
            } else {
                view.loadUrl(authUrl);
            }
        } catch (RuntimeException | LinkageError e) {
            destroyWebView();
            if (PreviewFallback.open(this, authUrl, authCookie)) return;
            Log.w("DSHA", "系统 WebView 初始化失败: " + e.getClass().getSimpleName());
            showError("系统 WebView 无法启动", "请更新或启用 Android System WebView / Chrome，"
                    + "也可以使用系统浏览器进入对话。");
        }
    }

    private class PreviewClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (WebPreviewPolicy.sameService(baseUrl, url)) return false;
            openExternal(url);
            return true;
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // 插件的 iframe / 内嵌预览保持 WebView 原有行为，只接管顶层导航。
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if (WebPreviewPolicy.sameService(baseUrl, url)) return false;
            if (request.hasGesture()) openExternal(url);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (webView != view) return;
            pageFailed = false;
            errorPanel.setVisibility(View.GONE);
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (webView != view || pageFailed) return;
            progress.setVisibility(View.GONE);
            applyThemeToWebView(view);
            if (!WebPreviewPolicy.sameService(baseUrl, url)) return;
            view.evaluateJavascript(CAPABILITY_CHECK, result -> {
                if (webView != view || pageFailed || isFinishing() || isDestroyed()) return;
                try {
                    Object missing = new org.json.JSONTokener(result).nextValue();
                    if (missing instanceof String && !((String) missing).isEmpty()) {
                        if (PreviewFallback.open(WebPreviewActivity.this, authUrl, authCookie)) return;
                        showError("系统 WebView 需要更新", "当前内核缺少：" + missing
                                + "。\n更新 Android System WebView / Chrome 后重试，或在浏览器中打开。");
                    }
                } catch (org.json.JSONException ignored) { }
            });
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (webView != view || !request.isForMainFrame()) return;
            showError("暂时无法连接对话服务", "服务可能仍在启动或已退出。请稍后重试，"
                    + "持续失败时返回启动页查看日志。\n错误代码：" + error.getErrorCode());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (webView != view || !request.isForMainFrame()) return;
            int code = response.getStatusCode();
            if ((code == 401 || code == 403) && !authRetried) {
                authRetried = true;
                authCookie = null;
                view.loadUrl(authUrl);
                return;
            }
            showError(code == 401 || code == 403 ? "对话认证已失效" : "对话页面加载失败",
                    "HTTP " + code + "。请返回启动页重新进入对话，或稍后重试。");
        }

        @androidx.annotation.RequiresApi(26)
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (webView == view) {
                destroyWebView();
                showError("网页渲染进程已退出", detail.didCrash()
                        ? "系统 WebView 发生异常，点击重试可重新打开；持续出现时请更新内核。"
                        : "系统可能因内存不足回收了网页，点击重试可重新打开。");
            }
            return true;
        }
    }

    private class PreviewChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            if (webView == view && !pageFailed) progress.setProgress(value);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            cancelFileSelection();
            if (webView != view || !WebPreviewPolicy.sameService(baseUrl, view.getUrl())) {
                callback.onReceiveValue(null);
                return true;
            }
            fileCallback = callback;
            Intent primary = null;
            try {
                primary = params.createIntent();
                filePicker.launch(primary);
            } catch (RuntimeException e) {
                try {
                    if (primary == null) {
                        primary = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                                .putExtra(Intent.EXTRA_MIME_TYPES, params.getAcceptTypes());
                    }
                    filePicker.launch(WebUploads.fallback(primary));
                } catch (RuntimeException ignored) {
                    cancelFileSelection();
                    Toast.makeText(WebPreviewActivity.this, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    }

    private void showError(String title, String detail) {
        if (isFinishing() || isDestroyed()) return;
        pageFailed = true;
        progress.setVisibility(View.GONE);
        errorTitle.setText(title);
        errorDetail.setText(detail + "\n\n" + browserInfo);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void openExternal(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException e) {
            Toast.makeText(this, "未找到可用的系统浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateBack() {
        if (!pageFailed && webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    private void cancelFileSelection() {
        if (fileCallback == null) return;
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        callback.onReceiveValue(null);
    }

    private void destroyWebView() {
        cancelFileSelection();
        WebUploads.clean(uploads);
        WebView previous = webView;
        webView = null;
        if (previous != null) {
            container.removeView(previous);
            previous.destroy();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        authUrl = intent.getStringExtra(EXTRA_URL);
        authCookie = intent.getStringExtra(EXTRA_COOKIE);
        baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
        if (baseUrl != null) {
            loadSession();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) WebFullscreenUi.hideSystemBars(this);
    }

    @Override protected void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            applyThemeToWebView(webView);
        }
    }

    /** 彻底将网页深色背景对齐 App UI 界面同款深蓝色 (#10141B / #161B24)，消除死黑色 */
    private void applyThemeToWebView(WebView view) {
        if (view == null) return;
        boolean dark = ThemeController.isDark(this);
        int themeBg = dark ? Color.parseColor("#10141B") : Color.parseColor("#F7F8FB");
        if (container != null) container.setBackgroundColor(themeBg);
        view.setBackgroundColor(themeBg);

        if (dark) {
            String css = ":root, [data-ds-dark-theme], .dark, body {\n"
                    + "  --dsw-alias-bg-base: #10141B !important;\n"
                    + "  --dsw-alias-bg-layer-1: #161B24 !important;\n"
                    + "  --dsw-alias-bg-layer-2: #1C2330 !important;\n"
                    + "  --dsw-alias-bg-layer-3: #2A3344 !important;\n"
                    + "  --dsw-specific-sidebar-fill: #10141B !important;\n"
                    + "  --dsh-boot-bg: #10141B !important;\n"
                    + "  --dsw-specific-input-major: #161B24 !important;\n"
                    + "  --dsw-alias-markdown-code-block: #161B24 !important;\n"
                    + "  --dsw-alias-markdown-code-block-banner: #1C2330 !important;\n"
                    + "  --dsw-static-neutral-bluish-950: #10141B !important;\n"
                    + "  --dsw-static-neutral-bluish-900: #161B24 !important;\n"
                    + "  --dsw-static-neutral-bluish-875: #1C2330 !important;\n"
                    + "}\n"
                    + "html, body, #root, [data-ds-dark-theme], main, .dsh-layout-root {\n"
                    + "  background-color: #10141B !important;\n"
                    + "}\n";
            String js = "(function(){\n"
                    + "  var s = document.getElementById('dsha-theme-override');\n"
                    + "  if (!s) {\n"
                    + "    s = document.createElement('style');\n"
                    + "    s.id = 'dsha-theme-override';\n"
                    + "    document.head.appendChild(s);\n"
                    + "  }\n"
                    + "  s.innerHTML = " + org.json.JSONObject.quote(css) + ";\n"
                    + "  document.documentElement.classList.add('dark');\n"
                    + "  document.documentElement.setAttribute('data-theme', 'dark');\n"
                    + "})();";
            view.evaluateJavascript(js, null);
        } else {
            String js = "(function(){\n"
                    + "  var s = document.getElementById('dsha-theme-override');\n"
                    + "  if (s) s.remove();\n"
                    + "  document.documentElement.classList.remove('dark');\n"
                    + "  document.documentElement.setAttribute('data-theme', 'light');\n"
                    + "})();";
            view.evaluateJavascript(js, null);
        }
    }

    @Override protected void onDestroy() {
        if (downloads != null) downloads.dismiss();
        if (blobDownload != null) {
            blobDownload.close();
            blobDownload = null;
        }
        if (webView != null) {
            webView.pauseTimers();
        }
        destroyWebView();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (downloads != null) downloads.model.saveState(out);
        super.onSaveInstanceState(out);
    }
}
