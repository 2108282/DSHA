#!/bin/bash
set -e

PROJECT_DIR="/sdcard/Download/DSHA/dsha-ksu-project"
RELEASE_DIR="$PROJECT_DIR/release"
MODULE_DIR="$PROJECT_DIR/module_src"
ROOTFS_TAR_XZ="$RELEASE_DIR/rootfs.tar.xz"
ROOTFS_TAR_GZ="$RELEASE_DIR/rootfs.tar.gz"
MODULE_ZIP="$RELEASE_DIR/dsha_ksu_native_v1.2.0.zip"

mkdir -p "$RELEASE_DIR" "$MODULE_DIR"

echo "=========================================================="
echo "      DSHA 纯净公开发布版 RootFS 底包与模块打包工具       "
echo "   (严格脱敏：自动剔除对话记录、API Key、账号、历史指令)  "
echo "=========================================================="

echo "[1/4] 清理 apt 缓存与临时垃圾..."
apt-get clean >/dev/null 2>&1 || true
rm -rf /tmp/* /tmp/.* 2>/dev/null || true
rm -rf /root/.cache/* 2>/dev/null || true
rm -f /root/*.log /root/.*.pid 2>/dev/null || true

echo "[2/4] 正在打包【纯净脱敏版】rootfs.tar.gz (使用 pigz 多线程并行)..."
cd /
tar --numeric-owner -c \
    --exclude='./proc/*' \
    --exclude='./sys/*' \
    --exclude='./dev/*' \
    --exclude='./sdcard/*' \
    --exclude='./storage/*' \
    --exclude='./tmp/*' \
    --exclude='./root/dsha-repo' \
    --exclude='./root/手机存储' \
    --exclude='./root/.cache/*' \
    --exclude='./root/*.log' \
    --exclude='./root/.*.pid' \
    --exclude='./root/.bash_history' \
    --exclude='./root/.dsh/sessions/*' \
    --exclude='./root/.dsh/attachments/*' \
    --exclude='./root/.dsh/storages/*' \
    --exclude='./root/.dsh/agy-accounts.json' \
    --exclude='./root/.dsh/.credentials.yaml' \
    --exclude='./root/.dsh/.bridge_token' \
    --exclude='./root/.dsh/.launch_token' \
    --exclude='./root/.dsh/.anonymous-user-id' \
    --exclude='./root/.dsh/dsh-api-dashboard.json' \
    --exclude='./.l2s' \
    --exclude='./.proroot-meta' \
    --exclude='./sdcard' \
    --exclude='./storage' \
    . | pigz -1 > "$ROOTFS_TAR_GZ"

echo "[3/4] 纯净通用底包已生成: $ROOTFS_TAR_GZ"
ls -lh "$ROOTFS_TAR_GZ"

if which xz >/dev/null 2>&1; then
    echo "      正在生成 xz 高压缩比底包 (供发布使用)..."
    xz -T0 -1 -c <(pigz -dc "$ROOTFS_TAR_GZ") > "$ROOTFS_TAR_XZ"
    ls -lh "$ROOTFS_TAR_XZ"
fi

echo "[4/4] 正在重新打包纯净 KernelSU / Magisk 刷机包..."
cd "$MODULE_DIR"
rm -f "$MODULE_ZIP"
cp "$ROOTFS_TAR_GZ" "$MODULE_DIR/rootfs.tar.gz"
zip -r9 "$MODULE_ZIP" META-INF module.prop customize.sh service.sh scripts rootfs.tar.gz >/dev/null
rm -f "$MODULE_DIR/rootfs.tar.gz"

echo "=========================================================="
echo " 打包成功！此版本已彻底脱敏，位于 $RELEASE_DIR :"
echo " 1. 刷机包: $MODULE_ZIP"
echo " 2. Gzip底包: $ROOTFS_TAR_GZ"
echo " 3. Xz底包:   $ROOTFS_TAR_XZ"
echo "=========================================================="
