# DSHA 状态栏灵动胶囊与双轨通知系统全景规范 (通知修改README)

本文档为 DSHA 状态栏灵动胶囊与通知系统的**最终权威规范**，从原有安卓传统通知单通道升级为 3 通道（原生普通通知 + Android 16 AOSP 胶囊 + 小米澎湃 OS 灵动岛，通过系统设置开关及参数字典自动识别；其他定制厂商系统未做深度私有测试，可启用 AOSP 胶囊通道使用）。

已剔除所有中间探索阶段的废弃逻辑（如自绘 RemoteViews、通栏大黑框 textButton、低优先级静音降级、30 秒死板节流等），完整记录 **Google AOSP 16 官方原生实时活动 (Live Updates)**、**小米澎湃 OS (HyperOS / HyperIsland 灵动岛)** 与 **Android 原生标准通知** 的最终落地架构、全套工具人话分类标准与全版本兼容方案。

---

## 一、 架构总览：同一载体，三轨自适应

DSHA 采用“单一系统通知对象，三轨数据并行注入”的设计，由 `HttpShellService.java` 中的 `attachFocusCapsule` 统一调度：

```text
                          【DSHA HttpShellService 核心通知调度器】
                                             │
      ┌──────────────────────────────────────┼──────────────────────────────────────┐
      ▼                                      ▼                                      ▼
【轨道 1：小米澎湃 OS (HyperOS)】     【轨道 2：Google AOSP 16 (Live Updates)】     【轨道 3：Android 原生通知栏 (降级)】
- 协议: miui.focus.param (JSON)       - 协议: android.requestPromotedOngoing        - 协议: 标准 NotificationCompat
- 图标: miui.focus.pics (Bundle)      - 属性: android.shortCriticalText             - 渠道: IMPORTANCE_DEFAULT / HIGH
- 动作: miui.focus.actions (Bundle)   - 权限: POST_PROMOTED_NOTIFICATIONS          - 交互: BigTextStyle + RemoteInput
- 表现: 挖孔双耳胶囊 + 纯黑大卡片      - 表现: 状态栏顶置芯片 + 锁屏实时卡片          - 表现: 标准通知栏卡片 (完善通知优先级，保证弹出)
```

---

## 二、 技术规范：本项目未升级 SDK 36 但兼容 Android 16 AOSP 胶囊

### 1. 为什么本方案选择“不强升 SDK 36”？
* **官方编译期限制**：在 Java 代码中若直接调用 `b.setRequestPromotedOngoing(true)`，Javac 编译器在编译期必须依赖 Android 16（API 36）的 `android.jar`。而当前项目采用 `compileSdk 34` + `AGP 8.2.2`；
* **硬升 SDK 36 的巨大风险**：强行将 `compileSdk` / `targetSdk` 升至 36 会逼迫项目的 Gradle、Android Gradle Plugin (AGP) 以及 JDK 整体大版本升级，极易引发老依赖库断裂与构建环境不兼容；
* **底层机制本质**：翻看 Android 16 Framework 源码可知，Google 官方 `setRequestPromotedOngoing(boolean)` 的底层实现，**本质上仅仅是向通知的 `extras` Bundle 字典中写入了 `mExtras.putBoolean("android.requestPromotedOngoing", value)`**。

### 2. 不升级 SDK 依然激活 AOSP 16 胶囊的“三保险方案”
1. **底层数据直注（绕过编译限制，系统直读）**：
   ```java
   extras.putBoolean("android.requestPromotedOngoing", true);
   extras.putString("android.shortCriticalText", capsuleText != null ? capsuleText : "正在执行");
   ```
   Android 16 系统的 `NotificationManagerService` 在接收通知时直接读取该 Key，**100% 认定为 Promoted Ongoing 状态栏胶囊并予以提拔**。
2. **运行时双重保险（安全反射调用）**：
   ```java
   try {
       java.lang.reflect.Method m = b.getClass().getMethod("setRequestPromotedOngoing", boolean.class);
       m.invoke(b, true);
   } catch (Throwable ignored) {}
   ```
   若运行环境存在对应方法则反射调用；在老系统上自动静默忽略，绝不抛出 `NoSuchMethodError`。
3. **特权权限清单声明，调用 AOSP 胶囊**：
   ```xml
   <uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />
   ```
   不受 `compileSdk` 限制，Android 16 设备安装时直接赋予提拔胶囊特权。

---

## 三、 Android 8.0+ 全版本兼容性保障

本方案**理论向下兼容至 Android 8.0**：

```text
                        【Android 8.0 ~ 15 老手机接收通知】
                                       │
         ┌─────────────────────────────┴─────────────────────────────┐
         ▼                                                           ▼
【读取到 `miui.focus.param` 与 AOSP 16 Key】                  【读取标准 Android 通知字段】
- 系统判定：“这些是未知的扩展字典”                            - 读取 SmallIcon、Title、Text、Action
- 自动忽略，不执行灵动岛提拔                                  - 原生画出标准的下拉栏通知与大卡片
- 零报错、零崩溃、零异常！                                    - 100% 正常弹出、正常点击、正常交互！
```

### 全版本表现对照表

| 系统版本 | 手机形态示例 | 真实视觉与交互表现 | 兼容性评级 |
| :--- | :--- | :--- | :---: |
| **Android 16+ (澎湃OS)** | 小米 14/15 等 (HyperOS 2/3) | 状态栏挖孔双耳大肥鱼胶囊 + 日程/来电级纯黑毛玻璃大卡片（走 `miui.focus.param` 私有协议） | ** (适配)** |
| **Android 16+ (原生AOSP)** | Google Pixel / AOSP 原生 | 状态栏顶置胶囊芯片 (Chip) + 锁屏实时活动卡片（走 `POST_PROMOTED_NOTIFICATIONS` 官方标准） |  ** (OS厂商似乎未完全开放原生胶囊，有部分通知为融合状态，不知道为啥)** |
| **Android 8.0 ~ 15** | 各品牌 Android 手机 | 标准通知栏卡片 + 顶部横幅，支持 `RemoteInput` 键盘快捷回复，未知胶囊扩展 Key 自动平滑忽略 | **标准基石态 (原生兼容)** |

> ★ *注：其他定制厂商未做深度私有测试。澎湃 OS 对灵动岛（焦点通知）有严格的模板和按钮位置规范，不一定支持其他厂商私有协议；其他厂商设备可尝试启用 AOSP 通道（实时动态通知），普通通知表现为系统标准悬浮通知。*

---

## 四、 DeepSeek Harness (DSH) 全套 17 种工具分类与人话映射规范

为了防止工具名称模糊匹配导致误报（例如 `read_image` 误判为读取文本、`todo_write` 误判为写文件、英文单词 `task` 包含 `ask` 导致误判为提问），我们制定了严格的**优先级判定矩阵**：

| 工具名称 (Tool Name) | 工具分类 (TOOL_LABELS) | 胶囊右耳短词 (4~6字) | 二级卡片前缀精简展示 |
| :--- | :--- | :---: | :--- |
| **`ask_user_question`** | 💬 助手提问 / 决策 | **`等待回答`** | 提问等待态，点击直达抽屉 |
| **`read_image`** | ⚙ 正在分析画面 | **`分析画面中`** | `看图: screenshot.png` |
| **`todo_write`** | ⚙ 正在规划任务清单 | **`规划清单中`** | `清单: 5 项待办` |
| **`create_goal` / `update_goal` / `get_goal`** | ⚙ 正在规划任务清单 | **`规划清单中`** | `目标: 完成重构` |
| **`web_search` / `fetch` / `browse`** | ⚙ 正在联网查资料 | **`联网搜索中`** | `联网: Android 16 updates` |
| **`write` / `edit`** | ⚙ 正在修改文件 | **`修改文件中`** | `修改: src/index.ts` |
| **`read`** | ⚙ 正在读取文件 | **`读取文件中`** | `读取: package.json` |
| **`glob` / `grep`** | ⚙ 正在搜索文件 | **`搜索文件中`** | `搜索: attachFocusCapsule` |
| **`subagent` / `subagent_fork` / `workflow` / `ralph`** | ⚙ 正在调度子任务 | **`调度任务中`** | `子任务: review PR` |
| **`skill`** | ⚙ 正在加载技能 | **`执行命令中`** | `技能: android-dev` |
| **`ui/tap` / `ui/input` / `ui/swipe` / `ui/dump` / `launch`** | ⚙ 正在执行屏幕操作 | **`操作屏幕中`** | `屏幕: tap(540, 1200)` |
| **`notify` / `toast` / `vibrate` / `sensor` / `torch`** | ⚙ 正在调用手机功能 | **`执行命令中`** | `系统: toast(Done)` |
| **`bash` (git, npm, curl, python 等)** | ⚙ 正在执行命令 | **`执行命令中`** | **完整保留全部长命令与参数原文** |

---

## 五、 全场景 3 种通知形态在三大运行轨道中的完整视觉呈现与 ASCII 图解

---

### 1. 【任务执行中】（正在执行命令 / 读写文件 / 搜索）

* **业务特征**：高频动态更新，静默低打扰，提供一键紧急制动。
* **通知 ID**：`Constants.NOTIF_TASK = 2002`（`ongoing = true`）
* **渠道等级**：`Constants.CHANNEL_AGENT_RUNNING`（`IMPORTANCE_DEFAULT` 中级，开启灵动岛时通过 `enableFloat = false` 保持静默小药丸，关闭灵动岛时在通知栏正常显示，不进静音折叠栏）

---

#### 📱 轨道 ①：小米澎湃 OS (HyperOS 焦点模式 · 模板 ① 日程规范)
* **状态栏收起态（双耳小胶囊）**：
  ```text
         ┌────── 左耳 ──────┐   (摄像头挖孔)   ┌────── 右耳 ──────┐
         │  [ 🐬 ]  大肥鱼  │       ⚫⚫       │    执行命令中    │
         └──────────────────┘                  └──────────────────┘
  ```
* **二级展开纯黑大卡片（带 1dp 半透明细横线）**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │  正在执行                                       [ 🐬 ] │  <-- 顶部：大标题 + 右上角彩色大肥鱼
  ├────────────────────────────────────────────────────────┤  <-- 1dp 极细半透明分割线
  │  实时状态                                              │  <-- 蓝色状态小标 (hintInfo.content)
  │  git status -s --ignored            ( ⏰ 停止任务 )   │  <-- 底部：完整命令原文 + [🛑 停止任务] 药丸按钮
  └────────────────────────────────────────────────────────┘
  ```

---

#### 📱 轨道 ②：Google AOSP 16 (Live Updates 官方实时活动)
* **状态栏常驻胶囊芯片 (Pill Chip · 顶置显示)**：
  ```text
  ┌───────────────────────────────┐
  │   [ 🐬 ]     执行命令中       │  <-- 状态栏顶部：白色小蓝鲸图标 + 4~6 字实时短词
  └───────────────────────────────┘
  ```
* **锁屏置顶实时卡片 & 下拉通知栏置顶卡片**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │ (🐬) DSHA · 大肥鱼                                实时 │  <-- 系统置顶标签
  │ 正在执行                                               │  <-- 主标题
  │ git status -s --ignored                                │  <-- 完整命令/步骤说明
  │                                                        │
  │ [ 🛑 停止任务 ]                                        │  <-- AOSP 官方原生 Action 按钮
  └────────────────────────────────────────────────────────┘
  ```

---

#### 📱 轨道 ③：Android 原生通知栏 (8.0 ~ 15 降级标准通知)
* **状态栏小图标**：状态栏左侧显示白色透明小蓝鲸图标 `[ 🐬 ]`。
* **下拉通知栏常驻卡片**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │ (🐬) DSHA · 大肥鱼                                正在运行 │
  │ 正在执行                                               │
  │ git status -s --ignored                                │
  │                                                        │
  │ [ 🛑 停止任务 ]                                        │  <-- 点击发送广播一键紧急制动
  └────────────────────────────────────────────────────────┘
  ```
* **行为特性**：`ongoing = true`（不可被用户手势滑动清除），AI 步骤更新时在通知栏内**原地平滑刷新文字，绝不闪烁重建**。

---

### 2. 【任务完成 / 报错 / 终止】（终态交付通知）

* **业务特征**：任务交付或异常告知，带 120 秒倒计时自动消除。
* **通知 ID**：`Constants.NOTIF_TASK = 2002`（原子原地覆写）
* **渠道等级**：`Constants.CHANNEL_TASK_RESULT`（`IMPORTANCE_HIGH`）
* **超时控制**：`.setTimeoutAfter(120_000L)`（展示 2 分钟后系统自动平滑淡出，无需手动清理）

---

#### 📱 轨道 ①：小米澎湃 OS (HyperOS 焦点模式 · 模板 ① 日程规范)
* **状态栏收起态（双耳小胶囊）**：
  ```text
         ┌────── 左耳 ──────┐   (摄像头挖孔)   ┌────── 右耳 ──────┐
         │  [ 🐬 ]  大肥鱼  │       ⚫⚫       │    任务已完成    │
         └──────────────────┘                  └──────────────────┘
  ```
* **二级展开纯黑大卡片**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │  任务已完成                                     [ 🐬 ] │  <-- 顶部：大标题 (如 ❌ 模型请求失败: 503)
  ├────────────────────────────────────────────────────────┤  <-- 1dp 极细半透明分割线
  │  任务完成                                              │  <-- 蓝色状态小标
  │  全部构建与端到端测试均已通过       ( 💬 返回对话 )   │  <-- 底部：结果详情 + [💬 返回对话] 药丸按钮
  └────────────────────────────────────────────────────────┘
  ```

---

#### 📱 轨道 ②：Google AOSP 16 (Live Updates 官方实时活动)
* **状态栏胶囊芯片**：`[ 🐬 ] 任务已完成` 或 `[ 🐬 ] 请求异常`
* **锁屏置顶实时卡片 & 下拉通知栏卡片**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │ (🐬) DSHA · 大肥鱼                                刚刚 │
  │ 任务已完成                                             │
  │ 全部构建与端到端测试均已通过                           │
  │                                                        │
  │ [ 💬 返回对话 ]                                        │  <-- 点击直接从屏幕底部滑出聊天抽屉
  └────────────────────────────────────────────────────────┘
  ```
  > *注：系统在 120 秒（2 分钟）后自动将该卡片从状态栏及锁屏上平滑收起消除。*

---

#### 📱 轨道 ③：Android 原生通知栏 (8.0 ~ 15 降级标准通知)
* **下拉通知栏标准交互大卡片 (支持键盘打字回复)**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │ (🐬) DSHA · 大肥鱼                                刚刚 │
  │ 任务已完成                                             │
  │ 全部构建与端到端测试均已通过                           │
  │                                                        │
  │ [ 💬 返回对话 ]      [ 💬 快捷输入 ]                    │  <-- 带 RemoteInput 原生输入框
  └────────────────────────────────────────────────────────┘
  ```
* **双交互入口**：
  * 点击 `[ 💬 返回对话 ]` 或卡片任意处：直接呼出 `QuickChatSheetActivity` 快速聊天抽屉；
  * 点击 `[ 💬 快捷输入 ]`：直接在下拉通知栏内就地弹出软键盘打字，回车自动通过 `ConfirmReceiver` 注入会话开启新一轮对话。

---

### 3. 【安全确认 / 危险授权 / 提问】（决策型交互）

* **业务特征**：强提醒弹窗，高危操作双选决策，点击任意文字区域直达快速聊天抽屉。
* **通知 ID**：`3003` (Confirm) / `3007` (Ask)
* **渠道等级**：`CONFIRM_CHANNEL`（`IMPORTANCE_HIGH`，`enableFloat = true`，`islandFirstFloat = true` 强提醒弹出）

---

#### 📱 轨道 ①：小米澎湃 OS (HyperOS 焦点模式 · 模板 ② 来电决策规范)
* **状态栏收起态（强提醒小胶囊）**：
  ```text
         ┌────── 左耳 ──────┐   (摄像头挖孔)   ┌────── 右耳 ──────┐
         │  [ 🐬 ]  大肥鱼  │       ⚫⚫       │     命令确认     │
         └──────────────────┘                  └──────────────────┘
  ```
* **二级展开纯黑大卡片（彻底消灭分割横线 · 左右居中对齐）**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │  ⚠️ 请求特权执行此命令                                 │  <-- 顶部：醒目提要标题 (粗体)
  │                                           ( ✅ )  ( ❌ ) │  <-- 右侧：居中红绿双圆钮 (左绿勾允许，右红叉拒绝)
  │  rm -f /data/local/tmp/test_build                      │  <-- 底部：完整命令原文 (100% 宽幅无遮挡)
  └────────────────────────────────────────────────────────┘
  ```
  * **实心红绿双圆钮规范**：
    * **左边按钮 (`action_1`)**：实心绿色大圆底（`#34C759`）+ 纯白勾号 **`( ✅ )`**（允许授权）；
    * **右边按钮 (`action_2`)**：实心红色大圆底（`#FF3B30`）+ 纯白叉号 **`( ❌ )`**（拒绝）；
    * 符号占圆底 **70% 黄金饱满面积**，在卡片右侧**整体垂直居中**；
  * **抽屉直达**：点击卡片任意文字区域，瞬间滑出 `QuickChatSheetActivity` 快速聊天抽屉。

---

#### 📱 轨道 ②：Google AOSP 16 (Live Updates 官方实时活动)
* **状态栏强提醒胶囊**：`[ 🐬 ] 命令确认` / `[ 🐬 ] 危险授权`
* **锁屏与通知栏决策卡片**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │ (🐬) DSHA · 大肥鱼                                紧急 │
  │ ⚠️ 请求特权执行此命令                                   │
  │ rm -f /data/local/tmp/test_build                       │
  │                                                        │
  │ [ 允许 ]          [ 拒绝 ]                             │  <-- AOSP 原生双 Action 按钮
  └────────────────────────────────────────────────────────┘
  ```

---

#### 📱 轨道 ③：Android 原生通知栏 (8.0 ~ 15 降级标准通知)
* **屏幕顶部浮动横幅 (Heads-up Notification · 强提醒)**：
  ```text
  ┌────────────────────────────────────────────────────────┐
  │ (🐬) DSHA · 大肥鱼                                强提醒│
  │ ⚠️ 请求特权执行此命令                                   │
  │ rm -f /data/local/tmp/test_build                       │
  │                                                        │
  │ [ 允许 ]          [ 拒绝 ]                             │  <-- 原生并排大操作按钮
  └────────────────────────────────────────────────────────┘
  ```
* **交互逻辑**：
  * 点击 `[ 允许 ]`：发送允许广播，放行命令执行；
  * 点击 `[ 拒绝 ]`：发送拒绝广播，拦截高危命令；
  * 点击卡片任意文字空白处：直接滑出快速聊天抽屉查看详细上下文。

---

## 六、 核心代码架构与设计模式

```java
// 统一入口：根据是否传入 secondaryAction 自动判定走 模板① 还是 模板②
public static void attachFocusCapsule(Context ctx, NotificationCompat.Builder b, 
    String title, String detail, String statusLabel, String actionTitle, String capsuleText, 
    PendingIntent primaryActionPi, String secondaryActionTitle, PendingIntent secondaryActionPi, boolean enableFloat) {
    
    boolean hasDualActions = (secondaryActionPi != null && secondaryActionTitle != null);

    // 1. Google AOSP 16 标准轨道注入
    android.os.Bundle extras = b.getExtras();
    if (extras != null) {
        extras.putBoolean("android.requestPromotedOngoing", true);
        extras.putString("android.shortCriticalText", capsuleText != null ? capsuleText : "正在执行");
    }
    try {
        java.lang.reflect.Method m = b.getClass().getMethod("setRequestPromotedOngoing", boolean.class);
        m.invoke(b, true);
    } catch (Throwable ignored) {}

    // 2. 小米 HyperOS 焦点协议轨道注入
    try {
        org.json.JSONObject paramV2 = new org.json.JSONObject();
        paramV2.put("protocol", 1);
        paramV2.put("business", "schedule_reminder");
        paramV2.put("enableFloat", enableFloat);
        paramV2.put("islandFirstFloat", enableFloat);
        paramV2.put("ticker", "大肥鱼 " + (capsuleText != null ? capsuleText : "正在执行"));

        // 胶囊左右耳配置
        org.json.JSONObject island = new org.json.JSONObject();
        island.put("highlightColor", "#58A6FF");
        ... (左耳大肥鱼图文 + 右耳 4~6 字短词) ...
        paramV2.put("param_island", island);

        org.json.JSONObject baseInfo = new org.json.JSONObject();
        baseInfo.put("type", 2);
        baseInfo.put("title", title != null ? title : "DSHA");

        if (hasDualActions) {
            // 模板 ②（来电决策双动作规范）：彻底移除 hintInfo（消灭横线）
            baseInfo.put("content", detail != null ? detail : "");
            paramV2.put("baseInfo", baseInfo);
            paramV2.put("actions", [绿勾 a1, 红叉 a2]); // 注入 70% 饱满实心红绿双圆钮
        } else {
            // 模板 ①（日程单按钮规范）：画出细横线，挂载单药丸按钮
            paramV2.put("baseInfo", baseInfo);
            hintInfo.put("title", detail != null ? detail : "");
            hintInfo.put("actionInfo", 单药丸 a1);
            paramV2.put("hintInfo", hintInfo);
            paramV2.put("picInfo", 彩色大肥鱼大图标);
        }

        // 打包实体字典 (防止 SystemUI 崩溃)
        extras.putString("miui.focus.param", root.toString());
        extras.putBundle("miui.focus.pics", picsBundle);
        extras.putBundle("miui.focus.actions", actionsBundle);
    } catch (Throwable ignored) {}
}
```

---

## 七、 关键避坑守则（构建与运行时防线）

1. **绝对禁止直接传递裸 `PendingIntent` 到 `miui.focus.actions`**：必须通过 `new Notification.Action.Builder(icon, title, pi).build()` 封装为 Action 实体，否则状态栏 SystemUI 强转异常导致手机软重启。
2. **实心双圆钮必须传递带底色的位图 Icon**：使用 `createRoundedBackgroundIcon` 在 Bitmap 上调用 `Canvas.drawCircle` 填充实心颜色，符号占 70% 黄金直径；若直接传透明矢量，系统会渲染为青色空心线圈 (`OO`)。
3. **禁止在悬浮条淡出 (`appOverlay?kind=done`) 时取消通知**：悬浮条与状态栏通知管理器彻底解耦，防止完成通知刚发出就被异步 cancel 误杀。
4. **内置插件资产版本强控 (`BUILTIN_ASSET_VERSION`)**：修改 `assets/` 插件后必须将 `HarnessController.java` 中的版本号递增（当前 `36`），确保 Proot 容器启动时自动删除旧 marker 并覆盖刷新实体 JS 文件。
5. **离线清单同步更新**：修改 assets 文件后必须运行 `python3 tools/gen-runtime-manifest.py` 刷新 `runtime-manifest.json`，确保 CI `Fast checks` 100% 通过。
6. **消除 `ask`/`task` 英文子串模糊匹配**：胶囊提取算法严禁使用裸词 `"ask"`，必须使用中文全词 `s.contains("等待回答")`，防止正常包含 `task` 的英语思考流或普通 Bash 命令被误判为提问通知。
