package com.deepseekharness.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启（参考 Shizuku 生态 pixincreate/Shizuku 等项目的无 Root 自启思路）：
 * 监听 BOOT_COMPLETED / MY_PACKAGE_REPLACED（覆盖安装），
 * 若用户开启了「启用 ADB 设备通道」，自动拉起 DeviceBridgeService →
 * 连接看门狗（startConnWatcher）会自动重连已配对的设备，实现 ADB 永不掉。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? "" : intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                    && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                return;
            }
            // 用户没开 ADB 就不拉起（尊重开关）
            if (!DeviceBridgeService.isAdbEnabled(context)) return;
            android.util.Log.i("DSHA-ADB", "开机/升级自启：拉起 ADB 设备桥（看门狗将自动重连）");
            DeviceBridgeService.apply(context);
        } catch (Throwable ignored) {
        }
    }
}
