#!/bin/bash
# 「停止」进程判据的端到端实测：造出真实进程与 pid 文件，跑 WebProcSel 生成的 shell 片段，
# 看它认不认得、会不会误杀。
#
# 为什么需要这个脚本：停止功能已经改坏过三轮，每一轮都「看代码没问题」，
# 真机上却毫无作用 —— 因为病根在**环境**而不在逻辑：
#   · /proc/net/tcp 对非 root App 读不到（Permission denied）→ 端口反查、ss、netstat 全是空；
#   · /proc 只看得到同 uid 的进程（Android hidepid）→ 跨会话扫描不保证看得见。
# 纯逻辑断言（tools/pure-logic-test.sh）只能保证字符串拼对了，拼对的片段照样可能全程返回空。
# 所以这里真的起进程、真的写 pid 文件、真的跑一遍。
#
# 片段里的 /root 会被换成临时目录，所以在 CI 与开发机上都能跑（不需要 root）。
# 用法：bash tools/stop-proc-test.sh
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
JD="$REPO/app/src/main/java/com/deepseekharness/app"
PORT=${PORT:-13080}
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ok   $1"; }
bad()  { fail=$((fail+1)); echo "  FAIL $1"; }
check(){ if [ "$2" = yes ]; then ok "$1"; else bad "$1"; fi; }

command -v javac >/dev/null 2>&1 || { echo "没有 javac（需要 JDK 17+）"; exit 1; }

mkdir -p "$WORK/src/com/deepseekharness/app"
cp "$JD/WebProcSel.java" "$WORK/src/com/deepseekharness/app/"
cat > "$WORK/src/com/deepseekharness/app/Dump.java" <<'EOF'
package com.deepseekharness.app;
public class Dump {
    public static void main(String[] a) {
        System.out.println(WebProcSel.pidsPort(Integer.parseInt(a[0])));
        System.out.println(WebProcSel.pidsDsh(true));
        System.out.println(WebProcSel.pidsFile());
    }
}
EOF
javac -encoding UTF-8 -nowarn -d "$WORK/cls" "$WORK/src/com/deepseekharness/app/"*.java \
  || { echo "javac 失败"; exit 1; }
java -cp "$WORK/cls" com.deepseekharness.app.Dump "$PORT" > "$WORK/frag-raw.sh" || exit 1
# 片段里的容器内路径换到临时目录（不需要 root，也不污染开发机的 /root）
sed "s#/root/#$WORK/#g" "$WORK/frag-raw.sh" > "$WORK/frag.sh"
PIDW="$WORK/.dsha-web.pid"
PIDG="$WORK/.dsha-watchdog.pid"

if bash -n "$WORK/frag.sh" 2>/dev/null; then ok "片段是合法 shell（3 个函数定义）"; else
  bad "片段有语法错误"; cat "$WORK/frag.sh"; exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "  --   没有 node，跳过运行时部分"
  echo "全部通过：$pass 条"; exit 0
fi

# 场景：真 node（当 Web）· 假看门狗（cmdline 含 dsh-watchdog）· 一个无关进程（当过期 pid）
node -e 'require("http").createServer(function(){}).listen('"$PORT"', "127.0.0.1", function(){}); setTimeout(function(){}, 40000)' &
WEB=$!
printf '#!/bin/bash\nsleep 40\n' > "$WORK/dsh-watchdog.sh"
bash "$WORK/dsh-watchdog.sh" &
WD=$!
sleep 40 &
STALE=$!
sleep 1.5

# shellcheck disable=SC1090
. "$WORK/frag.sh"

echo "$WEB" > "$PIDW"; echo "$WD" > "$PIDG"
R=$(pids_file | tr '\n' ' ')
check "pid 通道找到 Web（$WEB）" "$(echo "$R" | grep -qw "$WEB" && echo yes || echo no)"
check "pid 通道找到看门狗（$WD）" "$(echo "$R" | grep -qw "$WD" && echo yes || echo no)"

# pid 回卷复用：文件里的号被别的进程占了，长相对不上就必须当过期忽略
echo "$STALE" > "$PIDW"
R2=$(pids_file | tr '\n' ' ')
check "pid 被复用成无关进程时不误杀" "$(echo "$R2" | grep -qw "$STALE" && echo no || echo yes)"

echo "999999" > "$PIDW"
R3=$(pids_file 2>"$WORK/err" | tr '\n' ' ')
check "pid 指向已退出的进程时安静跳过" "$(echo "$R3" | grep -qw 999999 && echo no || echo yes)"
check "并且不往输出里漏 shell 报错（会污染活动日志）" \
      "$([ -s "$WORK/err" ] && echo no || echo yes)"

rm -f "$PIDW" "$PIDG"
R4=$(pids_file | tr '\n' ' ')
check "没有 pid 文件时返回空且不报错" "$([ -z "${R4// /}" ] && echo yes || echo no)"

# 下面两项是环境探测，不作为断言 —— 它们在 Android 上与在 CI 上结果本来就不同，
# 记录下来是为了让人知道「这条通道在目标环境里到底能不能用」。
if awk '$4=="0A"' /proc/net/tcp >/dev/null 2>&1; then
  echo "  --   /proc/net/tcp 可读，端口反查 → $(pids_port | tr '\n' ' ')（Android 10+ 上通常读不到）"
else
  echo "  --   /proc/net/tcp 读不到 → 端口反查在这个环境是空的（真机同理，只能当附加层）"
fi
echo "  --   pids_dsh（按 cmdline 长相）→ $(pids_dsh | tr '\n' ' ')"

kill "$WEB" "$WD" "$STALE" 2>/dev/null
echo "----------------------------------------------"
if [ "$fail" -eq 0 ]; then echo "全部通过：$pass 条"; else echo "失败 $fail 条（通过 $pass）"; exit 1; fi
