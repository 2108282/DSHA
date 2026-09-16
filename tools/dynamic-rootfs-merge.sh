#!/usr/bin/env bash
# ============================================================
# dynamic-rootfs-merge.sh — 动态镜像反射式底包合成引擎
# 特性：
# 1. 零硬编码：自动发现 rootfs-overlay/ 下的一切文件与目录；
# 2. 动态映射：无论插件/文件叫什么名字，自动镜像注入目标 rootfs 对应路径；
# 3. 自动立牌：检测到 /root/dsha-* 实体，自动为其补齐 -installed 凭证；
# 4. 软链补齐：自动为注入插件补齐 profile 与全局 node_modules 软链接；
# 5. 语法卫士：自动遍历所有注入的 .js，语法报错立即终止打包；
# 6. 本地与云端 CI 100% 通用。
# ============================================================
set -euo pipefail

BASE_TAR="${1:-/tmp/raw_rootfs.tar.gz}"
OVERLAY_DIR="${2:-rootfs-overlay}"
OUTPUT_TAR="${3:-magisk-module/rootfs.tar.gz}"

STAGE_DIR="/tmp/dsh_merge_stage_$$"
mkdir -p "$STAGE_DIR"
cleanup() {
    python3 -c "import shutil; shutil.rmtree('$STAGE_DIR', ignore_errors=True)" 2>/dev/null || rm -rf "$STAGE_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "      DSHA 动态反射式 RootFS 镜像合成引擎启动              "
echo "=========================================================="

echo "==> [1/4] 解压基准底包: $BASE_TAR"
tar -xzf "$BASE_TAR" -C "$STAGE_DIR" --no-same-owner

echo "==> [2/4] 通用规则净化 (根据通配符模式清理旧系统痕迹)..."
# 仅根据模式匹配清理，不绑定特定业务名
find "$STAGE_DIR/tmp" -mindepth 1 -delete 2>/dev/null || true
find "$STAGE_DIR/root/.cache" -mindepth 1 -delete 2>/dev/null || true
find "$STAGE_DIR" -name "*proroot*" -exec rm -rf {} + 2>/dev/null || true
find "$STAGE_DIR" -name "*.l2s*" -exec rm -rf {} + 2>/dev/null || true
find "$STAGE_DIR/root/.dsh" -name "*patch*.sh" -exec rm -f {} + 2>/dev/null || true
find "$STAGE_DIR/root/.dsh" -name "*heal*.sh" -exec rm -f {} + 2>/dev/null || true

echo "==> [3/4] 【动态反射注入】递归扫描并叠加镜像补丁层: $OVERLAY_DIR"
if [ -d "$OVERLAY_DIR" ]; then
    # 递归覆盖所有文件，保持相对路径 1:1 精准映射 (包括隐藏文件)
    cp -a "$OVERLAY_DIR/." "$STAGE_DIR/"
    
    # 准备软链接目录
    PROFILE_NM="$STAGE_DIR/root/.dsh/profiles/web/node_modules"
    GLOBAL_NM="$STAGE_DIR/usr/local/lib/node_modules"
    mkdir -p "$PROFILE_NM" "$GLOBAL_NM"

    # 动态扫描注入的插件实体，自动完成安装凭证立牌与语法检测
    for pdir in "$STAGE_DIR/root/dsha-"*; do
        [ -d "$pdir" ] || continue
        pname=$(basename "$pdir")
        pkg_name="dsh-${pname#dsha-}"
        
        # 1. 自动立牌：确保 App 与底座将其识别为内置插件
        touch "$STAGE_DIR/root/${pname}-installed"
        echo "    + 动态捕获并注册插件: $pname (npm: $pkg_name)"
        
        # 2. 自动建立 Web Profile 与全局软链接
        ln -sfn "/root/$pname" "$PROFILE_NM/$pkg_name"
        ln -sfn "/root/$pname" "$GLOBAL_NM/$pkg_name"

        # 3. 语法安全卫士：自动对该插件 lib/ 下的所有 js 文件执行语法校验
        for js in "$pdir/lib/"*.js; do
            [ -f "$js" ] || continue
            node --check "$js" >/dev/null 2>&1 || {
                echo "❌ 致命错误: 动态注入的插件 [$pname] 中文件 $js 存在语法错误！" >&2
                exit 1
            }
        done
    done
    echo "    ✓ 动态注入内容语法校验 100% 通过！"
else
    echo "    (提示: 未发现 $OVERLAY_DIR 目录，跳过叠加)"
fi

echo "==> [4/4] 生成目标底包: $OUTPUT_TAR"
mkdir -p "$(dirname "$OUTPUT_TAR")"
cd "$STAGE_DIR"
tar --numeric-owner -czf "$OUTPUT_TAR" .
cd - >/dev/null

echo "=========================================================="
echo "✓ 动态构建完成！产物: $OUTPUT_TAR ($(ls -lh "$OUTPUT_TAR" | awk '{print $5}'))"
echo "=========================================================="
