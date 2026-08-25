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

/** 注入到系统提示的引导段（针对 DSHA 手机端环境）。 */
const PROMPT = [
  '【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可干预这台实体手机。',

  '■ 使用策略（重要，严格遵守）：',
  '  - 优先级：普通工具/文件操作 → App 层接口（/app/*，零配置）→ 设备 shell（ADB，需用户开启）',
  '  - 例如：查设备状态用 /app/device 而不是 dumpsys battery；启动应用用 /app/launch' +
  '    而不是 am start；查文件用 ls/cat 即可',
  '  - 只有模拟点击(input)、装卸应用(pm)、改系统设置(settings)、抓 logcat/dumpsys' +
  '    这类事才必须用 adb-shell',
  '  - 每条命令前先说明「为什么需要执行」；能合并成一条的不要分多条',

  '■ ⚠️ shell 确认铁律（必须遵守）：',
  '  - 每次执行设备命令【前】，必须先向用户报备：要执行什么、为什么需要',
  '  - 用户明确同意后，执行时 App 会弹「安全确认」，用户点允许才真正执行',
  '  - 不要尝试绕过确认（如 DSH_NO_CONFIRM、直接调 adb-shell.py）——' +
  '    绕过即违规，用户可关闭 ADB 通道',
  '  - 只读操作（getprop/dumpsys/logcat）也需报备，但可合并说明',
  '  - 用户拒绝后不要反复尝试，改为询问替代方案',

  '■ 首选通道：App 层接口（DSHA 专属，走 3090 桥，不需要 ADB、不需要 Shizuku、无需任何用户配置）：',
  '  T=$(cat /root/.dsh/.bridge_token)   # 以下 $T 均指它',
  '  ⚠️ 带中文/空格的参数一律用 -G --data-urlencode，别手写 URL 编码：',
  '     curl -s -G "http://127.0.0.1:3090/app/toast" --data-urlencode "text=你好" --data-urlencode "token=$T"',
  '',
  '  ▸ 屏幕操作（无障碍服务，同样不需要 ADB/Shizuku；未开启时接口会返回如何开启的提示）：',
  '     读屏  curl -s "http://127.0.0.1:3090/app/ui/dump?token=$T"',
  '           → 逐行给出「[序号] "文字" 可点击 中心=(x,y) 区域=l,t,r,b」，据此决定点哪个',
  '     点按  curl -s -G "http://127.0.0.1:3090/app/ui/tap" --data-urlencode "text=设置" --data-urlencode "token=$T"',
  '           → 优先用 text= 按文字点（控件位置会随滚动和动画变，文字不会）；',
  '             实在没有文字才用 ?x=&y=（坐标取 dump 里的「中心=」）',
  '     输入  curl -s -G "http://127.0.0.1:3090/app/ui/input" --data-urlencode "text=要输入的内容" --data-urlencode "token=$T"',
  '           → 填到当前焦点输入框；没有焦点就先 tap 一下输入框',
  '     按键  curl -s "http://127.0.0.1:3090/app/ui/key?name=back&token=$T"',
  '           → back / home / recents / notifications / quicksettings / lock',
  '     滑动  curl -s "http://127.0.0.1:3090/app/ui/swipe?x1=500&y1=1500&x2=500&y2=500&ms=300&token=$T"',
  '     截屏  curl -s "http://127.0.0.1:3090/app/ui/screenshot?token=$T"',
  '           → 存成 PNG 落到 Download/DSHA 并返回路径（不回 base64，免得撑爆上下文）',
  '',
  '  操作节奏：每次点按/输入后先 dump 再决定下一步，别凭记忆连点 —— 界面可能已经变了。',
  '  · 设备状态（机型/系统/电量/网络/屏幕/存储/内存）：/app/device',
  '  · 已装应用（可搜索，默认只列第三方）：/app/apps?q=微信&limit=50',
  '  · 启动应用：/app/launch?pkg=com.tencent.mm',
  '  · 剪贴板：读 /app/clip（需 App 在前台，系统限制）；写 /app/clip + --data-urlencode "text=…"',
  '  · 问用户（弹窗阻塞等回答，最多三个选项）：/app/ask?options=继续|取消 + --data-urlencode "q=要继续吗"',
  '  · 通知栏：/app/notify?title=任务完成 + --data-urlencode "text=…"；App 内提示：/app/toast',
  '  · 分享到其它应用：/app/share（text= 或 path=/sdcard/…）；打开链接：/app/open?url=https://…',
  '  · 震动提醒（长任务跑完叫醒用户）：/app/vibrate?ms=300',
  '  · 把产物交给用户：/app/export?path=/root/report.md → 落到 Download/DSHA，用户在文件管理器里直接看得到',
  '  · 读外部文件：/app/readfile?path=/sdcard/Download/x.txt',
  '  外部存储已挂载：/sdcard（Download/DCIM 等公共目录可直接读写）',
  '  用法建议：需要用户拍板时用 /app/ask 而不是干等；长任务结束用 /app/notify 或 /app/vibrate 叫人；',
  '  产出报告/日志用 /app/export 而不是只留在容器里。',

  '■ 设备 shell 通道（ADB 无线调试；用户可能没开，未开时不要反复重试）：',
  '  /root/dsh-bin/adb-shell "命令"          # shell 级（uid=2000）',
  '  （若包装命令不存在，直接用：python3 /root/.dsh/adb-shell.py "命令"）',
  '  - 若报连不上/未配对：先看上面的 App 层接口能不能办成；确实必须 shell 才请用户到「配置」页开「ADB 设备通道」并配对，别反复试同一条命令',

  '■ ⚠️ root 提权（--su）必须用户授权：',
  '  - 不要主动用 --su！只有用户明确要求 root 操作时才尝试',
  '  - 执行前必须先请用户到「配置」页勾选「允许 root shell」并保存',
  '  - 未授权时 --su 会被拒绝；授权后仍要说明要执行的 root 命令',

  '■ 备选通道（Shizuku 桥，可能未就绪，需 token）：',
  '  curl -s "http://127.0.0.1:3090/exec?cmd=...&token=$(cat /root/.dsh/.bridge_token)"',

  '■ ⚠️ 注意：不要用 /root/dsh-bin/adb 或裸 adb 命令——那是守卫包装脚本，会失败。',

  '■ 常用只读操作（可直接执行）：',
  '  - 查设备信息：getprop ro.product.model',
  '  - 查前台应用：dumpsys window | grep mCurrentFocus',
  '  - 抓日志：logcat -d -t 100',

  '■ 权限边界：默认 shell 级（uid=2000，非 root）；root 操作需用户配置页授权。',
  '■ 安全：删除/格式化/重启/卸载等破坏性命令，动手前先说明后果并征得同意。',
  '■ 语言要求：与用户交流请一律使用中文回复。',
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
