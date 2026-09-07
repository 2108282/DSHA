#!/bin/bash
# dsh-token-patch.sh — 修复 deepseek-harness 0.1.2+ 新增的随机 Launch Token
# 与 DSHA 客户端固定 Bridge Token (?dsha_t=...) 的鉴权冲突。
#
# 根因：
#   1. 上游 dsh 0.1.2+ 在 dsh-client-connection 中引入了进程级 launchToken，
#      authorizeIndex 要求 GET / 必须带 ?token=<launchToken> 才能换取签名 Cookie。
#   2. DSHA 客户端 App 仍按旧版协议访问 ?dsha_t=<bridge_token>，导致官方层直接返回 401。
#   3. 修复方案：在 authorizeIndex 增加对 dsha_t 的识别，校验与 /root/.dsh/.bridge_token
#      一致时，同样签发官方 Cookie 并下发。
#
# 幂等：已打过补丁输出 TOKEN_PATCH_ALREADY；修复完成输出 TOKEN_PATCH_OK。
set -u

C=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-client-connection/lib/index.js" 2>/dev/null | head -1)
if [ -z "$C" ]; then
  C=$(find /root -maxdepth 7 -path "*dsh-client-connection/lib/index.js" 2>/dev/null | head -1)
fi

if [ -z "$C" ] || [ ! -f "$C" ]; then
  echo TOKEN_PATCH_SKIP
  exit 0
fi

if grep -q 'DSHA_BRIDGE_AUTH' "$C"; then
  echo TOKEN_PATCH_ALREADY
  exit 0
fi

python3 - "$C" <<'PY'
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()

import_target = 'import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";'
import_replacement = 'import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";\nimport { readFileSync } from "node:fs";'

if 'import { readFileSync } from "node:fs";' not in src:
    if import_target in src:
        src = src.replace(import_target, import_replacement, 1)
    else:
        print("IMPORT_TARGET_MISS")
        sys.exit(2)

target = '\t\tconst tokens = url.searchParams.getAll(TOKEN_QUERY);'
replacement = '''\t\t// [DSHA_BRIDGE_AUTH] 兼容 DSHA 客户端传来的 ?dsha_t= 鉴权
\t\tconst dshaT = url.searchParams.get("dsha_t");
\t\tif (dshaT) {
\t\t\ttry {
\t\t\t\tconst expectedToken = readFileSync("/root/.dsh/.bridge_token", "utf8").trim();
\t\t\t\tif (expectedToken && dshaT === expectedToken) {
\t\t\t\t\tconst authority = requestAuthority(req.headers);
\t\t\t\t\tif (req.method === "GET" && url.pathname === "/" && authority !== void 0) {
\t\t\t\t\t\tconst issuedAt = Date.now();
\t\t\t\t\t\tconst expiresAt = issuedAt + this.maxAgeMilliseconds;
\t\t\t\t\t\tconst value = encodeCookie({
\t\t\t\t\t\t\tversion: COOKIE_PAYLOAD_VERSION,
\t\t\t\t\t\t\tauthority,
\t\t\t\t\t\t\tissuedAt,
\t\t\t\t\t\t\texpiresAt
\t\t\t\t\t\t}, this.secret);
\t\t\t\t\t\tres.writeHead(303, {
\t\t\t\t\t\t\t"cache-control": "no-store",
\t\t\t\t\t\t\t"location": "/",
\t\t\t\t\t\t\t"referrer-policy": "no-referrer",
\t\t\t\t\t\t\t"set-cookie": [
\t\t\t\t\t\t\t\tsessionCookie(cookieName(authority), value, expiresAt, Math.floor(this.maxAgeMilliseconds / 1e3)),
\t\t\t\t\t\t\t\t"dsha_t=" + expectedToken + "; Path=/; SameSite=Strict; Max-Age=31536000"
\t\t\t\t\t\t\t]
\t\t\t\t\t\t});
\t\t\t\t\t\tres.end();
\t\t\t\t\t\treturn false;
\t\t\t\t\t}
\t\t\t\t}
\t\t\t} catch (e) {}
\t\t}
\t\tconst tokens = url.searchParams.getAll(TOKEN_QUERY);'''

if target not in src:
    print("AUTHORIZE_INDEX_TARGET_MISS")
    sys.exit(3)

src = src.replace(target, replacement, 1)
open(path, 'w', encoding='utf-8').write(src)
print("PATCHED")
PY

if [ $? -eq 0 ]; then
  echo TOKEN_PATCH_OK
else
  echo TOKEN_PATCH_FAIL
fi
