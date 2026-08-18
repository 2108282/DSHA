#!/bin/bash
# 把「设备 Shell 引导」插件内置到手机 rootfs 的 deepseek-harness（web-app profile）。
# 启用后：每个新对话的系统提示后注入「设备操作能力」引导，让 agent 用
#   /root/dsh-bin/adb-shell "命令"（ADB 无线，uid=2000 免 root）或
#   curl 127.0.0.1:3090/exec（Shizuku 桥）干预实体机。
#
# 用法：把本目录（device-shell-guide-builtin/）放到 rootfs 可访问处，然后：
#   bash /sdcard/Download/DSHA/device-shell-guide-builtin/apply-device-shell-guide.sh
# 重启 WebUI（启动页「重启」）生效。停用：插件管理 → 禁用 device-shell-guide。
set -u
H=/root/deepseek-harness
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -d "$H/packages/bundle/web-app" ] || { echo "ERROR: not found $H"; exit 1; }

echo "== 1) 复制插件包 -> packages/host/dsh-device-shell-guide =="
mkdir -p "$H/packages/host/dsh-device-shell-guide"
cp -r "$DIR/dsh-device-shell-guide/." "$H/packages/host/dsh-device-shell-guide/"
echo "OK package copied"

echo "== 2) web-app dependencies += dsh-device-shell-guide (workspace:*) =="
python3 -c 'import json; p="/root/deepseek-harness/packages/bundle/web-app/package.json"; d=json.load(open(p));
d.setdefault("dependencies",{})["dsh-device-shell-guide"]="workspace:*"; json.dump(d,open(p,"w"),ensure_ascii=False,indent=2); print("OK deps")'

echo "== 3) cordis.patch.yml 追加 insert（幂等） =="
grep -q "device-shell-guide" "$H/packages/bundle/web-app/cordis.patch.yml" || \
  printf '\n# DSHA device-shell-guide builtin\n- insert:\n    - id: device-shell-guide\n      name: '\''dsh-device-shell-guide'\''\n' >> "$H/packages/bundle/web-app/cordis.patch.yml"
echo "OK insert"

echo "== 4) pnpm install 链接 workspace 包 =="
cd "$H" && pnpm install --no-frozen-lockfile 2>&1 | tail -3

echo ""
echo "✅ DONE. 在启动页点「重启」后，每个新对话的系统提示将包含设备操作引导。"
echo "   停用：App 插件管理 → 禁用 device-shell-guide。"
echo "   检查：node_modules 内 ls packages/dsh-device-shell-guide/lib/index.js 应存在。"
