# DSHA

> 下一个 AI / 开发者请先读 **[AGENT.md](AGENT.md)**，不要先全库扫描。

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

## 📱 ADB 无线配对教程（设备 Shell 能力）

DSHA 内置 ADB 无线通道（免 Shizuku），配对一次后永久授权，看门狗自动维护不掉线。

### 首次配对（约 1 分钟）

1. **App 配置页** → 勾选「启用 ADB 设备通道」→ 点「保存配置」
2. 手机系统：**设置 → 开发者选项 → 无线调试** → 打开
3. 点「**使用配对码配对设备**」→ **不要切回 App**，直接在通知栏操作：
   - 下拉通知 → 找到 DSHA 的「输码配对」卡片
   - 点「输码配对」→ 在通知栏直接输入屏幕上的 **6 位码**
   - ⚠️ 配对码切回 App 即销毁，**记下来也没用**，必须在通知栏里输入
4. 配对成功通知会显示 ✅，自动获得 `uid=2000(shell)` 设备权限

### 配对之后（自动维护，无需再操作）

| 场景 | 自动行为 |
|---|---|
| App 重启 | 启动体检自愈，自动恢复连接 |
| 手机重启 | BootReceiver 自动拉起 + 看门狗重连（已授权 WRITE_SECURE_SETTINGS 时自动开无线调试） |
| 无线调试被系统关闭 | 看门狗自动重开（有权限）或通知提醒 |
| 端口变化 | mDNS 自动发现新端口，无需重新配对 |
| 掉线 | 30 秒看门狗周期自动重连 |

### 验证配对成功

配置页状态应显示：
```
● ADB 运行中（已连接，uid=2000 shell）
```

或在 App 终端执行：
```bash
/root/dsh-bin/adb-shell id
# 输出 uid=2000(shell) gid=2000(shell) 即成功
```

### 让 AI 助手使用 ADB

配对后，**新开一个对话**，AI 助手会自动获得「设备操作能力」引导（标准/极简模式均支持），你可以直接问：

- 「我手机现在前台是什么应用？」
- 「打开微信」
- 「帮我看看手机上的通知」

AI 会通过 `/root/dsh-bin/adb-shell` 操作你的手机（uid=2000，非 root，破坏性操作会先征得同意）。

### 常见问题

| 问题 | 解决 |
|---|---|
| 提示"未连接" | 确认无线调试已开启；配对的 6 位码 2 分钟过期，重新配对 |
| 配对失败 | 重新点「使用配对码配对设备」生成新码再输 |
| 需要重新授权 | 系统安全设置可能清除授权，重开无线调试重新配对一次 |
| 换手机/重置 | 重新走首次配对流程即可 |

## 💬 交流 & 插件推荐

**🐧 QQ 交流群：960636357**

- **进群玩最新测试版**：正式版发布前，群里第一时间体验新功能/修复，直接反馈 bug 给开发者
- **插件推荐**：群里不定期分享好用的 dsh 插件（技能包/主题/工具增强），不知道怎么装插件也可以进群问
- 遇到问题（安装失败/ADB 配对/插件异常）优先进群，附上日志更快解决

> 提示：插件安装路径 `dsh plugin --profile web add <插件名>`，或 App「插件市场」直接搜。
