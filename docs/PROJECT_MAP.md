# DSHA (KernelSU / Magisk + APK) 项目架构地图

---

## 一、 系统全局通信拓扑

```text
[ 用户操作 ]
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Android APK 前端外壳 (com.dsh.client)                                   │
│  ├─ WebView 视图 ──────────── HTTP/WS (127.0.0.1:3080) ────┐            │
│  ├─ 控制面板 (Start/Stop) ──── su -mm -c ───┐               │            │
│  └─ 设备能力桥 (HttpShellService)           │               │            │
│       └─ 监听 127.0.0.1:3090 ◄──────────┐   │               │            │
└─────────────────────────────────────────┼───┼───────────────┼───────────┘
                                          │   │               │
┌─────────────────────────────────────────┼───┼───────────────┼───────────┐
│ Linux 内核层与模块宿主 (KernelSU / Magisk)│   │               │            │
│  ├─ 控制脚本: /data/adb/dsha/scripts/ ◄─┼───┘               │            │
│  │   ├─ start.sh (挂载 + 拉起)          │                   │            │
│  │   ├─ stop.sh  (杀进程 + 卸载)        │                   │            │
│  │   └─ term.sh  (交互终端)             │                   │            │
│  │                                      │                   │            │
│  └─ 原生 chroot 根目录:                 │                   │            │
│     /data/adb/dsha/rootfs/              │                   │            │
│       ├─ bin/bash                       │                   │            │
│       ├─ usr/local/bin/node             │                   │            │
│       ├─ DSH 核心守护进程 ──────────────┴───────────────────┘            │
│       │    └─ 监听 127.0.0.1:3080 (WebUI)                                │
│       └─ Agent 执行硬件指令 ──────────── HTTP curl ─────────┘            │
│            └─ 带 Token 访问 127.0.0.1:3090/app/*                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 二、 完整项目目录树

### 1. 模块与构建产物目录 (`/sdcard/Download/DSHA/`)

```text
/sdcard/Download/DSHA/
├── PROJECT_MAP.md                  # [地图文档] 本项目完整架构地图与故障速查手册
├── dsha_ksu_native_v1.2.0.zip      # [交付物] KernelSU / Magisk 一键刷机模块包
├── rootfs.tar.xz                   # [交付物] 纯净 Ubuntu ARM64 底包 (xz 高压版)
├── rootfs.tar.gz                   # [交付物] 纯净 Ubuntu ARM64 底包 (Android 通用 gzip 版)
├── export-rootfs.sh                # [维护工具] 导出当前容器环境并重新打包模块的脚本
│
├── dsha_ksu_module/                # 模块源工程目录（用于打包 zip）
│   ├── META-INF/com/google/android/
│   │   ├── update-binary           # Magisk/KSU 通用模块安装解析入口
│   │   └── updater-script          # 标准 Magisk 占位脚本
│   ├── module.prop                 # 模块元数据（ID: dsha_native, 名称, 版本）
│   ├── customize.sh                # 刷入时执行：解压 rootfs 至 /data/adb/dsha/rootfs
│   ├── service.sh                  # 开机静默服务：仅负责解除 Android 12+ 幽灵进程上限
│   └── scripts/                    # 运行时控制脚本集合
│       ├── start.sh                # 启动脚本：按需挂载虚拟文件系统并后台拉起 DSH
│       ├── stop.sh                 # 停止脚本：终止内部全部进程，安全 umount 挂载点
│       ├── status.sh               # 状态检测：通过 PID 文件检测服务运行状态
│       └── term.sh                 # 终端直通：以 Root 身份直接进入 chroot bash
│
└── 工作区/                         # Agent 实际执行文件操作的映射目录 (/sdcard)
```

---

### 2. 运行时宿主环境部署目录 (`/data/adb/dsha/`)

```text
/data/adb/dsha/
├── scripts/                        # 真实运行的控制脚本
│   ├── start.sh
│   ├── stop.sh
│   ├── status.sh
│   └── term.sh
│
├── run/                            # 运行时状态与日志
│   ├── dsh.pid                     # DSH 主进程 PID 记录文件
│   └── dsh-web.log                 # DSH 运行标准输出与错误日志
│
└── rootfs/                         # Ubuntu ARM64 完整根文件系统 (chroot 目标路径)
    ├── bin/ -> usr/bin
    ├── etc/
    │   ├── resolv.conf             # 容器内部 DNS 解析配置
    │   └── group                   # 包含 Android GID 的组配置
    ├── root/                       # root 用户主目录
    │   ├── .dsh/
    │   │   ├── .bridge_token       # 3090 硬件桥鉴权密钥（与 APK 双向对齐）
    │   │   ├── settings.yaml       # DSH 核心运行配置文件
    │   │   └── profiles/           # DSH 运行 Profile
    │   └── dsh-bin/                # 守护包装脚本与环境变量注入路径
    ├── usr/
    │   ├── bin/                    # 系统基础工具 (bash, tar, xz, git, curl)
    │   └── local/
    │       ├── bin/
    │       │   ├── node            # Node.js 运行时可执行文件 (v24.19.0)
    │       │   └── dsh -> ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js
    │       └── lib/node_modules/
    │           └── @deepseek-ai/dsh/ # DSH 核心代码包（上游源码/发布包所在目录）
    ├── dev/                        # 挂载点：内核 /dev (由 start.sh 绑定)
    ├── proc/                       # 挂载点：内核 /proc (由 start.sh 绑定)
    ├── sys/                        # 挂载点：内核 /sys (由 start.sh 绑定)
    └── sdcard/                     # 挂载点：/storage/emulated/0 (由 start.sh 绑定)
```

---

### 3. Android APK 客户端源码结构 (`/root/dsha-repo/`)

```text
app/src/main/
├── AndroidManifest.xml             # 清单文件：权限、Foreground Service、AccessibilityService
├── assets/                         # 随 APK 打包的基础资源与补丁脚本
│   └── builtin-plugins/            # DSH 内置前端拓展插件
│
└── java/com/deepseekharness/app/
    ├── DshaApp.java                # Application 实例，全局状态初始化
    ├── HarnessService.java         # 前台保活 Service，维护 3090 端口与防杀通知
    ├── HttpShellService.java       # 设备能力桥服务端 (127.0.0.1:3090, 响应 /app/*)
    ├── DshaAccessibilityService.java # 无障碍服务：实现系统读屏 (dump) 与模拟点击 (tap)
    ├── PtySession.java             # PTY 伪终端会话封装，对接 Termux terminal-view
    │
    ├── bridge/
    │   ├── AppBridge.java          # 处理 /app/* 的具体逻辑（振动、通知、电量、剪贴板等）
    │   └── AdbBridge.java          # 无线调试配对与 ADB 命令转发
    │
    ├── core/
    │   ├── HarnessController.java  # 核心控制器：调度 Web 生命周期、鉴权提取、状态流转
    │   ├── ConfigStore.java        # 本地 SharedPreferences 配置存取
    │   └── DiagnosticRepository.java # 诊断信息汇总（排查环境问题）
    │
    ├── runtime/
    │   ├── ContainerRuntime.java   # 容器运行时抽象接口 (Proot / Proroot / Chroot)
    │   ├── ProotBootstrap.java     # 运行时参数组装、环境变量注入与执行入口
    │   ├── WebProcessManager.java  # 进程启停逻辑与停止哨兵
    │   └── RuntimeTools.java       # 运行时依赖检查工具
    │
    └── ui/
        ├── MainActivity.java       # 主窗口，承载加载 127.0.0.1:3080 的 WebView
        ├── LaunchFragment.java     # 启停控制主页面（启动/停止按钮、状态指示灯）
        ├── TerminalFragment.java   # 嵌入式终端页面，展示命令行交互界面
        ├── QuickChatSheetActivity.java # 悬浮/半屏快聊面板
        ├── ConfigFragment.java     # 参数配置界面（端口、网络模式、主题）
        └── DiagnosticActivity.java # 诊断日志展示页面
```

---

## 三、 核心接口、端口与关键路径映射表

| 项目 | 配置值 / 路径 | 作用说明 | 关联文件 |
| :--- | :--- | :--- | :--- |
| **DSH Web 服务端口** | `127.0.0.1:3080` (测试可自定义如 3088) | DSH 前端 UI 与 WebSocket 后端交互端口 | `start.sh`, `MainActivity.java` |
| **设备硬件能力桥端口** | `127.0.0.1:3090` | 容器内 Agent 操控实体手机的 HTTP API 端口 | `HttpShellService.java`, `HarnessService.java` |
| **设备能力鉴权密钥** | `/root/.dsh/.bridge_token` | Agent 访问 3090 端口必须携带的 Token（Header: `X-Token`） | `HttpShellService.java`, `start.sh` |
| **Chroot 宿主绝对路径** | `/data/adb/dsha/rootfs` | 原生 Linux 镜像在手机真实 ext4 分区上的根路径 | `customize.sh`, `start.sh`, `stop.sh` |
| **PID 状态记录路径** | `/data/adb/dsha/run/dsh.pid` | 记录 DSH 主进程 PID，用于状态判断和精准 kill | `start.sh`, `stop.sh`, `status.sh` |
| **标准输出日志路径** | `/data/adb/dsha/run/dsh-web.log` (宿主) / `/root/dsh-web.log` (容器内部) | DSH 运行标准输出与错误日志 | `start.sh` |
| **幽灵进程上限配置** | `max_phantom_processes` | Android 12+ 允许派生的最大子进程数（设为 2147483647） | `service.sh`, `start.sh` |

---

## 四、 常用运维与调试指令

```bash
# 1. 启动服务（支持指定端口，如 3088 防止与当前环境冲突）
/data/adb/dsha/scripts/start.sh 3088

# 2. 查看当前服务运行状态
/data/adb/dsha/scripts/status.sh

# 3. 查看实时运行日志 (在宿主环境终端执行)
cat /data/adb/dsha/run/dsh-web.log

# 4. 以 Root 身份直接进入 Linux 原生 bash 终端
/data/adb/dsha/scripts/term.sh

# 5. 测试 3090 宿主硬件能力连通性（在 term.sh 终端内执行）
T=$(cat /root/.dsh/.bridge_token)
curl -s "http://127.0.0.1:3090/app/device?token=$T"

# 6. 停止服务并安全卸载所有挂载点
/data/adb/dsha/scripts/stop.sh

# 7. 更新 DSH 后重新打包新底包
/sdcard/Download/DSHA/export-rootfs.sh
```
