package com.deepseekharness.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机与升级事件接收器：支持开机静默就绪。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // 开机广播接收入口（保留供后续开机自启能力扩展）
    }
}
