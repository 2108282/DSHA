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

/** 依赖的服务：systemPrompt（标准模式）+ agent 核心（极简模式走 pre-step） */
export const inject = ['systemPrompt']

/** 注入到系统提示的引导段（针对 DSHA 手机端环境）。 */
const PROMPT = [
  '【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可干预这台实体手机。',

  '■ 使用策略（重要，严格遵守）：',
  '  - 优先用普通工具/文件操作完成，只有【不得不用 shell】时才执行设备命令',
  '  - 例如：查文件用 ls/cat 即可，别 dumpsys；只有看前台应用/启动应用/抓日志' +
  '    这类必须设备命令的场景才用 adb-shell',
  '  - 每条命令前先说明「为什么需要执行」；能合并成一条的不要分多条',

  '■ ⚠️ shell 确认铁律（必须遵守）：',
  '  - 每次执行设备命令【前】，必须先向用户报备：要执行什么、为什么需要',
  '  - 用户明确同意后，执行时 App 会弹「安全确认」，用户点允许才真正执行',
  '  - 不要尝试绕过确认（如 DSH_NO_CONFIRM、直接调 adb-shell.py）——' +
  '    绕过即违规，用户可关闭 ADB 通道',
  '  - 只读操作（getprop/dumpsys/logcat）也需报备，但可合并说明',
  '  - 用户拒绝后不要反复尝试，改为询问替代方案',

  '■ 主通道（ADB 无线，已配对）：',
  '  /root/dsh-bin/adb-shell "命令"          # shell 级（uid=2000）',
  '  （若包装命令不存在，直接用：python3 /root/.dsh/adb-shell.py "命令"）',

  '■ ⚠️ root 提权（--su）必须用户授权：',
  '  - 不要主动用 --su！只有用户明确要求 root 操作时才尝试',
  '  - 执行前必须先请用户到「配置」页勾选「允许 root shell」并保存',
  '  - 未授权时 --su 会被拒绝；授权后仍要说明要执行的 root 命令',

  '■ 备选通道（Shizuku 桥，可能未就绪，需 token）：',
  '  curl -s "http://127.0.0.1:3090/exec?cmd=...&token=$(cat /root/.dsh/.bridge_token)"',

  '■ App 层交互（通过 3090 桥，DSHA 专属能力）：',
  '  T=$(cat /root/.dsh/.bridge_token)',
  '  发通知：curl -s "http://127.0.0.1:3090/app/notify?title=任务完成&text=内容&token=$T"',
  '  弹提示：curl -s "http://127.0.0.1:3090/app/toast?text=内容&token=$T"',
  '  读外部文件：curl -s "http://127.0.0.1:3090/app/readfile?path=/sdcard/Download/x.txt&token=$T"',
  '  外部存储已挂载：/sdcard（Download/DCIM 等公共目录可直接读写）',

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
  // 通道 1：标准模式 systemPrompt 注入
  ctx.systemPrompt.section({
    name: 'dsh:device-shell-guide',
    order: 150,
    text: PROMPT,
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
