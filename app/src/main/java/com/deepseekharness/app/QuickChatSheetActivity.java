package com.deepseekharness.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 快捷对话底部抽屉弹层（纯原生零依赖实现，对标 Box / Material 3 风格）：
 * - 点击常驻通知直接从屏幕底部顺滑滑出；
 * - 顶部横条（Drag Handle）支持手势上下拉伸调节高度、下滑快速关闭；
 * - 左上角 ✕ 关闭按钮、⚙ 容器设置按钮（直达 App 容器控制台）；
 * - 右上角 ◫ 分栏面板按钮（直达 App 完整全屏聊天页）；
 * - 独立单例栈运行，关闭后不在系统多任务/最近任务列表残留卡片。
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class QuickChatSheetActivity extends AppCompatActivity {

    private FrameLayout rootOverlay;
    private LinearLayout sheetCard;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private TextView errorHint;
    private WebView webView;
    private HarnessController controller;

    private int screenHeight = 0;
    private int defaultHeight = 0;
    private int maxHeight = 0;
    private int minHeight = 0;
    private int currentHeight = 0;
    private boolean isDismissing = false;

    private float initialTouchY = 0f;
    private int initialHeightOnTouch = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 窗口基础配置：半透明、点击外部关闭
        setFinishOnTouchOutside(true);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.5f);
            // 软键盘弹出时顶起布局
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        setContentView(R.layout.activity_quick_chat_sheet);

        controller = HarnessController.get(this);
        calculateDimensions();
        initViews();
        setupGesture();
        setupActions();
        loadChatWeb();
    }

    private void calculateDimensions() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        defaultHeight = (int) (screenHeight * 0.68f);
        maxHeight = (int) (screenHeight * 0.94f);
        minHeight = (int) (screenHeight * 0.38f);
        currentHeight = defaultHeight;
    }

    private void initViews() {
        rootOverlay = findViewById(R.id.root_overlay);
        sheetCard = findViewById(R.id.sheet_card);
        webContainer = findViewById(R.id.web_container);
        progressBar = findViewById(R.id.sheet_progress);
        errorHint = findViewById(R.id.sheet_error_hint);

        // 初始高度应用
        ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
        if (lp != null) {
            lp.height = defaultHeight;
            sheetCard.setLayoutParams(lp);
        }

        // 点击卡片外部的半透明空白区域直接退出
        rootOverlay.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                int[] loc = new int[2];
                sheetCard.getLocationOnScreen(loc);
                float y = event.getRawY();
                if (y < loc[1]) {
                    dismissSheet();
                    return true;
                }
            }
            return false;
        });
    }

    private void setupGesture() {
        View dragArea = findViewById(R.id.drag_handle_area);
        View headerBar = findViewById(R.id.sheet_header);

        View.OnTouchListener gestureListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchY = event.getRawY();
                    initialHeightOnTouch = sheetCard.getHeight();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - initialTouchY; // 向下滑 dy > 0，向上滑 dy < 0
                    int targetHeight = (int) (initialHeightOnTouch - dy);
                    if (targetHeight > maxHeight) targetHeight = maxHeight;
                    if (targetHeight > 0) {
                        ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
                        if (lp != null) {
                            lp.height = targetHeight;
                            sheetCard.setLayoutParams(lp);
                            currentHeight = targetHeight;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float totalDy = event.getRawY() - initialTouchY;
                    // 若向下滑动超过 100dp 或当前高度低于下限阈值，直接顺滑退出
                    if (totalDy > dpToPx(100) || currentHeight < minHeight) {
                        dismissSheet();
                    } else if (currentHeight > (defaultHeight + maxHeight) / 2) {
                        // 吸附到全屏高度
                        animateHeightTo(maxHeight);
                    } else {
                        // 弹性恢复到默认半屏高度
                        animateHeightTo(defaultHeight);
                    }
                    return true;
            }
            return false;
        };

        if (dragArea != null) dragArea.setOnTouchListener(gestureListener);
        if (headerBar != null) headerBar.setOnTouchListener(gestureListener);
    }

    private void animateHeightTo(int targetH) {
        int startH = sheetCard.getHeight();
        if (startH == targetH) return;

        ValueAnimator anim = ValueAnimator.ofInt(startH, targetH);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(animation -> {
            int h = (int) animation.getAnimatedValue();
            ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
            if (lp != null) {
                lp.height = h;
                sheetCard.setLayoutParams(lp);
                currentHeight = h;
            }
        });
        anim.start();
    }

    private void setupActions() {
        ImageButton btnClose = findViewById(R.id.btn_close);
        ImageButton btnSettings = findViewById(R.id.btn_settings);
        ImageButton btnFullscreen = findViewById(R.id.btn_fullscreen);

        // 1. 左上角 ✕ 关闭按钮
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismissSheet());
        }

        // 2. 左侧 ⚙ 容器设置按钮：进入 APP 容器主控制台
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                dismissSheet();
            });
        }

        // 3. 右上角 ◫ 分栏面板按钮：进入 APP 的完整全屏聊天页面
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(v -> {
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("open_web", true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                dismissSheet();
            });
        }
    }

    private void loadChatWeb() {
        String url = buildWebUrl();

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSupportMultipleWindows(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        boolean desktop = getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean("desktop_mode", false);
        if (desktop) {
            ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && errorHint != null) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    errorHint.setVisibility(View.VISIBLE);
                    errorHint.setText("DSHA 服务未就绪，请先在控制台启动");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(url);
    }

    private String buildWebUrl() {
        String base = "http://127.0.0.1:" + controller.getPort() + "/";
        String token = HttpShellService.currentToken();
        return token.isEmpty() ? base : base + "?dsha_t=" + Uri.encode(token);
    }

    /** 顺滑向下平移退出弹层 */
    private void dismissSheet() {
        if (isDismissing) return;
        isDismissing = true;

        if (sheetCard != null) {
            sheetCard.animate()
                    .translationY(sheetCard.getHeight() + dpToPx(30))
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            finish();
                            overridePendingTransition(0, 0);
                        }
                    })
                    .start();
        } else {
            finish();
            overridePendingTransition(0, R.anim.slide_out_bottom);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            dismissSheet();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            try {
                webContainer.removeView(webView);
                webView.stopLoading();
                webView.destroy();
                webView = null;
            } catch (Throwable ignored) {
            }
        }
    }
}
