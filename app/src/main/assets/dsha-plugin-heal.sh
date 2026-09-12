#!/bin/bash
# DSHA: 一键自愈与修复全部内置插件与第三方插件的依赖符号链接
# 在 Linux 容器内原生运行，确保路径 100% 正确，彻底根除 ERR_MODULE_NOT_FOUND
set -e
TARGET="/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"

# 1. 修复 /root/.dsh/plugin-src/* 下的所有第三方插件 (如 dsh-agy)
if [ -d "/root/.dsh/plugin-src" ]; then
  for p in /root/.dsh/plugin-src/*; do
    [ -d "$p" ] || continue
    name=$(basename "$p")
    # 全局顶层软链
    ln -sfn "$p" "/usr/local/lib/node_modules/$name"
    # profile 局部软链
    mkdir -p "/root/.dsh/profiles/web/node_modules"
    ln -sfn "$p" "/root/.dsh/profiles/web/node_modules/$name"
    # 插件自身 node_modules 真实目录与 @deepseek-ai 软链
    if [ -L "$p/node_modules" ]; then
      rm -f "$p/node_modules"
    fi
    mkdir -p "$p/node_modules"
    ln -sfn "$TARGET" "$p/node_modules/@deepseek-ai"
    echo "Fixed third-party plugin: $name"
  done
fi

# 2. 修复 /root/dsha-* 内置插件
for p in /root/dsha-*; do
  [ -d "$p" ] || continue
  bname=$(basename "$p")
  if [ "$bname" = "dsha-repo" ] || [ "$bname" = "dsha-builtin.txt" ] || [ "$bname" = "dsha-device-shell-guide-installed" ] || [ "$bname" = "dsha-status-overlay-installed" ] || [ "$bname" = "dsha-task-notifier-installed" ] || [ "$bname" = "dsha-web-mobile-installed" ]; then
    continue
  fi
  name="dsh-${bname#dsha-}"
  ln -sfn "$p" "/usr/local/lib/node_modules/$name"
  if [ -L "$p/node_modules" ]; then
    rm -f "$p/node_modules"
  fi
  mkdir -p "$p/node_modules"
  ln -sfn "$TARGET" "$p/node_modules/@deepseek-ai"
  mkdir -p "/root/.dsh/profiles/web/node_modules"
  ln -sfn "$p" "/root/.dsh/profiles/web/node_modules/$name"
  echo "Fixed builtin plugin: $name"
done
echo "HEAL_ALL_PLUGINS_OK"
