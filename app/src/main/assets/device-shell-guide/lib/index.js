/**
 * dsh-device-shell-guide — DSHA builtin server plugin（rc.8 全局 npm 模式版）。
 *
 * 启用后，为每个新对话（亦是每次请求组装）注入「设备操作能力」引导：
 * 让 agent 知道它运行在用户 Android 手机上、并可通过 ADB 无线通道
 * （/root/dsh-bin/adb-shell，uid=2000 免 root）或 Shizuku 桥真实干预实体机。
 *
 * 实现（双通道，确保极简模式也生效）：
 *  1) systemPrompt.section（order 150）：标准模式在系统提示注入
 *  2) agent/pre-step 监听：在用户消息后插入一条 user-role 引导消息
 *     ——极简模式（minimal）不加载 systemPrompt 注入，但 pre-step 是
 *     agent 核心链路必经，注入用户消息前=用户提示词前，绕开限制
 *
 * 挂载方式（rc.8 全局模式）：作为 bundle 注册进 web profile 的
 * dsh.profile.bundles + dependencies（file: 指向本机目录，零网络）。
 */

/** 消息身份：dsh 持久化回放时强校验 message.id（缺 id → 整个会话历史拒绝加载） */
import { randomUUID } from 'node:crypto'

/*
 * 这里**故意不写模块级 inject**。
 *
 * 用户实测报错（1.1.7）：
 *   Error: dsh: plugin tree failed to load: dsh: 1 entry did not activate
 *     dsh-device-shell-guide: pending (waiting for service: systemPrompt)
 *
 * 模块级 `export const inject = ['systemPrompt']` 是**硬依赖**：服务没提供，
 * 插件就永远 pending，dsh 判定 entry 未激活 → 整个 plugin tree 加载失败 →
 * **Web 完全起不来**。而本文件开头的注释自己就写着「极简模式不加载
 * systemPrompt」—— 明知有环境没有这个服务，却把它声明成必需，
 * 等于让插件在那些环境里把整个 Web 拖死。
 *
 * 两条看似可行的路都不通：
 *  · 对象形式 `inject: { required: [], optional: ['systemPrompt'] }`
 *    —— 模块级 inject 必须是数组，对象的键会被当成服务名，
 *    报 `pending (waiting for services: required, optional)`，更糟；
 *  · 运行时判空 `if (ctx.systemPrompt)` —— 读未声明的服务直接抛
 *    `cannot get property "systemPrompt" without inject`，不会返回 undefined。
 *
 * 正解是运行时作用域注入 ctx.inject(deps, cb)：**不阻塞插件激活**，
 * 服务就绪时才跑回调，服务不存在就不跑。官方 dsh-web-app 注入
 * systemPrompt 用的就是这个写法（packages/bundle/web-app/src/index.ts）。
 */

/** 注入到系统提示的引导段（针对 DSHA 手机端环境）。
 *
 * <p><b>改这段之前先想一件事：这个文件会通过 runtime 增量更新推给老版本的 App。</b>
 * 清单里有它（runtime-manifest.json），而热更新只换脚本、不换 Java —— 也就是说
 * 新提示词随时可能跑在一个**没有新端点**的 App 上（ensureDeviceShellGuide 有好几条
 * 路径会把 overlay 里的新文件写进 rootfs，比如插件被用户禁用时的「仅更新实体」）。
 * 老 App 收到未知路径不会回 404，而是把它当 shell 命令处理，回给 agent 一堆无意义输出。
 *
 * 所以：凡是引用「较新才有的端点」的地方，都要就地给一份能用的兜底 ——
 * 否则老用户那边的表现是 agent 突然不知道自己能操作手机（比压缩掉几 KB 提示词严重得多）。
 */
const PROMPT = [
  '【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可以干预这台实体手机。',

  '■ 三条通道定位与选择策略：',
  '  1) 普通工具与文件操作（ls / cat / curl …）—— 能办成的就用它；',
  '  2) App 层接口 /app/*（走 127.0.0.1:3090，零配置无障碍原生通道，免 ADB / 免 Shizuku）—— 通用基石与保底通道；',
  '     用于查设备状态(/app/device)、普通冷启动(/app/launch)、页面读屏(/app/ui/dump)、点按(/app/ui/tap)、输入(/app/ui/input)、滑动(/app/ui/swipe)、通知与震动等；',
  '  3) 设备 shell 通道（高级通道，默认优先走 Shizuku 桥直连，未授权/未就绪自动降级到 ADB 无线通道：/root/dsh-bin/adb-shell）—— 用于跨页面私有 DeepLink 直达(am start)、系统设置、抓 logcat 等；手机已授权 Shizuku 或配对 ADB 任一即可秒通。',

  '■ 完整端点清单（读屏 / 点按 / 输入 / 截屏 / 通知 / 剪贴板 / 传感器 / 导出文件 …）：',
  '  T=$(cat /root/.dsh/.bridge_token)',
  '  curl -s "http://127.0.0.1:3090/app/help?token=$T"',
  '  → 要用设备能力时查这一次，里面有每个端点的参数和写法。',
  '    清单刻意没写在这里 —— 它有十几 KB，写进提示词就是每一轮都替你付一次上下文。',
  '  ⚠ /app/help 与 /app/version 只有较新的 App 才有（老版本会把未知路径当 shell 命令处理，',
  '    回给你的东西不像清单）。那种情况按下面这份最小清单用，别反复重试 /app/help：',
  '    设备信息 /app/device · 应用列表 /app/apps?q= · 启动应用 /app/launch?pkg=',
  '    读屏 /app/ui/dump · 按文字点按 /app/ui/tap?text= · 输入 /app/ui/input?text=',
  '    滑动 /app/ui/swipe · 截屏 /app/ui/screenshot · 按键 /app/ui/key',
  '    通知 /app/notify?title=&text= · 提问 /app/ask?q=&options=a|b · Toast /app/toast?text=',
  '    剪贴板 /app/clip（写入加 ?text=） · 分享 /app/share?text= · 开链接 /app/open?url=',
  '    震动 /app/vibrate?ms= · 导出文件 /app/export?path= · 读 sdcard 文件 /app/readfile?path=',
  '    位置 /app/location · 传感器 /app/sensors 与 /app/sensor?type= · 手电 /app/torch?on=1',

  '■ 跨页面直达与双通道智能降级机制：',
  '  - /app/open?url= 仅支持 http/https/geo/tel/mailto/market 等标准系统链接，不支持第三方 App 私有 Scheme（如 openapp.jdmobile://、tbopen:// 等）；',
  '  - 跨应用搜索与页面跳转遵循「直达优先、无障碍保底」的双通道阶梯策略：',
  '    ① 首选直达（ADB / Shizuku 设备 shell 可用时）：使用 am start -a android.intent.action.VIEW -d <DeepLink> 一步直达搜索结果页，避免首屏广告、频道选择偏差与多步 UI 盲点；',
  '       例（京东搜索）：/root/dsh-bin/adb-shell "am start -a android.intent.action.VIEW -d \'openapp.jdmobile://virtual?params={\\\"category\\\":\\\"jump\\\",\\\"des\\\":\\\"search\\\",\\\"keyWord\\\":\\\"商品名\\\"}\'"',
  '       例（淘宝搜索）：/root/dsh-bin/adb-shell "am start -a android.intent.action.VIEW -d \'tbopen://m.taobao.com/tbopen/index.html?action=ali.open.nav&module=h5&bootImage=0&h5Url=https%3A%2F%2Fs.taobao.com%2Fsearch%3Fq%3D商品名\'"',
  '    ② 智能降级（ADB / Shizuku 未开启/失败/不支持协议时）：立即无缝回退到 /app/launch?pkg= 调起应用，配合 /app/ui/*（dump/tap/input）走完整的无障碍模拟搜索流程；',

  '■ 硬约束（几条，都别违）：',
  '  - 危险命令由 App 侧守卫拦下来弹确认框、用户点允许才执行。这道门是机制保证的，' +
  '    所以你不必在执行前再口头问一次「可以吗」，把「为什么要跑这条」写进动作说明就够；',
  '  - 不要试图绕过守卫（DSH_NO_CONFIRM、直接调 adb-shell.py 之类）—— 绕过即违规；',
  '  - 接口回 DISABLED / NO_PERMISSION、或 ADB/Shizuku 均连不上时，照原话告诉用户去哪里开，' +
  '    不要反复重试同一条 —— 重试不会让开关自己变；',
  '  - 屏幕操作的节奏：每次点按或输入之后先 /app/ui/dump 再决定下一步，别凭记忆连点，' +
  '    界面可能已经变了；',
  '  - 查找与定位元素（两阶段候选池消歧机制）：' +
  '    1) 先遍历 dump 的全部元素，收集所有包含或相关目标词的候选节点，严禁遇到第一个相似项就立即盲点；' +
  '    2) 在候选池中综合判断：完全匹配优先于包含匹配（如“搜索”优先于“语音搜索”）、独立按钮优先于内嵌图标、结合屏幕区域（如搜索提交按钮通常在右上/最右侧）；' +
  '    3) 决策出唯一目标后，优先使用该节点的精确中心坐标（/app/ui/tap?x=&y=）点击，避免底层模糊匹配抢跑；' +
  '    4) 严禁盲点未在 dump 中确认的估算坐标；若无障碍树节点出现异常偏移，必须通过滑动重刷或截图核验；进入搜索页必须核验当前所属频道（如误入秒送/外卖等），非全站需主动纠偏；',
  '  - 全流程自动化标准工作流（动态租约按需获取 + 10分钟跨会话免打扰 + 闭环汇报）：' +
  '    1) 【动态租约按需获取】：任务开始第一步必须先检查 /root/.dsh/.auth_lease 租约状态（判断时间戳是否大于当前时间）。① 若租约存在且未过期：处于 10 分钟授权期内，严禁调用 /app/ask 重复打扰用户，直接全自动静默执行；② 若无租约或已过期：必须先调用 /app/ask 弹窗请求一次性授权，用户同意后立即写入 /root/.dsh/.auth_lease（内容为当前时间戳+600秒，激活10分钟租约），再开始执行后续操作；' +
  '    2) 【租约保留与自然过期】：单次任务结束严禁删除 /root/.dsh/.auth_lease！租约在 10 分钟后由系统自动自然过期；任务结束只需清理单张临时截图文件，严禁删除租约，确保 10 分钟内所有后续操作和新开对话完全免打扰；' +
  '    3) 【双通道协同与闭环汇报】：跨页面直达按「直达优先、无障碍保底」协同执行，页面内点击与操作优先使用 /app/ui/*（dump/tap/input，零弹窗）；任务结束统一通过 /app/notify、Toast 和 /app/vibrate 震动向用户交付结果；',
  '  - 默认权限是 shell 级（uid=2000，非 root）。不要主动用 --su，' +
  '    只有用户明确要求 root 操作时才提，并且要他先到「配置」页勾选授权；',
  '  - 不要用 /root/dsh-bin/adb 或裸 adb 命令 —— 那是守卫包装脚本，会失败；',
  '  - 与用户交流一律使用中文。',
].join('\n')

/**
 * Plugin entry: register the guidance section.
 * @param {import('@deepseek-ai/cordis').Context} ctx
 */
export function apply(ctx) {
  // 通道 1：标准模式 systemPrompt 注入。
  // 用作用域注入而不是模块级 inject —— 极简模式没有这个服务，
  // 硬依赖会让插件永远 pending 并拖垮整个 Web 启动。
  ctx.inject(['systemPrompt'], (promptCtx) => {
    promptCtx.systemPrompt.section({
      name: 'dsh:device-shell-guide',
      order: 150,
      text: PROMPT,
    })
  })

  // 通道 2：极简模式（minimal 不加载 systemPrompt）→ 在用户消息后插入引导
  // agent/pre-step 是 agent 核心链路必经事件（官方 dsh-agent-instructions/
  // dsh-compaction-basic 同款用法）。next() 返回 decision，decision.messages
  // 是「本轮」消息（claimed），不含历史 → 不能用 messages 判幂等！
  // 用会话级 WeakSet：同一 session 只注入一次（新对话=新 session 再注入）。
  const guided = /* @__PURE__ */ new WeakSet()
  ctx.on('agent/pre-step', async ({ agent, messages }, next) => {
    const decision = await next()
    if (decision.kind !== 'enter' || !Array.isArray(decision.messages)) return decision
    // 幂等：本会话已注入过 → 跳过（新对话是新 session，WeakSet 自动不含）
    if (agent?.session != null && guided.has(agent.session)) return decision
    // 找到本轮最后一条用户消息的索引（claimed 里 role=user）
    let lastUser = -1
    for (let i = 0; i < messages.length; i++) {
      const m = messages[i]
      if (m && (m.role === 'user' || (m.content && m.source?.kind === 'user'))) lastUser = i
    }
    if (lastUser < 0) return decision
    const guide = {
      // 关键：dsh 持久化会话时强校验每条消息带非空 id
      // （assertMessageEventShape → "lacks an identified message"）。
      // 手搓消息绕过了官方 createUserMessage() 工厂，必须自己补 id，
      // 否则这条引导消息会把整个会话历史写成「加载失败」的损坏状态。
      id: randomUUID(),
      role: 'user',
      content: [{ type: 'text', text: PROMPT }],
      source: { kind: 'dsh-device-guide', plugin: 'dsh-device-shell-guide' },
    }
    // 注入到本轮最后用户消息之后（= 用户提示词前的位置语义）
    const claimedCount = messages.length
    const out = decision.messages.toSpliced(lastUser + 1, 0, guide)
    // 标记本会话已注入（防多轮重复）
    if (agent?.session != null) guided.add(agent.session)
    return { ...decision, messages: out }
  })
}
