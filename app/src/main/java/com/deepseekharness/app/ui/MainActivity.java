package com.deepseekharness.app.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面外壳：SukiSU-Ultra 风格全景沉浸式布局 + 悬浮药丸胶囊底栏 + 平滑页面切换。
 */
public class MainActivity extends AppCompatActivity {

    public static volatile MainActivity current;
    private boolean requestingLocalNetwork;

    // 悬浮药丸底栏控制引用
    private View pillIndicator;
    private ViewGroup tabsContainer;
    private View tabItemLaunch;
    private View tabItemTerminal;
    private View tabItemPlugins;
    private View tabItemSettings;

    private ImageView tabIconLaunch;
    private ImageView tabIconTerminal;
    private ImageView tabIconPlugins;
    private ImageView tabIconSettings;

    private TextView tabTextLaunch;
    private TextView tabTextTerminal;
    private TextView tabTextPlugins;
    private TextView tabTextSettings;

    private int currentTab = 0; // 0: Launch, 1: Terminal, 2: Plugins, 3: Settings

    private final androidx.activity.result.ActivityResultLauncher<String> localNetworkPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        requestingLocalNetwork = false;
                        if (granted) com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
                        else android.widget.Toast.makeText(this,
                                "未允许局域网访问；本机对话仍可使用，LAN / 无线 ADB 需在系统权限设置中开启",
                                android.widget.Toast.LENGTH_LONG).show();
                    });

    public void requestLocalNetwork() {
        if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) {
            com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
        } else if (!requestingLocalNetwork) {
            requestingLocalNetwork = true;
            getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                    .edit().putBoolean("local_network_permission_asked", true).apply();
            localNetworkPermission.launch(com.deepseekharness.app.bridge.LocalNetworkAccess.PERMISSION);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        ConfigStore config = new ConfigStore(this);
        HarnessController controller = HarnessController.get(this);
        boolean skipExtract = getIntent().getBooleanExtra("skip_extract", false);

        // 启动门禁：未欢迎 → Welcome
        if (!config.isWelcomed()) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        // Android 13+ (API 33+) 动态申请通知权限
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        setContentView(R.layout.activity_main);
        String pendingLink = getSharedPreferences("dsha-install-link", MODE_PRIVATE).getString("pending", "");
        if (!pendingLink.isEmpty()) {
            getSharedPreferences("dsha-install-link", MODE_PRIVATE).edit().remove("pending").apply();
            try {
                com.deepseekharness.app.util.PluginInstallLink.parse(pendingLink);
                startActivity(new Intent(this, PluginInstallActivity.class).setData(android.net.Uri.parse(pendingLink)));
            } catch (IllegalArgumentException ignored) { }
        }

        TextView themeBtn = findViewById(R.id.btn_theme);
        if (themeBtn != null) {
            boolean dark = ThemeController.isDark(this);
            themeBtn.setText(dark ? "☀️ 白天" : "🌙 黑夜");
            themeBtn.setContentDescription(dark ? "切换到白天模式" : "切换到黑夜模式");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }
        findViewById(R.id.btn_about).setOnClickListener(v -> AboutDialog.show(this));

        // 初始化悬浮药丸底栏视图
        initFloatingBottomBar();

        if (savedInstanceState == null) {
            if (getIntent().getBooleanExtra("open_terminal", false)) {
                selectTab(1, false);
            } else if (getIntent().getBooleanExtra("open_plugins", false)) {
                selectTab(2, false);
            } else {
                selectTab(0, false);
            }
        } else {
            selectTab(currentTab, false);
        }
    }

    private void initFloatingBottomBar() {
        pillIndicator = findViewById(R.id.pill_indicator);
        tabsContainer = findViewById(R.id.floating_tabs_container);

        tabItemLaunch = findViewById(R.id.tab_item_launch);
        tabItemTerminal = findViewById(R.id.tab_item_terminal);
        tabItemPlugins = findViewById(R.id.tab_item_plugins);
        tabItemSettings = findViewById(R.id.tab_item_settings);

        tabIconLaunch = findViewById(R.id.tab_icon_launch);
        tabIconTerminal = findViewById(R.id.tab_icon_terminal);
        tabIconPlugins = findViewById(R.id.tab_icon_plugins);
        tabIconSettings = findViewById(R.id.tab_icon_settings);

        tabTextLaunch = findViewById(R.id.tab_text_launch);
        tabTextTerminal = findViewById(R.id.tab_text_terminal);
        tabTextPlugins = findViewById(R.id.tab_text_plugins);
        tabTextSettings = findViewById(R.id.tab_text_settings);

        tabItemLaunch.setOnClickListener(v -> selectTab(0, true));
        tabItemTerminal.setOnClickListener(v -> selectTab(1, true));
        tabItemPlugins.setOnClickListener(v -> selectTab(2, true));
        tabItemSettings.setOnClickListener(v -> selectTab(3, true));

        // 布局就绪后初始化指示器位置
        tabsContainer.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                tabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                updateIndicatorPosition(currentTab, false);
            }
        });
    }

    public void selectTab(int index, boolean animate) {
        currentTab = index;
        TextView title = findViewById(R.id.app_title);
        TextView subtitle = findViewById(R.id.app_subtitle);
        Fragment f;

        int activeColor = ContextCompat.getColor(this, R.color.primary);
        int inactiveColor = ContextCompat.getColor(this, R.color.text_muted);

        // 重置所有 Tab 状态
        resetTabVisual(tabIconLaunch, tabTextLaunch, inactiveColor);
        resetTabVisual(tabIconTerminal, tabTextTerminal, inactiveColor);
        resetTabVisual(tabIconPlugins, tabTextPlugins, inactiveColor);
        resetTabVisual(tabIconSettings, tabTextSettings, inactiveColor);

        switch (index) {
            case 0:
            default:
                f = new LaunchFragment();
                if (title != null) title.setText(R.string.nav_launch);
                if (subtitle != null) subtitle.setText("DSHA · KernelSU / Magisk 原生服务控制");
                highlightTabVisual(tabIconLaunch, tabTextLaunch, activeColor, tabItemLaunch);
                break;
            case 1:
                f = PtyTerminalFragment.preferred(this) ? new PtyTerminalFragment() : new TerminalFragment();
                if (title != null) title.setText(R.string.nav_terminal);
                if (subtitle != null) subtitle.setText("DSHA · 原生 PTY 交互终端");
                highlightTabVisual(tabIconTerminal, tabTextTerminal, activeColor, tabItemTerminal);
                break;
            case 2:
                f = new PluginFragment();
                if (getIntent().getBooleanExtra("open_plugins", false)) {
                    Bundle args = new Bundle();
                    args.putBoolean("show_installed", true);
                    f.setArguments(args);
                    getIntent().removeExtra("open_plugins");
                }
                if (title != null) title.setText(R.string.nav_plugins);
                if (subtitle != null) subtitle.setText("DSHA · 官方核心扩展与插件市场");
                highlightTabVisual(tabIconPlugins, tabTextPlugins, activeColor, tabItemPlugins);
                break;
            case 3:
                f = new SettingsFragment();
                if (title != null) title.setText(R.string.nav_settings);
                if (subtitle != null) subtitle.setText("DSHA · 系统参数、自愈与抽屉沉浸配置");
                highlightTabVisual(tabIconSettings, tabTextSettings, activeColor, tabItemSettings);
                break;
        }

        // 平滑淡入切换 Fragment
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragment_container, f)
                .commit();

        updateIndicatorPosition(index, animate);
    }

    private void resetTabVisual(ImageView icon, TextView text, int color) {
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(color));
        if (text != null) text.setTextColor(color);
    }

    private void highlightTabVisual(ImageView icon, TextView text, int color, View container) {
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(color));
        if (text != null) text.setTextColor(color);
        if (container != null) {
            container.setScaleX(0.92f);
            container.setScaleY(0.92f);
            container.animate().scaleX(1.0f).scaleY(1.0f).setDuration(220).setInterpolator(new OvershootInterpolator(1.4f)).start();
        }
    }

    private void updateIndicatorPosition(int index, boolean animate) {
        if (tabsContainer == null || pillIndicator == null) return;
        int containerWidth = tabsContainer.getWidth();
        if (containerWidth <= 0) return;

        int tabWidth = containerWidth / 4;
        ViewGroup.LayoutParams lp = pillIndicator.getLayoutParams();
        if (lp.width != tabWidth) {
            lp.width = tabWidth;
            pillIndicator.setLayoutParams(lp);
        }

        float targetX = index * tabWidth;
        if (animate) {
            pillIndicator.animate()
                    .translationX(targetX)
                    .setDuration(280)
                    .setInterpolator(new OvershootInterpolator(1.15f))
                    .start();
        } else {
            pillIndicator.setTranslationX(targetX);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("open_terminal", false)) {
            selectTab(1, true);
        } else if (intent.getBooleanExtra("open_plugins", false)) {
            selectTab(2, true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.deepseekharness.app.HarnessService.checkAndSyncService(this);
        if (!isFinishing() && new ConfigStore(this).isLanMode()
                && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)
                && !getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                .getBoolean("local_network_permission_asked", false)) {
            requestLocalNetwork();
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (isFinishing()) {
            try {
                PtyTerminalFragment.shutdown();
            } catch (Throwable ignored) { }
            try {
                TerminalFragment.shutdownShell();
            } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
