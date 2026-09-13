#!/usr/bin/env bash
# ============================================================
# 本地一键打包 Magisk / KernelSU 模块脚本
# 用法:
#   ./scripts/build-module.sh          # 默认打包轻量版 (外置底包模式, ~20KB)
#   ./scripts/build-module.sh --full   # 打包全内置版 (含 rootfs.tar.gz, ~200MB)
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/magisk-module"
OUTPUT_DIR="$ROOT_DIR/dist"
mkdir -p "$OUTPUT_DIR"

MODE="${1:-lite}"

if [ "$MODE" = "--full" ] || [ "$MODE" = "full" ]; then
    echo "==> 正在打包【全内置完整版】模块 (含 rootfs.tar.gz)..."
    TAR_SRC=""
    for p in "$ROOT_DIR/rootfs.tar.gz" \
             "/sdcard/Download/DSHA/rootfs.tar.gz" \
             "/data/media/0/Download/DSHA/rootfs.tar.gz" \
             "/sdcard/Download/DSHA/dsha-ksu-project/release/rootfs.tar.gz"; do
        if [ -f "$p" ]; then TAR_SRC="$p"; break; fi
    done
    if [ -z "$TAR_SRC" ]; then
        echo "错误: 未找到本地 rootfs.tar.gz 底包，无法打包全内置版！" >&2
        echo "请将底包放入 /sdcard/Download/DSHA/rootfs.tar.gz 或项目根目录下后重试。" >&2
        exit 1
    fi
    echo "  使用底包: $TAR_SRC"
    cp "$TAR_SRC" "$MODULE_DIR/rootfs.tar.gz"
    cd "$MODULE_DIR"
    zip -r9 "$OUTPUT_DIR/dsha_ksu_native_full.zip" META-INF module.prop customize.sh service.sh scripts rootfs.tar.gz >/dev/null
    rm -f "$MODULE_DIR/rootfs.tar.gz"
    echo "✓ 全内置刷机包已生成: $OUTPUT_DIR/dsha_ksu_native_full.zip"
    ls -lh "$OUTPUT_DIR/dsha_ksu_native_full.zip"
else
    echo "==> 正在打包【轻量版】模块 (外置底包模式, ~20KB)..."
    cd "$MODULE_DIR"
    zip -r9 "$OUTPUT_DIR/dsha_ksu_native_lite.zip" META-INF module.prop customize.sh service.sh scripts >/dev/null
    echo "✓ 轻量刷机包已生成: $OUTPUT_DIR/dsha_ksu_native_lite.zip"
    ls -lh "$OUTPUT_DIR/dsha_ksu_native_lite.zip"
fi
