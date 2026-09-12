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
            ui.postDelayed(this, 1000);
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
        v.findViewById(R.id.launch_safe).setOnClickListener(x -> new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("安全启动 Web？")
                .setMessage("暂时禁用第三方插件后启动，保留插件文件、会话和配置。可在插件管理中逐个启用或恢复之前的状态。")
                .setNegativeButton("取消", null).setPositiveButton("安全启动", (dialog, which) -> doStart(activity, status, start, true)).show());

        // 启动按钮：未就绪时是「启动」；鉴权链接就绪后自动变为「进入」，点击进 WebUI。
        start.setOnClickListener(x -> {
            if (webReady || !controller.getWebAuthUrl().isEmpty()) {
                enterWeb();
                return;
            }
            doStart(activity, status, start);
        });

        restart.setOnClickListener(x -> {
            // startWeb 本身串行执行「清旧进程 → 启动」，无需拆成两次请求。
            doStart(activity, status, start);
        });

        stop.setOnClickListener(x -> {
            controller.stopWeb(msg -> {
                long generation = controller.getWebGeneration();
                activity.runOnUiThread(() -> {
                    if (getView() != v || generation != controller.getWebGeneration()) return;
                    status.setText(msg);
                    refreshRunState();
                    refreshLanAddr();
                });
            });
            webReady = false;
            start.setText("启动");
            status.setText("停止中…");
            refreshLanAddr();
            refreshRunState();
        });

        return v;
    }

    /** 启动 dsh：记录启动时刻，鉴权链接就绪后把「启动」变「进入」并输出 URL 到日志。 */
    private void doStart(Activity activity, TextView status, Button start) {
        doStart(activity, status, start, false);
    }

    private void doStart(Activity activity, TextView status, Button start, boolean safeMode) {
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
                }
                refreshRunState();
                refreshLanAddr();
            });
        };
        boolean accepted = safeMode ? controller.startWebSafely(startStatus) : controller.startWeb(startStatus);
        refreshRunState();
        if (!accepted) return;
        // 前台保活服务：dsh 后台常驻 + 看门狗自动重启（退到桌面/锁屏不被杀）
        try {
            Intent svc = new Intent(requireContext(), com.deepseekharness.app.HarnessService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(svc);
            } else {
                requireContext().startService(svc);
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "拉起保活服务失败: " + t.getMessage());
        }
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
            runState.setText(stopping ? "DSH 停止中…" : starting ? "DSH 启动中…"
                    : ready ? "DSH 已就绪，可进入" : controller.isUserStopped() ? "DSH 已停止" : "DSH 未就绪");
            if (start != null) {
                webReady = ready;
                start.setText(ready ? "进入" : "启动");
                start.setEnabled(!starting && !stopping);
            }
            Button restart = root.findViewById(R.id.launch_open);
            if (restart != null) restart.setEnabled(!starting && !stopping);
            Button stop = root.findViewById(R.id.launch_stop);
            if (stop != null) stop.setEnabled(!stopping);
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
                    final String addr = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                            + LanProxyService.getLanToken(requireContext());
                    lanAddrText.setText("局域网地址：\n" + addr + "\n▸ 点此查看 Bridge Token 与本机访问凭据");
                } else {
                    lanAddrText.setText("局域网已开启（等待 WiFi）\n▸ 点此查看 Bridge Token 与本机凭据");
                }
            } else {
                lanAddrText.setText("局域网代理等待认证\n▸ 点此查看 Bridge Token 与本机凭据");
            }
        } else {
            lanAddrText.setText("▸ 点击查看 Bridge Token 与本机/浏览器访问地址");
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
                Intent intent = new Intent(requireContext(), QuickChatSheetActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(requireContext(), "无法打开抽屉：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
