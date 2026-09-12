# DSHA test 分支改动与 RC1 启动日志 / 私有底包方案落地记录

## 一、分支说明

本分支（`test`）由 `1.2.0rc1test` 分支完整复制拉出，所有针对 0.1.5-rc1 的功能缝合与方案 B 底包自主化改动均在 **`test`** 分支内独立进行，未对 `1.2.0rc1test` 造成任何污染。
用户的专属王牌功能（如快捷对话抽屉 `QuickChatSheetActivity`、3090 设备桥、备份恢复工作区动态重映射）100% 原样保留。

---

## 二、本次改动清单

### 1. 移植 0.1.5-rc1 启动时间线日志系统（彻底告别“还没有日志”）
* **引入核心时间线引擎**：
  - `app/src/main/java/com/deepseekharness/app/util/StartupTrace.java`：毫秒级流逝时间戳计算、各阶段（环境准备、创建进程、等待鉴权、代理绑定）耗时统计、终端 ANSI 控制符清洗、有界 300 行防内存膨胀；
  - `app/src/main/java/com/deepseekharness/app/util/DshAuthLog.java`：按行流式脱敏，防止 Token/Key 明文外泄；
  - `app/src/main/java/com/deepseekharness/app/util/PluginFailureOwner.java`：正则匹配并智能归因报错插件；
  - `app/src/main/java/com/deepseekharness/app/util/TextLogTail.java`：有界高效读取末尾 UTF-8 运行日志；
  - `app/src/main/java/com/deepseekharness/app/core/StartupDiagnostics.java`：进程输出拦截、`[DSHA_STARTUP]` 阶段事件分发与案发现场保全（`preserveFailure`）。
* **业务接入与秒级自愈**：
  - `HarnessController.java`：在启动全流程接入 `startupDiagnostics.begin`、`stage` 和 `output`，进程异常退出时自动生成 `last-startup-failure.log`；
  - `LaunchFragment.java`：启动页每秒定时比对 `trace.revision`，只要切入启动页或重新打开 App，时间线瞬间恢复并自动滑至末尾，绝不再卡死在“还没有日志”。

### 2. 资产防解包优化与拆分底包兼容
* **阻止 aapt 自动解包膨胀**：
  将 `app/src/main/assets/` 下的 `adb-wheels.tar.gz` 和 `glibc-python.tar.gz` 重命名为 `.bin` 后缀。Android 编译工具不再强制将其解压为裸 tar，成功为安装包瘦身；Java 解压器依靠头两个字节的 Gzip 魔数（`0x1f 0x8b`）自动解压，完全平滑兼容。
* **兼容未来拆分运行时**：
  在 `ProotBootstrap.java` 中加入对 `offline-rootfs.layout`（`split-runtime-v1`）规范的支持，自动兼容单包与拆分包。

### 3. 方案 B：底包私有化与云端一键转存工作流
* **新增转存工作流**（`.github/workflows/sync-base-rootfs.yml`）：
  支持 GitHub Actions 页面一键点击（`workflow_dispatch`），由 GitHub 服务器全自动将指定 Release 的 `offline-rootfs.bin` 抓取并发布至当前仓库自身的固定 Release（如 `0.1.5rc1-base` 或 `0.1.5rc.2-base`）。全程无需用户消耗手机流量。
* **对齐构建拉取源**（`.github/workflows/android-build.yml`）：
  构建 APK 时优先从用户自身的仓库 Release 下载离线底包，彻底脱离外部第三方仓库的任何依赖。

---

## 三、方案 B 操作指南（如何在 GitHub 上一键转存底包）

当你想把 0.1.5-rc1 的精简底包彻底收拢到自己的仓库名下时：
1. 打开你的 GitHub 仓库（`2108282/DSHA`）➔ 点击顶部的 **Actions** 标签页；
2. 在左侧工作流列表中找到 **`Sync & Publish Base Rootfs Asset`**；
3. 点击右侧的 **Run workflow** 按钮：
   - `source_repo`：填入源仓库（默认为 `DSH-APP/DSHA`）；
   - `source_tag`：填入你想转存的 Tag（如 `v0.1.5-rc1`）；
   - `target_tag`：填入你想发布到自己仓库的 Tag（如 `0.1.5rc1-base`）；
4. 点击绿色的 **Run workflow**。GitHub 云端会在 1 分钟内自动下载并发布到你自己的 Release 页面下，一劳永逸！
