#!/usr/bin/env bash
set -euo pipefail

ROOTFS_TAR="${1:-magisk-module/rootfs.tar.gz}"
TARGET_TAG="${2:-rootfs}"
BRANCH_NAME="${3:-rootfs}"
REPO="${GITHUB_REPOSITORY:-2108282/DSHA}"
TOKEN="${GH_TOKEN:-}"

if [ ! -f "$ROOTFS_TAR" ]; then
    echo "错误: 未找到底包文件 $ROOTFS_TAR" >&2
    exit 1
fi

echo "=========================================================="
echo "         DSHA 底包资产云端自动发布与持久化引擎              "
echo "=========================================================="

echo "==> [1/3] 上传纯净底包到 Release $TARGET_TAG..."
if ! gh release view "$TARGET_TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "创建 Release $TARGET_TAG..."
    gh release create "$TARGET_TAG" --repo "$REPO" --title "DSHA 1.2.0（内置 dsh 0.1.5-rc.2 基础底包预发布）" --notes "纯净重构底包" --prerelease
fi
gh release upload "$TARGET_TAG" "$ROOTFS_TAR" --repo "$REPO" --clobber
DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TARGET_TAG}/rootfs.tar.gz"
echo "✓ Release 上传成功: $DOWNLOAD_URL"

echo "==> [2/3] 计算校验和并切分分卷 (适配 GitHub 100MB 单文件限制)..."
SHA256=$(sha256sum "$ROOTFS_TAR" | awk '{print $1}')
SIZE=$(ls -lh "$ROOTFS_TAR" | awk '{print $5}')

STAGE_DIR="/tmp/split_stage_$$"
mkdir -p "$STAGE_DIR"
split -b 50M -d "$ROOTFS_TAR" "$STAGE_DIR/rootfs.tar.gz.part-"

cat > "$STAGE_DIR/merge.sh" << 'EOF_MERGE'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "==> 正在合并底包分卷..."
cat "$DIR"/rootfs.tar.gz.part-* > "$DIR/rootfs.tar.gz"
echo "✓ 合并完成: $DIR/rootfs.tar.gz"
sha256sum "$DIR/rootfs.tar.gz"
EOF_MERGE
chmod +x "$STAGE_DIR/merge.sh"

cat > "$STAGE_DIR/README.md" << EOF_DOC
# DSHA 0.1.5-rc.2 纯净原生 Linux 底包资产

本目录为 GitHub Actions 自动化流水线熔铸生成的 **100% 纯净全新底包**。
已彻底物理清除旧 PRoot 时代残留，并集成消除空白行与通知防误弹等最新修复。

---

## 方式一：完整单文件直链下载（推荐）
- **直链下载**: [rootfs.tar.gz](${DOWNLOAD_URL})
- **文件大小**: ${SIZE}
- **SHA-256 校验和**: \`${SHA256}\`

---

## 方式二：本分支直接本地合并还原
因 GitHub 限制单个文件不能超过 100MB，本目录下提供 50MB 分卷文件（\`rootfs.tar.gz.part-*\`）。
克隆本分支后，直接在当前目录执行：
\`\`\`bash
./merge.sh
\`\`\`
即可还原为完整的 \`rootfs.tar.gz\`。
EOF_DOC

echo "==> [3/3] 同步至分支 $BRANCH_NAME..."
CLONE_DIR="/tmp/branch_clone_$$"
git clone --depth=1 --branch "$BRANCH_NAME" "https://x-access-token:${TOKEN}@github.com/${REPO}.git" "$CLONE_DIR"

mkdir -p "$CLONE_DIR/rootfs"
cp -rf "$STAGE_DIR"/* "$CLONE_DIR/rootfs/"

cd "$CLONE_DIR"
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${TOKEN}@github.com/${REPO}.git"

if [ -f README.md ]; then
  if ! grep -q "最新纯净底包" README.md; then
    printf '\n> **📦 [最新纯净底包]**: 单文件直链：[rootfs.tar.gz](%s)（%s，SHA-256: `%s`），或查看 [`rootfs/ 目录`](./rootfs/) 内的分卷实体与合并脚本。\n\n' "$DOWNLOAD_URL" "$SIZE" "$SHA256" | cat - README.md > README.md.tmp && mv README.md.tmp README.md
  fi
fi

git add rootfs README.md
if git diff --staged --quiet; then
  echo "底包资产无变动，跳过推送。"
else
  git commit -m "feat(rootfs): 持久化全新纯净 0.1.5-rc.2 底包资产 (永久直链 + 50M分卷实体)"
  git push origin "$BRANCH_NAME"
  echo "✓ 成功推送到 $BRANCH_NAME 分支！"
fi

rm -rf "$STAGE_DIR" "$CLONE_DIR"
echo "=========================================================="
echo "✓ 底包持久化发布全部完成！"
echo "=========================================================="
