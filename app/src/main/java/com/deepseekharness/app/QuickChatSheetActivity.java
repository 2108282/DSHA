package com.deepseekharness.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * 快捷对话底部抽屉弹层（纯代码动态构建，零外部 XML 依赖）：
 * - 点击常驻通知或任务完成通知，直接从屏幕底部顺滑滑出；
 * - 顶部横条（Drag Handle）支持手势上下拉伸调节高度、下滑快速关闭；
 * - 底部锁定（Gravity.BOTTOM），拖拽时仅顶部上下伸展；
 * - 标题物理绝对居中（RelativeLayout 居中），横条与标题间距紧凑精致；
 * - 半透明毛玻璃质感底色（#EBF5F8FC），显式禁用 WebView 强制反色算法；
 * - 退出转入后台保活（moveTaskToBack），再次呼出秒开、零加载、状态不丢；
 * - 左上角 ✕ 关闭按钮、⚙ 容器设置按钮（直达 App 容器控制台）；
 * - 右上角 ◫ 分栏面板按钮（直达 App 完整全屏聊天页）；
 * - 独立单例栈运行，关闭后不在系统多任务/最近任务列表残留卡片。
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class QuickChatSheetActivity extends Activity {

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
    private boolean isDarkMode = false;
    private boolean webLoaded = false;

    private float initialTouchY = 0f;
    private int initialHeightOnTouch = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 窗口基础配置：全屏铺满、底部对齐（彻底锁死底部）、半透明遮罩、点击外部退出、键盘顶起
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(true);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        controller = HarnessController.get(this);
        isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        calculateDimensions();
        setContentView(buildUi());
        setupGesture();
        loadChatWeb();
        animateIn();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        animateIn();
    }

    private void calculateDimensions() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        defaultHeight = (int) (screenHeight * 0.68f);
        maxHeight = (int) (screenHeight * 0.94f);
        minHeight = (int) (screenHeight * 0.38f);
        currentHeight = defaultHeight;
    }

    private View buildUi() {
        // 毛玻璃半透明底色（浅色：#EBF5F8FC 半透轻白蓝；深色：#EB161B24 半透深灰）
        int cardBgColor = isDarkMode ? Color.parseColor("#EB161B24") : Color.parseColor("#EBF5F8FC");
        int textColor = isDarkMode ? Color.parseColor("#E8ECF4") : Color.parseColor("#1A2230");
        int lineColor = isDarkMode ? Color.parseColor("#302A3344") : Color.parseColor("#30E2E6EE");
        int handleColor = isDarkMode ? Color.parseColor("#704A5568") : Color.parseColor("#90CBD5E1");
        int borderColor = isDarkMode ? Color.parseColor("#352A3344") : Color.parseColor("#35CBD5E1");

        // 1. 根全屏透明遮罩容器
        rootOverlay = new FrameLayout(this);
        rootOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootOverlay.setBackgroundColor(Color.TRANSPARENT);

        // 点击外部空白区域退出
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

        // 2. 底部卡片主体（Gravity.BOTTOM 彻底锁定底部）
        sheetCard = new LinearLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, defaultHeight);
        cardLp.gravity = Gravity.BOTTOM;
        sheetCard.setLayoutParams(cardLp);
        sheetCard.setOrientation(LinearLayout.VERTICAL);
        sheetCard.setElevation(dpToPx(16));

        // 24dp 顶部圆角毛玻璃半透背景 + 细微描边
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        float r = dpToPx(24);
        cardBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        cardBg.setColor(cardBgColor);
        cardBg.setStroke(dpToPx(1), borderColor);
        sheetCard.setBackground(cardBg);

        // 3. 紧凑拖拽横条区域（Drag Handle）：压缩留白
        FrameLayout dragArea = new FrameLayout(this);
        dragArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(14)));
        dragArea.setPadding(0, dpToPx(5), 0, dpToPx(2));

        View handle = new View(this);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(handleLp);

        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.RECTANGLE);
        handleBg.setCornerRadius(dpToPx(2));
        handleBg.setColor(handleColor);
        handle.setBackground(handleBg);
        dragArea.addView(handle);
        sheetCard.addView(dragArea);

        // 4. 顶部操作栏（RelativeLayout 保证标题绝对居中）
        RelativeLayout headerBar = new RelativeLayout(this);
        headerBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(42)));
        headerBar.setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(2));

        // 左侧按钮组：[✕] + [⚙]
        LinearLayout leftGroup = new LinearLayout(this);
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        leftLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        leftLp.addRule(RelativeLayout.CENTER_VERTICAL);
        leftGroup.setLayoutParams(leftLp);
        leftGroup.setOrientation(LinearLayout.HORIZONTAL);
        leftGroup.setGravity(Gravity.CENTER_VERTICAL);

        // [✕ 关闭按钮]
        View btnClose = createHeaderButton("✕", textColor);
        btnClose.setOnClickListener(v -> dismissSheet());
        leftGroup.addView(btnClose);

        // [⚙ 容器设置按钮]
        View btnSettings = createHeaderButton("⚙", textColor);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            dismissSheet();
        });
        leftGroup.addView(btnSettings);
        headerBar.addView(leftGroup);

        // 中间标题（物理绝对居中）
        TextView title = new TextView(this);
        RelativeLayout.LayoutParams titleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        title.setLayoutParams(titleLp);
        title.setText("DSHA 对话");
        title.setTextColor(textColor);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerBar.addView(title);

        // 右侧按钮：[◫ 全屏聊天]
        View btnFullscreen = createHeaderButton("◫", textColor);
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(
                dpToPx(36), dpToPx(36));
        rightLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        btnFullscreen.setLayoutParams(rightLp);
        btnFullscreen.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("open_web", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            dismissSheet();
        });
        headerBar.addView(btnFullscreen);

        sheetCard.addView(headerBar);

        // 5. 分割线
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        divider.setBackgroundColor(lineColor);
        sheetCard.addView(divider);

        // 6. WebView 主体容器
        webContainer = new FrameLayout(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webContainer.setLayoutParams(webLp);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        webContainer.addView(progressBar);

        errorHint = new TextView(this);
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.CENTER;
        errorHint.setLayoutParams(errLp);
        errorHint.setText("正在连接 DSHA 服务…");
        errorHint.setTextColor(isDarkMode ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
        errorHint.setTextSize(14);
        errorHint.setVisibility(View.GONE);
        webContainer.addView(errorHint);

        sheetCard.addView(webContainer);
        rootOverlay.addView(sheetCard);

        return rootOverlay;
    }

    private View createHeaderButton(String text, int textColor) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
        lp.setMargins(dpToPx(1), 0, dpToPx(1), 0);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        tv.setFocusable(true);

        // 圆形水波纹反馈
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#203D6FD4")), null, mask);
        tv.setBackground(ripple);

        return tv;
    }

    private void setupGesture() {
        View.OnTouchListener gestureListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchY = event.getRawY();
                    initialHeightOnTouch = sheetCard.getHeight();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - initialTouchY; // 下滑 > 0，上滑 < 0
                    int targetH = (int) (initialHeightOnTouch - dy);
                    if (targetH > maxHeight) targetH = maxHeight;
                    if (targetH > 0) {
                        ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
                        if (lp != null) {
                            lp.height = targetH;
                            sheetCard.setLayoutParams(lp);
                            currentHeight = targetH;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float totalDy = event.getRawY() - initialTouchY;
                    if (totalDy > dpToPx(90) || currentHeight < minHeight) {
                        dismissSheet();
                    } else if (currentHeight > (defaultHeight + maxHeight) / 2) {
                        animateHeightTo(maxHeight);
                    } else {
                        animateHeightTo(defaultHeight);
                    }
                    return true;
            }
            return false;
        };

        if (sheetCard.getChildCount() > 0) {
            sheetCard.getChildAt(0).setOnTouchListener(gestureListener); // dragArea
        }
        if (sheetCard.getChildCount() > 1) {
            sheetCard.getChildAt(1).setOnTouchListener(gestureListener); // headerBar
        }
    }

    private void animateHeightTo(int targetH) {
        int startH = sheetCard.getHeight();
        if (startH == targetH) return;

        ValueAnimator anim = ValueAnimator.ofInt(startH, targetH);
        anim.setDuration(180);
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

    private void loadChatWeb() {
        if (webLoaded && webView != null) return;
        webLoaded = true;

        String base = "http://127.0.0.1:" + (controller != null ? controller.getPort() : "3080") + "/";
        String token = HttpShellService.currentToken();
        String url = token.isEmpty() ? base : base + "?dsha_t=" + Uri.encode(token);

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

        // 显式禁用 Android 系统算法强制变暗/反色（保持浅白原色，不被误反相）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ws.setForceDark(WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ws.setAlgorithmicDarkeningAllowed(false);
            } catch (Throwable ignored) {}
        }

        webView.setBackgroundColor(Color.TRANSPARENT);

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

    /** 从底部顺滑滑入展开 */
    private void animateIn() {
        isDismissing = false;
        if (sheetCard != null) {
            sheetCard.setVisibility(View.VISIBLE);
            sheetCard.post(() -> {
                sheetCard.setTranslationY(sheetCard.getHeight() > 0 ? sheetCard.getHeight() : defaultHeight);
                sheetCard.animate()
                        .translationY(0)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(null)
                        .start();
            });
        }
    }

    /** 顺滑向下平移退出弹层并转入后台保活（moveTaskToBack） */
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
                            moveTaskToBack(true);
                            overridePendingTransition(0, 0);
                            isDismissing = false;
                        }
                    })
                    .start();
        } else {
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
            isDismissing = false;
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
