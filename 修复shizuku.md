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


---



### 📢 【追加修复】后台无限生成 `com.dsh.client:shizuku` 
在真机环境下，结合使用官方社区适配的 Android 16 Shizuku 修复版（解决 A16 `IProcessObserver` 崩溃），进行了测试和修补

目前 **`UserService`（`bound=true`）与 `execDirect`双通道握手正常，解决后台堆叠僵尸进程的历史缺陷**。以下整理完整的原因分析和修复方案。
---

#### 一、 核心根因深度剖析

##### 1. 为什么此前 UserService 会在部分定制系统上卡死？
* **Provider 跨域拦截机制**：
  在 Android 16 环境下，使用 `app_process` 拉起的独立无头进程如果反向调用管理器端的 `ContentProvider`，在部分激进安全策略（如a16的 `MIUIOP(10021)` 关联唤醒审查）下容易被系统框架挂起；
  
* **测试**：
https://github.com/RikkaApps/Shizuku/issues/1125

  改用Shizuku issue#1125 并显式完成运行时权限授予（`moe.shizuku.manager.permission.API_V23`）后，`UserService` 的 Binder 回传链路在 Android 16 上**完全可以握手成功（真机实测 `bound=true`，成功回调 `onServiceConnected`）**。这证明其机制并未被 AOSP 彻底封死，规范配置下完全可行。

##### 2. 为什么后台会无限堆叠几十个 `com.dsh.client:shizuku` 僵尸进程？
1. **缺失 `destroy` Binder 事务处理（杀不死）**：
   - 官方规范（`rikka.shizuku.Shizuku`）明确规定：注销 UserService 时，服务端会向 Binder 派发 `USER_SERVICE_TRANSACTION_destroy (16777115)` 事务（AIDL 中编号为 `16777114`）；
   - 原项目中 `IShellService.aidl` 没声明 `destroy`，`ShellService.java` 中写了 `public void destroy() { System.exit(0); }` 但**没有重写 `onTransact` 拦截 `16777115`**；
   - 导致销毁指令被 Binder 驱动静默丢弃，子进程从未执行 `System.exit(0)`，变成杀不死的孤儿僵尸进程常驻后台。
2. **`.daemon(false)` 导致生命周期波动时“滚雪球”式重新创建**：
   - 配置为 `daemon(false)` 且无固定 `.tag()`；主 App 被后台回收或重连时，Shizuku 服务端注销旧服务（但由于硬伤 1 没杀死）；
   - 下次 App 重绑时，Shizuku 发现记录表为空，**又重新 fork 出一个新的 `app_process`**，导致僵尸进程只增不减。
3. **AIDL 编译器语法强校验**：
   - 为 `destroy` 分配显式 ID 时，必须为 interface 内所有方法显式分配 ID（如 `exec` 赋 `id = 1`），否则 Android SDK 34 的 `compileDebugAidl` 会抛出：
     `You must either assign id's to all methods or to none of them`。

##### 3. 之前提交的`execDirect` 潜藏的两处错误
1. **超时强退遗漏 `rp.destroy()` 导致远程孤儿与本地线程泄漏**：
   原版仅在超时时执行 `task.cancel(true)`。但在 Android 运行时中，`Thread.interrupt()` **无法打断正在阻塞等待内核 Binder 驱动返回的 `rp.waitFor()`**！如果命令死循环（如未加次数的 `ping`），不仅本地子线程永久挂死，远程 Shell 也会在 Shizuku 域下永久空耗 CPU。**必须显式调用 `rp.destroy()` 强杀远程进程，才能打破子线程等待并彻底回收资源**；
2. **标准输入（stdin）未关闭导致交互式命令被动卡死 30 秒**：
   通过 `newProcess` 派生进程时分配了 stdin 管道，若未显式关闭 `rp.getOutputStream()`，需要等待 EOF 的脚本会傻等输入，直到触发 30 秒超时强退。

---

#### 二、 最终设计：（`execDirect` 提级优先使用 ， `UserService`用于 兜底）

execDirect调用cmd速度更快，占用更小，同时**保留让Android 调用Java API 的扩展需求**。
1. **`execDirect` 为主**：
   - 绕过 Java 虚拟机与 RPC 流程，直接由 Shizuku 服务端走 Linux 原生标准管道通信；
   - 补齐 PATH 环境变量兜底与 256KB 输出缓冲截断；
   - **关闭 stdin**，防止交互式命令挂起；
   - **超时在 catch 中显式调用 `rp.destroy()`**，彻底斩除远程孤儿进程与本地线程泄漏。
2. **`UserService` 兜底（特权 Java 专属底座与命令兜底）**：
   - AIDL 补齐显式 ID 与 `onTransact` 的 `destroy` 自杀处理，根除僵尸进程；
   - 改为 `.daemon(true)` + 固化 `.tag("dsha_shell")`，防止重复多开；
   - 作为命令备用通道：若直通管道遇到突发环境异常，无需重新初始化，顺位由已连接的 `shellService.exec(cmd)` 接管。

---

#### 三、 完整代码（已通过 CI 编译和实测）

##### 1. `app/src/main/aidl/com/deepseekharness/app/IShellService.aidl`
```aidl
package com.deepseekharness.app;

interface IShellService {
    void destroy() = 16777114;
    String exec(String cmd) = 1;
}
```

##### 2. `app/src/main/java/com/deepseekharness/app/ShellService.java`
```java
package com.deepseekharness.app;

import android.content.Context;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ShellService extends IShellService.Stub {

    public ShellService() {
    }

    @Keep
    public ShellService(Context context) {
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // 16777115: USER_SERVICE_TRANSACTION_destroy
        if (code == 16777115) {
            destroy();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    @Override
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

##### 3. `app/src/main/java/com/deepseekharness/app/ShizukuShell.java`
```java
// 1. 守护单例配置（避免重复多开，包名与组件动态获取，非硬编码）
Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
        new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
        .daemon(true)
        .tag("dsha_shell")
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE);

// 2. 加固execDirect（带 30s 严格超时强退、rp.destroy 与 stdin 立即关闭）
private static String execDirect(String cmd) throws Exception {
    IBinder binder = Shizuku.getBinder();
    if (binder == null) throw new IllegalStateException("Shizuku binder is null");

    moe.shizuku.server.IShizukuService shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
    String wrappedCmd = "export PATH=$PATH:/system/bin:/system/xbin:/vendor/bin; " + cmd + " 2>&1";
    moe.shizuku.server.IRemoteProcess rp = shizukuService.newProcess(new String[]{"sh", "-c", wrappedCmd}, null, null);

    // 立即关闭标准输入流，防止交互式命令或需 EOF 的脚本被动卡死 30 秒
    try {
        ParcelFileDescriptor outPfd = rp.getOutputStream();
        if (outPfd != null) {
            new ParcelFileDescriptor.AutoCloseOutputStream(outPfd).close();
        }
    } catch (Throwable ignored) {}

    ParcelFileDescriptor inPfd = rp.getInputStream();
    if (inPfd == null) {
        try { rp.destroy(); } catch (Throwable ignored) {}
        throw new IllegalStateException("Remote process InputStream is null");
    }

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    final int MAX_BYTES = 256 * 1024;

    FutureTask<Integer> task = new FutureTask<>(() -> {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(inPfd)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bos.size() < MAX_BYTES) {
                    bos.write(buf, 0, Math.min(n, MAX_BYTES - bos.size()));
                }
            }
        }
        return rp.waitFor();
    });

    Thread workerThread = new Thread(task, "dsha-shizuku-exec");
    workerThread.start();

    try {
        int code = task.get(30, TimeUnit.SECONDS);
        return bos.toString("UTF-8") + "\n[EXIT=" + code + "]";
    } catch (TimeoutException te) {
        task.cancel(true);
        // 核心补齐：超时强制杀死远程进程，Binder 阻塞，解决远程进程与本地线程泄漏
        try { rp.destroy(); } catch (Throwable ignored) {}
        try { inPfd.close(); } catch (Throwable ignored) {}
        return bos.toString("UTF-8") + "\n[EXIT=timeout] 命令执行超时(30s)已主动强退";
    } catch (Throwable e) {
        task.cancel(true);
        try { rp.destroy(); } catch (Throwable ignored) {}
        try { inPfd.close(); } catch (Throwable ignored) {}
        throw e;
    }
}

// 3. 稳健双通道入口：execDirect，UserService 
public static String exec(String cmd) {
    if (!hasPermission()) {
        return "[NO_SHIZUKU_PERMISSION]";
    }

    // 优先走execDirect
    try {
        return execDirect(cmd);
    } catch (Throwable t) {
        Log.w(TAG, "execDirect 管道未通，顺位转由 UserService 执行: " + t.getMessage());
    }

    // 顺位走 UserService
    IShellService s = shellService;
    if (s != null) {
        try {
            return s.exec(cmd);
        } catch (Throwable e) {
            Log.e(TAG, "UserService 也未执行成功: " + e.getMessage());
        }
    } else {
        ensureBound(appCtx);
    }

    return "[SHIZUKU_SERVICE_NOT_READY]";
}
```

---

#### 四、 实测
- **连通状态达成**：3090 诊断端点返回 `binder=true, permission=granted, bound=true, binding=false`，`UserService` 握手完全连通；
- **僵尸进程去除**：高频执行命令或切换前后台，后台 `com.dsh.client:shizuku` 进程从始至终严格只有唯一 1 个常驻单例，内存占用正常；
- **防卡死逻辑正常**：补齐 `rp.destroy()` 与 stdin 关闭后，无退出命令测试 30s自动杀死，资源正常释放。
