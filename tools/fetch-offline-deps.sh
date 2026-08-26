#!/usr/bin/env bash
# 把 gradle 解析不到的依赖用 curl 抓下来放进 mavenLocal（~/.m2/repository）。
#
# **为什么需要这个脚本**：开发这个项目的机器是手机上的 proot 容器，网络时通时不通 ——
# 实测同一分钟内 curl 能从 jitpack.io 拿到 HTTP 200，gradle 却报
#   Could not GET '…/terminal-view-0.118.0.pom' > jitpack.io: Temporary failure in name resolution
# 而 gradle 解析失败一次要白跑七分半钟（这台机器上 configuration + 重试就这么慢）。
# 把 aar/pom 先抓到 mavenLocal，之后 `gradle --offline` 33 秒就能编完。
#
# settings.gradle 里 mavenLocal() 放在最前面，所以本地有就直接用。CI 上没有 .m2，
# 那一条直接跳过、照旧从 JitPack 拉 —— 线上行为不变。
#
# 用法：bash tools/fetch-offline-deps.sh
# 幂等：已经存在且非空的文件会跳过；想重新抓就先删掉对应目录。
set -uo pipefail

M="${HOME}/.m2/repository"
JITPACK="https://jitpack.io"
CENTRAL="https://repo.maven.apache.org/maven2"

ok=0
fail=0

# fetch <base-url> <group-path> <artifact> <version> <ext...>
fetch() {
    local base="$1" gpath="$2" art="$3" ver="$4"
    shift 4
    local dir="$M/$gpath/$art/$ver"
    mkdir -p "$dir"
    local ext
    for ext in "$@"; do
        local file="$dir/$art-$ver.$ext"
        if [ -s "$file" ]; then
            echo "  ✓ 已有 $art-$ver.$ext"
            ok=$((ok + 1))
            continue
        fi
        if curl -fsSL --max-time 120 -o "$file" "$base/$gpath/$art/$ver/$art-$ver.$ext"; then
            echo "  ✓ 抓到 $art-$ver.$ext（$(stat -c %s "$file") 字节）"
            ok=$((ok + 1))
        else
            echo "  ✗ 失败 $art-$ver.$ext" >&2
            rm -f "$file"
            fail=$((fail + 1))
        fi
    done
}

echo "==> Termux 终端库（真 PTY 终端用，Apache 2.0）"
# 坐标别照 Termux Wiki 抄：Wiki 写的是 com.termux:terminal-view，那个 groupId 在 JitPack
# 上会被当成仓库 termux/terminal-view 去找（不存在）→ 401。JitPack 多模块项目的真实
# groupId 是「域名 + 仓库名」，即 com.termux.termux-app。查证：
#   curl https://jitpack.io/api/builds/com.termux/termux-app/0.118.0 → modules: [...]
fetch "$JITPACK" "com/termux/termux-app" "terminal-view" "0.118.0" aar pom
fetch "$JITPACK" "com/termux/termux-app" "terminal-emulator" "0.118.0" aar pom

echo "==> guava 占位包（**目前用不到，留着以防将来引 termux-shared**）"
# 只有引入 termux-shared 时才需要它：termux-shared 依赖完整版 guava，会和
# listenablefuture:1.0 撞 Duplicate class，这个空包正好顶掉后者。
# 只用 terminal-view 的话千万别往 build.gradle 里加 —— 项目里没有 guava，加了等于把
# 真正的 ListenableFuture 接口换成空的，androidx 的 concurrent-futures 失去父接口，
# App 一启动 ProfileInstaller 就 NoClassDefFoundError（真机崩过，见 app/build.gradle 注释）。
fetch "$CENTRAL" "com/google/guava" \
      "listenablefuture" "9999.0-empty-to-avoid-conflict-with-guava" jar pom

echo
echo "成功 $ok 个，失败 $fail 个 · 仓库位置 $M"
if [ "$fail" -gt 0 ]; then
    echo "有失败项 —— 网络不通就过几分钟重跑，脚本是幂等的" >&2
    exit 1
fi
echo "现在可以 gradle --offline 编译了"
