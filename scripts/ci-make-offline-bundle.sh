#!/usr/bin/env bash
# ============================================================
# ci-make-offline-bundle.sh — 在 Linux CI（原生 arm64 或
# x86_64 + qemu-user-static）里预装 Ubuntu rootfs，产出
# APK 内置的 offline-rootfs.tar.gz。不依赖 Termux / Docker。
#
# 用法（GitHub Actions ARM runner 上）：
#   bash scripts/ci-make-offline-bundle.sh
# 产物：
#   ${OUT:-$PWD/bundle/offline-rootfs.tar.gz}
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROOTFS_DIR="${ROOTFS_DIR:-$PWD/ci-rootfs}"
OUT="${OUT:-$PWD/bundle/offline-rootfs.tar.gz}"
UBUNTU_VER="${UBUNTU_VER:-24.04.4}"

log() { echo "==> $*"; }
die() { echo "❌ $*" >&2; exit 1; }

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"; }

ARCH="$(uname -m)"
NATIVE_ARM=0
case "$ARCH" in
  aarch64|arm64) NATIVE_ARM=1 ;;
esac

need_cmd curl
need_cmd tar
need_cmd gzip
need_cmd sudo

if [ "$NATIVE_ARM" != 1 ]; then
  log "当前架构 $ARCH，安装 qemu-user-static 以便 chroot arm64"
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    qemu-user-static binfmt-support
  # 确保 aarch64 binfmt 已注册
  if [ -x /usr/sbin/update-binfmts ]; then
    sudo update-binfmts --enable qemu-aarch64 >/dev/null 2>&1 || true
  fi
  [ -x /usr/bin/qemu-aarch64-static ] || die "qemu-aarch64-static 未安装"
fi

cleanup() {
  set +e
  if [ -d "$ROOTFS_DIR" ]; then
    sudo umount -l "$ROOTFS_DIR/dev/pts" 2>/dev/null
    sudo umount -l "$ROOTFS_DIR/dev" 2>/dev/null
    sudo umount -l "$ROOTFS_DIR/proc" 2>/dev/null
    sudo umount -l "$ROOTFS_DIR/sys" 2>/dev/null
  fi
}
trap cleanup EXIT

log "[1/6] 下载 Ubuntu base ${UBUNTU_VER} arm64"
mkdir -p "$ROOTFS_DIR" "$(dirname "$OUT")"
TARBALL="$PWD/ubuntu-base-${UBUNTU_VER}-arm64.tar.gz"
URLS=(
  "https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_VER}/release/ubuntu-base-${UBUNTU_VER}-base-arm64.tar.gz"
  "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/${UBUNTU_VER}/release/ubuntu-base-${UBUNTU_VER}-base-arm64.tar.gz"
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/${UBUNTU_VER}/release/ubuntu-base-${UBUNTU_VER}-base-arm64.tar.gz"
)
if [ ! -s "$TARBALL" ]; then
  ok=0
  for url in "${URLS[@]}"; do
    log "  尝试: $url"
    if curl -fL --retry 3 --retry-delay 2 -m 300 "$url" -o "$TARBALL"; then
      ok=1
      break
    fi
    rm -f "$TARBALL"
  done
  [ "$ok" = 1 ] || die "下载 ubuntu-base 失败"
fi
ls -lh "$TARBALL"

log "[2/6] 解压 rootfs → $ROOTFS_DIR"
# 已有残留则清空（避免二次跑半成品）
if [ -e "$ROOTFS_DIR/usr/bin/bash" ] || [ -e "$ROOTFS_DIR/bin/bash" ]; then
  log "  已有 rootfs，跳过解压"
else
  sudo mkdir -p "$ROOTFS_DIR"
  sudo tar -xzf "$TARBALL" -C "$ROOTFS_DIR"
fi
[ -x "$ROOTFS_DIR/usr/bin/bash" ] || [ -x "$ROOTFS_DIR/bin/bash" ] || die "rootfs 不完整（缺少 bash）"
# merged-/usr：保证 /bin 存在
if [ ! -e "$ROOTFS_DIR/bin" ]; then
  sudo ln -sf usr/bin "$ROOTFS_DIR/bin"
fi

log "[3/6] 挂载 /proc /sys /dev + DNS"
sudo mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts,tmp,root}
sudo mount -t proc proc "$ROOTFS_DIR/proc"
sudo mount -t sysfs sysfs "$ROOTFS_DIR/sys"
sudo mount --bind /dev "$ROOTFS_DIR/dev"
sudo mount --bind /dev/pts "$ROOTFS_DIR/dev/pts"
# 用宿主 DNS（GitHub runner 可达）
if [ -f /etc/resolv.conf ]; then
  sudo cp -L /etc/resolv.conf "$ROOTFS_DIR/etc/resolv.conf"
else
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' | sudo tee "$ROOTFS_DIR/etc/resolv.conf" >/dev/null
fi
if [ "$NATIVE_ARM" != 1 ]; then
  sudo mkdir -p "$ROOTFS_DIR/usr/bin"
  sudo cp -f /usr/bin/qemu-aarch64-static "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"
  sudo chmod +x "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"
fi

log "[4/6] 注入预装脚本与补丁"
sudo mkdir -p "$ROOTFS_DIR/root/patches"
for f in webui-sidebar.patch bash-guard.patch webui-polyfill.sh webui-origin-port-patch.sh rootfs-confirm-install.sh; do
  if [ -f "$REPO_ROOT/app/src/main/assets/$f" ]; then
    sudo cp "$REPO_ROOT/app/src/main/assets/$f" "$ROOTFS_DIR/root/patches/$f"
  fi
done
# 内置插件源码 + 预置脚本（offline-provision.sh 里调用，实现「解压即用」）
if [ -d "$REPO_ROOT/app/src/main/assets/mobile-nav" ]; then
  sudo mkdir -p "$ROOTFS_DIR/root/patches/builtin"
  sudo cp -r "$REPO_ROOT/app/src/main/assets/mobile-nav" "$ROOTFS_DIR/root/patches/builtin/"
  sudo cp -r "$REPO_ROOT/app/src/main/assets/device-shell-guide" "$ROOTFS_DIR/root/patches/builtin/"
  sudo cp -r "$REPO_ROOT/app/src/main/assets/task-notifier" "$ROOTFS_DIR/root/patches/builtin/"
  sudo cp -r "$REPO_ROOT/app/src/main/assets/status-overlay" "$ROOTFS_DIR/root/patches/builtin/"
  sudo cp "$REPO_ROOT/scripts/provision-builtin-plugins.sh" "$ROOTFS_DIR/root/patches/"
fi
sudo cp "$REPO_ROOT/scripts/offline-provision.sh" "$ROOTFS_DIR/root/offline-provision.sh"
sudo chmod +x "$ROOTFS_DIR/root/offline-provision.sh"

log "[5/6] chroot 预装（apt / Node / pnpm / dsh（默认 rc.8，见 offline-provision.sh）/ 守卫）"
# CI 里走官方源；保留 ca-certificates（真 chroot 下 postinst 可用）
sudo chroot "$ROOTFS_DIR" /usr/bin/env \
  DEBIAN_FRONTEND=noninteractive \
  GITHUB_ACTIONS="${GITHUB_ACTIONS:-true}" \
  DSHA_KEEP_CA=1 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /usr/bin/bash /root/offline-provision.sh

log "[6/6] 卸载挂载 + 打包"
cleanup
trap - EXIT
# 清掉仅构建期文件
sudo rm -f "$ROOTFS_DIR/root/offline-provision.sh"
sudo rm -f "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"
sudo rm -rf "$ROOTFS_DIR/var/lib/apt/lists" \
            "$ROOTFS_DIR/var/cache/apt/archives" \
            "$ROOTFS_DIR/root/.cache" \
            "$ROOTFS_DIR/root/.npm" \
            "$ROOTFS_DIR/tmp/"* \
            "$ROOTFS_DIR/root/patches" 2>/dev/null || true
# 保证空的虚拟目录存在（proot 需要）
sudo mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts,tmp}

# 不用 --absolute-names，条目为相对路径，App 的 TarGzipExtractor 才能解
sudo tar \
  --exclude='./proc/*' \
  --exclude='./sys/*' \
  --exclude='./dev/*' \
  --exclude='./tmp/*' \
  -C "$ROOTFS_DIR" \
  -czf "$OUT" \
  .
sudo chown "$(id -u):$(id -g)" "$OUT"
# 还原空目录进包（上面 exclude 会丢掉内容，目录本身仍在）
ls -lh "$OUT"
python3 -c "
p='$OUT'
b=open(p,'rb').read(2)
assert b==b'\\x1f\\x8b', '不是 gzip: %r' % b
print('gzip 魔数 OK, size=', __import__('os').path.getsize(p))
"
# 再确认包里有 bash 和 dsh
python3 - <<PY
import tarfile, sys
need = ("usr/bin/bash", "bin/bash")
with tarfile.open("$OUT", "r:gz") as t:
    names = set(t.getnames())
# tar 可能带 ./ 前缀
flat = {n[2:] if n.startswith("./") else n for n in names}
ok_bash = "usr/bin/bash" in flat or "bin/bash" in flat
ok_node = "usr/local/bin/node" in flat
ok_dsh  = "usr/local/bin/dsh" in flat
print("entries:", len(flat), "bash=", ok_bash, "node=", ok_node, "dsh=", ok_dsh)
if not ok_bash or not ok_node:
    sys.exit("离线包缺关键文件")
if not ok_dsh:
    print("WARN: 包内未见 /usr/local/bin/dsh（可能是符号链接被记成 link 目标）")
PY

log "✅ 离线包已生成: $OUT"
