#!/bin/bash
# DSHA_ADB_SCRIPT_VERSION=9
# DSHA ADB 无线配对环境安装（幂等；rootfs 内执行）
# 步骤：依赖(adb_shell_wifi/spake2-cffi) → 密钥 → 包装命令 /root/dsh-bin/adb-shell
# 依赖走「离线 wheel 包」（/root/.dsh/wheels，由 App 注入），不依赖 apt/网络/编译器。
# 完整日志写入 /root/.dsh/adb-setup.log（App 内置终端可 cat 查看）
set -u
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
export DEBIAN_FRONTEND=noninteractive

LOG=/root/.dsh/adb-setup.log
mkdir -p /root/.dsh
exec > >(tee "$LOG") 2>&1   # 全程 tee 到日志文件

echo "== [1/4] 校验 python3"
python3 --version || { echo "NO_PYTHON: 请先在安装页装好基础工具"; exit 1; }

# 依赖自检（新版 adb_shell_wifi(0.5.0+) 从 spake2.spake2 导入，模块名无下划线）
deps_ok() {
  python3 -c "import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob" 2>/dev/null
}

WHEELS=/root/.dsh/wheels
PIP_PKGS="adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf"

echo "== [2/4] 安装 Python 依赖 (离线 wheel 包)"
if deps_ok; then
  echo "deps 已就绪"
else
  if [ ! -d "$WHEELS" ] || ! ls "$WHEELS"/*.whl >/dev/null 2>&1; then
    echo "NO_WHEELS: 缺少离线 wheel 包目录 $WHEELS"
    echo "请重装最新 APK 并在配置页重开 ADB 开关（App 会自动注入）"
    exit 1
  fi

  # --- 确保 pip 可用：离线解压 pip/setuptools wheel（不依赖 apt/ensurepip） ---
  if ! python3 -m pip --version >/dev/null 2>&1; then
    echo "  >> pip 缺失，从离线 wheel 解压安装 ..."
    SITE=$(python3 -c "import site; print(site.getsitepackages()[0])" 2>/dev/null)
    [ -z "$SITE" ] && SITE="/usr/local/lib/python3.12/dist-packages"
    mkdir -p "$SITE"
    P=""
    for w in "$WHEELS"/pip-*.whl; do [ -f "$w" ] && P="$w"; done
    S=""
    for w in "$WHEELS"/setuptools-*.whl; do [ -f "$w" ] && S="$w"; done
    if [ -n "$P" ]; then
      unzip -qo "$P" -d "$SITE" && echo "  >> pip wheel 解压成功"
    else
      echo "  !! 找不到 pip wheel，尝试 ensurepip 兜底"
      python3 -m ensurepip --upgrade >/dev/null 2>&1 || true
    fi
    if [ -n "$S" ] && [ -d "$SITE/setuptools" ]; then
      unzip -qo "$S" -d "$SITE" 2>/dev/null || true
    fi
  fi
  if ! python3 -m pip --version >/dev/null 2>&1; then
    echo "PIP_MISSING: pip 仍不可用（离线 wheel 解压失败）"
    exit 1
  fi

  # --- 离线安装依赖（--no-index 纯本地，绝不联网） ---
  echo "  >> pip 离线安装 $PIP_PKGS ..."
  if python3 -m pip install --break-system-packages --no-index \
        --find-links="$WHEELS" $PIP_PKGS >/tmp/pip.log 2>&1; then
    echo "  >> 依赖安装成功"
  else
    echo "DEPS_FAILED: 离线安装失败，尾部错误："
    tail -15 /tmp/pip.log
    exit 1
  fi
  if ! deps_ok; then
    echo "DEPS_FAILED: 安装完成但导入校验失败："
    python3 -c "import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob" 2>&1 | tail -5
    exit 1
  fi
fi

echo "== [3/4] 生成 ADB 密钥（存在则跳过）"
python3 /root/.dsh/adb-pair.py --genkey || { echo "KEYGEN_FAILED"; exit 1; }

echo "== [4/4] 安装 /root/dsh-bin/adb-shell 包装命令"
mkdir -p /root/dsh-bin
cat > /root/dsh-bin/adb-shell <<'EOF'
#!/bin/bash
# DSHA ADB 设备 shell（无线通道，免 Shizuku）
# 安全铁律（与守卫合并）：守卫开（App 配置「危险 Shell 操作需确认」+ 标记存在）
# → 执行前经 dsh-confirm.sh --force 确认（所有命令都弹窗，用户点允许才执行）。
# 守卫关 → 不弹窗，只靠 agent 引导「口头报备」（用户可接受才执行）。
# 跳过确认：DSH_NO_CONFIRM=1（安装脚本/看门狗内部用，agent 无此变量）
if [ "${DSH_NO_CONFIRM:-0}" != "1" ] && [ -f /root/.dsh/confirm-shell-enabled ]; then
  /root/dsh-confirm.sh --force "adb-shell $*" || exit 1
fi
exec python3 /root/.dsh/adb-shell.py "$@"
EOF
chmod +x /root/dsh-bin/adb-shell

ls -l /root/.dsh/adbkeys/ | grep -q adbkey && echo "SETUP_DONE" || { echo "SETUP_ERR"; exit 1; }
echo "完整日志: $LOG"
