package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.QuickChatSheetActivity;
import com.deepseekharness.app.util.Constants;

/**
 * 启动页：启动 / 进入 / 停止 dsh Web，显示运行状态、鉴权链接与局域网访问地址。
 */
public class LaunchFragment extends Fragment {

    private HarnessController controller;
    private TextView lanAddrText;
    private TextView launchLog;
    /** 启动按钮当前是否处于「进入」态（鉴权链接已就绪）。 */
    private boolean webReady;
    /** 日志时间线版本号，防止无变更时重复刷新 DOM。 */
    private long logRevision = -1;
    /** 本次启动开始时刻（显示耗时用）。 */
    private long startAtMs;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshState = new Runnable() {
        @Override public void run() {
            refreshRunState();
            refreshLanAddr();
            long delay = (webReady || (controller != null && !controller.getWebAuthUrl().isEmpty())) ? 2500L : 1000L;
            ui.postDelayed(this, delay);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_launch, container, false);

        controller = HarnessController.get(requireContext());
        final Activity activity = requireActivity();
        TextView status = v.findViewById(R.id.launch_status);
        Button start = v.findViewById(R.id.launch_start);
        Button restart = v.findViewById(R.id.launch_open);
        Button stop = v.findViewById(R.id.launch_stop);
        lanAddrText = v.findViewById(R.id.lan_addr);
        launchLog = v.findViewById(R.id.launch_log);

        restart.setText("重启");

        // 快捷入口：系统浏览器 & 快捷抽屉
        View quickBrowser = v.findViewById(R.id.btn_quick_browser);
        if (quickBrowser != null) {
            quickBrowser.setOnClickListener(x -> {
                String url = controller.getWebAuthUrl();
                if (url == null || url.isEmpty()) {
                    url = "http://127.0.0.1:" + controller.config().getPort();
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "调起浏览器失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
        View quickSheet = v.findViewById(R.id.btn_quick_sheet);
        if (quickSheet != null) {
            quickSheet.setOnClickListener(x -> {
                QuickChatSheetActivity.launch(requireContext());
            });
        }

        // Hero 卡片按压微动与点击进入
        View heroCard = v.findViewById(R.id.hero_card);
        if (heroCard != null) {
            heroCard.setOnClickListener(x -> {
                if (controller.isWebRunning() && !controller.getWebAuthUrl().isEmpty()) {
                    enterWeb();
                }
            });
        }

        android.widget.EditText portInput = v.findViewById(R.id.launch_port_input);
        if (portInput != null) {
            portInput.setText(String.valueOf(controller.config().getPortInt()));
            portInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    String p = s.toString().trim();
                    if (!p.isEmpty()) {
                        controller.config().setPort(p);
                    }
                }
            });
            v.findViewById(R.id.launch_port_chip_3080).setOnClickListener(x -> portInput.setText("3080"));
            v.findViewById(R.id.launch_port_chip_3088).setOnClickListener(x -> portInput.setText("3088"));
            v.findViewById(R.id.launch_port_chip_8080).setOnClickListener(x -> portInput.setText("8080"));
        }

        // 启动按钮：未就绪时是「启动」；鉴权链接就绪后自动变为「进入」，点击进 WebUI。
        start.setOnClickListener(x -> {
            if ((webReady || !controller.getWebAuthUrl().isEmpty()) && controller.isWebRunning()) {
                enterWeb();
                return;
            }
            doStart(activity, status, start);
        });

        restart.setOnClickListener(x -> {
            doRestart(activity, status, start);
        });

        stop.setOnClickListener(x -> {
            controller.stopWeb(msg -> {
                long generation = controller.getWebGeneration();
                activity.runOnUiThread(() -> {
                    if (getView() != v || generation != controller.getWebGeneration()) return;
                    status.setText(msg);
                    refreshRunState();
                    refreshLanAddr();
                    if (isAdded()) {
                        com.deepseekharness.app.HarnessService.stopServiceIfNecessary(requireContext());
                    }
                });
            });
            webReady = false;
            start.setText("启动");
            status.setText("停止中…");
            refreshLanAddr();
            refreshRunState();
            com.deepseekharness.app.HarnessService.stopServiceIfNecessary(requireContext());
        });

        return v;
    }

    /** 强制重启 dsh：破除残留死锁状态，强制清旧进程后拉起。 */
    private void doRestart(Activity activity, TextView status, Button start) {
        final View root = getView();
        startAtMs = System.currentTimeMillis();
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        status.setText("重启中…（" + time + "）");
        start.setText("启动");
        webReady = false;
        appendLog("—— 强制重启 " + time + " ——");
        java.util.function.Consumer<String> startStatus = msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(msg);
                if (!controller.getWebAuthUrl().isEmpty() && !webReady) {
                    long sec = (System.currentTimeMillis() - startAtMs) / 1000;
                    appendLog("启动成功，耗时 " + sec + "s");
                    appendLog("本机打开：" + controller.getWebAuthUrl()
                            + "　（仅本机；其它设备请用「局域网地址」那条）");
                    if (com.deepseekharness.app.HarnessService.currentInstance != null) {
                        com.deepseekharness.app.HarnessService.currentInstance.refreshNotification();
                    }
                }
                refreshRunState();
                refreshLanAddr();
            });
        };
        controller.restartWeb(startStatus);
        refreshRunState();
    }

    /** 启动 dsh：记录启动时刻，鉴权链接就绪后把「启动」变「进入」并输出 URL 到日志。 */
    private void doStart(Activity activity, TextView status, Button start) {
        if (controller.isStarting() || controller.isStopping()) return;
        final View root = getView();
        startAtMs = System.currentTimeMillis();
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        status.setText("启动中…（" + time + "）");
        start.setText("启动");
        webReady = false;
        appendLog("—— 启动 " + time + " ——");
        java.util.function.Consumer<String> startStatus = msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(msg);
                if (!controller.getWebAuthUrl().isEmpty() && !webReady) {
                    long sec = (System.currentTimeMillis() - startAtMs) / 1000;
                    appendLog("启动成功，耗时 " + sec + "s");
                    appendLog("本机打开：" + controller.getWebAuthUrl()
                            + "　（仅本机；其它设备请用「局域网地址」那条）");
                    if (com.deepseekharness.app.HarnessService.currentInstance != null) {
                        com.deepseekharness.app.HarnessService.currentInstance.refreshNotification();
                    }
                }
                refreshRunState();
                refreshLanAddr();
            });
        };
        boolean accepted = controller.startWeb(startStatus);
        refreshRunState();
        if (!accepted) return;
        // 核心运转常驻通知：根据用户设置与核心状态自动挂载
        com.deepseekharness.app.HarnessService.checkAndSyncService(requireContext());
    }

    /** 打开 WebPreviewActivity 进入 dsh WebUI。 */
    private void enterWeb() {
        String url = controller.getWebAuthUrl();
        if (url.isEmpty()) {
            if (getView() != null) {
                ((TextView) getView().findViewById(R.id.launch_status))
                        .setText("先点「启动」，等鉴权链接就绪后再进入");
            }
            return;
        }
        final Activity activity = requireActivity();
        final View root = getView();
        final long generation = controller.getWebGeneration();
        new Thread(() -> {
            String cookie = controller.exchangeDshAuthCookie();
            String finalUrl = url;
            activity.runOnUiThread(() -> {
                if (getView() != root || generation != controller.getWebGeneration()
                        || !url.equals(controller.getWebAuthUrl())) return;
                startActivity(WebPreviewActivity.intent(requireContext(), finalUrl, cookie));
                refreshLanAddr();
            });
        }, "dsh-cookie").start();
    }

    /** 往日志区追加一行（首行替换占位文本）。 */
    private void appendLog(String line) {
        if (launchLog == null || !isAdded()) return;
        String cur = launchLog.getText().toString();
        launchLog.setText("还没有日志。".equals(cur) ? line : cur + "\n" + line);
        if (getView() != null) {
            try {
                android.widget.ScrollView sv = getView().findViewById(R.id.launch_log_scroll);
                if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ui.post(refreshState);
    }

    @Override
    public void onPause() {
        ui.removeCallbacks(refreshState);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        ui.removeCallbacks(refreshState);
        lanAddrText = null;
        launchLog = null;
        super.onDestroyView();
    }

    /** 读取共享状态，不在主线程执行 proot/kill -0；重建页面也能跟随后台启停。 */
    private void refreshRunState() {
        try {
            View root = getView();
            if (root == null) return;
            TextView runState = root.findViewById(R.id.launch_run_state);
            Button start = root.findViewById(R.id.launch_start);
            if (runState == null) return;
            boolean starting = controller.isStarting();
            boolean stopping = controller.isStopping();
            boolean ready = !starting && !stopping && !controller.getWebAuthUrl().isEmpty();
            com.deepseekharness.app.util.StartupTrace.Snapshot trace = controller.startupDiagnostics().snapshot();
            if (launchLog != null && trace.revision != logRevision && !trace.log.isEmpty()) {
                launchLog.setText(trace.log);
                logRevision = trace.revision;
                if (getView() != null) {
                    try {
                        android.widget.ScrollView sv = getView().findViewById(R.id.launch_log_scroll);
                        if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                    } catch (Throwable ignored) {
                    }
                }
            }
            boolean running = controller.isWebRunning();
            if (!ready && !starting && !stopping && running) {
                controller.tryRecoverRunningUrl();
            }
            if (stopping) {
                runState.setText("DSH 停止中…");
            } else if (starting) {
                runState.setText("DSH 启动中…");
            } else if (ready) {
                runState.setText("DSH 已就绪，可进入");
            } else if (running) {
                runState.setText("DSH 运行中，正在同步连接…");
            } else if (!controller.isEnvironmentReady()) {
                runState.setText("⚠️ 未检测到 KernelSU 模块或未授权 Root");
            } else if (controller.isUserStopped()) {
                runState.setText("DSH 已停止");
            } else {
                runState.setText("DSH 未就绪");
            }
            if (start != null) {
                webReady = ready;
                start.setText(ready ? "进入" : "启动");
                start.setEnabled(!starting && !stopping);
            }
            Button restart = root.findViewById(R.id.launch_open);
            if (restart != null) restart.setEnabled(!starting && !stopping);
            Button stop = root.findViewById(R.id.launch_stop);
            if (stop != null) stop.setEnabled(!stopping);

            // 联动 SukiSU-Ultra 风格 Hero 状态大卡片背景与水印
            View heroCard = root.findViewById(R.id.hero_card);
            android.widget.ImageView heroWatermark = root.findViewById(R.id.hero_watermark);
            TextView runDot = root.findViewById(R.id.launch_run_dot);
            if (heroCard != null && heroWatermark != null && isAdded()) {
                if (ready || running) {
                    heroCard.setBackgroundResource(R.drawable.bg_hero_card_running);
                    heroWatermark.setImageResource(R.drawable.ic_watermark_check);
                    if (runDot != null) runDot.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.ok));
                } else {
                    heroCard.setBackgroundResource(R.drawable.bg_hero_card_stopped);
                    heroWatermark.setImageResource(R.drawable.ic_watermark_power);
                    if (runDot != null) runDot.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_muted));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 刷新访问地址与凭据展示区：就绪或开局域网时可见，点击弹出完整地址与 Token 菜单。 */
    private void refreshLanAddr() {
        if (lanAddrText == null || !isAdded()) return;
        boolean lan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        boolean ready = controller != null && !controller.getWebAuthUrl().isEmpty();

        if (!lan && !ready) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }

        if (lan) {
            boolean bound = LanProxyService.isBound();
            if (bound) {
                String ip = HarnessController.getLanAddress();
                if (ip != null && !ip.isEmpty()) {
                    lanAddrText.setText("🌐 局域网服务已就绪 · " + ip + ":" + LanProxyService.LAN_PORT + "\n▸ 点击查看/复制完整访问链接、Bridge Token 与凭据");
                } else {
                    lanAddrText.setText("🌐 局域网服务已开启（等待连接 WiFi）\n▸ 点击查看 Bridge Token 与本机访问凭据");
                }
            } else {
                lanAddrText.setText("🌐 局域网核心代理启动中…\n▸ 点击查看 Bridge Token 与本机访问凭据");
            }
        } else {
            lanAddrText.setText("▸ 点击查看/复制 Bridge Token 与本机访问凭据");
        }

        lanAddrText.setOnClickListener(v -> showAccessCredentialsDialog());
        lanAddrText.setVisibility(View.VISIBLE);
    }

    /**
     * 弹出访问地址与凭据选择器（专供自建后端、外部插件及调试访问）。
     */
    private void showAccessCredentialsDialog() {
        if (!isAdded()) return;
        final String bridgeToken = HttpShellService.currentToken();
        final String authUrl = controller != null ? controller.getWebAuthUrl() : "";

        boolean lan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        String ip = HarnessController.getLanAddress();
        final String lanAddr = (lan && ip != null && !ip.isEmpty())
                ? "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                        + LanProxyService.getLanToken(requireContext())
                : null;

        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> acts = new java.util.ArrayList<>();

        // 1. 复制 Bridge Token (3090 设备桥 / 自建后端鉴权)
        items.add("📋 复制设备桥令牌 (Bridge Token)\n" + (bridgeToken.isEmpty() ? "（尚未生成）" : bridgeToken));
        acts.add(() -> copyAddr("Bridge Token", bridgeToken));

        // 2. 本机 Web 访问链接
        if (!authUrl.isEmpty()) {
            items.add("🌐 复制本机 Web 访问地址（带 Launch Token）\n" + authUrl);
            acts.add(() -> copyAddr("本机 Web 地址", authUrl));

            items.add("🚀 用系统外部浏览器打开 Web 界面");
            acts.add(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
                    startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "无法打开浏览器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 3. 局域网访问地址
        if (lanAddr != null) {
            items.add("📶 复制局域网访问地址（同 WiFi 其它设备）\n" + lanAddr);
            acts.add(() -> copyAddr("局域网地址", lanAddr));

            items.add("🔄 重新生成局域网访问 Token（更换密码）");
            acts.add(() -> {
                LanProxyService.regenerateLanToken(requireContext());
                refreshLanAddr();
                Toast.makeText(requireContext(), "已生成新 Token 并更新地址", Toast.LENGTH_SHORT).show();
            });
        } else if (lan) {
            items.add("📶 局域网模式已开启，等待获取 WiFi IP…");
            acts.add(() -> {});
        } else {
            items.add("📶 局域网访问未开启（可在配置页中打开）");
            acts.add(() -> {});
        }

        // 4. 快捷对话抽屉
        items.add("💬 打开快捷对话底部抽屉");
        acts.add(() -> {
            try {
                Intent intent = QuickChatSheetActivity.createLaunchIntent(requireContext());
                startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(requireContext(), "无法打开抽屉：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("访问地址与鉴权凭据")
                .setItems(items.toArray(new CharSequence[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyAddr(String label, String addr) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, addr));
                Toast.makeText(requireContext(), label + " 已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
