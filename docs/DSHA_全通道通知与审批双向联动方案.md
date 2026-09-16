# DSHA 全通道通知、悬浮条与 Web 审批双向联动技术白皮书 (三级精简架构)

> **版本**：v1.2.0-native  
> **架构体系**：KernelSU / Magisk 原生 Linux chroot (uid=0) 极速运行时 + Android 前端宿主  
> **三级联动终端**：
> 1. **通知 / 状态栏灵动胶囊**（系统级全域覆盖）  
> 2. **桌面顶部悬浮条**（就地批准最短路径）  
> 3. **Web 端小黄窗**（对话流中原生审批卡片）  
> *(注：原第 4 级 App 前台 AlertDialog 弹窗已被彻底废弃，彻底消除弹窗叠弹窗的冗余体验)*

---

## 一、 为什么废弃 App 弹窗？确立三级联动架构

在早期的设计中，存在一个在 App 前台弹出的 `AlertDialog`（第 4 级弹窗）。在实际使用中暴露出明显弊端：
1. **视图层叠冲突**：用户打开 App 时，界面主体就是 WebView（抽屉或全屏）。Web 内部本身就会弹出审批卡片，此时若外层原生系统再强制弹出一个白色对话框，会直接挡住 Web 对话流，造成“弹窗叠弹窗”；
2. **生命周期脆弱**：Activity 一旦因分屏、息屏或后台切换进入 `onPause`/`onDestroy`，系统弹窗极易抛出 `BadTokenException` 导致崩溃；或者因为误触 Dismiss 导致命令被误拒绝。

**结论**：彻底剔除冗余的 App 原生弹窗，聚焦于 **通知/灵动岛 + 悬浮窗 + Web小黄窗** 的黄金三级架构！

---

## 二、 三级联动架构全景图

```text
                                  【任一场景触发安全审批】
                                             │
             ┌───────────────────────────────┴───────────────────────────────┐
             ▼                                                               ▼
   【场景 1：DSH 原生提权】                                         【场景 2：底层命令守卫拦截】
   (Agent 申请 danger-full-access)                                  (执行 rm -rf / 等高危命令)
             │                                                               │
             ▼                                                               ▼
   Node 端派发 approval/request                                    dsh-confirm.sh 拦截
             │                                                               │
             │── 调 3095 /app/task/confirm                                   │── 调 3095 /confirm
             │                                                               │── 写入 pending 状态
             ▼                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 【统一三级通道并发弹出】                                      │
│                                                                                             │
│   ① Web 端小黄窗：  对话流中原生黄色【等待审批】卡片（含【拒绝】【允许一次】按钮）                  │
│   ② 桌面悬浮条：    屏幕顶部流式条就地展开【允许】【拒绝】双操作按钮                              │
│   ③ 状态栏通知：    通知栏卡片 + 灵动岛双耳胶囊挂载快捷允许/拒绝动作                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
                                             │
                     ┌───────────────────────┴───────────────────────┐
                     ▼                                               ▼
         【用户在手机端先点】                                  【用户在 Web 端先点】
       (点通知栏 / 灵动岛 / 悬浮条)                               (点对话流小黄窗允许一次)
                     │                                               │
                     ├── 写入 .approval_decision                     ├── 决断返回 allowed-once
                     ├── 解锁 latch                                  ├── 调 3095 /app/task/confirm/cancel
                     ├── Web 自动模拟点击小黄窗                        └── 触发 dismissAllApprovalUi()
                     ▼                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                            【三端毫秒级瞬间跟随销毁 (闭环)】                                    │
│             Web 端小黄窗关闭  +  桌面悬浮条收起  +  系统通知栏/灵动岛胶囊撤回                   │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 三、 核心互斥与防重入机制

### 1. 唯一轮次序号：`confirmEpoch`
- 每次审批发起时，自增全局序号：`final long myEpoch = confirmEpoch.incrementAndGet()`；
- 悬浮条回调、通知栏 PendingIntent、灵动岛动作全部携带此 `myEpoch`；
- **防串线防误触**：任何锁屏残留、历史通知或手表推送，若 `epoch != confirmEpoch.get()`，底层直接原子丢弃，绝不误授权给下一个请求。

### 2. 单次决断抢占：`CountDownLatch(1)` + `AtomicBoolean`
- 在 `requestUserConfirm` 中持有门闩 `pendingLatch = new CountDownLatch(1)`；
- 决断时通过 `confirmResolved.compareAndSet(false, true)` 争夺决策权；
- **竞速原则**：三个渠道谁先点谁生效。先到达的点击完成放行/拒绝并 `latch.countDown()`，其余后到点击一律忽略。

### 3. 三端同步清空：`dismissAllApprovalUi()`
决断成功或超时退出时，全量清理方法确保无死锁残留：
```java
public void dismissAllApprovalUi() {
    isApprovalWaiting = false;
    // 1. 取消通知栏卡片并收起灵动岛大胶囊
    cancelConfirmNotification();
    // 2. 关闭桌面悬浮条批准卡片
    try { OverlayController.dismissConfirm(ctx); } catch (Throwable ignored) {}
    // 3. 同步消除 Web 上的审批弹窗
    dismissWebApprovalDialogs();
}
```

---

## 四、 云端与本地打包产物一致性校验清单

为了确保后续打包构建（不管是 GitHub Actions 还是本地打包）不再出问题，已完成全方位对齐：

| 校验模块 | 本地路径 | 云端对应分支 | 对齐状态 |
| :--- | :--- | :--- | :---: |
| **前端移动端插件** | `/root/dsha-web-mobile/lib/client.js` | `dsh-magisk` / `magisk-apk` | ✅ **已清除未定义语法，空白行已修平，语法校验通过** |
| **Android 宿主端** | `HttpShellService.java` | `magisk-apk` | ✅ **已剔除 App 弹窗，补齐悬浮条展开与 myEpoch 绑定** |
| **广播接收器** | `ConfirmReceiver.java` | `magisk-apk` | ✅ **writeApprovalDecision 已开放公共调用** |
| **模块控制脚本** | `dsha-ksu-project/module_src/*` | `dsh-magisk:magisk-module/*` | ✅ **action/customize/service/scripts 100% 逐行完全一致** |
| **纯净底包导出器** | `dsha-ksu-project/tools/export-rootfs.sh` | 本地运行环境直接打包 | ✅ **自动脱敏后装插件，仅保留 4 大原生纯净插件** |

---

## 五、 端到端快速验收标准

1. **触发提权审批**：Web 端出现黄色卡片的同时，手机桌面悬浮条与通知栏/灵动岛同步出现；
2. **就地点击悬浮条【允许】**：悬浮条与通知栏胶囊瞬间消失，Web 端小黄窗自动完成点击并继续执行；
3. **就地点击 Web 端【允许一次】**：Web 页面继续执行，桌面悬浮条与通知栏胶囊瞬间撤回；
4. **App 内无冗余弹窗**：不会再弹出任何系统白底对话框遮挡 WebView，体验丝滑统一。

---
*本文档由 DeepSeek Harness 架构协同生成，归属于 DSHA Native 核心技术资产。*
