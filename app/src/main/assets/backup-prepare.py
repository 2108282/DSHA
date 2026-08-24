#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 备份前置整理（在 rootfs 内运行）。

目标：让备份包「自描述 + 可移植」，跨设备/跨版本恢复不再必然失败。

做两件事（任一失败都不影响备份本体——宽容优先）：
1. 内联本机路径插件：profile 的 dependencies 里形如 link:/root/plugin-src/x 的
   依赖只是一个绝对路径，换设备后目录不存在 → dsh 启动报
   "cannot resolve profile bundle"。这里把这些目录的内容复制到
   /root/.dsha-plugin-src/<包名>/ 一起打进备份，恢复端再落地并重写路径。
2. 生成 /root/.dsha-backup-manifest.json：记录格式版本、App/dsh 版本、工作目录、
   各 profile 的 bundles 与 link 依赖原始路径，供恢复端决策与提示。

用法：python3 backup-prepare.py [--app-version 1.1.5] [--app-code 26] [--workdir deepseek-harness]
输出：最后一行打印需要额外打包的相对路径（空格分隔，供 tar 使用）。
"""
import json
import os
import shutil
import sys
import time

# 路径可配：不然测「磁盘满 / 权限被拒时会不会留下半个备份」只能拿真实环境试，
# 而这个脚本会在 /root 下写清单和内联插件源码，试一次就污染一次
DSH_HOME = os.environ.get("DSHA_DSH_HOME", "/root/.dsh")
_BP_ROOT = os.path.dirname(DSH_HOME.rstrip("/")) or "/root"
PROFILES = os.path.join(DSH_HOME, "profiles")
INLINE_DIR = os.path.join(_BP_ROOT, ".dsha-plugin-src")
MANIFEST = os.path.join(_BP_ROOT, ".dsha-backup-manifest.json")
# 内联单个插件的体积上限（防把巨大目录塞进备份）
MAX_INLINE_BYTES = 24 * 1024 * 1024
SKIP_DIRS = {"node_modules", ".git", ".pnpm-store", "dist-cache"}


def arg(name, default=""):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return default


def local_path_dep(spec):
    """link:/file: 形式的本机路径依赖 → 返回路径；否则 None。"""
    if not isinstance(spec, str):
        return None
    for prefix in ("link:", "file:"):
        if spec.startswith(prefix):
            return spec[len(prefix):]
    return None


def dir_size(path):
    total = 0
    for root, dirs, files in os.walk(path):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
        if total > MAX_INLINE_BYTES:
            return total
    return total


def copy_plugin(src, dst):
    """复制插件源码（跳过 node_modules 等重目录）。失败返回 False。"""
    try:
        if os.path.isdir(dst):
            shutil.rmtree(dst, ignore_errors=True)
        shutil.copytree(src, dst, symlinks=True,
                        ignore=shutil.ignore_patterns(*SKIP_DIRS))
        return True
    except Exception as e:
        print("[backup-prepare] 内联失败 %s: %s" % (src, e), file=sys.stderr)
        return False


def read_dsh_version():
    for p in ("/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json",
              "/root/deepseek-harness/package.json"):
        try:
            with open(p) as f:
                v = json.load(f).get("version")
            if v:
                return v
        except Exception:
            pass
    return "unknown"


def main():
    manifest = {
        "formatVersion": 2,
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "appVersion": arg("app-version", "unknown"),
        "appVersionCode": arg("app-code", "0"),
        "dshVersion": read_dsh_version(),
        "workdir": arg("workdir", "deepseek-harness"),
        "profiles": {},
        "inlinedPlugins": [],
        "notes": "恢复端见 restore-merge.py：无此文件也能恢复（会走启发式推断）",
    }
    extra = []
    inlined_any = False
    try:
        if os.path.isdir(INLINE_DIR):
            shutil.rmtree(INLINE_DIR, ignore_errors=True)
        for prof in sorted(os.listdir(PROFILES)) if os.path.isdir(PROFILES) else []:
            pkg_path = os.path.join(PROFILES, prof, "package.json")
            if not os.path.isfile(pkg_path):
                continue
            try:
                with open(pkg_path) as f:
                    pkg = json.load(f)
            except Exception as e:
                print("[backup-prepare] 读取 %s 失败: %s" % (pkg_path, e), file=sys.stderr)
                continue
            deps = pkg.get("dependencies") or {}
            bundles = (((pkg.get("dsh") or {}).get("profile") or {}).get("bundles")) or []
            link_deps = {}
            for name, spec in deps.items():
                p = local_path_dep(spec)
                if p is None:
                    continue
                link_deps[name] = spec
                # 内联该插件源码（体积超限则只记录路径，恢复端再提示重装）
                if os.path.isdir(p):
                    if dir_size(p) <= MAX_INLINE_BYTES:
                        os.makedirs(INLINE_DIR, exist_ok=True)
                        if copy_plugin(p, os.path.join(INLINE_DIR, name)):
                            manifest["inlinedPlugins"].append(name)
                            inlined_any = True
                    else:
                        print("[backup-prepare] %s 超过内联上限，跳过内联" % name, file=sys.stderr)
            manifest["profiles"][prof] = {
                "bundles": list(bundles),
                "linkDeps": link_deps,
            }
    except Exception as e:
        print("[backup-prepare] 整理异常（不影响备份）: %s" % e, file=sys.stderr)

    try:
        with open(MANIFEST, "w") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        extra.append(os.path.basename(MANIFEST))
    except Exception as e:
        print("[backup-prepare] manifest 写入失败: %s" % e, file=sys.stderr)

    if inlined_any and os.path.isdir(INLINE_DIR):
        extra.append(os.path.basename(INLINE_DIR))
    # 末行：供 tar 追加的相对路径（相对 /root）
    print(" ".join(extra))
    return 0


if __name__ == "__main__":
    sys.exit(main())
