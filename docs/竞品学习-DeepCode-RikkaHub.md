# 学习笔记：DeepCode 与 RikkaHub（2026-08-26）

两个项目的定位完全不同，但各自都逼着我们重新看一遍 DSHA 的位置。

| | DeepCode (HKUDS) | RikkaHub (re-ovo) |
|---|---|---|
| star | 16.4k | 7.1k |
| 许可证 | **MIT** | **AGPL-3.0** |
| 形态 | Python + Rust + Tauri 桌面 / CLI | Android 原生（Kotlin + Compose） |
| 定位 | Agent 运行时与编排（自称 Agent Harness） | 多 provider LLM 客户端 |
| 与 DSHA 的关系 | 思想可借鉴，代码不适用 | **正面重叠**，但代码不能碰 |

---

## 一、必须先说的两件事

### 1. RikkaHub 也做了 proot Linux 环境

它的 feature 列表里明写着 `📦 Workspace: a proot-based Linux agent environment`，实测容器是
**Ubuntu 24.04.3 LTS**。

这意味着「Android 上的完整 glibc Linux 环境」现在至少有三家：**Operit（7k）· RikkaHub（7.1k）·
DSHA**。这条本来写在 README 里的卖点已经彻底不成立，对外表述必须换成别的：

> 专为 dsh（DeepSeek 官方 agent 框架）做的一体化宿主 · proroot 零 ptrace 开销 ·
> 免 Shizuku 的 ADB 直连 · 卸载重装数据不丢 · 备份与自愈体系

### 2. AGPL-3.0 意味着「一行都不能看着抄」

RikkaHub 是 AGPL，比 Operit 的 LGPL 更狠：它会传染整个应用，还带网络服务条款
（用户通过网络使用你的服务时也要能拿到源码）。DSHA 是 MIT，**抄它的代码等于必须把 DSHA 整个
改成 AGPL**。可以看它做了什么功能、怎么组织交互，不能参考它的实现。

DeepCode 是 MIT，代码层面没有障碍，但它是 Python/Rust 的桌面 + CLI agent 内核 —— 我们的
内核是 dsh，所以能拿的也是设计而不是代码。

---

## 二、DeepCode 里真正值得学的东西

它把很多我们靠经验摸索的东西做成了明确契约。

### 权限模型：trust 与 access 是两件事

> Project trust 与权限档位相互独立，`--trust` 不代表 `--access full-access`。
> 无交互的 Ask 模式无法回答审批时**会拒绝该工具调用**。

两点都直接对得上 DSHA 的现状：

- 我们的「设备桥」开关一直混着两件事 —— 桥要不要监听、给不给 shell 执行权限。今天已经拆了一半
  （`DeviceBridgeService.needed()` 只管前者，Shizuku 绑定仍看 ADB 开关），这个方向是对的。
- 待办里的「工具权限三档」应该照它的语义做：**无法审批时默认拒绝，不是放行**。这一条比三档本身
  更重要。

### 会话记录必须能重建请求

> Tool calls and their results are part of the canonical record now, so a resumed agent can
> answer "what did you just run?". A test makes the rule executable: **every request a run
> sends must be rebuildable from the session file alone.**

这是把契约写成测试的范例。DSHA 不做 agent 内核，但同一手法可以用在**备份/恢复**上：

> 恢复出来的环境，必须能只凭备份文件重建出「用户可见的全部状态」。

今天早上那个 bug 正是违反了它 —— 备份刻意排除了 `.bridge_token`，重解压的数据保护却把它带回来，
两处对「什么算用户数据」的定义不一致。如果有这样一条可执行的断言，那个 401 根本出不来。

### compaction 保留近期尾部原文

> The checkpoint replaces the older range and everything recent survives verbatim.

对 DSHA 直接可用的地方是悬浮条与终端缓冲：**砍历史，不动最近**。目前悬浮条的缓冲是「超长块式砍
1200→800」，方向一致；终端 PTY 的 transcript 也该按这个原则。

### 脚本契约（退出码语义）

> `deepcode exec` 仅在 Turn 成功收敛时返回成功退出码。
> JSON 输出供程序解析；人类可读 transcript 不是稳定的机器协议。

DSHA 的 3090 桥 `/exec` 目前只回文本。要让 agent 可靠判断成败，应该分开「给人看的输出」和
「给程序判的状态」。

### Skills 多目录兼容

```text
.agents/skills/     标准
.claude/skills/     Claude 兼容（只读）
~/.deepcode/skills/ 旧路径兼容（只读）
```

**成本极低、收益立刻可见**：DSHA 的 agent-skills 如果也认 `.claude/skills/`，用户从别处拿来的
skill 丢进去就能用，不需要改目录结构。建议优先做这一条。

### Automation

定时（interval）/ 手动 / `--request-id` 幂等键 / 删除只退役定义但保留 Run 历史。DSHA 有
`dsh-task-notifier`，但没有调度。Operit 也做了工作流 —— 这是个共识型功能，值得排期。

---

## 三、RikkaHub 里值得学的（只看功能与交互，不看代码）

- **AI 翻译**：它内置了。我们今天刚做了插件市场描述翻译，方向一致；它的做法是全局能力，我们可以
  逐步扩展到「插件 README」「issue 正文」。
- **provider 配置二维码导出导入**：待办里已有（OpenClaw 的 `pair --link` 是同一类）。手输 token
  这件事本来就该消失。
- **Web access 多端**：我们有 3081 局域网桥，已具备。
- **消息分支 / 记忆 / prompt 变量 / 角色卡**：都属于 dsh 内核的职责范围，DSHA 不该碰。
- **它明确拒绝的 PR 类型**（新功能、AI 生成的大规模重构）值得我们也表态 —— 一个有主张的项目
  需要说清哪些贡献不收。

---

## 四、先划边界：dsh 本体已经有的，DSHA 一律不做

这份笔记第一版写完之后被否掉了一半，原因是踩了这条线。记下来避免再犯：

**DSHA 是 dsh 的宿主与 Android 集成层，不是第二个 agent 内核。**

| 能力 | 归属 | 说明 |
|---|---|---|
| 权限档位 | **dsh 本体** | 已有 `danger-full-access` / `workspace-write` / `read-only`，DSHA 只负责把 `DSH_PERMISSION_MODE` 传进去。所谓「照 DeepCode 做权限三档」纯属重复造轮子 |
| canonical session / resume / 工具调用记录 | **dsh 本体** | |
| compaction 策略 | **dsh 本体** | |
| evidence-driven completion（`--test-cmd`） | **dsh 本体** | |
| skills 机制 | **dsh 本体** | 我们的 `agent-skills/` 已经用标准路径 `~/.agents/skills/`，本来就对齐了 |
| Automation / 任务调度 | **dsh 本体** | 属于 agent 编排，不是宿主的事 |
| provider / 模型管理 | **dsh 本体** | |

DSHA 该管的只有这些：环境宿主（proot/proroot、rootfs、解压升级）· Android 集成（悬浮条、
通知、ADB/Shizuku、无障碍、PTY 终端、3090/3081 桥）· 凭据与配置 · 备份恢复与自愈 ·
插件市场与内置插件安置 · 更新与签名。

**从 DeepCode 借的东西，必须落在这几项之内才算有效。**

## 五、过滤之后真正可做的

1. **把「什么算用户数据」收成一份可断言的清单** —— 备份与重解压都是 DSHA 独有的逻辑，dsh 没有
   对应物。今天的 bridge_token 401 就是这份定义分裂的代价。借的是 DeepCode「把规则写成可执行
   断言」的手法，不是它的 session 设计。
2. **3090 桥 `/exec` 分离「人读输出」与「机器状态」** —— 桥是 DSHA 自己造的，契约该由我们定清。
3. **悬浮条缓冲与 PTY transcript 统一成「砍历史不动最近」** —— 都是 DSHA 自己的 UI 缓冲。
4. **无障碍 snapshot / act 编 ref** —— `DshaAccessibilityService` 是 DSHA 自己的能力，
   这条来自 HermesApp 而不是这两个项目。
5. **局域网二维码配对** —— 桥与局域网访问是 DSHA 的职责（RikkaHub 的 provider 二维码是同类交互）。
6. **README 对外表述改写** —— 「完整 Linux 环境」不再是差异点。

---

## 五、一句话总结

DeepCode 教的是**怎么把约定变成不可违反的东西**（canonical record、可执行的规则、退出码契约、
trust/access 分离）；RikkaHub 提醒的是**我们的技术护城河比想象的浅**，真正的差异只剩「专为 dsh
做到底」这一件事 —— 那就得把它做得比任何通用客户端都顺。
