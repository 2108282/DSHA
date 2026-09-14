#!/usr/bin/env bash
# Import the already-built dsh production closure into an arm64 rootfs.
#
# This script runs inside ci-make-offline-bundle.sh after the base rootfs,
# Node runtime, and the pinned release-pack closure have been supplied.  It is
# deliberately network-free: no apt/npm/pnpm/git path is allowed here, and no
# legacy DSHA patch or plugin is injected into the official Alpha profile.
set -euo pipefail

ARCHIVE="${DSH_RUNTIME_ARCHIVE:-/root/dsh-runtime.tar.gz}"
EXPECTED_RUNTIME_ID="dsh-v0.1.2-alpha.2"
EXPECTED_DSH_VERSION="0.1.2-alpha.2"
EXPECTED_TAG="dsh-v0.1.2-alpha.2"
EXPECTED_COMMIT="0a53fb55bea101816fa226bb964ae2bed71c343b"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -s "$ARCHIVE" ] || die "missing dsh production closure: $ARCHIVE"
command -v tar >/dev/null 2>&1 || die "tar is required"

# Inspect the archive before extraction.  A production closure must never
# carry an upstream checkout, package-manager store, or other development tree.
if tar -tzf "$ARCHIVE" | grep -E '(^|/)(\.git(/|$)|packages/|apps/|\.pnpm/|pnpm-lock\.yaml|node_modules/\.cache/)' >/dev/null; then
  die "dsh production closure contains source/development content"
fi

echo "==> import pinned dsh production closure"
tar -xzf "$ARCHIVE" -C / --no-same-owner --no-same-permissions

NODE=/usr/local/bin/node
DSH=/usr/local/bin/dsh
META=/usr/local/share/dsha-dsh-runtime.json
[ -s "$META" ] || if [ -s /runtime-manifest.json ]; then
  mkdir -p /usr/local/share
  cp /runtime-manifest.json "$META"
fi
[ -x "$NODE" ] || die "closure did not install /usr/local/bin/node"
[ -x "$DSH" ] || die "closure did not install /usr/local/bin/dsh"
[ -s "$META" ] || die "closure did not install runtime metadata"

"$NODE" - "$META" <<'NODE'
const fs = require('node:fs')
const path = process.argv[2]
const meta = JSON.parse(fs.readFileSync(path, 'utf8'))
const expected = {
  runtimeId: 'dsh-v0.1.2-alpha.2',
  dshVersion: '0.1.2-alpha.2',
  upstreamTag: 'dsh-v0.1.2-alpha.2',
  upstreamCommit: '0a53fb55bea101816fa226bb964ae2bed71c343b',
}
const bad = Object.keys(expected).filter(key => meta[key] !== expected[key])
if (bad.length) throw new Error(`runtime metadata mismatch: ${bad.join(', ')}`)
NODE

# Keep the runtime writable at first boot, but leave its profile/plugin state
# untouched.  The official dsh process owns migrations for its session format.
mkdir -p /root/.dsh /tmp
chmod 700 /root/.dsh || true

"$NODE" "$DSH" --version | grep -Fx "$EXPECTED_DSH_VERSION" >/dev/null \
  || die "installed dsh does not report the pinned version"
echo "dsh runtime ready: $EXPECTED_RUNTIME_ID ($EXPECTED_TAG @ $EXPECTED_COMMIT)"
