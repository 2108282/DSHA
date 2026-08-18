#!/bin/bash
# lan-bind-patch.sh — 放行 dsh web 的 --host 0.0.0.0（局域网访问）。
# deepseek-harness 官方 CLI 出于安全会 program.error 拒绝 0.0.0.0，
# 底层 webServer 本就支持 0.0.0.0 绑定，这里仅把 CLI 层的拦截移除。
#
# 兼容多条安装线路与多个版本写法：
#   RC6 全局包 : /usr/local/lib/node_modules/@deepseek-ai/dsh/.../dsh-web-app/lib/startup.js
#   源码构建   : /root/<workdir>/packages/bundle/web-app/lib/startup.js
#   写法差异   : 旧版单引号+大括号  if (options.host === '0.0.0.0') {
#                新版双引号+无大括号  if (options.host === "0.0.0.0") program.error(...)
#
# 幂等：已打过补丁则 LAN_ALREADY；找不到模块输出 LAN_UNSUPPORTED（不视为失败）。
set -u

C1=/usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js
C2=/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js
C3=$(find /usr/local/lib/node_modules /root -maxdepth 10 -path '*dsh-web-app/lib/startup.js' 2>/dev/null | head -1)

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

# 仅移除 CLI 对 0.0.0.0 的拒绝分支（不改变其他行为）；兼容单/双引号与有无大括号
sed -i \
  -e "s|if (options.host === '0.0.0.0') {|if (false) { /* dsha-lan */|" \
  -e "s|if (options.host === \"0.0.0.0\")|if (false) /* dsha-lan */|" \
  "$F"

if grep -q 'dsha-lan' "$F"; then
  echo LAN_PATCHED
else
  echo LAN_PATCH_FAIL
fi
