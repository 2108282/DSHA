# DSHA Shizuku / Stellar 底层通信与澎湃 OS (Android 15+) 适配排查与双保险修复记录 (修复shizuku.md)

本文档完整记录了 DSHA 在高版本系统（小米澎湃 OS HyperOS / Android 15/16 SDK 36）以及使用 **Shizuku / Stellar** 进行授权时，底层 Shell 通道绑定挂起排查根因、双保险架构设计与完整落地方案。

---

## 🎯 一、 现象

在部分高版本 Android 设备（如小米澎湃 OS HyperOS / Android 15/16 SDK 36）以及使用 **Shizuku / Stellar** 进行授权时，即使在管理器端已授予权限，DSHA 依然无法执行任何 shell 命令，`/exec?cmd=...` 恒返回：
```json
{"result":"[SHIZUKU_SERVICE_NOT_READY]"}
```

通过增加诊断端点排查，发现系统处于 `binder=true, permission=granted, bound=false, binding=true` 状态（即已拿到 Binder 并授权，但 `UserService` 绑定永远挂起无法完成握手）。

---

## 🔍 二、 原因深度排查

### 1. `Shizuku.UserServiceArgs` 缺少必填参数导致 NPE（Shizuku 13.x+）
在 `dev.rikka.shizuku:api:13.1.5` 的 `Shizuku.java` 内部 `args.forAdd()`（第 673 行）方法中，对 `processNameSuffix` 有非空断言：
```java
options.putString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
        Objects.requireNonNull(processName, "process name suffix must not be null"));
```
当前 `ShizukuShell.java` 中构建 `UserServiceArgs` 时仅传了 ComponentName，漏掉了 `.processNameSuffix("shizuku")`，导致每次 `ensureBound` 触发时均在内部抛出 NullPointerException：
```text
java.lang.NullPointerException: process name suffix must not be null
    at java.util.Objects.requireNonNull(Objects.java:247)
    at rikka.shizuku.Shizuku$UserServiceArgs.forAdd(Shizuku.java:673)
    at rikka.shizuku.Shizuku.bindUserService(Shizuku.java:734)
    at com.deepseekharness.app.ShizukuShell.ensureBound(ShizukuShell.java:135)
```
由于外层有 `try...catch (Throwable e)`，该异常被直接吞掉，导致请求压根没有到达服务端。

### 2. `DshaApp.onCreate()` 在 `app_process` 无 UI 进程中崩溃
Shizuku / Stellar 服务端拉起 `UserService` 时会通过 `app_process` 启动独立进程并反射调用 `makeApplication()` 初始化 Application。
`DshaApp.onCreate()` 第一句即执行：
```java
AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
```
在无 GUI/Theme 上下文的 `app_process` 进程中，这句会直接抛异常导致子进程闪退。且因其运行在 `UID=2000 (shell)` 身份下，无法写入 App 私有目录的 `crash.log`，故障极其隐蔽。

### 3. 高版本系统沙箱对 `app_process` 跨进程 `ContentProvider` 握手的物理拦截
在小米澎湃 OS 等系统上，底层 SELinux 策略对 `app_process` 子进程通过 `ContentProvider` 向管理器回传 Binder 的行为进行了静默拦截，导致 `UserService` 远程握手永远无法送达，`onServiceConnected` 永不触发。

---

## 🛠️ 三、 修复方案（规范修补 + 引入 execDirect 兜底双保险）

我们在实际真机上已经完整验证并跑通了这套**「组合拳」落地方案**：
1. **先规范化修补** `UserService` 构造参数与 `DshaApp` 进程隔离（消除代码层缺陷）；
2. **同时引入 `execDirect`**（利用底层 `IShizukuService.newProcess` 管道原生直通执行命令），在 `shellService == null` 或被厂商系统拦截时自动无缝降级兜底，彻底免疫所有定制系统的跨进程拦截！

### 完整改动代码：

#### 1. `app/src/main/java/com/deepseekharness/app/DshaApp.java`（增加进程名隔离防崩溃）
```java
@Override
public void onCreate() {
    super.onCreate();

    // 防御：如果是 Shizuku/Stellar 的 UserService 进程 (UID 2000 app_process)，
    // 不能执行 AppCompat 强依赖宿主 Context 的操作，否则会导致子进程秒崩。
    String processName = Application.getProcessName();
    if (processName != null && (processName.contains(":shizuku") || processName.contains(":service"))) {
        return;
    }

    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    ...
```

#### 2. `app/src/main/java/com/deepseekharness/app/ShellService.java`（补齐规范化构造与清理）
```java
package com.deepseekharness.app;

import android.content.Context;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shizuku UserService：在 root/shell（ADB）身份下执行 shell 命令。
 * 由 ShizukuShell 通过 bindUserService 绑定，进程由 Shizuku 托管。
 */
public class ShellService extends IShellService.Stub {

    public ShellService() {
    }

    @Keep
    public ShellService(Context context) {
    }

    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            String oldPath = env.get("PATH");
            env.put("PATH", (oldPath == null || oldPath.isEmpty() ? "" : oldPath + ":")
                    + "/system/bin:/system/xbin:/sbin:/vendor/bin");
            Process p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            final int MAX = 256 * 1024;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) != -1) {
                    if (bos.size() < MAX) {
                        int w = Math.min(n, MAX - bos.size());
                        bos.write(buf, 0, w);
                    }
                }
            }
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return bos.toString(StandardCharsets.UTF_8.name())
                        + "\n[EXIT=timeout] 命令执行超时(30s)已强杀";
            }
            int code = p.exitValue();
            return bos.toString(StandardCharsets.UTF_8.name()) + "\n[EXIT=" + code + "]";
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
```

#### 3. `app/src/main/java/com/deepseekharness/app/ShizukuShell.java`（完善参数 + 引入 execDirect 直通）
```java
package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

public final class ShizukuShell {

    private static final String TAG = "ShizukuShell";

    private static volatile Context appCtx;
    private static volatile IShellService shellService;
    private static volatile boolean binding = false;
    private static volatile long bindingStartedAt = 0L;
    private static volatile boolean binderListenerAttached = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile long lastRetryAt = 0L;
    private static final long RETRY_DELAY_MS = 4000L;
    private static final long RETRY_COOLDOWN_MS = 10000L;

    private ShizukuShell() {}

    public static void init(Context ctx) {
        if (appCtx == null && ctx != null) {
            appCtx = ctx.getApplicationContext();
        }
        attachBinderListener();
    }

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isReady() {
        return shellService != null;
    }

    public static String status() {
        String perm;
        try {
            int p = Shizuku.checkSelfPermission();
            perm = p == PackageManager.PERMISSION_GRANTED ? "granted" : "denied(" + p + ")";
        } catch (Throwable e) {
            perm = "err:" + e.getClass().getSimpleName();
        }
        return "binder=" + isAvailable()
                + ",permission=" + perm
                + ",bound=" + (shellService != null)
                + ",binding=" + binding;
    }

    public static void ensureBound(Context ctx) {
        init(ctx);
        attachBinderListener();
        long now = System.currentTimeMillis();
        if ((binding && now - bindingStartedAt < 6000L) || shellService != null) return;
        if (appCtx == null) return;
        if (!hasPermission()) return;
        binding = true;
        bindingStartedAt = now;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("shizuku")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    shellService = IShellService.Stub.asInterface(binder);
                    binding = false;
                    Log.i(TAG, "UserService connected: " + name);
                    try {
                        binder.linkToDeath(() -> {
                            Log.w(TAG, "UserService binder died");
                            shellService = null;
                            retryBindSoon();
                        }, 0);
                    } catch (Throwable ignored2) {}
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    shellService = null;
                    binding = false;
                    retryBindSoon();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    shellService = null;
                    binding = false;
                    retryBindSoon();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    binding = false;
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "bindUserService failed: " + e, e);
            binding = false;
        }
    }

    private static void retryBindSoon() {
        long now = System.currentTimeMillis();
        if (now - lastRetryAt < RETRY_COOLDOWN_MS) return;
        lastRetryAt = now;
        mainHandler.postDelayed(() -> ensureBound(appCtx), RETRY_DELAY_MS);
    }

    private static void attachBinderListener() {
        if (binderListenerAttached || appCtx == null) return;
        try {
            if (Shizuku.isPreV11()) {
                Shizuku.addBinderReceivedListener(() -> retryBindSoon());
            } else {
                Shizuku.addBinderReceivedListenerSticky(() -> retryBindSoon());
            }
            binderListenerAttached = true;
        } catch (Throwable e) {
            Log.w(TAG, "attachBinderListener failed", e);
        }
    }

    /** 通过 Shizuku 底层直接执行命令（绕过所有不稳定的 UserService 握手） */
    private static String execDirect(String cmd) throws Exception {
        IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");
        
        moe.shizuku.server.IShizukuService shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
        // 关键点：使用 2>&1 在内核态合并错误流，单 InputStream 读取防止双重管道死锁
        moe.shizuku.server.IRemoteProcess rp = shizukuService.newProcess(new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
        
        android.os.ParcelFileDescriptor pfd = rp.getInputStream();
        if (pfd == null) throw new IllegalStateException("Remote process InputStream is null");
        
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        rp.waitFor();
        int code = rp.exitValue();
        return bos.toString("UTF-8") + "\n[EXIT=" + code + "]";
    }

    /** 双通道执行策略：优先使用 UserService，未就绪或被系统拦截时自动走 execDirect 直通 */
    public static String exec(String cmd) {
        if (!hasPermission()) {
            return "[NO_SHIZUKU_PERMISSION]";
        }
        IShellService s = shellService;
        if (s != null) {
            try {
                return s.exec(cmd);
            } catch (Throwable ignored) {}
        }
        // 降级兜底：握手未完成或被系统拦截时，自动走底层直通管道
        try {
            return execDirect(cmd);
        } catch (Throwable e) {
            Log.w(TAG, "Direct newProcess failed, status=" + status(), e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
```

---

## 📡 四、 3090 桥只读诊断端点代码

在 `HttpShellService.java` 的路由处理中挂载 `/status` 与 `/app/shizuku`：
```java
} else if (path.startsWith("/status") || path.startsWith("/app/shizuku")) {
    result = ShizukuShell.status();
}
```

* **容器内调用方式**：
  ```bash
  curl -s "http://127.0.0.1:3090/app/shizuku?token=$(cat /root/.dsh/.bridge_token)"
  # 正常直通或绑定返回格式：{"result":"binder=true,permission=granted,bound=true,binding=false"}
  ```

---

## 🧠 五、 Agent 提示词与 Shizuku 权限接管逻辑对齐

实测如果只修底层 Java，Agent 在容器内依然会因为提示词限制而死等无线调试。
* **现象**：原版提示词把通道 3 写死为 `设备 shell（ADB 无线调试，需开启无线调试）`，导致即便用户授权了 Shizuku，Agent 遇阻依然机械提示用户“去开发者选项开启无线调试”，不会主动调用；
* **修正逻辑**（`app/src/main/assets/device-shell-guide/lib/index.js`）：
  将通道 3 定义修正为：
  > **“设备 shell 通道（高级通道，默认优先走 Shizuku 桥直连，未授权/未就绪自动降级到 ADB 无线通道：/root/dsh-bin/adb-shell）—— 用于跨页面私有 DeepLink 直达(am start)、系统设置、抓 logcat 等；手机已授权 Shizuku 或配对 ADB 任一即可秒通。”**
* **跨 App 跳转的「直达优先、无障碍保底」双通道策略**：
  * `/app/open?url=` 仅支持标准系统协议，第三方私有 Scheme（如 `openapp.jdmobile://`、`tbopen://`）走 `am start`；
  * **首选直达**：Shizuku / ADB 就绪时，优先 `am start -a ... -d <DeepLink>` 一步直达搜索结果页；
  * **智能降级**：Shizuku / ADB 未开启或失败时，自动无缝回退到 `/app/launch?pkg=...` 调起 App，配合无障碍 `/app/ui/*`（dump/tap/input）走模拟点击搜索，杜绝直接报错中断。

---

## 🧪 六、 真机验证结果

* **测试版本范围**：DSHA v1.1.9.1 ~ v1.1.10
* **测试机型环境**：Xiaomi 14 (小米澎湃 OS HyperOS / Android 16 SDK 36) + Stellar / Shizuku
* **验证结论**：通过上述双保险组合方案成功突破澎湃 OS 底层跨进程拦截，`/exec` 顺利返回系统 shell 输出 `[EXIT=0]`，命令执行稳定无异常，Agent 决策与降级链路端到端闭环。
