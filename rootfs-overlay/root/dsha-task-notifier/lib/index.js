/**
 * dsh-task-notifier — DSHA builtin server plugin.
 *
 * 全生命周期通知与交互控制器：
 * 1. 运行中通知：监听 turn/start 与 tool/call，通过 3090 桥 /app/task/running 实时推送进度，带「🛑 停止任务」紧急制动按钮；
 * 2. 任务完成通知：监听 turn/end（结合 1.5s 停稳防抖与交互态过滤），通过 3090 桥 /app/notify 推送完成卡片，带「💬 继续对话」输入框；
 * 3. 任务紧急制动：监听 /root/.dsh/.cancel_requested，通过 ctx.inject(['agents']) 调用 agent.cancel() 真正中止工作；
 * 4. 通知栏回复注入：监听 /root/.dsh/.pending_prompt，通过 ctx.inject(['agents']) 调用 agent.followup() 开启新一轮对话；
 * 5. 双向闭环审批：支持手机通知栏/灵动岛与网页端双向决策，谁先点谁生效，点击后自动撤回通知与浮层。
 */
import { randomUUID } from 'node:crypto'
import { readFileSync, existsSync, unlinkSync } from 'node:fs'
import { readFile, unlink } from 'node:fs/promises'

// 保持无模块级硬依赖，防止阻塞插件树初始化
export const name = 'dsh-task-notifier'
export const inject = []

const CANCEL_FLAG = '/root/.dsh/.cancel_requested'
const PENDING_PROMPT = '/root/.dsh/.pending_prompt'
const DECISION_FLAGS = [
  '/root/.dsh/.approval_decision',
  '/sdcard/Download/DSHA/.approval_decision',
  '/root/内部存储/.approval_decision'
]
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

const BRIDGE_PORTS = [3095, 3090]

/** 通过硬件桥发送 HTTP 请求（Native 3095 优先，3090 兜底） */
async function callBridge(endpoint, params = {}) {
  const token = bridgeToken()
  if (!token) return
  for (const port of BRIDGE_PORTS) {
    try {
      const url = new URL(`http://127.0.0.1:${port}${endpoint}`)
      url.searchParams.set('token', token)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) {
          url.searchParams.set(k, String(v))
        }
      }
      const resp = await fetch(url.toString(), { signal: AbortSignal.timeout(3000) })
      if (resp.ok) {
        await resp.text()
        return
      }
    } catch {}
  }
}

const TOOL_LABELS = [
  [/^ask_user/i, "💬 助手提问"],
  [/^read_image|image|screenshot|vision/i, "正在分析画面"],
  [/^todo|plan|goal/i, "正在规划任务清单"],
  [/^web_search|fetch|web|http|browse|url/i, "正在联网查资料"],
  [/^write|create.*file|edit|patch|apply/i, "正在修改文件"],
  [/^read|cat|view|open.*file/i, "正在读取文件"],
  [/^glob|grep|find|search/i, "正在搜索文件"],
  [/^task|agent|subagent|dispatch|workflow|ralph/i, "正在调度子任务"],
  [/^skill/i, "正在加载技能"],
  [/^tap|input|swipe|dump|launch/i, "正在执行屏幕操作"],
  [/^notify|toast|share|clip|vibrate|sensor|location|torch/i, "正在调用手机功能"],
  [/^bash|shell|command|exec|terminal|shizuku/i, "正在执行命令"],
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
  let lastAssistantText = ''
  let isApprovalActive = false
  let isInInteractivePrompt = false
  let justApproved = false
  let lastApprovedAt = 0
  let completionTimer = null

  // 灵动岛/三通道状态机与 2s Trailing 节流控制
  const THROTTLE_MS = 2000
  let lastSentTime = 0
  let lastSentState = ''
  let pendingState = null
  let trailingTimer = null

  function flushRunningNotification(title, text) {
    if (isApprovalActive) return
    if (trailingTimer) {
      clearTimeout(trailingTimer)
      trailingTimer = null
    }
    pendingState = null
    lastSentTime = Date.now()
    lastSentState = text
    void callBridge('/app/task/running', { title, text })
  }

  function scheduleRunningNotification(title, text) {
    if (isApprovalActive) return
    if (text === lastSentState) {
      if (trailingTimer && pendingState && pendingState.text === text) {
        clearTimeout(trailingTimer)
        trailingTimer = null
        pendingState = null
      }
      return
    }

    const now = Date.now()
    const elapsed = now - lastSentTime

    if (elapsed >= THROTTLE_MS && !trailingTimer) {
      flushRunningNotification(title, text)
      return
    }

    pendingState = { title, text }
    if (!trailingTimer) {
      const waitTime = Math.max(50, THROTTLE_MS - elapsed)
      trailingTimer = setTimeout(() => {
        trailingTimer = null
        if (pendingState) {
          const next = pendingState
          pendingState = null
          if (next.text !== lastSentState) {
            flushRunningNotification(next.title, next.text)
          }
        }
      }, waitTime)
    }
  }

  // 1. 会话事件监听（实时同步通知栏与灵动岛）
  ctx.on('session/event', (session, event) => {
    try {
      const type = event?.type
      if (session?.id) {
        lastActiveSessionId = session.id
      }

      if (type === 'turn/start') {
        isApprovalActive = false
        isInInteractivePrompt = false
        if (Date.now() - lastApprovedAt >= 8000) {
          justApproved = false
        }
        if (completionTimer) {
          clearTimeout(completionTimer)
          completionTimer = null
        }
        lastAssistantText = ''
        if (trailingTimer) {
          clearTimeout(trailingTimer)
          trailingTimer = null
        }
        pendingState = null
        lastSentState = '智能体正在分析并执行任务...'
        lastSentTime = Date.now()
        void callBridge('/app/task/running', {
          title: '正在执行',
          text: lastSentState
        })
        return
      }

      if (type === 'assistant/chunk') {
        const chunk = event?.data?.chunk
        if (chunk?.type === 'text-delta' && chunk.text) {
          lastAssistantText = (lastAssistantText + chunk.text).trim()
          if (Date.now() - lastApprovedAt >= 8000) {
            justApproved = false
          }
        }
        return
      }

      if (type === 'assistant/message') {
        const msg = event?.data?.message
        const texts = (msg?.content || []).filter(c => c.type === 'text').map(c => c.text)
        if (texts.length > 0) {
          lastAssistantText = texts.join('').trim()
          if (Date.now() - lastApprovedAt >= 8000) {
            justApproved = false
          }
        }
        return
      }

      if (type === 'approval/asked') {
        isApprovalActive = true
        isInInteractivePrompt = true
        if (completionTimer) {
          clearTimeout(completionTimer)
          completionTimer = null
        }
        if (trailingTimer) {
          clearTimeout(trailingTimer)
          trailingTimer = null
        }
        pendingState = null
        const tool = event?.data?.toolName || '敏感操作'
        const reason = event?.data?.reason || `模型申请执行 ${tool}，等待安全审批`
        lastSentState = reason
        lastSentTime = Date.now()
        void callBridge('/app/task/confirm', {
          title: '⚠️ 危险权限授权申请',
          text: reason
        })
        return
      }

      if (type === 'approval/decided') {
        isApprovalActive = false
        isInInteractivePrompt = false
        justApproved = true
        lastApprovedAt = Date.now()
        lastAssistantText = ''
        if (completionTimer) {
          clearTimeout(completionTimer)
          completionTimer = null
        }
        // Web 侧审批决断后，立即反向通知手机端撤回审批通知与灵动岛大卡片！
        void callBridge('/app/task/confirm/cancel')
        // 关键：文案不含“完成”，彻底杜绝 Android 宿主 compactCapsuleText 误将灵动岛设为“任务已完成”
        lastSentState = '安全授权已通过，正在继续执行...'
        lastSentTime = Date.now()
        void callBridge('/app/task/running', {
          title: '正在执行',
          text: lastSentState
        })
        return
      }

      if (type === 'tool/call') {
        if (completionTimer) {
          clearTimeout(completionTimer)
          completionTimer = null
        }
        const toolName = String(event?.data?.name || '')
        // 提问工具：立即通知手机切换为「💬 助手提问 / 等待回答」状态，挂载「返回对话」抽屉按钮
        if (toolName.includes('ask_user') || toolName.includes('ask_question')) {
          isInInteractivePrompt = true
          if (trailingTimer) {
            clearTimeout(trailingTimer)
            trailingTimer = null
          }
          pendingState = null
          let questionText = '智能体正在等待你的回答与选择'
          try {
            const args = typeof event?.data?.arguments === 'string' ? JSON.parse(event.data.arguments) : event?.data?.arguments
            const q0 = args?.questions?.[0]
            if (q0?.question || q0?.header) {
              questionText = q0.question || q0.header
            }
          } catch {}
          lastSentState = questionText
          lastSentTime = Date.now()
          void callBridge('/app/task/ask', {
            title: '💬 助手提问',
            text: questionText
          })
          return
        }

        isInInteractivePrompt = false
        const text = formatToolDetail(event?.data?.name, event?.data?.arguments) || '智能体正在调用工具...'
        scheduleRunningNotification('正在执行', text)
        return
      }

      if (type === 'turn/end') {
        if (trailingTimer) {
          clearTimeout(trailingTimer)
          trailingTimer = null
        }
        pendingState = null

        // 子任务 (Subagent / Workflow) 结束不向手机发任务完成通知
        if (session?.parentSessionId || session?.parent) {
          return
        }

        // 若正处于审批或提问交互等待中，本轮 turn/end 只是工具交互切段，任务绝未结束，禁止弹完成！
        if (isInInteractivePrompt || isApprovalActive) {
          return
        }

        const reasonObj = event.data?.reason
        const kind = reasonObj?.kind ?? 'completed'

        if (kind === 'error') {
          if (completionTimer) {
            clearTimeout(completionTimer)
            completionTimer = null
          }
          const detail = parseFailureDetail(reasonObj?.error)
          void callBridge('/app/notify', {
            title: '❌ 模型请求失败',
            text: detail || '服务请求异常，点击或在下方打字重试'
          })
          return
        }

        if (kind === 'aborted') {
          if (completionTimer) {
            clearTimeout(completionTimer)
            completionTimer = null
          }
          void callBridge('/app/notify', {
            title: '⚠️ 任务已终止',
            text: '已按指令停止操作，点击查看或继续对话'
          })
          return
        }

        if (kind === 'max-tokens') {
          if (completionTimer) {
            clearTimeout(completionTimer)
            completionTimer = null
          }
          void callBridge('/app/notify', {
            title: '📏 达到最大长度',
            text: '已达单次最大输出限制，可发送“继续”接着生成'
          })
          return
        }

        if (kind === 'blocked') {
          if (completionTimer) {
            clearTimeout(completionTimer)
            completionTimer = null
          }
          void callBridge('/app/notify', {
            title: '🛡️ 任务已挂起',
            text: '等待安全授权或前置条件处理'
          })
          return
        }

        if (kind === 'interrupted') {
          if (completionTimer) {
            clearTimeout(completionTimer)
            completionTimer = null
          }
          void callBridge('/app/notify', {
            title: '⚡ 连接异常中断',
            text: '与容器连接丢失，点击重新进入'
          })
          return
        }

        if (kind === 'completed') {
          // 严密防误弹：处于 8 秒审批冷却期内、或标志位为真、或助手未输出有效文本，绝对禁止弹任务完成！
          const inApprovalCooldown = (Date.now() - lastApprovedAt) < 8000
          if (justApproved || inApprovalCooldown || !lastAssistantText || !lastAssistantText.trim()) {
            // 防抖前（0ms）绝对不立即 cancel：避免审批后或连续步骤间灵动岛被误杀闪烁（采纳上次 Review 关切）
            // 若 1.5s 后确实没有新动作，由下方 completionTimer 停稳后统一安全释放
            return
          }

          const clean = lastAssistantText.replace(/\s+/g, ' ').trim()
          const endText = clean.length > 60 ? clean.slice(0, 60) + '…' : clean

          // 防抖保护（Quiescence Debounce，1500ms）：
          // 避免多轮次长任务在每一个中间回合结束时频繁误弹“任务完成”
          if (completionTimer) {
            clearTimeout(completionTimer)
            completionTimer = null
          }
          completionTimer = setTimeout(() => {
            completionTimer = null
            const inCooldown = (Date.now() - lastApprovedAt) < 8000
            if (!isInInteractivePrompt && !isApprovalActive && !justApproved && !inCooldown) {
              void callBridge('/app/notify', {
                title: '任务已完成',
                text: endText
              })
            } else {
              // 处于交互或冷却状态不弹完成卡片时，拔除 2003 胶囊释放唤醒锁
              void callBridge('/app/task/cancel')
            }
          }, 1500)
          return
        }

        // 统一终极兜底
        const detail = parseFailureDetail(reasonObj?.error) || String(kind || '任务中途脱轨或意外中断')
        void callBridge('/app/notify', {
          title: '⚠️ 任务异常中断',
          text: `${detail}，点击返回对话查看`
        })
      }
    } catch {}
  })

  // 2. 监听全局 agent/error 异常广播
  ctx.on('agent/error', ({ agent, error }) => {
    try {
      if (completionTimer) {
        clearTimeout(completionTimer)
        completionTimer = null
      }
      if (trailingTimer) {
        clearTimeout(trailingTimer)
        trailingTimer = null
      }
      pendingState = null
      const detail = parseFailureDetail(error)
      void callBridge('/app/notify', {
        title: '⚠️ 任务异常中断',
        text: detail || '智能体运行时发生底层故障中断'
      })
    } catch {}
  })

  // 3. 作用域注入 agents 服务，安全、非阻塞地管理 Agent 生命周期
  ctx.inject(['agents'], (agentScope) => {
    let timer = setInterval(async () => {
      try {
        // A. 处理用户点击通知栏「🛑 停止任务」紧急制动
        if (existsSync(CANCEL_FLAG)) {
          lastCancelByNotification = Date.now()
          try { unlinkSync(CANCEL_FLAG) } catch {}
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
          void callBridge('/app/notify', {
            title: '⚠️ 任务已终止',
            text: '已按指令停止操作，点击查看或继续对话'
          })
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
              if (lastActiveSessionId) {
                targetAgent = agentScope.agents.get(lastActiveSessionId)
              }
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
    }, 4000)

    if (timer && typeof timer.unref === 'function') {
      timer.unref()
    }

    agentScope.on('dispose', () => {
      if (timer) clearInterval(timer)
    })
  })

  // 4. 双向闭环审批监听：竞速响应手机灵动岛与网页端点击
  ctx.on('approval/request', async (req, next) => {
    isApprovalActive = true
    isInInteractivePrompt = true
    if (completionTimer) {
      clearTimeout(completionTimer)
      completionTimer = null
    }
    if (trailingTimer) {
      clearTimeout(trailingTimer)
      trailingTimer = null
    }
    pendingState = null

    try {
      for (const flag of DECISION_FLAGS) {
        if (existsSync(flag)) unlinkSync(flag)
      }
    } catch {}

    const tool = req?.toolName || '敏感操作'
    const reason = req?.reason || `模型申请执行 ${tool}，等待安全审批`
    void callBridge('/app/task/confirm', {
      title: '⚠️ 危险权限授权申请',
      text: reason
    })

    // 写入活跃审批状态文件，供 Web 前端极速轮询自愈
    const APPROVAL_STATUS_FILE = '/root/.dsh/.approval_status.json'
    try {
      const fs = await import('node:fs/promises')
      await fs.writeFile(APPROVAL_STATUS_FILE, JSON.stringify({ active: true, time: Date.now(), tool, reason }))
    } catch {}

    let phoneTimer = null
    const phoneDecisionPromise = new Promise((resolve) => {
      phoneTimer = setInterval(() => {
        try {
          for (const flag of DECISION_FLAGS) {
            if (existsSync(flag)) {
              const decision = readFileSync(flag, 'utf-8').trim()
              try { unlinkSync(flag) } catch {}
              if (decision === 'allowed-once' || decision === 'rejected') {
                if (phoneTimer) clearInterval(phoneTimer)
                phoneTimer = null
                lastApprovedAt = Date.now()
                justApproved = true
                resolve(decision)
                return
              }
            }
          }
        } catch {}
      }, 80)
    })

    const webAbortController = new AbortController()
    if (req?.signal) {
      req.signal.addEventListener('abort', () => {
        if (phoneTimer) clearInterval(phoneTimer)
        try { webAbortController.abort(req.signal.reason) } catch {}
      }, { once: true })
    }

    let combinedSignal = webAbortController.signal
    if (req?.signal) {
      if (typeof AbortSignal.any === 'function') {
        combinedSignal = AbortSignal.any([req.signal, webAbortController.signal])
      } else {
        const combinedCtrl = new AbortController()
        const onAb = () => combinedCtrl.abort()
        req.signal.addEventListener('abort', onAb, { once: true })
        webAbortController.signal.addEventListener('abort', onAb, { once: true })
        combinedSignal = combinedCtrl.signal
      }
    }
    const forwardedReq = Object.assign({}, req, { signal: combinedSignal })
    const webDecisionPromise = next ? next(forwardedReq) : Promise.resolve('unavailable')

    try {
      const decision = await Promise.race([phoneDecisionPromise, webDecisionPromise])
      lastApprovedAt = Date.now()
      justApproved = true
      try {
        const fs = await import('node:fs/promises')
        await fs.writeFile(APPROVAL_STATUS_FILE, JSON.stringify({ active: false, time: Date.now(), decision }))
      } catch {}
      // 任何一方决断成功，都确保向手机端发送取消审批通知请求
      void callBridge('/app/task/confirm/cancel')
      return decision
    } finally {
      isApprovalActive = false
      isInInteractivePrompt = false
      lastApprovedAt = Date.now()
      justApproved = true
      // 关键修复：决断结束后立即终止 Web 侧挂起的请求，促使前端 PendingApproval 释放并销毁黄色卡片！
      try { webAbortController.abort('approval settled') } catch {}
      if (phoneTimer) clearInterval(phoneTimer)
      try {
        for (const flag of DECISION_FLAGS) {
          if (existsSync(flag)) unlinkSync(flag)
        }
      } catch {}
    }
  })
}
