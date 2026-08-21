#!/bin/bash
# lan-bind-patch.sh — 放行 dsh web 的 --host 0.0.0.0（局域网访问）+ /api trust fence 兜底。
# 性能优先：先尝试“常见部署路径快速命中”，命中就不做全盘扫描（启动提速）；都没命中才兜底全盘。
# 幂等：已打过则 LAN_ALREADY；全部找不到输出 LAN_UNSUPPORTED。
set -u

TARGETS=""
FAST_OK=0
# ===== 1) 快速路径（最常见部署形态直接检查，O(1)） =====
# 快速路径覆盖两种 npm 布局：
#   a) 顶层展开（老版/源码）：/usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/...
#   b) rc.8 全局嵌套（实测）：/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/...
for c in \
  /usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js \
  /usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/lib/types/startup.js \
  /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js \
  /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/types/startup.js \
  /root/deepseek-harness/packages/bundle/web-app/lib/startup.js \
  /root/deepseek-harness/packages/bundle/web-app/lib/types/startup.js; do
  if [ -f "$c" ] && grep -q "intentionally not supported" "$c"; then
    TARGETS="$TARGETS $c"
    FAST_OK=1
  fi
done

# ===== 2) 全盘兜底（仅当快速路径未命中；用拒绝文案精确定位，兼容 RC6/源码） =====
if [ "$FAST_OK" -eq 0 ]; then
  TARGETS="$TARGETS $(grep -rl "intentionally not supported" \
    /usr/local/lib/node_modules/@deepseek-ai /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai /usr/local/bin --include='*.js' 2>/dev/null | head -20)"
  for wd in /root/deepseek-harness /root/*/ ; do
    for base in "$wd"packages/bundle/web-app/lib "$wd"apps/cli/lib "$wd"node_modules/@deepseek-ai; do
      [ -d "$base" ] && TARGETS="$TARGETS $(grep -rl "intentionally not supported" "$base" \
        --include='*.js' 2>/dev/null | head -10)"
    done
  done
fi

# ===== 3) 对所有候选全量替换（不中途退出，把含拒绝文案的每一份都 patch 掉） =====
PATCHED=0
ALREADY=0
for F in $TARGETS; do
  [ -n "$F" ] && [ -f "$F" ] || continue
  if grep -q 'dsha-lan' "$F"; then ALREADY=1; continue; fi
  grep -q '0\.0\.0\.0' "$F" || continue
  # 兼容两代语法：
  #   rc.6:  if (options.host === '0.0.0.0') { program.error(...) }
  #   rc.8:  if (options.host === "0.0.0.0") program.error(...)
  sed -i \
    -e "s|if (options.host === '0.0.0.0') {|if (false) { /* dsha-lan */|" \
    -e "s|if (options.host === \"0.0.0.0\")|if (false) /* dsha-lan */|" \
    -e "s|\\.host[[:space:]]*===?[[:space:]]*['\\\"]0\\.0\\.0\\.0['\\\"]|.host === \"dsha-lan-enabled\"|g" \
    -e "s|['\\\"]0\\.0\\.0\\.0['\\\"]|\"dsha-lan-enabled\"|g" \
    "$F"
  grep -q 'dsha-lan' "$F" && PATCHED=$((PATCHED + 1))
done

# ===== 4) /api trust fence 兜底（IP 字面量 Host 放行；修复局域网 403） =====
# rc.8 有 4 处 isTrustedApiRequest 拦截（loopback interceptor/route/PRIVILEGED/route2），
# 只改一处 !isTrustedAuthority 不够。直接让 isTrustedApiRequest 恒真（LAN 全放行）：
#   function isTrustedApiRequest(...) { return true }
# 兼容旧版（只有 !isTrustedAuthority 一处）：两套 sed 都打。
# RC6/npm 路径含 dsh-client-connection；源码构建路径是 packages/client/connection（无 dsh- 前缀）
TF_LIST=$(find /usr/local/lib/node_modules /root* -maxdepth 16 \
  \( -path '*dsh-client-connection/lib/index.js' -o -path '*/packages/client/connection/lib/index.js' \) 2>/dev/null | head -6)
[ -z "$TF_LIST" ] && TF_LIST=$(find /root -maxdepth 16 \
  \( -path '*dsh-client-connection/lib/*.js' -o -path '*/packages/client/connection/lib/*.js' \) 2>/dev/null | head -10)
for TF in $TF_LIST; do
  [ -f "$TF" ] || continue
  grep -q 'dsha-lan-ip' "$TF" && { ALREADY=1; continue; }
  # 方案A（rc.8）：整个 isTrustedApiRequest 函数体替换为恒真
  if grep -q 'function isTrustedApiRequest' "$TF"; then
    sed -i -E \
      -e 's|function isTrustedApiRequest\(request, trustedHosts\) \{\n\tconst host = header\(request\.headers, "host"\);|function isTrustedApiRequest(request, trustedHosts) { return true /* dsha-lan-ip */; const host = header(request.headers, "host");|' \
      -e 's|function isTrustedApiRequest\(request, trustedHosts\) \{|function isTrustedApiRequest(request, trustedHosts) { return true /* dsha-lan-ip */;|' \
      "$TF"
  fi
  # 方案B（旧版）：!isTrustedAuthority(...) 调用点置 0
  if command -v perl >/dev/null 2>&1; then
    perl -pi -e 's/&& !isTrustedAuthority\((?:(?:[^()]|\([^()]*\))*)\)/&& 0 \/\* dsha-lan-ip \*\//g' "$TF"
  else
    sed -i -E \
      -e "s|&& !isTrustedAuthority\([^()]*\([^()]*\)[^()]*\)|&& 0 /* dsha-lan-ip */|g" \
      -e "s|&& !isTrustedAuthority\([^()]*\)|&& 0 /* dsha-lan-ip */|g" \
      "$TF"
  fi
  grep -q 'dsha-lan-ip' "$TF" && PATCHED=$((PATCHED + 1))
done

if [ "$PATCHED" -gt 0 ]; then echo "LAN_PATCHED:${PATCHED}files"; exit 0; fi
if [ "$ALREADY" -gt 0 ]; then echo "LAN_ALREADY"; exit 0; fi
echo "LAN_UNSUPPORTED"
exit 0
