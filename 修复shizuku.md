## 🎯 现象

在部分高版本 Android 设备（如小米澎湃 OS HyperOS / Android 15/16 SDK 36）以及使用 **Shizuku / Stellar** 进行授权时，即使在管理器端已授予权限，DSHA 依然无法执行任何 shell 命令，`/exec?cmd=...` 恒返回：
`{"result":"[SHIZUKU_SERVICE_NOT_READY]"}`

通过增加诊断端点排查，发现系统处于 `binder=true, permission=granted, bound=false, binding=true` 状态（即已拿到 Binder 并授权，但 `UserService` 绑定永远挂起无法完成握手）。

---

## 🔍 原因排查

### 1. `Shizuku.UserServiceArgs` 缺少必填参数导致 NPE（Shizuku 13.x+）
在 `dev.rikka.shizuku:api:13.1.5` 的 `Shizuku.java` 内部 `forAdd()` 方法中，对 `processNameSuffix` 有非空断言：
```java
options.putString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
        Objects.requireNonNull(processName, "process name suffix must not be null"));
```
当前 `ShizukuShell.java` 中构建 `UserServiceArgs` 时仅传了 ComponentName，漏掉了 `.processNameSuffix("shizuku")`，导致每次 `ensureBound` 触发时均抛出 `NullPointerException` 并在 catch 块被吞掉。

### 2. `DshaApp.onCreate()` 在 `app_process` 无 UI 进程中崩溃
Shizuku / Stellar 服务端拉起 `UserService` 时会通过 `app_process` 启动进程并反射调用 `makeApplication()`。
`DshaApp.onCreate()` 第一句即执行 `AppCompatDelegate.setDefaultNightMode(...)`，在无 GUI 上下文的 `app_process` 进程中会直接触发异常闪退。且因其运行在 `UID=2000 (shell)` 身份下，无法写入 App 私有目录的 `crash.log`，故障极其隐蔽。

### 3. 高版本系统沙箱对 `app_process` 跨进程 `ContentProvider` 握手的物理拦截
在小米澎湃 OS 等系统上，底层 SELinux 策略对 `app_process` 子进程通过 `ContentProvider` 回传 Binder 的行为进行了静默拦截，导致 `UserService` 远程握手永远无法完成，`onServiceConnected` 永不触发。

---

## 🛠️ 修复方案（规范修补 + 引入execDirect）

我们在实际真机上已经完整验证并跑通了这套**「组合拳」落地方案**：
1. 先规范化修补 `UserService` 与 `DshaApp` 进程隔离（消除代码层缺陷）；
2. 同时引入 `execDirect`（利用底层 `IShizukuService.newProcess` 管道原生直通执行命令），在 `shellService == null` 或被厂商系统拦截时自动无缝降级兜底，彻底免疫所有定制系统的跨进程拦截！

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
public class ShellService extends IShellService.Stub {
    public ShellService() {}
    @Keep public ShellService(Context context) {}
    public void destroy() { System.exit(0); }
    ...
```

#### 3. `app/src/main/java/com/deepseekharness/app/ShizukuShell.java`（完善参数 + 引入execDirect 直通）
```java
public final class ShizukuShell {
    private static volatile Context appCtx;
    private static volatile IShellService shellService;
    private static volatile boolean binding = false;
    private static volatile long bindingStartedAt = 0L;

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
            binding = false;
        }
    }

    /** 通过 Shizuku 底层直接执行命令（绕过所有不稳定的 UserService 握手） */
    private static String execDirect(String cmd) throws Exception {
        IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");
        
        moe.shizuku.server.IShizukuService shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
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
        if (s == null) {
            ensureBound(appCtx);
            try {
                return execDirect(cmd);
            } catch (Throwable e) {
                Log.w(TAG, "Direct newProcess failed, status=" + status(), e);
                return "[SHIZUKU_SERVICE_NOT_READY]";
            }
        }
        try {
            return s.exec(cmd);
        } catch (Throwable e) {
            try {
                return execDirect(cmd);
            } catch (Throwable ex) {
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }
}
```

---

## 🧪 真机验证结果
版本号1.1.9.1-1.1.10
已在 Xiaomi 14 (澎湃 OS / Android 16 SDK 36) 及 Stellar / 原版 Shizuku 环境下实测，通过上述双保险组合方案成功突破系统拦截，`/exec` 顺利返回系统 shell 输出 `[EXIT=0]`，命令执行稳定无异常。
