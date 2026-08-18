# DSHA

> 下一个 AI / 开发者请先读 **[HANDOFF.md](HANDOFF.md)**，不要先全库扫描。

**DeepSeek Harness 安卓启动器** —— 在手机上跑 deepseek-harness 的一体化方案，无需 Termux、无需 ROOT。

内置 proot + Ubuntu rootfs，一键（或分步）安装 deepseek-harness，内嵌 WebView 直接使用 Web UI。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## ✨ 功能

| 功能 | 说明 |
|---|---|
| **分步安装** | 4 个步骤（rootfs / 基础工具 / Node.js / deepseek-harness），每步可单独重装、更新，不重复下载 |
| **多源测速** | 每个下载源都有多个镜像（清华/阿里云/华为云/腾讯云/南大/哈工大/npmmirror…），并行测速后弹窗自选 |
| **直连源码构建** | 不依赖预构建包，直接从 GitHub 克隆源码 + pnpm 本地构建，自动修复 node-pty 等原生模块编译问题 |
| **Web UI 预览** | 启动后自动检测就绪并弹出全屏预览，支持重启/停止 |
| **免 ROOT 文件共享** | 集成 MT 管理器官方文件提供器，直接浏览/编辑 App 私有数据（工作区、配置、日志） |
| **配置备份/重置** | 一键备份配置（防死机），重置配置时保留对话记录 |
| **设备 Shell 桥接** | 通过 Shizuku 让智能体直接在设备上执行 shell 命令 |
| **WebUI 移动端适配** | 自动移除侧边栏开关等移动端无效功能 |

## 🚀 快速上手

1. 安装 APK（仅 arm64；GitHub Actions 产物已内置完整 Linux 环境）
2. 首次启动会解压内置环境（数分钟，只需一次）
3. 「配置」页填入 DeepSeek API key
4. 「启动」页启动 Web UI，自动打开预览

## 🧰 Agent Skills（智能体技能包）

配套的智能体技能，位于 [`agent-skills/`](agent-skills/)：

| 技能 | 说明 |
|---|---|
| `device-shell` | 通过 ADB 或本地 Shizuku HTTP 桥（127.0.0.1:3090）在安卓设备上执行 shell 命令 |
| `screen-ocr-operator` | 指挥官模式：OCR/视觉模型 + ADB 批量操作屏幕，最少往返 |

复制到 agent 技能目录即可使用：

```bash
cp -r agent-skills/device-shell ~/.agents/skills/
cp -r agent-skills/screen-ocr-operator ~/.agents/skills/
```

## 🔧 构建

公开仓库用 GitHub Actions 免费构建（**不需要电脑、不需要 Termux**）：

1. 推送到 `main`（或在 Actions 页点 Run workflow）
2. 流水线分两段：
   - `ubuntu-24.04-arm`：原生 arm64 chroot 预装 Ubuntu + Node + dsh RC6
   - `ubuntu-latest`：把离线包打进 APK
3. 在 Actions 的 Artifacts 下载 `dsha-debug-apk`

本地：

```sh
./build.sh   # 需要 Gradle 8.5 + Android SDK + JDK 17
# 还需要先有 app/src/main/assets/offline-rootfs.tar.gz
```

## 🧱 技术架构

- **UI**：原生 Android（Java）+ Material3 + BottomNavigationView
- **执行层**：Termux 官方 `proot` 二进制（`/system/bin/linker64` 启动，绕过 Android 10+ W^X）
- **rootfs**：Ubuntu base 24.04 arm64（约 30MB，多镜像下载）
- **运行时**：Node.js 24 + pnpm + deepseek-harness（预构建包或源码构建）
- **文件共享**：MT 管理器 `MTDataFilesProvider` 编程注入
- **设备命令**：Shizuku + 内置 HTTP 桥（127.0.0.1:3090）

## ⚠️ 注意

- 仅支持 arm64-v8a 设备，Android 8.0+
- 环境存储在 App 私有空间，卸载即清除（可先用「备份配置」）
- 设备 Shell 能力需要安装并授权 [Shizuku](https://shizuku.rikka.app/)
- QQ交流群960636357🐧
