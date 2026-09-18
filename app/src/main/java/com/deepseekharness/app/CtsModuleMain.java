package com.deepseekharness.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 快捷手势与语音助手重定向模块（Java 版，DSHA 为纯 Java 工程）。
 *
 * 【核心架构升级 · 源头零唤醒重定向】
 * 之前机制：系统按默认助理配置唤醒 Google，Google 进程启动并初始化后在 FloatyActivity.onCreate
 * 被拦截并 finish，导致每次手势都必须唤醒庞大的 Google 进程，且破坏生命周期引发 Peer 致命异常崩溃，
 * 造成双应用并发占用高 CPU。
 *
 * 现机制：
 * 1. 【system_server 前置源头拦截（主路径）】在系统 ActivityTaskManagerService (ATMS) 分发
 *    startActivityAsUser 时，直接检测指向 Google 语音助手/FloatyActivity/CTS 的 Intent，
 *    在系统调度第一瞬间直接改写为拉起 DSHA 网关/抽屉。Google 进程根本不会被创建与唤醒，CPU 占用为 0！
 * 2. 【Google 进程兜底防崩】若有遗留进程被调起，在拦截 FloatyActivity 的同时，静默跳过 onUserLeaveHint，
 *    彻底消除 createPeer() called outside of onCreate 崩溃异常。
 * 3. 【CSMS 兜底】兼容标准 Android 圈定即搜 (ContextualSearchManagerService)。
 */
public class CtsModuleMain extends XposedModule {

    private static final String TAG = "DSHA-CTS";

    /** 远程偏好文件名，与 App 侧 ConfigStore 共用（LSPosed 跨进程同步） */
    private static final String CONFIG_NAME = "cts_redirect_config";
    /** 开关键：true=重定向到本应用，false=回退系统默认 */
    private static final String KEY_ENABLED = "enabled";

    /** 重定向目标包名 = 本应用 applicationId */
    private static final String TARGET_PACKAGE = "com.dsha.fr";

    /** Google Gemini / Assistant 悬浮界面（手势的目标 Activity） */
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

        // 1. 【核心主路径】在 system_server 的 ATMS 中进行前置源头拦截：
        // 凡是系统或进程试图启动 Google 语音助手/FloatyActivity 的 Intent，直接改写为 DSHA 抽屉网关。
        // 从源头彻底阻断 Google 进程的创建与冷启动，实现 0 CPU 消耗与系统级秒开！
        try {
            Class<?> atms = param.getClassLoader().loadClass("com.android.server.wm.ActivityTaskManagerService");
            int hookedCount = 0;
            for (Method m : atms.getDeclaredMethods()) {
                if ("startActivityAsUser".equals(m.getName())) {
                    hook(m).intercept(new AtmsStartActivityHooker());
                    hookedCount++;
                }
            }
            log(Log.INFO, TAG, "hook ATMS.startActivityAsUser installed (count=" + hookedCount + ")");
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "hook ATMS fail", e);
        }

        // 2. CSMS 兜底路径（AOSP 标准圈定即搜）
        try {
            Class<?> csms = param.getClassLoader().loadClass(
                    "com.android.server.contextualsearch.ContextualSearchManagerService");
            hook(csms.getDeclaredMethod("getContextualSearchPackageName"))
                    .intercept(new GetCSPackageNameHooker());
            log(Log.INFO, TAG, "hook getContextualSearchPackageName installed");
        } catch (Throwable e) {
            log(Log.DEBUG, TAG, "hook CSMS skip (service not present on this ROM)");
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        super.onPackageReady(param);
        if (!param.isFirstPackage()) return;

        if ("com.google.android.googlequicksearchbox".equals(param.getPackageName())) {
            // 兜底路径：若有特殊路径仍进入 Google 进程，优雅接管并消除 Peer 崩溃
            try {
                Class<?> floaty = param.getClassLoader().loadClass(GOOGLE_FLOATY_ACTIVITY);
                hook(floaty.getDeclaredMethod("onCreate", android.os.Bundle.class))
                        .intercept(new FloatyRedirectHooker());

                // 核心防崩安全网：拦截 onUserLeaveHint，防止 Peer 为 null 抛出致命崩溃
                try {
                    hook(floaty.getDeclaredMethod("onUserLeaveHint"))
                            .intercept(new FloatyUserLeaveHooker());
                } catch (Throwable ignored) {}

                log(Log.INFO, TAG, "hook FloatyActivity fallback installed cleanly");
            } catch (Throwable e) {
                log(Log.ERROR, TAG, "hook FloatyActivity fail", e);
            }
        }
    }

    /** 开关开 → 重定向；关 → 透传系统原逻辑。每次手势都实时读取，改开关即时生效。 */
    private boolean isEnabled() {
        try {
            return instance != null
                    && instance.getRemotePreferences(CONFIG_NAME).getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 【源头拦截核心】ATMS.startActivityAsUser Hooker。
     * 当拦截到启动目标是 Google Assistant / FloatyActivity / CTS 时，
     * 直接在 system_server 层面将 Intent 目标组件改写为 DSHA 的 AssistGatewayActivity，
     * 让系统直接拉起 DSHA，彻底杜绝 Google 进程被唤醒！
     */
    private final class AtmsStartActivityHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (!isEnabled()) {
                return chain.proceed();
            }

            Object[] args = chain.getArgs();
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Intent) {
                        Intent intent = (Intent) args[i];
                        ComponentName comp = intent.getComponent();
                        String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
                        String cls = comp != null ? comp.getClassName() : "";
                        String act = intent.getAction();

                        boolean isGoogleTarget = "com.google.android.googlequicksearchbox".equals(pkg)
                                && (cls.contains("FloatyActivity")
                                        || cls.contains("VoiceSearchActivity")
                                        || cls.contains("OpaSearchActivity"));

                        boolean isCtsAction = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH".equals(act);

                        if (isGoogleTarget || isCtsAction) {
                            log(Log.INFO, TAG, "ATMS pre-intercepted assist startActivity: "
                                    + (comp != null ? comp.flattenToShortString() : act)
                                    + " -> cleanly redirected to " + TARGET_PACKAGE);
                            intent.setClassName(TARGET_PACKAGE,
                                    "com.deepseekharness.app.ui.AssistGatewayActivity");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                            break;
                        }
                    }
                }
            }
            return chain.proceed();
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
     * 兜底路径：FloatyActivity.onCreate 拦截。
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
                if (activity.getWindow() != null) {
                    activity.getWindow().setBackgroundDrawable(
                            new ColorDrawable(Color.TRANSPARENT));
                    activity.getWindow().setDimAmount(0f);
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                }
                activity.overridePendingTransition(0, 0);

                Intent intent = new Intent();
                intent.setClassName(TARGET_PACKAGE,
                        "com.deepseekharness.app.ui.AssistGatewayActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                activity.startActivity(intent);
                activity.finishAndRemoveTask();
                activity.overridePendingTransition(0, 0);
                log(Log.INFO, TAG, "FloatyActivity redirected cleanly via fallback");

                // 满足系统 super.onCreate 检查，阻断 Google 子类 View 加载
                Field mCalledField = Activity.class.getDeclaredField("mCalled");
                mCalledField.setAccessible(true);
                mCalledField.setBoolean(activity, true);
                return null;
            } catch (Throwable e) {
                log(Log.WARN, TAG, "redirect FloatyActivity clean interception fallback", e);
                return chain.proceed();
            }
        }
    }

    /**
     * 核心防崩安全网：当 FloatyActivity 被拦截跳过 onCreate 时，Peer 对象为 null。
     * 系统生命周期调用 onUserLeaveHint 时会访问 Peer 导致 IllegalStateException Crash。
     * 本 Hooker 安全拦截该回调并静默跳过，彻底解决崩溃问题。
     */
    private final class FloatyUserLeaveHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (isEnabled()) {
                return null;
            }
            return chain.proceed();
        }
    }
}
