#!/bin/bash
# DSHA：修「proot 下用 link() 发布文件 → 目标变悬空链接」。
#
# 根因：Android 私有目录禁止真硬链接（link() 直接 AccessDeniedException），
# 所以 proot 必须开 --link2symlink，把 link() 模拟成「目标 → 源附近的 .l2s 中间文件」
# 的符号链接。而 dsh 有两处都用 link(临时文件, 目标) 做原子发布、发布后就删临时目录，
# 于是目标立刻悬空：写的时候一切正常，读的时候 ENOENT。
#
# 两处受害者：
#   1. dsh-fs-local            → agent 的 write 工具新建文件后读不出来
#   2. dsh-session-persistence-jsonl → 会话日志发布后即失效，表现为
#      「本轮运行失败 ENOENT: ... session.jsonl.zstd」
#
# 修法一致：目标不存在时改用 rename 原子发布（新建场景，绕开 link）；
# 目标已存在时仍走 link，保留「不覆盖并发创建者」的语义。
# 幂等：打过标记就跳过；语法校验不过自动回滚。
set -u
LOG=/root/.dsh/fs-write-patch.log
mkdir -p /root/.dsh 2>/dev/null || true

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

# ESM 语法校验：index.js 是 ESM，必须以 .mjs 复制后再 --check（否则按 CJS 解析必报错）
check_syntax() {
  cp -f "$1" /tmp/dsha-syn-check.mjs 2>/dev/null || return 1
  node --check /tmp/dsha-syn-check.mjs 2>>"$LOG"
  rc=$?
  rm -f /tmp/dsha-syn-check.mjs
  return $rc
}

# 在常见布局里找包文件（rootfs 的 node_modules 巨大，全目录 find 要几十秒，会拖慢启动自愈）
locate_pkg() {
  sub="$1"
  for P in \
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/$sub/lib/index.js" \
    "/usr/local/lib/node_modules/@deepseek-ai/$sub/lib/index.js" ; do
    if [ -f "$P" ]; then echo "$P"; return 0; fi
  done
  R=$(find /usr/local/lib/node_modules/@deepseek-ai -maxdepth 5 -path "*$sub/lib/index.js" 2>/dev/null | head -1)
  [ -n "$R" ] && { echo "$R"; return 0; }
  R=$(find /root -maxdepth 6 -path "*/$sub/lib/index.js" 2>/dev/null | head -1)
  [ -n "$R" ] && { echo "$R"; return 0; }
  return 1
}

# ============ ① agent 的 write 工具（dsh-fs-local） ============
F=$(locate_pkg dsh-fs-local)
if [ -z "${F:-}" ] || [ ! -f "${F:-}" ]; then
  log "未找到 dsh-fs-local/lib/index.js，跳过"
  echo FS_PATCH_SKIP
elif grep -q 'DSHA_L2S_FIX' "$F"; then
  echo FS_PATCH_ALREADY
else
  cp -f "$F" "$F.dsha-bak" 2>/dev/null || true
  python3 - "$F" <<'PY'
import sys
path = sys.argv[1]
src = open(path, encoding='utf-8').read()
old = "await linkFile(tempPath, absolutePath);"
new = ("/* DSHA_L2S_FIX */ await (async () => { "
       "const exists = await lstat(absolutePath).catch(() => null); "
       "if (exists) { await linkFile(tempPath, absolutePath); } "
       "else { await rename(tempPath, absolutePath); } })();")
if old not in src:
    print("PATTERN_MISS")
    sys.exit(3)
open(path, 'w', encoding='utf-8').write(src.replace(old, new, 1))
print("PATCHED")
PY
  rc=$?
  if [ "$rc" -ne 0 ]; then
    log "write 补丁失败 rc=$rc（目标字符串可能随 dsh 版本变化）"
    [ -f "$F.dsha-bak" ] && cp -f "$F.dsha-bak" "$F"
    echo FS_PATCH_FAIL
  elif ! check_syntax "$F"; then
    log "write 补丁语法校验未通过，已回滚"
    [ -f "$F.dsha-bak" ] && cp -f "$F.dsha-bak" "$F"
    echo FS_PATCH_ROLLBACK
  else
    log "已打 write 发布补丁：$F"
    echo FS_PATCH_OK
  fi
fi

# ============ ② 会话日志发布（dsh-session-persistence-jsonl） ============
# 这一处才是「本轮运行失败 ENOENT ... session.jsonl.zstd」的根因：
# 会话日志用 link(tmp, finalPath) 发布，proot 下发布完就悬空，下一次读会话直接失败。
S=$(locate_pkg dsh-session-persistence-jsonl)
if [ -z "${S:-}" ] || [ ! -f "${S:-}" ]; then
  log "未找到 dsh-session-persistence-jsonl/lib/index.js，跳过"
  echo SESSION_PATCH_SKIP
  exit 0
fi
if grep -q 'DSHA_L2S_FIX2' "$S"; then
  echo SESSION_PATCH_ALREADY
  exit 0
fi

cp -f "$S" "$S.dsha-bak" 2>/dev/null || true

python3 - "$S" <<'PY'
import re
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()
CALL = "await link(tmp, finalPath);"
if CALL not in src:
    print("PATTERN_MISS")
    sys.exit(3)

# 1) 补 import：existsSync 来自 node:fs，rename 来自 node:fs/promises
m = re.search(r'^import \{([^}]*)\} from "node:fs";', src, re.M)
if m:
    if 'existsSync' not in m.group(1):
        src = src[:m.start(1)] + " existsSync," + m.group(1) + src[m.end(1):]
else:
    src = 'import { existsSync } from "node:fs";\n' + src

m2 = re.search(r'^import \{([^}]*)\} from "node:fs/promises";', src, re.M)
if not m2:
    print("NO_FS_PROMISES_IMPORT")
    sys.exit(4)
if 'rename' not in m2.group(1):
    src = src[:m2.start(1)] + " rename," + m2.group(1) + src[m2.end(1):]

# 2) 注入发布 helper（模块级，放在最后一条 import 之后）
helper = '''
/* DSHA_L2S_FIX2 —— proot --link2symlink 下 link() 会让目标变成悬空 symlink
   （Android 私有目录禁硬链接）。dsh 用 link(tmp, finalPath) 发布会话日志，
   发布后临时文件一清，目标即失效，表现为 ENOENT: open '...session.jsonl.zstd'。
   目标不存在 → rename 原子发布；已存在 → 仍走 link，保留 EEXIST 并发语义。 */
async function __dshaPublishLog(tmp, finalPath) {
  if (existsSync(finalPath)) return link(tmp, finalPath);
  try {
    return await rename(tmp, finalPath);
  } catch (e) {
    if (e && e.code === "EXDEV") return link(tmp, finalPath);
    throw e;
  }
}
'''
lines = src.split('\n')
last_import = -1
for i, line in enumerate(lines):
    if line.startswith('import '):
        last_import = i
if last_import < 0:
    print("NO_IMPORT_BLOCK")
    sys.exit(5)
lines.insert(last_import + 1, helper)
src = '\n'.join(lines)

# 3) 换掉调用点
src = src.replace(CALL, "await __dshaPublishLog(tmp, finalPath);", 1)
open(path, 'w', encoding='utf-8').write(src)
print("PATCHED")
PY
rc=$?

if [ "$rc" -ne 0 ]; then
  log "会话发布补丁失败 rc=$rc（目标字符串可能随 dsh 版本变化）"
  [ -f "$S.dsha-bak" ] && cp -f "$S.dsha-bak" "$S"
  echo SESSION_PATCH_FAIL
  exit 0
fi
if ! check_syntax "$S"; then
  log "会话发布补丁语法校验未通过，已回滚"
  [ -f "$S.dsha-bak" ] && cp -f "$S.dsha-bak" "$S"
  echo SESSION_PATCH_ROLLBACK
  exit 0
fi

log "已打会话发布补丁：$S"
echo SESSION_PATCH_OK
