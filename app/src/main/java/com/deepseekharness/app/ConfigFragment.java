package com.deepseekharness.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

/** 配置模块：API key / 端口 / 模型 / 沙箱模式 */
public class ConfigFragment extends Fragment {

    private HarnessController c;
    private EditText apiKeyEdit, portEdit, modelEdit;
    private Spinner modeSpinner;
    private int tapCount = 0;
    private long lastTap = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        apiKeyEdit = view.findViewById(R.id.config_api_key);
        portEdit = view.findViewById(R.id.config_port);
        modelEdit = view.findViewById(R.id.config_model);
        modeSpinner = view.findViewById(R.id.config_mode);
        Button saveBtn = view.findViewById(R.id.config_save);
        TextView repoLink = view.findViewById(R.id.config_repo_link);

        String[] modes = {"danger-full-access", "workspace-write", "read-only"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, modes);
        modeSpinner.setAdapter(adapter);

        loadConfig();

        saveBtn.setOnClickListener(v -> {
            c.setApiKey(apiKeyEdit.getText().toString().trim());
            c.setPort(portEdit.getText().toString().trim());
            c.setModel(modelEdit.getText().toString().trim());
            c.setPermissionMode((String) modeSpinner.getSelectedItem());
            Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
        });

        // 隐藏入口：连点版本号 5 次，弹出 GitHub 仓库 / QQ 群
        repoLink.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTap > 3000) tapCount = 0;
            lastTap = now;
            if (++tapCount >= 5) {
                tapCount = 0;
                showLinksDialog();
            }
        });
    }

    private void showLinksDialog() {
        String[] items = {"GitHub 开源仓库", "QQ 交流群", "取消"};
        new AlertDialog.Builder(requireContext())
                .setTitle("DSHA")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        openBrowser("https://github.com/qiannianhuanxiang/DSHA");
                    } else if (w == 1) {
                        openQQGroup();
                    }
                })
                .show();
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打不开，请手动访问：" + url, Toast.LENGTH_SHORT).show();
        }
    }

    private void openQQGroup() {
        try {
            // mqqapi scheme 直接拉起 QQ 加群界面（无需群链接 key）
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1"
                            + "&uin=960636357&card_type=group")));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打不开 QQ，请手动搜索群号：960636357", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadConfig() {
        apiKeyEdit.setText(c.getApiKey());
        portEdit.setText(c.getPort());
        modelEdit.setText(c.getModel());
        String mode = c.getPermissionMode();
        int idx = 0;
        if ("workspace-write".equals(mode)) idx = 1;
        else if ("read-only".equals(mode)) idx = 2;
        modeSpinner.setSelection(idx);
    }
}
