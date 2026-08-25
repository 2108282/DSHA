#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# make-offline-bundle.sh — Termux 兜底方案（一般不需要）。
# 正常构建请走 GitHub Actions：scripts/ci-make-offline-bundle.sh
# 会在 ubuntu-24.04-arm 上原生 chroot 预装，无需手机、无需 Termux。
#
# 仅当 CI 不可用时，才在 Termux 里跑本脚本：
#   bash scripts/make-offline-bundle.sh
# 产物：~/offline-rootfs.tar.gz
# ============================================================
set -e
# 记录脚本所在仓库根路径(绝对)，避免后面 cd 后相对路径失效。
# $0 形如 scripts/make-offline-bundle.sh → 先取脚本目录 scripts，再上一级到仓库根
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOTFS_DIR="$HOME/dsha-offline-rootfs"
OUTPUT="$HOME/offline-rootfs.tar.gz"

echo "==> [1/7] 检查 proot"
command -v proot >/dev/null 2>&1 || { echo "请先安装 proot: pkg install proot"; exit 1; }

echo "==> [2/7] 下载 Ubuntu base arm64 rootfs"
mkdir -p "$ROOTFS_DIR"
cd "$ROOTFS_DIR"
URLS=(
  "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
)
TARBALL="$ROOTFS_DIR/ubuntu-base.tar.gz"
for url in "${URLS[@]}"; do
  echo "  尝试源: $url"
  curl -kfsSL --retry 3 -m 300 "$url" -o "$TARBALL" && break
done
[ -s "$TARBALL" ] || { echo "下载 rootfs 失败"; exit 1; }
echo "  下载完成 ($(du -h "$TARBALL" | cut -f1))"

echo "==> [3/7] 解压 rootfs"
# Termux 文件系统不支持硬链接，平台 tar/bsdtar 会在 perl/uncompress 等硬链接上
# 报 Permission denied。用 bsdtar 且容忍失败（清除残留后手动补）。
if command -v bsdtar >/dev/null 2>&1; then
  bsdtar -xzf "$TARBALL" -C "$ROOTFS_DIR" 2>/dev/null || true
else
  tar -xzf "$TARBALL" -C "$ROOTFS_DIR" 2>/dev/null || true
fi
rm -f "$TARBALL"
[ -f "$ROOTFS_DIR/usr/bin/bash" ] || { echo "rootfs 不完整(缺少 bash)"; exit 1; }
# 硬链接失败的文件用软链接回退(软链在 Termux 可用)
[ -e "$ROOTFS_DIR/usr/bin/perl" ] && \
  [ ! -e "$ROOTFS_DIR/usr/bin/perl5.38.2" ] && \
  ln -sf perl "$ROOTFS_DIR/usr/bin/perl5.38.2" 2>/dev/null || true
[ -e "$ROOTFS_DIR/usr/bin/gunzip" ] && \
  [ ! -e "$ROOTFS_DIR/usr/bin/uncompress" ] && \
  ln -sf gunzip "$ROOTFS_DIR/usr/bin/uncompress" 2>/dev/null || true

echo "==> [4/7] 配置 proot 环境 + 写入 resolv.conf"
mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts}
echo "nameserver 223.5.5.5" > "$ROOTFS_DIR/etc/resolv.conf"
echo "nameserver 8.8.8.8" >> "$ROOTFS_DIR/etc/resolv.conf"
# 修复 /bin 符号链接（Ubuntu 24.04 默认 /bin → usr/bin，Termux 解压可能丢失）
[ -L "$ROOTFS_DIR/bin" ] || [ -d "$ROOTFS_DIR/bin" ] || ln -sf usr/bin "$ROOTFS_DIR/bin" 2>/dev/null || true

PROOT_ARGS=(
  proot
  --rootfs="$ROOTFS_DIR"
  -0
  -b /dev:/dev
  -b /proc:/proc
  -b /sys:/sys
  -b /dev/pts:/dev/pts
  -w /root
  -e PATH=/usr/bin:/bin
)

echo "==> [5/7] 注入预装脚本与补丁"
"${PROOT_ARGS[@]}" /usr/bin/bash -c "mkdir -p /root/patches" 2>/dev/null
if [ -d "$REPO_ROOT/app/src/main/assets" ]; then
  for f in webui-sidebar.patch bash-guard.patch webui-polyfill.sh webui-origin-port-patch.sh rootfs-confirm-install.sh; do
    cp "$REPO_ROOT/app/src/main/assets/$f" "$ROOTFS_DIR/root/patches/$f" 2>/dev/null || true
  done
fi
# 内置插件源码 + 预置脚本（offline-provision.sh 里调用）
if [ -d "$REPO_ROOT/app/src/main/assets/mobile-nav" ]; then
  mkdir -p "$ROOTFS_DIR/root/patches/builtin"
  cp -r "$REPO_ROOT/app/src/main/assets/mobile-nav" "$ROOTFS_DIR/root/patches/builtin/" 2>/dev/null || true
  cp -r "$REPO_ROOT/app/src/main/assets/device-shell-guide" "$ROOTFS_DIR/root/patches/builtin/" 2>/dev/null || true
  cp -r "$REPO_ROOT/app/src/main/assets/task-notifier" "$ROOTFS_DIR/root/patches/builtin/" 2>/dev/null || true
  cp -r "$REPO_ROOT/app/src/main/assets/status-overlay" "$ROOTFS_DIR/root/patches/builtin/" 2>/dev/null || true
  cp "$REPO_ROOT/scripts/provision-builtin-plugins.sh" "$ROOTFS_DIR/root/patches/" 2>/dev/null || true
fi
cp "$REPO_ROOT/scripts/offline-provision.sh" "$ROOTFS_DIR/root/offline-provision.sh"
chmod +x "$ROOTFS_DIR/root/offline-provision.sh"

echo "==> [6/7] 在 proot 内执行预装(apt-get / node / pnpm / harness)"
echo "    预计耗时 15~30 分钟（取决于网络与设备性能）"
"${PROOT_ARGS[@]}" /usr/bin/bash -c "
  cd /root
  export DEBIAN_FRONTEND=noninteractive
  bash /root/offline-provision.sh
"

echo "==> [7/7] 清理 + 打包"
"${PROOT_ARGS[@]}" /usr/bin/bash -c "rm -f /root/offline-provision.sh; \
  rm -rf /root/patches /var/lib/apt/lists /root/.cache /root/.npm /tmp/* 2>/dev/null || true"
cd "$ROOTFS_DIR"
tar -czf "$OUTPUT" .
echo "✅ 离线包已生成: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
echo ""
echo "下一步: 将 $OUTPUT 上传到 GitHub Releases"
echo "  https://github.com/rthdfd/DSHA/releases/new"
echo "  Tag: v1.1.0-offline-bundle"
echo "  附件: offline-rootfs.tar.gz"
echo ""
echo "上传后 CI 会自动下载并打进 APK，无需再手动构建 rootfs。"
