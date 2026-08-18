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
                    // 兜底再在通知栏内快速发现端口（≤5s）
                    int cached = DeviceBridgeService.pairPort;
                    int[] ports = cached > 0
                            ? new int[]{cached, 0}
                            : discoverPortsSync(context, 5000);
                    String pp = ports[0] > 0 ? String.valueOf(ports[0]) : "";
                    String cp = ports[1] > 0 ? String.valueOf(ports[1]) : "";
                    String prep = AdbBridge.ensureReady(context, hc.getProot());
                    if (!prep.contains("SETUP_DONE")) {
                        out = "环境准备失败，详见输出：\n" + prep;
                    } else {
                        out = AdbBridge.pair(hc.getProot(), code, pp, cp);
                    }
                }
            } catch (Throwable e) {
                out = "ERROR: " + e;
            }
            boolean ok = out.contains("PAIR_OK");
            notifyResult(context,
                    ok ? "🎉 ADB 配对成功！" : "❌ ADB 配对失败",
                    ok
                            ? "已直连 adbd（uid=2000），agent 可用：\n/root/dsh-bin/adb-shell \"id\"\n\n" + out
                            : "配对未成功（可能是码已失效）。\n请回手机「无线调试」重新点「使用配对码配对设备」，再在该卡片重新输入新码。\n\n" + out,
                    ok);
        }, "dsha-adb-pair").start();
    }

    /** 同步阻塞地从 NsdManager 发现配对/连接端口（INLINE 输入场景：不离开通知栏） */
    private static int[] discoverPortsSync(Context ctx, long timeoutMs) {
        final int[] ports = new int[2];
        final CountDownLatch done = new CountDownLatch(2);
        try {
            NsdManager nm = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nm != null) {
                discoverAsync(nm, "_adb-tls-pairing._tcp.", p -> {
                    ports[0] = p;
                    done.countDown();
                });
                discoverAsync(nm, "_adb-tls-connect._tcp.", p -> {
                    ports[1] = p;
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
        return ports;
    }

    private static void discoverAsync(final NsdManager nm, final String type,
                                      final java.util.function.IntConsumer sink) {
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
                                    sink.accept(p);
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
