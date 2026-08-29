#!/bin/bash
# TarGzipExtractor 的解压往返测试：造一个覆盖各种边界的 tar.gz，用**真实代码**解出来，
# 再逐个文件比 sha256。
#
# 为什么需要它：首次解压是整条路线的核心（几百 MB rootfs，解坏了用户环境直接废），
# 而这段代码里有一堆容易被「顺手优化」碰坏的东西 —— 读写缓冲大小、GZIP 内部缓冲、
# tar 头解析、长文件名（GNU/PAX）、符号链接与硬链接、块对齐填充。
# 缓冲大小从 8KB 提到 256KB 这类改动尤其危险：文件大小正好落在缓冲边界上时最容易出错，
# 所以测试数据里专门放了 8191/8192/8193 与 262143/262144/262145 这些尺寸。
#
# 做法：给 android.system.Os 写一个纯 Java 的 stub（chmod 空实现、symlink/link 用 NIO），
# 于是 TarGzipExtractor.java **一个字符都不用改**就能在桌面 JVM 上跑 —— 测的是真代码。
#
# 用法：bash tools/extract-roundtrip-test.sh
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
JD="$REPO/app/src/main/java/com/deepseekharness/app"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ok   $1"; }
bad()  { fail=$((fail+1)); echo "  FAIL $1"; }
check(){ if [ "$2" = yes ]; then ok "$1"; else bad "$1"; fi; }

command -v javac >/dev/null 2>&1 || { echo "没有 javac（需要 JDK 17+）"; exit 1; }

# ---------- 1. 编译真实代码 + Os stub ----------
mkdir -p "$WORK/src/com/deepseekharness/app" "$WORK/src/android/system"
cp "$JD/TarGzipExtractor.java" "$JD/FileCopy.java" "$WORK/src/com/deepseekharness/app/"
cat > "$WORK/src/android/system/Os.java" <<'EOF'
package android.system;

/** 桌面 JVM 上的 android.system.Os 替身，只为让 TarGzipExtractor 原样编译。 */
public final class Os {
    private Os() {
    }

    /** 权限位在这个测试里不校验（解压正确性与 mode 无关）。 */
    public static void chmod(String path, int mode) {
    }

    public static void symlink(String target, String linkpath) throws java.io.IOException {
        java.nio.file.Files.createSymbolicLink(
                java.nio.file.Paths.get(linkpath), java.nio.file.Paths.get(target));
    }

    public static void link(String oldPath, String newPath) throws java.io.IOException {
        java.nio.file.Files.createLink(
                java.nio.file.Paths.get(newPath), java.nio.file.Paths.get(oldPath));
    }
}
EOF
cat > "$WORK/src/com/deepseekharness/app/ExtractRun.java" <<'EOF'
package com.deepseekharness.app;

import java.io.File;

/** 放在同一个包里：FileCopy 与 TarGzipExtractor 都是 package-private / 包内可见。 */
public class ExtractRun {
    public static void main(String[] a) throws Exception {
        if (a.length > 2 && "copy".equals(a[2])) {
            int fb = FileCopy.copyPreservingLinks(
                    new File(a[0]), new File(a[1]));
            System.out.println("FALLBACKS=" + fb);
            return;
        }
        TarGzipExtractor.extract(new File(a[0]), new File(a[1]));
        System.out.println("SKIPPED=" + TarGzipExtractor.lastSkipped);
    }
}
EOF
if javac -encoding UTF-8 -nowarn -d "$WORK/cls" \
        "$WORK/src/android/system/Os.java" \
        "$WORK/src/com/deepseekharness/app/TarGzipExtractor.java" \
        "$WORK/src/com/deepseekharness/app/FileCopy.java" \
        "$WORK/src/com/deepseekharness/app/ExtractRun.java" 2>"$WORK/javac.err"; then
    ok "TarGzipExtractor 与 FileCopy 能在桌面 JVM 上原样编译（测的是真代码）"
else
    bad "编译失败：$(head -5 "$WORK/javac.err")"; exit 1
fi

# ---------- 2. 造覆盖边界的样本 ----------
SRC="$WORK/src-tree"
mkdir -p "$SRC/a/b/c" "$SRC/empty-dir"
: > "$SRC/zero.bin"                                    # 0 字节
printf 'x' > "$SRC/one.bin"                            # 1 字节
for n in 511 512 513 8191 8192 8193 262143 262144 262145; do
    head -c "$n" /dev/urandom > "$SRC/sz-$n.bin"       # 块与缓冲边界
done
head -c 5000000 /dev/urandom > "$SRC/big-5mb.bin"      # 跨很多次读写循环
head -c 1000 /dev/urandom > "$SRC/a/b/c/deep.bin"      # 深层目录
LONG="$SRC/$(printf 'n%.0s' $(seq 1 160)).bin"         # 超 100 字符 → GNU/PAX 长名
head -c 100 /dev/urandom > "$LONG"
printf 'utf8 内容 with 中文\n' > "$SRC/中文名-文件.txt"   # 非 ASCII 文件名
ln -s big-5mb.bin "$SRC/link-to-big"                   # 符号链接
# 刻意**不**造硬链接：这个开发容器跑在 proot --link2symlink 下，硬链接会被换成
# 「符号链接 + .l2s.<名字>NNNN 替身」，tar 跟不动替身，打包直接报
# 「Too many levels of symbolic links」——那是环境的事，不是解压代码的事。
# 真机上 tar 里的硬链接条目走 Os.link，属于 --link2symlink 那条已知路子。
(cd "$SRC" && find . -type f -o -type l | sort | while read -r f; do
    if [ -L "$f" ]; then echo "L $f -> $(readlink "$f")"; else echo "F $f $(sha256sum "$f" | cut -d' ' -f1)"; fi
done) > "$WORK/before.txt"
BEFORE_N=$(wc -l < "$WORK/before.txt")

if tar -czf "$WORK/sample.tar.gz" -C "$SRC" . 2>"$WORK/tar.err"; then
    ok "样本 tar.gz 造好（$BEFORE_N 个文件/链接，含 0 字节、512/8K/256K 边界、5MB、长名、中文名、软链）"
else
    bad "造样本失败：$(head -3 "$WORK/tar.err")"; exit 1
fi

# ---------- 3. 用真实代码解压 ----------
OUT="$WORK/out"
mkdir -p "$OUT"
if java -cp "$WORK/cls" com.deepseekharness.app.ExtractRun "$WORK/sample.tar.gz" "$OUT" > "$WORK/run.log" 2>&1; then
    ok "解压跑通（$(grep -o 'SKIPPED=[0-9]*' "$WORK/run.log")）"
else
    bad "解压抛异常：$(head -5 "$WORK/run.log")"; exit 1
fi
check "没有条目被跳过" "$(grep -q 'SKIPPED=0' "$WORK/run.log" && echo yes || echo no)"

# ---------- 4. 逐个比 sha256 ----------
(cd "$OUT" && find . -type f -o -type l | sort | while read -r f; do
    if [ -L "$f" ]; then echo "L $f -> $(readlink "$f")"; else echo "F $f $(sha256sum "$f" | cut -d' ' -f1)"; fi
done) > "$WORK/after.txt"
AFTER_N=$(wc -l < "$WORK/after.txt")

check "文件与链接数一致（$BEFORE_N → $AFTER_N）" "$([ "$BEFORE_N" = "$AFTER_N" ] && echo yes || echo no)"
if diff -u "$WORK/before.txt" "$WORK/after.txt" > "$WORK/diff.txt" 2>&1; then
    ok "每个文件的 sha256 与符号链接目标都一模一样"
else
    bad "解出来的内容和原始不一致（前 12 行差异）："
    head -12 "$WORK/diff.txt" | sed 's/^/         /'
fi
check "空目录也建出来了" "$([ -d "$OUT/empty-dir" ] && echo yes || echo no)"
# 缓冲边界重点复查：这几个尺寸正好卡在读写缓冲上
for n in 8192 262144; do
    a=$(sha256sum "$SRC/sz-$n.bin" | cut -d' ' -f1)
    b=$(sha256sum "$OUT/sz-$n.bin" 2>/dev/null | cut -d' ' -f1)
    check "正好 $n 字节的文件逐字节一致（缓冲边界）" "$([ "$a" = "$b" ] && echo yes || echo no)"
done

# ---------- 5. 数据保护复制：软链必须原样保留 ----------
# 重解压内置环境（v1→v2 这类升级）之前，会把 .dsh 挪进 .data-preserve-*。
# 而 .dsh/sessions、storages、attachments、settings.yaml 是指向
# /sdcard/Documents/dshdata 的软链（「卸载不丢数据」就是这么实现的）。
# 跟随复制的后果：备份体积从几 KB 变成几 GB、还原后软链变真目录、下次启动
# migrate-public-data.sh 撞上冲突分支又在公开侧留一份 *.conflict-<ts> ——
# 一次升级把对话复制三遍，空间不够时还会在 rootfs 已删之后失败。
PUB="$WORK/fake-public"
DSH="$WORK/fake-dsh"
mkdir -p "$PUB/sessions" "$DSH/profiles/web"
head -c 5000000 /dev/urandom > "$PUB/sessions/big-conversation.bin"   # 假装很多对话
printf 'key: value\n' > "$PUB/settings.yaml"
ln -s "$PUB/sessions" "$DSH/sessions"
ln -s "$PUB/settings.yaml" "$DSH/settings.yaml"
printf '{"dependencies":{}}\n' > "$DSH/profiles/web/package.json"
printf '#!/bin/sh\necho hi\n' > "$DSH/hook.sh"; chmod +x "$DSH/hook.sh"

BAK="$WORK/preserve"
if java -cp "$WORK/cls" com.deepseekharness.app.ExtractRun "$DSH" "$BAK" copy > "$WORK/copy.log" 2>&1; then
    ok "数据保护复制跑通（$(cat "$WORK/copy.log")）"
else
    bad "数据保护复制抛异常：$(head -3 "$WORK/copy.log")"
fi
check "软链原样保留（sessions 仍是链接，不是真目录）" \
      "$([ -L "$BAK/sessions" ] && echo yes || echo no)"
check "软链目标没变" \
      "$([ "$(readlink "$BAK/sessions")" = "$PUB/sessions" ] && echo yes || echo no)"
check "指向单个文件的软链也保留（settings.yaml）" \
      "$([ -L "$BAK/settings.yaml" ] && echo yes || echo no)"
SZ=$(du -sk "$BAK" 2>/dev/null | cut -f1)
check "备份体积没被链接目标撑大（${SZ}KB；跟随复制会是 4900KB 以上）" \
      "$([ "${SZ:-99999}" -lt 500 ] && echo yes || echo no)"
check "真文件照样复制到位" \
      "$([ -f "$BAK/profiles/web/package.json" ] && echo yes || echo no)"
check "可执行位保留（rootfs 里的脚本丢了执行位就跑不起来）" \
      "$([ -x "$BAK/hook.sh" ] && echo yes || echo no)"

echo "----------------------------------------------"
if [ "$fail" -eq 0 ]; then echo "全部通过：$pass 条"; else echo "失败 $fail 条（通过 $pass）"; exit 1; fi
