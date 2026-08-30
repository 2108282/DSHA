#!/bin/bash
# DSHA：修 pnpm 在 proot 容器里的两类顽固故障。
#
# 故障一：硬链接。pnpm 默认把包从 content-addressable store 用 **硬链接** 铺到
# node_modules/.pnpm（它自己启动时就会打印 "Packages are hard linked from the
# content-addressable store"）。硬链接不可用时（部分 ROM / 跨挂载点，以及
# proroot 无条件启用的 --link2symlink）proot 会把 link() 模拟成 .l2s 符号链接链
# —— 临时文件一清理目标就悬空，表现为各种莫名的 ENOENT，dsh 侧还会因为
# require.resolve 把 realpath 解进 store 的扁平哈希目录而报 MissingClientBundleError。
#
# **写在哪很关键（2026-08-30 实测，pnpm 11.21.0）**：
#   .npmrc           package-import-method=copy   → pnpm 仍打印 "hard linked"，nlink=5
#   pnpm-workspace.yaml  packageImportMethod: copy → pnpm 打印 "copied"，nlink=1
# pnpm ≥10 把这类设置搬到了 pnpm-workspace.yaml（dsh 自己的 profile 模板注释也写着
# "pnpm ≥10 reads its settings from pnpm-workspace.yaml, not .npmrc"）。也就是说
# 只写 .npmrc 的那半年里这条修复一直是死代码。现在两处都写：.npmrc 留给 pnpm 9 与
# npm，真正生效的是 §③ 往 profile 的 pnpm-workspace.yaml 里追加的那行。
#
# 故障二：git-hosted 包的残留。pnpm 装 GitHub 来源的插件时会在
# store/v*/tmp/_tmp_<pid>_<hash>/ 里 clone、跑 prepare、再入库。中途失败的话
# 这些目录会一直留着，下次可能撞上同名或读到半成品（典型报错：
# "Failed to prepare git-hosted package ... ENOENT ... /store/v11/tmp/..."）。
#
# 全部操作幂等，只碰 pnpm 自己的配置与临时目录，不动用户的 .dsh 数据。
set -u
LOG=/root/.dsh/pnpm-env-fix.log
mkdir -p /root/.dsh 2>/dev/null || true
log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

NPMRC=/root/.npmrc
touch "$NPMRC" 2>/dev/null || true

changed=0
# ① 禁用硬链接导入
if grep -q '^package-import-method=' "$NPMRC" 2>/dev/null; then
  if ! grep -q '^package-import-method=copy' "$NPMRC"; then
    sed -i 's|^package-import-method=.*|package-import-method=copy|' "$NPMRC"
    changed=1
  fi
else
  printf 'package-import-method=copy\n' >> "$NPMRC"
  changed=1
fi
# ② 顺带关掉 side-effects-cache：它缓存的是 build 后的产物，同样靠硬链接铺开
if ! grep -q '^side-effects-cache=' "$NPMRC" 2>/dev/null; then
  printf 'side-effects-cache=false\n' >> "$NPMRC"
  changed=1
fi

if [ "$changed" = "1" ]; then
  log "已写入 package-import-method=copy / side-effects-cache=false"
  echo "PNPM_NPMRC_FIXED"
else
  echo "PNPM_NPMRC_ALREADY"
fi

# ③ 真正生效的那一处：profile 的 pnpm-workspace.yaml（pnpm ≥10 只读这里）。
#
# 只在「link() 实际被模拟成符号链接」时才写 copy —— 真硬链接可用的机器上
# （多数 ext4/f2fs 私有目录都可用，proot 那条路会因此不加 --link2symlink）
# 强行改成复制等于白扔掉 store 去重，profile 装几十个插件能多占几百 MB。
# 判据不猜：容器内自己 ln 一次，看结果是不是符号链接。
#   DSHA_LINK_EMULATED=1/0 可覆盖探测（诊断与测试用）。
link_is_emulated() {
  case "${DSHA_LINK_EMULATED:-}" in
    1) return 0 ;;
    0) return 1 ;;
  esac
  d=$(mktemp -d "${DSHA_PROBE_DIR:-/root/.dsh}/.dsha-linkprobe.XXXXXX" 2>/dev/null) || return 1
  printf ok > "$d/a" 2>/dev/null || { rm -rf "$d"; return 1; }
  if ln "$d/a" "$d/b" 2>/dev/null; then
    # 模拟出来的是 symlink；真硬链接 lstat 是普通文件
    if [ -L "$d/b" ]; then rm -rf "$d"; return 0; fi
    rm -rf "$d"
    return 1
  fi
  # link() 直接失败：同样得走复制，否则 pnpm 每次安装都在重试注定失败的调用
  rm -rf "$d"
  return 0
}

yaml_fixed=0
yaml_already=0
PROFILES_DIR="${DSHA_PROFILES_DIR:-/root/.dsh/profiles}"
if link_is_emulated; then
  # 两处都要：dsh profile（装插件的地方）与源码安装路线的工作区（装 dsh 本体依赖，
  # node-pty 那类原生模块也从 store 铺过来）。/root 下那一层可能是用户自己的项目，
  # 所以只认「package.json 里真的依赖 dsh」的目录。
  for WS in "$PROFILES_DIR"/*/pnpm-workspace.yaml "${DSHA_ROOT_DIR:-/root}"/*/pnpm-workspace.yaml; do
    [ -f "$WS" ] || continue
    if [ "${WS#"$PROFILES_DIR"/}" = "$WS" ]; then
      grep -q '@deepseek-ai/dsh' "${WS%/pnpm-workspace.yaml}/package.json" 2>/dev/null || continue
    fi
    if grep -q '^packageImportMethod:' "$WS" 2>/dev/null; then
      yaml_already=$((yaml_already + 1))
      continue
    fi
    # 只追加顶层键，不重排用户已有内容（这个文件还管着 nodeLinker / allowBuilds）
    printf '\npackageImportMethod: copy\n' >> "$WS" \
      && yaml_fixed=$((yaml_fixed + 1)) \
      && log "$WS 追加 packageImportMethod: copy（link() 被模拟成符号链接）"
  done
  echo "PNPM_YAML_COPY=$yaml_fixed already=$yaml_already"
  # 已经用模拟硬链接铺好的 node_modules 不会自动重铺，只影响后续安装 —— 不主动重装，
  # 那属于用户数据范围；真出问题时插件页的「重装」按钮走的就是新配置。
else
  log "link() 是真硬链接，保留 pnpm 去重（不写 packageImportMethod）"
  echo "PNPM_YAML_COPY=skip-hardlink-ok"
fi

# ④ 清 store 的临时目录（只有失败残留才会留在这儿；正常安装结束即清）
cleaned=0
for STORE in /root/.local/share/pnpm/store/v* /root/.pnpm-store/v*; do
  [ -d "$STORE/tmp" ] || continue
  n=$(find "$STORE/tmp" -maxdepth 1 -mindepth 1 2>/dev/null | wc -l)
  if [ "$n" -gt 0 ]; then
    rm -rf "$STORE"/tmp/* 2>/dev/null || true
    cleaned=$((cleaned + n))
    log "清理 $STORE/tmp 残留 $n 项"
  fi
done
echo "PNPM_TMP_CLEANED=$cleaned"

# ⑤ store 里若已有悬空的 .l2s 链（上一次硬链接模拟留下的），一并摘掉：
#    它们既读不出内容，又会让后续 tar/复制整体失败
dangling=0
for STORE in /root/.local/share/pnpm/store/v*; do
  [ -d "$STORE" ] || continue
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    if ! head -c 1 "$p" >/dev/null 2>&1; then
      rm -f "$p" 2>/dev/null && dangling=$((dangling + 1))
    fi
  done <<EOF
$(find "$STORE" -name '.l2s.*' -o -name '*.l2s.*' 2>/dev/null | head -500)
EOF
done
[ "$dangling" -gt 0 ] && log "摘除悬空 l2s 链 $dangling 个"
echo "PNPM_DANGLING_REMOVED=$dangling"

log "pnpm 环境修复完成"
echo "PNPM_FIX_OK"
