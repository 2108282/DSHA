package com.deepseekharness.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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

        // 0. 【系统语音最上游拦截】在 VoiceInteractionManagerService 中前置拦截：
        // 凡是系统通过手势或按键呼出 Google 语音会话 (showSessionForActiveService) 时，
        // 直接在系统层改写为拉起 DSHA 抽屉，并直接阻断原方法（不向下通知 Google）！
        // 这样 Google 连一次 Session 启动的信号都收不到，100% 杜绝 Google 麦克风被开启！
        try {
            String[] vimsClasses = new String[]{
                    "com.android.server.voiceinteraction.VoiceInteractionManagerService$VoiceInteractionManagerServiceStub",
                    "com.android.server.voiceinteraction.VoiceInteractionManagerService"
            };
            int vimsHookCount = 0;
            for (String clsName : vimsClasses) {
                try {
                    Class<?> cls = param.getClassLoader().loadClass(clsName);
                    for (Method m : cls.getDeclaredMethods()) {
                        if ("showSessionForActiveService".equals(m.getName())) {
                            hook(m).intercept(new VimsShowSessionHooker());
                            vimsHookCount++;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            log(Log.INFO, TAG, "hook VIMS.showSessionForActiveService installed (count=" + vimsHookCount + ")");
        } catch (Throwable e) {
            log(Log.WARN, TAG, "hook VIMS skip", e);
        }

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

                // 核心安全网：拦截 Google 进程内的 VoiceInteractionSession.onShow，直接隐藏并释放麦克风
                try {
                    Class<?> sessionCls = param.getClassLoader().loadClass("android.service.voice.VoiceInteractionSession");
                    for (Method m : sessionCls.getDeclaredMethods()) {
                        if ("onShow".equals(m.getName())) {
                            hook(m).intercept(new VoiceSessionOnShowHooker());
                        }
                    }
                    log(Log.INFO, TAG, "hook VoiceInteractionSession.onShow fallback installed");
                } catch (Throwable e) {
                    log(Log.DEBUG, TAG, "hook VoiceInteractionSession skip", e);
                }

                log(Log.INFO, TAG, "hook FloatyActivity fallback installed cleanly");
            } catch (Throwable e) {
                log(Log.ERROR, TAG, "hook FloatyActivity fail", e);
            }
        }
    }

    private static volatile boolean sCachedEnabled = true;
    private static volatile long sLastPrefFetchMs = 0L;
    private static final long PREF_CACHE_TTL_MS = 5_000L;

    /** 手势重定向全局防抖时间戳（杜绝 VIMS 拦截与 ATMS 拦截重复触发两次） */
    private static volatile long sLastGestureRedirectMs = 0L;
    private static final long GESTURE_REDIRECT_DEBOUNCE_MS = 800L;

    /** 开关开 → 重定向；关 → 透传系统原逻辑。带 TTL 内存缓存，严禁在 ATMS 核心锁路径同步跨进程 IPC。 */
    private boolean isEnabled() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastPrefFetchMs < PREF_CACHE_TTL_MS) {
            return sCachedEnabled;
        }
        sLastPrefFetchMs = now;
        try {
            if (instance != null) {
                sCachedEnabled = instance.getRemotePreferences(CONFIG_NAME).getBoolean(KEY_ENABLED, true);
            }
        } catch (Throwable t) {
            // 异常时维持已有缓存值，绝不阻塞调用者
        }
        return sCachedEnabled;
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
            List<?> args = chain.getArgs();
            if (args == null || args.isEmpty()) {
                return chain.proceed();
            }

            Intent targetIntent = null;
            ComponentName comp = null;
            String act = null;
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    Intent intent = (Intent) arg;
                    comp = intent.getComponent();
                    String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
                    String cls = comp != null ? comp.getClassName() : "";
                    act = intent.getAction();
                    android.net.Uri data = intent.getData();

                    // 1. 绝对放行白名单：任何包含 Bard/Gemini 独立应用或其 DeepLink 的启动坚决不拦截
                    if ("com.google.android.apps.bard".equals(pkg) || cls.contains("com.google.android.apps.bard")) {
                        continue;
                    }
                    if (data != null && data.getHost() != null &&
                            (data.getHost().contains("bard.google.com") || data.getHost().contains("gemini.google.com"))) {
                        continue;
                    }
                    // 2. 普通网页浏览与深层链接 (ACTION_VIEW) 绝对放行，绝非语音手势
                    if (Intent.ACTION_VIEW.equals(act)) {
                        continue;
                    }

                    boolean isGooglePkg = "com.google.android.googlequicksearchbox".equals(pkg);
                    // 精准限定语音助手专属悬浮窗类，杜绝模糊子串 "Assist" 误伤 MainAssistantDeeplinkAnimated 等正常页面
                    boolean isGoogleAssistCls = isGooglePkg && !cls.isEmpty() && (
                            cls.contains("FloatyActivity")
                                    || cls.endsWith("VoiceSearchActivity")
                                    || cls.endsWith("OpaSearchActivity"));

                    boolean isAssistAction = Intent.ACTION_ASSIST.equals(act)
                            || Intent.ACTION_VOICE_COMMAND.equals(act)
                            || "android.intent.action.VOICE_ASSIST".equals(act);

                    boolean isGoogleTarget = (isGooglePkg && isGoogleAssistCls) || (isGooglePkg && isAssistAction);
                    boolean isCtsAction = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH".equals(act);

                    if (isGoogleTarget || isCtsAction) {
                        targetIntent = intent;
                        break;
                    }
                }
            }

            // 极速前置过滤：非目标调用 0 开销直接放行，绝不阻碍系统其他任何 Activity 的启动调度！
            if (targetIntent == null) {
                return chain.proceed();
            }

            // 仅在命中目标后，读取内存缓存的开关
            if (!isEnabled()) {
                return chain.proceed();
            }

            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sLastGestureRedirectMs < GESTURE_REDIRECT_DEBOUNCE_MS) {
                // 核心防抖安全网：如果 800ms 内已被 VIMS 或上一次手势成功拉起，
                // 则本次由系统 Fallback 产生的重复启动直接截断返回成功 (0)，彻底杜绝抽屉被二次拉起！
                log(Log.INFO, TAG, "ATMS assist startActivity suppressed by debounce: "
                        + (comp != null ? comp.flattenToShortString() : act));
                return 0; // START_SUCCESS = 0
            }
            sLastGestureRedirectMs = now;

            log(Log.INFO, TAG, "ATMS pre-intercepted assist startActivity: "
                    + (comp != null ? comp.flattenToShortString() : act)
                    + " -> cleanly redirected to " + TARGET_PACKAGE);
            targetIntent.setComponent(new ComponentName(TARGET_PACKAGE,
                    "com.deepseekharness.app.ui.AssistGatewayActivity"));
            targetIntent.setAction(Intent.ACTION_ASSIST);
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            // 彻底根除 Google 后台语音空转：主动通知系统销毁/隐藏 Google 的 VoiceInteractionSession
            dismissActiveVoiceSession();

            return chain.proceed();
        }
    }

    /** 主动通知系统 VoiceInteractionManagerService 隐藏并结束活跃会话，释放被 Google 占用的麦克风。 */
    private static void dismissActiveVoiceSession() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "voiceinteraction");
            if (binder != null) {
                Class<?> stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService$Stub");
                Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
                Object service = asInterface.invoke(null, binder);
                if (service != null) {
                    Method hideSession = service.getClass().getMethod("hideCurrentSession");
                    hideSession.invoke(service);
                    Log.i(TAG, "dismissActiveVoiceSession: successfully hid voice session and released mic");
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "dismissActiveVoiceSession skip: " + t.getMessage());
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
                dismissActiveVoiceSession();
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

    /**
     * 【系统语音源头终极拦截】
     * 在 system_server 层直接拦截系统手势/按键向默认助理发起的 showSessionForActiveService，
     * 100% 从源头直接转跳 DSHA 抽屉，并阻断向 Google 发送会话启动指令！
     */
    private final class VimsShowSessionHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (!isEnabled()) {
                return chain.proceed();
            }

            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sLastGestureRedirectMs < GESTURE_REDIRECT_DEBOUNCE_MS) {
                // 防抖窗口内，直接阻断，不重复启动
                return false;
            }

            try {
                Context context = null;
                Object thisObj = chain.getThisObject();
                if (thisObj != null) {
                    try {
                        Field f = thisObj.getClass().getDeclaredField("mContext");
                        f.setAccessible(true);
                        context = (Context) f.get(thisObj);
                    } catch (Throwable t) {
                        try {
                            Field f2 = thisObj.getClass().getDeclaredField("this$0");
                            f2.setAccessible(true);
                            Object parent = f2.get(thisObj);
                            Field f = parent.getClass().getDeclaredField("mContext");
                            f.setAccessible(true);
                            context = (Context) f.get(parent);
                        } catch (Throwable ignored) {}
                    }
                }

                if (context == null) {
                    try {
                        Class<?> atCls = Class.forName("android.app.ActivityThread");
                        Method currentAppM = atCls.getMethod("currentApplication");
                        context = (Context) currentAppM.invoke(null);
                    } catch (Throwable ignored) {}
                }

                if (context != null) {
                    sLastGestureRedirectMs = now;
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(TARGET_PACKAGE,
                            "com.deepseekharness.app.ui.AssistGatewayActivity"));
                    intent.setAction(Intent.ACTION_ASSIST);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

                    final long origId = android.os.Binder.clearCallingIdentity();
                    try {
                        context.startActivity(intent);
                        log(Log.INFO, TAG, "VIMS showSession pre-intercepted cleanly -> redirected to DSHA");
                    } finally {
                        android.os.Binder.restoreCallingIdentity(origId);
                    }
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "VIMS pre-intercept redirect fail", t);
            }

            // 彻底阻断向下调用 Google：返回 false 通知调用方会话未能展示，Google 根本收不到任何信号！
            return false;
        }
    }

    /**
     * Google 进程内的 Session 兜底：若有漏网之鱼仍创建了 VoiceInteractionSession 并触发 onShow，
     * 立即拉起 DSHA 抽屉并命令 session.hide()，彻底释放麦克风！
     */
    private final class VoiceSessionOnShowHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (!isEnabled()) {
                return chain.proceed();
            }
            try {
                Object thisObj = chain.getThisObject();
                if (thisObj instanceof android.service.voice.VoiceInteractionSession) {
                    android.service.voice.VoiceInteractionSession session =
                            (android.service.voice.VoiceInteractionSession) thisObj;
                    Context ctx = session.getContext();
                    if (ctx != null) {
                        Intent intent = new Intent();
                        intent.setComponent(new ComponentName(TARGET_PACKAGE,
                                "com.deepseekharness.app.ui.AssistGatewayActivity"));
                        intent.setAction(Intent.ACTION_ASSIST);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        ctx.startActivity(intent);
                    }
                    session.hide();
                    log(Log.INFO, TAG, "VoiceInteractionSession.onShow intercepted and forced to hide");
                    return null;
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "VoiceInteractionSession onShow intercept fail", t);
            }
            return chain.proceed();
        }
    }
}
