#!/usr/bin/env node
/**
 * DSHA 局域网反向代理守护服务 (DSHA LAN Proxy Daemon)
 * 监听 0.0.0.0:3081 -> 转发至 127.0.0.1:3080
 *
 * 核心安全契约：
 * 1. 严格门禁：每个进入 3081 的请求必须持有与当前系统相同的有效 LAN Token，绝不单纯信任 dsh-auth-*！
 * 2. 即时失效：一旦手机端更换 Token，所有持有旧 Token/旧 Cookie 的设备立即被 401 拦截并清空 Cookie！
 * 3. 实时强断：Token 变更时主动掐断所有存量活动 WebSocket 与长连接，防止旧会话静默偷跑。
 * 4. 友好登录：未带 Token 或凭据失效时展示中文 401 解锁卡片，输入新 Token 即可一键登录。
 */

const http = require('http');
const net = require('net');
const fs = require('fs');

const LAN_PORT = parseInt(process.env.LAN_PORT || '3081', 10);
const BACKEND_PORT = parseInt(process.env.DSH_PORT || '3080', 10);
const BACKEND_HOST = '127.0.0.1';

const LAN_TOKEN_FILE = '/root/.dsh/.lan_token';
const BRIDGE_TOKEN_FILE = '/root/.dsh/.bridge_token';

// 活动连接池（用于 Token 变更时主动秒杀旧会话）
const activeSockets = new Set();
let lastKnownToken = null;

// 动态读取有效 Token（优先读取专职 lan_token，若无则使用 bridge_token）
function getCurrentToken() {
  try {
    if (fs.existsSync(LAN_TOKEN_FILE)) {
      const t = fs.readFileSync(LAN_TOKEN_FILE, 'utf8').trim();
      if (t) return t;
    }
  } catch (e) {}
  try {
    if (fs.existsSync(BRIDGE_TOKEN_FILE)) {
      const t = fs.readFileSync(BRIDGE_TOKEN_FILE, 'utf8').trim();
      if (t) return t;
    }
  } catch (e) {}
  return '';
}

function getBridgeToken() {
  try {
    if (fs.existsSync(BRIDGE_TOKEN_FILE)) {
      return fs.readFileSync(BRIDGE_TOKEN_FILE, 'utf8').trim();
    }
  } catch (e) {}
  return getCurrentToken();
}

// 恒定时间安全比对，防时序攻击
function safeCompare(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (!a || !b) return false;
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  let diff = 0;
  for (let i = 0; i < bufA.length; i++) {
    diff |= bufA[i] ^ bufB[i];
  }
  return diff === 0;
}

// 解析 Cookie
function parseCookies(header) {
  const cookies = {};
  if (!header) return cookies;
  const pairs = header.split(';');
  for (let i = 0; i < pairs.length; i++) {
    const p = pairs[i].trim();
    const eq = p.indexOf('=');
    if (eq > 0) {
      cookies[p.substring(0, eq).trim()] = p.substring(eq + 1).trim();
    }
  }
  return cookies;
}

// 提取请求中所呈现的 Token
function extractPresentedToken(req) {
  // 1. URL Query 参数检查 (?token= 或 ?dsha_t=)
  try {
    const urlObj = new URL(req.url || '/', 'http://127.0.0.1');
    const queryToken = urlObj.searchParams.get('token') || urlObj.searchParams.get('dsha_t');
    if (queryToken) return { token: queryToken.trim(), source: 'query' };
  } catch (e) {}

  // 2. Header 检查 (X-Dsha-Token 或 X-Token)
  const headerToken = req.headers['x-dsha-token'] || req.headers['x-token'];
  if (headerToken && typeof headerToken === 'string') {
    return { token: headerToken.trim(), source: 'header' };
  }

  // 3. Cookie 检查 (dsha_lan_token= 或 dsha_t=)
  const cookies = parseCookies(req.headers.cookie);
  const cookieToken = cookies['dsha_lan_token'] || cookies['dsha_t'];
  if (cookieToken) {
    return { token: cookieToken.trim(), source: 'cookie' };
  }

  return { token: '', source: 'none' };
}

// 检查请求是否通过鉴权
function authenticateRequest(req, currentToken) {
  if (!currentToken) return { ok: true, via: 'no_token_required' };

  const presented = extractPresentedToken(req);
  if (presented.token && safeCompare(presented.token, currentToken)) {
    return { ok: true, via: presented.source, token: presented.token };
  }

  // 核心安全红线：如果提供的凭据不匹配最新 Token，不管带了什么 Cookie（如 dsh-auth-*），一律拒绝！
  return { ok: false };
}

// 检查并处理 Token 变更（强杀所有活动长连接）
function checkTokenChange() {
  const current = getCurrentToken();
  if (lastKnownToken === null) {
    lastKnownToken = current;
    return;
  }
  if (!safeCompare(lastKnownToken, current)) {
    console.log(`[DSHA LAN Proxy] 检测到 Token 已变更！主动断开 ${activeSockets.size} 个存量活动连接...`);
    lastKnownToken = current;
    for (const socket of activeSockets) {
      try {
        socket.destroy();
      } catch (e) {}
    }
    activeSockets.clear();
  }
}

// 友好未授权 HTML（401 解锁卡片）
function get401Html(reason) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>401 - DSHA 局域网访问授权</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
  .card { background: #1e293b; border: 1px solid #334155; border-radius: 16px; padding: 32px; max-width: 440px; width: 100%; box-shadow: 0 10px 25px rgba(0,0,0,0.5); text-align: center; }
  .icon { font-size: 48px; margin-bottom: 16px; }
  h2 { margin: 0 0 12px; font-size: 22px; font-weight: 600; color: #38bdf8; }
  p { margin: 0 0 20px; color: #94a3b8; font-size: 14px; line-height: 1.6; }
  .desc { background: rgba(56, 189, 248, 0.1); border: 1px solid rgba(56, 189, 248, 0.2); border-radius: 8px; padding: 10px; margin-bottom: 20px; font-size: 13px; color: #7dd3fc; }
  .input-group { display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px; }
  input { background: #0f172a; border: 1px solid #475569; border-radius: 8px; padding: 12px 16px; color: #fff; font-size: 15px; outline: none; transition: border-color 0.2s; }
  input:focus { border-color: #38bdf8; }
  button { background: #0284c7; color: white; border: none; border-radius: 8px; padding: 12px; font-size: 15px; font-weight: 600; cursor: pointer; transition: background 0.2s; }
  button:hover { background: #0369a1; }
  .tip { font-size: 12px; color: #64748b; margin-top: 16px; }
</style>
</head>
<body>
<div class="card">
  <div class="icon">🔒</div>
  <h2>DSHA 局域网访问授权</h2>
  <div class="desc">${reason || '访问此页面需要提供最新的局域网访问 Token'}</div>
  <p>请在手机 DSHA 应用首页点击「局域网访问」查看或复制最新 Token，输入解锁访问：</p>
  <div class="input-group">
    <input type="password" id="tok" placeholder="输入最新局域网 Token" autofocus />
    <button onclick="login()">验证并解锁进入</button>
  </div>
  <div class="tip">提示：也可直接在浏览器地址栏末尾加上 <code>?token=你的Token</code> 访问。</div>
</div>
<script>
function login() {
  const v = document.getElementById('tok').value.trim();
  if (!v) return alert('请输入有效 Token');
  const u = new URL(window.location.href);
  u.searchParams.set('token', v);
  window.location.href = u.toString();
}
document.getElementById('tok').addEventListener('keydown', (e) => { if (e.key === 'Enter') login(); });
</script>
</body>
</html>`;
}

// 创建 HTTP 代理服务器
const server = http.createServer((req, res) => {
  checkTokenChange();

  const currentToken = getCurrentToken();
  const bridgeToken = getBridgeToken();
  const auth = authenticateRequest(req, currentToken);

  // 1. 鉴权失败：主动清除客户端所有本地会话 Cookie 并拦截
  if (!auth.ok) {
    const cookies = parseCookies(req.headers.cookie);
    const clearCookies = [
      'dsha_lan_token=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT',
      'dsha_t=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT'
    ];
    for (const k of Object.keys(cookies)) {
      if (k.startsWith('dsh-auth-')) {
        clearCookies.push(`${k}=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT`);
      }
    }

    const isHtmlRequest = (req.headers.accept || '').includes('text/html') || req.url === '/' || req.url.startsWith('/?');
    if (isHtmlRequest) {
      res.writeHead(401, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store, no-cache, must-revalidate',
        'Set-Cookie': clearCookies
      });
      res.end(get401Html('局域网访问凭据已变更或尚未输入'));
    } else {
      res.writeHead(401, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store, no-cache, must-revalidate',
        'Set-Cookie': clearCookies
      });
      res.end(JSON.stringify({ error: 'Unauthorized', message: 'DSHA 局域网访问凭据已变更或失效，请重新提供最新 Token' }));
    }
    return;
  }

  // 2. 鉴权通过：登记活动 socket
  activeSockets.add(req.socket);
  req.socket.once('close', () => activeSockets.delete(req.socket));

  // 构造发给 3080 后端的请求头
  const headers = Object.assign({}, req.headers);
  headers['host'] = `${BACKEND_HOST}:${BACKEND_PORT}`;
  headers['x-forwarded-for'] = req.socket.remoteAddress || '127.0.0.1';
  headers['x-forwarded-proto'] = 'http';
  headers['x-forwarded-host'] = req.headers['host'] || '';

  // 注入内部 Bridge Token，让 3080 核心无条件放行并签发 Session Cookie
  if (bridgeToken) {
    headers['x-dsha-token'] = bridgeToken;
  }

  const options = {
    hostname: BACKEND_HOST,
    port: BACKEND_PORT,
    path: req.url,
    method: req.method,
    headers: headers
  };

  const proxyReq = http.request(options, (proxyRes) => {
    const resHeaders = Object.assign({}, proxyRes.headers);

    // 检查客户端是否已持有最新的 dsha_lan_token
    const clientCookies = parseCookies(req.headers.cookie);
    const needSetLanCookie = currentToken && (!clientCookies['dsha_lan_token'] || !safeCompare(clientCookies['dsha_lan_token'], currentToken));

    if (needSetLanCookie) {
      const setCookie = resHeaders['set-cookie'] || [];
      const setCookieArr = Array.isArray(setCookie) ? setCookie.slice() : [setCookie];
      setCookieArr.push(`dsha_lan_token=${currentToken}; Path=/; SameSite=Lax; Max-Age=31536000`);
      setCookieArr.push(`dsha_t=${currentToken}; Path=/; SameSite=Lax; Max-Age=31536000`);
      resHeaders['set-cookie'] = setCookieArr;
    }

    res.writeHead(proxyRes.statusCode, resHeaders);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`DSHA LAN Proxy: 无法连接本地 DSH 服务 (127.0.0.1:${BACKEND_PORT})，请确认核心服务已启动。\n错误详情: ${err.message}`);
  });

  req.pipe(proxyReq);
});

// 处理 WebSocket Upgrade
server.on('upgrade', (req, clientSocket, head) => {
  checkTokenChange();

  const currentToken = getCurrentToken();
  const bridgeToken = getBridgeToken();
  const auth = authenticateRequest(req, currentToken);

  // 鉴权未通过，立即断开 WebSocket
  if (!auth.ok) {
    clientSocket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    clientSocket.destroy();
    return;
  }

  activeSockets.add(clientSocket);
  clientSocket.once('close', () => activeSockets.delete(clientSocket));

  const backendSocket = net.connect(BACKEND_PORT, BACKEND_HOST, () => {
    let rawHeaders = `${req.method} ${req.url} HTTP/1.1\r\n`;
    const headers = Object.assign({}, req.headers);
    headers['host'] = `${BACKEND_HOST}:${BACKEND_PORT}`;
    if (bridgeToken) {
      headers['x-dsha-token'] = bridgeToken;
    }

    for (const key of Object.keys(headers)) {
      rawHeaders += `${key}: ${headers[key]}\r\n`;
    }
    rawHeaders += '\r\n';

    backendSocket.write(rawHeaders);
    if (head && head.length > 0) {
      backendSocket.write(head);
    }

    // 双向全双工转发
    backendSocket.pipe(clientSocket);
    clientSocket.pipe(backendSocket);
  });

  backendSocket.on('error', (err) => {
    clientSocket.destroy();
  });

  clientSocket.on('error', (err) => {
    backendSocket.destroy();
  });
});

// 定时 1 秒检测 Token 是否被外部修改，若修改立刻断开旧连接
setInterval(checkTokenChange, 1000);

server.listen(LAN_PORT, '0.0.0.0', () => {
  lastKnownToken = getCurrentToken();
  console.log(`[DSHA LAN Proxy] 已启动，监听 0.0.0.0:${LAN_PORT}，转发至 127.0.0.1:${BACKEND_PORT}`);
  console.log(`[DSHA LAN Proxy] 当前鉴权 Token: ${lastKnownToken ? '已加载' : '未设置(公开模式)'}`);
});
