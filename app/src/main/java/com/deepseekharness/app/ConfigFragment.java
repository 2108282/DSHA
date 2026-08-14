package com.deepseekharness.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** 配置模块：API key / 端口 / 模型 / 沙箱模式 */
public class ConfigFragment extends Fragment {

    private HarnessController c;
    private EditText apiKeyEdit, portEdit, modelEdit;
    private Spinner modeSpinner;
    private CheckBox confirmShellCb, checkUpdateCb;

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
        confirmShellCb = view.findViewById(R.id.config_confirm_shell);
        checkUpdateCb = view.findViewById(R.id.config_check_update);
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
            requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("confirm_shell", confirmShellCb.isChecked())
                    .putBoolean("check_update", checkUpdateCb.isChecked()).apply();
            Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
        });

        // 关于入口：点版本号弹「关于」对话框（GitHub / QQ 群）
        repoLink.setOnClickListener(v -> AboutDialog.show(requireContext()));
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
        confirmShellCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("confirm_shell", true));
        checkUpdateCb.setChecked(requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("check_update", true));
    }
}
