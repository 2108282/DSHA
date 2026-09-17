#!/usr/bin/env bash
# ============================================================
# 本地一键打包 Magisk / KernelSU 刷机包
# 用法:
#   ./scripts/build-module.sh          # 默认打包全内置完整版 (~218MB)
#   ./scripts/build-module.sh --lite   # 单独打包极速热更新包 (Lite, ~27KB)
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/magisk-module"
OUTPUT_DIR="$ROOT_DIR/dist"
mkdir -p "$OUTPUT_DIR"

MODE="${1:-full}"

if [ "$MODE" = "--lite" ] || [ "$MODE" = "lite" ]; then
    echo "==> 正在准备打包【DSHA 极速热更新补丁包】(Lite, 仅脚本)..."
    STAGE_LITE="/tmp/dsha_build_lite_$$"
    mkdir -p "$STAGE_LITE"
    cleanup_lite() {
        python3 -c "import shutil; shutil.rmtree('$STAGE_LITE', ignore_errors=True)" 2>/dev/null || rm -rf "$STAGE_LITE" 2>/dev/null || true
    }
    trap cleanup_lite EXIT INT TERM

    cp -rf "$MODULE_DIR/META-INF" "$STAGE_LITE/"
    cp -f "$MODULE_DIR/module.prop" "$STAGE_LITE/"
    cp -f "$MODULE_DIR/service.sh" "$STAGE_LITE/"
    cp -f "$MODULE_DIR/action.sh" "$STAGE_LITE/"
    cp -f "$MODULE_DIR/uninstall.sh" "$STAGE_LITE/"
    
    # 使用专用的两步音量键交互安装器
    cp -f "$MODULE_DIR/customize.lite.sh" "$STAGE_LITE/customize.sh"

    # 打包 scripts/ 下的全部脚本 (包含五大基础脚本: start/stop/status/term/lan-proxy + 增量补丁脚本)
    mkdir -p "$STAGE_LITE/scripts"
    cp -rf "$MODULE_DIR/scripts/"* "$STAGE_LITE/scripts/"

    # 打包核心局域网代理守护实体 dsha-lan-proxy.js 至 Lite 模块
    if [ -f "$ROOT_DIR/rootfs-overlay/root/.dsh/dsha-lan-proxy.js" ]; then
        mkdir -p "$STAGE_LITE/root/.dsh"
        cp -f "$ROOT_DIR/rootfs-overlay/root/.dsh/dsha-lan-proxy.js" "$STAGE_LITE/root/.dsh/dsha-lan-proxy.js"
        chmod 755 "$STAGE_LITE/root/.dsh/dsha-lan-proxy.js"
    elif [ -f "/root/.dsh/dsha-lan-proxy.js" ]; then
        mkdir -p "$STAGE_LITE/root/.dsh"
        cp -f "/root/.dsh/dsha-lan-proxy.js" "$STAGE_LITE/root/.dsh/dsha-lan-proxy.js"
        chmod 755 "$STAGE_LITE/root/.dsh/dsha-lan-proxy.js"
    fi

    cd "$STAGE_LITE"
    chmod +x customize.sh service.sh action.sh uninstall.sh scripts/*.sh
    zip -r -9 "$OUTPUT_DIR/dsha_ksu_native_lite.zip" . >/dev/null
    cd "$ROOT_DIR"
    echo "✓ 极速热更新包已生成: $OUTPUT_DIR/dsha_ksu_native_lite.zip"
    ls -lh "$OUTPUT_DIR/dsha_ksu_native_lite.zip"
    exit 0
fi

# 完整包编译流程（步骤完全保持不变）
echo "==> 正在准备打包【DSHA 全内置完整刷机包】(含纯净 rootfs.tar.gz)..."
TAR_SRC=""
for p in "$ROOT_DIR/magisk-module/rootfs.tar.gz" \
         "$ROOT_DIR/rootfs.tar.gz" \
         "/sdcard/Download/DSHA/rootfs.tar.gz" \
         "/data/media/0/Download/DSHA/rootfs.tar.gz" \
         "/sdcard/Download/DSHA/dsha-ksu-project/release/rootfs.tar.gz"; do
    if [ -f "$p" ]; then TAR_SRC="$p"; break; fi
done

# 若本地未找到底包，自动从官方 Release 0.1.5rc.2-base 下载纯净底包
if [ -z "$TAR_SRC" ]; then
    echo "--> 未检测到本地底包，正在自动从官方 Release (0.1.5rc.2-base) 下载纯净底包..."
    DOWN_URL="https://github.com/2108282/DSHA/releases/download/0.1.5rc.2-base/rootfs.tar.gz"
    mkdir -p /tmp/dsha_download
    if command -v curl >/dev/null 2>&1; then
        curl -L -f -o /tmp/dsha_download/rootfs.tar.gz "$DOWN_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O /tmp/dsha_download/rootfs.tar.gz "$DOWN_URL"
    else
        echo "错误: 缺少 curl 或 wget，请手动将 rootfs.tar.gz 放入 /sdcard/Download/DSHA/ 目录" >&2
        exit 1
    fi
    TAR_SRC="/tmp/dsha_download/rootfs.tar.gz"
fi

echo "  使用底包原料: $TAR_SRC"

# 若存在 rootfs-overlay，则调用动态层叠引擎合成，确保包含最新插件
if [ -d "$ROOT_DIR/rootfs-overlay" ] && [ -f "$ROOT_DIR/tools/dynamic-rootfs-merge.sh" ]; then
    echo "  检测到 rootfs-overlay，执行动态反射合成..."
    bash "$ROOT_DIR/tools/dynamic-rootfs-merge.sh" "$TAR_SRC" "$ROOT_DIR/rootfs-overlay" "$MODULE_DIR/rootfs.tar.gz"
else
    cp -f "$TAR_SRC" "$MODULE_DIR/rootfs.tar.gz"
fi

cd "$MODULE_DIR"
chmod +x customize.sh service.sh action.sh uninstall.sh scripts/*.sh
cp -f rootfs.tar.gz "$OUTPUT_DIR/rootfs.tar.gz"
zip -r -0 "$OUTPUT_DIR/dsha_ksu_native_full.zip" META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts rootfs.tar.gz >/dev/null
rm -f "$MODULE_DIR/rootfs.tar.gz"
echo "✓ 全内置刷机包已生成: $OUTPUT_DIR/dsha_ksu_native_full.zip"
ls -lh "$OUTPUT_DIR/dsha_ksu_native_full.zip"
