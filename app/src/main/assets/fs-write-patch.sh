#!/bin/bash
# DSHA：修「dsh write 工具新建文件变悬空链接」（用户实测 3/3 复现）。
#
# 根因：dsh 新建文件的发布动作是 link(临时文件, 目标)，随后递归删掉临时目录；
# 而 proot 的 --link2symlink 把 link() 模拟成「目标 → 临时目录内 .l2s 中间文件」的
# 符号链接，临时目录一删，新建的文件立刻成为悬空链接（工具报成功，文件读不出来）。
#
# 修法：目标不存在时改用 rename 发布（新建场景，绕开 link）；目标已存在时仍走
# 原来的 link，保留「不覆盖并发创建者的文件」这层语义。
# 幂等：打过标记就跳过；语法校验不过自动回滚。
set -u
LOG=/root/.dsh/fs-write-patch.log
mkdir -p /root/.dsh 2>/dev/null || true

F=""
# 常见布局先直查（rootfs 里 node_modules 巨大，全目录 find 要几十秒，会拖慢启动自愈）
for P in \
  /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js \
  /usr/local/lib/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js ; do
  if [ -f "$P" ]; then F="$P"; break; fi
done
if [ -z "$F" ]; then
  F=$(find /usr/local/lib/node_modules/@deepseek-ai -maxdepth 5 -path '*dsh-fs-local/lib/index.js' 2>/dev/null | head -1)
fi
if [ -z "$F" ]; then
  F=$(find /root -maxdepth 6 -path '*/dsh-fs-local/lib/index.js' 2>/dev/null | head -1)
fi
if [ -z "$F" ] || [ ! -f "$F" ]; then
  echo "[$(date '+%F %T')] 未找到 dsh-fs-local/lib/index.js，跳过" >> "$LOG"
  echo FS_PATCH_SKIP
  exit 0
fi

if grep -q 'DSHA_L2S_FIX' "$F"; then
  echo FS_PATCH_ALREADY
  exit 0
fi

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
  echo "[$(date '+%F %T')] 打补丁失败 rc=$rc（目标字符串可能随 dsh 版本变化）" >> "$LOG"
  [ -f "$F.dsha-bak" ] && cp -f "$F.dsha-bak" "$F"
  echo FS_PATCH_FAIL
  exit 0
fi

# 语法校验：index.js 是 ESM，必须以 .mjs 复制后再 --check（否则按 CJS 解析必报错）
cp -f "$F" /tmp/dsha-fs-check.mjs 2>/dev/null
if ! node --check /tmp/dsha-fs-check.mjs 2>>"$LOG"; then
  echo "[$(date '+%F %T')] 语法校验未通过，已回滚" >> "$LOG"
  [ -f "$F.dsha-bak" ] && cp -f "$F.dsha-bak" "$F"
  rm -f /tmp/dsha-fs-check.mjs
  echo FS_PATCH_ROLLBACK
  exit 0
fi
rm -f /tmp/dsha-fs-check.mjs

echo "[$(date '+%F %T')] 已打 write 发布补丁：$F" >> "$LOG"
echo FS_PATCH_OK
