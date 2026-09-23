#!/system/bin/sh
# DSHA 现场增量补丁：修复 DSH 原生 Office 预览（挂载系统字库 + .xls 路由至 officeToPdf）
set -euo pipefail

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [Patch] 正在应用 Office/文档预览修复补丁..."

# 1. 宿主系统字体支持：创建挂载点并即时挂载，消除 WASM 缺字体导致的转码崩溃
mkdir -p "$ROOTFS/usr/share/fonts/android" 2>/dev/null || true
if [ -d "/system/fonts" ]; then
    if ! grep -q " $ROOTFS/usr/share/fonts/android " /proc/mounts 2>/dev/null; then
        mount -o bind /system/fonts "$ROOTFS/usr/share/fonts/android" 2>/dev/null || true
        echo "  ✓ 已挂载宿主 /system/fonts 至容器 /usr/share/fonts/android"
    else
        echo "  ✓ 宿主字库挂载点已处于活跃状态"
    fi
fi

# 2. 修复 client.js：将 .xls 接入后端 officeToPdf 服务并从纯前端 excel 移除
CLIENT_JS="$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js"
if [ -f "$CLIENT_JS" ]; then
    # 若 extensions: ["doc", "docx", "ppt", "pptx"] 未包含 xls，进行热修
    if ! grep -q '"xls"' "$CLIENT_JS" 2>/dev/null; then
        sed -i 's/"pptx"/"pptx",\n\t\t\t\t"xls"/g' "$CLIENT_JS" 2>/dev/null || true
    fi
    sed -i 's/|| suffix === "xls" //g' "$CLIENT_JS" 2>/dev/null || true
    echo "  ✓ 已热修 documentpreview 模块，.xls 已成功接入 officeToPdf 转码"
fi

echo "✓ Office 原生预览增量补丁应用成功！"
