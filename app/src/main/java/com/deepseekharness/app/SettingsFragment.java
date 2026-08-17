package com.deepseekharness.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 设置模块容器：顶部 安装 / 配置 / 工作区 三个子页切换。
 * 底部导航「设置」tab 的入口（原 安装/配置/工作区 三个 tab 合并至此）。
 */
public class SettingsFragment extends Fragment {

    private TextView tabInstall;
    private TextView tabConfig;
    private TextView tabWorkspace;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tabInstall = view.findViewById(R.id.settings_tab_install);
        tabConfig = view.findViewById(R.id.settings_tab_config);
        tabWorkspace = view.findViewById(R.id.settings_tab_workspace);

        tabInstall.setOnClickListener(v -> showSub(0));
        tabConfig.setOnClickListener(v -> showSub(1));
        tabWorkspace.setOnClickListener(v -> showSub(2));

        if (savedInstanceState == null) {
            showSub(0);
        }
    }

    /** 切换子页：0 安装 / 1 配置 / 2 工作区 */
    private void showSub(int index) {
        Fragment f;
        switch (index) {
            case 1:
                f = new ConfigFragment();
                break;
            case 2:
                f = new WorkspaceFragment();
                break;
            default:
                f = new InstallFragment();
        }
        getChildFragmentManager().beginTransaction()
                .replace(R.id.settings_container, f)
                .commit();
        updateTabs(index);
    }

    private void updateTabs(int index) {
        tabInstall.setBackgroundResource(index == 0 ? R.drawable.settings_tab_selected : R.drawable.bg_btn);
        tabConfig.setBackgroundResource(index == 1 ? R.drawable.settings_tab_selected : R.drawable.bg_btn);
        tabWorkspace.setBackgroundResource(index == 2 ? R.drawable.settings_tab_selected : R.drawable.bg_btn);
        tabInstall.setTextColor(index == 0 ? 0xFFFFFFFF : 0xFF1F2328);
        tabConfig.setTextColor(index == 1 ? 0xFFFFFFFF : 0xFF1F2328);
        tabWorkspace.setTextColor(index == 2 ? 0xFFFFFFFF : 0xFF1F2328);
    }
}
