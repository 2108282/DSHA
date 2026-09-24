package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;

/**
 * 快捷抽屉二级设置页：抽屉反色开关、圈定即搜重定向、白天与黑夜不透明度设置。
 */
public class SheetSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_sheet_settings, container, false);

        TextView back = v.findViewById(R.id.sub_back);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(x -> getParentFragmentManager().popBackStack());

        ConfigStore cfg = new ConfigStore(requireContext());

        // 1. 抽屉正反色开关（独立控制快捷抽屉深色反色，与主应用黑夜白天解耦）
        SwitchCompat invertSwitch = v.findViewById(R.id.sheet_settings_invert_switch);
        if (invertSwitch != null) {
            invertSwitch.setChecked(cfg.isSheetInvertColor());
            v.findViewById(R.id.sheet_settings_invert_row).setOnClickListener(x -> {
                boolean next = !invertSwitch.isChecked();
                invertSwitch.setChecked(next);
                cfg.setSheetInvertColor(next);
                QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
                Toast.makeText(requireContext(),
                        next ? "抽屉反色已开启（深色反色视觉）" : "抽屉反色已关闭（常规浅色视觉）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 2. 抽屉莫奈取色开关（提取系统壁纸 Material You 调色板）
        SwitchCompat monetSwitch = v.findViewById(R.id.sheet_settings_monet_switch);
        if (monetSwitch != null) {
            monetSwitch.setChecked(cfg.isSheetMonetColor());
            v.findViewById(R.id.sheet_settings_monet_row).setOnClickListener(x -> {
                boolean next = !monetSwitch.isChecked();
                monetSwitch.setChecked(next);
                cfg.setSheetMonetColor(next);
                MonetThemeHelper.clearCache(requireContext());
                QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
                Toast.makeText(requireContext(),
                        next ? "莫奈取色已开启（跟随系统壁纸调色）" : "莫奈取色已关闭（恢复经典科技蓝灰）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 2. 圈定即搜重定向开关（LSPosed 模块配置同步）
        SwitchCompat ctsSwitch = v.findViewById(R.id.sheet_settings_cts_redirect_switch);
        if (ctsSwitch != null) {
            ctsSwitch.setChecked(cfg.isCtsRedirectEnabled());
            v.findViewById(R.id.sheet_settings_cts_redirect_row).setOnClickListener(x -> {
                boolean next = !ctsSwitch.isChecked();
                ctsSwitch.setChecked(next);
                cfg.setCtsRedirectEnabled(next);
                Toast.makeText(requireContext(),
                        next ? "圈定即搜重定向已开启（手势唤起抽屉）" : "圈定即搜已回退系统默认（Google）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 3. 抽屉白天不透明度
        EditText opacityDayInput = v.findViewById(R.id.sheet_settings_opacity_day_input);
        Button opacityDaySave = v.findViewById(R.id.sheet_settings_opacity_day_save);
        if (opacityDayInput != null) {
            opacityDayInput.setText(String.valueOf(cfg.getSheetOpacityDay()));
        }
        if (opacityDaySave != null) {
            opacityDaySave.setOnClickListener(x -> {
                int val = 88;
                try {
                    val = Integer.parseInt(opacityDayInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 100) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 100 之间的数值", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetOpacityDay(val);
                Toast.makeText(requireContext(), "已保存白天不透明度为 " + val + "%（下次唤起抽屉生效）", Toast.LENGTH_SHORT).show();
            });
        }

        // 4. 抽屉黑夜不透明度
        EditText opacityNightInput = v.findViewById(R.id.sheet_settings_opacity_night_input);
        Button opacityNightSave = v.findViewById(R.id.sheet_settings_opacity_night_save);
        if (opacityNightInput != null) {
            opacityNightInput.setText(String.valueOf(cfg.getSheetOpacityNight()));
        }
        if (opacityNightSave != null) {
            opacityNightSave.setOnClickListener(x -> {
                int val = 80;
                try {
                    val = Integer.parseInt(opacityNightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 100) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 100 之间的数值", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetOpacityNight(val);
                Toast.makeText(requireContext(), "已保存黑夜不透明度为 " + val + "%（下次唤起抽屉生效）", Toast.LENGTH_SHORT).show();
            });
        }

        // 5. 抽屉默认展开高度与吸附档位 (30~95%)
        EditText heightInput = v.findViewById(R.id.sheet_settings_height_input);
        Button heightSave = v.findViewById(R.id.sheet_settings_height_save);
        if (heightInput != null) {
            heightInput.setText(String.valueOf(cfg.getSheetHeightPercent()));
        }
        if (heightSave != null) {
            heightSave.setOnClickListener(x -> {
                int val = 75;
                try {
                    val = Integer.parseInt(heightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 95) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 95 之间的百分比", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetHeightPercent(val);
                Toast.makeText(requireContext(), "已将默认高度与吸附档位设为 " + val + "%", Toast.LENGTH_SHORT).show();
            });
        }

        // 6. 低于 45% 自动恢复默认高度 开关
        SwitchCompat restoreSwitch = v.findViewById(R.id.sheet_settings_auto_restore_switch);
        if (restoreSwitch != null) {
            restoreSwitch.setChecked(cfg.isSheetAutoRestoreDefault());
            v.findViewById(R.id.sheet_settings_auto_restore_row).setOnClickListener(x -> {
                boolean next = !restoreSwitch.isChecked();
                restoreSwitch.setChecked(next);
                cfg.setSheetAutoRestoreDefault(next);
                Toast.makeText(requireContext(),
                        next ? "已开启：抽屉低于 45% 时下次自动回弹至默认高度" : "已关闭：抽屉保持上次停留高度",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 7. 左右屏幕独立边距 (0~100 dp)
        EditText mlInput = v.findViewById(R.id.sheet_settings_margin_left_input);
        EditText mrInput = v.findViewById(R.id.sheet_settings_margin_right_input);
        Button marginSave = v.findViewById(R.id.sheet_settings_margin_save);
        if (mlInput != null && mrInput != null) {
            mlInput.setText(String.valueOf(cfg.getSheetMarginLeft()));
            mrInput.setText(String.valueOf(cfg.getSheetMarginRight()));
        }
        if (marginSave != null) {
            marginSave.setOnClickListener(x -> {
                int l = 0, r = 0;
                try {
                    l = Integer.parseInt(mlInput.getText().toString().trim());
                    r = Integer.parseInt(mrInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (l < 0 || l > 100 || r < 0 || r > 100) {
                    Toast.makeText(requireContext(), "边距建议在 0 ~ 100 dp 之间", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetMarginLeft(l);
                cfg.setSheetMarginRight(r);
                Toast.makeText(requireContext(), "边距已保存：左 " + l + "dp，右 " + r + "dp（下次唤起生效）", Toast.LENGTH_SHORT).show();
            });
        }

        return v;
    }
}
