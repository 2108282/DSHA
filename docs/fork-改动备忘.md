# Fork 改动备忘（2108282/DSHA）

记录本 fork 相对上游 `qiannianhuanxiang/DSHA` 的全部改动，便于日后 rebase、拆分提 PR，或者上游想合并时快速理解动机。

- **分叉基点**：上游 `main` @ `41539fa`（`fix(star-history): 去掉每次都变的时间戳字段`），对应 v1.1.9.1 之后
- **分支**：`feat/auth-lease-and-automation-improvements`
- **规模**：10 个提交，24 个文件，+1136 / −307

改动可以分成四组，彼此基本独立，**可以按组拆开提 PR**：A 授权租约、B 通知栏交互闭环、C 元素定位与截屏、D fork 侧 CI。其中 D 只服务于本 fork 的免费打包，不应该提给上游。

---

## A. 授权租约：一次同意，10 分钟双通道免打扰

**动机**：跑一个自动化任务要连续用到 `am start`、`screencap`、`input keyevent`，每一条都被守卫拦下弹一次确认框。用户点到第五次就不想用了。而 Java 侧的 `uiAuthorized`（无障碍点击授权）和容器侧的 Python 守卫是两套互不知情的判据，导致同一个任务要在两个通道各自被打断。

**做法**：引入单一事实来源 `/root/.dsh/.auth_lease`，内容是一个到期 Unix 时间戳。任意一侧授权后写入，两侧都读它。

| 文件 | 改动 |
|---|---|
| `app/src/main/assets/adb-shell.py` | 新增 `is_auth_lease_active()`；主判据改为 `DSH_INTERNAL != 1 and not is_auth_lease_active() and not is_readonly_cmd(cmd)`。租约过期时顺手删除租约文件。脚本版本 12 → 13 |
| `app/src/main/java/com/deepseekharness/app/AdbBridge.java` | `SCRIPT_VERSION` 12 → 13，强制重注入上面的新脚本 |
| `app/src/main/assets/rootfs-confirm-install.sh` | `dsh-confirm.sh` 开头加租约检查，命中直接 `exit 0`；守卫版本 11 → 12 |
| `app/src/main/java/com/deepseekharness/app/HarnessController.java` | `GUARD_VERSION` 11 → 12 与上面对齐（这两个数字不一致会导致每次启动都 `rm -rf` 重装守卫） |
| `app/src/main/java/com/deepseekharness/app/HttpShellService.java` | 新增 `authLeaseFileIfPossible()`；确认框文案追加「允许后 10 分钟内的屏幕与设备操作不再询问」，让用户知道自己批的是一段时间窗而不是一条命令 |
| `app/src/main/assets/adb-setup.sh` | `/root/dsh-bin/adb-shell` 包装器里删掉那段 `dsh-confirm.sh --force` 前置调用 —— 授权判定统一收归 `adb-shell.py` 内部，否则同一条命令被两层各弹一次 |

**顺带放开的只读子命令**（`adb-shell.py` 的 `READONLY_SUB`）：

```python
'am': frozenset(('start', 'stack', 'get-config', 'to-uri', 'to-intent-uri')),
'wm': frozenset(('size', 'density', 'displays')),
```

> ⚠️ **这是本 fork 唯一实质放宽安全边界的地方，上游 review 时应重点看这里。** `am start` 严格说不是只读——它能拉起任意 Activity、投递任意 Intent。放开的理由是跨 App DeepLink 直达全靠它，而它的破坏力受 Android 自身的 Intent 权限模型约束，拿不到 shell 之外的能力。如果上游不接受，退路是把 `am start` 留在确认路径里，仅靠租约免审（即只删 `READONLY_SUB` 这一处改动，A 组其余部分照旧可用）。

**临时文件清理放行**：`dsh-confirm.sh` 新增 `is_safe_cleanup()`。任务收尾删自己刚截的那张图，不该再弹一次框。判定刻意收得很紧：

- 只认 `rm` / `unlink` 开头
- 含 `-r` / `-R` / `*` 一律拒绝（不给递归和通配任何机会）
- 逐个参数白名单：`/sdcard/Download/<名>.{png,jpg,jpeg,tmp}`、`/tmp/<名>`、`/root/.dsh/.auth_lease`
- 有任何一个参数不在白名单 → 整条命令回退到正常确认流程
- `--force` 模式（设备 shell 报备）不走这条捷径

---

## B. 通知栏全生命周期闭环

**动机**：手机上跑长任务，人不会一直盯着 WebUI。原先通知栏只有一条「任务完成」，且点了没反应——`setContentIntent` 没绑，点击卡片什么都不发生。更要紧的是任务跑飞了没有刹车。

### B1. 运行中实时状态 + 停止按钮

`HttpShellService.java` 新增两个端点：

- `/app/task/running` —— 插件按 `kind` 推送状态：`tool` / `text` 更新当前步骤，`done` / `clear` 收起通知
- `/app/task/cancel` —— 收起运行中通知

配套 `showRunningNotification(title, text)` / `cancelRunningNotification()`，通知挂 `[🛑 停止任务]` 按钮。新通知 ID 见 `Constants.java`：`NOTIF_TASK_RUNNING=2003`、`NOTIF_TASK_STOPPED=2004`、`NOTIF_ASK_QUESTION=3007`。

### B2. 停止要真的停下来

这是踩坑最深的一处。第一版 `handleStopTask` 只清租约、收通知、弹一条「已终止」——**但后台 DSH 完全不知情，继续往下跑**，用户看到通知说停了、实际还在操作手机。

真正停下来需要三件事同时做到：

1. `ConfirmReceiver.handleStopTask()`：注销租约 + 写 `/root/.dsh/.cancel_requested` + 杀掉活动的工具子进程
2. `task-notifier` 插件轮询 `.cancel_requested`，通过 `ctx.inject(['agents'])` 拿到 agent 作用域，对 `agents.list()` 逐个调 `ag.cancel({ kind: 'user' }, { keepInbox: true })` —— **这才是真正中止 DSH 工作的那一步**
3. `showStoppedNotification()` 推终止通知，且这条通知自带输入框

`keepInbox: true` 是有意的：保住收件箱，用户才能接着在通知栏打字续上对话，而不是被清空重开。

### B3. 就地打字继续对话

`TaskNotifier.java` 与 `ConfirmReceiver.java` 给完成 / 终止通知都挂上 `RemoteInput`：

- 新增 action：`ACTION_TASK_REPLY`、`ACTION_STOP_TASK`、`ACTION_ASK_ANSWER`、`ACTION_ASK_REPLY`
- 新增 extra：`EXTRA_TASK_REPLY_TEXT`、`EXTRA_REPLY_TEXT`、`EXTRA_ANSWER`
- `handleTaskReply()` 取出用户输入写进 `/root/.dsh/.pending_prompt`
- 插件侧轮询该文件，找到目标 agent（优先 `agents.roots()`，退回 `agents.list()`）后调 `targetAgent.followup(msg)` 开启新一轮

PendingIntent 必须用 `FLAG_MUTABLE`（Android 12+），否则 RemoteInput 的文字传不进来。

### B4. 点通知回到对话界面

原先点通知只能回到 App 首页。改成全链路透传 `open_web` extra：

- `HarnessService.java` / `TaskNotifier.java` / `HttpShellService.java` / `ConfirmReceiver.java` 的所有 `PendingIntent` 统一 `.putExtra("open_web", true)` 并加 `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`
- `MainActivity.java` 新增 `onNewIntent()` + `handleNotificationEnterWeb()`，切到启动页并调 `LaunchFragment.enterWebDirectly()`
- `LaunchFragment.java` 新增 `enterWebDirectly()`：Web 已就绪直接进，没就绪则置 `enterWhenReady` 并自动触发启动

`onNewIntent` 那一半容易漏——App 已在前台时 `onCreate` 不会再走一遍，少了它「App 开着时点通知没反应」。

### B5. `/app/ask` 双通道

`showAskNotification()` 让助手提问同时以高优先级通知（选项做快捷按钮 + RemoteInput 自由输入）和前台弹窗两路呈现。两路用 `askEpoch`(AtomicLong) + `askResolved`(AtomicBoolean) + `CountDownLatch` 做互斥，`resolveAsk(answer, epoch)` 只认当轮 epoch，先到先得，另一路自动收起（`dismissAskDialog()` / `cancelAskNotification()`）。没有 epoch 校验的话，上一轮的过期通知按钮会污染这一轮的答案。

这里是照着 `AGENTS.md` 那两条坑位写的，改动时别退回去：`askBusy` 用 `AtomicBoolean.compareAndSet`（不是「检查后置位」）、弹窗 `setCancelable(false)` 且**只认按钮回调、不在 `OnDismiss`/`OnCancel` 里 `countDown`**（旋屏或 Activity 被回收造成的 dismiss 会给 agent 送回一句假的「用户关掉了提问框」）、`finally` 里的清理顺序是先 latch/弹窗/通知、最后才放开 `askBusy`。

### B6. 插件注册补齐

`HarnessController.java` 里 `dsha-task-notifier` 之前既不在 marker 清理列表、也不在 `missingBuiltinEntities()` 的目录数组里，等于资产版本自愈永远轮不到它，改了插件源码用户拿不到。两处都补上，`BUILTIN_ASSET_VERSION` 20 → 22 强制重注入。

另外 `AGENTS.md` 里 `fs-write-patch.sh` 一行的描述同步改成三个包（原文只写了 `dsh-fs-local`）。

`task-notifier/lib/index.js` 另加了 `TOOL_LABELS` 表和 `formatToolDetail()`，把 `tool/call` 事件翻译成人能读的一行（从 `command`/`path`/`query` 等 `ARG_KEYS` 里挑第一个有值的显示），否则通知栏只能看到工具名。

---

## C. 元素定位与静默截屏

### C1. 两阶段候选池消歧（`device-shell-guide/lib/index.js`）

**动机**：在淘宝、京东搜索时，AI 高频把输入框里的「语音搜索」话筒当成右上角的「搜索」按钮点掉。根因是底层按子串模糊匹配，而话筒节点在 UI 树里恰好排在真按钮前面，扫到第一个含「搜索」的就截断触发。

提示词里加入硬约束：先遍历 dump **全部**元素建候选池（禁止遇到第一个相似项就点），再综合消歧——完全匹配优先于包含匹配、独立按钮优先于内嵌图标、结合屏幕区域（提交按钮通常在右上/最右），定出唯一目标后用该节点的精确中心坐标 `/app/ui/tap?x=&y=` 点击，绕开底层模糊匹配抢跑。同时禁止盲点未在 dump 中确认的估算坐标。

同一处还补了三条：三通道的定位说明（`/app/*` 是基石与保底、ADB 是高级通道）、跨页面 DeepLink 直达与降级策略（`/app/open?url=` 只吃标准 scheme，第三方私有 scheme 如 `openapp.jdmobile://`、`tbopen://` 必须走 `am start`；ADB 不可用时回退 `/app/launch` + `/app/ui/*`）、以及租约标准工作流（先查租约再决定是否 `/app/ask`，任务结束**不删租约**让它自然过期）。

### C2. 无障碍原生静默截屏（`accessibility_service_config.xml`）

一行：`android:canTakeScreenshot="true"`。缺这个声明，系统降级走 `MediaProjection` 投屏接口，每截一张图强制弹一次录屏授权框，视觉识别根本没法连续用。补上之后走无障碍底层显存通道，静默出图。

### C3. 附件补丁自检（`selftest.py`）

`fs-write-patch.sh` 新增了对 `dsh-attachment-local` 的处理（第三个用 `link()` 原子发布的包，Android 外部存储上必然失败，报 `ATTACHMENT_WRITE_FAILED`）。`selftest.py` 相应加 `ATTACHMENT_PKG_CANDIDATES` 与「附件发布补丁」检查项，找 `DSHA_L2S_FIX_ATTACHMENT` 标记，未打时给出「到启动页点重启」的处置建议。详见 `bug/图片发送ATTACHMENT_WRITE_FAILED修复记录.md`。

---

## D. Fork 侧 CI（不建议提给上游）

`.github/workflows/android-build.yml`：删掉 `bundle` job（原本在 `ubuntu-24.04-arm` 上现编 arm64 rootfs，约 90 分钟且 fork 用不上 ARM runner 配额），改为直接从上游 release APK 里 `unzip -p` 抽出 `assets/offline-rootfs.*`。触发分支加 `feat/**`。

**这组改动纯为本 fork 的免费打包服务，会破坏上游的正式发布链路，提 PR 时必须排除。** 已知代价：

- 硬编码了 `v1.1.9.1` 的下载 URL，上游发新版要手改
- `offline-rootfs.version` 恒写 `1`，不参与升级判定
- 删掉了签名指纹校验步骤（`Report signing fingerprint`）

---

## 版本标记总账

`AGENTS.md` 的铁律：凡是塞进 rootfs 的东西都要有版本标记，否则老安装永远留着旧副本。本 fork 动过的标记：

| 标记 | 变化 | 因为改了 |
|---|---|---|
| `AdbBridge.SCRIPT_VERSION` | 12 → 13 | `adb-shell.py`、`adb-setup.sh` |
| `HarnessController.GUARD_VERSION` | 11 → 12 | `rootfs-confirm-install.sh`（**该脚本末尾 `echo 12` 已同步改过**，两处数字必须相等） |
| `HarnessController.BUILTIN_ASSET_VERSION` | 20 → 22 | 两个内置插件资产 |
| `device-shell-guide/package.json` | 0.1.14 → 0.1.16 | 提示词 |
| `task-notifier/package.json` | 0.1.0 → 0.2.0 | 插件逻辑 |

`STEP6_VERSION` 保持 `4` 未动 —— 步骤⑥安装的东西本身没变，变的是它注入的资产内容，那由 `BUILTIN_ASSET_VERSION` 负责。

---

## 已知缺口

- **`xdg-open` 适配器未入仓**。`/usr/local/bin/xdg-open`（Web 点击文件时 1:1 无损复制到 `Download/DSHA/`，解决 `spawn xdg-open ENOENT` 和导出被压成 `.gz`）目前只存在于设备文件系统上，不在 git 里，重装即丢。要保留得放进 `app/src/main/assets/` 并挂到 provision 流程。
- **`runtime-manifest.sig` 被删**（不是修改，是整个文件删掉）。改了 assets 就必须重签，而签名用的 `DSHA-release.keystore` 只有上游有，所以本 fork 只能删掉它。按 `AGENTS.md` 的说明，签名不对会让客户端拒收整批增量更新 —— 提 PR 时必须请上游用 `tools/sign-runtime-manifest.sh` 重签，不要自己塞一个假的进去。
- **B2 的停止链路只在真机验证过一次**，没有自动化回归。`agents.list()` 与 `agents.roots()` 的行为依赖 dsh 版本，上游升级 agent scope API 会静默失效。
- **本地未跑过构建验证**。`tools/pure-logic-test.sh` 和 `:app:assembleDebug` 都没在这个容器里跑过（缺 SDK 34 / NDK 26），编译正确性只由 fork CI 的 `assembleDebug` 背书。

## rebase 现状

分叉基点 `41539fa` **就是上游 `main` 的当前 HEAD**，所以此刻是零冲突、可直接开 PR 的状态。上游那批大改动（提示词压缩 `cdac5de`、停止链路重做、`/root` 整体数据保护 `8759092`）全部落在基点之前，已经包含在内，不构成冲突。

上游继续往前走之后再 rebase，最可能撞车的是这三个文件：`runtime-manifest.json`（每次改 assets 都会重算哈希，必冲突且只能重新生成而非手工合并）、`HttpShellService.java`（B 组在里面加了 375 行）、`device-shell-guide/lib/index.js`（C1 改的是提示词字符串数组，上游也常动它）。
