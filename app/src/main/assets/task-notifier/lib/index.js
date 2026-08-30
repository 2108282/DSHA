/**
 * dsh-task-notifier — DSHA builtin server plugin.
 *
 * 全生命周期通知与交互控制器：
 * 1. 运行中通知：监听 turn/start 与 tool/call，通过 3090 桥 /app/task/running 实时推送进度，带「🛑 停止任务」紧急制动按钮；
 * 2. 任务完成通知：监听 turn/end，通过 3090 桥 /app/notify 推送完成卡片，带「💬 继续对话」输入框；
 * 3. 任务紧急制动：监听 /root/.dsh/.cancel_requested，遍历 ctx.agents 调用 agent.cancel() 真正中止工作；
 * 4. 通知栏回复注入：监听 /root/.dsh/.pending_prompt，读取后自动向当前 agent 发送 followup 开启新一轮对话。
 */
import { randomUUID } from 'node:crypto'
import { readFileSync, existsSync, unlinkSync } from 'node:fs'
import { readFile, unlink } from 'node:fs/promises'

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

export function apply(ctx) {
  // 1. 会话事件监听
  ctx.on('session/event', (session, event) => {
    try {
      const type = event?.type

      if (type === 'turn/start') {
        void callBridge('/app/task/running', {
          title: 'DSHA · 正在执行',
          text: '智能体正在分析并执行任务...'
        })
        return
      }

      if (type === 'tool/call') {
        const text = formatToolDetail(event?.data?.name, event?.data?.arguments)
        void callBridge('/app/task/running', {
          title: 'DSHA · 正在执行',
          text: text || '智能体正在调用工具...'
        })
        return
      }

      if (type === 'turn/end') {
        void callBridge('/app/task/cancel')

        const reason = event.data?.reason?.kind ?? 'completed'
        const sessionId = session?.id ?? 'session'
        const now = Date.now()
        const last = lastNotified.get(sessionId) ?? 0
        if (now - last < THROTTLE_MS) return
        lastNotified.set(sessionId, now)

        if (reason !== 'completed' && reason !== 'max-tokens') {
          if (reason === 'aborted') {
            // 用户主动中断不需要再发完成通知（ConfirmReceiver 已发送已终止通知）
            return
          }
          void callBridge('/app/notify', {
            title: `DSHA · 任务已结束（${reason}）`,
            text: 'Agent 一轮对话已结束，点击查看结果'
          })
          return
        }

        void callBridge('/app/notify', {
          title: 'DSHA · 任务完成',
          text: 'Agent 已完成当前任务，点击或在下方打字继续对话'
        })
      }
    } catch {}
  })

  // 2. 周期巡检：监听取消指令文件与新回复指令文件
  let timer = setInterval(async () => {
    try {
      // 检查中止请求
      try {
        if (existsSync(CANCEL_FLAG)) {
          unlinkSync(CANCEL_FLAG)
          // 遍历所有 live agents 中止当前任务
          if (ctx.agents && typeof ctx.agents.list === 'function') {
            for (const ag of ctx.agents.list()) {
              try {
                if (ag && typeof ag.cancel === 'function') {
                  ag.cancel({ kind: 'user' }, { keepInbox: true })
                }
              } catch {}
            }
          }
        }
      } catch {}

      // 检查继续对话/新指令请求
      try {
        if (existsSync(PENDING_PROMPT)) {
          const raw = (await readFile(PENDING_PROMPT, 'utf-8')).trim()
          await unlink(PENDING_PROMPT).catch(() => {})
          if (raw) {
            let targetAgent = null
            if (ctx.agents && typeof ctx.agents.list === 'function') {
              const list = ctx.agents.list()
              if (list.length > 0) {
                targetAgent = list[list.length - 1]
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
          }
        }
      } catch {}
    } catch {}
  }, 400)

  if (timer && typeof timer.unref === 'function') {
    timer.unref()
  }

  ctx.on('dispose', () => {
    if (timer) clearInterval(timer)
  })
}
