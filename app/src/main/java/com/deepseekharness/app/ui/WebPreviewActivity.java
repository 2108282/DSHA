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

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                ValueCallback<Uri[]> callback = fileCallback;
                fileCallback = null;
                if (callback == null) return;
                Uri[] selected = WebChromeClient.FileChooserParams.parseResult(
                        result.getResultCode(), result.getData());
                if (selected != null) {
                    for (Uri uri : selected) {
                        // 只接收内容 URI，不向网页开放任意本地文件路径。
                        if (uri == null || !"content".equals(uri.getScheme())) {
                            selected = null;
                            break;
                        }
                    }
                }
                callback.onReceiveValue(selected);
            });

    public static Intent intent(Context ctx, String url, String cookie) {
        return new Intent(ctx, WebPreviewActivity.class)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_COOKIE, cookie);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            PackageInfo provider = android.os.Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
            browserInfo = provider == null ? "系统 WebView 版本未知"
                    : provider.packageName + " " + provider.versionName;
            Log.i("DSHA", "标准版预览内核: " + browserInfo);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
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
            container.addView(view, new FrameLayout.LayoutParams(-1, -1));
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(view, false);
            if (authCookie != null && !authCookie.isEmpty()) {
                // setCookie 是异步的：完成后才加载，避免首次进入偶发未认证。
                cookies.setCookie(baseUrl, authCookie + "; Path=/; HttpOnly; SameSite=Strict", ok -> {
                    if (webView != view || isFinishing() || isDestroyed()) return;
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
            try {
                filePicker.launch(params.createIntent());
            } catch (RuntimeException e) {
                cancelFileSelection();
                Toast.makeText(WebPreviewActivity.this, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show();
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
        WebView previous = webView;
        webView = null;
        if (previous != null) {
            container.removeView(previous);
            previous.destroy();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) WebFullscreenUi.hideSystemBars(this);
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }
}
