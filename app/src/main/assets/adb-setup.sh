#!/bin/bash
# DSHA ADB 无线配对环境安装（幂等；rootfs 内执行）
# 步骤：依赖(adb_shell_wifi/spake2-cffi) → 密钥 → 包装命令 /root/dsh-bin/adb-shell
set -u
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
export DEBIAN_FRONTEND=noninteractive

echo "== [1/4] 校验 python3"
python3 --version || { echo "NO_PYTHON: 请先在安装页装好基础工具"; exit 1; }

echo "== [2/4] 安装 Python 依赖 (adb_shell_wifi / spake2-cffi ...)"
python3 -c "import adb_shell_wifi, spake2_cffi" 2>/dev/null && echo "deps 已就绪" || {
  python3 -m pip --version >/dev/null 2>&1 || python3 -m ensurepip >/dev/null 2>&1 || true
  python3 -m pip install --break-system-packages \
      adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf \
      -i https://pypi.tuna.tsinghua.edu.cn/simple 2>&1 | tail -3 \
    || python3 -m pip install --system \
      adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf 2>&1 | tail -3
}
python3 -c "import adb_shell_wifi, spake2_cffi" 2>/dev/null || {
  echo "DEPS_FAILED: 依赖安装失败，请检查网络/镜像"; exit 1
}

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
