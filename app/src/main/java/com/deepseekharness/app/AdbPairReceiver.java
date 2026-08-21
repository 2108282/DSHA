package com.deepseekharness.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 通知卡片就地输入配对码 → 后台完成 ADB 无线配对（免 Shizuku）。
 *
 * 通知上的「🔐 输码配对」action 带 RemoteInput，用户直接在通知卡片里输入
 * 6 位配对码（不离开通知栏），提交后这里后台执行：Nsd 自动发现端口 →
 * ensureReady(幂等，环境已后台预热则秒回) → 单次配对 → 结果推回通知。
 */
public class AdbPairReceiver extends BroadcastReceiver {

    public static final String ACTION_PAIR = "com.deepseekharness.app.ADB_PAIR";
    public static final String EXTRA_CODE = "adb_pair_code";

    private static final String RESULT_CHANNEL = "dsh_adbpair_channel";
    private static final int RESULT_NOTIF_ID = 3004;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PAIR.equals(intent.getAction())) return;
        if (!DeviceBridgeService.isAdbEnabled(context)) {
            notifyResult(context, "ADB 已关闭", "设置 → 配置里先勾选「启用 ADB」并保存。", false);
            return;
        }
        CharSequence cs = null;
        try {
            Bundle result = RemoteInput.getResultsFromIntent(intent);
            cs = result != null ? result.getCharSequence(EXTRA_CODE) : null;
        } catch (Throwable ignored) {
        }
        final String code = cs == null ? "" : cs.toString().trim();
        if (code.length() < 6) {
            notifyResult(context, "配对码不足 6 位", "请在通知里重新输入手机屏幕上的 6 位配对码。", false);
            return;
        }

        new Thread(() -> {
            String out;
            try {
                HarnessController hc = HarnessController.get(context);
                if (hc == null || !hc.getProot().isInstalled()) {
                    out = "环境未安装，请先到「安装」页装好 deepseek-harness。";
                } else {
                    // 优先用设备桥服务缓存的配对端口（弹窗监听已捕获，秒级直用）；
                    // 但缓存端口可能已过期（配对弹窗 2 分钟失效/重开无线调试换端口）：
                    // 先探测可达性，不通则实时 Nsd 重新发现（≤5s），再兜底容器内 mdns。
                    String[] discovered = null;
                    int cached = DeviceBridgeService.pairPort;
                    if (cached > 0 && portReachable(cached)) {
                        discovered = new String[]{DeviceBridgeService.pairHost, String.valueOf(cached), ""};
                    } else {
                        discovered = discoverPortsSync(context, 5000);
                    }
                    String host = discovered[0];
                    String pp = discovered[1] != null && !discovered[1].isEmpty() && Integer.parseInt(discovered[1]) > 0
                            ? discovered[1] : "";
                    String cp = discovered[2];
                    String prep = AdbBridge.ensureReady(context, hc.getProot());
                    if (!prep.contains("SETUP_DONE")) {
                        out = "环境准备失败，详见输出：\n" + prep
                                + "\n\n完整日志：App 终端里执行\ncat /root/.dsh/adb-setup.log";
                    } else {
                        out = AdbBridge.pair(hc.getProot(), code, pp, cp, host);
                    }
                }
            } catch (Throwable e) {
                out = "ERROR: " + e;
            }
            boolean ok = out.contains("PAIR_OK");
            // 失败时把完整输出注入内置终端（通知栏有字数上限且无法复制，终端可滚动可复制）
            if (!ok) TerminalFragment.inject("========== ADB 配对失败 ==========\n" + out);
            notifyResult(context,
                    ok ? "🎉 ADB 配对成功！" : "❌ ADB 配对失败",
                    ok
                            ? "已直连 adbd（uid=2000），agent 可用：\n/root/dsh-bin/adb-shell \"id\"\n\n" + out
                            : "配对未成功。完整日志已写入「终端」页（可滚动/复制）：\n"
                            + (out.contains("PIP_MISSING")
                                ? "rootfs 缺 pip → 已尝试自动安装，请看终端日志"
                                : out.contains("DEPS_FAILED")
                                    ? "Python 依赖安装失败 → 请看终端日志"
                                    : out.contains("PORT_UNREACHABLE")
                                        ? "配对端口连不上 → 请回手机「无线调试」重新点「使用配对码配对设备」"
                                        : out.contains("SPAKE2_ERROR")
                                            ? "配对码错误/已失效 → 重新打开配对弹窗，输入新码"
                                            : out.contains("TLS_ERROR")
                                                ? "TLS 握手失败 → 重开无线调试后重试"
                                                : "请回手机「无线调试」重新点「使用配对码配对设备」，再在该卡片重新输入新码。详情见终端页"),
                    ok);
        }, "dsha-adb-pair").start();
    }

    /** 同步阻塞地从 NsdManager 发现配对/连接端口（INLINE 输入场景：不离开通知栏）。
     *  返回 String[3]：{host, pairPort, connectPort}——host 为解析出的真实 IP
     *  （部分 ROM 配对服务只监听 WiFi 接口，127.0.0.1 连不上，必须传真实 IP）。 */
    private static String[] discoverPortsSync(Context ctx, long timeoutMs) {
        final String[] result = new String[3];
        // 配对只需要 pair 端口：发现即放行（不必等两个都超时）
        final CountDownLatch done = new CountDownLatch(1);
        try {
            NsdManager nm = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nm != null) {
                discoverAsync(nm, "_adb-tls-pairing._tcp.", (host, p) -> {
                    result[0] = host;
                    result[1] = String.valueOf(p);
                    done.countDown();
                });
                discoverAsync(nm, "_adb-tls-connect._tcp.", (host, p) -> {
                    result[2] = String.valueOf(p);
                    // 若 pair 已找到则不重复放行；connect 先到也先放行（脚本能自发现）
                    done.countDown();
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /** 快速探测某 TCP 端口在本机是否可达（判断缓存配对端口是否过期） */
    private static boolean portReachable(int port) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1500);
            s.close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void discoverAsync(final NsdManager nm, final String type,
                                      final java.util.function.BiConsumer<String, Integer> sink) {
        try {
            final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
            holder[0] = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    nm.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            int p = serviceInfo.getPort();
                            if (p > 0) {
                                try {
                                    // host 可能是 IPv6 字面量，去括号
                                    String h = "";
                                    if (serviceInfo.getHost() != null) {
                                        h = serviceInfo.getHost().getHostAddress();
                                        if (h != null && h.startsWith("[") && h.endsWith("]")) {
                                            h = h.substring(1, h.length() - 1);
                                        }
                                    }
                                    sink.accept(h == null ? "" : h, p);
                                } catch (Throwable ignored) {
                                }
                            }
                            try {
                                nm.stopServiceDiscovery(holder[0]);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nm.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, holder[0]);
        } catch (Throwable ignored) {
        }
    }

    private static void notifyResult(Context ctx, String title, String text, boolean ok) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            // 最多一条配对通知：弹结果前先清掉「输入配对码」卡（3101），
            // 避免配对卡 + 结果通知并存（用户反馈：最多只存在一条配对通知）
            try {
                nm.cancel(Constants.NOTIF_ADB_PAIR_CARD);
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(RESULT_CHANNEL, "ADB 配对结果",
                        ok ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, RESULT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle(title)
                    .setContentText(ok ? "已直连 adbd（uid=2000）" : "请重开无线调试配对后重输")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(ok ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT);
            nm.notify(RESULT_NOTIF_ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
