#!/bin/bash
# dsh-token-patch.sh — 适配 deepseek-harness 0.1.2 ~ 0.1.5-alpha 的官方 Launch Token
# 动态捕获、地址文件同步与移动端浏览器长连断开修复。
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

if 'import { writeFileSync }' not in src:
    src = src.replace('import { createHash,', 'import { writeFileSync } from "node:fs";\nimport { createHash,', 1)

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

src = src.replace('new URL(origin).host === hostUrl.host', 'new URL(origin).hostname === hostUrl.hostname')
src = src.replace('if (header$1(request.headers, "sec-fetch-site") === "cross-site") return false;',
                  'if (!isLoopbackHostname(hostUrl.hostname) && header$1(request.headers, "sec-fetch-site") === "cross-site") return false;')
src = src.replace('HttpOnly; SameSite=Strict', 'HttpOnly; SameSite=Lax')

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

W=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-web-app/lib/index.js" 2>/dev/null | head -1)
if [ -n "$W" ] && [ -f "$W" ]; then
  python3 - "$W" <<'PY2'
import sys
path = sys.argv[1]
src = open(path, encoding='utf-8').read()
if 'import { writeFileSync }' not in src:
    src = src.replace('import { createRequire }', 'import { writeFileSync, mkdirSync } from "node:fs";\nimport { createRequire }', 1)
old_log = 'if (config.printUrl) console.log(`dsh web: ${authenticatedUrl}${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);'
new_log = '''try {
\t\t\tmkdirSync("/root/.dsh", { recursive: true });
\t\t\twriteFileSync("/root/.dsh/web_url.txt", authenticatedUrl + "\\n", "utf8");
\t\t} catch(e) {}
\t\tif (config.printUrl) console.log(`dsh web: ${authenticatedUrl}${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);'''
if old_log in src:
    src = src.replace(old_log, new_log, 1)
    open(path, 'w', encoding='utf-8').write(src)
PY2
fi

cat << 'EOF2' > /usr/local/bin/dsh-url
#!/bin/bash
URL_FILE="/root/.dsh/web_url.txt"
TOKEN_FILE="/root/.dsh/.launch_token"
if [ -f "$URL_FILE" ] && [ -s "$URL_FILE" ]; then
    URL=$(cat "$URL_FILE" | tr -d '\r\n')
    echo "=================================================="
    echo "当前本次启动 DSH Web 官方最新完整访问地址:"
    echo "$URL"
    echo "=================================================="
elif [ -f "$TOKEN_FILE" ] && [ -s "$TOKEN_FILE" ]; then
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
