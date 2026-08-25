#!/usr/bin/env bash
# 把本地离线 rootfs 里的 dsh 换成指定版本，产出新的 offline-rootfs.tar.gz。
#
# 用途：CI 每次发版都会用 scripts/ci-make-offline-bundle.sh 从零建包（需要 sudo +
# chroot，在这个 proot 工作区里跑不了），所以本地那份离线包会一直停在建它那天的
# dsh 版本上。本地打的测试包因此比线上旧 —— 装完还要手动点一次 dsh 更新。
# 这个脚本只换 dsh，其余内容原样保留，几分钟跑完。
#
# 用法：
#   bash tools/rebuild-offline-dsh.sh                 # 换成 offline-provision.sh 里 pin 的版本
#   bash tools/rebuild-offline-dsh.sh 0.1.1-rc.2      # 换成指定版本
#   SRC=/path/in.tar.gz OUT=/path/out.tar.gz bash tools/rebuild-offline-dsh.sh
#
# **为什么不是「解包 → 改 → 重新打包」**（这是本脚本最重要的一条）：
# 这个工作区跑在 proot 里，带 --link2symlink。tar 解包重建硬链接时，proot 把
# link(2) 换成「符号链接 + .l2s.<名字>NNNN 实体」这套替身结构。再打包时 tar 跟不动
# 那些替身（报 Too many levels of symbolic links），于是 ./usr/bin/gunzip 这类原本
# 是硬链接的条目，会变成指向包外的悬空符号链接 —— 包在真机上解压出来 gunzip、perl
# 就是坏的，而且不会有任何报错，等你发现时已经装到手机上了。
# 所以这里在**归档层面**动手：gunzip 出 .tar，用 tar --delete 摘掉旧 dsh 子树，
# 再 tar -r 追加新装的，原有条目（包括硬链接）一个字节都不碰。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${SRC:-/workspace/dl/offline-rootfs.tar.gz}"
WORK="${WORK:-/workspace/rebuild}"
OUT="${OUT:-$WORK/offline-rootfs-rebuilt.tar.gz}"
NM_SUBTREE="./usr/local/lib/node_modules/@deepseek-ai"

# 默认版本取 scripts/offline-provision.sh 里 pin 的那个（与 CI 建的包保持一致）。
# 那里写的是 DSH_VERSION="${DSH_VERSION:-0.1.1-rc.2}" 这种带默认值的形式，
# 所以两条规则都试：先取 ${...:-X} 里的 X，再兜一手直接赋值的写法。
DSH_VERSION="${1:-}"
if [ -z "$DSH_VERSION" ]; then
  DSH_VERSION="$(sed -n \
    -e 's/^DSH_VERSION=.*:-\([^}"'\'']*\)}.*/\1/p' \
    -e 's/^DSH_VERSION=["'\'']\{0,1\}\([0-9][^"'\'' ]*\).*/\1/p' \
    "$REPO_ROOT/scripts/offline-provision.sh" | head -1)"
fi
[ -n "$DSH_VERSION" ] || { echo "读不出目标 dsh 版本，请显式传参" >&2; exit 1; }

say() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "❌ $*" >&2; exit 1; }

[ -f "$SRC" ] || die "找不到源离线包：$SRC"
command -v pigz >/dev/null 2>&1 && GZ=(pigz -p 4 -6) || GZ=(gzip -6)

say "目标 dsh 版本：$DSH_VERSION"
say "源包：$SRC（$(du -h "$SRC" | cut -f1)）"

# ---- 1. 解包，但跳过旧 dsh（少解 3 万多个条目，快很多；这份解包只用来装 npm 包） ----
rm -rf "$WORK/stage"
mkdir -p "$WORK/stage"
say "[1/5] 解包（排除 $NM_SUBTREE）"
gunzip -c "$SRC" | tar -x --numeric-owner -p -C "$WORK/stage" \
  --exclude="$NM_SUBTREE/*" --exclude="$NM_SUBTREE" 2>"$WORK/untar.err" \
  || die "解包失败，见 $WORK/untar.err"

# ---- 2. 装新 dsh（宿主容器是 aarch64 Ubuntu + 同版本 node，编出来的原生模块架构对） ----
PREFIX="$WORK/stage/usr/local"
say "[2/5] npm 安装 @deepseek-ai/dsh@$DSH_VERSION → $PREFIX"
rm -f "$PREFIX/bin/dsh"
export npm_config_registry="${npm_config_registry:-https://registry.npmjs.org}"
npm install -g "@deepseek-ai/dsh@$DSH_VERSION" --prefix "$PREFIX" \
  --force --no-audit --no-fund || die "npm 安装失败"

GOT="$(node -p "require('$PREFIX/lib/node_modules/@deepseek-ai/dsh/package.json').version" 2>/dev/null)"
[ "$GOT" = "$DSH_VERSION" ] || die "装出来的是 $GOT，不是 $DSH_VERSION"
say "已装 dsh $GOT"

# ---- 3. 收尾：权限 + node-pty 的 spawn-helper ----
# 这个容器 umask 是 077，npm 装出来的文件是 600/700，跟包里其它文件（644/755）不一样。
# 真机上 proot 是伪 root 所以照样能读，但保持一致省得以后排查权限问题。
say "[3/5] 权限规范化 + spawn-helper 可执行位"
chmod -R u=rwX,go=rX "$PREFIX/lib/node_modules/@deepseek-ai" 2>/dev/null
# npm 打包会剥掉 spawn-helper 的执行位，dsh-subprocess-local 的 postinstall 本来负责补回来，
# 而 npm 11 默认不跑第三方 install 脚本（allow-scripts）。linux-arm64 的 prebuild 不带
# spawn-helper（那是 macOS 才用的），所以这里补是为了万一 —— 有就补，没有就跳过。
find "$PREFIX" -name spawn-helper -type f -exec chmod 755 {} + 2>/dev/null
[ -f "$PREFIX/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/prebuilds/linux-arm64/pty.node" ] \
  || echo "⚠️  没找到 node-pty 的 linux-arm64 预编译，PTY 功能可能不可用"

# ---- 4. 在归档层面换掉 dsh（见文件头那段说明：绝不重新打包整个 rootfs） ----
say "[4/5] 归档层面替换：gunzip → tar --delete → tar -r"
rm -f "$WORK/work.tar"
gunzip -c "$SRC" > "$WORK/work.tar" || die "gunzip 失败"
tar --delete --wildcards -f "$WORK/work.tar" "$NM_SUBTREE/*" || die "tar --delete 失败"
tar -rf "$WORK/work.tar" --numeric-owner -C "$WORK/stage" "$NM_SUBTREE" || die "tar -r 失败"

# ---- 5. 压缩 + 验收 ----
say "[5/5] 压缩 → $OUT"
"${GZ[@]}" -c "$WORK/work.tar" > "$OUT" || die "压缩失败"
ls -la "$OUT"

say "验收"
LIST="$WORK/rebuilt-list.txt"
tar -tvzf "$OUT" > "$LIST" 2>/dev/null || die "新包读不出来"
L2S="$(grep -c '\.l2s\.' "$LIST" || true)"
echo "  条目数：$(wc -l < "$LIST")"
echo "  proot 替身残留 .l2s：$L2S（必须是 0）"
echo "  硬链接条目：$(grep -c 'link to' "$LIST" || true)"
[ "$L2S" = "0" ] || die "包里混进了 proot 的 .l2s 替身，这个包不能用"
for p in "./usr/local/bin/dsh" "./usr/bin/gunzip" "./usr/bin/perl" "./root/dsh-guard.sh"; do
  grep -q " $p\$" "$LIST" || die "关键条目缺失：$p"
done
rm -rf "$WORK/vcheck"
mkdir -p "$WORK/vcheck"
tar -xzf "$OUT" -C "$WORK/vcheck" "$NM_SUBTREE/dsh/package.json" 2>/dev/null
FINAL="$(node -p "require('$WORK/vcheck/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json').version" 2>/dev/null)"
[ "$FINAL" = "$DSH_VERSION" ] || die "新包里的 dsh 是 $FINAL，不是 $DSH_VERSION"
echo "  包内 dsh 版本：$FINAL ✓"

say "完成。就位命令："
echo "  mv $SRC ${SRC%.tar.gz}-dsh<旧版本>.tar.gz   # 留个备份"
echo "  cp $OUT $SRC"
echo "  bash tools/pack-local.sh --from 2 --to 4 --force && bash tools/pack-local.sh --from 5"
echo "（段 2 会按新包里的 dsh 版本重写 assets/offline-rootfs.version）"
say "中间产物在 $WORK，确认无误后可删；stage 里有 proot 替身文件，"
say "删的时候先 find stage -type l -delete 再 rm -rf，否则 rm 会因为符号链接环报 Directory not empty"
