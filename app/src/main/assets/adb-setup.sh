#!/bin/bash
# DSHA_ADB_SCRIPT_VERSION=3
# DSHA ADB 无线配对环境安装（幂等；rootfs 内执行）
# 步骤：依赖(adb_shell_wifi/spake2-cffi) → 密钥 → 包装命令 /root/dsh-bin/adb-shell
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

echo "== [2/4] 安装 Python 依赖 (adb_shell_wifi / spake2-cffi ...)"
if deps_ok; then
  echo "deps 已就绪"
else
  # --- 确保 pip 可用：Ubuntu 24.04 base 默认没有 pip！ ---
  if ! python3 -m pip --version >/dev/null 2>&1; then
    echo "  >> pip 不存在，尝试安装 python3-pip ..."
    (apt-get update -qq && apt-get install -y -qq python3-pip) >/dev/null 2>&1 \
      || python3 -m ensurepip --upgrade >/dev/null 2>&1 \
      || echo "  !! pip 安装失败，继续尝试 ensurepip"
  fi
  if ! python3 -m pip --version >/dev/null 2>&1; then
    echo "PIP_MISSING: python3 -m pip 不可用（apt/ensurepip 都失败）"
    echo "请检查 rootfs 网络（apt 源）后在安装页重跑基础工具步骤"
    exit 1
  fi

  # --- 多镜像源 fallback：清华 → 阿里 → 腾讯 → 官方 ---
  PIP_PKGS="adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf"
  PIPS=(
    "-i https://pypi.tuna.tsinghua.edu.cn/simple"
    "-i https://mirrors.aliyun.com/pypi/simple/"
    "-i https://mirrors.cloud.tencent.com/pypi/simple/"
    ""
  )
  ok=0
  for idx in "${!PIPS[@]}"; do
    src="${PIPS[$idx]}"
    echo "  >> pip install 尝试 $((idx+1))/${#PIPS[@]} (${src:-官方源}) ..."
    if python3 -m pip install --break-system-packages --no-cache-dir $PIP_PKGS $src >/tmp/pip.log 2>&1; then
      ok=1
      break
    fi
    echo "    失败，尾部错误："
    tail -5 /tmp/pip.log | sed 's/^/    /'
  done
  if [ "$ok" != 1 ]; then
    echo "DEPS_FAILED: 所有镜像安装失败。完整日志: cat /tmp/pip.log"
    tail -20 /tmp/pip.log
    exit 1
  fi
  if ! deps_ok; then
    echo "DEPS_FAILED: 安装完成但导入校验失败："
    python3 -c "import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob" 2>&1 | tail -5
    exit 1
  fi
  echo "  >> 依赖安装成功"
fi

echo "== [3/4] 生成 ADB 密钥（存在则跳过）"
python3 /root/.dsh/adb-pair.py --genkey || { echo "KEYGEN_FAILED"; exit 1; }

echo "== [4/4] 安装 /root/dsh-bin/adb-shell 包装命令"
mkdir -p /root/dsh-bin
cat > /root/dsh-bin/adb-shell <<'EOF'
#!/bin/bash
# DSHA ADB 设备 shell（无线通道，免 Shizuku）
exec python3 /root/.dsh/adb-shell.py "$@"
EOF
chmod +x /root/dsh-bin/adb-shell

ls -l /root/.dsh/adbkeys/ | grep -q adbkey && echo "SETUP_DONE" || { echo "SETUP_ERR"; exit 1; }
echo "完整日志: $LOG"
