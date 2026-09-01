#!/usr/bin/env bash
# Historical Termux entry point.
#
# DSHA 1.2 alpha deliberately does not build or install dsh on a phone.  The
# production rootfs must be assembled on native Linux arm64 from the pinned
# upstream release pack, then verified as a clean consumer before it is packed
# into an APK.  Keep this file so old instructions fail closed instead of
# silently falling back to a clone/npm install path.
set -euo pipefail

echo "DSHA 1.2 alpha disables the Termux rootfs builder."
echo "Use scripts/ci-make-offline-bundle.sh on native Linux arm64 with DSH_RUNTIME_ARCHIVE."
exit 2
