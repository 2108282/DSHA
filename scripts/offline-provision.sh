#!/usr/bin/env bash
# ============================================================
# offline-provision.sh — 在 arm64 rootfs(chroot/qemu)内预装
# deepseek-harness 完整运行环境，产出"解压即用"的 rootfs。
#
# 由 .github/workflows/android-build.yml 调用（qemu-user chroot）。
# 运行环境假设：rootfs 已挂载 /proc /sys /dev，网络可用，root 用户。
# ============================================================
set -e

WORKDIR="${WORKDIR:-deepseek-harness}"

echo "==> [1/8] 替换 apt 国内源"
sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' \
    /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true

echo "==> [2/8] apt 更新 + 安装基础工具"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
    curl git python3 make gcc g++ xz-utils ca-certificates
# 与 App 内逻辑保持一致：ca-certificates 若 broken 则移除
apt-get install -y --no-install-recommends ca-certificates 2>/dev/null || true
dpkg --remove --force-remove-reinstreq ca-certificates 2>/dev/null || true
dpkg --configure -a 2>/dev/null || true

echo "==> [3/8] 安装 Node.js v24.19.0"
if [ ! -x /usr/local/bin/node ]; then
  cd /tmp
  curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz \
    || curl -kfsSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz
  tar -xJf node.tar.xz -C /usr/local --strip-components=1
  rm -f node.tar.xz
fi
node -v && npm -v

echo "==> [4/8] 安装 pnpm / node-gyp"
export npm_config_registry=https://registry.npmmirror.com
command -v pnpm >/dev/null 2>&1 || npm install -g pnpm@11.7.0
command -v node-gyp >/dev/null 2>&1 || npm install -g node-gyp

echo "==> [5/8] 获取 deepseek-harness 源码"
cd /root
rm -rf "${WORKDIR}"
git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git "${WORKDIR}" \
  || git clone --depth 1 https://gitclone.com/github.com/deepseek-ai/deepseek-harness.git "${WORKDIR}" \
  || { echo 'git 克隆失败，改用源码包'; \
      curl -kfsSL --retry 3 -m 300 https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main -o dsh-src.tar.gz && \
      tar -xzf dsh-src.tar.gz && mv deepseek-harness-main "${WORKDIR}" && rm -f dsh-src.tar.gz; }

echo "==> [6/8] 应用补丁"
cd /root/"${WORKDIR}"
if [ -f /root/patches/webui-sidebar.patch ]; then
  git apply --check /root/patches/webui-sidebar.patch 2>/dev/null && git apply /root/patches/webui-sidebar.patch || echo 'sidebar 补丁跳过'
fi
if [ -f /root/patches/bash-guard.patch ]; then
  git apply --check /root/patches/bash-guard.patch 2>/dev/null && git apply /root/patches/bash-guard.patch || echo 'bash-guard 补丁跳过'
fi
# WebUI polyfill
if [ -f /root/patches/webui-polyfill.sh ]; then
  bash /root/patches/webui-polyfill.sh || true
fi
# 危险命令确认包装器
if [ -f /root/patches/rootfs-confirm-install.sh ]; then
  bash /root/patches/rootfs-confirm-install.sh || true
fi

echo "==> [7/8] pnpm install（含 node-pty 原生编译）"
# pnpm 10/11 默认忽略构建脚本，node-pty 必须白名单
grep -q 'onlyBuiltDependencies' pnpm-workspace.yaml 2>/dev/null || \
  printf '\nonlyBuiltDependencies:\n  - node-pty\n' >> pnpm-workspace.yaml
command -v python >/dev/null 2>&1 || ln -sf /usr/bin/python3 /usr/bin/python || true
# Node headers 预缓存（npmmirror，避免 node-gyp 现场连 nodejs.org）
if [ ! -f /root/.cache/node-gyp/24.19.0/include/node/node.h ]; then
  mkdir -p /root/.cache/node-gyp/24.19.0 && cd /root/.cache/node-gyp/24.19.0
  curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz
  tar -xzf headers.tar.gz --strip-components=1 && rm -f headers.tar.gz && touch .install-stamp
fi
printf 'registry=https://registry.npmmirror.com\n' > /root/.npmrc
cd /root/"${WORKDIR}"
pnpm install

echo "==> [8/8] 编译 node-pty + 安装 dsh 命令"
NP=$(ls -d node_modules/.pnpm/node-pty@*/node_modules/node-pty 2>/dev/null | head -1)
if [ -n "$NP" ] && [ ! -f "$NP/prebuilds/linux-arm64/pty.node" ]; then
  cd "$NP"
  GYP=/usr/local/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js
  [ -f "$GYP" ] || GYP=$(find /usr/local/lib -maxdepth 8 -path '*/node-gyp/bin/node-gyp.js' 2>/dev/null | head -1)
  export npm_config_disturl=https://npmmirror.com/mirrors/node
  node "$GYP" rebuild
fi
ln -sf /root/"${WORKDIR}"/apps/cli/lib/bin.js /usr/local/bin/dsh
chmod +x /usr/local/bin/dsh 2>/dev/null || true

echo "==> 校验"
node -v
/usr/local/bin/dsh --version 2>/dev/null | head -1 || echo '(dsh --version 无输出属正常)'
[ -f /root/"${WORKDIR}"/apps/cli/lib/bin.js ] && echo "✅ harness bin.js 就绪"
echo "==> offline-provision 完成"
