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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 设置模块容器：安装 / 配置 / 工作区 等子页以纵向列表呈现。
 * <p>扩展方式：在 {@link #TAB_OPTIONS} 中追加一项（标题 + 子页工厂）即可，
 * 列表项、点击切换、选中高亮全部自动生成，无需改动其他代码。</p>
 */
public class SettingsFragment extends Fragment {

    /** 设置子页选项：新增子页只需在此追加一项 */
    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("安装", InstallFragment::new),
            new TabOption("配置", ConfigFragment::new),
            new TabOption("工作区", WorkspaceFragment::new),
    };

    private final List<LinearLayout> tabRows = new ArrayList<>();

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
        if (savedInstanceState == null) {
            showSub(0);
        }
    }

    /** 构建一行列表项：标题 + 右侧箭头，点击切换到对应子页 */
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
        row.setOnClickListener(v -> showSub(index));
        tabRows.add(row);
        return row;
    }

    /** 切换子页并刷新列表选中态 */
    private void showSub(int index) {
        Fragment f = TAB_OPTIONS[index].factory.get();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.settings_container, f)
                .commit();
        updateTabs(index);
    }

    private void updateTabs(int index) {
        for (int i = 0; i < tabRows.size(); i++) {
            LinearLayout row = tabRows.get(i);
            boolean sel = i == index;
            row.setBackgroundResource(sel ? R.drawable.settings_tab_selected : R.drawable.bg_btn);
            TextView title = (TextView) row.getChildAt(0);
            TextView arrow = (TextView) row.getChildAt(1);
            title.setTextColor(sel ? 0xFFFFFFFF : 0xFF1F2328);
            arrow.setTextColor(sel ? 0xFFFFFFFF : 0xFF999999);
        }
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
