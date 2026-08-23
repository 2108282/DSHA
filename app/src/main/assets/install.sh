#!/bin/bash
# deepseek-harness 一键安装脚本（在 Ubuntu rootfs 内通过 proot 执行）
# 由 DSH启动器 生成：占位符已替换为用户配置。
set -e

API_KEY="@@API_KEY@@"
PORT="@@PORT@@"
MODEL="@@MODEL@@"
PERMISSION_MODE="@@PERMISSION_MODE@@"
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
export DEBIAN_FRONTEND=noninteractive

if [ -z "$API_KEY" ]; then
  echo "!! 未配置 API key，请先在 App「配置」模块填入。"
  exit 1
fi

echo "==> [1/6] 更新 apt 源"
apt-get update -y >/dev/null

echo "==> [2/6] 安装基础依赖 (curl/git/python3/gcc/xz)"
if ! apt-get install -y curl git python3 make gcc g++ xz-utils ca-certificates; then
  echo "!! 基础依赖安装失败（可能是网络/源问题），重试："
  apt-get update -y
  apt-get install -y curl git python3 make gcc g++ xz-utils ca-certificates || {
    echo "!! 再次失败，请检查网络后到「安装」页重跑本步骤"
    exit 1
  }
fi

echo "==> [3/6] 下载并安装 Node.js (arm64)"
NODE_VERSION="24.19.0"
mkdir -p /tmp 2>/dev/null || true
curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz" -o /tmp/node.tar.xz
tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
node -v

echo "==> [4/6] 启用 pnpm"
(pnpm -v >/dev/null 2>&1 && echo "pnpm 已就绪，跳过安装") || npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com
pnpm -v

echo "==> [5/6] 拉取并构建 deepseek-harness"
cd /root
if [ ! -d deepseek-harness ]; then
  git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git
fi
cd deepseek-harness
pnpm install
pnpm run build

# 写入 API key（root .env）
printf 'DEEPSEEK_API_KEY=%s\n' "$API_KEY" > .env

echo ""
echo "==> 安装完成！"
echo "    工作区: /root/deepseek-harness"
echo "    端口:   $PORT"
echo "    启动:   node apps/cli/lib/bin.js web"
