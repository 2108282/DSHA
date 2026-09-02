# DSHA 状态栏灵动胶囊与双轨通知系统改造备忘 (通知修改README)

本文档完整记录了 DSHA 在 **Android 16 官方原生实时活动 (AOSP Live Updates)** 与 **小米澎湃 OS (HyperOS / HyperIsland 灵动岛)** 双轨通知系统中的全部技术排查、底层协议适配、架构重构与视觉体验演进。

---

## 一、 双轨通知架构设计（AOSP 16 官方标准 + HyperOS 私有焦点协议）

为了保证在所有 Android 设备上均能获得最佳体验（开源通用性），DSHA 采用了**“同一个通知载体，双轨数据并行注入”**的架构：

```
                              【DSHA 统一通知发送】
                                       │
        ┌──────────────────────────────┴──────────────────────────────┐
        ▼                                                             ▼
【轨道 1：Google AOSP 16 官方标准】                  【轨道 2：小米 HyperOS 焦点协议】
- 权限: POST_PROMOTED_NOTIFICATIONS                  - 协议: miui.focus.param (JSON)
- 提拔: android.requestPromotedOngoing               - 图片: miui.focus.pics (Bundle)
- 胶囊文本: android.shortCriticalText                - 动作: miui.focus.actions (Bundle)
- 分类: CATEGORY_STATUS                              - 模板: protocol=1 / schedule_reminder
        │                                                             │
        ▼ (生效设备)                                                  ▼ (生效设备)
Google Pixel / 三星 / OPPO / vivo / 魅族等            小米 / Redmi (澎湃 OS 1/2/3、HyperIsland)
原生状态栏药丸 / 灵动胶囊 / 锁屏置顶                   双耳大肥鱼胶囊 + 1:1 日程级原生纯黑大卡片
```

---

## 二、 轨道 1：Android 16 (API 36) 官方原生实时活动 (Live Updates)

### 1. 官方规范落地与权限声明
在 `AndroidManifest.xml` 中声明 Android 16 原生实时活动专属权限：
```xml
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />
```
* **高版本特性激活**：Android 16 设备自动赋予其提拔为状态栏常驻胶囊的特权；
* **低版本平滑兼容**：Android 8.0 ~ 15 系统会自动忽略该未识别权限，零报错、零崩溃。

### 2. 核心参数与数据注入
在 `HttpShellService.java` 中为通知注入 AOSP 官方标准 Key：
* **`android.requestPromotedOngoing = true`**：请求系统将本通知提拔为状态栏胶囊与锁屏常驻胶囊（Pill Chip）；
* **`android.shortCriticalText`**：注入 4~6 字紧凑状态短词（如 `执行命令中`、`深度思考中`、`任务已完成`、`503请求异常`）；
* **`NotificationCompat.CATEGORY_STATUS` + `VISIBILITY_PUBLIC`**：符合系统级状态任务分类规范；
* **运行时安全反射**：通过 `nb.getClass().getMethod("setRequestPromotedOngoing", boolean.class)` 进行动态安全调用，避免在低版本系统上抛出 `NoSuchMethodError`，同时无需强升项目 AGP/Gradle 编译链。

### 3. 各大厂商 Android 16+ ROM 的自适应表现
所有主流厂商的新版系统均基于上述 AOSP 标准进行了上层视觉封装，自动识别并呈现专属灵动形态：
* **Google Pixel / 原生 AOSP**：状态栏与锁屏显示官方 Rich Ongoing Notification 药丸；
* **三星 (Samsung One UI)**：状态栏与锁屏常驻「Now Bar / 实时活动胶囊」；
* **OPPO / 一加 / realme (ColorOS)**：顶部挖孔旁自适应展示「流体云」胶囊；
* **vivo / iQOO (OriginOS)**：状态栏挖孔展示「原子岛」实时任务活动；
* **魅族 (Flyme)**：状态栏展示「灵动实况」胶囊。

---

## 三、 轨道 2：小米澎湃 OS (HyperOS) 焦点通知深度适配

针对小米澎湃 OS 及其灵动岛模块（HyperIsland），注入专有的 `miui.focus.param` JSON 协议与图片/动作实体包：

### 1. 双耳小胶囊（1 级收起态）
* **左耳（小蓝鲸图文）**：
  * 使用 Python 漫水算法对原始图片进行边缘抠图，生成 **100% 纯透明底 PNG (`ic_whale_logo.png`)**；
  * `imageTextInfoLeft` 配置 `pic: "miui.focus.pic_big_island"` 与 `title: "大肥鱼"`，彻底解决左半边空白塌陷的问题；
* **右耳（4~6 字自适应短词）**：
  * 内置 `compactCapsuleText` 算法，动态截取紧凑短词，左右对称且绝不超出屏幕撞击电量/信号图标。

### 2. 原生纯黑大卡片（2 级展开态）
* **协议标准复刻**：采用与系统日程一致的 `protocol: 1` + `schedule_reminder` 模板；
* **无白边一体化**：彻底移除 `RemoteViews` 自绘布局（`setCustomContentView`），由系统依据 JSON 原生画出纯黑毛玻璃卡片（顶部标题 + 右上角大肥鱼 Logo + 1dp 半透明横线 + 底部操作栏），彻底根治浅色模式下的白色大外框与纵向压扁裁切问题。

### 3. 药丸按钮与动作绑定规范
* **单按钮规范**：在 `hintInfo.actionInfo` 声明 `action_1`；
* **动作实体打包**：在 `miui.focus.actions` 中存入合规的 `Notification.Action` 对象（挂载白色小闹钟矢量图标 `ic_alarm_white`）；
* **防卡死机制**：使用 `Icon.createWithResource` 传递图标，彻底消除因直接传递原始 `PendingIntent` 或缺少图标资源引发的 `SystemUI` 空指针异常与系统软重启。

---

## 四、 全生命周期交互与体验细节

### 1. 彻底静默一级小药丸（执行中绝不弹大卡片）
* 移除 `showRunningNotification` 中导致通知被反复销毁重建的 `cancel()` 代码，改用纯原地平滑覆写；
* 在 JSON 与 `extras` 中显式设置 `"islandFirstFloat": false` 与 `enableFloat: false`，AI 执行工具命令时 100% 只在状态栏挖孔亮起安静的小药丸，绝不弹窗打扰。

### 2. 单通知 ID（`2002`）原地替换
* 全生命周期状态（执行中 / 完成 / 报错 / 终止）统一收拢至单一通知 ID（`Constants.NOTIF_TASK = 2002`）；
* 开启新任务时直接在物理层面原地覆写旧卡片，通知栏永远只保留当前最新的唯一状态。

### 3. 终态 120 秒超时自动消除
* 「任务完成」、「❌ 模型请求失败」、「⚠️ 任务已终止」等终态通知，统一配置 **`.setTimeoutAfter(120_000L)`**；
* 状态栏胶囊与通知在生成后展示 2 分钟供查看与交互，随后自动平滑收起消除，无需手动清理下拉栏。

### 4. 全场景模型异常捕获（方案 B 符号系）
* 纯净提取 `LlmFailure`（HTTP 状态码与简要报错原因），严格物理隔离思考流与终端命令退出码；
* 6 大分支规范：
  * `❌ 模型请求失败`（如 `Service Unavailable (503)` / `Invalid API Key (401)`）
  * `🔵 任务已中断`
  * `📏 达到单次最大长度`
  * `🛡️ 任务已挂起`
  * `⚡ 连接异常中断`
  * `任务完成`
* 悬浮条同步：遇到模型 503/400 报错时，屏幕顶部悬浮条同步上屏展示 3.5 秒后自然淡出；
* 标题精简：去除全部通知标题中的 `DSHA · ` 冗余前缀。

### 5. 消除后台 Activity 弹窗抢前台
* 增加 `TaskNotifier.appInForeground` 校验，当用户在使用其他 App 或在桌面时，严禁在后台 Activity 上弹出 `AlertDialog` 模态对话框，所有授权操作 100% 留在灵动胶囊和通知栏中完成，绝不切屏打扰。

---

## 五、 场景参数与全通道速查表

| 场景类别 | 胶囊左耳 (澎湃OS) | 胶囊右耳 / AOSP胶囊文本 | 二级展开顶部大标题 | 底部状态标签与描述 | 右下角药丸按钮 | 浮动级别 | 超时收起 |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| **任务执行中** | 🐬大肥鱼 | 动态短词 (如`执行命令中`) | 正在执行 | 实时状态 / 步骤描述 | `( ⏰ 停止任务 )` | 静默 (无弹窗) | 常驻 (直到结束) |
| **任务完成** | 🐬大肥鱼 | 任务已完成 | 任务已完成 | 任务完成 / 点击查看 | `( 💬 继续对话 )` | 静默 (无弹窗) | 120 秒 |
| **模型报错** | 🐬大肥鱼 | 请求异常 | ❌ 模型请求失败 | 任务状态 / 503等详情 | `( 💬 重新开始 )` | 静默 (无弹窗) | 120 秒 |
| **任务终止** | 🐬大肥鱼 | 已终止 | ⚠️ 任务已终止 | 任务状态 / 已按指令停止 | `( 💬 重新开始 )` | 静默 (无弹窗) | 120 秒 |
| **危险授权** | 🐬大肥鱼 | 危险授权 | ⚠️ 危险操作授权 | 权限请求 / 具体请求动作 | `( ⏰ 允许授权 )` | 强提醒 (弹大卡) | 用户操作/超时 |
| **命令确认** | 🐬大肥鱼 | 命令确认 | ⚠️ 危险命令确认 | 命令详情 / 具体命令原文 | `( ⏰ 允许 )` | 强提醒 (弹大卡) | 用户操作/超时 |
| **助手提问** | 🐬大肥鱼 | 等待回答 | 💬 助手提问 | 等待回答 / 问题原文 | `( ⏰ 选项一 )` | 强提醒 (弹大卡) | 用户操作/超时 |
