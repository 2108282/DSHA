# DSHA Native (For Root) · 原生 Android AI 终端与运行底座

<p align="center">
  <b>DeepSeek Harness (DSH) 原生 Root 极速运行时与 Android 控制客户端</b><br>
  为 Android 12+ Root 设备打造 —— 0 虚拟化损耗，原生 glibc 性能，按需启停
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT"></a>
  <a href="https://github.com/2108282/DSHA/releases/latest"><img src="https://img.shields.io/github/v/release/2108282/DSHA?color=blue" alt="release"></a>
  <img src="https://img.shields.io/badge/Android-11.0%2B-3DDC84?logo=android&logoColor=white" alt="android">
  <img src="https://img.shields.io/badge/Root-KernelSU%20%7C%20APatch%20%7C%20Magisk-red" alt="root">
  <img src="https://img.shields.io/badge/arch-arm64--v8a-lightgrey" alt="arch">
</p>

---

## 🌟 核心特性与架构升级

本项目已废弃 PRoot / Proroot 用户态 ptrace 虚拟化方案与 Shizuku 依赖，全面重构下沉为 **KernelSU / Magisk 原生 Linux chroot (uid=0) 极速运行时**：

1. **双分支协同架构**：
   - **`magisk-apk` (前端外壳)**：原生Android 应用（包名 `com.dsha.fr`），包体仅17MB，支持web连接核心，备份、重制环境，通知整合、内置终端、端口变更。
   - **`dsh-magisk` (模块与底座)**：承载完整的 Ubuntu ARM64 。
2. **沉浸式 QuickChat 半悬浮快捷抽屉**：
   - 可调透明毛玻璃效果；
   - 独立active支持shell调用；
   - 继承日夜间模式联动。
3. **原生 Root 直通桥 (3095)**：
   - 无障碍读屏（dump）、坐标点按（tap）、文本输入（input）、按键模拟（key）、硬件传感器与振动；
   - 宿主特权命令直接穿透（`am`, `pm`, `cmd`, `screencap`, `dumpsys`），完全不受普通应用权限沙箱限制。
   - 远端访问token固化，不再随核心启停重制，支持手动切换
4. **安全隔离**：
   - `/dev/block` 物理闪存分区受内核只读 tmpfs 覆盖屏蔽，杜绝变砖风险；
   - 危险命令（格式化、清空数据、重刷系统）自动触发手机前台确认弹窗。
5. **功耗**：
   - 服务按需启停，CPU受调度线程控制，可自定义使用核心数；
   - 任务结束即刻释放 WakeLock 与 Wi-Fi 锁，深度休眠不偷跑电。
   
6.**灵动岛适配**  
   - 尝试增加AOSP胶囊，以及hyperos3灵动岛，原生通知


---

## 📦 下载与安装指南

请在 [GitHub Releases 最新发布页](https://github.com/2108282/DSHA/releases) 获取联合交付包：

| 交付文件 | 说明 | 适用场景 |
| :--- | :--- | :--- |
| **`dsha-for-root-1.0.apk`** | 原生 Root 控制端 App (~17MB) | 手机直接安装，负责 Web 交互与硬件操控 |
| **`dsha_ksu_native_full.zip`** | 完整 Magisk/KernelSU 刷机包 (~260MB) | 首次使用：在 Magisk/KernelSU 模块管理中本地刷入 |
| **`dsha_ksu_native_lite.zip`** | 极速轻量热更模块 (~28KB) | 已有底包用户更新控制脚本与配置 |

### 安装步骤：
1. **安装底层模块**：在 Magisk / KernelSU / APatch 管理器中刷入 `dsha_ksu_native_full.zip`（**无需重启手机**即可就绪）；
2. **安装控制端**：安装 `dsha-for-root-1.0.apk`，授予 Root 权限与「无障碍服务」（用于读屏与自动化操控）；
3. **启动使用**：打开 App 点击「启动」即可秒级进入 DSH 对话界面。

---

## 🏗️ 开发者与分支分工

- **`main`**：项目默认主分支（与 `magisk-apk` 保持一致）。
- **`magisk-apk`**：Android 前端客户端源码（Java 17, minSdk 31, arm64-v8a）。
- **`dsh-magisk`**：KernelSU / Magisk 模块源码与运行底座。
- **构建指南**：详见 [BUILD.md](BUILD.md)。
- **AI 智能体维护红线**：详见 [AGENTS.md](AGENTS.md)。

---

## 致谢与鸣谢 (Credits & Acknowledgments)

本项目基于以下优秀的开源项目构建或受其启发，特此向相关项目的开发者与开源社区致谢：

* [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) - 核心本体支持
* [dsh-web-mobile](https://github.com/saya-ch/dsh-mobile) - 内置移动端UI适配
* [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) - 轻量化容器设计及APP界面参考
* [LSPosed](https://github.com/LSPosed/LSPosed) - Xposed 框架运行与 Hook 支持
* [Magisk Module Template](https://github.com/topjohnwu/Magisk) - 模块打包模版与安装脚本结构

感谢所有为开源社区做出贡献的开发者！
