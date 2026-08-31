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
import android.graphics.Rect;
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
import android.view.ViewTreeObserver;
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
 * 1. 左右 100% 铺满屏幕（消除 Dialog Decor 默认 Padding/Margin）；
 * 2. 多档 15% 阶梯智能吸附停靠（35%/50%/65%/80%/95%），低于 25% 退出；
 * 3. 底部严格锁定，拖拽仅顶部伸缩；
 * 4. 标题绝对居中，顶部留白紧凑；
 * 5. 全局静态 WebView 单例保活，再次弹出零转圈、零重新加载；
 * 6. 1:1 精准字体还原（移除 OverviewMode，设置 textZoom 100）；
 * 7. 注入透明全局 CSS 变量与 DOM 背景，100% 透出毛玻璃半透明卡片；
 * 8. 键盘弹出时：卡片顶部物理 Y 坐标绝对锁死不动，仅 WebView 内部输入框上浮。
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class QuickChatSheetActivity extends Activity {

    // 全局静态保活单例，彻底解决再次进入重新转圈加载问题
    @SuppressLint("StaticFieldLeak")
    private static WebView sCachedWebView = null;
    private static boolean sWebLoaded = false;

    private FrameLayout rootOverlay;
    private LinearLayout sheetCard;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private TextView errorHint;
    private HarnessController controller;

    private int screenHeight = 0;
    private int defaultHeight = 0;
    private int maxHeight = 0;
    private int minHeight = 0;
    private int currentHeight = 0;
    private boolean isDismissing = false;
    private boolean isDarkMode = false;

    private float initialTouchY = 0f;
    private int initialHeightOnTouch = 0;

    // 键盘弹出防卡片顶飞机制
    private int lastVisibleDecorHeight = 0;
    private int activeKeyboardHeight = 0;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;

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
            if (window.getDecorView() != null) {
                window.getDecorView().setPadding(0, 0, 0, 0);
            }
        }

        controller = HarnessController.get(this);
        isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        calculateDimensions();
        setContentView(buildUi());
        setupGesture();
        setupKeyboardObserver();
        attachChatWeb();
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
        // 初始高度设为 78%，全屏态 95%，最低安全退出阈值 25%
        defaultHeight = (int) (screenHeight * 0.78f);
        maxHeight = (int) (screenHeight * 0.95f);
        minHeight = (int) (screenHeight * 0.25f);
        currentHeight = defaultHeight;
    }

    private View buildUi() {
        // 毛玻璃半透明底色（浅色：#EBF5F8FC 半透轻白蓝；深色：#EB161B24 半透深灰）
        int cardBgColor = isDarkMode ? Color.parseColor("#EB161B24") : Color.parseColor("#EBF5F8FC");
        int textColor = isDarkMode ? Color.parseColor("#E8ECF4") : Color.parseColor("#1A2230");
        int lineColor = isDarkMode ? Color.parseColor("#302A3344") : Color.parseColor("#30E2E6EE");
        int handleColor = isDarkMode ? Color.parseColor("#704A5568") : Color.parseColor("#90CBD5E1");
        int borderColor = isDarkMode ? Color.parseColor("#352A3344") : Color.parseColor("#35CBD5E1");

        // 1. 根全屏透明遮罩容器（左右 100% 撑满）
        rootOverlay = new FrameLayout(this);
        rootOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootOverlay.setBackgroundColor(Color.TRANSPARENT);
        rootOverlay.setPadding(0, 0, 0, 0);

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

        // 2. 底部卡片主体（Gravity.BOTTOM 彻底锁定底部，左右铺满）
        sheetCard = new LinearLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, defaultHeight);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.setMargins(0, 0, 0, 0);
        sheetCard.setLayoutParams(cardLp);
        sheetCard.setOrientation(LinearLayout.VERTICAL);
        sheetCard.setElevation(dpToPx(16));
        sheetCard.setClipChildren(true);

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
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
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

        // 6. WebView 主体容器（裁剪防漏字）
        webContainer = new FrameLayout(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webContainer.setLayoutParams(webLp);
        webContainer.setClipChildren(true);
        webContainer.setClipToPadding(true);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(sWebLoaded ? View.GONE : View.VISIBLE);
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

    /** 设置键盘高度监听，锁定卡片顶部 Y 坐标不被顶飞，仅内部输入框上移 */
    private void setupKeyboardObserver() {
        if (getWindow() == null || getWindow().getDecorView() == null) return;
        View decorView = getWindow().getDecorView();

        keyboardLayoutListener = () -> {
            Rect r = new Rect();
            decorView.getWindowVisibleDisplayFrame(r);
            int visibleDecorHeight = r.height();

            if (lastVisibleDecorHeight == 0) {
                lastVisibleDecorHeight = visibleDecorHeight;
                return;
            }

            int heightDiff = lastVisibleDecorHeight - visibleDecorHeight;
            if (heightDiff > dpToPx(120)) { // 键盘弹出
                activeKeyboardHeight = heightDiff;
                applyKeyboardHeightAdjustment();
            } else if (heightDiff < -dpToPx(120) || visibleDecorHeight == lastVisibleDecorHeight) { // 键盘收起
                activeKeyboardHeight = 0;
                applyKeyboardHeightAdjustment();
            }
        };
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
    }

    private void applyKeyboardHeightAdjustment() {
        if (sheetCard == null) return;
        int targetH = currentHeight;
        if (activeKeyboardHeight > 0) {
            // 键盘弹出时：卡片高度等额减小键盘高度，锁死卡片顶部物理 Y 坐标绝对不动
            targetH = Math.max(dpToPx(180), currentHeight - activeKeyboardHeight);
        }
        ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
        if (lp != null && lp.height != targetH) {
            lp.height = targetH;
            sheetCard.setLayoutParams(lp);
        }
    }

    private void setupGesture() {
        View.OnTouchListener gestureListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchY = event.getRawY();
                    initialHeightOnTouch = currentHeight;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - initialTouchY; // 下滑 > 0，上滑 < 0
                    int targetH = (int) (initialHeightOnTouch - dy);
                    if (targetH > maxHeight) targetH = maxHeight;
                    if (targetH > 0) {
                        currentHeight = targetH;
                        applyKeyboardHeightAdjustment();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 1. 低于安全下限阈值（< 25%），顺滑向下退出
                    if (currentHeight < minHeight) {
                        dismissSheet();
                    } else {
                        // 2. 15% 步长阶梯智能吸附停留（35%, 50%, 65%, 80%, 95%）
                        snapToNearest15PercentStep();
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

    /** 15% 阶梯智能多档吸附算法 */
    private void snapToNearest15PercentStep() {
        float[] steps = {0.35f, 0.50f, 0.65f, 0.80f, 0.95f};
        float currentRatio = (float) currentHeight / (float) screenHeight;

        float closestRatio = steps[0];
        float minDiff = Math.abs(currentRatio - steps[0]);

        for (int i = 1; i < steps.length; i++) {
            float diff = Math.abs(currentRatio - steps[i]);
            if (diff < minDiff) {
                minDiff = diff;
                closestRatio = steps[i];
            }
        }

        int targetH = (int) (screenHeight * closestRatio);
        animateHeightTo(targetH);
    }

    private void animateHeightTo(int targetH) {
        int startH = currentHeight;
        if (startH == targetH) return;

        ValueAnimator anim = ValueAnimator.ofInt(startH, targetH);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(animation -> {
            currentHeight = (int) animation.getAnimatedValue();
            applyKeyboardHeightAdjustment();
        });
        anim.start();
    }

    /** 挂载常驻单例 WebView，实现 100% 零转圈秒开、1:1 原生字体与透明毛玻璃透光 */
    private void attachChatWeb() {
        if (sCachedWebView == null) {
            sCachedWebView = new WebView(getApplicationContext());
            WebSettings ws = sCachedWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setSupportMultipleWindows(false);
            ws.setUseWideViewPort(true);
            // 移除 setLoadWithOverviewMode(true)，设置 100% 原始字体比例
            ws.setLoadWithOverviewMode(false);
            ws.setTextZoom(100);
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);

            // 禁用系统自动算法反色
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

            sCachedWebView.setBackgroundColor(Color.TRANSPARENT);

            boolean desktop = getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getBoolean("desktop_mode", false);
            if (desktop) {
                ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            }

            sCachedWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    sWebLoaded = true;
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    // 彻底覆写 DSH 前端 CSS 变量与 DOM 背景，消除纯黑实心色，透出半透明毛玻璃卡片
                    injectTransparentBackground(view);
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

            sCachedWebView.setWebChromeClient(new WebChromeClient());

            String base = "http://127.0.0.1:" + (controller != null ? controller.getPort() : "3080") + "/";
            String token = HttpShellService.currentToken();
            String url = token.isEmpty() ? base : base + "?dsha_t=" + Uri.encode(token);
            sCachedWebView.loadUrl(url);
        } else {
            if (sCachedWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) sCachedWebView.getParent()).removeView(sCachedWebView);
            }
            if (progressBar != null) {
                progressBar.setVisibility(sWebLoaded ? View.GONE : View.VISIBLE);
            }
            injectTransparentBackground(sCachedWebView);
        }

        webContainer.addView(sCachedWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 彻底覆写前端背景 CSS 变量，确保背景 100% 透明透光 */
    private void injectTransparentBackground(WebView view) {
        if (view == null) return;
        try {
            String js = "(function() {" +
                    "  var css = `\n" +
                    "    html, body, #root, [data-ds-dark-theme], main, .dsh-layout-root {\n" +
                    "      background: transparent !important;\n" +
                    "      background-color: transparent !important;\n" +
                    "    }\n" +
                    "    :root, .dark, [data-ds-dark-theme] {\n" +
                    "      --dsw-alias-bg-base: transparent !important;\n" +
                    "      --dsw-alias-bg-layer-1: transparent !important;\n" +
                    "      --dsw-alias-bg-layer-2: rgba(255, 255, 255, 0.05) !important;\n" +
                    "      --dsw-specific-sidebar-fill: transparent !important;\n" +
                    "    }\n" +
                    "  `;\n" +
                    "  var style = document.getElementById('dsh-transparent-style');\n" +
                    "  if (!style) {\n" +
                    "    style = document.createElement('style');\n" +
                    "    style.id = 'dsh-transparent-style';\n" +
                    "    document.head.appendChild(style);\n" +
                    "  }\n" +
                    "  style.innerHTML = css;\n" +
                    "  if (document.documentElement) document.documentElement.style.backgroundColor = 'transparent';\n" +
                    "  if (document.body) document.body.style.backgroundColor = 'transparent';\n" +
                    "})();";
            view.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
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
        if (sCachedWebView != null && sCachedWebView.canGoBack()) {
            sCachedWebView.goBack();
        } else {
            dismissSheet();
        }
    }

    @Override
    protected void onDestroy() {
        if (keyboardLayoutListener != null && getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        }
        super.onDestroy();
        if (sCachedWebView != null && sCachedWebView.getParent() == webContainer) {
            webContainer.removeView(sCachedWebView);
        }
    }
}
