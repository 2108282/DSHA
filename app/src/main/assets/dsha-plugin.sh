#!/bin/sh
# npm 插件安装后登记到 dsh；普通 npm install 保持 npm 自身语义。
. /etc/profile.d/dsha-runtime-env.sh
case "$1" in
    install) shift; exec python3 /root/.dsh/plugin-manager.py npm "$@" ;;
    import|export|delete|list) exec python3 /root/.dsh/plugin-manager.py "$@" ;;
    *) printf '%s\n' '用法：dsha-plugin install <npm包名[@版本]>' \
            '      dsha-plugin import <插件压缩包路径>' \
            '      dsha-plugin list | delete <插件名>' \
            '安装或删除后，请重启 DSHA Web。普通依赖/命令行工具请使用 npm install。' ;;
esac
