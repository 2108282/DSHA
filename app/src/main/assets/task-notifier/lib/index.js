/**
 * dsh-task-notifier — DSHA builtin server plugin.
 *
 * 全生命周期通知与交互控制器：
 * 1. 运行中通知：监听 turn/start 与 tool/call，通过 3090 桥 /app/task/running 实时推送进度，带「🛑 停止任务」紧急制动按钮；
 * 2. 任务完成通知：监听 turn/end，通过 3090 桥 /app/notify 推送完成卡片，带「💬 继续对话」输入框；
 * 3. 任务紧急制动：监听 /root/.dsh/.cancel_requested，通过 ctx.inject(['agents']) 调用 agent.cancel() 真正中止工作；
 * 4. 通知栏回复注入：监听 /root/.dsh/.pending_prompt，通过 ctx.inject(['agents']) 调用 agent.followup() 开启新一轮对话。
 */
import { randomUUID } from 'node:crypto'
import { readFileSync, existsSync, unlinkSync } from 'node:fs'
import { readFile, unlink } from 'node:fs/promises'

// 保持无模块级硬依赖，防止阻塞插件树初始化
export const inject = []

const THROTTLE_MS = 30_000
const lastNotified = new Map()

const CANCEL_FLAG = '/root/.dsh/.cancel_requested'
const PENDING_PROMPT = '/root/.dsh/.pending_prompt'
const TOKEN_PATH = '/root/.dsh/.bridge_token'

let cachedToken = ''
function bridgeToken() {
  if (cachedToken) return cachedToken
  try {
    cachedToken = readFileSync(TOKEN_PATH, 'utf-8').trim()
  } catch {
    cachedToken = ''
  }
  return cachedToken
}

/** 通过 3090 桥发送 HTTP 请求 */
async function callBridge(endpoint, params = {}) {
  const token = bridgeToken()
  if (!token) return
  try {
    const url = new URL(`http://127.0.0.1:3090${endpoint}`)
    url.searchParams.set('token', token)
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null) {
        url.searchParams.set(k, String(v))
      }
    }
    const resp = await fetch(url.toString(), { signal: AbortSignal.timeout(5000) })
    await resp.text()
  } catch {}
}

const TOOL_LABELS = [
  [/bash|shell|command|exec|terminal/i, '正在执行命令'],
  [/write|create.*file|edit|patch|apply/i, '正在修改文件'],
  [/read|cat|view|open.*file/i, '正在读取文件'],
  [/glob|grep|search|find/i, '正在搜索文件'],
  [/fetch|web|http|browse|url/i, '正在联网查资料'],
  [/todo|plan/i, '正在规划任务清单'],
  [/task|agent|subagent|dispatch/i, '正在调度子任务'],
  [/image|screenshot|vision/i, '正在分析屏幕画面'],
  [/notify|toast|share|clip/i, '正在调用手机系统功能'],
]

function formatToolDetail(name, argsJson) {
  const n = String(name || '').trim()
  let label = '正在使用工具 ' + n
  for (const [re, l] of TOOL_LABELS) {
    if (re.test(n)) {
      label = l
      break
    }
  }
  try {
    if (!argsJson) return label
    const o = typeof argsJson === 'string' ? JSON.parse(argsJson) : argsJson
    if (!o || typeof o !== 'object') return label
    const ARG_KEYS = ['command', 'cmd', 'script', 'path', 'file_path', 'filePath', 'pattern', 'query', 'url', 'prompt', 'description']
    for (const k of ARG_KEYS) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) {
        const detail = v.trim().replace(/\n/g, ' ')
        return `${label}: ${detail.length > 40 ? detail.slice(0, 40) + '…' : detail}`
      }
    }
  } catch {}
  return label
}

function parseFailureDetail(error) {
  if (!error) return "网络或服务异常"
  if (typeof error === "object") {
    const statusPart = error.status ? ` (${error.status})` : ""
    const msg = error.message || error.code || "请求失败"
    return `${msg}${statusPart}`.slice(0, 50)
  }
  return String(error).slice(0, 50)
}

let lastCancelByNotification = 0

export function apply(ctx) {
  let lastActiveSessionId = null

  // 1. 会话事件监听（实时同步通知栏）
  ctx.on('session/event', (session, event) => {
    try {
      const type = event?.type
      if (session?.id) {
        lastActiveSessionId = session.id
      }

      if (type === 'turn/start') {
        void callBridge('/app/task/running', {
          title: '正在执行',
          text: '智能体正在分析并执行任务...'
        })
        return
      }

      if (type === 'tool/call') {
        const text = formatToolDetail(event?.data?.name, event?.data?.arguments)
        void callBridge('/app/task/running', {
          title: '正在执行',
          text: text || '智能体正在调用工具...'
        })
        return
      }

      if (type === 'turn/end') {
        void callBridge('/app/task/cancel')

        const reasonObj = event.data?.reason
        const kind = reasonObj?.kind ?? 'completed'
        const sessionId = session?.id ?? 'session'
        const now = Date.now()
        const last = lastNotified.get(sessionId) ?? 0
        if (now - last < THROTTLE_MS) return
        lastNotified.set(sessionId, now)

        if (kind === 'error') {
          const detail = parseFailureDetail(reasonObj?.error)
          void callBridge('/app/notify', {
            title: '❌ 模型请求失败',
            text: detail || '服务请求异常，点击或在下方打字重试'
          })
          return
        }

        if (kind === 'aborted') {
          if (now - lastCancelByNotification < 5000) {
            return
          }
          void callBridge('/app/notify', {
            title: '🔵 任务已中断',
            text: 'Agent 任务已被中断，点击或在下方打字重新开始'
          })
          return
        }

        if (kind === 'max-tokens') {
          void callBridge('/app/notify', {
            title: '📏 达到单次最大长度',
            text: '已达单次最大输出限制，可发送“继续”接着生成'
          })
          return
        }

        if (kind === 'blocked') {
          void callBridge('/app/notify', {
            title: '🛡️ 任务已挂起',
            text: '等待安全授权或前置条件处理'
          })
          return
        }

        if (kind === 'interrupted') {
          void callBridge('/app/notify', {
            title: '⚡ 连接异常中断',
            text: '与容器连接丢失，点击重新进入'
          })
          return
        }

        void callBridge('/app/notify', {
          title: '任务完成',
          text: 'Agent 已完成当前任务，点击或在下方打字继续对话'
        })
      }
    } catch {}
  })

  // 2. 作用域注入 agents 服务，安全、非阻塞地管理 Agent 生命周期（停止与继续对话）
  ctx.inject(['agents'], (agentScope) => {
    let timer = setInterval(async () => {
      try {
        // A. 处理用户点击通知栏「🛑 停止任务」紧急制动
        if (existsSync(CANCEL_FLAG)) {
          lastCancelByNotification = Date.now()
          try {
            unlinkSync(CANCEL_FLAG)
          } catch {}
          try {
            const list = agentScope.agents.list()
            for (const ag of list) {
              try {
                if (ag && typeof ag.cancel === 'function') {
                  ag.cancel({ kind: 'user' }, { keepInbox: true })
                }
              } catch {}
            }
          } catch {}
        }

        // B. 处理用户在通知栏输入文字「💬 继续对话 / 重新输入」
        if (existsSync(PENDING_PROMPT)) {
          let raw = ''
          try {
            raw = (await readFile(PENDING_PROMPT, 'utf-8')).trim()
            await unlink(PENDING_PROMPT).catch(() => {})
          } catch {}

          if (raw) {
            try {
              let targetAgent = null
              // 优先查找最近活跃的 session 对应的 agent
              if (lastActiveSessionId) {
                targetAgent = agentScope.agents.get(lastActiveSessionId)
              }
              // 兜底找根 agent 或最新 live agent
              if (!targetAgent) {
                const roots = typeof agentScope.agents.roots === 'function' ? agentScope.agents.roots() : []
                if (roots && roots.length > 0) {
                  targetAgent = roots[0]
                } else {
                  const list = agentScope.agents.list()
                  if (list && list.length > 0) {
                    targetAgent = list[list.length - 1]
                  }
                }
              }

              if (targetAgent && typeof targetAgent.followup === 'function') {
                const msg = {
                  id: randomUUID(),
                  role: 'user',
                  content: [{ type: 'text', text: raw }],
                  source: { kind: 'user' }
                }
                targetAgent.followup(msg)
              }
            } catch {}
          }
        }
      } catch {}
    }, 300)

    if (timer && typeof timer.unref === 'function') {
      timer.unref()
    }

    agentScope.on('dispose', () => {
      if (timer) clearInterval(timer)
    })
  })
}
