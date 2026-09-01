#!/bin/bash
# DataPreserve 的端到端断言：真造目录、真造软链、真让复制失败，跑真实代码。
#
# 为什么值得单独一套：这段逻辑的下一步就是 deleteRecursively(rootfs)。1.1.9 那次
# 「工作区内容全部丢失」就出在这里，而它当时只能靠读代码判断对不对 ——
# ProotBootstrap 依赖 Context/SharedPreferences/AssetManager，javac 编不动。
# 把判据搬进纯 java.io 的 DataPreserve 之后，这些场景才跑得起来。
#
# 做法：给 android.system.Os 写一个纯 Java stub（FileCopy 的 relink 用它），
# 用 javac 编译真实的 FileCopy + DataPreserve，再用一个小 Runner 驱动。
#
# 用法：bash tools/data-preserve-test.sh
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_DIR="$REPO/app/src/main/java/com/deepseekharness/app"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok()   { echo "  ok   $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL $1"; fail=$((fail+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 [$2]，实到 [$3]）"; fi; }

command -v javac >/dev/null 2>&1 || { echo "没有 javac（需要 JDK 17+）"; exit 1; }
command -v mkfifo >/dev/null 2>&1 || { echo "没有 mkfifo"; exit 1; }

# ---------- 1. 编译真实代码 + Os stub ----------
mkdir -p "$WORK/src/com/deepseekharness/app" "$WORK/src/android/system"
cat > "$WORK/src/android/system/Os.java" <<'EOF'
package android.system;

/** 纯 Java stub：只实现 FileCopy 用到的三个调用。 */
public final class Os {
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
cat > "$WORK/src/com/deepseekharness/app/PreserveRun.java" <<'EOF'
package com.deepseekharness.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 同包 Runner：DataPreserve 与 FileCopy 都是 package-private。 */
public class PreserveRun {
    public static void main(String[] a) throws Exception {
        if ("preserve".equals(a[0])) {
            DataPreserve.Result r = DataPreserve.preserve(new File(a[1]), new File(a[2]));
            System.out.println("MOVED=" + r.moved + " COPIED=" + r.copied);
        } else if ("rollback".equals(a[0])) {
            List<String> names = new ArrayList<>(Arrays.asList(a).subList(3, a.length));
            System.out.println(DataPreserve.rollback(new File(a[1]), new File(a[2]), names));
        } else if ("delete".equals(a[0])) {
            DataPreserve.deleteRecursively(new File(a[1]));
            System.out.println("DELETED");
        }
    }
}
EOF
cp "$JAVA_DIR/FileCopy.java" "$JAVA_DIR/DataPreserve.java" "$WORK/src/com/deepseekharness/app/"
if javac -encoding UTF-8 -nowarn -d "$WORK/cls" \
        "$WORK/src/android/system/Os.java" \
        "$WORK/src/com/deepseekharness/app/FileCopy.java" \
        "$WORK/src/com/deepseekharness/app/DataPreserve.java" \
        "$WORK/src/com/deepseekharness/app/PreserveRun.java" 2>"$WORK/javac.err"; then
    ok "编译真实 FileCopy + DataPreserve"
else
    bad "编译失败：$(head -5 "$WORK/javac.err")"; exit 1
fi
run() { java -cp "$WORK/cls" com.deepseekharness.app.PreserveRun "$@" 2>&1; }

# 造设备现状：/root 下有 .dsh（内含指向公开目录的软链 + 一根悬空软链）、
# 一个工作区目录、一个内置插件实体（dsha-* 前缀，必须被跳过）。
setup_root() {
    rm -rf "$WORK/root" "$WORK/pub" "$WORK/bak"
    mkdir -p "$WORK/root/.dsh/profiles/web" "$WORK/pub/sessions" "$WORK/root/myproj" \
             "$WORK/root/dsha-device-shell-guide" "$WORK/bak"
    echo '{"id":"conv-1"}'  > "$WORK/pub/sessions/session.jsonl"
    echo 'theme: dark'      > "$WORK/pub/settings.yaml"
    echo '{"deps":{}}'      > "$WORK/root/.dsh/profiles/web/package.json"
    echo 'OLD-CRED'         > "$WORK/root/.dsh/.credentials.yaml"
    echo 'user code'        > "$WORK/root/myproj/main.py"
    echo 'builtin'          > "$WORK/root/dsha-device-shell-guide/index.js"
    ln -s "$WORK/pub/sessions"      "$WORK/root/.dsh/sessions"
    ln -s "$WORK/pub/settings.yaml" "$WORK/root/.dsh/settings.yaml"
    # 悬空软链：换设备之后公开目录不在，就是这个样子。File.exists() 对它返回 false，
    # 回滚时用 exists() 判断会把它整根漏掉 —— 这正是要盯的地方。
    ln -s "$WORK/pub/gone-after-device-move" "$WORK/root/dangling"
}

echo "① 正常路径：全部 rename 走"
setup_root
out="$(run preserve "$WORK/root" "$WORK/bak")"
chk "moved=3 copied=0（.dsh / myproj / dangling，dsha-* 跳过）" "MOVED=3 COPIED=0" "$out"
chk "内置插件实体留在原地（不必保）" 1 \
    "$([ -d "$WORK/root/dsha-device-shell-guide" ] && echo 1 || echo 0)"
chk "用户数据已不在 /root" 0 "$([ -e "$WORK/root/.dsh" ] && echo 1 || echo 0)"
chk "保护目录里 sessions 仍是软链（没跟随复制）" 1 \
    "$([ -L "$WORK/bak/.dsh/sessions" ] && echo 1 || echo 0)"
chk "软链目标没变" "$WORK/pub/sessions" "$(readlink "$WORK/bak/.dsh/sessions")"
chk "悬空软链原样搬走了" 1 \
    "$([ -L "$WORK/bak/dangling" ] && echo 1 || echo 0)"
chk "公开目录的对话一条没动" '{"id":"conv-1"}' "$(cat "$WORK/pub/sessions/session.jsonl")"

echo "② 复制路径：rename 失败但复制成功"
setup_root
# 目标已是非空目录 → POSIX rename 返回 ENOTEMPTY → renameTo false → 落到复制分支
mkdir -p "$WORK/bak/myproj/occupied"; echo x > "$WORK/bak/myproj/occupied/x"
out="$(run preserve "$WORK/root" "$WORK/bak")"
chk "copied=1（myproj 走了复制）" "MOVED=2 COPIED=1" "$out"
chk "复制不删原件：myproj 仍在 /root" 1 \
    "$([ -f "$WORK/root/myproj/main.py" ] && echo 1 || echo 0)"
chk "副本也到了保护目录" "user code" "$(cat "$WORK/bak/myproj/main.py" 2>/dev/null)"

echo "③ fail-closed：复制失败必须中止 + 回滚"
setup_root
# FIFO 不是普通文件也不是目录 → FileCopy 明确拒绝（未知文件类型）
mkdir -p "$WORK/root/badproj"; mkfifo "$WORK/root/badproj/pipe"
mkdir -p "$WORK/bak/badproj/occupied"; echo x > "$WORK/bak/badproj/occupied/x"
before="$(ls -A "$WORK/root" | sort | tr '\n' ' ')"
out="$(run preserve "$WORK/root" "$WORK/bak")"
chk "抛了 IOException 且提示 rootfs 未改动" 1 \
    "$(printf '%s' "$out" | grep -c '升级已中止（rootfs 未改动）')"
chk "提示里点名了保不住的那一项" 1 "$(printf '%s' "$out" | grep -c 'badproj')"
after="$(ls -A "$WORK/root" | sort | tr '\n' ' ')"
# 与遍历顺序无关的判据：不管 badproj 排在第几个被处理，抛之前 rename 走的都要挪回来
chk "/root 的条目集合与保护前完全一致（回滚干净）" "$before" "$after"
chk "悬空软链也挪回来了（不是被 exists() 漏掉）" 1 \
    "$([ -L "$WORK/root/dangling" ] && echo 1 || echo 0)"
chk "回滚后 .dsh 里的软链还是软链" 1 \
    "$([ -L "$WORK/root/.dsh/sessions" ] && echo 1 || echo 0)"
chk "公开目录的对话仍然完好" '{"id":"conv-1"}' "$(cat "$WORK/pub/sessions/session.jsonl")"

echo "④ rollback 单独跑：悬空软链 + 非空保护目录"
setup_root
mv "$WORK/root/.dsh" "$WORK/bak/.dsh"
mv "$WORK/root/dangling" "$WORK/bak/dangling"
# 再塞一份「复制成功」留下的冗余副本，rollback 之后应该被一起清掉
mkdir -p "$WORK/bak/leftover"; echo x > "$WORK/bak/leftover/x"
out="$(run rollback "$WORK/bak" "$WORK/root" .dsh dangling)"
chk "报告说挪回 2 项且 rootfs 一致" 1 \
    "$(printf '%s' "$out" | grep -c '已挪回 2 项，rootfs 与升级前一致')"
chk ".dsh 回来了" 1 "$([ -d "$WORK/root/.dsh" ] && echo 1 || echo 0)"
chk "悬空软链回来了" 1 "$([ -L "$WORK/root/dangling" ] && echo 1 || echo 0)"
chk "保护目录被整个清掉（含冗余副本，delete() 对非空目录会静默失败）" 0 \
    "$([ -e "$WORK/bak" ] && echo 1 || echo 0)"

echo "⑤ deleteRecursively 绝不跟随软链"
setup_root
mkdir -p "$WORK/root/tobedeleted"
ln -s "$WORK/pub/sessions" "$WORK/root/tobedeleted/link-to-public"
out="$(run delete "$WORK/root/tobedeleted")"
chk "目录删掉了" 0 "$([ -e "$WORK/root/tobedeleted" ] && echo 1 || echo 0)"
chk "公开目录里的对话没被连带删除" '{"id":"conv-1"}' \
    "$(cat "$WORK/pub/sessions/session.jsonl" 2>/dev/null)"
# 悬空软链本身也要删得掉（File.exists() 对它是 false，用它做提前 return 就会留垃圾）
mkdir -p "$WORK/root/withdangling"
ln -s "$WORK/pub/nope" "$WORK/root/withdangling/dead"
run delete "$WORK/root/withdangling" >/dev/null
chk "含悬空软链的目录也能删净" 0 \
    "$([ -e "$WORK/root/withdangling" ] || [ -L "$WORK/root/withdangling" ] && echo 1 || echo 0)"

echo "----------------------------------------------"
if [ "$fail" -eq 0 ]; then echo "全部通过：$pass 条"; else echo "失败 $fail 条（通过 $pass）"; exit 1; fi
