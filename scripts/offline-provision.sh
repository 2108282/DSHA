#!/usr/bin/env bash
# ============================================================
# offline-provision.sh — 在 arm64 rootfs（chroot / proot）内
# 预装 deepseek-harness 运行环境，产出「解压即用」的 rootfs。
#
# 默认装 RC6（@deepseek-ai/dsh@0.1.0-rc.6），与 App 默认开关
# use_rc6=true 对齐；RC6 失败再回退源码构建。
#
# 环境变量：
#   GITHUB_ACTIONS=true  → 官方源优先（GitHub runner 在海外）
#   DSHA_KEEP_CA=1       → 保留 ca-certificates（真 chroot 需要）
# ============================================================
set -euo pipefail

WORKDIR="${WORKDIR:-deepseek-harness}"
IN_CI="${GITHUB_ACTIONS:-}"
KEEP_CA="${DSHA_KEEP_CA:-}"
export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

echo "==> [1/8] 配置 apt 源"
if [ -n "$IN_CI" ]; then
  echo "    CI：保留官方源（ports.ubuntu.com / archive.ubuntu.com）"
else
  sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' \
    /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true
fi

echo "==> [2/8] apt 更新 + 安装基础工具"
apt-get update -y
apt-get install -y --no-install-recommends \
    curl git python3 make gcc g++ xz-utils ca-certificates
# proot 下 ca-certificates postinst 常失败，会把 dpkg 卡成 broken。
# 真 chroot / CI 必须保留证书，否则 https 全挂。
if [ -z "$KEEP_CA" ] && [ -z "$IN_CI" ]; then
  apt-get install -y --no-install-recommends ca-certificates 2>/dev/null || true
  dpkg --remove --force-remove-reinstreq ca-certificates 2>/dev/null || true
  dpkg --configure -a 2>/dev/null || true
fi
command -v python >/dev/null 2>&1 || ln -sf /usr/bin/python3 /usr/bin/python || true

echo "==> [3/8] 安装 Node.js v24.19.0"
if [ ! -x /usr/local/bin/node ]; then
  cd /tmp
  rm -f node.tar.xz
  if [ -n "$IN_CI" ]; then
    curl -fSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz \
      || curl -fSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz
  else
    curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz \
      || curl -kfsSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz
  fi
  tar -xJf node.tar.xz -C /usr/local --strip-components=1
  rm -f node.tar.xz
fi
node -v && npm -v

echo "==> [4/8] 安装 pnpm / node-gyp"
if [ -n "$IN_CI" ]; then
  export npm_config_registry=https://registry.npmjs.org
  printf 'registry=https://registry.npmjs.org\n' > /root/.npmrc
else
  export npm_config_registry=https://registry.npmmirror.com
  printf 'registry=https://registry.npmmirror.com\n' > /root/.npmrc
fi
command -v pnpm >/dev/null 2>&1 || npm install -g pnpm@11.7.0
command -v node-gyp >/dev/null 2>&1 || npm install -g node-gyp
pnpm -v
node-gyp --version || true

install_headers() {
  if [ -f /root/.cache/node-gyp/24.19.0/include/node/node.h ]; then
    echo "Node headers 已缓存"
    return 0
  fi
  mkdir -p /root/.cache/node-gyp/24.19.0
  cd /root/.cache/node-gyp/24.19.0
  if [ -n "$IN_CI" ]; then
    curl -fSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz \
      || curl -fSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz
  else
    curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz \
      || curl -kfsSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz
  fi
  tar -xzf headers.tar.gz --strip-components=1
  rm -f headers.tar.gz
  touch .install-stamp
}

build_pty() {
  local dir="$1"
  [ -n "$dir" ] && [ -d "$dir" ] || return 1
  if [ -f "$dir/build/Release/pty.node" ] || [ -f "$dir/prebuilds/linux-arm64/pty.node" ]; then
    echo "pty.node 已就绪: $dir"
    return 0
  fi
  cd "$dir"
  GYP=/usr/local/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js
  [ -f "$GYP" ] || GYP=$(find /usr/local/lib -maxdepth 8 -path '*/node-gyp/bin/node-gyp.js' 2>/dev/null | head -1)
  [ -n "$GYP" ] || GYP=$(command -v node-gyp)
  export npm_config_disturl=https://npmmirror.com/mirrors/node
  node "$GYP" rebuild
  [ -f "$dir/build/Release/pty.node" ] || [ -f "$dir/prebuilds/linux-arm64/pty.node" ]
}

echo "==> [5/8] 安装 @deepseek-ai/dsh@0.1.0-rc.6（App 默认 RC6）"
install_headers
RC6_OK=0
npm config set allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs --location=user 2>/dev/null || true
if npm install -g @deepseek-ai/dsh@0.1.0-rc.6 --force; then
  NP=$(find /usr/local/lib/node_modules -maxdepth 8 -path '*/node-pty' -type d 2>/dev/null | head -1)
  if build_pty "$NP"; then
    RC6_OK=1
    echo "RC6 + node-pty 就绪"
  else
    echo "WARN: RC6 已装但 node-pty 编译失败，尝试源码回退"
  fi
else
  echo "WARN: npm 安装 RC6 失败，回退源码构建"
fi

if [ "$RC6_OK" != 1 ]; then
  echo "==> [5b/8] 回退：克隆 deepseek-harness 源码并构建"
  cd /root
  rm -rf "${WORKDIR}"
  git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git "${WORKDIR}" \
    || git clone --depth 1 https://gitclone.com/github.com/deepseek-ai/deepseek-harness.git "${WORKDIR}" \
    || {
      curl -fSL --retry 3 -m 300 https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main -o dsh-src.tar.gz
      tar -xzf dsh-src.tar.gz
      mv deepseek-harness-main "${WORKDIR}"
      rm -f dsh-src.tar.gz
    }
  cd /root/"${WORKDIR}"
  grep -q 'onlyBuiltDependencies' pnpm-workspace.yaml 2>/dev/null || \
    printf '\nonlyBuiltDependencies:\n  - node-pty\n' >> pnpm-workspace.yaml
  pnpm install
  if [ -f package.json ] && grep -q '"build"' package.json; then
    pnpm run build || echo "WARN: pnpm run build 失败，若 lib/bin.js 已存在仍可继续"
  fi
  NP=$(ls -d node_modules/.pnpm/node-pty@*/node_modules/node-pty 2>/dev/null | head -1 || true)
  build_pty "$NP" || echo "WARN: 源码树 node-pty 编译失败"
  if [ -f /root/"${WORKDIR}"/apps/cli/lib/bin.js ]; then
    ln -sf /root/"${WORKDIR}"/apps/cli/lib/bin.js /usr/local/bin/dsh
    chmod +x /usr/local/bin/dsh 2>/dev/null || true
  fi
fi

echo "==> [6/8] 应用补丁"
cd /root
if [ -d /root/"${WORKDIR}"/.git ]; then
  if [ -f /root/patches/webui-sidebar.patch ]; then
    (cd /root/"${WORKDIR}" && git apply --check /root/patches/webui-sidebar.patch \
      && git apply /root/patches/webui-sidebar.patch && echo 'sidebar 补丁已应用') \
      || echo 'sidebar 补丁跳过'
  fi
  if [ -f /root/patches/bash-guard.patch ]; then
    (cd /root/"${WORKDIR}" && git apply --check /root/patches/bash-guard.patch \
      && git apply /root/patches/bash-guard.patch && echo 'bash-guard 补丁已应用') \
      || echo 'bash-guard 补丁跳过'
  fi
fi
if [ -f /root/patches/webui-polyfill.sh ]; then
  bash /root/patches/webui-polyfill.sh || true
fi

echo "==> [7/8] 安装危险命令确认包装器"
if [ -f /root/patches/rootfs-confirm-install.sh ]; then
  bash /root/patches/rootfs-confirm-install.sh
fi
# 空的内置插件快照，避免 App 误把后续用户插件当自带
touch /root/dsha-builtin.txt

echo "==> [8/8] 校验"
node -v
command -v pnpm
command -v node-gyp
command -v dsh && dsh --version 2>/dev/null | head -1 || echo '(dsh --version 无输出属正常)'
test -f /root/dsh-guard.sh
test -d /root/dsh-bin
test -f /root/dsh-bin/.version
if [ -x /usr/local/bin/dsh ] || command -v dsh >/dev/null 2>&1; then
  echo "✅ dsh 命令就绪"
else
  echo "❌ dsh 命令缺失" >&2
  exit 1
fi
echo "==> offline-provision 完成"
