#!/bin/bash
# plugin-links-patch.sh — 自动拔除历史 @deepseek-ai 相对死链接，建立第三方插件双向寻址闭环
set -u

python3 - << 'PY'
import os

bundled = "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
dsh_nm_dsh = "/root/.dsh/node_modules/@deepseek-ai"
os.makedirs(dsh_nm_dsh, exist_ok=True)

# 1. 自动拔除 /root/.dsh/node_modules/@deepseek-ai 下所有指向 /root/usr 的历史死链接并以绝对路径重建
if os.path.isdir(bundled):
    for item in os.listdir(bundled):
        src = os.path.join(bundled, item)
        dst = os.path.join(dsh_nm_dsh, item)
        if os.path.islink(dst):
            if not os.path.exists(dst) or os.path.realpath(dst) != os.path.realpath(src):
                try: os.unlink(dst)
                except OSError: pass
        if not os.path.exists(dst):
            try: os.symlink(src, dst, target_is_directory=True)
            except OSError: pass

# 2. 为 plugin-src 下的所有第三方插件补齐就近 @deepseek-ai 依赖与全局模块软链
src_dir = "/root/.dsh/plugin-src"
if os.path.isdir(src_dir):
    for name in os.listdir(src_dir):
        p_path = os.path.join(src_dir, name)
        if not os.path.isdir(p_path) or not os.path.isfile(os.path.join(p_path, "package.json")):
            continue
        p_nm = os.path.join(p_path, "node_modules")
        os.makedirs(p_nm, exist_ok=True)
        p_link = os.path.join(p_nm, "@deepseek-ai")
        if os.path.islink(p_link):
            try:
                if not os.path.exists(p_link) or os.path.realpath(p_link) != os.path.realpath(bundled):
                    os.unlink(p_link)
            except OSError: pass
        if not os.path.exists(p_link):
            try: os.symlink(bundled, p_link, target_is_directory=True)
            except OSError: pass

        g_link = os.path.join("/usr/local/lib/node_modules", name)
        if os.path.islink(g_link):
            try:
                if not os.path.exists(g_link) or os.path.realpath(g_link) != os.path.realpath(p_path):
                    os.unlink(g_link)
            except OSError: pass
        if not os.path.exists(g_link):
            try: os.symlink(p_path, g_link, target_is_directory=True)
            except OSError: pass
PY

echo PLUGIN_LINKS_PATCH_OK
