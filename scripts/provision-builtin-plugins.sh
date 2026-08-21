#!/usr/bin/env bash
# ============================================================
# provision-builtin-plugins.sh — 在离线 rootfs 内预置 DSHA 内置插件
# （dsh-client-ui-mobile-adapt / dsh-device-shell-guide），
# 让离线包「解压即用」：首启无需运行时注入。
#
# 由 offline-provision.sh（chroot 内）调用；插件源在 /root/patches/builtin/。
# 与 App 端 ensureNativeMobileAdapt()/ensureDeviceShellGuide() 的 marker
# 幂等逻辑对齐：marker 存在 → App 启动时自动跳过注入。
# ============================================================
set -euo pipefail

SRC=/root/patches/builtin
DEST_MOBILE=/root/dsha-mobile-adapt
DEST_GUIDE=/root/dsha-device-shell-guide
PROFILE_DIR=/root/.dsh/profiles/web
NM="$PROFILE_DIR/node_modules"
PF="$PROFILE_DIR/package.json"
# marker = 实体目录 + "-installed"（与 App ensureNativeMobileAdapt/
# ensureDeviceShellGuide 检查的路径一致）
MARK_MOBILE="${DEST_MOBILE}-installed"
MARK_GUIDE="${DEST_GUIDE}-installed"
BUILTIN_SNAPSHOT=/root/dsha-builtin.txt

echo "==> 预置 DSHA 内置插件"

# ---------- 1) 复制插件实体（App 的 marker 检查会据此跳过运行时注入） ----------
if [ -d "$SRC/mobile-adapt" ]; then
  mkdir -p "$DEST_MOBILE"
  cp -f "$SRC/mobile-adapt/package.json" "$DEST_MOBILE/" 2>/dev/null || true
  cp -f "$SRC/mobile-adapt/index.js" "$DEST_MOBILE/" 2>/dev/null || true
  cp -f "$SRC/mobile-adapt/client.js" "$DEST_MOBILE/" 2>/dev/null || true
  cp -f "$SRC/mobile-adapt/cordis.patch.yml" "$DEST_MOBILE/" 2>/dev/null || true
  touch "$MARK_MOBILE"
  echo "  ✓ mobile-adapt 已预置 ($(ls "$DEST_MOBILE" | wc -l) 文件)"
else
  echo "  WARN: 缺 mobile-adapt 源（/root/patches/builtin/mobile-adapt），跳过"
fi

if [ -d "$SRC/device-shell-guide" ]; then
  mkdir -p "$DEST_GUIDE/lib"
  cp -f "$SRC/device-shell-guide/package.json" "$DEST_GUIDE/" 2>/dev/null || true
  cp -f "$SRC/device-shell-guide/cordis.patch.yml" "$DEST_GUIDE/" 2>/dev/null || true
  cp -f "$SRC/device-shell-guide/lib/index.js" "$DEST_GUIDE/lib/" 2>/dev/null || true
  touch "$MARK_GUIDE"
  echo "  ✓ device-shell-guide 已预置 ($(ls "$DEST_GUIDE" "$DEST_GUIDE/lib" 2>/dev/null | wc -l) 文件)"
else
  echo "  WARN: 缺 device-shell-guide 源（/root/patches/builtin/device-shell-guide），跳过"
fi

# ---------- 2) 注册到 web profile（merge，不覆盖已有插件） ----------
mkdir -p "$PROFILE_DIR" "$NM"
if [ -f "$PF" ]; then
  python3 - "$PF" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d.setdefault('dependencies', {})
d['dependencies']['dsh-client-ui-mobile-adapt'] = 'link:/root/dsha-mobile-adapt'
d['dependencies']['dsh-device-shell-guide'] = 'link:/root/dsha-device-shell-guide'
dsh = d.setdefault('dsh', {})
prof = dsh.setdefault('profile', {})
bundles = prof.setdefault('bundles', [])
for n in ('dsh-client-ui-mobile-adapt', 'dsh-device-shell-guide'):
    if n not in bundles:
        bundles.append(n)
json.dump(d, open(p, 'w'), indent=2, ensure_ascii=False)
print('  ✓ profile 已 merge 内置插件声明')
PY
else
  cat > "$PF" <<JSON
{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {
    "dsh-client-ui-mobile-adapt": "link:/root/dsha-mobile-adapt",
    "dsh-device-shell-guide": "link:/root/dsha-device-shell-guide"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "dsh-client-ui-mobile-adapt",
        "dsh-device-shell-guide"
      ]
    }
  }
}
JSON
  echo "  ✓ profile package.json 已创建"
fi

# ---------- 3) node_modules 符号链接（togglePlugin 靠改名开关） ----------
ln -sfn /root/dsha-mobile-adapt "$NM/dsh-client-ui-mobile-adapt"
ln -sfn /root/dsha-device-shell-guide "$NM/dsh-device-shell-guide"
echo "  ✓ node_modules 符号链接已建"

# ---------- 4) home 级 cordis.patch.yml：官方极简模式 bash 描述注入 ----------
# （复刻 App ensureDeviceShellGuide 的逻辑，marker 存在时 App 跳过，必须在这做）
HP=/root/.dsh/cordis.patch.yml
mkdir -p "$(dirname "$HP")"
if [ -f "$HP" ] && grep -q "dsha-device-guide-bash" "$HP"; then
  echo "  = home patch 已存在，跳过"
else
  cat >> "$HP" <<'PATCH'

# DSHA device guide (dsha-device-guide-bash) - 官方极简模式 bash 工具描述注入
- update:
  - id: persistent-bash
    name: '@deepseek-ai/dsh-tool-bash-persistent'
    config:
      description: |+
        Run commands in a bash shell
        * 设备操作：/root/dsh-bin/adb-shell "命令"（唯一可用通道，uid=2000，已配对）
        * 不要用裸 adb（守卫脚本，会失败）；Shizuku 桥备用 curl 127.0.0.1:3090/exec
        * 与用户交流请用中文回复
PATCH
  echo "  ✓ home patch（极简模式 bash 描述）已注入"
fi

# ---------- 5) 内置插件快照（App「隐藏自带」功能依赖；installGuard 只在
#            文件不存在时生成，离线预置的会沿用） ----------
printf 'dsh-client-ui-mobile-adapt\ndsh-device-shell-guide\n' > "$BUILTIN_SNAPSHOT"
echo "  ✓ 内置插件快照已写（dsha-builtin.txt）"

echo "==> 内置插件预置完成"
