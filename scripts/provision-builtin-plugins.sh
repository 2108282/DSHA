#!/usr/bin/env bash
# ============================================================
# provision-builtin-plugins.sh — 在离线 rootfs 内预置 DSHA 内置插件
# （dsh-device-shell-guide / dsh-task-notifier），
# 让离线包「解压即用」：首启无需运行时注入。
#
# 由 offline-provision.sh（chroot 内）调用；插件源在 /root/patches/builtin/。
# 与 App 端 ensureNativeMobileAdapt()/ensureDeviceShellGuide() 的 marker
# 幂等逻辑对齐：marker 存在 → App 启动时自动跳过注入。
# ============================================================
set -euo pipefail

SRC=/root/patches/builtin
DEST_GUIDE=/root/dsha-device-shell-guide
DEST_NOTIFIER=/root/dsha-task-notifier
DEST_OVERLAY=/root/dsha-status-overlay
DEST_WEB=/root/dsha-web-mobile
PROFILE_DIR=/root/.dsh/profiles/web
NM="$PROFILE_DIR/node_modules"
PF="$PROFILE_DIR/package.json"
# marker = 实体目录 + "-installed"（与 App ensureNativeMobileAdapt/
# ensureDeviceShellGuide 检查的路径一致）
MARK_GUIDE="${DEST_GUIDE}-installed"
MARK_NOTIFIER="${DEST_NOTIFIER}-installed"
MARK_OVERLAY="${DEST_OVERLAY}-installed"
MARK_WEB="${DEST_WEB}-installed"
BUILTIN_SNAPSHOT=/root/dsha-builtin.txt

echo "==> 预置 DSHA 内置插件"

# ---------- 1) 复制插件实体（App 的 marker 检查会据此跳过运行时注入） ----------
# marker 是「已经装好了」的凭证 —— App 看到它就跳过运行时注入。所以只有实体**完整**
# 才能 touch。原来 mobile 那块是 `cp … 2>/dev/null || true` 三连再无条件 touch，
# 而源里的入口文件在 lib/ 下、根目录压根没有 index.js / client.js：三条 cp 全部
# 静默失败，却照样立了「已装好」的凭证。结果离线包里的插件只剩 package.json +
# cordis.patch.yml，main 指向的 lib/index.js 不存在 —— dsh 加载它必然失败，
# 而 App 因为 marker 在就永远不会来补。现在一律先校验再立 marker。
if [ -d "$SRC/device-shell-guide" ]; then
  mkdir -p "$DEST_GUIDE/lib"
  cp -f "$SRC/device-shell-guide/package.json" "$DEST_GUIDE/" 2>/dev/null || true
  cp -f "$SRC/device-shell-guide/cordis.patch.yml" "$DEST_GUIDE/" 2>/dev/null || true
  cp -f "$SRC/device-shell-guide/lib/index.js" "$DEST_GUIDE/lib/" 2>/dev/null || true
  if [ -s "$DEST_GUIDE/package.json" ] && [ -s "$DEST_GUIDE/lib/index.js" ]; then
    touch "$MARK_GUIDE"
    echo "  ✓ device-shell-guide 已预置"
  else
    rm -f "$MARK_GUIDE"
    echo "  ERROR: device-shell-guide 实体不完整 → 不立 marker" >&2
  fi
else
  echo "  WARN: 缺 device-shell-guide 源（/root/patches/builtin/device-shell-guide），跳过"
fi

if [ -d "$SRC/task-notifier" ]; then
  mkdir -p "$DEST_NOTIFIER/lib"
  cp -f "$SRC/task-notifier/package.json" "$DEST_NOTIFIER/" 2>/dev/null || true
  cp -f "$SRC/task-notifier/cordis.patch.yml" "$DEST_NOTIFIER/" 2>/dev/null || true
  cp -f "$SRC/task-notifier/lib/index.js" "$DEST_NOTIFIER/lib/" 2>/dev/null || true
  if [ -s "$DEST_NOTIFIER/package.json" ] && [ -s "$DEST_NOTIFIER/lib/index.js" ]; then
    touch "$MARK_NOTIFIER"
    echo "  ✓ task-notifier 已预置"
  else
    rm -f "$MARK_NOTIFIER"
    echo "  ERROR: task-notifier 实体不完整 → 不立 marker" >&2
  fi
else
  echo "  WARN: 缺 task-notifier 源（/root/patches/builtin/task-notifier），跳过"
fi

if [ -d "$SRC/web-mobile" ]; then
  rm -rf "$DEST_WEB"
  mkdir -p "$DEST_WEB"
  cp -f "$SRC/web-mobile/package.json" "$DEST_WEB/"
  cp -f "$SRC/web-mobile/cordis.patch.yml" "$DEST_WEB/"
  cp -f "$SRC/web-mobile/LICENSE" "$DEST_WEB/" 2>/dev/null || true
  cp -a "$SRC/web-mobile/lib" "$DEST_WEB/"
  cp -a "$SRC/web-mobile/assets" "$DEST_WEB/" 2>/dev/null || true
  if [ -s "$DEST_WEB/package.json" ] && [ -s "$DEST_WEB/cordis.patch.yml" ] \
     && [ -s "$DEST_WEB/lib/index.js" ] && [ -s "$DEST_WEB/lib/client.js" ] \
     && [ -s "$DEST_WEB/lib/compress.js" ]; then
    touch "$MARK_WEB"
    echo "  ✓ web-mobile 已预置（完整 client/compress bundle）"
  else
    rm -f "$MARK_WEB"
    echo "  ERROR: web-mobile 实体不完整 → 不立 marker" >&2
  fi
else
  echo "  WARN: 缺 web-mobile 源（/root/patches/builtin/web-mobile），跳过" >&2
fi

# 流式悬浮条（把 agent 输出实时显示在屏幕顶部）。默认功能是关着的，插件本身仍要装好，
# 否则用户打开开关后还得等一次 Web 重启才生效。
if [ -d "$SRC/status-overlay" ]; then
  mkdir -p "$DEST_OVERLAY/lib"
  cp -f "$SRC/status-overlay/package.json" "$DEST_OVERLAY/" 2>/dev/null || true
  cp -f "$SRC/status-overlay/cordis.patch.yml" "$DEST_OVERLAY/" 2>/dev/null || true
  cp -f "$SRC/status-overlay/lib/index.js" "$DEST_OVERLAY/lib/" 2>/dev/null || true
  if [ -s "$DEST_OVERLAY/package.json" ] && [ -s "$DEST_OVERLAY/lib/index.js" ]; then
    touch "$MARK_OVERLAY"
    echo "  ✓ status-overlay 已预置"
  else
    rm -f "$MARK_OVERLAY"
    echo "  ERROR: status-overlay 实体不完整 → 不立 marker" >&2
  fi
else
  echo "  WARN: 缺 status-overlay 源（/root/patches/builtin/status-overlay），跳过"
fi

# ---------- 2) 注册到 web profile（merge，不覆盖已有插件） ----------
mkdir -p "$PROFILE_DIR" "$NM"
if [ -f "$PF" ]; then
  python3 - "$PF" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d.setdefault('dependencies', {})
d['dependencies']['dsh-device-shell-guide'] = 'link:/root/dsha-device-shell-guide'
d['dependencies']['dsh-task-notifier'] = 'link:/root/dsha-task-notifier'
d['dependencies']['dsh-status-overlay'] = 'link:/root/dsha-status-overlay'
d['dependencies']['dsh-web-mobile'] = 'link:/root/dsha-web-mobile'
dsh = d.setdefault('dsh', {})
prof = dsh.setdefault('profile', {})
bundles = prof.setdefault('bundles', [])
for n in ('dsh-device-shell-guide', 'dsh-task-notifier', 'dsh-status-overlay', 'dsh-web-mobile'):
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
    "dsh-device-shell-guide": "link:/root/dsha-device-shell-guide",
    "dsh-task-notifier": "link:/root/dsha-task-notifier",
    "dsh-status-overlay": "link:/root/dsha-status-overlay",
    "dsh-web-mobile": "link:/root/dsha-web-mobile"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "dsh-device-shell-guide",
        "dsh-task-notifier",
        "dsh-status-overlay",
        "dsh-web-mobile"
      ],
      "patchReload": "startup"
    }
  }
}
JSON
  echo "  ✓ profile package.json 已创建"
fi

# ---------- 3) node_modules 符号链接（togglePlugin 靠改名开关） ----------
ln -sfn /root/dsha-device-shell-guide "$NM/dsh-device-shell-guide"
ln -sfn /root/dsha-task-notifier "$NM/dsh-task-notifier"
ln -sfn /root/dsha-status-overlay "$NM/dsh-status-overlay"
ln -sfn /root/dsha-web-mobile "$NM/dsh-web-mobile"
echo "  ✓ node_modules 符号链接已建"

# Cordis loader imports entries from its own global module location rather than
# the profile's working directory.  Keep explicit global links as well; without
# these, a profile can look registered while the real loader reports
# ERR_MODULE_NOT_FOUND.  Each link is created independently to avoid shell
# precedence/word-splitting surprises.
GLOBAL_NM=/usr/local/lib/node_modules
ln -sfn /root/dsha-device-shell-guide "$GLOBAL_NM/dsh-device-shell-guide"
ln -sfn /root/dsha-task-notifier "$GLOBAL_NM/dsh-task-notifier"
ln -sfn /root/dsha-status-overlay "$GLOBAL_NM/dsh-status-overlay"
ln -sfn /root/dsha-web-mobile "$GLOBAL_NM/dsh-web-mobile"
echo "  ✓ global node_modules 符号链接已建"

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
printf 'dsh-device-shell-guide\ndsh-task-notifier\ndsh-status-overlay\ndsh-web-mobile\n' > "$BUILTIN_SNAPSHOT"
echo "  ✓ 内置插件快照已写（dsha-builtin.txt）"

echo "==> 内置插件预置完成"
