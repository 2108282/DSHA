package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;

/**
 * ADB 无线配对输入器（免 Shizuku）：自动发现配对端口 → 输 6 位码 → SPAKE2 配对。
 */
public class AdbPairActivity extends Activity {

    private TextView statusText;
    private EditText codeEt;
    private Button startBtn;

    private volatile int discoveredPairPort = 0;
    private volatile int discoveredConnPort = 0;
    private volatile boolean pairing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) discoverPorts();
        else requestNetworkPermission();
    }

    private void requestNetworkPermission() {
        setStatus("无线 ADB 需要局域网权限，以发现配对端口并连接设备");
        requestPermissions(new String[]{com.deepseekharness.app.bridge.LocalNetworkAccess.PERMISSION}, 37);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 37) {
            if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) discoverPorts();
            else setStatus("未允许局域网访问，请在系统的 DSHA 权限设置中允许后重试配对");
        }
    }

    private View buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (24 * getResources().getDisplayMetrics().density), pad, pad);

        TextView title = new TextView(this);
        title.setText("🔐 ADB 无线配对（免 Shizuku）");
        title.setTextSize(18);
        title.setTextColor(0xFF222222);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("手机：设置 → 开发者选项 → 无线调试 → 「使用配对码配对设备」。\n"
                + "输入屏幕上的 6 位码即可（端口自动发现，无需手填）。码是一次性的，请尽快。");
        hint.setTextSize(13);
        hint.setLineSpacing(4, 1f);
        hint.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(hint);

        codeEt = new EditText(this);
        codeEt.setHint("6 位配对码");
        codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeEt.setGravity(Gravity.CENTER);
        codeEt.setTextSize(24);
        root.addView(codeEt);

        startBtn = new Button(this);
        startBtn.setText("⚡ 开始配对");
        startBtn.setAllCaps(false);
        startBtn.setOnClickListener(v -> startPair());
        root.addView(startBtn);

        statusText = new TextView(this);
        statusText.setText("正在自动发现端口…（若无则默认 5555）");
        statusText.setTextSize(13);
        statusText.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(statusText);

        Button autoBtn = new Button(this);
        autoBtn.setText("⚡ 免手抄：自动读配对码");
        autoBtn.setOnClickListener(v -> onAutoReadClick());
        root.addView(autoBtn);

        Button advanced = new Button(this);
        advanced.setText("手动填端口（高级）");
        advanced.setAllCaps(false);
        advanced.setTextSize(12);
        advanced.setOnClickListener(v -> showManualPorts());
        root.addView(advanced);

        return root;
    }

    private void onAutoReadClick() {
        if (!DshaAccessibilityService.enabled(this)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要先开启无障碍服务")
                    .setMessage("开启后 DSHA 能直接读出配对弹窗里的 6 位码并自动配对。\n\n"
                            + "隐私：可读范围在清单里已限定为系统「设置」应用；"
                            + "只在你点过这个按钮之后的两分钟内才读取，读到配对码立即停止，不保存、不上传。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable t) {
                            setStatus("打不开无障碍设置：" + t.getMessage());
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        DshaAccessibilityService.startWatch((code, ip, port) -> runOnUiThread(() -> {
            codeEt.setText(code);
            if (port != null && !port.isEmpty()) {
                try {
                    discoveredPairPort = Integer.parseInt(port);
                } catch (NumberFormatException ignored) {
                }
            }
            setStatus("已自动读到配对码 " + code
                    + (port == null || port.isEmpty() ? "" : "（配对端口 " + port + "）")
                    + "，正在配对…");
            startPair();
        }));
        setStatus("已开始监听（两分钟内有效）。请在系统页面点「使用配对码配对设备」，弹窗一出现就会自动读码配对。");
        try {
            startActivity(new android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t2) {
                setStatus("请手动打开：设置 → 开发者选项 → 无线调试");
            }
        }
    }

    @Override
    protected void onDestroy() {
        DshaAccessibilityService.stopWatch();
        super.onDestroy();
    }

    private void showManualPorts() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, 4, pad, 4);
        final EditText pairPort = new EditText(this);
        pairPort.setHint("配对端口（手机配对弹窗里的 :端口）");
        pairPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText connPort = new EditText(this);
        connPort.setHint("连接端口（默认 5555）");
        connPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (discoveredPairPort > 0) pairPort.setText(String.valueOf(discoveredPairPort));
        ll.addView(pairPort);
        ll.addView(connPort);
        new android.app.AlertDialog.Builder(this)
                .setTitle("手动端口")
                .setView(ll)
                .setPositiveButton("确定", (d, w) -> {
                    if (!pairPort.getText().toString().trim().isEmpty()) {
                        try {
                            discoveredPairPort = Integer.parseInt(pairPort.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                    }
                    if (!connPort.getText().toString().trim().isEmpty()) {
                        try {
                            discoveredConnPort = Integer.parseInt(connPort.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                    }
                    setStatus("已设置：配对端口=" + discoveredPairPort
                            + " 连接端口=" + (discoveredConnPort > 0 ? discoveredConnPort : 5555));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    private void startPair() {
        if (!com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) {
            requestNetworkPermission();
            return;
        }
        if (pairing) return;
        String code = codeEt.getText().toString().trim();
        if (code.length() < 6) {
            Toast.makeText(this, "配对码不足 6 位", Toast.LENGTH_SHORT).show();
            return;
        }
        pairing = true;
        startBtn.setEnabled(false);
        startBtn.setText("配对中…（首次自动装环境）");
        new Thread(() -> {
            final String out = doPair(code);
            runOnUiThread(() -> {
                pairing = false;
                startBtn.setEnabled(true);
                startBtn.setText("⚡ 开始配对");
                boolean ok = out.contains("PAIR_OK");
                setStatus(ok ? "🎉 配对成功！agent 可用：/root/dsh-bin/adb-shell \"id\"\n" + out : out);
                if (!ok) {
                    Toast.makeText(AdbPairActivity.this,
                            "配对未成功（可能是码已失效，回到无线调试重新点「使用配对码配对设备」）",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "adb-pair").start();
    }

    private String doPair(String code) {
        try {
            HarnessController c = new HarnessController(this);
            ProotBootstrap proot = c.proot();
            if (!proot.isEnvironmentReady()) {
                return "环境未就绪，请先启动一次让环境解压完成。";
            }
            String prep = AdbBridge.ensureReady(this, proot);
            if (!prep.contains("SETUP_DONE")) {
                return "环境准备失败，详见输出：\n" + prep;
            }
            String pp = discoveredPairPort > 0 ? String.valueOf(discoveredPairPort) : "";
            String cp = discoveredConnPort > 0 ? String.valueOf(discoveredConnPort) : "";
            return AdbBridge.pair(proot, code, pp, cp, localIp());
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** 本机 IPv4（部分 ROM 配对服务只监听 WiFi 接口，127.0.0.1 连不上）。 */
    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void discoverPorts() {
        try {
            NsdManager nm = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nm == null) return;
            discover(nm, "_adb-tls-pairing._tcp.", p -> {
                discoveredPairPort = p;
                runOnUiThread(() -> setStatus("检测到配对端口：" + p + "，直接输码即可 ~"));
            });
            discover(nm, "_adb-tls-connect._tcp.", p -> discoveredConnPort = p);
        } catch (Throwable ignored) {
        }
    }

    private void discover(final NsdManager nm, final String type, final java.util.function.IntConsumer sink) {
        try {
            final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
            holder[0] = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    nm.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            final int p = i.getPort();
                            if (p > 0) {
                                try { sink.accept(p); } catch (Throwable ignored) { }
                            }
                            try { nm.stopServiceDiscovery(holder[0]); } catch (Throwable ignored) { }
                        }
                    });
                }
                @Override public void onServiceLost(NsdServiceInfo info) { }
            };
            nm.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, holder[0]);
        } catch (Throwable ignored) {
        }
    }
}
