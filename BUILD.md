# DSHA 构建说明

**DeepSeek Harness 安卓启动器 v1.1.0** —— 在手机上免 ROOT 免 Termux 运行 deepseek-harness。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17**（OpenJDK 17 即可） |
| Android SDK | Android SDK **34**（build-tools 34） |
| Android NDK | **26**（构建脚本已适配 NDK 26） |
| Gradle | **8.5**（可自行下载，或用 `GRADLE_BIN` 指定） |
| 操作系统 | Linux / macOS / Windows（配好环境即可） |
| Android Studio | 建议 Iguana+，直接用它打开工程更省事 |

> 注意：本工程需要 **NDK** 编译原生库（`libproot.so` 需要 arm64 目标），所以 SDK 管理器里记得装 **NDK 26.x**。

## 2. 打开工程

1. 用 Android Studio **直接打开 `deepseekharness/` 目录**（不是 `app/`，是根目录）。
2. 首次打开会提示 Gradle 同步，等它拉完依赖即可。

如果没有 Android Studio，命令行也可以（见第 4 节）。

## 3. 配置 local.properties（命令行构建必需）

项目根目录新建 `local.properties`（本包已排除，需自行创建）：

```properties
sdk.dir=/绝对路径/你的/Android/Sdk
```

Windows 示例：`sdk.dir=C\:\\Users\\xxx\\AppData\\Local\\Android\\Sdk`

## 4. 打包

一键脚本（推荐）：

```bash
./build.sh          # 默认使用 /workspace/gradle 与 /workspace/android-sdk，可覆盖：
# GRADLE_BIN=/你的/gradle/bin/gradle \
# ANDROID_SDK_ROOT=/你的/android-sdk \
# ANDROID_HOME=/你的/android-sdk \
# ./build.sh
```

或手动：

```bash
gradle :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ./deepseekharness-arm64-v1.1.0.apk
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 5. 首次构建耗时说明（重要）

- **GeckoView 内置浏览器内核**较大，首次构建需要联网下载约 **100MB+** 依赖（来自 `maven.mozilla.org` / Google Maven）。
- 国内网络若下载慢/失败，请配置**镜像或代理**（`~/.gradle/gradle.properties` 加 `systemProp.https.proxyHost=...`）。
- 之后增量构建会很快（几秒~1 分钟）。

## 6. 常见问题

| 问题 | 解决 |
|---|---|
| `Could not find dependencyResolutionManagement` | Gradle 版本太老（需 ≥6.8，建议 8.5） |
| `Unable to strip ... libproot.so` | 正常警告，不影响使用（原样打包） |
| 找不到 NDK / `abiFilters` 报错 | SDK Manager 安装 NDK 26.x |
| 手机上装不了 | 仅支持 **arm64-v8a + Android 8.0+** |

## 7. 版本号修改

- 版本名/版本号在 `app/build.gradle` 的 `defaultConfig`：
  - `versionName "1.1.0"` （显示版本）
  - `versionCode 16` （自增）

## 8. 内置 proot 说明（改前必读）

`app/src/main/jniLibs/arm64-v8a/libproot.so` 是**已修复**的 proot 主程序：
- 已移除 `canonicalize` 里会导致 WebUI 崩溃的断言（`/proc/self/fd` 误杀）。
- **不要**用旧版本 proot 覆盖它，否则会带回崩溃 bug。
- 如需重新编译 proot，源码补丁见：`proot-canon-crash-fix.patch`（随仓库另行提供）。

## 9. 交流反馈

- 项目主页：https://github.com/qiannianhuanxiang/DSHA
- QQ 交流群：960636357 🐧
