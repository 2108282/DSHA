#!/bin/bash
# dsh-token-patch.sh — 适配 deepseek-harness 0.1.2 ~ 0.1.5-alpha 官方 Launch Token
# 动态捕获落盘 + 解决移动端浏览器长连断开。
set -u

C=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-client-connection/lib/index.js" 2>/dev/null | head -1)
if [ -z "$C" ]; then
  C=$(find /root -maxdepth 7 -path "*dsh-client-connection/lib/index.js" 2>/dev/null | head -1)
fi

if [ -z "$C" ] || [ ! -f "$C" ]; then
  echo TOKEN_PATCH_SKIP
  exit 0
fi

python3 - "$C" <<'PY'
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()

# 1. 引入 writeFileSync
if 'import { writeFileSync }' not in src:
    src = src.replace('import { createHash,', 'import { writeFileSync } from "node:fs";\nimport { createHash,', 1)

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

# 5. 放宽 SameSite=Strict 为 Lax（解决移动端建立 WebSocket 时被拦截）
src = src.replace('HttpOnly; SameSite=Strict', 'HttpOnly; SameSite=Lax')

# 6. 对本机同源 WebSocket Upgrade 增加放行保障
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

# 创建快捷查看命令: dsh-url
cat << 'EOF2' > /usr/local/bin/dsh-url
#!/bin/bash
TOKEN_FILE="/root/.dsh/.launch_token"
if [ -f "$TOKEN_FILE" ] && [ -s "$TOKEN_FILE" ]; then
    TOK=$(cat "$TOKEN_FILE" | tr -d '\r\n')
    echo "=================================================="
    echo "当前本次启动 DSH Web 官方最新完整访问地址:"
    echo "http://127.0.0.1:3080/?token=$TOK"
    echo "=================================================="
else
    echo "DSH 尚未启动或未生成 Launch Token，请在 App 启动页重启服务后再试。"
fi
EOF2
chmod +x /usr/local/bin/dsh-url 2>/dev/null || true

echo TOKEN_PATCH_OK
