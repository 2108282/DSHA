#!/data/data/com.termux/files/usr/bin/bash
# deepseek-harness 一键安装脚本（在 Termux 内执行）
# 由 DSH启动器 生成：占位符已替换为用户配置。
set -e

API_KEY="@@API_KEY@@"
PERMISSION_MODE="@@PERMISSION_MODE@@"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
HOME_DIR="/data/data/com.termux/files/home"

if [ -z "$API_KEY" ]; then
  echo "!! 未配置 API key，请先在 App「配置」模块填入。"
  exit 1
fi

echo "==> [1/5] 更新包管理器"
pkg update -y

echo "==> [2/5] 安装依赖 + Node.js LTS"
pkg install -y curl git python make clang binutils nodejs-lts

NODE_V=$(node -v)
echo "    Node 版本: $NODE_V"
MAJOR=$(echo "$NODE_V" | sed 's/v\([0-9]*\).*/\1/')
MINOR=$(echo "$NODE_V" | sed 's/v[0-9]*\.\([0-9]*\).*/\1/')
if [ "$MAJOR" -lt 22 ] || { [ "$MAJOR" -eq 22 ] && [ "$MINOR" -lt 19 ]; }; then
  echo "!! Node 版本过低 (需 >= 22.19)，当前 $NODE_V，请升级 Termux 的 nodejs-lts。"
  exit 1
fi

echo "==> [3/5] 安装 pnpm"
npm install -g pnpm

echo "==> [4/5] 拉取并构建 deepseek-harness"
cd "$HOME_DIR"
if [ ! -d deepseek-harness ]; then
  git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git
fi
cd deepseek-harness
pnpm install
pnpm run build

echo "==> [5/5] 写入 API key"
printf 'DEEPSEEK_API_KEY=%s\n' "$API_KEY" > .env

echo "==> [6/6] 安装危险命令确认包装器"
DSH_BIN="$HOME_DIR/dsh-bin"
mkdir -p "$DSH_BIN"

# 确认核心脚本（termux-dialog 弹窗，勾选「允许」才放行）
cat > "$HOME_DIR/dsh-confirm.sh" <<'DSH_CONFIRM'
#!/data/data/com.termux/files/usr/bin/bash
CMD="$*"
if ! command -v termux-dialog >/dev/null 2>&1; then
  echo "!! 未安装 Termux:API（pkg install termux-api），已拒绝危险操作：$CMD" >&2
  exit 1
fi
RES=$(termux-dialog checkbox -v "允许" -t "DSHA 安全确认：模型试图执行 [$CMD]，是否允许？" 2>/dev/null)
case "$RES" in
  *"允许"*) exit 0 ;;
  *) echo "!! 用户拒绝：$CMD" >&2; exit 1 ;;
esac
DSH_CONFIRM
chmod +x "$HOME_DIR/dsh-confirm.sh"

# 危险命令包装：命中后先确认，再执行真实命令
for C in rm dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe pm sm settings; do
cat > "$DSH_BIN/$C" <<DSH_WRAP
#!/data/data/com.termux/files/usr/bin/bash
SELF=\$(basename "\$0")
REAL=""
for p in /system/bin /system/xbin \$PREFIX/bin /data/data/com.termux/files/usr/bin; do
  if [ -x "\$p/\$SELF" ] && [ "\$p/\$SELF" != "\$0" ]; then REAL="\$p/\$SELF"; break; fi
done
[ -z "\$REAL" ] && REAL=\$(command -v -- "\$SELF" 2>/dev/null)
if [ -n "\$REAL" ] && "\$HOME/dsh-confirm.sh" "\$SELF \$*"; then
  exec "\$REAL" "\$@"
fi
exit 1
DSH_WRAP
chmod +x "$DSH_BIN/$C"
done
echo "    已包装: rm dd mkfs fdisk reboot shutdown pm 等（PATH 前置 \$HOME/dsh-bin）"

echo ""
echo "==> 安装完成！"
echo "    工作区: $HOME_DIR/deepseek-harness"
echo "    启动:   node apps/cli/lib/bin.js web"
echo "    安全:   危险命令会自动弹确认框（需 pkg install termux-api）"
