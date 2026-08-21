#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=8
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
    use_su = False
    if args and args[0] == '--port':
        if len(args) >= 2:
            port = int(args[1])
        args = args[2:]
    # --su：以 root 身份执行（需手机已 root；未 root 会提示）
    # 安全：必须用户已在 App「配置」页勾选「允许 root shell」才会生成
    # /root/.dsh/allow-root-shell 标记；未授权一律拒绝（防止 agent 擅自提权）
    if args and args[0] == '--su':
        if not os.path.exists('/root/.dsh/allow-root-shell'):
            print('ROOT_NOT_ALLOWED: 未授权 root shell')
            print('请在 App「配置」页勾选「允许 root shell」并保存后重试')
            print('[EXIT=1]')
            sys.exit(1)
        use_su = True
        args = args[1:]
    if not args:
        args = ['id']
    cmd = ' '.join(args)
    # su 模式：shell 下用 su -c 提升到 root
    if use_su:
        cmd = "su -c '" + cmd.replace("'", "'\\''") + "'"

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

    try:
        from adb_shell_wifi.adb_device import AdbDeviceTls  # Android 11+ Wi-Fi 调试必须用 TLS 传输
        from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    except ImportError as e:
        print('DEPS_MISSING: 缺少 adb_shell_wifi 库（%s）' % e)
        print('请重开 App 配置页的 ADB 开关，或重跑安装步骤⑥（会自动安装依赖）')
        print('[EXIT=1]')
        sys.exit(1)

    # ===== 执行前报备确认（用户要求：用 shell 必须先说明理由，用户确认后才执行）=====
    # 通过 3090 桥 /confirm 弹窗（App 前台）或通知（后台）让用户确认；
    # 命令里 # 后的注释作为「理由」展示。未确认/超时默认拒绝。
    # 只读命令（getprop/dumpsys 等以只读开头）直接放行，减少打扰。
    confirm_reason = cmd.split('#', 1)[1].strip() if '#' in cmd else ''
    is_readonly = cmd.strip().split(' ', 1)[0] in (
        'getprop', 'dumpsys', 'logcat', 'ls', 'cat', 'id', 'ps', 'df', 'free',
        'pm', 'settings', 'wm', 'input', 'getevent', 'uptime', 'date', 'echo')
    if not is_readonly:
        ok = request_confirm(cmd, confirm_reason)
        if not ok:
            print('USER_REJECTED: 用户未确认该命令（报备被拒）')
            print('[EXIT=1]')
            sys.exit(1)

    try:
        signer = PythonRSASigner(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
        priv_pem = open(KEY, 'rb').read()  # PKCS#8 PEM，作为 TLS 客户端私钥（0.5.0 库：传给 connect()）
        dev = AdbDeviceTls('127.0.0.1', port)
        dev.connect(rsa_keys=[signer], auth_timeout_s=20, tls_priv_pem=priv_pem)
        try:
            out = dev.shell(cmd)
        finally:
            dev.close()
    except Exception as e:
        print('CONNECT_FAIL: %s (%s)' % (e, type(e).__name__))
        print('请确认手机「开发者选项→无线调试」已开启，且已配对（App 工作区→ADB 无线配对）')
        print('[EXIT=1]')
        sys.exit(1)

    sys.stdout.write(out if out.endswith('\n') else out + '\n')
    print('[EXIT=0]')


def request_confirm(cmd, reason=''):
    """请求用户确认执行设备命令（3090 桥 /confirm，App 弹窗/通知）。
    返回 True=允许。失败/超时默认拒绝（安全优先）。"""
    import urllib.request
    import urllib.parse
    token = ''
    try:
        with open('/root/.dsh/.bridge_token') as f:
            token = f.read().strip()
    except Exception:
        pass
    # 命令 + 理由一起发给确认弹窗
    display = cmd if not reason else cmd + '\n\n[理由] ' + reason
    try:
        url = 'http://127.0.0.1:3090/confirm?cmd=' + urllib.parse.quote(display) + '&token=' + urllib.parse.quote(token) + '&force=1'
        with urllib.request.urlopen(url, timeout=65) as r:
            body = r.read().decode('utf-8', 'ignore')
            return '"YES"' in body
    except Exception:
        return False

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
