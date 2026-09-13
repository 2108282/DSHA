# DSHA 双分支架构联动与项目完整地图

本文档专为开发者设计，详细阐述 `magisk-apk`（Android 前端壳）与 `dsh-magisk`（KernelSU 后端底座）两个分支的完整工程树、接口联动契约与协同维护规范。

---

## 一、 双分支架构分工与通信联动全景

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ 分支 [ magisk-apk ] (Android 客户端与控制外壳)                           │
│  - 纯净 WebView 界面渲染 (minSdk 31, 专供 A12+)                        │
│  - 3090 设备能力服务端 (提供振动/无障碍点按/通知/截图/剪贴板)            │
│  - 不包含任何 Linux 镜像，包体体积 ~2.5 MB                             │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     │ 【6 大联动接口】
                                     │ 1. 启停控制: su -mm -c start.sh / stop.sh
                                     │ 2. 界面呈现: HTTP/WS 127.0.0.1:3080
                                     │ 3. 硬件能力: HTTP 127.0.0.1:3090/app/*
                                     │ 4. 鉴权令牌: /root/.dsh/.bridge_token
                                     │ 5. 终端会话: su -mm -c term.sh
                                     │ 6. 存储映射: /storage/emulated/0 <-> /sdcard
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ 分支 [ dsh-magisk ] (KernelSU / Magisk 模块与原生 Linux 底座)            │
│  - 纯原生 Ubuntu ARM64 (glibc) 真实 ext4 分区部署 (/data/adb/dsha/rootfs)│
│  - Node.js 24 + pnpm + DSH 核心运行时                                  │
│  - 按需随开随关，空闲 0 功耗，0 虚拟化损耗                              │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 二、 双分支工程目录树深度对比

### 1. 分支：`magisk-apk` (Android 客户端代码库)
> **职责**：前端显示、用户交互、Android 硬件桥接、生命周期调度。
> **构建行为**：push 时触发 GitHub Actions 编译（`android-build.yml`），生成轻量安装包。

```text
dsha-repo/ (branch: magisk-apk)
├── .github/workflows/
│   └── android-build.yml           # CI 流水线：已移除 150MB 底包下载，1分钟快速出包
├── app/
│   ├── build.gradle                # 构建配置：minSdk 31, 剔除 low flavor 与 GeckoView
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml     # 清单：Foreground Service、无障碍与存储权限
│       ├── assets/                 # 内置轻量插件配置与证书（无离线 rootfs 死重）
│       │   ├── builtin-plugins/    # 内置前端交互插件
│       │   └── ca-certificates.crt # TLS 根证书
│       └── java/com/deepseekharness/app/
│           ├── DshaApp.java        # 全局 Application 入口
│           ├── HarnessService.java # 前台保活服务：提供 3090 端口，无 WakeLock 死锁
│           ├── HttpShellService.java # 设备能力桥服务 (监听 127.0.0.1:3090)
│           ├── DshaAccessibilityService.java # 无障碍自动化核心（读屏/模拟点击）
│           ├── PtySession.java     # 终端 PTY 会话：直通 term.sh
│           ├── bridge/
│           │   └── AppBridge.java  # 响应 /app/* 请求的底层系统反射调用
│           ├── core/
│           │   └── HarnessController.java # 核心中枢：状态机流转与端口探活
│           ├── runtime/
│           │   ├── ContainerRuntime.java  # [核心改造] 新增 KsuChroot 原生运行时
│           │   ├── ProotBootstrap.java    # [核心改造] 优先探测并直通 /data/adb/dsha
│           │   └── WebProcessManager.java # [核心改造] stopWeb 直接调用 stop.sh
│           └── ui/
│               ├── MainActivity.java      # 主窗口：全屏加载 127.0.0.1:3080
│               ├── LaunchFragment.java    # 控制面板：启动/停止按钮
│               ├── TerminalFragment.java  # 原生 Root 命令行终端交互页面
│               └── QuickChatSheetActivity.java # 悬浮半屏快聊面板
```

---

### 2. 分支：`dsh-magisk` (KernelSU / Magisk 模块与底包工程)
> **职责**：Linux 运行时底座、系统虚拟文件挂载、多进程共享内存、安全脱敏打包。
> **构建行为**：**不触发 CI 编译**（纯模块源码与本地打包脚本）。

```text
dsha-repo/ (branch: dsh-magisk)
├── magisk-module/                  # KernelSU / Magisk 模块源码模版
│   ├── META-INF/com/google/android/
│   │   ├── update-binary           # Magisk/KernelSU 通用刷机解析执行器
│   │   └── updater-script          # 兼容标识
│   ├── module.prop                 # 模块信息定义 (id: dsha_native)
│   ├── customize.sh                # 刷入模块时执行：解压 rootfs.tar.gz 至真实分区
│   ├── service.sh                  # 开机静默服务：解除 Android 12+ 幽灵进程限制
│   └── scripts/                    # 模块常驻控制脚本集
│       ├── start.sh                # 按需挂载 /dev, /proc, /sys, /sdcard, /dev/shm 并拉起 Node
│       ├── stop.sh                 # 彻底清理 chroot 内进程，安全 umount 挂载点
│       ├── status.sh               # 状态探测脚本
│       └── term.sh                 # 终端直通脚本：原生登录 root bash
│
├── scripts/
│   └── export-rootfs.sh            # 核心维护工具：环境清理、数据脱敏、底包与刷机包打包
│
└── docs/magisk/
    ├── HOW_TO_UPDATE_ROOTFS.md     # 官方 DSH 更新后的底包迭代指南
    └── PROJECT_MAP.md              # 架构拓扑与排错参考手册
```

---

## 三、 双分支联动接口标准（修改代码必读契约）

在后续迭代中，只要保持以下 6 条契约一致，两个分支无论如何修改都能完美兼容：

| 联动项 | 约定协议 / 物理路径 | `magisk-apk` 端代码 | `dsh-magisk` 端代码 |
| :--- | :--- | :--- | :--- |
| **1. 启动入口** | `/data/adb/dsha/scripts/start.sh [port]` | `ProotBootstrap.java`<br>`HarnessController.java` | `magisk-module/scripts/start.sh` |
| **2. 停止入口** | `/data/adb/dsha/scripts/stop.sh` | `WebProcessManager.java` | `magisk-module/scripts/stop.sh` |
| **3. 终端入口** | `/data/adb/dsha/scripts/term.sh` | `PtySession.java`<br>`TerminalFragment.java` | `magisk-module/scripts/term.sh` |
| **4. 容器根目录**| `/data/adb/dsha/rootfs` | `ProotBootstrap.getRootfsDir()` | `customize.sh` 安装解压目标路径 |
| **5. 鉴权 Token** | `/root/.dsh/.bridge_token` (容器内)<br>`/data/adb/dsha/rootfs/root/.dsh/.bridge_token` (宿主) | `HttpShellService.java` 生成并写入 | `start.sh` 设置 666 权限供 Agent 读取 |
| **6. 端口映射** | Web: `127.0.0.1:3080`<br>Bridge: `127.0.0.1:3090` | WebView 加载 3080；<br>NanoHTTPD 监听 3090 | Node 进程监听 3080；<br>Agent curl 访问 3090 |

---

## 四、 日常开发协同工作流

### 场景 1：仅修改 Android 界面或手机控制能力（改 `magisk-apk`）
1. 切换分支：`git checkout magisk-apk`；
2. 修改 Java 代码（如调整悬浮面板、增加新的 `/app/*` 硬件控制接口）；
3. 提交并推流：`git push origin magisk-apk`；
4. GitHub Actions 自动编译出新的轻量 APK，手机直接覆盖安装即可，**底包模块无需重刷**。

### 场景 2：升级 DSH 核心包或增加 Linux 软件（改 `dsh-magisk`）
1. 手机进入终端：`su -c /data/adb/dsha/scripts/term.sh`；
2. 升级核心：`pnpm install -g @deepseek-ai/dsh@latest` 或 `apt install <工具>`；
3. 退出并打包：在 Android 宿主执行 `/sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh`；
4. 切换分支：`git checkout dsh-magisk`，提交脚本改动并推流（**不触发 APK 构建**）；
5. 将生成的新 `dsha_ksu_native_v1.2.0.zip` 覆盖刷入 KernelSU，**APK 无需重装**。
