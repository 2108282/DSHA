#!/usr/bin/env node
/**
 * DSHA 局域网反向代理守护服务 (DSHA LAN Proxy Daemon)
 * 监听 0.0.0.0:3081 -> 转发至 127.0.0.1:3080
 * 由核心模块常驻/按需拉起，具备：
 * 1. 毫秒级免重启热读取 Token
 * 2. 自动注入 X-Dsha-Token 与 Host 重写，确保 DSH 官方鉴权 100% 放行
 * 3. 完整支持 HTTP 与 WebSocket (Terminal / Stream / EventSource)
 * 4. 友好的 401 交互鉴权页（支持在浏览器直接输入 Token 登录）
 */

const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');

const LAN_PORT = parseInt(process.env.LAN_PORT || '3081', 10);
const BACKEND_PORT = parseInt(process.env.DSH_PORT || '3080', 10);
const BACKEND_HOST = '127.0.0.1';

const TOKEN_FILE = '/root/.dsh/.lan_token';
const BRIDGE_TOKEN_FILE = '/root/.dsh/.bridge_token';

// 动态读取有效 Token（优先读取 lan_token，若无则使用 bridge_token）
function getCurrentToken() {
  try {
    if (fs.existsSync(TOKEN_FILE)) {
      const t = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
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

// 检查请求是否通过鉴权
function authenticateRequest(req, currentToken) {
  if (!currentToken) return { ok: true, via: 'no_token_required' };

  // 1. URL Query 参数检查 (?token= 或 ?dsha_t=)
  const urlObj = new URL(req.url || '/', 'http://127.0.0.1');
  const queryToken = urlObj.searchParams.get('token') || urlObj.searchParams.get('dsha_t');
  if (queryToken && safeCompare(queryToken, currentToken)) {
    return { ok: true, via: 'query', token: queryToken };
  }

  // 2. Header 检查 (X-Dsha-Token 或 X-Token)
  const headerToken = req.headers['x-dsha-token'] || req.headers['x-token'];
  if (headerToken && safeCompare(headerToken, currentToken)) {
    return { ok: true, via: 'header', token: headerToken };
  }

  // 3. Cookie 检查 (dsha_t= 或 dsh-auth-)
  const cookies = parseCookies(req.headers.cookie);
  if (cookies['dsha_t'] && safeCompare(cookies['dsha_t'], currentToken)) {
    return { ok: true, via: 'cookie', token: cookies['dsha_t'] };
  }

  // 允许已经由 3080 签发的官方 dsh-auth 会话 Cookie 通过
  for (const k of Object.keys(cookies)) {
    if (k.startsWith('dsh-auth-') && cookies[k]) {
      return { ok: true, via: 'dsh_auth_cookie' };
    }
  }

  return { ok: false };
}

// 友好未授权 HTML
function get401Html() {
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
  p { margin: 0 0 24px; color: #94a3b8; font-size: 14px; line-height: 1.6; }
  .input-group { display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px; }
  input { background: #0f172a; border: 1px solid #475569; border-radius: 8px; padding: 12px 16px; color: #fff; font-size: 15px; outline: none; }
  input:focus { border-color: #38bdf8; }
  button { background: #0284c7; color: white; border: none; border-radius: 8px; padding: 12px; font-size: 15px; font-weight: 600; cursor: pointer; transition: background 0.2s; }
  button:hover { background: #0369a1; }
  .tip { font-size: 12px; color: #64748b; margin-top: 16px; }
</style>
</head>
<body>
<div class="card">
  <div class="icon">🔒</div>
  <h2>DSHA 局域网访问受保护</h2>
  <p>当前连接来自局域网。请在手机 DSHA 应用首页点击「局域网访问」查看 Token，或在下方输入 Token 解锁访问：</p>
  <div class="input-group">
    <input type="password" id="tok" placeholder="输入局域网访问 Token / 凭据" autofocus />
    <button onclick="login()">解锁进入 DSH</button>
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
  const currentToken = getCurrentToken();
  const bridgeToken = getBridgeToken();
  const auth = authenticateRequest(req, currentToken);

  if (!auth.ok) {
    res.writeHead(401, {
      'Content-Type': 'text/html; charset=utf-8',
      'WWW-Authenticate': 'Bearer error="invalid_token"'
    });
    res.end(get401Html());
    return;
  }

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
    // 复制响应头
    const resHeaders = Object.assign({}, proxyRes.headers);

    // 如果是通过 query 或 header 首次带有效 token 访问，且后端尚未设 dsha_t cookie，补充种植 dsha_t cookie
    if (currentToken && (auth.via === 'query' || auth.via === 'header')) {
      const setCookie = resHeaders['set-cookie'] || [];
      const setCookieArr = Array.isArray(setCookie) ? setCookie : [setCookie];
      let hasDshaT = false;
      for (const sc of setCookieArr) {
        if (sc.includes('dsha_t=')) { hasDshaT = true; break; }
      }
      if (!hasDshaT) {
        setCookieArr.push(`dsha_t=${currentToken}; Path=/; SameSite=Lax; Max-Age=31536000`);
        resHeaders['set-cookie'] = setCookieArr;
      }
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
  const currentToken = getCurrentToken();
  const bridgeToken = getBridgeToken();
  const auth = authenticateRequest(req, currentToken);

  if (!auth.ok) {
    clientSocket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    clientSocket.destroy();
    return;
  }

  const backendSocket = net.connect(BACKEND_PORT, BACKEND_HOST, () => {
    // 重写并构造发往后端的 Upgrade 请求行和头部
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

server.listen(LAN_PORT, '0.0.0.0', () => {
  console.log(`[DSHA LAN Proxy] 已启动，监听 0.0.0.0:${LAN_PORT}，转发至 127.0.0.1:${BACKEND_PORT}`);
  console.log(`[DSHA LAN Proxy] 当前鉴权 Token: ${getCurrentToken() ? '已加载' : '未设置(公开模式)'}`);
});
