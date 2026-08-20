# DSHA

> 下一个 AI / 开发者请先读 **[HANDOFF.md](HANDOFF.md)**，不要先全库扫描。

**DeepSeek Harness 安卓启动器** —— 在手机上跑 deepseek-harness 的一体化方案，无需 Termux、无需 ROOT。

内置 proot + Ubuntu rootfs，一键（或分步）安装 deepseek-harness，内嵌 WebView 直接使用 Web UI。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## ✨ 功能

| 功能 | 说明 |
|---|---|
| **装完即用** | 内置 Ubuntu 24.04 + Node 24 + dsh（默认 rc.8），首启解压即可用 |
| **ADB 无线通道** | 免 Shizuku 直连设备：配对一次永久授权，看门狗自动重连、开机自启、永不掉线 |
| **设备 Shell 引导** | 内置插件让 agent 主动用 `/root/dsh-bin/adb-shell` 操作手机（查应用/启动应用/抓日志…） |
| **自动备份体系** | 每 N 次启动/升级自动备份到 Download/DSHA，重解压数据保护，卸载重装不丢 |
| **自愈能力** | busy 超时释放 / Web 自动重试 / 崩溃恢复提示 / 内置插件防消失 / 检测 dsh 新版自动适配 |
| **多源加速** | npm/rootfs/Node 全部走国内镜像（npmmirror/清华/华为云…），多级回退 |
| **插件市场** | 过滤非插件/仅兼容筛选/排序优化，内置插件可管理 |
| **免 ROOT 文件共享** | 集成 MT 管理器官方文件提供器，直接浏览/编辑 App 私有数据 |
| **WebUI 移动端适配** | 单栏/抽屉/汉堡/全屏设置，移动端开箱即用 |

## 🚀 快速上手

1. 安装 APK（仅 arm64 / Android 8.0+；GitHub Actions 产物已内置完整 Linux 环境）
2. 首次启动解压内置环境（数分钟，只需一次）
3. 「配置」页填入 DeepSeek API key（可选勾选「启用 ADB 设备通道」）
4. 「启动」页启动 Web UI，自动打开预览

## 🔧 构建

公开仓库用 GitHub Actions 免费构建（**不需要电脑、不需要 Termux**）：

1. 推送到 `main`（或在 Actions 页点 Run workflow）
2. 流水线分两段：
   - `ubuntu-24.04-arm`：原生 arm64 chroot 预装 Ubuntu + Node + dsh rc.8
   - `ubuntu-latest`：把离线包打进 APK
3. 在 Actions 的 Artifacts 下载 `dsha-debug-apk`

本地：

```sh
./build.sh   # 需要 Gradle 8.5 + Android SDK + JDK 17
```

## 🧱 技术架构

- **UI**：原生 Android（Java）+ Material3 + BottomNavigationView
- **执行层**：Termux 官方 `proot` 二进制（`/system/bin/linker64` 启动，绕过 Android 10+ W^X）
- **rootfs**：Ubuntu base 24.04 arm64（约 30MB，多镜像下载）
- **运行时**：Node.js 24 + pnpm + dsh（默认 rc.8，npm 镜像安装，失败即明确报错不克隆）
- **设备通道**：ADB 无线（TLS 直连）+ Shizuku 桥双通道；WRITE_SECURE_SETTINGS 开机自启
- **文件共享**：MT 管理器 `MTDataFilesProvider` 编程注入

## ⚠️ 注意

- 仅支持 arm64-v8a 设备，Android 8.0+
- 环境存储在 App 私有空间，卸载即清除（可先用「备份配置」）
- 设备 Shell 能力需要「启用 ADB」并首次配对（输 6 位码），之后自动维护
- QQ交流群960636357🐧
