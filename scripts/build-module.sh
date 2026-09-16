#!/usr/bin/env bash
# ============================================================
# 本地一键打包 Magisk / KernelSU 完整刷机包
# 用法:
#   ./scripts/build-module.sh          # 直接打包全内置完整版 (~218MB)
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/magisk-module"
OUTPUT_DIR="$ROOT_DIR/dist"
mkdir -p "$OUTPUT_DIR"

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
zip -r -0 "$OUTPUT_DIR/dsha_ksu_native_full.zip" META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts rootfs.tar.gz >/dev/null
rm -f "$MODULE_DIR/rootfs.tar.gz"
echo "✓ 全内置刷机包已生成: $OUTPUT_DIR/dsha_ksu_native_full.zip"
ls -lh "$OUTPUT_DIR/dsha_ksu_native_full.zip"
