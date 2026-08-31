# Fork 改动备忘（2108282/DSHA）

记录本 fork 相对上游 `qiannianhuanxiang/DSHA` 的全部改动，便于日后 rebase、拆分提 PR，或者上游想合并时快速理解动机。

- **分叉基点**：上游 `main` @ `41539fa`（`fix(star-history): 去掉每次都变的时间戳字段`），对应 v1.1.9.1 之后
- **分支**：`feat/auth-lease-and-automation-improvements`
- **规模**：11 个提交，24 个文件，约 +1200 / −310

改动可以分成五组，彼此基本独立，**可以按组拆开提 PR**：A 授权租约、B 通知栏交互闭环、C 元素定位与截屏、D 图片附件软链接修复、E fork 侧 CI。其中 E 只服务于本 fork 的免费打包，不应该提给上游。

D 组虽然被混在 `9afb5a4` 那个通知栏大提交里一起提交了，但它跟通知栏毫无关系，是独立的一处 bug 修复；**在另一个仓库 `/root/工作区/dsha-repo` 里它有一个干净的单主题提交 `6dcdcb8`**（父提交就是上游 `41539fa`，内容与 `9afb5a4` 里那份逐字节一致）。提 PR 直接用它，不必从混合提交里做手术。

---

## A. 模拟点击授权租约：10 分钟双通道临时租约授权
<img width="2160" height="2880" alt="image" src="https://github.com/user-attachments/assets/b524ec1d-f7e6-4c11-a05e-dbaeb43c6a57" />

(仅加入了原生通知和按钮，演示通知样式为其他lsposed模块实现)
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

## B. 任务过程接入交互通知栏（不想看到悬浮栏）
<img width="2160" height="2880" alt="image" src="https://github.com/user-attachments/assets/1fd59770-2bbd-472a-afe3-4c28bc0bd0e2" />
(仅加入了原生通知和按钮，演示通知样式为其他lsposed模块实现)
**动机**：手机上跑长任务，人不会一直盯着 WebUI。原先通知栏只有一条「任务完成」，且点了没反应——`setContentIntent` 没绑，点击卡片什么都不发生。更要紧的是任务跑飞了没有刹车。

### B1. 运行中实时状态 + 停止按钮

`HttpShellService.java` 新增两个端点：

- `/app/task/running` —— 插件按 `kind` 推送状态：`tool` / `text` 更新当前步骤，`done` / `clear` 收起通知
- `/app/task/cancel` —— 收起运行中通知

配套 `showRunningNotification(title, text)` / `cancelRunningNotification()`，通知挂 `[🛑 停止任务]` 按钮。新通知 ID 见 `Constants.java`：`NOTIF_TASK_RUNNING=2003`、`NOTIF_TASK_STOPPED=2004`、`NOTIF_ASK_QUESTION=3007`。

### B2. 修复通知栏点击按钮不生效

这是踩坑最深的一处。第一版 `handleStopTask` 只清租约、收通知、弹一条「已终止」——**但后台 DSH 完全不知情，继续往下跑**，用户看到通知说停了、实际还在操作手机。

真正停下来需要三件事同时做到：

1. `ConfirmReceiver.handleStopTask()`：注销租约 + 写 `/root/.dsh/.cancel_requested` + 杀掉活动的工具子进程
2. `task-notifier` 插件轮询 `.cancel_requested`，通过 `ctx.inject(['agents'])` 拿到 agent 作用域，对 `agents.list()` 逐个调 `ag.cancel({ kind: 'user' }, { keepInbox: true })` —— **这才是真正中止 DSH 工作的那一步**
3. `showStoppedNotification()` 推终止通知，且这条通知自带输入框

`keepInbox: true` 是有意的：保住收件箱，用户才能接着在通知栏打字续上对话，而不是被清空重开。

### B3. 通知交互与 Active 快捷抽屉弹层架构 (`QuickChatSheetActivity`)
点击通知栏常驻通知或者交互按钮即可调出
<img width="1200" height="2670" alt="image" src="https://github.com/user-attachments/assets/eacf404b-9017-4535-b8b3-7c474f072ca5" />


（弹层高度过低时输入法有bug，懒得修了）
通知交互全面升级为 **`QuickChatSheetActivity` 全局快捷悬浮抽屉弹层**（取代原本局限狭窄的通知栏 `RemoteInput` 打字输入框）：

#### 核心特性与技术规范：
1. **物理边缘 100% 满屏贴合与零留白**：
   - 彻底摆脱 Dialog 边距约束：配置 `Theme.DeepseekHarness.SheetTransparent`（`windowIsTranslucent=true`、`windowIsFloating=false`、`statusBarColor=@android:color/transparent`）；
   - 消除系统浮动边框，左右两侧 **100% 贴满手机物理屏幕边缘**，呈现沉浸式宽屏视觉。
2. **15% 多档智能阶梯吸附停靠（35% ~ 95%）**：
   - 内置基于 `screenHeight` 的 15% 步长阶梯锚点算法（**35%、50%、65%、80%、95%**）；
   - 默认初始高度为 **78%**（大开阔视野），可拉升至 **95%**（全屏展开态）；
   - 拖拽松手时自动吸附到最近的 15% 档位；仅当在半屏继续用力向下拉动、高度低于安全下限（`< 25%`）时才触发向下滑出退出，**彻底杜绝从全屏拉回半屏时误退出**。
3. **毛玻璃半透明质感与 CSS 变量透光**：
   - 动态绘制 24dp 顶部大圆角与 `#EBF5F8FC`（浅色）/ `#EB161B24`（深色）半透明背景与细微描边；
   - 页面加载时注入动态 CSS 样式，强制覆写 DSH 前端的 `--dsw-alias-bg-base`、`--dsw-alias-bg-layer-1`、`--dsw-specific-sidebar-fill` 为 `transparent !important`；
   - 显式关闭 `FORCE_DARK` 与 `setAlgorithmicDarkeningAllowed(false)`，**100% 保持 DSHA 浅色原貌，透出底层桌面壁纸与应用**。
4. **顶栏 4 按钮像素级统一规格与新会话功能**：
   - **规格对齐**：4 个按钮一律锁定为 **36dp × 36dp** 外层触摸区与 36dp 正圆水波纹反馈，内部图形严格基于 24dp 视口与 **1.85dp 黄金中等线宽**圆倒角绘制；
   - **对称绝对居中**：左侧 2 个按钮（`[✕]` + `[⚙]`）与右侧 2 个按钮（`[💬➕]` + `[⬒]`）完全对称，中间“DSHA 对话”标题在物理屏幕正中央 100% 绝对居中；
   - **4 按钮功能一览**：
     - **① `[ ✕ ]` 关闭**：光学收敛细线交叉，顺滑滑出退出并转入后台保活（`moveTaskToBack`）；
     - **② `[ >_ ]` 容器终端控制台**：圆角窗口外框与命令行提示符 `>_`，一键唤醒并跳转至 `MainActivity` 容器后台主界面（启动/终端/市场/设置）；
     - **③ `[ 💬➕ ]` 开启新对话**：圆角气泡内嵌十字加号，毫秒级通过 DOM 事件触发 DSH 前端内置新会话广播或路由重置，就地清空输入框并开启全新对话流；
     - **④ `[ ⬒ ]` 全屏聊天**：顺时针旋转 90° 后的顶部分栏形态，一键进入主 App 完整全屏对话页（彻底避开与网页内展开侧边栏图标的视觉撞车）。
5. **键盘自适应与同色底板覆盖（顶栏绝对固定）**：
   - 卡片顶边物理锚定在原处，键盘弹出时顶栏、关闭与设置按钮绝对不被顶飞出屏幕；
   - 卡片底板延伸覆盖到屏幕物理最底端，键盘覆盖在卡片上方，**透过键盘半透明缝隙看到的依然是纯净浅色底板，彻底消灭黑块与漏桌面现象**；
   - WebView 可用视口等额收缩，网页底部的输入框自动精准吸附在输入法正上方。
6. **内存单例保活秒开（0 秒加载、0 网络请求）**：
   - 采用内存静态 `WebView` 常驻缓存，1:1 精准还原字体（移除 OverviewMode，设置 textZoom 100）；
   - 用户下滑或点击 ✕ 退出时，平滑滑出后调用 `moveTaskToBack(true)` 挂起保活，不销毁 WebView 与 WebSocket 连接；
   - 再次点击通知或发送命令时，通过 `FLAG_ACTIVITY_REORDER_TO_FRONT` 与 `onNewIntent()` 毫秒级滑出，**会话状态不丢、输入框草稿不丢、零重新加载**。

#### 命令行直接调出方法：
```bash
# 1. DSHA 容器内执行（推荐）：
/root/dsh-bin/adb-shell "am start -n com.dsh.client/com.deepseekharness.app.QuickChatSheetActivity"

# 2. Android 设备终端（Termux / Shizuku / ADB）：
am start -n com.dsh.client/com.deepseekharness.app.QuickChatSheetActivity
```
> *注：输出 `Warning: Activity not started, its current task has been brought to the front` 属于正常现象，表明系统成功将后台常驻保活的弹层直接置顶唤醒（Brought to front）。*

### B4. 点通知直达抽屉弹层

原先点通知只能全屏切到 App 首页或全屏 WebView。现已全链路统一收拢至抽屉弹层：

- `HarnessService.java`（常驻保活 1001）/ `TaskNotifier.java`（任务完成 2002）/ `HttpShellService.java`（实时运行 2003）/ `ConfirmReceiver.java`（任务终止 2004）的所有通知点击主体均统一指向 `QuickChatSheetActivity`；
- 配置 `FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP`，复用后台常驻实例秒开；
- 弹层顶部配备 `[⚙ 容器控制台]` 与 `[◫ 全屏聊天]` 按钮，随时可一键转入全屏 App。

| 通知类型 | 触发场景 | 点击行为 |
| :--- | :--- | :--- |
| **常驻保活通知** (1001) | DSHA 后台服务运行中 | 点击通知主体直接从屏幕底部顺滑唤起抽屉弹层 |
| **任务实时运行通知** (2003) | 智能体执行多步骤自动化任务中 | 点击通知主体直达抽屉弹层（实时查阅步骤与日志），带 `[🛑 停止任务]` 动作按钮 |
| **任务完成通知** (2002) | 智能体完成单轮/多轮任务 | 点击通知主体或点击 `[💬 继续对话]` 按钮，直接唤起抽屉弹层 |
| **任务终止通知** (2004) | 用户紧急制动或外部异常中断 | 点击通知主体或点击 `[💬 重新开始]` 按钮，直接唤起抽屉弹层 |

### B5. `/app/ask` 双通道

`showAskNotification()` 让助手提问同时以高优先级通知（选项做快捷按钮 + RemoteInput 自由输入）和前台弹窗两路呈现。两路用 `askEpoch`(AtomicLong) + `askResolved`(AtomicBoolean) + `CountDownLatch` 做互斥，`resolveAsk(answer, epoch)` 只认当轮 epoch，先到先得，另一路自动收起（`dismissAskDialog()` / `cancelAskNotification()`）。没有 epoch 校验的话，上一轮的过期通知按钮会污染这一轮的答案。

这里是照着 `AGENTS.md` 那两条坑位写的，改动时别退回去：`askBusy` 用 `AtomicBoolean.compareAndSet`（不是「检查后置位」）、弹窗 `setCancelable(false)` 且**只认按钮回调、不在 `OnDismiss`/`OnCancel` 里 `countDown`**（旋屏或 Activity 被回收造成的 dismiss 会给 agent 送回一句假的「用户关掉了提问框」）、`finally` 里的清理顺序是先 latch/弹窗/通知、最后才放开 `askBusy`。

### B6. 插件注册补齐

`HarnessController.java` 里 `dsha-task-notifier` 之前既不在 marker 清理列表、也不在 `missingBuiltinEntities()` 的目录数组里，等于资产版本自愈永远轮不到它，改了插件源码用户拿不到。两处都补上，`BUILTIN_ASSET_VERSION` 20 → 22 强制重注入。

另外 `AGENTS.md` 里 `fs-write-patch.sh` 一行的描述同步改成三个包（原文只写了 `dsh-fs-local`）。

`task-notifier/lib/index.js` 另加了 `TOOL_LABELS` 表和 `formatToolDetail()`，把 `tool/call` 事件翻译成人能读的一行（从 `command`/`path`/`query` 等 `ARG_KEYS` 里挑第一个有值的显示），否则通知栏只能看到工具名。

### B7. 独立结果通知渠道与先通知后 Toast 逻辑改造

**动机**：
1. 之前的完成通知、报错通知和紧急终止通知全部混在 `dsh_agent_channel` 这一通道中。由于工具调用（`tool/call`）每一步都实时更新运行中通知，若同属于一个通道会导致手机高频弹横幅与震动打扰；
2. 原先 `HttpShellService.java` 中包含前台防打扰逻辑：`if (TaskNotifier.appInForeground) { Toast... return "FOREGROUND_SKIP"; }`。这导致当任务报错（如 fetch failed、token 超限）或正常完成时，只要用户正开着 App 前台，通知就会被直接拦截只弹 Toast，通知栏被完全清空且丢失了带有 `RemoteInput` 原生输入框的交互卡片；
3. 任务在 Web 端点击停止或外部中断（`reason === 'aborted'`）时，插件原先简单一刀切 `return`，导致非通知栏触发的中断没有任何通知提示。

**改动实现**：
- **新增独立通知渠道**：在 `Constants.java` 中新增 `CHANNEL_TASK_RESULT = "dsh_task_result_channel"`（“任务结果与交互”，高优先级 `IMPORTANCE_HIGH`），将生命周期完成通知（2002）、报错/异常通知（2002）与紧急终止通知（2004）全部迁入该独立通道；`dsh_agent_channel` 仅保留纯运行中状态（2003，带停止按钮）；
- **先走通知，再走 Toast**：`HttpShellService.appNotify` 调整逻辑时序，无论 App 在前台还是后台，第一时间向系统通知栏写入直达抽屉弹层的高优先级结果卡片，随后在前台额外弹出友好 Toast 提示，彻底移除 `return "FOREGROUND_SKIP"` 截断；`ConfirmReceiver` 同步优化为先发通知再弹 Toast；
- **全链路中断事件捕获**：`dsh-task-notifier` 插件完善 `turn/end` 逻辑，识别通知栏制动与外部中断，对 Web 端停止和模型异常中断主动补发 `DSHA · 任务已中断` 结果卡片。

---

## C. 模拟点击 修复元素定位逻辑与静默截屏权限

### C1. 两阶段候选池消歧（`device-shell-guide/lib/index.js`）

**动机**：在淘宝、京东搜索时，AI 高频把输入框里的「语音搜索」话筒当成右上角的「搜索」按钮点掉。根因是底层按子串模糊匹配，而话筒节点在 UI 树里恰好排在真按钮前面，扫到第一个含「搜索」的就截断触发。

提示词里加入硬约束：先遍历 dump **全部**元素建候选池（禁止遇到第一个相似项就点），再综合消歧——完全匹配优先于包含匹配、独立按钮优先于内嵌图标、结合屏幕区域（提交按钮通常在右上/最右），定出唯一目标后用该节点的精确中心坐标 `/app/ui/tap?x=&y=` 点击，绕开底层模糊匹配抢跑。同时禁止盲点未在 dump 中确认的估算坐标。

同一处还补了三条：三通道的定位说明（`/app/*` 是基石与保底、ADB 是高级通道）、跨页面 DeepLink 直达与降级策略（`/app/open?url=` 只吃标准 scheme，第三方私有 scheme 如 `openapp.jdmobile://`、`tbopen://` 必须走 `am start`；ADB 不可用时回退 `/app/launch` + `/app/ui/*`）、以及租约标准工作流（先查租约再决定是否 `/app/ask`，任务结束**不删租约**让它自然过期）。

### C2. 无障碍原生静默截屏（`accessibility_service_config.xml`）

一行：`android:canTakeScreenshot="true"`。缺这个声明，系统降级走 `MediaProjection` 投屏接口，每截一张图强制弹一次录屏授权框，视觉识别根本没法连续用。补上之后走无障碍底层显存通道，静默出图。

### C3. 附件补丁的自检项（`selftest.py`）

配合下面 D 组：加 `ATTACHMENT_PKG_CANDIDATES` 与「附件发布补丁」检查项，找 `DSHA_L2S_FIX_ATTACHMENT` 标记，未打时按 `NEXT_STEP` 的约定给出「到启动页点一次重启」的处置建议（那份表要求每条 FAIL 都得告诉用户下一步做什么）。

---

## D. 图片附件软链接修复（`ATTACHMENT_WRITE_FAILED`）

**现象**：WebUI 输入框里加图片发送，弹红字 `图片发送失败（ATTACHMENT_WRITE_FAILED）`，多模态模型收不到图。

**根因**：跟 `AGENTS.md` 已知坑位里那条硬链接问题是**同一个根因的第三个漏网包**。`/root/.dsh/attachments` 软链到 `/sdcard/Documents/dshdata/attachments`，而 Android 外部存储不支持 POSIX 硬链接，`link()` 直接返回 `EINVAL`/`EPERM`。上游 `fs-write-patch.sh` 已经修过 `dsh-fs-local`（段①）和 `dsh-session-persistence-jsonl`（段②）两个包，`dsh-attachment-local` 是第三个也用 `link(temp, target)` 做原子发布的包，一直没被覆盖。

**修法**：`fs-write-patch.sh` 新增第③段，把 `await link(temporary, target)` 换成 `await rename(temporary, target)`。几处细节都是有意的：

- **`EXDEV` 回退**。`rename` 不能跨文件系统，捕获到 `EXDEV` 就 `copyFile` + `unlink`。原来的 `link` 版本没有这个分支，因为它失败的方式不同。
- **删掉末尾的 `await unlink(temporary)`**。`rename` 已经把临时文件消耗掉了，留着必报 `ENOENT`。
- **匹配两级降级**：先整段字面量匹配，失配则用 `re.S` 正则再试一次（`PATCHED_REGEX`），都不中才 `PATTERN_MISS` + `exit 3`。dsh 版本一升，字面量就可能对不上。
- **改完校验，不过就回滚**：`node --check`（ESM 要先复制成 `.mjs`，否则按 CJS 解析必报错）失败就从 `.dsha-bak` 恢复，输出 `ATTACHMENT_PATCH_ROLLBACK`。
- **幂等**：认 `DSHA_L2S_FIX_ATTACHMENT` 标记，已打过直接 `ATTACHMENT_PATCH_ALREADY`。

排查全过程另有一份记录：`bug/图片发送ATTACHMENT_WRITE_FAILED修复记录.md`。

### D 组踩过的坑：新段落不能加在段②后面

第一版把第③段追加到脚本末尾（段②之后），结果**补丁只在全新安装上生效，老用户永远拿不到**。

原因是段②的每个分支结尾都是 `exit 0`（在上游原版里段②就是脚本末尾，那时无害）。老用户升级时①②早就打过了，段②走 `SESSION_PATCH_ALREADY` 就 `exit 0`，第③段一行都不会执行。实测三个场景确认过：

| 场景 | 结果 |
|---|---|
| ①②已打过、③未打（老用户升级） | 段③完全不执行，`grep DSHA_L2S_FIX_ATTACHMENT` 为 0 |
| 全新安装 | 段③正常执行 |

**修法**：把第③段挪到段②**之前**，并改成跟段①同构的 `if/elif/else`（不带 `exit`），任何分支都不中断后续。这样三段的执行互不依赖。已复测：老用户场景输出 `FS_PATCH_ALREADY / PATCHED / ATTACHMENT_PATCH_OK / SESSION_PATCH_ALREADY`，补丁正常打上；重复跑输出三个 `ALREADY`（幂等）；段③ `PATTERN_MISS` 时段②照常继续。

> 段②那四处 `exit 0` 是上游原有代码，本 fork 没有改动它 —— 只是把新段落放在了它前面绕开。要动上游代码就得多担一份 review 风险，不值得。

**遗留小问题**（未修，不影响功能）：`HarnessController.noteFsWritePatchResult()` 只匹配输出里的 `"PATCHED"`，而段③成功时输出 `ATTACHMENT_PATCH_OK`，所以活动日志记不到这一段，排查时看不见。

---

## E. Fork 侧 CI（不建议提给上游）

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
- **本地未跑过构建验证**。`tools/pure-logic-test.sh` 和 `:app:assembleDebug` 都没在这个容器里跑过（缺 SDK 34 / NDK 26），编译正确性只由 fork CI 的 `assembleDebug` 背书。唯一在本地实跑验证过的是 D 组的 `fs-write-patch.sh`（`bash -n` 语法检查 + 三个场景的执行路径模拟）。
- **`noteFsWritePatchResult` 记不到段③**（见 D 组末尾），活动日志缺一条，不影响功能。

## rebase 现状

分叉基点 `41539fa` **就是上游 `main` 的当前 HEAD**，所以此刻是零冲突、可直接开 PR 的状态。上游那批大改动（提示词压缩 `cdac5de`、停止链路重做、`/root` 整体数据保护 `8759092`）全部落在基点之前，已经包含在内，不构成冲突。

上游继续往前走之后再 rebase，最可能撞车的是这三个文件：`runtime-manifest.json`（每次改 assets 都会重算哈希，必冲突且只能重新生成而非手工合并）、`HttpShellService.java`（B 组在里面加了 375 行）、`device-shell-guide/lib/index.js`（C1 改的是提示词字符串数组，上游也常动它）。
