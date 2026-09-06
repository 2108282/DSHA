package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.WebPreviewPolicy;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** 兼容版浏览器：鉴权、文件上传、错误恢复与系统 WebView 入口保持一致。 */
public final class GeckoPreviewActivity extends AppCompatActivity implements WebFullscreenUi.Host {
    private GeckoSession session;
    private GeckoView browser;
    private FrameLayout container;
    private ProgressBar progress;
    private View errorPanel;
    private String authUrl, baseUrl;
    private boolean canGoBack;
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileResult;
    private final ArrayList<File> uploads = new ArrayList<>();

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePrompt == null) return;
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    cancelFilePrompt();
                    return;
                }
                Intent data = result.getData();
                ArrayList<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++)
                        uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
                receiveFiles(uris);
            });

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_web_preview);
        WebFullscreenUi.install(this);
        container = findViewById(R.id.web_container);
        progress = findViewById(R.id.web_progress);
        errorPanel = findViewById(R.id.web_error_panel);
        authUrl = getIntent().getStringExtra("url");
        baseUrl = WebPreviewPolicy.loopbackBaseUrl(authUrl);
        findViewById(R.id.web_error_browser).setOnClickListener(v -> external(authUrl));
        findViewById(R.id.web_retry).setOnClickListener(v -> load());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        if (baseUrl == null) { showError("对话地址无效", "请返回启动页重新进入。"); return; }
        load();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) WebFullscreenUi.hideSystemBars(this);
    }

    private void load() {
        if (baseUrl == null || isFinishing()) return;
        closeSession();
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        canGoBack = false;
        try {
            browser = new GeckoView(this);
            com.deepseekharness.app.core.DiagnosticLog.record(this, "WEB_ENGINE", "Gecko 143");
            boolean desktop = getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                    .getBoolean(Constants.KEY_DESKTOP_MODE, false);
            GeckoSession current = new GeckoSession(new GeckoSessionSettings.Builder()
                    .userAgentMode(desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                            : GeckoSessionSettings.USER_AGENT_MODE_MOBILE).build());
            session = current;
            current.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public void onCanGoBack(GeckoSession s, boolean allowed) { canGoBack = allowed; }
                @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                    if (WebPreviewPolicy.sameService(baseUrl, request.uri)) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                    if (request.hasUserGesture) external(request.uri);
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
                    showError("对话页面加载失败", "请确认服务仍在运行，点击重试。错误代码：" + error.code);
                    return null;
                }
            });
            current.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override public void onPageStart(GeckoSession s, String url) {
                    progress.setProgress(0);
                    progress.setVisibility(View.VISIBLE);
                }
                @Override public void onProgressChange(GeckoSession s, int value) { progress.setProgress(value); }
                @Override public void onPageStop(GeckoSession s, boolean success) {
                    progress.setVisibility(View.GONE);
                    if (!success) showError("页面未完成加载", "服务可能已退出，点击重试或返回启动页。");
                }
            });
            current.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override public void onCrash(GeckoSession s) { showError("网页进程异常退出", "点击重试可重新打开对话。"); }
                @Override public void onKill(GeckoSession s) { showError("网页进程被系统回收", "关闭其他应用后重试。"); }
            });
            current.setPromptDelegate(new GeckoSession.PromptDelegate() {
                @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt prompt) {
                    GeckoResult<PromptResponse> result = new GeckoResult<>();
                    new androidx.appcompat.app.AlertDialog.Builder(GeckoPreviewActivity.this)
                            .setTitle("网页提示").setMessage(prompt.message)
                            .setPositiveButton("确定", (d, w) -> result.complete(prompt.dismiss()))
                            .setOnCancelListener(d -> result.complete(prompt.dismiss())).show();
                    return result;
                }
                @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt prompt) {
                    GeckoResult<PromptResponse> result = new GeckoResult<>();
                    new androidx.appcompat.app.AlertDialog.Builder(GeckoPreviewActivity.this)
                            .setTitle("网页确认").setMessage(prompt.message)
                            .setPositiveButton("确定", (d, w) -> result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                            .setNegativeButton("取消", (d, w) -> result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                            .setOnCancelListener(d -> result.complete(prompt.dismiss())).show();
                    return result;
                }
                @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
                    cancelFilePrompt();
                    if (prompt.type == FilePrompt.Type.FOLDER) {
                        Toast.makeText(GeckoPreviewActivity.this, "请先将文件夹压缩为文件再上传", Toast.LENGTH_LONG).show();
                        return GeckoResult.fromValue(prompt.dismiss());
                    }
                    filePrompt = prompt;
                    fileResult = new GeckoResult<>();
                    GeckoResult<PromptResponse> pending = fileResult;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
                    if (prompt.mimeTypes != null && prompt.mimeTypes.length > 0)
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
                    try { picker.launch(intent); } catch (RuntimeException error) { cancelFilePrompt(); }
                    return pending;
                }
            });
            current.open(GeckoRuntime.getDefault(this));
            browser.setSession(current);
            container.addView(browser, new FrameLayout.LayoutParams(-1, -1));
            // 由 Gecko 自己完成 token → Cookie 交换，避免与 WebView 分开的 Cookie 存储混用。
            current.loadUri(authUrl);
        } catch (RuntimeException | LinkageError error) {
            closeSession();
            showError("兼容内核无法启动", "可尝试在系统浏览器打开。错误：" + error.getClass().getSimpleName());
        }
    }

    private void receiveFiles(ArrayList<Uri> uris) {
        final GeckoSession.PromptDelegate.FilePrompt prompt = filePrompt;
        final GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending = fileResult;
        if (prompt == null || uris.isEmpty()) { cancelFilePrompt(); return; }
        new Thread(() -> {
            ArrayList<File> copied = new ArrayList<>();
            String failure = null;
            try {
                if (uris.size() > 20) throw new java.io.IOException("一次最多上传 20 个文件");
                long total = 0;
                for (Uri uri : uris) {
                    if (!"content".equals(uri.getScheme())) throw new java.io.IOException("不支持的文件来源");
                    // 部分文档提供方没有可直接使用的路径；复制用户授权的内容到本次缓存。
                    String name = "upload.bin";
                    try (android.database.Cursor cursor = getContentResolver().query(uri,
                            new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null)
                            name = cursor.getString(0).replaceAll("[\\\\/\\p{Cntrl}]", "_");
                    }
                    if (name.equals(".") || name.equals("..") || name.isEmpty()) name = "upload.bin";
                    File folder = new File(getCacheDir(), "gecko-upload-" + java.util.UUID.randomUUID());
                    if (!folder.mkdir()) throw new java.io.IOException("无法创建上传缓存");
                    File file = new File(folder, name);
                    copied.add(file);
                    try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(file)) {
                        if (in == null) throw new java.io.IOException("无法读取文件");
                        byte[] buffer = new byte[65536]; int size;
                        while ((size = in.read(buffer)) != -1) {
                            total += size;
                            if (total > 256L * 1024 * 1024) throw new java.io.IOException("本次上传超过 256 MiB");
                            out.write(buffer, 0, size);
                        }
                    }
                }
            } catch (Exception error) { failure = error.getMessage(); }
            final String error = failure;
            runOnUiThread(() -> {
                if (filePrompt != prompt || isFinishing() || isDestroyed() || error != null) {
                    for (File file : copied) { file.delete(); file.getParentFile().delete(); }
                    if (filePrompt == prompt) cancelFilePrompt();
                    if (error != null && !isFinishing()) Toast.makeText(this, "上传失败：" + error, Toast.LENGTH_LONG).show();
                    return;
                }
                Uri[] files = new Uri[copied.size()];
                for (int i = 0; i < files.length; i++) files[i] = Uri.fromFile(copied.get(i));
                uploads.addAll(copied);
                pending.complete(prompt.confirm(this, files));
                filePrompt = null; fileResult = null;
            });
        }, "gecko-file-import").start();
    }

    private void cancelFilePrompt() {
        if (filePrompt != null && fileResult != null) fileResult.complete(filePrompt.dismiss());
        filePrompt = null; fileResult = null;
    }
    private void back() { if (session != null && canGoBack) session.goBack(); else finish(); }
    private void external(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (RuntimeException error) { Toast.makeText(this, "没有可用的系统浏览器", Toast.LENGTH_SHORT).show(); }
    }
    private void showError(String title, String detail) {
        if (isFinishing() || isDestroyed()) return;
        ((TextView) findViewById(R.id.web_error_title)).setText(title);
        ((TextView) findViewById(R.id.web_error_detail)).setText(detail + "\n兼容内核 Gecko 143");
        progress.setVisibility(View.GONE); errorPanel.setVisibility(View.VISIBLE);
    }
    private void closeSession() {
        cancelFilePrompt();
        if (browser != null) { browser.releaseSession(); container.removeView(browser); browser = null; }
        if (session != null) { session.close(); session = null; }
    }
    @Override protected void onPause() { if (session != null) session.setActive(false); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (session != null) session.setActive(true); }
    @Override protected void onDestroy() {
        closeSession();
        for (File file : uploads) { file.delete(); file.getParentFile().delete(); }
        uploads.clear();
        super.onDestroy();
    }
}
