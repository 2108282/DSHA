# DSH启动器

DeepSeek Harness 的一键式安卓启动器：**一体式**内置 Linux 环境，无需 Termux，
自动安装、启动 Web UI、配置、工作区管理。

## 功能模块

| 模块 | 说明 |
|---|---|
| **安装** | 一键：内置 proot → 下载 Ubuntu rootfs → 安装 Node + deepseek-harness，带进度显示 |
| **启动** | 启动/停止 Web UI，内嵌 WebView 预览 `127.0.0.1:3080` |
| **配置** | API key / 端口 / 模型 / 沙箱权限模式（SharedPreferences 持久化） |
| **工作区** | 工作目录配置、环境状态、清除环境 |
| **引导** | 首次启动 3 页引导，说明三步上手流程 |

## 技术架构（一体式）

- **UI**：原生 Android（Java）+ Material3 + BottomNavigationView + ViewPager2 引导
- **执行层**：内置 Termux 官方 `proot` 二进制 + `libtalloc.so.2` + `libandroid-shmem.so`
  - 运行时解压 proot 到 `codeCacheDir`（filesDir 为 noexec），`chmod 0555`
  - 通过 `/system/bin/linker64 <proot>` 启动，绕过 Android 10+ 的 W^X EACCES
  - `LD_LIBRARY_PATH` 指向依赖库目录
- **rootfs**：下载 Ubuntu base 24.04 arm64（约 30MB），解压到 App 私有空间
- **安装脚本**：`assets/install.sh`（rootfs 内装 Node 24 + pnpm + deepseek-harness）

## 构建

```sh
./build.sh          # 输出 deepseekharness-arm64-v0.1.0.apk
```

依赖（本工作区已就绪）：Gradle 8.5、Android SDK + NDK 26、JDK 17。

> ARM64 环境注意：Maven 分发的 aapt2 仅含 x86_64，需在 `gradle.properties`
> 设置 `android.aapt2FromMavenOverride` 指向 SDK 内 arm64 aapt2。

## 使用流程

1. 「配置」填 DeepSeek API key
2. 「安装」一键安装（首次约 5~15 分钟）
3. 「启动」启动 Web UI →「打开预览」

## 说明

- 沙箱权限模式默认 `danger-full-access`（部分安卓环境无 bwrap/Landlock，需无沙箱才能用 bash 工具）
- 环境存储在 App 私有空间，卸载 App 即清除；也可在「工作区」手动清除
- 仅面向 arm64-v8a 设备

## 目录

```
app/src/main/assets/proot/        proot 二进制 + 依赖库（打包进 APK）
app/src/main/assets/install.sh    rootfs 内安装脚本（模板）
app/src/main/java/.../ProotBootstrap.java   proot + rootfs 管理
app/src/main/java/.../HarnessController.java  核心控制
```
