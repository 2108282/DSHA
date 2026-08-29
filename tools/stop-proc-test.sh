#!/bin/bash
# 「停止」链路里那几段 shell 的端到端实测：造出真实进程与 pid 文件，跑 WebProcSel 生成的
# 片段，看它认不认得、会不会误杀、看门狗的幂等闸判得对不对。
#
# 为什么需要这个脚本：停止功能已经改坏过三轮，每一轮都「看代码没问题」，真机上却毫无
# 作用 —— 因为病根在**环境**而不在逻辑：
#   · /proc/net/tcp 对非 root App 读不到（Permission denied）→ 端口反查、ss、netstat 全空；
#   · /proc 只看得到同 uid 的进程（Android hidepid）→ 跨会话扫描不保证看得见。
# 纯逻辑断言（tools/pure-logic-test.sh）只能保证字符串拼对了，拼对的片段照样可能全程
# 返回空。所以这里真的起进程、真的写 pid 文件、真的跑一遍。
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
cp "$JD/WebProcSel.java" "$JD/WatchdogScript.java" "$JD/ShellQuote.java" \
   "$WORK/src/com/deepseekharness/app/"
cat > "$WORK/src/com/deepseekharness/app/Dump.java" <<'EOF'
package com.deepseekharness.app;
public class Dump {
    public static void main(String[] a) {
        String what = a.length > 1 ? a[1] : "";
        if ("guards".equals(what)) {
            System.out.print(WebProcSel.watchdogGuards());
            return;
        }
        if ("watchdog".equals(what)) {
            System.out.print(WatchdogScript.watchdog(Integer.parseInt(a[0])));
            return;
        }
        if ("restart".equals(what)) {
            // 工作区名从参数来：要能验「名字里有空格/引号也不会把脚本弄坏」
            String wd = a.length > 2 ? a[2] : "deepseek-harness";
            System.out.print(WatchdogScript.restart(
                    "export DEEPSEEK_API_KEY='sk-test'\n",
                    ShellQuote.arg("workspace-write"), wd,
                    "exec node --expose-internals bin.js web > ~/dsh-web.log 2>&1"));
            return;
        }
        System.out.println(WebProcSel.pidsPort(Integer.parseInt(a[0])));
        System.out.println(WebProcSel.pidsDsh(true));
        System.out.println(WebProcSel.pidsFile());
    }
}
EOF
javac -encoding UTF-8 -nowarn -d "$WORK/cls" "$WORK/src/com/deepseekharness/app/"*.java \
  || { echo "javac 失败"; exit 1; }
run_dump() { java -cp "$WORK/cls" com.deepseekharness.app.Dump "$PORT" "$@"; }
# 片段里的容器内路径换到临时目录（不需要 root，也不污染开发机的 /root）
run_dump          | sed "s#/root/#$WORK/#g" > "$WORK/frag.sh"   || exit 1
run_dump guards   | sed "s#/root/#$WORK/#g" > "$WORK/guards.sh" || exit 1
PIDW="$WORK/.dsha-web.pid"
PIDG="$WORK/.dsha-watchdog.pid"

if bash -n "$WORK/frag.sh" 2>/dev/null; then ok "找进程的片段是合法 shell"; else
  bad "找进程的片段有语法错误"; cat "$WORK/frag.sh"; exit 1
fi
if bash -n "$WORK/guards.sh" 2>/dev/null; then ok "看门狗幂等闸是合法 shell"; else
  bad "看门狗幂等闸有语法错误"; cat "$WORK/guards.sh"; exit 1
fi

# ---------- 生成出来的两段脚本必须是合法 shell ----------
# 这两段是拼字符串拼出来的，写错的后果全是静默的：看门狗启动即退（Web 崩了没人拉）、
# 或者重启脚本自己坏掉（看门狗以为重启了、其实什么都没发生）。Java 编译期查不出来。
run_dump watchdog | sed "s#/root/#$WORK/#g" > "$WORK/wd.sh"
if bash -n "$WORK/wd.sh" 2>"$WORK/wderr"; then ok "看门狗脚本是合法 shell"; else
  bad "看门狗脚本有语法错误：$(cat "$WORK/wderr")"; fi
# 结构不变量：哨兵检查必须在 while 循环**里**（放循环外就只在启动那一刻看一次，
# 用户点停止后这个实例会一直数失联次数、最后把 Web 拉回来）
if grep -Pzoq 'while true; do\n  if \[ -f ' "$WORK/wd.sh" 2>/dev/null \
   || python3 - "$WORK/wd.sh" <<'PY'
import sys
s = open(sys.argv[1]).read()
i = s.find("while true; do\n")
sys.exit(0 if i >= 0 and s[i:i+80].find("if [ -f ") > 0 else 1)
PY
then ok "哨兵检查在看门狗的循环里（每轮都看）"; else bad "哨兵检查不在循环里"; fi

run_dump restart | sed "s#/root/#$WORK/#g" > "$WORK/rs.sh"
if bash -n "$WORK/rs.sh" 2>"$WORK/rserr"; then ok "重启脚本是合法 shell"; else
  bad "重启脚本有语法错误：$(cat "$WORK/rserr")"; fi
# 工作区名是用户可改的（配置页），名字里带空格必须照样能跑
run_dump restart "my work dir" | sed "s#/root/#$WORK/#g" > "$WORK/rs2.sh"
if bash -n "$WORK/rs2.sh" 2>"$WORK/rs2err"; then ok "工作区名带空格时重启脚本仍合法"; else
  bad "工作区名带空格把重启脚本弄坏了：$(cat "$WORK/rs2err")"; fi
# 名字里带单引号是最容易把 '…' 包裹弄断的情形
run_dump restart "it's work" | sed "s#/root/#$WORK/#g" > "$WORK/rs3.sh"
if bash -n "$WORK/rs3.sh" 2>"$WORK/rs3err"; then ok "工作区名带单引号时重启脚本仍合法"; else
  bad "工作区名带单引号把重启脚本弄坏了：$(head -2 "$WORK/rs3err")"; fi
# 光「语法合法」不够 —— 手工的 '…' 包裹遇到单引号时会重新配对，bash -n 照样过，
# 只是 mkdir/cd 指向了另一个目录（表现为「看门狗重启永远失败」且无任何报错）。
# 所以这里真跑一遍 mkdir + cd，看目录名对不对。
mkdir -p "$WORK/wdroot"
run_dump restart "it's work" | sed "s#/root/#$WORK/wdroot/#g" > "$WORK/rs4.sh"
sed -n '/^mkdir -p/,/^cd /p' "$WORK/rs4.sh" > "$WORK/rs4-head.sh"
bash "$WORK/rs4-head.sh" >/dev/null 2>&1
check "带单引号的工作区名真的建成了同名目录（不是只过语法）" \
      "$([ -d "$WORK/wdroot/it's work" ] && echo yes || echo no)"

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

# ---------- pid 文件通道 ----------
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

# ---------- 看门狗幂等闸 ----------
# guards 里有 exit 0 与 echo $$，不能 source；在子 shell 里跑，看它有没有走到末尾。
# 走到末尾 = 判定「没有活着的实例」→ 会启动新看门狗。
reaches_end() {
  { cat "$WORK/guards.sh"; echo 'echo REACHED_END'; } > "$WORK/probe.sh"
  bash "$WORK/probe.sh" 2>/dev/null | grep -q REACHED_END && echo yes || echo no
}
echo "$WD" > "$PIDG"
check "已有活着的看门狗时不再起第二个" "$([ "$(reaches_end)" = no ] && echo yes || echo no)"

kill "$WD" 2>/dev/null; wait "$WD" 2>/dev/null
echo "$STALE" > "$PIDG"
check "pid 被复用成无关进程时照样会起看门狗（否则永远不启动）" "$(reaches_end)"

rm -f "$PIDG"
check "没有 pid 文件时会起看门狗" "$(reaches_end)"
check "起之前把自己的 pid 写下来了" "$([ -s "$PIDG" ] && echo yes || echo no)"

# ---------- 环境探测（不作为断言）----------
# 这两项在 Android 上与在 CI 上结果本来就不同，记录下来是为了让人知道
# 「这条通道在目标环境里到底能不能用」。
if awk '$4=="0A"' /proc/net/tcp >/dev/null 2>&1; then
  echo "  --   /proc/net/tcp 可读，端口反查 → $(pids_port | tr '\n' ' ')（Android 10+ 上通常读不到）"
else
  echo "  --   /proc/net/tcp 读不到 → 端口反查在这个环境是空的（真机同理，只能当附加层）"
fi
echo "  --   pids_dsh（按 cmdline 长相）→ $(pids_dsh | tr '\n' ' ')"

kill "$WEB" "$STALE" 2>/dev/null
echo "----------------------------------------------"
if [ "$fail" -eq 0 ]; then echo "全部通过：$pass 条"; else echo "失败 $fail 条（通过 $pass）"; exit 1; fi
