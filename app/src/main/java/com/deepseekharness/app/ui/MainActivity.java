package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面外壳：启动门禁 + 底部导航（启动 / 插件 / 设置 / 终端）+ 顶栏标题 + 关于入口。
 */
public class MainActivity extends AppCompatActivity {

    public static volatile MainActivity current;
    private boolean requestingLocalNetwork;
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

        setContentView(R.layout.activity_main);
        String pendingLink = getSharedPreferences("dsha-install-link", MODE_PRIVATE).getString("pending", "");
        if (!pendingLink.isEmpty()) {
            getSharedPreferences("dsha-install-link", MODE_PRIVATE).edit().remove("pending").apply();
            try {
                com.deepseekharness.app.util.PluginInstallLink.parse(pendingLink);
                startActivity(new Intent(this, PluginInstallActivity.class).setData(android.net.Uri.parse(pendingLink)));
            } catch (IllegalArgumentException ignored) { }
        }

        TextView title = findViewById(R.id.app_title);
        TextView themeBtn = findViewById(R.id.btn_theme);
        if (themeBtn != null) {
            boolean dark = ThemeController.isDark(this);
            themeBtn.setText(dark ? "☀️ 白天" : "🌙 黑夜");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }
        findViewById(R.id.btn_about).setOnClickListener(v -> AboutDialog.show(this));

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
                title.setText(R.string.nav_launch);
            } else if (id == R.id.nav_plugins) {
                f = new PluginFragment();
                if (getIntent().getBooleanExtra("open_plugins", false)) {
                    Bundle args = new Bundle(); args.putBoolean("show_installed", true); f.setArguments(args);
                    getIntent().removeExtra("open_plugins");
                }
                title.setText(R.string.nav_plugins);
            } else if (id == R.id.nav_settings) {
                f = new SettingsFragment();
                title.setText(R.string.nav_settings);
            } else {
                // 终端：默认挂真 PTY 页（vim/htop/tmux 能跑），可在 PTY 页切回简易版
                f = PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment() : new TerminalFragment();
                title.setText(R.string.nav_terminal);
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .commit();
            return true;
        });

        if (savedInstanceState == null) {
            if (getIntent().getBooleanExtra("open_terminal", false)) {
                nav.setSelectedItemId(R.id.nav_terminal);
            } else if (getIntent().getBooleanExtra("open_plugins", false)) {
                nav.setSelectedItemId(R.id.nav_plugins);
            } else {
                nav.setSelectedItemId(R.id.nav_launch);
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) {
            if (intent.getBooleanExtra("open_terminal", false)) {
                nav.setSelectedItemId(R.id.nav_terminal);
            } else if (intent.getBooleanExtra("open_plugins", false)) {
                nav.setSelectedItemId(R.id.nav_plugins);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isFinishing() && findViewById(R.id.bottom_nav) != null
                && new ConfigStore(this).isLanMode()
                && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)
                && !getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                .getBoolean("local_network_permission_asked", false)) requestLocalNetwork();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        // 收掉 PTY 会话与简易 shell（防在容器里留孤儿 bash）
        try {
            PtyTerminalFragment.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            TerminalFragment.shutdownShell();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
