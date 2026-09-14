# AGENTS.md — DSHA Native (KernelSU / Magisk) 架构与维护红线必读

> **⚠️ 必读警告**：
> 本项目已彻底废弃 PRoot / Proroot 虚拟化模拟层与 Shizuku，全面重构下沉为 **KernelSU / Magisk 原生 Linux chroot (uid=0) 极速运行时**。
> 任何 AI 代理在处理、修改、打包或排查本仓库代码之前，**必须先完整通读本文件**！

---

## 一、 双分支工程契约与架构分工

本工程采用 **双分支并行协同架构**：

| 分支名称 | 仓库定位与职责 | 构建产物与发布目标 |
| :--- | :--- | :--- |
| **`magisk-apk`** | **Android 前端客户端**（Java 17, minSdk 31, 纯 64 位）<br>- 承载全屏/抽屉 WebView UI (`:3080`)<br>- 提供 3095 设备能力桥 (`HttpShellService`)<br>- 提供 PTY 终端会话连接器 (`term.sh`)<br>- 不含任何 Linux 底包，包体仅 ~25MB | 自动编译 `dsha-v1.2.0-native-debug.apk`<br>(直接安装至 Android 手机) |
| **`dsh-magisk`** | **KernelSU / Magisk 原生 Linux 底座与模块源码**<br>- 包含完整 Ubuntu ARM64 生产闭包与 7 大插件实体<br>- 包含模块安装器 (`customize.sh`) 与生命周期脚本 (`scripts/`)<br>- 负责打包全内置刷机包与轻量热更新刷机包 | 自动打包 `dsha_ksu_native_full.zip` (~200MB)<br>与 `dsha_ksu_native_lite.zip` (~3.6MB) |

---

## 二、 RootFS 底包内部 6 大物理层级清单 (脱敏与构成明细)

`rootfs.tar.gz` 位于 `/data/adb/dsha/rootfs/`（原生 ext4 分区，零 FUSE 损耗），包含以下精确层级：

```text
/data/adb/dsha/rootfs/ (RootFS 根目录)
├── [1. 系统与基础工具层]
│   ├── bin/ -> usr/bin
│   ├── etc/resolv.conf          # 权威公共 DNS (223.5.5.5 / 119.29.29.29 / 1.1.1.1)
│   ├── etc/group                # 包含 Android GID (1000/1023/2000)，解决终端权限
│   └── usr/bin/                 # ARM64 glibc bash, tar, xz, python3.12, git, curl, setsid
│
├── [2. 核心运行时引擎层]
│   ├── usr/local/bin/node       # Node.js v24.19.0 (纯 64 位 ARM64)
│   ├── usr/local/bin/pnpm       # pnpm v10.34.5 (支持 POSIX 原生硬链接)
│   ├── usr/local/bin/dsh        # 软链指向 @deepseek-ai/dsh/lib/bin.js
│   └── usr/local/lib/node_modules/
│       └── @deepseek-ai/dsh/    # DSH 官方核心运行时源码与生产闭包
│
├── [3. 全部 7 大插件实体与双向软链挂载层]
│   ├── root/dsha-*              # 4 大原生内置核心插件实体：
│   │   ├── dsha-device-shell-guide (设备指南提示词与 nsenter 直通说明)
│   │   ├── dsha-status-overlay     (屏幕顶部悬浮流式状态条)
│   │   ├── dsha-task-notifier      (任务结束通知，通过 3095 桥直推 Android 通知栏)
│   │   └── dsha-web-mobile         (沉浸式移动端 UI、毛玻璃背景与触屏让路守卫)
│   │
│   ├── root/.dsh/plugin-src/    # 3 大官方扩展插件源码实体：
│   │   ├── dsh-agy                 (Antigravity 账户多模型与 Token 管理)
│   │   ├── dsh-api-dashboard       (多平台 API 余额看板与大肥鱼桌面挂件)
│   │   └── @xmanrui/dsh-im         (即时通讯机器人接入)
│   │
│   ├── root/.dsh/profiles/web/  # Web 运行时 Profile：
│   │   ├── package.json         # 注册了全部 7 大插件 dependencies 与 bundles 清单
│   │   ├── cordis.yml           # 插件加载与启动参数
│   │   └── node_modules/        # [关键] 指向 /root/dsha-* 与 plugin-src/* 的符号链接
│   └── usr/local/lib/node_modules/ # [关键] 供 Cordis 全局加载器导入的同名软链
│
├── [4. 宿主穿透与命令安全守卫层]
│   ├── root/dsh-bin/            # 宿主特权命令直通包装器：
│   │   ├── am, pm, cmd, input, screencap, dumpsys, getprop, logcat
│   │   │   └── 实现原理：全部通过 nsenter -t 1 -m /system/bin/<cmd> 直通 Android 宿主
│   │   └── rm, dd, mkfs, reboot, poweroff, wipe 等危险命令拦截包装器
│   ├── root/dsh-guard.sh        # bashrc 自动加载的高危命令守卫规则
│   ├── root/dsh-confirm.sh      # 触发 3095 端口手机端二次确认弹窗
│   └── root/.bashrc             # 自动注入 PATH=/root/dsh-bin:... 与 LANG=C.UTF-8
│
├── [5. 存储直通与设备桥鉴权层]
│   ├── root/内部存储            # 软链接 -> /sdcard/Download/DSHA
│   └── root/.dsh/.bridge_token  # 3095 硬件桥通信 Token（权限 666）
│
└── [6. 内核隔离与文件系统挂载点]
    ├── dev/block                # [防砖安全] 挂载 mode=000 只读 tmpfs，物理屏蔽底层分区
    ├── dev/pts                  # 与宿主 /dev/pts bind 挂载，彻底根除 PTY ioctl 报错
    ├── dev/shm                  # tmpfs 1777 共享内存（Node.js Worker 线程必需）
    └── sdcard/                  # bind 挂载宿主 /storage/emulated/0
```

---

## 三、 维护与修改标准操作规范（SOP）

### 1. 升级 DSH 官方核心版本
```bash
# 1. 手机进入原生终端
su -c /data/adb/dsha/scripts/term.sh
# 2. 升级核心
pnpm install -g @deepseek-ai/dsh@latest
# 3. 验证版本
dsh --version && exit
# 4. 宿主执行脱敏打包并同步
/sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh
```

### 2. 修改或调试控制脚本 (`scripts/`)
- `start.sh`：负责虚拟文件系统安全判重挂载与后台拉起 Node.js；
- `stop.sh`：按 PID 毫秒级精准杀灭容器主进程及其子进程，普通停止**绝不卸载挂载点**；
- `term.sh`：利用 `setsid -c /bin/bash -l` 分配独立 PTY 控制终端，彻底杜绝 Inappropriate ioctl 与 job control 报错；
- `status.sh`：快速检测服务状态与打印当前鉴权 URL。

---

## 四、 绝对禁止触发的红线

1. **严禁在云端擅自“拼凑”或“裁剪”插件与底包**：所有 7 大插件与运行环境必须完整打包，不可挑三拣四；
2. **严禁在调用 `su` 时传入不存在的参数（如 `-i`）**：KernelSU 的 `su` 仅支持 `-c`、`-mm` 等标准选项；
3. **严禁在自愈脚本中使用 `rm -rf` 等破坏性命令**：优先使用 `ln -sfn` 原子符号链接覆盖与 `printf ... >` 直接写入；
4. **严禁破坏 `/dev/block` 的只读隔离**：设备物理分区安全第一，禁止尝试任何写入分区的行为。
