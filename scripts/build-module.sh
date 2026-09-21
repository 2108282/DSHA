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

    # 打包 scripts/ 下的全部基础控制脚本 (五大核心控制脚本)
    mkdir -p "$STAGE_LITE/scripts"
    cp -rf "$MODULE_DIR/scripts/"* "$STAGE_LITE/scripts/"

    # 打包 patches/ 下的现场增量补丁脚本 (若存在，专供老用户增量热修)
    if [ -d "$MODULE_DIR/patches" ] && [ -n "$(ls -A "$MODULE_DIR/patches" 2>/dev/null)" ]; then
        mkdir -p "$STAGE_LITE/patches"
        cp -rf "$MODULE_DIR/patches/"* "$STAGE_LITE/patches/"
        echo "  -> 已打包 patches 现场增量补丁至 Lite 模块"
    elif [ -d "$ROOT_DIR/patches" ] && [ -n "$(ls -A "$ROOT_DIR/patches" 2>/dev/null)" ]; then
        mkdir -p "$STAGE_LITE/patches"
        cp -rf "$ROOT_DIR/patches/"* "$STAGE_LITE/patches/"
        echo "  -> 已打包 patches 现场增量补丁至 Lite 模块"
    fi

    # 通用增量层叠：若存在 rootfs-overlay 增量资产，整体打包至 Lite 模块
    # 零硬编码：任何放入 rootfs-overlay 的增量文件均自动纳入 Lite 热更新包
    if [ -d "$ROOT_DIR/rootfs-overlay" ] && [ -n "$(ls -A "$ROOT_DIR/rootfs-overlay" 2>/dev/null)" ]; then
        mkdir -p "$STAGE_LITE/rootfs-overlay"
        cp -af "$ROOT_DIR/rootfs-overlay/." "$STAGE_LITE/rootfs-overlay/"
        echo "  -> 已打包通用 rootfs-overlay 增量层叠资产至 Lite 模块"
    fi

    cd "$STAGE_LITE"
    chmod +x customize.sh service.sh action.sh uninstall.sh scripts/*.sh
    [ -d patches ] && chmod +x patches/*.sh 2>/dev/null || true
    python3 -c "import os; p='$OUTPUT_DIR/dsha_ksu_native_lite.zip'; os.path.exists(p) and os.remove(p)" 2>/dev/null || true
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

# 若本地未找到底包，自动从官方 Release rootfs 下载纯净底包
if [ -z "$TAR_SRC" ]; then
    echo "--> 未检测到本地底包，正在自动从官方 Release (rootfs) 下载纯净底包..."
    DOWN_URL="https://github.com/2108282/DSHA/releases/download/rootfs/rootfs.tar.gz"
    mkdir -p /tmp/dsha_download
    DL_FILE="/tmp/dsha_download/rootfs.tar.gz"
    DL_OK=0

    # 1. 优先尝试 GitHub 官方 CLI 工具 (在 Actions 环境下具备原生认证与高抗抖动性)
    if command -v gh >/dev/null 2>&1; then
        echo "  [下载通道 1] 使用 gh CLI 下载..."
        if gh release download rootfs --repo "${GITHUB_REPOSITORY:-2108282/DSHA}" --pattern "rootfs.tar.gz" --dir /tmp/dsha_download 2>/dev/null && [ -s "$DL_FILE" ]; then
            DL_OK=1
        fi
    fi

    # 2. 备选高可靠 Python 流式下载器 (显式指定 User-Agent，自动跟随 CDN 重定向，规避 curl 404)
    if [ "$DL_OK" = "0" ] && command -v python3 >/dev/null 2>&1; then
        echo "  [下载通道 2] 使用 Python 原生流式下载器..."
        if python3 -c '
import urllib.request, sys, time
url = sys.argv[1]
dst = sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"})
for attempt in range(1, 4):
    try:
        print(f"    第 {attempt} 次连接尝试...")
        with urllib.request.urlopen(req, timeout=60) as resp:
            total = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            with open(dst, "wb") as f:
                while True:
                    chunk = resp.read(1024 * 1024)
                    if not chunk: break
                    f.write(chunk)
                    downloaded += len(chunk)
            print(f"    ✓ 下载完成: {downloaded} 字节")
            sys.exit(0)
    except Exception as e:
        print(f"    ⚠️ 异常: {e}")
        time.sleep(2)
sys.exit(1)
' "$DOWN_URL" "$DL_FILE" && [ -s "$DL_FILE" ]; then
            DL_OK=1
        fi
    fi

    # 3. 备选带 Header 的 curl
    if [ "$DL_OK" = "0" ] && command -v curl >/dev/null 2>&1; then
        echo "  [下载通道 3] 使用 curl 下载..."
        if curl -L -A "Mozilla/5.0 (X11; Linux x86_64)" --retry 3 -f -o "$DL_FILE" "$DOWN_URL" 2>/dev/null && [ -s "$DL_FILE" ]; then
            DL_OK=1
        fi
    fi

    # 4. 备选 wget
    if [ "$DL_OK" = "0" ] && command -v wget >/dev/null 2>&1; then
        echo "  [下载通道 4] 使用 wget 下载..."
        if wget -U "Mozilla/5.0 (X11; Linux x86_64)" -t 3 -O "$DL_FILE" "$DOWN_URL" 2>/dev/null && [ -s "$DL_FILE" ]; then
            DL_OK=1
        fi
    fi

    if [ "$DL_OK" = "0" ]; then
        echo "错误: 纯净底包下载失败，请手动将 rootfs.tar.gz 放入 /sdcard/Download/DSHA/ 目录" >&2
        exit 1
    fi
    TAR_SRC="$DL_FILE"
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
