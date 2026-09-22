#!/usr/bin/env bash
# ============================================================
# package-all.sh — DSHA 三大产物一键本地/CI打包总装脚本
# 最终产物 (一共 3 个):
#   1. dist/DSHA-FR 0.1.5rc.2-u2.apk    (前端 APK, 来自 magisk-apk)
#   2. dist/dsha_ksu_native_full.zip (核心完整包, 来自 dsh-magisk)
#   3. dist/dsha_ksu_native_lite.zip (核心 Lite 包, 来自 dsh-magisk)
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
mkdir -p "$DIST_DIR"

STAGE_DIR="/tmp/dsha_pkg_stage_$$"
mkdir -p "$STAGE_DIR"
cleanup() {
    python3 -c "import shutil; shutil.rmtree(, ignore_errors=True)" 2>/dev/null || rm -rf "$STAGE_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "    DSHA for Root 三合一总装打包引擎启动                  "
echo "=========================================================="

REPO_URL="${REPO_URL:-https://github.com/2108282/DSHA.git}"

# 1. 检出或准备前端 APK 源码 (magisk-apk 分支)
echo "==> [1/3] 获取并构建前端 APK (源自 magisk-apk 分支)..."
git clone --depth=1 -b magisk-apk "$REPO_URL" "$STAGE_DIR/apk_src"
cd "$STAGE_DIR/apk_src"
chmod +x gradlew
./gradlew :app:assembleStandardRelease --stacktrace
ORIG_APK=$(ls app/build/outputs/apk/standard/release/*.apk | head -1)
cp -f "$ORIG_APK" "$DIST_DIR/DSHA-FR 0.1.5rc.2-u2.apk"
echo "✓ 产物 1 完成: $DIST_DIR/DSHA-FR 0.1.5rc.2-u2.apk"
cd "$ROOT_DIR"

# 2. 检出或准备核心模块源码 (dsh-magisk 分支)
echo "==> [2/3] 获取并构建核心模块 (源自 dsh-magisk 分支)..."
git clone --depth=1 -b dsh-magisk "$REPO_URL" "$STAGE_DIR/magisk_src"
cd "$STAGE_DIR/magisk_src"
chmod +x scripts/*.sh tools/*.sh magisk-module/*.sh magisk-module/scripts/*.sh 2>/dev/null || true

echo "  -> 按照 dsh-magisk 规范打包极速热更新 Lite 包..."
./scripts/build-module.sh --lite
cp -f dist/dsha_ksu_native_lite.zip "$DIST_DIR/dsha_ksu_native_lite.zip"
echo "✓ 产物 2 完成: $DIST_DIR/dsha_ksu_native_lite.zip"

echo "  -> 按照 dsh-magisk 规范动态层叠熔铸底包并打包全内置 Full 包..."
./scripts/build-module.sh full
cp -f dist/dsha_ksu_native_full.zip "$DIST_DIR/dsha_ksu_native_full.zip"
echo "✓ 产物 3 完成: $DIST_DIR/dsha_ksu_native_full.zip"
cd "$ROOT_DIR"

# 3. 产物汇总与校验和计算
echo "==> [3/3] 计算 3 大交付物 SHA-256 校验和..."
cd "$DIST_DIR"
sha256sum "DSHA-FR 0.1.5rc.2-u2.apk" | tee DSHA-FR 0.1.5rc.2-u2.apk.sha256
sha256sum dsha_ksu_native_full.zip | tee dsha_ksu_native_full.zip.sha256
sha256sum dsha_ksu_native_lite.zip | tee dsha_ksu_native_lite.zip.sha256

echo "=========================================================="
echo "✓ 三大交付物总装完成！清单如下："
ls -lh "$DIST_DIR"
echo "=========================================================="
