package com.deepseekharness.app;

import android.util.Log;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 圈定即搜（Contextual Search）重定向模块入口（Java 版，DSHA 为纯 Java 工程）。
 *
 * 作用域：仅 system（hook 运行在 system_server 的 ContextualSearchManagerService）。
 * 原理：HyperOS 的圈定即搜手势最终汇聚到
 *   {@code ContextualSearchManagerService.getContextualSearchPackageName()}
 * 该方法返回处理者包名（默认 framework-res 写死为 Google）。
 * hook 它返回本包 {@code com.dsha.fr}，系统即在本包内用
 * ACTION_LAUNCH_CONTEXTUAL_SEARCH 解析到 QuickChatSheetActivity 并拉起。
 *
 * 相比 MiCTS 的 5 个 hook，本机已原生支持 CTS 服务，只需这 1 个核心 hook。
 * deviceHasConfigString / enforcePermission / VIMS 借道等均不需要：
 * 手势触发方是 SystemUI，自带 ACCESS_CONTEXTUAL_SEARCH 权限。
 */
public class CtsModuleMain extends XposedModule {

    private static final String TAG = "DSHA-CTS";

    /** 远程偏好文件名，与 App 侧 ConfigStore 共用（LSPosed 跨进程同步） */
    private static final String CONFIG_NAME = "cts_redirect_config";
    /** 开关键：true=重定向到本应用，false=回退系统默认（Google） */
    private static final String KEY_ENABLED = "enabled";

    /** 重定向目标包名 = 本应用 applicationId */
    private static final String TARGET_PACKAGE = "com.dsha.fr";

    private static volatile CtsModuleMain instance;

    public CtsModuleMain() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        instance = this;
        log(Log.INFO, TAG, "CTS redirect module loaded");
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        // 设备已确认存在 contextual_search 服务（cmd contextual_search 可用），
        // 只需主路径。反射探测 + try/catch 兜底，避免厂商改类名导致 system_server 崩溃。
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

    /** 开关开 → 返回本包；关 → 透传系统原逻辑（Google）。每次手势都实时读取，改开关即时生效。 */
    private boolean isEnabled() {
        try {
            return instance != null
                    && instance.getRemotePreferences(CONFIG_NAME).getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            // 远程偏好读取失败时默认启用（保守策略：功能可用优先）
            return true;
        }
    }

    private final class GetCSPackageNameHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            if (isEnabled()) {
                return TARGET_PACKAGE;
            }
            return chain.proceed();
        }
    }
}
