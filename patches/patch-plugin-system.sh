#!/system/bin/sh
# ============================================================
# patch-plugin-system.sh — DSHA 插件系统 @scope 物理隔离与自愈加固补丁
# 
# 职责：
# 1. 自动拔除历史老脚本遗留的 node_modules/@scope 父级软链，重构为标准物理目录；
# 2. 抢救被 pnpm 误重命名的 .ignored_* 插件源码目录并拔除死链；
# 3. 部署加固后的 register-builtin-plugins.py, plugin-manager.py, plugin-lifecycle.py, dsha-plugin-heal.sh。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在为现有系统执行插件系统安全加固与死链自愈..."

if [ ! -d "$ROOTFS/root" ]; then
    echo "⚠️ 错误: 未检测到有效的 rootfs 目录 ($ROOTFS)，跳过补丁应用。"
    exit 1
fi

DSH_DIR="$ROOTFS/root/.dsh"
mkdir -p "$DSH_DIR"

# 1. 优先从模块外部 rootfs-overlay 同步加固脚本
SRC_OVERLAY="$(dirname "$0")/../rootfs-overlay/root/.dsh"
if [ -d "$SRC_OVERLAY" ]; then
    for f in register-builtin-plugins.py plugin-manager.py plugin-lifecycle.py dsha-plugin-compat.sh dsha-plugin-heal.sh; do
        if [ -f "$SRC_OVERLAY/$f" ]; then
            cp -f "$SRC_OVERLAY/$f" "$DSH_DIR/$f"
            chmod 755 "$DSH_DIR/$f"
            echo "  ✓ 同步最新加固脚本: $f"
        fi
    done
fi

# 2. 在容器内部执行 Python 自愈修补（解绑 @scope 软链、抢救 .ignored_* 源码）
if [ -x "$ROOTFS/usr/bin/python3" ] || [ -x "$ROOTFS/usr/local/bin/node" ]; then
    chroot "$ROOTFS" /usr/bin/python3 - << 'PY' 2>/dev/null || true
import os

plugin_src = "/root/.dsh/plugin-src"
if os.path.isdir(plugin_src):
    for root, dirs, files in os.walk(plugin_src):
        for d in dirs:
            if d.startswith(".ignored_"):
                real_name = d[len(".ignored_"):]
                target_path = os.path.join(root, real_name)
                ignored_path = os.path.join(root, d)
                if os.path.islink(target_path):
                    try: os.unlink(target_path)
                    except OSError: pass
                if not os.path.exists(target_path):
                    try:
                        os.rename(ignored_path, target_path)
                        print(f"  ✓ 抢救恢复插件源码: {real_name}")
                    except OSError: pass

targets = ["/root/.dsh/profiles/web/node_modules", "/usr/local/lib/node_modules"]
if os.path.isdir(plugin_src):
    for base_dir in targets:
        if not os.path.isdir(base_dir): continue
        for scope in os.listdir(plugin_src):
            if not scope.startswith("@"): continue
            scope_src = os.path.join(plugin_src, scope)
            if not os.path.isdir(scope_src): continue
            scope_nm = os.path.join(base_dir, scope)
            if os.path.islink(scope_nm):
                try: os.unlink(scope_nm)
                except OSError: pass
            os.makedirs(scope_nm, exist_ok=True)
            for pkg in os.listdir(scope_src):
                pkg_src = os.path.join(scope_src, pkg)
                if not os.path.isdir(pkg_src) or not os.path.isfile(os.path.join(pkg_src, "package.json")):
                    continue
                leaf_link = os.path.join(scope_nm, pkg)
                if os.path.islink(leaf_link):
                    try:
                        if os.path.realpath(leaf_link) != os.path.realpath(pkg_src):
                            os.unlink(leaf_link)
                    except OSError: pass
                if not os.path.lexists(leaf_link):
                    try:
                        os.symlink(pkg_src, leaf_link, target_is_directory=True)
                        print(f"  ✓ 规范叶子软链: {scope}/{pkg}")
                    except OSError: pass
PY
fi

echo "✓ 插件系统加固与自愈补丁应用完成！"
exit 0
