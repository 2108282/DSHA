# DSHA Active 快捷抽屉弹层设计与使用指南 (`QuickChatSheetActivity`)

`QuickChatSheetActivity` 是 DSHA 为 Android 设备深度定制的**全局快捷悬浮/贴底抽屉式对话面板**。
它借鉴了 **Box / Material Design 3** 交互规范，允许用户在不离开当前应用（如微信、淘宝、浏览器等）的前提下，随时随地通过通知栏或命令行顺滑唤起 DSHA 完整 AI 对话与工具交互流。

---

## 🌟 核心特性与技术架构

### 1. 物理边缘 100% 满屏贴合与零留白
- **彻底摆脱 Dialog 边距约束**：配置 `Theme.DeepseekHarness.SheetTransparent`（`windowIsTranslucent=true`、`windowIsFloating=false`、`statusBarColor=@android:color/transparent`）；
- 消除系统浮动边框，左右两侧 **100% 贴满手机物理屏幕边缘**，呈现宽屏沉浸感。

### 2. 15% 多档智能阶梯吸附停靠（35% ~ 95%）
- **手势动态计算**：内置基于 `screenHeight` 的 15% 步长阶梯锚点算法（**35%、50%、65%、80%、95%**）；
- **平滑吸附**：
  - 默认初始高度为 **78%**（大开阔视野）；
  - 向上拉升至 **95%**（全屏展开态）；
  - 拖拽松手时自动吸附到最近的 15% 档位；
  - 只有在半屏继续用力向下拉动、高度低于安全下限（`< 25%`）时才触发向下滑出退出，**彻底杜绝“从全屏拉回半屏时发生误退出”**。

### 3. 毛玻璃半透明质感与 CSS 变量透光
- **卡片底层**：动态绘制 24dp 顶部大圆角与 `#EBF5F8FC`（浅色）/ `#EB161B24`（深色）半透明背景与细微描边；
- **WebView 背景透光**：在页面加载时注入动态 CSS 样式，强制覆写 DSH 前端的 `--dsw-alias-bg-base`、`--dsw-alias-bg-layer-1`、`--dsw-specific-sidebar-fill` 为 `transparent !important`；
- **禁用算法反色**：显式关闭 `FORCE_DARK` 与 `setAlgorithmicDarkeningAllowed(false)`，**100% 保持 DSHA 浅色原貌，透出底层桌面壁纸与应用**。

### 4. 键盘自适应与同色底板覆盖（顶栏绝对固定）
- **顶栏稳固不动**：卡片顶边物理锚定在原处，键盘弹出时顶栏、关闭与设置按钮绝对不被顶飞出屏幕；
- **全贴底同色底板**：卡片底板延伸覆盖到屏幕物理最底端，键盘覆盖在卡片上方，**透过键盘半透明缝隙看到的依然是纯净浅色底板，彻底消灭黑块与漏桌面现象**；
- **网页输入框自适应上浮**：WebView 可用视口等额收缩，网页底部的输入框自动精准吸附在输入法正上方。

### 5. 内存单例保活秒开（0 秒加载、0 网络请求）
- **全局静态单例**：采用内存静态 `WebView` 常驻缓存；
- **后台挂起**：用户下滑或点击 ✕ 退出时，平滑滑出后调用 `moveTaskToBack(true)` 挂起保活，不销毁 WebView 与 WebSocket 连接；
- **即时唤醒**：再次点击通知或发送命令时，通过 `FLAG_ACTIVITY_REORDER_TO_FRONT` 与 `onNewIntent()` 毫秒级滑出，**会话状态不丢、输入框草稿不丢、零重新加载**。

---

## 🔔 通知栏全生命周期直达

所有系统通知已全面升级并统一收拢到该快捷抽屉弹层（移除了原本局限狭窄的 `RemoteInput` 就地打字输入框）：

| 通知类型 | 触发场景 | 点击行为 |
| :--- | :--- | :--- |
| **常驻保活通知** (1001) | DSHA 后台服务运行中 | 点击通知主体直接从屏幕底部顺滑唤起抽屉弹层 |
| **任务实时运行通知** (2003) | 智能体执行多步骤自动化任务中 | 点击通知主体直达抽屉弹层（实时查阅步骤与日志），带 `[🛑 停止任务]` 动作按钮 |
| **任务完成通知** (2002) | 智能体完成单轮/多轮任务 | 点击通知主体或点击 `[💬 继续对话]` 按钮，直接唤起抽屉弹层 |
| **任务终止通知** (2004) | 用户紧急制动或外部异常中断 | 点击通知主体或点击 `[💬 重新开始]` 按钮，直接唤起抽屉弹层 |

---

## 💻 命令行直接调出方法

你可以通过容器内的 ADB Shell 通道或外部设备 Shell，随时通过命令在屏幕底部呼出该弹层：

### 1. DSHA 容器内执行（推荐）：
```bash
/root/dsh-bin/adb-shell "am start -n com.dsh.client/com.deepseekharness.app.QuickChatSheetActivity"
```

### 2. Android 设备终端（Termux / Shizuku / ADB）：
```bash
am start -n com.dsh.client/com.deepseekharness.app.QuickChatSheetActivity
```

> **💡 说明**：
> 执行命令后输出 `Warning: Activity not started, its current task has been brought to the front` 属于正常现象，表明系统成功将后台常驻保活的弹层直接置顶唤醒（Brought to front），并触发了顺滑滑入动画。
