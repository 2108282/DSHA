#!/bin/bash
# fix-stale-bundles.sh — 清理 profile 里无法解析的 bundle 条目（幂等）。
# 解决 rc.8 "cannot resolve profile bundle" 报错：bundles 里存在但依赖未装/
# 手写 file: 声明不被识别等历史遗留，会导致 dsh web 无法启动。
# 启动前由 App 调用；只移除【无法解析】的条目，正常插件不动。
set -u
PF=/root/.dsh/profiles/web/package.json
[ -f "$PF" ] || exit 0

python3 - "$PF" <<'PY'
import json, os, sys
pf = sys.argv[1]
d = json.load(open(pf))
bundles = d.get('dsh', {}).get('profile', {}).get('bundles', [])
if not bundles:
    print('BUNDLES_OK: empty')
    sys.exit(0)
deps = d.get('dependencies', {})
nm = os.path.join(os.path.dirname(pf), 'node_modules')

def resolvable(name):
    # 1) dsh 安装目录（内置 @deepseek-ai/* bundle）
    sub = name.split('/')[-1]
    for base in (
        '/usr/local/lib/node_modules/@deepseek-ai',
        '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai',
        '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules',
    ):
        if os.path.isfile(os.path.join(base, sub, 'package.json')):
            return True
    # 2) profile node_modules（pnpm/link 安装的树外插件）
    if os.path.isfile(os.path.join(nm, name, 'package.json')):
        return True
    # 3) pnpm 的 .pnpm 虚拟目录里存在（file:/link: 装的）
    pnpm = os.path.join(nm, '.pnpm')
    if os.path.isdir(pnpm):
        key = name.replace('@', '').replace('/', '+')
        for e in os.listdir(pnpm):
            if e.startswith(key + '@'):
                return True
    return False

keep, removed = [], []
for b in bundles:
    if resolvable(b):
        keep.append(b)
    else:
        removed.append(b)

if removed:
    d['dsh']['profile']['bundles'] = keep
    json.dump(d, open(pf, 'w'), indent=2)
    print('STALE_REMOVED: ' + ','.join(removed))
else:
    print('BUNDLES_OK: all resolvable')
PY
