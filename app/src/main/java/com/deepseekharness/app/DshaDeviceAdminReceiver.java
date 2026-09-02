package com.deepseekharness.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * DeviceOwner 用的管理组件。
 *
 * <p>它必须存在、必须在清单里声明、必须有 {@code BIND_DEVICE_ADMIN} 权限 ——
 * {@code dpm set-device-owner} 认的就是这个组件名。方法体刻意留空：
 * 我们要的是 DeviceOwner 这个**角色**（静默安装、授权限、不被系统杀），
 * 不是设备管理类 App 那套锁屏密码策略。
 *
 * <p>{@link #onDisableRequested} 返回一句提示：用户从系统设置里撤销时，
 * 至少知道会失去什么。真正的撤销入口在 App 内（clearDeviceOwnerApp），
 * 那条路更可靠 —— 上游 Dhizuku 最常见的求助就是「找不到怎么关，只能恢复出厂」。
 */
public class DshaDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        try {
            HarnessController.get(context).logActivity(
                    "已成为设备所有者（DeviceOwner）—— 系统不再杀本应用，agent 可静默装卸应用与授权限");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "撤销后：系统会重新按省电策略冻结后台，agent 也失去静默安装与授权限的能力。";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        try {
            HarnessController.get(context).logActivity("设备所有者权限已撤销");
        } catch (Throwable ignored) {
        }
    }
}
