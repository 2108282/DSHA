#!/usr/bin/env bash
# Build the Android offline rootfs on native Linux arm64 from verified local
# inputs. This script intentionally has no download, apt, npm, or git path.
# Supply a preprovisioned base rootfs, a Node 24.19.0 arm64 tarball, and the
# pinned dsh closure produced by prepare-dsh-alpha-runtime.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROOTFS_REQUESTED="${ROOTFS_DIR:-$PWD/ci-rootfs}"
OUT="${OUT:-$PWD/bundle/offline-rootfs.tar.gz}"
BASE_ROOTFS_ARCHIVE="${BASE_ROOTFS_ARCHIVE:-}"
BASE_ROOTFS_SHA256="${BASE_ROOTFS_SHA256:-}"
NODE_RUNTIME_ARCHIVE="${NODE_RUNTIME_ARCHIVE:-}"
NODE_RUNTIME_SHA256="${NODE_RUNTIME_SHA256:-}"
DSH_RUNTIME_ARCHIVE="${DSH_RUNTIME_ARCHIVE:-}"
DSH_RUNTIME_SHA256="${DSH_RUNTIME_SHA256:-}"

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

# Termux invokes this script inside a root chroot, where sudo is intentionally
# absent.  Workstation invocations remain sudo-backed.
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi

verify_local_input() {
  local name="$1" path="$2" expected="$3" actual
  [ -n "$path" ] && [ -s "$path" ] || die "set $name to a non-empty local file"
  case "$expected" in
    ''|*[!0-9a-fA-F]*) die "set ${name}_SHA256 to its 64-character SHA-256" ;;
  esac
  [ "${#expected}" -eq 64 ] || die "set ${name}_SHA256 to its 64-character SHA-256"
  actual="$(sha256sum "$path" | awk '{print $1}')"
  [ "$actual" = "${expected,,}" ] || die "$name SHA-256 mismatch"
}

case "$(uname -s):$(uname -m)" in
  Linux:aarch64|Linux:arm64) ;;
  *) die "this rootfs must be assembled on native Linux arm64" ;;
esac
for command in tar gzip mktemp sha256sum; do need_cmd "$command"; done
[ -z "$SUDO" ] || need_cmd sudo
verify_local_input BASE_ROOTFS_ARCHIVE "$BASE_ROOTFS_ARCHIVE" "$BASE_ROOTFS_SHA256"
verify_local_input NODE_RUNTIME_ARCHIVE "$NODE_RUNTIME_ARCHIVE" "$NODE_RUNTIME_SHA256"
verify_local_input DSH_RUNTIME_ARCHIVE "$DSH_RUNTIME_ARCHIVE" "$DSH_RUNTIME_SHA256"

# A reusable rootfs can retain a source checkout, development dependencies, or
# a profile. Build every output in a fresh sibling stage and retain failures.
[ -n "$ROOTFS_REQUESTED" ] && [ "$ROOTFS_REQUESTED" != "/" ] || die "ROOTFS_DIR must be a concrete directory"
ROOTFS_PARENT="$(dirname "$ROOTFS_REQUESTED")"
ROOTFS_NAME="$(basename "$ROOTFS_REQUESTED")"
[ "$ROOTFS_NAME" != "." ] && [ "$ROOTFS_NAME" != ".." ] || die "ROOTFS_DIR must be a concrete directory"
mkdir -p "$ROOTFS_PARENT" "$(dirname "$OUT")"
ROOTFS_PARENT="$(cd "$ROOTFS_PARENT" && pwd -P)"
ROOTFS_DIR="$(mktemp -d "$ROOTFS_PARENT/${ROOTFS_NAME}.build.XXXXXX")"
ROOTFS_STAGE_PREFIX="$ROOTFS_PARENT/${ROOTFS_NAME}.build."
log "fresh rootfs stage: $ROOTFS_DIR"

cleanup_mounts() {
  set +e
  if [ -d "$ROOTFS_DIR" ]; then
    $SUDO umount -l "$ROOTFS_DIR/dev/pts" 2>/dev/null
    $SUDO umount -l "$ROOTFS_DIR/dev" 2>/dev/null
    $SUDO umount -l "$ROOTFS_DIR/proc" 2>/dev/null
    $SUDO umount -l "$ROOTFS_DIR/sys" 2>/dev/null
  fi
}
trap cleanup_mounts EXIT

log "[1/6] extract verified local arm64 base rootfs"
$SUDO tar -xzf "$BASE_ROOTFS_ARCHIVE" -C "$ROOTFS_DIR"
[ -x "$ROOTFS_DIR/usr/bin/bash" ] || [ -x "$ROOTFS_DIR/bin/bash" ] || die "base rootfs lacks bash"
if [ ! -e "$ROOTFS_DIR/bin" ]; then $SUDO ln -sf usr/bin "$ROOTFS_DIR/bin"; fi

log "[2/6] inject verified Node v24.19.0 arm64"
$SUDO mkdir -p "$ROOTFS_DIR/usr/local"
$SUDO tar -xJf "$NODE_RUNTIME_ARCHIVE" -C "$ROOTFS_DIR/usr/local" --strip-components=1
[ -x "$ROOTFS_DIR/usr/local/bin/node" ] || die "Node archive lacks usr/local/bin/node"

log "[3/6] mount chroot prerequisites (no DNS or network provisioning)"
$SUDO mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts,tmp,root}
$SUDO mount -t proc proc "$ROOTFS_DIR/proc"
$SUDO mount -t sysfs sysfs "$ROOTFS_DIR/sys"
$SUDO mount --bind /dev "$ROOTFS_DIR/dev"
$SUDO mount --bind /dev/pts "$ROOTFS_DIR/dev/pts"

log "[4/6] inject fixed local dsh production closure"
$SUDO cp "$REPO_ROOT/scripts/offline-provision.sh" "$ROOTFS_DIR/root/offline-provision.sh"
$SUDO cp "$REPO_ROOT/scripts/provision-builtin-plugins.sh" "$ROOTFS_DIR/root/provision-builtin-plugins.sh"
$SUDO mkdir -p "$ROOTFS_DIR/root/patches/builtin"
$SUDO cp -a "$REPO_ROOT/app/src/main/assets/device-shell-guide" \
  "$REPO_ROOT/app/src/main/assets/task-notifier" \
  "$REPO_ROOT/app/src/main/assets/status-overlay" \
  "$REPO_ROOT/app/src/main/assets/web-mobile" \
  "$ROOTFS_DIR/root/patches/builtin/"
$SUDO chmod +x "$ROOTFS_DIR/root/offline-provision.sh"
$SUDO chmod +x "$ROOTFS_DIR/root/provision-builtin-plugins.sh"
$SUDO cp "$DSH_RUNTIME_ARCHIVE" "$ROOTFS_DIR/root/dsh-runtime.tar.gz"

log "[5/6] chroot import and verify (no legacy patch or network install)"
$SUDO chroot "$ROOTFS_DIR" /usr/bin/env \
  DSH_RUNTIME_ARCHIVE=/root/dsh-runtime.tar.gz \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /usr/bin/bash /root/offline-provision.sh
$SUDO chroot "$ROOTFS_DIR" /usr/bin/env \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /usr/bin/bash /root/provision-builtin-plugins.sh

log "[6/6] package verified rootfs"
cleanup_mounts
trap - EXIT
$SUDO rm -f "$ROOTFS_DIR/root/offline-provision.sh" "$ROOTFS_DIR/root/provision-builtin-plugins.sh" "$ROOTFS_DIR/root/dsh-runtime.tar.gz"
$SUDO rm -rf "$ROOTFS_DIR/root/patches"
$SUDO rm -rf "$ROOTFS_DIR/root/.cache" "$ROOTFS_DIR/root/.npm" \
            "$ROOTFS_DIR/tmp/"* "$ROOTFS_DIR/root/.pnpm-store" 2>/dev/null || true
$SUDO mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts,tmp}
$SUDO tar --exclude='./proc/*' --exclude='./sys/*' --exclude='./dev/*' --exclude='./tmp/*' \
  -C "$ROOTFS_DIR" -czf "$OUT" .
$SUDO chown "$(id -u):$(id -g)" "$OUT"

names="$(tar -tzf "$OUT")"
printf '%s\n' "$names" | grep -E '(^|/)usr/local/bin/(node|dsh)$' >/dev/null \
  || die "offline rootfs misses node or dsh"
printf '%s\n' "$names" | grep -E '(^|/)usr/local/share/dsha-dsh-runtime.json$' >/dev/null \
  || die "offline rootfs misses runtime metadata"
if printf '%s\n' "$names" | grep -E '^\./(work/|packages/|apps/|\.pnpm/|pnpm-lock\.yaml|\.git(/|$))|(^|/)root/deepseek-harness|(^|/)root/\.pnpm-store|/node_modules/\.cache/' >/dev/null; then
  die "offline rootfs contains source/development content"
fi
meta_tmp="$(mktemp)"
trap 'rm -f "$meta_tmp"' RETURN
tar -xOf "$OUT" ./usr/local/share/dsha-dsh-runtime.json >"$meta_tmp" 2>/dev/null \
  || tar -xOf "$OUT" usr/local/share/dsha-dsh-runtime.json >"$meta_tmp"
"$ROOTFS_DIR/usr/local/bin/node" - "$meta_tmp" <<'NODE'
const fs = require('node:fs')
const meta = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const expected = { runtimeId: 'dsh-v0.1.2-alpha.2', dshVersion: '0.1.2-alpha.2', upstreamTag: 'dsh-v0.1.2-alpha.2', upstreamCommit: '0a53fb55bea101816fa226bb964ae2bed71c343b' }
const bad = Object.keys(expected).filter(key => meta[key] !== expected[key])
if (bad.length) throw new Error(`offline rootfs runtime metadata mismatch: ${bad.join(', ')}`)
NODE
rm -f "$meta_tmp"
echo "offline rootfs verified: $OUT"

case "$ROOTFS_DIR" in
  "$ROOTFS_STAGE_PREFIX"*) $SUDO rm -rf -- "$ROOTFS_DIR" ;;
  *) die "refusing to clean a non-stage rootfs: $ROOTFS_DIR" ;;
esac
sha256sum "$OUT" | tee "$OUT.sha256"
log "offline rootfs ready: $OUT"
