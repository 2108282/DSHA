#!/bin/bash
# WebUI 老浏览器兼容补丁：AbortSignal.any / AbortSignal.timeout（Chrome 116+ / Safari 17.4+ 才有）
# 在浏览器端 bundle 头部注入 polyfill（幂等：已注入则跳过）。
# 老版本 Android System WebView 没有这两个 API，dsh 前端在选择工作区等
# 带取消的 remote 调用上会抛 "AbortSignal.any is not a function"。
POLY=$(cat <<'PLY'
if (typeof AbortSignal !== 'undefined' && !AbortSignal.any) {
  AbortSignal.any = function (signals) {
    var ctrl = new AbortController();
    var done = false;
    var onAbort = function () { if (!done) { done = true; ctrl.abort(ctrl.signal.reason); } };
    signals = signals || [];
    for (var i = 0; i < signals.length; i++) {
      var s = signals[i];
      if (s) { if (s.aborted) { onAbort(); } else { s.addEventListener('abort', onAbort, { once: true }); } }
    }
    return ctrl.signal;
  };
}
if (typeof AbortSignal !== 'undefined' && !AbortSignal.timeout) {
  AbortSignal.timeout = function (ms) {
    var ctrl = new AbortController();
    setTimeout(function () { ctrl.abort(new DOMException('Timeout', 'TimeoutError')); }, ms);
    return ctrl.signal;
  };
}
PLY
)

inject() {
  F="$1"
  [ -n "$F" ] && [ -f "$F" ] || return 0
  if grep -q 'AbortSignal.any = function' "$F"; then
    echo "跳过（已注入）: $F"
    return 0
  fi
  { echo "$POLY"; cat "$F"; } > "$F.new" && mv "$F.new" "$F"
  echo "已注入 polyfill: $F"
}

# ===== 1) 快速路径（最常见部署形态直接命中，O(1)） =====
for F in \
  $(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-client-connection/lib/client.js' 2>/dev/null | head -1) \
  $(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-api-gateway/lib/client.js' 2>/dev/null | head -1) \
  /root/deepseek-harness/packages/client/connection/lib/client.js \
  /root/deepseek-harness/packages/api/gateway/lib/client.js; do
  inject "$F"
done

# ===== 2) 全量兜底（dsh 版本/路径变化时仍能命中） =====
# 快速路径未命中或未覆盖时，扫描所有 @deepseek-ai 编译产物与源码树里
# 实际调用 AbortSignal.any/timeout 的 JS 文件，逐个幂等注入。
FALLBACK=$(grep -rlE 'AbortSignal\.(any|timeout)' \
  /usr/local/lib/node_modules/@deepseek-ai /root/deepseek-harness/packages \
  --include='*.js' 2>/dev/null | head -40)
for F in $FALLBACK; do
  inject "$F"
done

echo "POLYFILL_DONE"
