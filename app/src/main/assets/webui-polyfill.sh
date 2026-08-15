#!/bin/bash
# WebUI 老浏览器兼容补丁：AbortSignal.any / AbortSignal.timeout（Chrome 118+ / Safari 17.4+ 才有）
# 在浏览器端 bundle 头部注入 polyfill（幂等：已注入则跳过）
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

for F in \
  /root/deepseek-harness/packages/client/connection/lib/client.js \
  /root/deepseek-harness/packages/api/gateway/lib/client.js; do
  if [ -f "$F" ] && ! grep -q 'AbortSignal.any = function' "$F"; then
    { echo "$POLY"; cat "$F"; } > "$F.new" && mv "$F.new" "$F"
    echo "已注入 polyfill: $F"
  else
    echo "跳过（已注入或不存在）: $F"
  fi
done
