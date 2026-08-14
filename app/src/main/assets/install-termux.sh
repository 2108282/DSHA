#!/data/data/com.termux/files/usr/bin/bash
# deepseek-harness 一键安装脚本（在 Termux 内执行）
# 由 DSH启动器 生成：占位符已替换为用户配置。
set -e

API_KEY="@@API_KEY@@"
PERMISSION_MODE="@@PERMISSION_MODE@@"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
HOME_DIR="/data/data/com.termux/files/home"

if [ -z "$API_KEY" ]; then
  echo "!! 未配置 API key，请先在 App「配置」模块填入。"
  exit 1
fi

echo "==> [1/5] 更新包管理器"
pkg update -y

echo "==> [2/5] 安装依赖 + Node.js LTS"
pkg install -y curl git python make clang binutils nodejs-lts

NODE_V=$(node -v)
echo "    Node 版本: $NODE_V"
MAJOR=$(echo "$NODE_V" | sed 's/v\([0-9]*\).*/\1/')
MINOR=$(echo "$NODE_V" | sed 's/v[0-9]*\.\([0-9]*\).*/\1/')
if [ "$MAJOR" -lt 22 ] || { [ "$MAJOR" -eq 22 ] && [ "$MINOR" -lt 19 ]; }; then
  echo "!! Node 版本过低 (需 >= 22.19)，当前 $NODE_V，请升级 Termux 的 nodejs-lts。"
  exit 1
fi

echo "==> [3/5] 安装 pnpm"
npm install -g pnpm

echo "==> [4/5] 拉取并构建 deepseek-harness"
cd "$HOME_DIR"
if [ ! -d deepseek-harness ]; then
  git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git
fi
cd deepseek-harness
pnpm install
pnpm run build

echo "==> [5/5] 写入 API key"
printf 'DEEPSEEK_API_KEY=%s\n' "$API_KEY" > .env

echo ""
echo "==> 安装完成！"
echo "    工作区: $HOME_DIR/deepseek-harness"
echo "    启动:   node apps/cli/lib/bin.js web"
