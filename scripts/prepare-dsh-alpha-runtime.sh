#!/usr/bin/env bash
# Prepare the DSHA 1.2 dsh production closure on native Linux arm64.
#
# This is intentionally a build-host tool. Android never runs it and never
# installs dsh from the network. The build host itself is network-isolated:
# its pnpm store and npm cache must be supplied in advance. Its output is
# consumed by ci-make-offline-bundle.sh as DSH_RUNTIME_ARCHIVE.
set -euo pipefail

DSH_VERSION="0.1.2-alpha.2"
DSH_TAG="dsh-v0.1.2-alpha.2"
DSH_COMMIT="0a53fb55bea101816fa226bb964ae2bed71c343b"
UPSTREAM_DIR="${DSH_UPSTREAM_DIR:-}"
OUT="${OUT:-$PWD/dsh-alpha-runtime-${DSH_VERSION}-linux-arm64.tar.gz}"
PNPM_BIN="${PNPM_BIN:-pnpm}"
PNPM_STORE_DIR="${PNPM_STORE_DIR:-}"
NPM_CACHE_DIR="${NPM_CACHE_DIR:-}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

case "$(uname -s):$(uname -m)" in
  Linux:aarch64|Linux:arm64) ;;
  *) die "this runtime must be prepared on native Linux arm64" ;;
esac

# Do not let a missing cache silently turn this into a registry build. This
# also makes the clean-consumer verification a genuine no-network test.
if [ "${DSH_NETWORK_ISOLATED:-}" != "1" ]; then
  command -v unshare >/dev/null 2>&1 || die "unshare is required to enforce a no-network build"
  exec env DSH_NETWORK_ISOLATED=1 unshare --net --fork -- bash "$0" "$@"
fi

case "$(uname -m)" in
  aarch64|arm64) ;;
  *) die "this runtime must be prepared on native Linux arm64" ;;
esac
[ -n "$UPSTREAM_DIR" ] || die "set DSH_UPSTREAM_DIR to the pinned upstream checkout"
[ -d "$UPSTREAM_DIR/.git" ] || die "not an upstream git checkout: $UPSTREAM_DIR"
[ ! -e "$OUT" ] || die "refusing to overwrite existing output: $OUT"
command -v "$PNPM_BIN" >/dev/null 2>&1 || die "pnpm command not found: $PNPM_BIN"
command -v npm >/dev/null 2>&1 || die "npm is required for clean-consumer verification"
command -v tar >/dev/null 2>&1 || die "tar is required"
[ -n "$PNPM_STORE_DIR" ] && [ -d "$PNPM_STORE_DIR" ] || die "set PNPM_STORE_DIR to a preseeded offline pnpm store"
[ -n "$NPM_CACHE_DIR" ] && [ -d "$NPM_CACHE_DIR" ] || die "set NPM_CACHE_DIR to a preseeded offline npm cache"
[ "$("$PNPM_BIN" --version)" = "11.7.0" ] || die "pnpm 11.7.0 is required"
[ "$(node --version)" = "v24.19.0" ] || die "node v24.19.0 is required"

# Keep both package managers offline even if their defaults or user config
# would otherwise point at a registry. The network namespace above catches
# accidental direct fetches from lifecycle scripts as well.
export npm_config_offline=true
export npm_config_cache="$NPM_CACHE_DIR"
# npm's cache key includes the registry URL. Keep the canonical registry here so
# a preseeded cache can be used; --offline plus the isolated network namespace
# above still turns any cache miss into a hard failure without network access.
export npm_config_registry="https://registry.npmjs.org"
export npm_config_audit=false
export npm_config_fund=false

actual_commit="$(git -C "$UPSTREAM_DIR" rev-parse HEAD)"
[ "$actual_commit" = "$DSH_COMMIT" ] || die "upstream commit is $actual_commit, expected $DSH_COMMIT"
git -C "$UPSTREAM_DIR" describe --tags --exact-match HEAD | grep -Fx "$DSH_TAG" >/dev/null \
  || die "upstream HEAD is not tag $DSH_TAG"

work="$(mktemp -d "${TMPDIR:-/tmp}/dsha-dsh-runtime.XXXXXX")"
cleanup() {
  if [ "${KEEP_WORK:-}" = "1" ]; then
    echo "keeping build work directory: $work" >&2
  else
    rm -rf "$work"
  fi
}
trap cleanup EXIT
mkdir -p "$(dirname "$OUT")"

echo "==> [1/6] Install pinned upstream build dependencies from the offline store"
(
  cd "$UPSTREAM_DIR"
  # The release pack scripts are build tooling, so a clean checkout needs the
  # full lockfile install before build:official can invoke tsx/TypeScript.
  "$PNPM_BIN" install --frozen-lockfile --offline --store-dir "$PNPM_STORE_DIR"
)

echo "==> [2/6] Build official upstream artifacts"
(
  cd "$UPSTREAM_DIR"
  "$PNPM_BIN" run build:official
)

echo "==> [3/6] Pack dsh and vendor release families"
(
  cd "$UPSTREAM_DIR"
  # pnpm v11 forwards the separator as a positional argument for this script;
  # invoke the pinned pack entry directly so Node's strict argument parser sees
  # only the declared options.
  "$PNPM_BIN" exec tsx scripts/release/pack.ts --family dsh --out "$work/dsh-pack"
  "$PNPM_BIN" exec tsx scripts/release/pack.ts --family vendor --out "$work/vendor-pack"
)

# The dsh release family also publishes test-support packages.  They are not
# part of the production CLI closure, but their test-only dependencies pull in
# a large React/testing graph that is intentionally absent from the offline
# cache.  Keep the complete pack output for auditing, then feed only runtime
# packages to the clean-consumer verifier and closure builder.
mkdir -p "$work/prod-dsh-pack" "$work/prod-vendor-pack"
node - "$work/dsh-pack" "$work/vendor-pack" "$work/prod-dsh-pack" "$work/prod-vendor-pack" <<'NODE'
const { execFileSync } = require('node:child_process')
const { copyFileSync, mkdirSync, readdirSync } = require('node:fs')
const { join } = require('node:path')
const [dsh, vendor, outDsh, outVendor] = process.argv.slice(2)
const isTestOnly = name => /(?:test|mock|loader-smoke|session-snapshot)/i.test(name)
for (const [source, target] of [[dsh, outDsh], [vendor, outVendor]]) {
  mkdirSync(target, { recursive: true })
  for (const file of readdirSync(source).filter(name => name.endsWith('.tgz'))) {
    const manifest = JSON.parse(execFileSync('tar', ['-xOf', join(source, file), 'package/package.json'], { encoding: 'utf8' }))
    if (!isTestOnly(manifest.name)) copyFileSync(join(source, file), join(target, file))
  }
}
NODE

echo "==> [4/6] Verify upstream packed install in a clean offline consumer"
(
  cd "$UPSTREAM_DIR"
  "$PNPM_BIN" exec tsx scripts/release/verify-packed-install.ts --family dsh \
    --from "$work/prod-dsh-pack" --from "$work/prod-vendor-pack"
)

echo "==> [5/6] Materialize the production dependency closure"
mkdir -p "$work/consumer" "$work/stage/usr/local/lib/node_modules" "$work/stage/usr/local/bin"
node - "$work/consumer/package.json" "$work/prod-dsh-pack" "$work/prod-vendor-pack" <<'NODE'
const { execFileSync } = require('node:child_process')
const { readdirSync, writeFileSync } = require('node:fs')
const { join, resolve } = require('node:path')

const [manifestPath, ...packDirs] = process.argv.slice(2)
const dependencies = {}
for (const packDir of packDirs) {
  for (const file of readdirSync(packDir).filter(name => name.endsWith('.tgz')).sort()) {
    const full = resolve(packDir, file)
    const pkg = JSON.parse(execFileSync('tar', ['-xOf', full, 'package/package.json'], { encoding: 'utf8' }))
    if (typeof pkg.name !== 'string' || typeof pkg.version !== 'string') {
      throw new Error(`invalid packed manifest: ${full}`)
    }
    dependencies[pkg.name] = `file:${full}`
  }
}
if (dependencies['@deepseek-ai/dsh'] === undefined) throw new Error('dsh tarball is missing')
writeFileSync(manifestPath, `${JSON.stringify({
  name: 'dsha-dsh-alpha-production-closure',
  version: '0.0.0',
  private: true,
  dependencies,
}, null, 2)}\n`)
NODE
(
  cd "$work/consumer"
  # All DeepSeek packages resolve from local file: tarballs. External runtime
  # dependencies must already be in NPM_CACHE_DIR; --offline and the isolated
  # network namespace make an attempted registry fallback fail immediately.
  npm install --offline --no-audit --no-fund --omit=dev
  # Reinstall from the just-written lockfile in a clean consumer. This proves
  # the shipped closure can be assembled without the upstream checkout.
  rm -rf node_modules
  npm ci --offline --no-audit --no-fund --omit=dev
)
test -f "$work/consumer/node_modules/@deepseek-ai/dsh/lib/bin.js" \
  || die "clean consumer did not install dsh"
got_version="$(node -p "require('$work/consumer/node_modules/@deepseek-ai/dsh/package.json').version")"
[ "$got_version" = "$DSH_VERSION" ] || die "consumer installed $got_version, expected $DSH_VERSION"
test ! -d "$work/consumer/node_modules/.pnpm" || die "pnpm development store leaked into consumer"
test ! -e "$work/consumer/node_modules/@deepseek-ai/dsh/.git" || die "git metadata leaked into consumer"
test ! -d "$work/consumer/node_modules/@deepseek-ai/dsh/packages" || die "source tree leaked into consumer"
test ! -d "$work/consumer/node_modules/@deepseek-ai/dsh/apps" || die "source tree leaked into consumer"
cp -a "$work/consumer/node_modules/." "$work/stage/usr/local/lib/node_modules/"
ln -s ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js "$work/stage/usr/local/bin/dsh"
cp "$work/consumer/package-lock.json" "$work/stage/consumer-package-lock.json"

node - "$work/stage/runtime-manifest.json" "$work/prod-dsh-pack" "$work/prod-vendor-pack" <<'NODE'
const { createHash } = require('node:crypto')
const { readFileSync, readdirSync, writeFileSync } = require('node:fs')
const { dirname, join } = require('node:path')

const [out, ...dirs] = process.argv.slice(2)
const packed = {}
for (const dir of dirs) {
  for (const file of readdirSync(dir).filter(name => name.endsWith('.tgz')).sort()) {
    packed[file] = createHash('sha256').update(readFileSync(join(dir, file))).digest('hex')
  }
}
writeFileSync(out, `${JSON.stringify({
  formatVersion: 1,
  runtimeId: 'dsh-v0.1.2-alpha.2',
  dshVersion: '0.1.2-alpha.2',
  upstreamTag: 'dsh-v0.1.2-alpha.2',
  upstreamCommit: '0a53fb55bea101816fa226bb964ae2bed71c343b',
  packedArtifacts: packed,
  consumerLockSha256: createHash('sha256').update(
    readFileSync(join(dirname(out), 'consumer-package-lock.json'))
  ).digest('hex'),
}, null, 2)}\n`)
NODE

echo "==> [6/6] Verify staged runtime without source checkout"
env -i \
  HOME="$work/clean-home" \
  PATH="${PATH}" \
  DSH_HOME="$work/clean-home/.dsh" \
  node "$work/stage/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" --version \
  | grep -Fx "$DSH_VERSION" >/dev/null || die "staged dsh cannot report the pinned version"
tar -C "$work/stage" -czf "$OUT" .
# Dependency packages may legitimately contain documentation metadata such as
# `.github` or `.gitmodules`; reject only actual VCS directories, pnpm stores,
# or the upstream checkout layout in the shipped dsh package.
bad_entries="$(tar -tzf "$OUT" | grep -E '(^|/)(\.git/|\.pnpm/|packages/|apps/)' || true)"
if [ -n "$bad_entries" ]; then
  printf '%s\n' "$bad_entries" >&2
  die "runtime archive contains source/development content"
fi
sha256sum "$OUT" | tee "$OUT.sha256"
echo "runtime archive ready: $OUT"
