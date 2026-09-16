# DSHA for Root 打包流水线工程分支 (`dsha-for-root.x`)

> **架构定位**：  
> 本分支为 **纯打包逻辑与持续交付工程分支**，**本分支不存放任何业务源码**。  
> 专门负责调度与整合：
> 1. **前端 APK 仓库分支**：`magisk-apk`
> 2. **核心 Magisk/KernelSU 模块分支**：`dsh-magisk`

---

## 一、 最终交付物清单（一共 3 个）

每次流水线运行或本地打包，严格按照两端分支原生规范产出以下 **3 大核心产物**：

| 序号 | 交付产物文件名 | 产物类型 | 源码分支源头 | 作用与使用场景 |
| :---: | :--- | :---: | :---: | :--- |
| **1** | **`DSHA-FR 0.1.5rc.2-u2.apk`** | Android 前端应用 | `magisk-apk` | 原生 Root 前端客户端。体积轻盈（~5.5MB），内置 3095 硬件通道桥、无障碍 UI 自动化控制守护与全沉浸 Web 容器。 |
| **2** | **`dsha_ksu_native_full.zip`** | 模块完整刷机包 | `dsh-magisk` | **全内置完整刷机包**（~218MB）。内置纯净 Ubuntu ARM64 生产闭包与经由 `rootfs-overlay/` 反射熔铸的四大核心插件，新机开箱即刷即用。 |
| **3** | **`dsha_ksu_native_lite.zip`** | 模块极速热更新包 | `dsh-magisk` | **增量热更新补丁包**（几十 KB）。包含两步音量键交互安装器与 `scripts/` 下的现场热修脚本，已有环境老用户无需下载 200MB 大包，无痛秒级热修。 |

---

## 二、 严格对齐各分支的原生打包逻辑

本分支打包逻辑 100% 遵照各源分支规范：

### 1. 前端 APK 打包规范 (`magisk-apk`)
* **环境**：Ubuntu Latest + JDK 17 (Temurin) + Android SDK
* **编译命令**：`./gradlew :app:assembleStandardDebug --stacktrace`
* **签名策略**：优先识别 `secrets.DSHA_KEYSTORE_B64` 生产签名，未配置时严格按分支逻辑使用 Gradle debug 签名；
* **产物提纯**：提取构建产物并重命名为 `dist/DSHA-FR 0.1.5rc.2-u2.apk`。

### 2. 核心模块双打包规范 (`dsh-magisk`)
* **统一调度**：调用 `dsh-magisk` 官方收口打包脚本 `./scripts/build-module.sh`；
* **Lite 模块生成**：运行 `./scripts/build-module.sh --lite`，仅打包控制脚本与现场增量补丁；
* **Full 模块生成**：运行 `./scripts/build-module.sh full`，调用 `tools/dynamic-rootfs-merge.sh` 递归扫描 `rootfs-overlay/`，自动完成插件立牌、软链接创建与语法安全卫士检测，熔铸出全新底包并打包全内置 zip。

---

## 三、 触发与发布方式

1. **自动构建**：
   - 当向 `dsha-for-root.x` 分支推送提交时，自动触发编译，生成 Artifacts 提供下载；
2. **手动打包 (Workflow Dispatch)**：
   - 可在 GitHub Actions 页面手动点击运行，并可自由选择：
     - `publish_release`: 是否发布至 GitHub Releases；
     - `release_tag`: 自定义发布的版本 Tag；
3. **标签发布 (Git Tag)**：
   - 推送形如 `v1.0.0` 或 `dsha-for-root-1.0` 的 Tag 时，自动打包并将 3 大产物自动挂载至 Release 附件。
