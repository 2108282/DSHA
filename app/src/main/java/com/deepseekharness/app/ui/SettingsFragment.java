package com.deepseekharness.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/重新解压/关于）。
 */
public class SettingsFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());

    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("安装", "分步安装 rootfs / 工具 / Node / harness", InstallFragment::new),
            new TabOption("配置", "API key · 端口 · 行为", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 保存位置 · 工作区", WorkspaceFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        LinearLayout tabs = v.findViewById(R.id.settings_tabs);
        for (int i = 0; i < TAB_OPTIONS.length; i++) {
            if (i > 0) {
                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(requireContext().getColor(R.color.line));
                tabs.addView(divider);
            }
            tabs.addView(buildRow(i));
        }

        String version = "unknown";
        try {
            version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView ver = v.findViewById(R.id.settings_ver);
        ver.setText("DSHA v" + version + " · MIT License");
        TextView updateSub = v.findViewById(R.id.settings_update_sub);
        updateSub.setText("当前 v" + version + " · 稳定 / 预览更新通道");

        v.findViewById(R.id.settings_about).setOnClickListener(x -> AboutDialog.show(requireContext()));
        v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());
        v.findViewById(R.id.settings_reextract).setOnClickListener(x -> confirmReextract());

        return v;
    }

    private void confirmReextract() {
        new AlertDialog.Builder(requireContext())
                .setTitle("重新解压内置环境")
                .setMessage("用 APK 里自带的环境覆盖当前容器，约数分钟。\n\n"
                        + "会保留：配置、API Key（自动备份后还原）。\n"
                        + "会回到出厂状态：自己在容器里额外装的东西。\n\n"
                        + "适用场景：dsh 或 npm 不见了、环境怎么修都不对。")
                .setPositiveButton("重新解压", (d, w) -> {
                    try {
                        Intent i = new Intent(requireContext(), ExtractActivity.class);
                        i.putExtra("force_extract", true);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(requireContext(), "打不开解压页：" + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void runSelftest() {
        startActivity(new Intent(requireContext(), DiagnosticActivity.class));
    }

    private void checkUpdate() {
        startActivity(new Intent(requireContext(), UpdateActivity.class));
    }

    private LinearLayout buildRow(final int index) {
        TabOption opt = TAB_OPTIONS[index];
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(15), dp(15), dp(15));
        // 用主题的 selectableItemBackground（Material ripple），不用 Holo 的黄色 list_selector
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(requireContext());
        title.setText(opt.title);
        title.setTextSize(14);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(opt.sub);
        sub.setTextSize(12);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(title);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(requireContext().getColor(R.color.text_muted));

        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, opt.factory.get())
                .addToBackStack("settings")
                .commit());
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class TabOption {
        final String title;
        final String sub;
        final Supplier<Fragment> factory;

        TabOption(String title, String sub, Supplier<Fragment> factory) {
            this.title = title;
            this.sub = sub;
            this.factory = factory;
        }
    }
}
