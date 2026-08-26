#!/bin/bash
# restore-merge.py 的分范围恢复断言（不需要设备、不需要 rootfs）。
#
# 盯的是这个功能里唯一会让用户丢数据的地方：**部分备份被当成全量恢复**。
# 全量恢复的做法是「把整个 .dsh 挪成 .pre-restore-* 再换上包里的」——
# 拿一个只含对话的包那么干，等于把配置、凭据、插件声明一起换掉。原数据虽然还在
# .pre-restore-* 里，但用户看到 RESTORE_OK 就不会去找，等发现时已经分不清该回退哪个。
#
# 三个用例：
#   1. scope=sessions —— 只换对话，配置/插件/凭据必须原样，且不得出现 .dsh.pre-restore-*
#   2. scope=plugins  —— 只换插件声明，对话必须原样
#   3. 无清单的老包   —— 必须仍走全量路径（老备份的语义就是全量，行为不能变）
#
# 顺带验证「软链落点」：sessions 在设备上是指向 Documents/dshdata 的软链，
# 恢复要写进它指向的公开目录、保持软链不动，否则数据会跟着私有目录一起被卸载带走。
#
# 用法：bash tools/restore-scope-test.sh
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
MERGE="$REPO/app/src/main/assets/restore-merge.py"
[ -f "$MERGE" ] || { echo "找不到 $MERGE" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "没有 python3" >&2; exit 1; }

B="$(mktemp -d)"
trap 'rm -rf "$B"' EXIT
fail=0
chk() {
  if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1（期望 [$2]，实到 [$3]）"; fail=1; fi
}

# 造设备现状：配置 + 插件 + 凭据都在，sessions 是指向「公开目录」的软链
setup_device() {
  rm -rf "$B/root" "$B/pub"
  mkdir -p "$B/root/.dsh/profiles/web" "$B/pub/sessions"
  echo '{"dependencies":{"dsh-mobile-nav":"link:/root/dsha-mobile-nav"}}' \
      > "$B/root/.dsh/profiles/web/package.json"
  echo 'theme: dark'    > "$B/root/.dsh/settings.yaml"
  echo 'OLD-CRED'       > "$B/root/.dsh/.credentials.yaml"
  echo '{"id":"old-1"}' > "$B/pub/sessions/session.jsonl"
  ln -s "$B/pub/sessions" "$B/root/.dsh/sessions"
}

manifest() {  # $1 = scope（空则不写清单，模拟老包）
  [ -n "$1" ] || return 0
  printf '{"formatVersion":2,"appVersion":"test","workdir":"deepseek-harness","scope":"%s","profiles":{},"inlinedPlugins":[]}\n' \
      "$1" > "$B/stage/.dsha-backup-manifest.json"
}

run_merge() {  # $1 = App 侧从文件名推断的 scope（清单存在时应当被清单压过）
  python3 "$MERGE" --stage "$B/stage" --root "$B/root" \
      --workdir deepseek-harness --scope "$1" 2>&1
}

echo "① scope=sessions：只换对话"
setup_device
rm -rf "$B/stage"; mkdir -p "$B/stage/.dsha-pub/sessions"
manifest sessions
echo '{"id":"new-1"}' > "$B/stage/.dsha-pub/sessions/session.jsonl"
echo '{"id":"new-2"}' > "$B/stage/.dsha-pub/sessions/session.jsonl.2"
# 故意传 full：清单里的 sessions 必须赢（文件名能被用户改，清单不会）
out="$(run_merge full)"
chk "结果是 RESTORE_OK" 1 "$(printf '%s' "$out" | grep -c RESTORE_OK)"
chk "插件声明还在" 1 "$([ -f "$B/root/.dsh/profiles/web/package.json" ] && echo 1 || echo 0)"
chk "settings.yaml 未被动" "theme: dark" "$(cat "$B/root/.dsh/settings.yaml" 2>/dev/null)"
chk "凭据未被动" "OLD-CRED" "$(cat "$B/root/.dsh/.credentials.yaml" 2>/dev/null)"
chk "没走整目录替换（无 .dsh.pre-restore-*）" 0 \
    "$(ls -d "$B/root"/.dsh.pre-restore-* 2>/dev/null | wc -l)"
chk "sessions 仍是软链" 1 "$([ -L "$B/root/.dsh/sessions" ] && echo 1 || echo 0)"
chk "对话落在公开目录且已替换" 2 "$(ls "$B/pub/sessions" 2>/dev/null | wc -l)"
chk "对话内容来自包" '{"id":"new-1"}' "$(cat "$B/pub/sessions/session.jsonl" 2>/dev/null)"
chk "旧对话留了 .pre-restore 备份" 1 \
    "$(ls -d "$B/pub"/sessions.pre-restore-* 2>/dev/null | wc -l)"

echo "② scope=plugins：只换插件"
setup_device
rm -rf "$B/stage"; mkdir -p "$B/stage/.dsh/profiles/web"
manifest plugins
# 用 registry 依赖 + 一个标记文件来验证「子树确实被换了」。
# 不要用 link: 依赖做断言 —— fix_profiles 会把 link 目标不存在的条目从 dependencies
# 摘掉并报 MISSING_PLUGINS（那是刻意的：dsh 必须能起来，缺的插件由 App 后台补装）。
echo '{"dependencies":{"some-registry-plugin":"^1.0.0"}}' \
    > "$B/stage/.dsh/profiles/web/package.json"
echo 'from-backup' > "$B/stage/.dsh/profiles/web/.marker"
out="$(run_merge plugins)"
chk "结果是 RESTORE_OK" 1 "$(printf '%s' "$out" | grep -c RESTORE_OK)"
chk "profiles 子树已被替换（标记文件来自包）" "from-backup" \
    "$(cat "$B/root/.dsh/profiles/web/.marker" 2>/dev/null)"
chk "包里的依赖声明保留了" 1 \
    "$(grep -c 'some-registry-plugin' "$B/root/.dsh/profiles/web/package.json" 2>/dev/null)"
chk "原 profiles 留了 .pre-restore 备份" 1 \
    "$(ls -d "$B/root/.dsh"/profiles.pre-restore-* 2>/dev/null | wc -l)"
chk "对话一条没动" '{"id":"old-1"}' "$(cat "$B/pub/sessions/session.jsonl" 2>/dev/null)"
chk "settings.yaml 未被动" "theme: dark" "$(cat "$B/root/.dsh/settings.yaml" 2>/dev/null)"
chk "没走整目录替换" 0 "$(ls -d "$B/root"/.dsh.pre-restore-* 2>/dev/null | wc -l)"

echo "③ 老包（无清单）：必须仍是全量行为"
setup_device
rm -rf "$B/stage"; mkdir -p "$B/stage/.dsh/sessions" "$B/stage/.dsh/profiles/web"
echo '{"id":"legacy-1"}'  > "$B/stage/.dsh/sessions/session.jsonl"
echo '{"dependencies":{}}' > "$B/stage/.dsh/profiles/web/package.json"
echo 'theme: light'        > "$B/stage/.dsh/settings.yaml"
out="$(run_merge full)"
chk "结果是 RESTORE_OK 或 PARTIAL" 1 \
    "$(printf '%s' "$out" | grep -cE 'RESTORE_OK|RESTORE_PARTIAL')"
chk "整目录替换发生了（有 .dsh.pre-restore-*）" 1 \
    "$(ls -d "$B/root"/.dsh.pre-restore-* 2>/dev/null | wc -l)"
chk "settings.yaml 换成了包里的" "theme: light" "$(cat "$B/root/.dsh/settings.yaml" 2>/dev/null)"

echo "----------------------------------------------"
if [ "$fail" = 0 ]; then
  echo "全部通过"
else
  echo "有失败（见上面 FAIL 行）"
fi
exit "$fail"
