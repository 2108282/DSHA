#!/bin/bash
# pnpm-env-fix.sh 的 packageImportMethod 断言（沙箱里真跑一遍，不碰真 /root）。
#
# 为什么必须有：这条修复此前只写 /root/.npmrc，而 **pnpm ≥10 不读 .npmrc 的这一项**。
# 2026-08-30 在容器里做的对照实验（pnpm 11.21.0，同一个包、同一个 store）：
#
#   .npmrc              package-import-method=copy   → "Packages are hard linked"，nlink=5
#   pnpm-workspace.yaml packageImportMethod: copy    → "Packages are copied"，   nlink=1
#
# 也就是说旧写法是死代码：proot 把 link() 模拟成 .l2s 符号链接的机器上，pnpm 依旧
# 走硬链接，悬空链带来的 ENOENT / MissingClientBundleError 一个都没被挡住。
# 这类故障纯逻辑测不出来（脚本片段拼得再对，写错文件就是无效），所以这里真跑脚本、
# 真查文件内容。
#
# 顺带盯住三件容易写坏的事：
#   · 幂等 —— 这个脚本每次安装插件都会跑，追加式写入最容易写成每次多一行；
#   · 不覆盖用户已有的值（有人会故意设 clone）；
#   · 真硬链接可用时**不要**写 copy —— 那会白扔掉 store 去重，profile 装几十个
#     插件能多占几百 MB。
#
# 用法：bash tools/pnpm-import-test.sh
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/app/src/main/assets/pnpm-env-fix.sh"
[ -f "$SRC" ] || { echo "找不到 $SRC" >&2; exit 1; }

B="$(mktemp -d)"
trap 'rm -rf "$B"' EXIT
fail=0
n=0
chk() {
  n=$((n + 1))
  if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1（期望 [$2]，实到 [$3]）"; fail=1; fi
}

# 把脚本里的 /root 指到沙箱，其它一行不改（测的就是发出去的那份）
SCRIPT="$B/pnpm-env-fix.sh"
sed "s|/root|$B/root|g" "$SRC" > "$SCRIPT"

# dsh initProfile 写出来的模板（packages/boot/app-boot/src/profile.ts）
template() {
  printf 'packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n'
}

mk_profile() {
  mkdir -p "$B/root/.dsh/profiles/$1"
  template > "$B/root/.dsh/profiles/$1/pnpm-workspace.yaml"
}

reset_all() {
  rm -rf "$B/root"
  mkdir -p "$B/root/.dsh"
  mk_profile web
  mk_profile cli
}

run() {  # run <期望取哪一行的 key>
  ( cd "$B" && bash "$SCRIPT" 2>/dev/null )
}

# ── 用例 1：link() 被模拟成符号链接 → 两个 profile 都追加 ──────────────────
reset_all
out="$(DSHA_LINK_EMULATED=1 run)"
chk "模拟硬链接时写入两个 profile" "PNPM_YAML_COPY=2 already=0" \
    "$(printf '%s\n' "$out" | grep '^PNPM_YAML_COPY=')"
chk "web profile 拿到 copy" "1" \
    "$(grep -c '^packageImportMethod: copy$' "$B/root/.dsh/profiles/web/pnpm-workspace.yaml")"
chk "cli profile 拿到 copy" "1" \
    "$(grep -c '^packageImportMethod: copy$' "$B/root/.dsh/profiles/cli/pnpm-workspace.yaml")"
chk "dsh 自己那几项没被动" "hoisted" \
    "$(sed -n 's/^nodeLinker: //p' "$B/root/.dsh/profiles/web/pnpm-workspace.yaml")"
chk "顶层键没被写进缩进块里" "0" \
    "$(grep -c '^ .*packageImportMethod' "$B/root/.dsh/profiles/web/pnpm-workspace.yaml")"

# ── 用例 2：幂等（这个脚本每次装插件都会跑）────────────────────────────────
out="$(DSHA_LINK_EMULATED=1 run)"
chk "第二次跑不再写" "PNPM_YAML_COPY=0 already=2" \
    "$(printf '%s\n' "$out" | grep '^PNPM_YAML_COPY=')"
chk "跑两次仍只有一行" "1" \
    "$(grep -c 'packageImportMethod' "$B/root/.dsh/profiles/web/pnpm-workspace.yaml")"

# ── 用例 3：用户已有的值不覆盖 ─────────────────────────────────────────────
reset_all
printf 'packageImportMethod: clone\n' >> "$B/root/.dsh/profiles/web/pnpm-workspace.yaml"
out="$(DSHA_LINK_EMULATED=1 run)"
chk "已有设置只计入 already" "PNPM_YAML_COPY=1 already=1" \
    "$(printf '%s\n' "$out" | grep '^PNPM_YAML_COPY=')"
chk "用户的 clone 保持不变" "clone" \
    "$(sed -n 's/^packageImportMethod: //p' "$B/root/.dsh/profiles/web/pnpm-workspace.yaml")"

# ── 用例 4：真硬链接可用 → 一个字都不写（保留 store 去重）──────────────────
reset_all
out="$(DSHA_LINK_EMULATED=0 run)"
chk "真硬链接时跳过" "PNPM_YAML_COPY=skip-hardlink-ok" \
    "$(printf '%s\n' "$out" | grep '^PNPM_YAML_COPY=')"
chk "yaml 未被追加" "0" \
    "$(grep -c 'packageImportMethod' "$B/root/.dsh/profiles/web/pnpm-workspace.yaml")"

# ── 用例 5：真探测能跑通，且不留探测垃圾 ───────────────────────────────────
reset_all
out="$(run)"
line="$(printf '%s\n' "$out" | grep '^PNPM_YAML_COPY=')"
case "$line" in
  PNPM_YAML_COPY=skip-hardlink-ok|PNPM_YAML_COPY=[0-9]*" already="[0-9]*) real="ok" ;;
  *) real="$line" ;;
esac
chk "真探测输出可识别的结论" "ok" "$real"
chk "探测目录已清掉" "0" \
    "$(find "$B/root/.dsh" -maxdepth 1 -name '.dsha-linkprobe.*' 2>/dev/null | wc -l)"

# ── 用例 6：原有的 .npmrc 行为没被改坏（pnpm 9 / npm 还靠它）───────────────
chk ".npmrc 仍写 copy" "1" \
    "$(grep -c '^package-import-method=copy$' "$B/root/.npmrc")"
chk ".npmrc 仍关 side-effects-cache" "1" \
    "$(grep -c '^side-effects-cache=false$' "$B/root/.npmrc")"

# ── 用例 7：源码安装路线的工作区也要覆盖，用户自己的项目不许碰 ─────────────
reset_all
mkdir -p "$B/root/deepseek-harness" "$B/root/my-app"
printf '{"dependencies":{"@deepseek-ai/dsh":"0.1.1-rc.2"}}\n' > "$B/root/deepseek-harness/package.json"
template > "$B/root/deepseek-harness/pnpm-workspace.yaml"
printf '{"dependencies":{"express":"5.0.0"}}\n' > "$B/root/my-app/package.json"
template > "$B/root/my-app/pnpm-workspace.yaml"
out="$(DSHA_LINK_EMULATED=1 run)"
chk "profile ×2 + dsh 工作区 ×1" "PNPM_YAML_COPY=3 already=0" \
    "$(printf '%s\n' "$out" | grep '^PNPM_YAML_COPY=')"
chk "dsh 工作区拿到 copy" "1" \
    "$(grep -c 'packageImportMethod' "$B/root/deepseek-harness/pnpm-workspace.yaml")"
chk "用户项目一个字没动" "0" \
    "$(grep -c 'packageImportMethod' "$B/root/my-app/pnpm-workspace.yaml")"

echo
if [ "$fail" = "0" ]; then
  echo "全部通过：$n 条"
else
  echo "有失败项"
  exit 1
fi
