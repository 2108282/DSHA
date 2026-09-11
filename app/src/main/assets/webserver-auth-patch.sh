#!/bin/bash
# DSHA: dsh webserver auth patch, support both ?token= and ?dsha_t=
set -u
LOG=/root/.dsh/webserver-auth-patch.log
mkdir -p /root/.dsh 2>/dev/null || true

W=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-host-webserver/lib/index.js" 2>/dev/null | head -1)
if [ -z "$W" ]; then
  W=$(find /root -maxdepth 7 -path "*dsh-host-webserver/lib/index.js" 2>/dev/null | head -1)
fi
if [ -z "$W" ] || [ ! -f "$W" ]; then
  echo WEBAUTH_SKIP
  exit 0
fi

if grep -q 'url.includes("token=")' "$W"; then
  echo WEBAUTH_ALREADY
  exit 0
fi

cp -f "$W" "$W.dsha-bak" 2>/dev/null || true

python3 - "$W" << 'PY'
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()

FN = """
const __DSHA_TOKEN_PATH = "/root/.dsh/.bridge_token";
let __dshaTokenCache;
const __dshaToken = () => {
  if (__dshaTokenCache !== undefined) return __dshaTokenCache;
  try {
    __dshaTokenCache = __dshaReadFileSync(__DSHA_TOKEN_PATH, "utf8").trim();
  } catch {
    __dshaTokenCache = "";
  }
  return __dshaTokenCache;
};
const __dshaAuthOk = (req, res) => {
  const tok = __dshaToken();
  const url = req.url || "/";

  // Official Launch Token
  if (url.includes("token=")) return true;

  // DSHA Bridge Token
  const qi = url.indexOf("dsha_t=");
  if (qi >= 0) {
    const got = decodeURIComponent(url.slice(qi + 7).split("&")[0].split("#")[0]);
    if (!tok || got === tok) {
      try {
        res?.setHeader?.("Set-Cookie",
          "dsha_t=" + got + "; Path=/; SameSite=Lax; Max-Age=31536000");
      } catch {}
      return true;
    }
  }

  // Cookie
  const cookie = req.headers?.cookie || "";
  for (const part of cookie.split(";")) {
    const p = part.trim();
    if ((tok && p === "dsha_t=" + tok) || p.startsWith("dsh-auth-")) return true;
  }

  // Header
  if (tok && req.headers?.["x-dsha-token"] === tok) return true;

  if (!tok) return true;
  return false;
};
"""

if '__dshaReadFileSync' not in src:
    src = src.replace('import { createServer } from "node:http";',
                      'import { createServer } from "node:http";\nimport { readFileSync as __dshaReadFileSync } from "node:fs";\n' + FN)

HANDLE = 'const handle = async (req, res) => {'
if HANDLE in src and '__dshaAuthOk(req, res)' not in src:
    src = src.replace(
        HANDLE,
        HANDLE + "\n\t\t\tif (!__dshaAuthOk(req, res)) {\n\t\t\t\tres.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });\n\t\t\t\tres.end('DSHA: 需要 token。请在 DSHA 应用内打开，或在 URL 后加 ?dsha_t=<token>');\n\t\t\t\treturn;\n\t\t\t}", 1)

UP = "this.server.on('upgrade', (req, socket, head) => {"
if UP not in src:
    UP = 'this.server.on("upgrade", (req, socket, head) => {'
if UP in src and '__dshaAuthOk(req, undefined)' not in src:
    src = src.replace(
        UP,
        UP + "\n\t\t\tif (!__dshaAuthOk(req, undefined)) {\n\t\t\t\tsocket.destroy();\n\t\t\t\treturn;\n\t\t\t}", 1)

open(path, 'w', encoding='utf-8').write(src)
print("PATCHED")
PY

if [ $? -eq 0 ]; then
  echo WEBAUTH_OK
else
  [ -f "$W.dsha-bak" ] && cp -f "$W.dsha-bak" "$W"
  echo WEBAUTH_FAIL
fi
