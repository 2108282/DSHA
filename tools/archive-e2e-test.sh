#!/usr/bin/env bash
# 插件归档识别的端到端验证：造**真实**归档，走真实解包，再用 ArchiveProbe 判布局。
#
# 为什么单独一个脚本：pure-logic-test 里那些 pluginRoots 断言喂的是**手写的路径字符串**，
# 而真实归档里的路径长什么样（./ 前缀、npm pack 的 package/ 外层、GitHub zip 的
# <repo>-<branch>/ 外层、目录条目、软链条目）只有真打一个包才知道。
# 「合成输入过了、真实输入不过」正是这个项目栽过的那类问题。
#
# 不需要设备，也不需要 rootfs。
set -u
cd "$(dirname "$0")/.." || exit 1

JAVA_DIR="app/src/main/java/com/deepseekharness/app"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0
ok()   { pass=$((pass+1)); printf '  ok   %s\n' "$1"; }
bad()  { fail=$((fail+1)); printf '  FAIL %s\n' "$1"; }
check(){ # check <描述> <期望> <实际>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 [$2] 实际 [$3]）"; fi
}

echo "== 准备真实归档 =="
SRC="$WORK/src"; mkdir -p "$SRC"

# ① 单插件包：根目录直接是 package.json（旧实现会把它拆成一堆「插件」）
mkdir -p "$SRC/single/lib"
cat > "$SRC/single/package.json" <<'JSON'
{ "name": "dsh-single-demo", "main": "lib/index.js",
  "dsh": { "bundle": { "patch": "./cordis.patch.yml" } } }
JSON
echo "export function apply(){}" > "$SRC/single/lib/index.js"
cat > "$SRC/single/cordis.patch.yml" <<'YML'
- insert:
    - id: single-demo
      name: dsh-single-demo
YML
( cd "$SRC/single" && tar -czf "$WORK/single.tar.gz" . ) 2>/dev/null

# ② 多插件包：两个子目录各有 package.json，且各自带 node_modules（不该被算成插件）
mkdir -p "$SRC/multi/plug-a/node_modules/dep" "$SRC/multi/plug-b"
echo '{ "name": "plug-a", "main": "index.js" }' > "$SRC/multi/plug-a/package.json"
echo '{ "name": "some-dep" }'                  > "$SRC/multi/plug-a/node_modules/dep/package.json"
echo '{ "name": "plug-b", "main": "index.js" }' > "$SRC/multi/plug-b/package.json"
( cd "$SRC/multi" && tar -czf "$WORK/multi.tar.gz" . ) 2>/dev/null

# ③ GitHub 下载的 zip：外面多包一层 <repo>-<branch>/
mkdir -p "$SRC/gh/repo-main/plugins/x" "$SRC/gh/repo-main/plugins/y"
echo '{ "name": "@sc/x", "main": "lib/i.js" }' > "$SRC/gh/repo-main/plugins/x/package.json"
echo '{ "name": "@sc/y", "main": "lib/i.js" }' > "$SRC/gh/repo-main/plugins/y/package.json"
echo '{ "name": "monorepo-root", "private": true }' > "$SRC/gh/repo-main/package.json"
( cd "$SRC/gh" && zip -qr "$WORK/gh.zip" . ) 2>/dev/null

# ④ npm pack 出来的 tarball：内层固定是 package/
mkdir -p "$SRC/npm/package/lib"
echo '{ "name": "dsh-packed", "main": "lib/index.js" }' > "$SRC/npm/package/package.json"
echo "//" > "$SRC/npm/package/lib/index.js"
( cd "$SRC/npm" && tar -czf "$WORK/packed.tgz" package ) 2>/dev/null

# ⑤ 恶意 zip：带 ../ 逃逸条目
mkdir -p "$SRC/evil/ok"
echo '{ "name": "evil", "main": "i.js" }' > "$SRC/evil/ok/package.json"
echo "pwned" > "$WORK/evil-payload"
( cd "$SRC/evil" && zip -qr "$WORK/evil.zip" . && \
  cd "$WORK" && zip -q "$WORK/evil.zip" evil-payload && \
  printf '' ) 2>/dev/null
# 手工塞一个 ../ 条目（zip 命令本身会规范化路径，所以直接改名塞）
( cd "$WORK" && mkdir -p esc && cp evil-payload esc/ && \
  zip -q --names-stdin evil.zip <<< "esc/evil-payload" ) 2>/dev/null

ls -1 "$WORK"/*.tar.gz "$WORK"/*.tgz "$WORK"/*.zip 2>/dev/null | sed 's/^/  造好 /'

echo
echo "== 编译驱动（复用 ArchiveProbe 与 PluginSpec 本体）=="
DRV="$WORK/drv"; mkdir -p "$DRV"
for f in ArchiveProbe PluginSpec PatchToggle; do
  sed 's/^package .*/ /' "$JAVA_DIR/$f.java" > "$DRV/$f.java"
done
cat > "$DRV/E2E.java" <<'JAVA'
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** 用真实归档跑一遍：读文件头判格式 → 真解包 → 收集相对路径 → pluginRoots 判布局。 */
public class E2E {
    static int pass = 0, fail = 0;
    static void check(String what, String want, String got) {
        if (Objects.equals(want, got)) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "（期望 [" + want + "] 实际 [" + got + "]）"); }
    }

    public static void main(String[] a) throws Exception {
        File work = new File(a[0]);

        // —— 格式识别：读真实文件头 ——
        check("格式: 真实 tar.gz 认成 GZIP", "1", "" + ArchiveProbe.kindOf(head(new File(work, "single.tar.gz"))));
        check("格式: 真实 zip 认成 ZIP", "2", "" + ArchiveProbe.kindOf(head(new File(work, "gh.zip"))));
        check("格式: 真实 .tgz 也是 GZIP", "1", "" + ArchiveProbe.kindOf(head(new File(work, "packed.tgz"))));

        // —— 布局识别：真解包后的路径 ——
        String[] r1 = ArchiveProbe.pluginRoots(untarPaths(new File(work, "single.tar.gz")));
        check("布局: 单插件包（根有 package.json）", "true", "" + ArchiveProbe.isSinglePlugin(r1));

        String[] r2 = ArchiveProbe.pluginRoots(untarPaths(new File(work, "multi.tar.gz")));
        Arrays.sort(r2);
        check("布局: 多插件包认出两个", "[plug-a, plug-b]", Arrays.toString(r2));

        // GitHub 下载的 monorepo zip：最浅一层是仓库管理包，真插件在下一层。
        // 这正是「只取最浅一层」会装错东西的地方。
        List<String[]> ghLayers = ArchiveProbe.pluginRootsByDepth(unzipPaths(new File(work, "gh.zip"), null));
        check("布局: GitHub zip 最浅一层是仓库管理包那一层", "[repo-main]",
                Arrays.toString(ghLayers.get(0)));
        String[] deeper = ghLayers.size() > 1 ? ghLayers.get(1) : new String[0];
        Arrays.sort(deeper);
        check("布局: 往下一层才是真插件（两个）",
                "[repo-main/plugins/x, repo-main/plugins/y]", Arrays.toString(deeper));

        String[] r4 = ArchiveProbe.pluginRoots(untarPaths(new File(work, "packed.tgz")));
        check("布局: npm pack 的 package/ 外层被剥到一层", "[package]", Arrays.toString(r4));

        // —— zip slip：真解包时逃逸条目必须被拒 ——
        List<String> refused = new ArrayList<>();
        unzipPaths(new File(work, "evil.zip"), refused);
        check("安全: 逃逸条目一个都不许落地", "true", "" + refused.stream().noneMatch(s -> s.contains("..")));

        // —— PluginSpec 对真实形态 ——
        check("spec: 真实 tarball URL", "6", "" + PluginSpec.classify(
                "https://github.com/o/r/releases/download/v1/pkg-1.0.0.tgz"));
        check("spec: 本地 .tgz 绝对路径", "8", "" + PluginSpec.classify(work + "/packed.tgz"));

        // —— PatchToggle 对真实插件 patch ——
        String pluginPatch = readAll(new File(a[1]));
        List<String> ids = PatchToggle.insertedIds(pluginPatch);
        check("patch: 从真实 cordis.patch.yml 抠出行 id", "[single-demo]", ids.toString());

        System.out.println();
        System.out.println(fail == 0 ? "Java 侧全部通过：" + pass + " 条"
                : "Java 侧失败 " + fail + " 条（通过 " + pass + "）");
        if (fail != 0) System.exit(1);
    }

    static byte[] head(File f) throws IOException {
        byte[] b = new byte[512];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(b);
            return n <= 0 ? new byte[0] : Arrays.copyOf(b, n);
        }
    }

    static String readAll(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** 用系统 tar 解包（走真实工具，不做自己的 tar 解析），返回相对路径。 */
    static String[] untarPaths(File tgz) throws Exception {
        File out = new File(tgz.getParentFile(), tgz.getName() + ".x");
        out.mkdirs();
        Process p = new ProcessBuilder("tar", "-xzf", tgz.getAbsolutePath(), "-C", out.getAbsolutePath())
                .redirectErrorStream(true).start();
        p.waitFor();
        List<String> rel = new ArrayList<>();
        collect(out, "", rel);
        return rel.toArray(new String[0]);
    }

    /** 用 ZipInputStream 解（与 App 里 unzipSafe 同一条路），顺便记下被拒的条目。 */
    static String[] unzipPaths(File zip, List<String> refused) throws Exception {
        File out = new File(zip.getParentFile(), zip.getName() + ".x");
        out.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!ArchiveProbe.safeEntryName(e.getName())) {
                    if (refused != null) refused.add(e.getName());
                    continue;
                }
                File f = new File(out, e.getName());
                if (!f.getCanonicalPath().startsWith(out.getCanonicalPath() + File.separator)) {
                    if (refused != null) refused.add(e.getName());
                    continue;
                }
                if (e.isDirectory()) { f.mkdirs(); continue; }
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zis.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
        }
        List<String> rel = new ArrayList<>();
        collect(out, "", rel);
        return rel.toArray(new String[0]);
    }

    static void collect(File dir, String prefix, List<String> out) {
        File[] cs = dir.listFiles();
        if (cs == null) return;
        for (File c : cs) {
            String rel = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
            if (c.isDirectory()) collect(c, rel, out);
            else out.add(rel);
        }
    }
}
JAVA
javac -encoding UTF-8 -d "$DRV" "$DRV"/*.java 2>&1 | head -8
if [ ! -f "$DRV/E2E.class" ]; then echo "  驱动编译失败"; exit 1; fi

echo
echo "== 跑真实归档 =="
java -cp "$DRV" E2E "$WORK" "$SRC/single/cordis.patch.yml"
jrc=$?

echo
echo "== shell 侧的实测 =="
# .npmrc：确认写出来的是真换行、不是字面反斜杠 n
echo 'registry=https://registry.npmmirror.com' > "$WORK/npmrc"
check ".npmrc 恰好一行（说明末尾是真换行）" "1" "$(wc -l < "$WORK/npmrc" | tr -d ' ')"
check ".npmrc 里没有任何反斜杠" "0" "$(grep -c '\\' "$WORK/npmrc" || true)"

# tar -czhf 必须解引用软链（插件在 node_modules 里就是软链，不解引用导出来是空壳）
mkdir -p "$WORK/lnk/real" && echo hello > "$WORK/lnk/real/file.txt"
( cd "$WORK/lnk" && ln -s real link ) 2>/dev/null
( cd "$WORK/lnk" && tar -czhf "$WORK/deref.tar.gz" link ) 2>/dev/null
inside=$(tar -tzf "$WORK/deref.tar.gz" 2>/dev/null | grep -c 'link/file.txt' || true)
check "tar -czhf 解引用软链（包里有 link/file.txt）" "1" "$inside"
( cd "$WORK/lnk" && tar -czf "$WORK/noderef.tar.gz" link ) 2>/dev/null
inside2=$(tar -tzf "$WORK/noderef.tar.gz" 2>/dev/null | grep -c 'link/file.txt' || true)
check "不加 -h 时软链只打进一行链接（这就是备份丢对话的原因）" "0" "$inside2"

echo
echo "----------------------------------------------"
if [ "$fail" -eq 0 ] && [ "$jrc" -eq 0 ]; then
  echo "全部通过（shell 侧 $pass 条）"
  exit 0
else
  echo "有失败：shell 侧 $fail 条 / Java 侧退出码 $jrc"
  exit 1
fi
