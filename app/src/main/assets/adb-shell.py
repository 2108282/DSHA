#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=4
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
import time

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
            port = 0
    if not port:
        # 自愈：connect_port 缺失/失效时，mDNS 自动发现无线调试连接端口（无需重新配对）
        port = discover_conn_port()
    if not port:
        port = 5555

    from adb_shell_wifi.adb_device import AdbDeviceTls  # Android 11+ Wi-Fi 调试必须用 TLS 传输
    from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner

    signer = PythonRSASigner(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
    priv_pem = open(KEY, 'rb').read()  # PKCS#8 PEM，作为 TLS 客户端私钥（0.5.0 库：传给 connect()）
    dev = AdbDeviceTls('127.0.0.1', port)
    dev.connect(rsa_keys=[signer], auth_timeout_s=15, tls_priv_pem=priv_pem)
    try:
        out = dev.shell(cmd)
    finally:
        dev.close()

    sys.stdout.write(out if out.endswith('\n') else out + '\n')
    print('[EXIT=0]')


def discover_conn_port(timeout_s=5):
    """mDNS 自动发现无线调试连接端口（_adb-tls-connect）。找到返回端口，失败返回 0。"""
    try:
        from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
        found = {}
        class L(ServiceListener):
            def add_service(self, zc, type_, name):
                info = zc.get_service_info(type_, name)
                if info:
                    found[info.port] = name
            def update_service(self, zc, type_, name):
                pass
            def remove_service(self, zc, type_, name):
                pass
        zc = Zeroconf()
        ServiceBrowser(zc, '_adb-tls-connect._tcp.local.', L())
        time.sleep(timeout_s)
        zc.close()
        if found:
            p = sorted(found)[0]
            # 顺手回写 connect_port，下次秒连
            try:
                with open(KEYDIR + '/connect_port', 'w') as f:
                    f.write(str(p))
            except Exception:
                pass
            return p
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    main()
