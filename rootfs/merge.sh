#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "==> 正在合并底包分卷..."
cat "$DIR"/rootfs.tar.gz.part-* > "$DIR/rootfs.tar.gz"
echo "✓ 合并完成: $DIR/rootfs.tar.gz"
sha256sum "$DIR/rootfs.tar.gz"
