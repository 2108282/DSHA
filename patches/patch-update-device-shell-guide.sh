#!/system/bin/sh
# ============================================================
# patch-update-device-shell-guide.sh — DSHA 极速热更新增量补丁
# 修复内容：
# 1. 升级 dsh-device-shell-guide 插件版本号至 0.1.17；
# 2. 将 peerDependencies 范围对齐适配 DSH 0.1.7-rc.1，根除启动与插件列表报不兼容异常。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在更新 dsh-device-shell-guide 适配 0.1.7rc ..."

if [ ! -d "$ROOTFS/root" ]; then
    echo "⚠️ 错误: 未检测到有效的 rootfs 目录 ($ROOTFS)，跳过补丁应用。"
    exit 1
fi

TARGET_DIR="$ROOTFS/root/dsha-device-shell-guide"
mkdir -p "$TARGET_DIR" 2>/dev/null || true

cat << 'EOFPKG' > "$TARGET_DIR/package.json"
{
  "name": "dsh-device-shell-guide",
  "description": "DSHA builtin plugin: inject device-shell (ADB/Shizuku) steering into each new conversation's system prompt",
  "version": "0.1.17",
  "type": "module",
  "main": "lib/index.js",
  "exports": {
    ".": "./lib/index.js"
  },
  "cordis": {
    "entry": "lib/index.js"
  },
  "dsh": {
    "bundle": {
      "patch": "./cordis.patch.yml"
    }
  },
  "peerDependencies": {
    "@deepseek-ai/cordis": "^4.0.1",
    "@deepseek-ai/dsh-system-prompt": "^0.1.7-rc.1",
    "@deepseek-ai/dsh-llm": "^0.1.7-rc.1"
  },
  "license": "MIT"
}
EOFPKG
chmod 644 "$TARGET_DIR/package.json"

# 补齐/核验 Web Profile 与全局软链接
PROFILE_NM="$ROOTFS/root/.dsh/profiles/web/node_modules"
GLOBAL_NM="$ROOTFS/usr/local/lib/node_modules"
mkdir -p "$PROFILE_NM" "$GLOBAL_NM" 2>/dev/null || true

ln -sfn "/root/dsha-device-shell-guide" "$PROFILE_NM/dsh-device-shell-guide" 2>/dev/null || true
ln -sfn "/root/dsha-device-shell-guide" "$GLOBAL_NM/dsh-device-shell-guide" 2>/dev/null || true

touch "$ROOTFS/root/dsha-device-shell-guide-installed" 2>/dev/null || true

echo "✓ 增量补丁应用成功：dsh-device-shell-guide 已升级为 0.1.17 并适配 DSH 0.1.7rc！"
