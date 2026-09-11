#!/bin/bash
# dsh-token-patch.sh — 彻底打通 DSHA 局域网代理 (X-Dsha-Token)、客户端 (dsha_t) 与官方 (token)
set -u

C=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-client-connection/lib/index.js" 2>/dev/null | head -1)
if [ -z "$C" ]; then
  C=$(find /root -maxdepth 7 -path "*dsh-client-connection/lib/index.js" 2>/dev/null | head -1)
fi

if [ -z "$C" ] || [ ! -f "$C" ]; then
  echo TOKEN_PATCH_SKIP
  exit 0
fi

python3 - "$C" << 'PY'
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()

# 1. 引入 readFileSync 与 writeFileSync
if 'import { readFileSync, writeFileSync }' not in src:
    src = src.replace('import { createHash,', 'import { readFileSync, writeFileSync } from "node:fs";\nimport { createHash,', 1)

# 2. 动态捕获每次启动生成的随机 Launch Token 并临时落盘
old_plt = '''function processLaunchToken(owner) {
\tconst existing = PROCESS_LAUNCH_TOKENS.get(owner);
\tif (existing !== void 0) return existing;
\tconst created = encodeBase64Url(randomBytes(SECRET_BYTES));
\tPROCESS_LAUNCH_TOKENS.set(owner, created);
\treturn created;
}'''

new_plt = '''function processLaunchToken(owner) {
\tconst existing = PROCESS_LAUNCH_TOKENS.get(owner);
\tif (existing !== void 0) return existing;
\tconst created = encodeBase64Url(randomBytes(SECRET_BYTES));
\ttry {
\t\twriteFileSync("/root/.dsh/.launch_token", created, "utf8");
\t} catch(e) {}
\tPROCESS_LAUNCH_TOKENS.set(owner, created);
\treturn created;
}'''
if old_plt in src:
    src = src.replace(old_plt, new_plt, 1)

# 3. 恢复 Chrome 端口省略兼容
src = src.replace('new URL(origin).host === hostUrl.host', 'new URL(origin).hostname === hostUrl.hostname')

# 4. 避免本机回环时被 sec-fetch-site: cross-site 误杀 403
src = src.replace('if (header$1(request.headers, "sec-fetch-site") === "cross-site") return false;',
                  'if (!isLoopbackHostname(hostUrl.hostname) && header$1(request.headers, "sec-fetch-site") === "cross-site") return false;')

# 5. 放宽 SameSite=Strict 为 Lax（允许局域网和移动端正常传输 Cookie）
src = src.replace('HttpOnly; SameSite=Strict', 'HttpOnly; SameSite=Lax')

# 6. 【核心！打通 3081 局域网代理与 dsha_t】在 authorizeIndex 识别 X-Dsha-Token 与 dsha_t
target_auth = '\t\tconst tokens = url.searchParams.getAll(TOKEN_QUERY);'
replacement_auth = '''\t\t// [DSHA_LAN_AND_BRIDGE_AUTH] 识别局域网代理发来的 X-Dsha-Token 与 dsha_t
\t\ttry {
\t\t\tconst expected = readFileSync("/root/.dsh/.bridge_token", "utf8").trim();
\t\t\tconst headerTok = header(req.headers, "x-dsha-token");
\t\t\tconst dshaT = url.searchParams.get("dsha_t");
\t\t\tif (expected && ((headerTok && headerTok === expected) || (dshaT && dshaT === expected))) {
\t\t\t\tconst authority = requestAuthority(req.headers);
\t\t\t\tif (authority !== void 0) {
\t\t\t\t\tconst issuedAt = Date.now();
\t\t\t\t\tconst expiresAt = issuedAt + this.maxAgeMilliseconds;
\t\t\t\t\tconst value = encodeCookie({ version: COOKIE_PAYLOAD_VERSION, authority, issuedAt, expiresAt }, this.secret);
\t\t\t\t\tres.setHeader("set-cookie", [
\t\t\t\t\t\tsessionCookie(cookieName(authority), value, expiresAt, Math.floor(this.maxAgeMilliseconds / 1e3)),
\t\t\t\t\t\t"dsha_t=" + expected + "; Path=/; SameSite=Lax; Max-Age=31536000"
\t\t\t\t\t]);
\t\t\t\t\treturn true; // 局域网代理 X-Dsha-Token 验证通过，直接放行渲染，绝不报 401！
\t\t\t\t}
\t\t\t}
\t\t} catch(e) {}
\t\tconst tokens = url.searchParams.getAll(TOKEN_QUERY);'''

if 'DSHA_LAN_AND_BRIDGE_AUTH' not in src and target_auth in src:
    src = src.replace(target_auth, replacement_auth, 1)

# 7. 【核心！打通局域网代理后续 /api 请求与 WebSocket】在 isAuthenticated 识别 X-Dsha-Token
target_is_auth = '\t\tconst authority = requestAuthority(request.headers);'
replacement_is_auth = '''\t\ttry {
\t\t\tconst expected = readFileSync("/root/.dsh/.bridge_token", "utf8").trim();
\t\t\tif (expected) {
\t\t\t\tconst headerTok = header(request.headers, "x-dsha-token");
\t\t\t\tif (headerTok === expected) return true; // 局域网代理 Header 放行！
\t\t\t\tconst rawCookie = header(request.headers, "cookie") || "";
\t\t\t\tfor (const part of rawCookie.split(";")) {
\t\t\t\t\tif (part.trim() === "dsha_t=" + expected) return true;
\t\t\t\t}
\t\t\t}
\t\t} catch(e) {}
\t\tconst authority = requestAuthority(request.headers);'''

if 'headerTok === expected' not in src and target_is_auth in src:
    src = src.replace(target_is_auth, replacement_is_auth, 1)

# 8. requestRejection 增加回环与局域网代理 WebSocket 放行保障
rej_idx = src.find("requestRejection(request) {")
if rej_idx != -1 and 'request.headers?.upgrade' not in src:
    rej_end = src.find("\n\t}", rej_idx) + 3
    new_rej = """requestRejection(request) {
\t\tif (!isTrustedApiRequest(request, this.trustedHosts)) return 403;
\t\tif (this.browserAuth.isAuthenticated(request)) return void 0;
\t\tif (request.headers?.upgrade?.toLowerCase() === "websocket") {
\t\t\tconst host = header$1(request.headers, "host");
\t\t\tconst hostUrl = host ? parseAuthority(host) : void 0;
\t\t\tif (hostUrl && isLoopbackHostname(hostUrl.hostname)) return void 0;
\t\t}
\t\treturn 401;
\t}"""
    src = src[:rej_idx] + new_rej + src[rej_end:]

open(path, 'w', encoding='utf-8').write(src)
PY

echo TOKEN_PATCH_OK
