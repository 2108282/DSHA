/**
 * dsh-task-notifier — DSHA builtin server plugin.
 *
 * 精准任务完成通知：监听 session/event 的 turn/end（整轮对话结束 =
 * agent 任务完成），通过 DSHA 3090 桥发 App 通知栏提醒。
 * 取代 App 端 TaskNotifier 的「轮询会话文件」方案（不准）。
 *
 * 链路：turn/end → curl http://127.0.0.1:3090/app/notify?title=&text=&token=
 * App 收到后在后台发通知（App 前台时 TaskNotifier 抑制，这里插件无感知——
 * 由 App 端 /app/notify 处理前台判断）。
 */

/** 无额外依赖（监听 session/event 是 cordis 核心事件，任何插件可用） */
export const inject = []

/** 通知节流：同一 agent 30s 内只发一次（防连续 turn/end 轰炸） */
const THROTTLE_MS = 30_000
const lastNotified = new Map()

/** 通过 3090 桥发 App 通知（token 鉴权） */
async function notifyApp(title, text) {
  try {
    const fs = await import('node:fs/promises')
    let token = ''
    try {
      token = (await fs.readFile('/root/.dsh/.bridge_token', 'utf-8')).trim()
    } catch {}
    if (!token) return
    // 用 fetch（node 18+ 内置）调 3090 桥 /app/notify
    const url = 'http://127.0.0.1:3090/app/notify'
      + '?title=' + encodeURIComponent(title)
      + '&text=' + encodeURIComponent(text)
      + '&token=' + encodeURIComponent(token)
    const resp = await fetch(url, { signal: AbortSignal.timeout(5000) })
    await resp.text()
  } catch {}
}

/**
 * Plugin entry.
 * @param {import('@deepseek-ai/cordis').Context} ctx
 */
export function apply(ctx) {
  ctx.on('session/event', (session, event) => {
    try {
      if (event.type !== 'turn/end') return
      // 整轮结束 = 任务完成（turnEnds.reason.kind: completed / blocked / max-tokens 等）
      const reason = event.data?.reason?.kind ?? 'completed'
      if (reason !== 'completed' && reason !== 'max-tokens') {
        // blocked/错误结束也提示（用户需要知道卡住了）
        const sessionId = session?.id ?? 'session'
        const now = Date.now()
        const last = lastNotified.get(sessionId) ?? 0
        if (now - last < THROTTLE_MS) return
        lastNotified.set(sessionId, now)
        notifyApp('DSHA · 任务已结束（' + reason + '）', 'Agent 一轮对话已结束，点击查看结果')
        return
      }
      // 正常完成
      const sessionId = session?.id ?? 'session'
      const now = Date.now()
      const last = lastNotified.get(sessionId) ?? 0
      if (now - last < THROTTLE_MS) return
      lastNotified.set(sessionId, now)
      notifyApp('DSHA · 任务完成', 'Agent 已完成一轮对话，点击查看结果')
    } catch {}
  })
}
