package com.deepseekharness.app;

import android.content.Intent;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 斜拉唤醒语音助手重定向模块（Java 版，DSHA 为纯 Java 工程）。
 *
 * 【背景】小米 SystemUI 的 onFsgestureEntered 硬编码 com.google.android.googlequicksearchbox，
 * 斜拉手势只唤醒 Google Gemini，不查询 ASSISTANT 角色是谁（实测：小爱同学也无法触发）。
 * 因此无论把默认助理设成哪个 App，斜拉手势都只会启动 Google 的 FloatyActivity。
 *
 * 【方案】保持 Google 为默认助理（保证手势可触发），在 Google 进程内 hook
 * com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity
 * 的 onCreate，转而拉起本包 AssistGatewayActivity（exported），由其接管交互。
 * AssistGatewayActivity 立即转拉 QuickChatSheetActivity 并自我关闭。
 *
 * 【作用域】system（CSMS 兜底，本机 CSMS 服务未启动，实际不生效）
 *         + com.google.android.googlequicksearchbox（主路径，FloatyActivity 拦截）
 */
public class CtsModuleMain extends XposedModule {

    private static final String TAG = "DSHA-CTS";

    /** 远程偏好文件名，与 App 侧 ConfigStore 共用（LSPosed 跨进程同步） */
    private static final String CONFIG_NAME = "cts_redirect_config";
    /** 开关键：true=重定向到本应用，false=回退系统默认（Google） */
    private static final String KEY_ENABLED = "enabled";

    /** 重定向目标包名 = 本应用 applicationId */
    private static final String TARGET_PACKAGE = "com.dsha.fr";

    /** Google Gemini 悬浮界面（斜拉手势的最终落地 Activity） */
    private static final String GOOGLE_FLOATY_ACTIVITY =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity";

    private static volatile CtsModuleMain instance;

    public CtsModuleMain() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        instance = this;
        log(Log.INFO, TAG, "Voice assist redirect module loaded");
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        // CSMS 兜底路径（圈定即搜）。本机 CSMS 服务未启动，此 hook 不会被调用，保留以防厂商差异。
        try {
            Class<?> csms = param.getClassLoader().loadClass(
                    "com.android.server.contextualsearch.ContextualSearchManagerService");
            hook(csms.getDeclaredMethod("getContextualSearchPackageName"))
                    .intercept(new GetCSPackageNameHooker());
            log(Log.INFO, TAG, "hook getContextualSearchPackageName installed");
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "hook CSMS fail", e);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        super.onPackageReady(param);
        if (!param.isFirstPackage()) return;

        if ("com.google.android.googlequicksearchbox".equals(param.getPackageName())) {
            // 主路径：拦截 Gemini 的 FloatyActivity，重定向到本应用
            try {
                Class<?> floaty = param.getClassLoader().loadClass(GOOGLE_FLOATY_ACTIVITY);
                hook(floaty.getDeclaredMethod("onCreate", android.os.Bundle.class))
                        .intercept(new FloatyRedirectHooker());
                log(Log.INFO, TAG, "hook FloatyActivity.onCreate installed");
            } catch (Throwable e) {
                log(Log.ERROR, TAG, "hook FloatyActivity fail", e);
            }
        }
    }

    /** 开关开 → 重定向；关 → 透传系统原逻辑（Google）。每次手势都实时读取，改开关即时生效。 */
    private boolean isEnabled() {
        try {
            return instance != null
                    && instance.getRemotePreferences(CONFIG_NAME).getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            // 远程偏好读取失败时默认启用（保守策略：功能可用优先）
            return true;
        }
    }

    /** CSMS 兜底：圈定即搜处理者重定向。 */
    private final class GetCSPackageNameHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (isEnabled()) {
                return TARGET_PACKAGE;
            }
            return chain.proceed();
        }
    }

    /**
     * 主路径：FloatyActivity.onCreate 拦截。
     * 当重定向开启时：
     * 1. 将窗口置为完全透明并抹去动画，消除 Google 悬浮卡片的入场白框与残影；
     * 2. 立即拉起本包 AssistGatewayActivity 并自我关闭；
     * 3. 反射设置 Activity.mCalled = true 满足系统生命周期检查，直接阻断 Google 子类
     *    执行后续的 View 布局膨胀与白框渲染；若反射异常则安全降级到 proceed()。
     */
    private final class FloatyRedirectHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (!isEnabled()) {
                return chain.proceed();
            }
            Object thisObject = chain.getThisObject();
            if (!(thisObject instanceof Activity)) {
                return chain.proceed();
            }
            Activity activity = (Activity) thisObject;

            try {
                // 1. 窗口完全透明并去除暗淡与动画，杜绝任何白框残影
                if (activity.getWindow() != null) {
                    activity.getWindow().setBackgroundDrawable(
                            new ColorDrawable(Color.TRANSPARENT));
                    activity.getWindow().setDimAmount(0f);
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                }
                activity.overridePendingTransition(0, 0);

                // 2. 立即启动网关并自我 finish
                Intent intent = new Intent();
                intent.setClassName(TARGET_PACKAGE,
                        "com.deepseekharness.app.ui.AssistGatewayActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                activity.startActivity(intent);
                activity.finish();
                activity.overridePendingTransition(0, 0);
                log(Log.INFO, TAG, "FloatyActivity redirected cleanly without white box");

                // 3. 满足系统 super.onCreate 检查，阻断 Google 子类 View 加载
                Field mCalledField = Activity.class.getDeclaredField("mCalled");
                mCalledField.setAccessible(true);
                mCalledField.setBoolean(activity, true);
                return null;
            } catch (Throwable e) {
                log(Log.WARN, TAG, "redirect FloatyActivity clean interception fallback", e);
                // 兜底：若反射受限则保证 Google 不闪退
                return chain.proceed();
            }
        }
    }
}
