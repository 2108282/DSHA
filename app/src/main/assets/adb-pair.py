#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DSHA ADB 无线配对（绕过 Shizuku）—— 单次配对脚本。
协议：Android 11+ wireless debugging pairing（TLS1.3-PSK + SPAKE2，AOSP/BoringSSL）。
关键坑（已踩）：
  * SPAKE2 必须用 spake2-cffi（BoringSSL 兼容：32 字节消息、NUL 终止、非主子群盲化点）；
    绝不能 pip install warner 的 spake2（33 字节消息，必败并让设备弹窗报警）。
  * 配对服务监听在 loopback：容器里用 127.0.0.1:<配对端口> 连（lan ip 连不上）。
  * 配对务必单次执行：失败握手会让设备弹"配对失败"并关闭配对，禁止循环重试。
  * 成功标志 PAIR_OK；随后直连 adbd（传统 5555 或无线调试常规端口）自检。
用法：
  python3 adb-pair.py --code 123456 [--port <配对端口>] [--connect-port 5555]
  python3 adb-pair.py --genkey                        # 仅生成/确保密钥
输出（供 App/脚本解析）：
  KEY_GEN_OK / DEPS_MISSING / NO_PAIR_PORT / PAIR_OK / PAIR_FAIL / CONNECT_OK / CONNECT_WARN
"""
import argparse
import os
import sys
import time

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'
DEFAULT_CONNECT_PORT = 5555


def check_deps():
    try:
        import adb_shell_wifi  # noqa
        import spake2_cffi  # noqa
    except Exception as e:
        print('DEPS_MISSING: %s' % e)
        print('RUN: python3 -m pip install --break-system-packages '
              'adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf')
        return False
    return True


def ensure_key():
    os.makedirs(KEYDIR, exist_ok=True)
    os.chmod(KEYDIR, 0o700)
    if not (os.path.exists(KEY) and os.path.exists(KEYPUB)):
        from adb_shell_wifi.auth.keygen import keygen
        keygen(KEY)
        print('KEY_GEN_OK')
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--code', default=None, help='6 位配对码')
    ap.add_argument('--port', type=int, default=0, help='配对端口（0=用户后补/不配）')
    ap.add_argument('--connect-port', type=int, default=DEFAULT_CONNECT_PORT)
    ap.add_argument('--genkey', action='store_true')
    a = ap.parse_args()

    if not check_deps():
        sys.exit(1)
    ensure_key()
    if a.genkey:
        sys.exit(0)

    if not a.code:
        print('NO_CODE')
        sys.exit(1)

    priv_pem = open(KEY, 'rb').read()
    pub_data = open(KEYPUB, 'rb').read().strip()

    port = a.port
    if not port:
        # 尽力 mdns 发现（容器回环上多数 ROM 收不到组播；App 端已用 NsdManager 优先发现）
        port = mdns_pair_port()
    if not port:
        print('NO_PAIR_PORT: 在 App 内输入手机「无线调试」界面显示的配对端口')
        sys.exit(1)

    # 先探测端口可达，再配对（单次！）
    if not probe(port):
        print('PORT_UNREACHABLE: 127.0.0.1:%d 连不上，请确认无线调试配对弹窗已打开' % port)
        sys.exit(1)

    try:
        from adb_shell_wifi.pairing import pair
        r = pair('127.0.0.1', port, a.code, priv_pem, pub_data, timeout_s=30)
        print('PAIR_OK: %r' % r)
    except Exception as e:
        print('PAIR_FAIL: %s' % e)
        sys.exit(1)

    # 配对成功后直连自检（等 adbd 更新授权列表）
    time.sleep(1.2)
    conn = a.connect_port
    for candidate in (conn, 5555):
        if candidate in (0, conn) and conn == candidate:
            pass
        try:
            out = adb_shell(candidate, ['id', 'getprop ro.product.model'])
            print('CONNECT_OK port=%d' % candidate)
            print(out.strip())
            save_connect_port(candidate)
            sys.exit(0)
        except Exception as e:
            last = e
    print('CONNECT_WARN: 配对成功但直连失败(%s)，请在 App 填写连接端口后重试' % last)
    sys.exit(0)


def adb_shell(port, cmds):
    from adb_shell_wifi.adb_device import AdbDeviceTcp
    from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    signer = PythonRSASigner(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
    dev = AdbDeviceTcp('127.0.0.1', port)
    dev.connect(rsa_keys=[signer], auth_timeout_s=15)
    try:
        return dev.shell(' && '.join(cmds))
    finally:
        dev.close()


def save_connect_port(port):
    try:
        with open('/root/.dsh/adbkeys/connect_port', 'w') as f:
            f.write(str(port))
    except Exception:
        pass


def probe(port, timeout=3.0):
    import socket
    try:
        s = socket.create_connection(('127.0.0.1', port), timeout=timeout)
        s.close()
        return True
    except Exception:
        return False


def mdns_pair_port(timeout_s=6):
    # 尽力而为：容器回环收不到组播时返回 0，由 App NsdManager 兜底
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
        ServiceBrowser(zc, '_adb-tls-pairing._tcp.local.', L())
        time.sleep(timeout_s)
        zc.close()
        if found:
            return sorted(found)[0]
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    main()
