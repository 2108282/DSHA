#!/bin/bash
# lan-bind-patch.sh — 放行 dsh web 的 --host 0.0.0.0（局域网访问）。
# deepseek-harness 官方 CLI startupt.ts 出于安全会 program.error 拒绝 0.0.0.0，
# 但底层 webServer（@deepseek-ai/dsh-host-webserver）本就支持 0.0.0.0 绑定，
# 这里仅把 CLI 层的拦截移除，让 --host 0.0.0.0 真正生效。
#
# 兼容两条安装线路：
#   RC6   ：/usr/local/lib/node_modules/@deepseek-ai/dsh/.../dsh-web-app/lib/types/startup.js
#   源码  ：/root/<workdir>/packages/bundle/web-app/lib/types/startup.js
#
# 幂等：已打过补丁则直接 LAN_ALREADY；找不到模块输出 LAN_UNSUPPORTED（不视为失败）。
set -u

C1=/usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/lib/types/startup.js
C2=/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/types/startup.js
C3=$(find /root -maxdepth 8 -path '*/dsh-web-app/lib/types/startup.js' 2>/dev/null | head -1)

F=""
for c in "$C1" "$C2" "$C3"; do
  if [ -n "$c" ] && [ -f "$c" ]; then
    F="$c"
    break
  fi
done

if [ -z "$F" ]; then
  echo LAN_UNSUPPORTED
  exit 0
fi

if grep -q 'dsha-lan' "$F"; then
  echo LAN_ALREADY
  exit 0
fi

# 仅移除 CLI 对 0.0.0.0 的拒绝分支（不改变其他行为）
sed -i "s|if (options.host === '0.0.0.0') {|if (false) { /* dsha-lan */|" "$F"

if grep -q 'dsha-lan' "$F"; then
  echo LAN_PATCHED
else
  echo LAN_PATCH_FAIL
fi
