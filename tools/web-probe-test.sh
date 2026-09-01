#!/usr/bin/env bash
# WebProbe 的真跑测试：起四种服务端，验证「3080 上那位还能不能服务」判得对。
#
# 为什么要这一套：startWeb() 现在靠这个判断决定「直接接管」还是「杀干净重来」。
# 判错的两个方向代价都不小 —— 把健康实例判成坏的，用户白等 12 秒清场（就是我们
# 要修的那个问题）；把坏的判成健康的，用户点了启动却进不去，还以为 App 没反应。
set -uo pipefail
cd "$(dirname "$0")/.."

T=$(mktemp -d)
PIDS=""
cleanup() {
  for p in $PIDS; do kill "$p" 2>/dev/null || true; done
  rm -rf "$T"
}
trap cleanup EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); printf '  ok   %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  FAIL %s\n' "$1"; }

# 取一个没人用的端口，避免和真在跑的服务撞
free_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY
}

sed 's/^package .*//' app/src/main/java/com/deepseekharness/app/WebProbe.java > "$T/WebProbe.java"
cat > "$T/Probe.java" <<'EOF'
public class Probe {
    public static void main(String[] a) {
        int port = Integer.parseInt(a[0]);
        int timeout = Integer.parseInt(a[1]);
        System.out.println(WebProbe.servesHttp(port, timeout) ? "HEALTHY" : "UNHEALTHY");
    }
}
EOF
javac -d "$T" "$T/WebProbe.java" "$T/Probe.java" 2>&1 | head -5
probe() { java -cp "$T" Probe "$1" "${2:-1500}"; }

echo "① 正常 HTTP 服务（就是健康的 dsh 该有的样子）"
P1=$(free_port)
python3 -m http.server "$P1" --bind 127.0.0.1 >/dev/null 2>&1 &
PIDS="$PIDS $!"
for _ in 1 2 3 4 5 6 7 8 9 10; do
  python3 -c "
import socket,sys
s=socket.socket()
s.settimeout(0.3)
try:
    s.connect(('127.0.0.1',$P1)); sys.exit(0)
except Exception:
    sys.exit(1)
" && break
  sleep 0.3
done
[ "$(probe "$P1")" = HEALTHY ] && ok "回 HTTP 状态行 → 判健康（可直接接管）" \
  || bad "正常 HTTP 服务被判成不健康 —— 会白等 12 秒清场"

echo "② 连得上但不答话（node 卡死 / 还没进 listen 回调）"
P2=$(free_port)
python3 - "$P2" >/dev/null 2>&1 <<'PY' &
import socket, sys, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', int(sys.argv[1])))
s.listen(5)
while True:
    c, _ = s.accept()
    time.sleep(30)          # 接了就是不回话
PY
PIDS="$PIDS $!"
sleep 0.6
[ "$(probe "$P2" 900)" = UNHEALTHY ] && ok "连得上却不答话 → 判不健康（该走清场）" \
  || bad "卡死的服务被判成健康 —— 用户点启动进不去"

echo "③ 端口上是别的服务，回的不是 HTTP"
P3=$(free_port)
python3 - "$P3" >/dev/null 2>&1 <<'PY' &
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', int(sys.argv[1])))
s.listen(5)
while True:
    c, _ = s.accept()
    try:
        c.sendall(b'hello, not http\n')
    finally:
        c.close()
PY
PIDS="$PIDS $!"
sleep 0.6
[ "$(probe "$P3")" = UNHEALTHY ] && ok "回非 HTTP 内容 → 判不健康（端口被别人占了）" \
  || bad "把非 HTTP 服务当成 dsh —— 会直接接管一个用不了的端口"

echo "④ 没人监听"
P4=$(free_port)
[ "$(probe "$P4" 600)" = UNHEALTHY ] && ok "连接被拒 → 判不健康" \
  || bad "空端口被判成健康"

echo "----------------------------------------------"
if [ "$fail" -eq 0 ]; then
  echo "全部通过：$pass 条"
else
  echo "有 $fail 条不通过（通过 $pass 条）"
  exit 1
fi
