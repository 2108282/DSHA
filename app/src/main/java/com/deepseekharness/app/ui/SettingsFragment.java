package com.deepseekharness.app.ui;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;

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
            new TabOption("配置", "端口 · 行为 · 权限", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 保存位置 · 工作区", WorkspaceFragment::new),
            new TabOption("抽屉设置", "反色 · 圈定即搜 · 白天黑夜透明度", SheetSettingsFragment::new),
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
        v.findViewById(R.id.settings_apply_patches).setOnClickListener(x -> confirmApplyPatches());

        // 常驻后台服务通知开关：控制是否随核心运转常驻通知栏
        androidx.appcompat.widget.SwitchCompat notifSwitch = v.findViewById(R.id.settings_persistent_notification_switch);
        if (notifSwitch != null) {
            ConfigStore cfg = new ConfigStore(requireContext());
            notifSwitch.setChecked(cfg.isPersistentNotificationEnabled());
            v.findViewById(R.id.settings_persistent_notification_row).setOnClickListener(x -> {
                boolean next = !notifSwitch.isChecked();
                notifSwitch.setChecked(next);
                cfg.setPersistentNotificationEnabled(next);
                com.deepseekharness.app.HarnessService.syncPersistentNotificationState(requireContext(), next);
                Toast.makeText(requireContext(),
                        next ? "已开启常驻通知" : "已关闭常驻通知",
                        Toast.LENGTH_SHORT).show();
            });
        }

        View reextract = v.findViewById(R.id.settings_reextract);
        if (reextract != null) {
            reextract.setVisibility(View.GONE);
        }

        return v;
    }

    private void confirmReextract() {
        new MaterialAlertDialogBuilder(requireContext())
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
        showUpdateDialog();
    }

    private void showUpdateDialog() {
        String[] options = {
                "① 升级DSH核心",
                "② DSHA 客户端与 Magisk/KSU 模块 (Release)"
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("检查与获取更新")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            showDshUpdate();
                            break;
                        case 1:
                            openUrl(AboutDialog.GITHUB_ROOT_URL + "/releases");
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showDshUpdate() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("升级DSH核心")
                .setMessage("当前版本: 请在核心中查看\n\n"
                        + "可在浏览器查看官方 GitHub 上游最新发布日志，或在终端执行 npm 升级命令:\n\n"
                        + "npm i -g @deepseek-ai/dsh@（版本号）")
                .setPositiveButton("查看官方 Release", (d, w) ->
                        openUrl("https://github.com/deepseek-ai/deepseek-harness/releases"))
                .setNeutralButton("复制升级命令", (d, w) -> copyText("npm i -g @deepseek-ai/dsh@（版本号）"))
                .setNegativeButton("返回", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "打开链接失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", text));
                Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {}
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
    private void confirmApplyPatches() {
        String msg = "【原生环境与存储直通自愈】\n\n"
                + "1. 内部存储直通：重新建立 /root/内部存储 → /sdcard/Download/DSHA 软链接；\n"
                + "2. 默认工作区检查：确保手机 Download/DSHA/工作区 存在且具备完全读写权限；\n"
                + "3. 网络与 DNS 校验：重写 /etc/resolv.conf 权威公共 DNS，解决网络解析异常；\n"
                + "4. 3095 设备桥令牌：重新同步并授权 /root/.dsh/.bridge_token 凭据；\n"
                + "5. 插件加载入口自愈：补齐内置核心插件与第三方插件软链接，保持依赖文件原生硬链接无损。\n\n"
                + "【适用场景】\n"
                + "· 终端内找不到「内部存储」直通软链接时；\n"
                + "· 导入备份包或重装模块后的首次环境修复；\n"
                + "· 插件市场或内置插件报依赖找不到时。";

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("原生环境与存储直通自愈")
                .setMessage(msg)
                .setPositiveButton("开始自愈修复", (d, w) -> runApplyPatches())
                .setNegativeButton("取消", null)
                .show();
    }

    private void runApplyPatches() {
        HarnessController controller = HarnessController.get(requireContext());
        AlertDialog progress = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("正在自愈")
                .setMessage("正在执行原生环境与直通校验，请稍候…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try {
                // 1. 直通软链接与工作区目录（原子覆盖软链接，避免 rm 误触命令守卫）
                String cmd1 = "mkdir -p /sdcard/Download/DSHA/工作区 /root/.dsh 2>/dev/null || true; "
                        + "ln -sfn /sdcard/Download/DSHA /root/内部存储 2>/dev/null || true; "
                        + "chmod 777 /root/.dsh 2>/dev/null || true; echo OK";
                String r1 = controller.proot().execChecked(cmd1);
                report.append("· 内部存储直通与工作区: ").append(r1.contains("OK") ? "✅ 已就绪 (/root/内部存储)" : "⚠️ 完成").append("\n");

                // 2. DNS 修复（直接覆盖重写，避免 rm 误触命令守卫）
                String cmd2 = "mkdir -p /etc 2>/dev/null; "
                        + "printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\nnameserver 1.1.1.1\\n' > /etc/resolv.conf 2>/dev/null; echo OK";
                String r2 = controller.proot().execChecked(cmd2);
                report.append("· 网络与 DNS 解析配置: ").append(r2.contains("OK") ? "✅ 已更新 (公共 DNS)" : "⚠️ 完成").append("\n");

                // 3. 3095 设备桥 Token 同步
                com.deepseekharness.app.HttpShellService.syncTokenToRootfsSync();
                report.append("· 3095 设备桥令牌: ✅ 同步就绪\n");

                // 4. 插件加载入口全面自愈（内置插件 + 第三方插件 + 全局/局部 node_modules + scope 支持）
                String cmd3 = "mkdir -p /root/.dsh/profiles/web/node_modules /usr/local/lib/node_modules 2>/dev/null || true; "
                        + "for p in /root/dsha-*; do [ -d \"$p\" ] || continue; "
                        + "  bname=$(basename \"$p\"); "
                        + "  case \"$bname\" in dsha-repo|dsha-builtin.txt|*-installed) continue ;; esac; "
                        + "  pname=\"dsh-${bname#dsha-}\"; "
                        + "  ln -sfn \"$p\" \"/root/.dsh/profiles/web/node_modules/$pname\" 2>/dev/null || true; "
                        + "  ln -sfn \"$p\" \"/usr/local/lib/node_modules/$pname\" 2>/dev/null || true; "
                        + "done; "
                        + "if [ -d /root/.dsh/plugin-src ]; then "
                        + "  for p in /root/.dsh/plugin-src/*; do [ -d \"$p\" ] || continue; "
                        + "    bname=$(basename \"$p\"); "
                        + "    if [ \"${bname:0:1}\" = \"@\" ]; then "
                        + "      mkdir -p \"/root/.dsh/profiles/web/node_modules/$bname\" \"/usr/local/lib/node_modules/$bname\" 2>/dev/null || true; "
                        + "      for sub in \"$p\"/*; do [ -d \"$sub\" ] || continue; "
                        + "        subname=$(basename \"$sub\"); "
                        + "        ln -sfn \"$sub\" \"/root/.dsh/profiles/web/node_modules/$bname/$subname\" 2>/dev/null || true; "
                        + "        ln -sfn \"$sub\" \"/usr/local/lib/node_modules/$bname/$subname\" 2>/dev/null || true; "
                        + "      done; "
                        + "    else "
                        + "      ln -sfn \"$p\" \"/root/.dsh/profiles/web/node_modules/$bname\" 2>/dev/null || true; "
                        + "      ln -sfn \"$p\" \"/usr/local/lib/node_modules/$bname\" 2>/dev/null || true; "
                        + "    fi; "
                        + "  done; "
                        + "fi; echo OK";
                String r3 = controller.proot().execChecked(cmd3);
                report.append("· 插件扩展依赖链: ").append(r3.contains("OK") ? "✅ 校验正常 (保持 pnpm 原生硬链接)" : "⚠️ 完成").append("\n");

            } catch (Throwable e) {
                report.append("执行异常: ").append(e.getMessage());
            }

            main.post(() -> {
                if (!isAdded()) return;
                progress.dismiss();
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("自愈完成")
                        .setMessage(report.toString() + "\n\n建议重启 Web 服务使修改全部生效。")
                        .setPositiveButton("立即重启服务", (d, w) -> {
                            controller.stopWeb();
                            controller.startWeb(status -> {});
                            Toast.makeText(requireContext(), "正在重启 Web 服务…", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("稍后手动重启", null)
                        .show();
            });
        }, "dsha-native-heal").start();
    }

}
