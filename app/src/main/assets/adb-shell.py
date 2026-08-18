#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DSHA 设备 shell 工具（ADB 无线通道，免 Shizuku）。
用法：
  adb-shell.py <command...>         # 在设备上以 shell(uid=2000) 身份执行
  /root/dsh-bin/adb-shell "命令"     # 包装命令（PATH 内）
连接端口优先级：--port > /root/.dsh/adbkeys/connect_port > 5555
输出：stdout + "\n[EXIT=n]"（与 3090 桥保持一致的格式）
"""
import sys
import os

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'


def main():
    args = sys.argv[1:]
    port = 0
    if args and args[0] == '--port':
        if len(args) >= 2:
            port = int(args[1])
        args = args[2:]
    if not args:
        args = ['id']
    cmd = ' '.join(args)

    if not (os.path.exists(KEY) and os.path.exists(KEYPUB)):
        print('NO_KEY: 请先在 App「工作区 → ADB 无线配对」完成配对')
        print('[EXIT=1]')
        sys.exit(1)

    if not port:
        try:
            port = int(open(KEYDIR + '/connect_port').read().strip())
        except Exception:
            port = 5555

    from adb_shell_wifi.adb_device import AdbDeviceTcp
    from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner

    signer = PythonRSASigner(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
    dev = AdbDeviceTcp('127.0.0.1', port)
    dev.connect(rsa_keys=[signer], auth_timeout_s=15)
    try:
        out = dev.shell(cmd)
    finally:
        dev.close()

    sys.stdout.write(out if out.endswith('\n') else out + '\n')
    print('[EXIT=0]')


if __name__ == '__main__':
    main()
