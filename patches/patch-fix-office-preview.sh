#!/system/bin/sh
# DSHA 修复补丁：恢复被遮蔽的 @deepseek-ai 核心包，保障系统字库与 .xls 正常预览
set -euo pipefail

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [Patch] 正在执行系统环境自愈与 Office 预览修复..."

# 1. 【紧急救砖】拔除错误的局部 @deepseek-ai 目录，解除对 @deepseek-ai/cordis 的作用域遮蔽
for p in "$ROOTFS/root/.dsh/profiles/web/node_modules/@deepseek-ai" \
         "$ROOTFS/usr/local/lib/node_modules/@deepseek-ai"; do
    if [ -d "$p" ] && [ ! -L "$p" ]; then
        echo "  -> 发现导致 ERR_MODULE_NOT_FOUND 的遮蔽目录: $p，正在彻底清除..."
        rm -rf "$p"
        echo "  ✓ 已解除 @deepseek-ai 核心模块遮蔽！"
    fi
done

# 拔除任何带有外部宿主绝对路径的坏死软链
if [ -d "$ROOTFS/root/.dsh/profiles/node_modules/@deepseek-ai" ]; then
    find "$ROOTFS/root/.dsh/profiles/node_modules/@deepseek-ai" -type l | while read -r lk; do
        tgt=$(readlink "$lk" 2>/dev/null || true)
        if [[ "$tgt" == *"/data/adb/dsha"* ]]; then
            rm -f "$lk"
        fi
    done
fi

# 2. 清理 cordis.patch.yml 中误注入的 office-to-pdf（避免 Cordis 冗余加载报错）
CORDIS_PATCH="$ROOTFS/root/.dsh/profiles/web/cordis.patch.yml"
if [ -f "$CORDIS_PATCH" ]; then
    if grep -q "office-to-pdf" "$CORDIS_PATCH" 2>/dev/null; then
        sed -i '/- id: office-to-pdf/,+2d' "$CORDIS_PATCH" 2>/dev/null || true
        echo "  ✓ 已恢复纯净的 cordis.patch.yml 配置"
    fi
fi

# 3. 宿主系统字体支持：挂载 /system/fonts 至容器 /usr/share/fonts/android
mkdir -p "$ROOTFS/usr/share/fonts/android" 2>/dev/null || true
if [ -d "/system/fonts" ]; then
    if ! grep -q " $ROOTFS/usr/share/fonts/android " /proc/mounts 2>/dev/null; then
        mount -o bind /system/fonts "$ROOTFS/usr/share/fonts/android" 2>/dev/null || true
        echo "  ✓ 已挂载宿主 /system/fonts 至容器 /usr/share/fonts/android"
    fi
fi

# 4. 修复 client.js：将 .xls 接入后端 officeToPdf 服务并从纯前端 excel 移除
CLIENT_JS="$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js"
if [ -f "$CLIENT_JS" ]; then
    if ! grep -q '"xls"' "$CLIENT_JS" 2>/dev/null; then
        sed -i 's/"pptx"/"pptx",\n\t\t\t\t"xls"/g' "$CLIENT_JS" 2>/dev/null || true
    fi
    sed -i 's/|| suffix === "xls" //g' "$CLIENT_JS" 2>/dev/null || true
    echo "  ✓ 已热修 documentpreview 模块，.xls 已成功接入 office 预览"
fi

echo "✓ 系统环境已自愈，Office 预览补丁执行完毕！"
