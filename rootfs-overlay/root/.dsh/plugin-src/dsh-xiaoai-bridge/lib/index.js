import { readFileSync, existsSync } from 'node:fs';
import { createHmac, randomUUID } from 'node:crypto';
import { SYSTEM_TOOLS, runShell } from './tools.js';

export const name = 'dsh-xiaoai-bridge';

// 小爱专属工作区路径与持久会话 ID 前缀
const WORKSPACE_DIR = '/sdcard/Download/DSHA/xiaoai_workspace';

// 小爱专有通信防伪 Salt（内存同步，免文件读写）
const XIAOAI_COMM_SALT = 'dsha-xiaoai-native-salt-2026';

/** 校验请求签名，防止非授权 App 本地探测与调用 */
function verifyXiaoAiAuth(query, timestampStr, signature) {
  if (!timestampStr || !signature) return false;
  const ts = parseInt(timestampStr, 10);
  const now = Math.floor(Date.now() / 1000);
  // 时间窗口 15 秒（防止重放攻击）
  if (Math.abs(now - ts) > 15) return false;

  const expected = createHmac('sha256', XIAOAI_COMM_SALT)
    .update(`${query}|${timestampStr}`)
    .digest('hex');
  return expected === signature;
}

export function apply(ctx) {
  // 注入 DSH 核心服务：WebServer、会话控制器、工作区注册表与智能体列表
  ctx.inject(['webServer', 'sessionController', 'workspaceRegistry', 'agents', 'sessions'], (injectedCtx) => {
    injectedCtx.effect(() => injectedCtx.webServer.register({
      kind: 'exact',
      path: '/xiaoai/chat',
      async handler(req, res) {
        // 允许跨域与预检
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Token, Authorization, X-XiaoAi-Timestamp, X-XiaoAi-Signature');
        if (req.method === 'OPTIONS') {
          res.writeHead(204);
          res.end();
          return;
        }

        // 仅处理 POST 请求
        if (req.method !== 'POST') {
          res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('Method Not Allowed\n');
          return;
        }

        // 读取请求体
        let body = '';
        for await (const chunk of req) {
          body += chunk;
        }

        let payload = {};
        try {
          payload = JSON.parse(body || '{}');
        } catch {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: 'Invalid JSON payload' }));
          return;
        }

        const query = (payload.query || '').trim();
        const dialogId = payload.dialogId || 'dialog-' + Date.now();

        if (!query) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: 'Query is empty' }));
          return;
        }

        // 严格安全校验：防非授权扫描
        const reqTimestamp = req.headers['x-xiaoai-timestamp'] || payload.timestamp;
        const reqSignature = req.headers['x-xiaoai-signature'] || payload.signature;
        if (!verifyXiaoAiAuth(query, reqTimestamp, reqSignature)) {
          res.writeHead(403, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: 'Forbidden: unauthorized client or signature mismatch' }));
          return;
        }

        // 启用 SSE 流式输出
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache, no-transform',
          'Connection': 'keep-alive',
          'X-Accel-Buffering': 'no'
        });

        const sendDelta = (text) => {
          res.write(`data: ${JSON.stringify({ dialogId, delta: text })}\n\n`);
        };

        const sendDone = () => {
          res.write(`data: [DONE]\n\n`);
          res.end();
        };

        try {
          // 1. 每次唤醒小爱均新建一个独立的会话，并关联到小爱专属工作区
          const xiaoaiWorkspace = injectedCtx.workspaceRegistry.list().find(w => w.path === WORKSPACE_DIR);
          const workspaceId = xiaoaiWorkspace ? xiaoaiWorkspace.id : undefined;

          // 生成形如 session-xiaoai-20260927-153022-abcd 的会话 ID
          const now = new Date();
          const pad = (n) => String(n).padStart(2, '0');
          const timeTag = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
          const randSuffix = randomUUID().slice(0, 4);
          const currentSessionId = `session-xiaoai-${timeTag}-${randSuffix}`;

          try {
            const createReq = workspaceId 
              ? { sessionId: currentSessionId, workspaceId } 
              : { sessionId: currentSessionId, cwd: WORKSPACE_DIR };
            await injectedCtx.sessionController.commands.create(createReq);
            console.log('[XiaoAiBridge] 成功创建小爱独立会话:', currentSessionId);

            // 自动重命名会话为用户提问前 30 字
            try {
              const sessionTitle = query.length > 30 ? query.slice(0, 30) + '…' : query;
              await injectedCtx.sessionController.commands.rename({
                sessionId: currentSessionId,
                title: sessionTitle,
              });
            } catch (renameErr) {
              // 命名非致命，静默忽略
            }
          } catch (e) {
            console.warn('[XiaoAiBridge] 创建独立会话异常，回退默认:', e.message);
          }

          let unsubscribeStream = () => {};
          let hasEmittedDelta = false;
          try {
            // 2. 挂载流式监听器：捕获大模型输出的文本流实时推给小爱悬浮卡片
            unsubscribeStream = injectedCtx.on('agent/assistant-stream', ({ agent, frame }) => {
              if (agent?.id !== currentSessionId) return;
              const chunk = frame.chunk;
              if (chunk.type === 'text-delta' && chunk.text) {
                hasEmittedDelta = true;
                sendDelta(chunk.text);
              }
            });

            // 3. 将用户的消息正式投递到 DSH 的 Agent 核心循环中（触发真实推理与工具调用）
            await injectedCtx.sessionController.commands.prompt({
              sessionId: currentSessionId,
              content: [{ type: 'text', text: query }],
              requestId: randomUUID(),
            });

            // 4. 等待 Agent 思考与工具执行彻底完成
            const agent = injectedCtx.agents.get(currentSessionId);
            if (agent) {
              await agent.whenIdle();
              await injectedCtx.sessions.flush(agent.session);
            }

            // 兜底保障：若模型未产出文本流，输出完成通知
            if (!hasEmittedDelta) {
              sendDelta('已完成处理。');
            }
          } catch (agentErr) {
            console.error('[XiaoAiBridge] DSH Agent 执行异常:', agentErr);
            sendDelta(`[DSH推理异常: ${agentErr.message}]`);
          } finally {
            unsubscribeStream();
            sendDone();
          }

          // 5. 本地归档备份到 xiaoai_history.md
          try {
            const timeStr = new Date().toLocaleString('zh-CN', { hour12: false });
            const logEntry = `\n### [${timeStr}] 小爱对话 (Dialog: ${dialogId})\n- **指令内容**：${query}\n- **DSH处理状态**：已由 DSH Agent 深度接管执行\n`;
            import('node:fs').then(fs => {
              fs.appendFileSync(`${WORKSPACE_DIR}/xiaoai_history.md`, logEntry, 'utf-8');
            });
          } catch (e) {}

        } catch (err) {
          console.error('[XiaoAiBridge] 接入 DSH 核心处理异常:', err);
          if (!res.writableEnded) {
            sendDelta(`[DSH服务异常: ${err.message}]`);
            sendDone();
          }
        }
      }
    }));
  });
}
