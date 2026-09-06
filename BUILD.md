# DSHA 构建说明

**发布文件统一存放：`F:\DSHA_RESTART\release`（源码工作区的 release 目录）。** 标准版和兼容版都交付 APK 与对应 `.apk.sha256`，保留原发布签名；Gradle 中间产物仍在 `app/build`。

**DSHA 标准版（开发中）** —— Android 11+ / arm64-v8a，目标 Android 17，系统 WebView，内置完整离线环境。

当前默认构建标准版，另有面向 Android 6—12 / arm64 的 low flavor。调试包使用本机调试证书，
不能假设它能覆盖历史发布包；正式覆盖包仍需配置已有的 `DSHA_KEYSTORE`。

本轮最小检查：编译 APK 与新增纯逻辑测试，不需要额外备份或完整设备矩阵。

标准版的 Termux JNI 已在 `app/src/main/jniLibs/arm64-v8a/libtermux.so` 提供，
与终端 Java 依赖同为 0.118.0，重编为 16 KB ELF 对齐。Windows 上可用已有 NDK 复现：

```powershell
./tools/termux-jni/build.ps1 -Ndk <NDK-r26d目录>
```

打包后可用 `python tools/audit-standard-apk.py app/build/outputs/apk/standard/debug/app-standard-debug.apk` 检查
宿主 JNI、rootfs、Python 和 ADB wheels 的 ELF 对齐。这是静态检查，不能替代 16 KB 真机运行验证。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17**（OpenJDK 17 即可） |
| Android SDK | 平台包 **platforms;android-37.0**，build-tools **36.0.0** |
| Android NDK | **26**（构建脚本已适配 NDK 26） |
| Gradle / AGP | Wrapper **9.3.1** / Android Gradle Plugin **9.1.1** |
| Python | **3.9+**，默认 `python3`；可用 `DSHA_PYTHON` 指定可执行文件绝对路径 |
| 操作系统 | Linux / macOS / Windows（配好环境即可） |
| Android Studio | 需支持 AGP 9.1；也可以直接用命令行构建 |

> 注意：本工程需要 **NDK** 编译原生库（`libproot.so` 需要 arm64 目标），所以 SDK 管理器里记得装 **NDK 26.x**。

## 2. 打开工程

1. 用 Android Studio **直接打开项目根目录**（包含 `settings.gradle`，不要只打开 `app/`）。
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
./build.sh          # 使用仓库内 Wrapper，JDK/SDK 默认查 F:/DSHA/_toolchains，可覆盖：
# GRADLE_BIN=/你的/gradle/bin/gradle \
# ANDROID_SDK_ROOT=/你的/android-sdk \
# ANDROID_HOME=/你的/android-sdk \
# ./build.sh
```

Windows PowerShell 示例（先按本机安装位置调整路径）：

```powershell
$env:JAVA_HOME = 'F:\DSHA\_toolchains\jdk-17'
$env:ANDROID_SDK_ROOT = 'F:\DSHA\_toolchains\android-sdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:DSHA_PYTHON = 'C:\Python312\python.exe'
./gradlew.bat :app:assembleStandardDebug
```

标准版产物：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`。
兼容版：`./gradlew.bat :app:assembleLowRelease`，产物为 `app/build/outputs/apk/low/release/app-low-release.apk`。
签名仍通过 `DSHA_KEYSTORE` 指定原发布密钥；兼容版务必保留 V1 签名供 Android 6 使用。

## 5. 首次构建耗时说明（重要）

- 首次构建需要下载 Gradle 与 Java 依赖，并重压离线 rootfs。标准版已移除 GeckoView 依赖。
- 国内网络若下载慢/失败，请配置**镜像或代理**（`~/.gradle/gradle.properties` 加 `systemProp.https.proxyHost=...`）。
- `prepareStandardAssets` 自动生成 `app/build/generated/standardAssets`，原始资产不变。
  rootfs 使用输入与生成脚本的 SHA-256 缓存，修改普通脚本或界面时可复用已校验的压缩结果。
- 不要手工改生成目录。减重规则改 `tools/prepare-standard-assets.py`，分项报告在
  `app/build/generated/standard-assets-report.json`。仅压缩减重不要递增环境版本号。
- Python 补充动态库与 pnpm 已作为小型离线资产随源码提供。需要重新生成时运行
  `python tools/build-standard-runtime.py`，要求 bsdtar（Windows 自带的 tar 可用）；
  下载使用脚本内固定版本与 SHA-256，正常 Gradle 构建不需要重新下载这些包。

## 6. 常见问题

| 问题 | 解决 |
|---|---|
| Gradle / AGP 版本不匹配 | 使用本仓库 `gradlew` / `gradlew.bat`，不要调用旧的全局 Gradle |
| `Unable to strip ... libproot.so` | 正常警告，不影响使用（原样打包） |
| 找不到 NDK / `abiFilters` 报错 | SDK Manager 安装 NDK 26.x |
| 找不到 python3 | 用 `DSHA_PYTHON` 指定 Python 3.9+ 的可执行文件 |
| 手机上装不了 | 仅支持 **arm64-v8a + Android 11+**；覆盖安装还要求签名一致 |

## 7. 版本号修改

- 版本名/版本号在 `app/build.gradle` 的 `defaultConfig`：
  - `versionName "1.2.0-rc1.2"` （标准版显示版本；low flavor 为 `1.2.0-rc1.2low`）
  - `versionCode 111` （正式发布时自增）

## 8. 内置 proot 说明（改前必读）

`app/src/main/jniLibs/arm64-v8a/libproot.so` 是**已修复**的 proot 主程序：
- 已移除 `canonicalize` 里会导致 WebUI 崩溃的断言（`/proc/self/fd` 误杀）。
- **不要**用旧版本 proot 覆盖它，否则会带回崩溃 bug。
- 如需重新编译 proot，源码补丁见：`proot-canon-crash-fix.patch`（随仓库另行提供）。

## 9. 交流反馈

- 项目主页：https://github.com/qiannianhuanxiang/DSHA
- QQ 交流群：975836806 🐧
