#!/usr/bin/env bash
# Deprecated for DSHA 1.2 alpha.
#
# The prior implementation replaced dsh by downloading an npm package into an
# existing rootfs.  That loses the fixed upstream release-pack provenance and
# can produce a partial dependency closure.  Keep this guard so old local
# automation cannot downgrade the Alpha runtime or introduce an online mobile
# install path.
set -euo pipefail

echo "DSHA 1.2 alpha refuses in-place dsh replacement."
echo "Build a fresh native Linux arm64 rootfs using scripts/prepare-dsh-alpha-runtime.sh"
echo "and scripts/ci-make-offline-bundle.sh."
exit 2
