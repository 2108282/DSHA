package com.deepseekharness.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.function.Supplier;

/**
 * 设置模块列表：安装 / 配置 / 工作区 等入口。
 * <p>点击一项→以二级页面（全屏）打开对应子页，返回键回到列表。</p>
 * <p>扩展方式：在 {@link #TAB_OPTIONS} 中追加一项（标题 + 子页工厂）即可。</p>
 */
public class SettingsFragment extends Fragment {

    /** 设置子页选项：新增子页只需在此追加一项 */
    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("安装", InstallFragment::new),
            new TabOption("配置", ConfigFragment::new),
            new TabOption("工作区", WorkspaceFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LinearLayout tabs = view.findViewById(R.id.settings_tabs);
        for (int i = 0; i < TAB_OPTIONS.length; i++) {
            tabs.addView(buildRow(i));
        }
    }

    /** 构建一行列表项：标题 + 右侧箭头，点击进入二级页面 */
    private LinearLayout buildRow(final int index) {
        TabOption opt = TAB_OPTIONS[index];

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        if (index > 0) {
            lp.topMargin = dp(8);
        }
        row.setLayoutParams(lp);
        row.setBackgroundResource(R.drawable.bg_btn);

        TextView title = new TextView(requireContext());
        title.setText(opt.title);
        title.setTextSize(15);
        title.setTextColor(0xFF1F2328);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(requireContext());
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(0xFF999999);

        row.addView(title);
        row.addView(arrow);
        row.setOnClickListener(v -> {
            Fragment f = TAB_OPTIONS[index].factory.get();
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .addToBackStack("settings")
                    .commit();
        });
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 设置子页选项定义 */
    private static final class TabOption {
        final String title;
        final Supplier<Fragment> factory;

        TabOption(String title, Supplier<Fragment> factory) {
            this.title = title;
            this.factory = factory;
        }
    }
}