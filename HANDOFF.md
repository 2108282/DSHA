# DSHA 交接手册（给下一个 AI / 开发者）

> **先读本文件，不要先全库扫描。**  
> 读完第 0～3 节就能动手。后面是地图、坑和未完成事项。

---

## 0. 你现在接手的是什么局面

日期：2026-08-19（用户时区 Asia/Shanghai）。

主人是 GitHub 用户 **`rthdfd`**，仓库是他自己的 fork：  
https://github.com/rthdfd/DSHA

上游原作：https://github.com/qiannianhuanxiang/DSHA  
主人已获作者**书面同意**，并已被加成上游 Write 协作者。本环境的 GitHub App **仍然不是**主人账号，不能替他在上游点「Create pull request」（403）。

**当前工作分支（必须一直待在这里，不要切分支）：**  
`arena/01a0149c-dsha`

**版本：** `versionName 1.1.2` / `versionCode 18` / 文件 `VERSION` = `v1.1.2`

**fork 上已有一份给作者 AI 看的 PR 说明（开在自己仓库，上游看不到）：**  
https://github.com/rthdfd/DSHA/pull/1

**历史已接上：** 本分支用 `--allow-unrelated-histories` 合并了上游 `main`（`v1.1.0-fixed50` / `6e8d6a7`）。保留上游 ADB / mobile-adapt 等文件，也保留我们的解压页 + 两段 CI。不要再 `reset --soft` 到上游。

用户要用 **rthdfd 账号**打开比较页，点绿色 **Create pull request**。Issues ≠ PR。

---

## 1. 项目一句话

**DSHA = DeepSeek Harness 安卓启动器。**  
在 **arm64 / Android 8+** 手机上跑 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的 Web UI。  
免 ROOT。默认路径也**不需要 Termux**。

做法：App 里塞 Termux 官方 `proot`（jniLibs 里伪装成 `libproot.so`），套一层 Ubuntu 24.04 arm64 rootfs，里面是 Node 24 + pnpm + `@deepseek-ai/dsh@0.1.0-rc.6`（RC6，App 默认 `use_rc6=true`）。Web UI 用系统 WebView，可选 GeckoView。

`applicationId`：`com.dsh.client`  
Java 包名：`com.deepseekharness.app`  
Gradle 工程名：`deepseekharness`  
唯一模块：`:app`  
语言：纯 Java 17，没有 Kotlin。

---

## 2. 用户打开 App 之后必须发生什么

这是产品契约，改启动逻辑时不要破坏。

```
冷启动 MainActivity
  welcomed == false  → WelcomeActivity（3 页）
       点「开始/跳过」→ ExtractActivity     // 强制，不能跳过
  welcomed == true 且 !isOfflineExtracted()
       → ExtractActivity                    // 强制
  isOfflineExtracted() == true
       → 主界面（底栏：启动 / 终端 / 市场 / 设置）

ExtractActivity
  已有 files/linux/.offline-extracted 且 bash 在
       → 显示「已就绪」约 400ms → MainActivity(skip_extract=true)
  APK 里找到内置包
       → 全屏进度「正在解压环境…」→ markOfflineExtracted() → Main
  找不到包
       → 停在本页，红字打印 diagnoseBundle()
       → 绝不能偷偷跳去「设置 → 安装」
```

解压完用户只需：设置 → 配置 → 填 DeepSeek API key → 启动页点启动。  
**不需要**再走分步「一键安装」。分步安装代码还在，只是带内置包的 APK 默认不走。

`Extract → Main` 必须：

```java
intent.putExtra("skip_extract", true);
```

否则 Main 看到 `!isOfflineExtracted()` 又拉起 Extract，死循环。

---

## 3. 目录与关键文件（按职责，不要通读 2000 行）

```
DSHA/
├── HANDOFF.md                 ← 你正在读的文件
├── README.md                  ← 用户向说明，偏短
├── VERSION                    ← v1.1.2
├── build.sh                   ← 本地 assembleDebug，默认找 /workspace/gradle 和 /workspace/android-sdk
├── gradle.properties          ← 有一行 ARM 本机 aapt2 覆盖，CI 必须 sed 删掉
├── .github/workflows/android-build.yml   ← 真正会跑的 CI
├── scripts/
│   ├── ci-make-offline-bundle.sh   ← ARM runner 上 chroot 打 offline-rootfs.tar.gz
│   ├── offline-provision.sh        ← chroot 内：apt + Node + RC6（失败再源码）
│   ├── ci/android-build.yml        ← workflow 副本（曾因 token 不能推 .github/workflows）
│   └── make-offline-bundle.sh      ← Termux 兜底，默认不要用
├── agent-skills/              ← 给外部 agent 的 skill，和 APK 构建无关
└── app/
    ├── build.gradle           ← 版本、ABI、签名、protectOfflineBundle、verifyArm64Apk
    └── src/main/
        ├── AndroidManifest.xml
        ├── jniLibs/arm64-v8a/libproot.so 等
        ├── assets/            ← 补丁脚本；离线包由 CI 拷进来，不进 git
        └── java/com/deepseekharness/app/
            ├── MainActivity.java
            ├── WelcomeActivity.java
            ├── ExtractActivity.java      ← 解压页
            ├── LaunchFragment.java       ← 启动/预览
            ├── SettingsFragment.java     ← 设置列表，点进去才是安装/配置/工作区
            ├── InstallFragment.java      ← 分步安装 UI（保留）
            ├── ConfigFragment.java
            ├── WorkspaceFragment.java
            ├── TerminalFragment.java
            ├── PluginFragment.java
            ├── HarnessController.java    ← 业务中枢，约 2200 行
            ├── ProotBootstrap.java       ← proot / 下载 / 解压 / 内置包
            ├── TarGzipExtractor.java     ← 纯 Java tar / tar.gz
            ├── HarnessService.java       ← 前台保活
            ├── HttpShellService.java     ← 127.0.0.1:3090
            ├── ShizukuShell.java
            └── ...
```

`HarnessController.java` 很大。不要整文件重写。改安装检测看 `isHarnessInstalled` / `isHarnessReady` / `hasPtyNode`；改启动看 `startWeb` / `runCoreCommand`。

---

## 4. 运行时架构

```
Android UI (Java + Material3 + BottomNav)
        │
HarnessController          安装步骤、配置、启停 Web、多源测速
        │
ProotBootstrap             下载/解压/exec rootfs
        │
libproot.so + Ubuntu 24.04 arm64
        │
Node 24 + pnpm + dsh RC6（或源码树 /root/deepseek-harness）
        │
Web UI  ← WebView，可选 GeckoView（geckoview-arm64-v8a）

旁路：
  HarnessService      前台保活
  HttpShellService    127.0.0.1:3090 给 agent 跑设备命令
  ShizukuShell        真机 shell
  DangerShellGuard    危险命令二次确认
  LanProxyService     局域网
  BackupManager       备份到 Download/DSHA
```

proot 二进制放在 `jniLibs`，靠 `useLegacyPackaging = true` 解压到 `nativeLibraryDir` 才能 exec（Android 10+ W^X）。

rootfs 在 App 私有目录：`files/linux/ubuntu/`。  
内置包解压成功标记：`files/linux/.offline-extracted`。  
旧标记 `.installed` 仍然会写，但**进主界面只认** `.offline-extracted` + bash。

`isInstalled()` = `usr/bin/bash` 或 `bin/bash` 存在。  
Ubuntu 24.04 的 `/bin` 是软链，有的机型 `Os.symlink` 失败，所以不能只认 `ubuntu/bin`。

---

## 5. CI（这是本分支的核心交付）

公开仓库 Actions 免费。主人只有手机，构建必须走 CI。

### 两段 job，不要再合成一段

| Job | Runner | 做什么 | 禁止 |
|---|---|---|---|
| `bundle` | `ubuntu-24.04-arm` | chroot 预装，产出 `offline-rootfs.tar.gz` | **禁止** setup-android |
| `apk` | `ubuntu-latest` | 把 bundle 拷进 assets，assembleDebug | 必须 `sed` 掉 `aapt2FromMavenOverride` |

原因：

- `node-pty` 必须在**原生 aarch64** 上编。x86 + qemu/docker 会 **OOM exit 137**。
- Maven/官方 aapt2 只有 x86_64。ARM runner 上 `android-actions/setup-android` 会挂。

### 缓存 key

```
offline-rootfs-${{ hashFiles(
  'scripts/offline-provision.sh',
  'scripts/ci-make-offline-bundle.sh',
  'app/src/main/assets/rootfs-confirm-install.sh',
  'app/src/main/assets/webui-polyfill.sh',
  'app/src/main/assets/*.patch') }}
```

命中则跳过 chroot（约 15 秒）。未命中 15～40 分钟。

### Artifact

- `offline-rootfs-bundle` → `offline-rootfs.tar.gz`（约 290MB）
- `dsha-debug-apk` → **zip**，里面才是 `app-debug.apk`

用户从手机下 Artifact，必须先解压再装 apk。直接装 zip 会「解析包出现错误」。

### 已跑绿的 run（fork）

- https://github.com/rthdfd/DSHA/actions/runs/32146454831 （1.1.2，含 aapt `.tar` 修复）
- https://github.com/rthdfd/DSHA/actions/runs/32142665799
- https://github.com/rthdfd/DSHA/actions/runs/32134128433

### `offline-provision.sh` 契约

- CI（`GITHUB_ACTIONS`）走官方 apt/npm 源；保留 ca-certificates（`DSHA_KEEP_CA=1`）。
- 非 CI 才换清华源，并且可能拆掉 ca-certificates（那是 proot 下的老逻辑）。
- **默认装 RC6**：`npm i -g @deepseek-ai/dsh@0.1.0-rc.6`，编 node-pty。
- RC6 失败才 clone `deepseek-ai/deepseek-harness` + pnpm。
- 结束时必须有：`dsh` 命令、`/root/dsh-guard.sh`、`/root/dsh-bin/.version`。
- **不要**同时塞一份半截源码树。`startWeb` 若发现 `/root/<workdir>/apps/cli/lib/bin.js` 会走源码启动；RC6 模式下 `depsSelfHeal()` 是空的，半截源码会把启动带沟里。

### Gradle 打包契约

`app/build.gradle` 任务 `protectOfflineBundle`：`preBuild` 前把  
`assets/offline-rootfs.tar.gz` **改名为** `assets/offline-rootfs.bin`。

原因见第 6 节坑 F。不要删这个任务，不要只改回 `.gz` 却不改查找逻辑。

`verifyArm64Apk` 在 `assembleDebug` 之后跑：APK 必须含 `lib/arm64-v8a/*.so`，不能有其它 ABI；assets 里必须能看到 `offline-rootfs*`。

`gradle.properties` 里这行只给本机 ARM 工作区：

```
android.aapt2FromMavenOverride=/workspace/android-sdk/build-tools/34.0.0/aapt2
```

CI（x86）必须删掉。作者若在 x86 本机构建也要删。

---

## 6. 已经踩死的坑（不要重蹈，不要当「可简化」删掉）

### 坑 A — `implementation` 两行粘一行

```
implementation 'androidx.appcompat:appcompat:1.6.1'    implementation 'com.google.android.material:...'
```

Gradle 报 `Could not find method implementation()`。已拆开。

### 坑 B — 无后缀 GeckoView 经常是 x86_64

必须：

```
implementation 'org.mozilla.geckoview:geckoview-arm64-v8a:126.0.20240526221752'
```

并 excludes `x86` / `x86_64` / `armeabi` / `armeabi-v7a`。

### 坑 C — 国产系统装 debug 包要 v1 签名

```
signingConfigs.debug { enableV1Signing/V2/V3 true }
```

### 坑 D — `AssetManager.open` 打不开 300MB+ asset

`hasOfflineBundle()` / 解压必须先 `ZipFile(getPackageCodePath())`，assets.open 只是 fallback。

### 坑 E — 旧 `isInstalled()` 

旧定义：`.installed` 存在 **且** `ubuntu/bin` 存在。

- 解压成功后曾在**写标记之前**调用它 → 必失败。
- 用户网上装一半已有 bash → 整页跳过解压。

现在进主界面只认 `isOfflineExtracted()`。网上装一半的 bash 不能代替内置包。

### 坑 F — aapt 会把 `.tar.gz` 解成 `.tar`（已用真机截图钉死）

用户机诊断原文：

```
apkSize=426043609
zipHit=null size=-1
assets/offline-rootfs.tar 1067530240    ← 约 1.02GB 裸 tar
assets.list 里是 offline-rootfs.tar，没有 .gz
```

代码当时只找 `.tar.gz`，所以「APK 里没找到内置环境包」。包其实在。

处理：

1. 找这些名字：`.tar.gz` / `.tar` / `.bin` / `.tgz`，以及名字含 `offline-rootfs` 的最大 zip 条目。
2. `TarGzipExtractor.extractAuto`：魔数 `1f 8b` 走 gzip，否则当裸 tar。
3. 打包前改名为 `.bin`，避免 aapt 再改。

### 坑 G — GitHub Artifact 是 zip

下载下来的 `dsha-debug-apk` 是 zip。直接当 APK 安装 → 「解析包出现错误」。先解压，只装 `app-debug.apk`。

### 坑 H — 本环境 token

- 不能推/改 `.github/workflows/`（除非用户自己在网页上改）。仓库里那份 workflow 是用户手动贴进去的。
- 不能给 `qiannianhuanxiang/DSHA` 建 PR（403）。
- 不能给人加 collaborator。只有作者打开  
  https://github.com/qiannianhuanxiang/DSHA/settings/access  
  → Add people → `rthdfd` → Write。

### 坑 I — 不要用的 CI 方案

| 方案 | 结果 |
|---|---|
| x86 + docker `--platform linux/arm64` + qemu | OOM 137 |
| 整个 job 跑 `ubuntu-24.04-arm` 并 setup-android | SDK 失败 |
| 从本仓库 Release 下 bundle | 没有 Release，鸡生蛋 |
| 让用户用 Termux 打 bundle | 主人明确不想再用 Termux |

---

## 7. 启动 / 解压不变量（改代码时保持）

```
Extract → Main 必须带 skip_extract=true
extract 成功必须 markOfflineExtracted()（不要只 markInstalled）
进主界面用 isOfflineExtracted()，不用 isInstalled()
找内置包不要只 open("offline-rootfs.tar.gz")
解压流必须 extractAuto，不能假定一定是 gzip
use_rc6 默认 true；预装默认 RC6；不要同时放半截源码树
```

`HarnessController.isHarnessReady` / `hasPtyNode`：源码树 **或** `/usr/local/lib/node_modules` 里的 node-pty，任一即可。

---

## 8. 明天要做的事（上游 PR）

作者把 `rthdfd` 加成 Write 之后，优先：

1. `git fetch https://github.com/qiannianhuanxiang/DSHA.git main`
2. **不要** `reset --soft` 到上游（会删掉上游独有的 ADB / mobile-adapt 等文件）。
3. 在 `arena/01a0149c-dsha` 上把我们的改动 **移植** 到上游树上：  
   以 upstream/main 为底，只挑本分支的「CI 两段 job + 解压页 + aapt/arm64/签名/gradle」相关文件合进去。
4. 冲突高发：`HarnessController.java`、`ProotBootstrap.java`、`LaunchFragment.java`、`app/build.gradle`、`AndroidManifest.xml`。上游这些文件更新。我们要的是解压/CI 行为，不要覆盖掉作者的 ADB 配对、mobile-adapt、插件热补丁。
5. 推到 `arena/01a0149c-dsha`（或作者让你推的分支），再在有共同祖先的前提下开 PR 到 `qiannianhuanxiang/DSHA` 的 `main`。
6. PR 正文直接抄 https://github.com/rthdfd/DSHA/pull/1 （已按「给作者 AI」写过）。

在加协作者之前，比较页这个链接**不会出现绿色按钮**，不要再让用户去那儿找：

https://github.com/qiannianhuanxiang/DSHA/compare/main...rthdfd:DSHA:arena/01a0149c-dsha?expand=1

上游独有、合并时必须保留的文件（不完全）：

- `app/src/main/java/.../AdbBridge.java`
- `AdbPairActivity.java` / `AdbPairReceiver.java` / `DeviceBridgeService.java`
- `assets/adb-*.py` `adb-setup.sh` `config-fix.js` `mobile-adapt/` `webui-degrade-patch.sh`
- `BUILD.md`

本分支独有、合并时必须带上的文件：

- `ExtractActivity.java` + `res/layout/activity_extract.xml`
- `scripts/ci-make-offline-bundle.sh` / `offline-provision.sh` / `scripts/ci/android-build.yml`
- `.github/workflows/android-build.yml`（两段 job 版本）

---

## 9. 本地 / CI 常用命令

```bash
# 本机（需要 SDK + 已有 offline-rootfs.tar.gz 放到 assets）
./build.sh

# 看 fork 上的 Actions
gh run list --repo rthdfd/DSHA --branch arena/01a0149c-dsha

# 下 APK artifact（得到的是 zip）
gh run download <id> -R rthdfd/DSHA -n dsha-debug-apk
```

触发 CI：往 `arena/01a0149c-dsha` 或 `main` push，或 Actions 页 Run workflow。

---

## 10. 人与仓库

| 谁 | 是什么 |
|---|---|
| `rthdfd` | 本 fork 主人，当前会话的用户 |
| `qiannianhuanxiang` | 上游作者，QQ 群 960636357 |
| 本会话分支 | `arena/01a0149c-dsha`，不要切走 |
| fork PR | https://github.com/rthdfd/DSHA/pull/1 |
| 最新成功 APK 构建 | https://github.com/rthdfd/DSHA/actions/runs/32146454831 |

App 里「关于」对话框的 GitHub 链接仍指向上游：  
`https://github.com/qiannianhuanxiang/DSHA`

---

## 11. 给下一个 AI 的工作风格

- 主人常用手机，步骤要短。GitHub 比较页一旦没绿按钮，直接说「没有按钮」，不要让他翻代码找。
- Issues ≠ PR。不要再教他去 Issues 交代码。
- 改启动/解压/打包前先重读第 2、6、7 节。
- `HarnessController.java` 只做最小补丁。
- 不要引入 Termux 作为默认路径。
- 不要在 x86 CI 里 qemu 编 arm64 node-pty。
- 提交信息用中文、说清楚「为什么」，和现有 log 风格一致。
- 只推 `arena/01a0149c-dsha`。
