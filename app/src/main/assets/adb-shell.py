#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=5
# 注意：上面这行只是给人看的，Java 侧不读它。真正决定脚本是否重注入到 rootfs 的
# 是 AdbBridge.SCRIPT_VERSION（比对 /root/.dsh/script-version）——改本文件必须同时 bump 它。
"""
DSHA 设备 shell 工具（ADB 无线通道，免 Shizuku）。
用法：
  adb-shell.py <command...>         # 在设备上以 shell(uid=2000) 身份执行
  /root/dsh-bin/adb-shell "命令"     # 包装命令（PATH 内）
连接端口优先级：--port > /root/.dsh/adbkeys/connect_port > 5555
输出：stdout + "\n[EXIT=n]"（与 3090 桥保持一致的格式）

安全：非只读命令先走 App 的 3090 确认桥，拿到 {"result":"YES"} 才执行；
桥不可达 / 用户拒绝 / 任何异常都拒绝执行（fail-closed）。
DSH_INTERNAL=1 时跳过确认，供 App 自己的内部调用使用。
注意这一层是防呆而非安全边界——容器内是 root，能改本脚本、也能直接调
adb_shell_wifi 库。真正的强制点在 App 侧的 3090 桥。
"""
import sys
import os
import re
import time

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'
TOKEN_FILE = '/root/.dsh/.bridge_token'
# 桥可能只监听 IPv6 回环（Android 上 localhost 解析优先 ::1），两个都试
BRIDGE_BASES = ('http://127.0.0.1:3090', 'http://[::1]:3090')

# 只读命令白名单：用白名单而非黑名单——黑名单挡不住 base64/eval 之类的编码绕过
READONLY_PREFIXES = (
    'id', 'whoami', 'getprop', 'dumpsys', 'logcat', 'ps', 'ls', 'cat', 'df',
    'uptime', 'date', 'pwd', 'stat', 'wc', 'head', 'tail', 'grep',
    'settings get', 'pm list', 'wm size', 'wm density', 'service list',
    'cmd package list', 'am stack list', 'getevent -l',
)
# 含重定向 / 管道 / 命令替换 / 串联的一律视为写操作（cat 能读、cat > 就能写）
_SHELL_META = re.compile(r'[>;|&`]|\$\(')


def is_readonly(cmd):
    """命令是否属于纯只读查询（免确认）。判不准就返回 False，宁可多问一次。"""
    if _SHELL_META.search(cmd):
        return False
    head = cmd.strip().lower()
    return any(head == p or head.startswith(p + ' ') for p in READONLY_PREFIXES)


def request_confirm(cmd):
    """向 App 3090 桥请求用户确认。只有明确收到 YES 才返回 True。

    返回 (allowed, reason)：reason 用于把「用户拒绝」和「桥不可达」区分开，
    否则用户只看到一句 USER_REJECTED，根本没法排查。
    """
    import json
    from urllib.parse import quote
    from urllib.request import urlopen

    try:
        with open(TOKEN_FILE) as f:
            token = f.read().strip()
    except Exception:
        return False, '读不到 %s（App 未生成鉴权令牌）' % TOKEN_FILE
    if not token:
        return False, '%s 为空（App 未生成鉴权令牌）' % TOKEN_FILE

    last = '3090 确认桥不可达（127.0.0.1 与 [::1] 都连不上）'
    for base in BRIDGE_BASES:
        url = '%s/confirm?cmd=%s&token=%s' % (base, quote(cmd), quote(token))
        try:
            # 超时要大于 App 侧 60s 确认超时，否则这边先断、用户点了也白点
            with urlopen(url, timeout=70) as r:
                data = json.loads(r.read().decode('utf-8', 'replace'))
        except Exception as e:
            last = '请求 %s 失败：%s' % (base, e)
            continue
        res = data.get('result')
        if res == 'YES':
            return True, ''
        if res == 'NO':
            return False, '用户点了拒绝'
        return False, '桥返回 %r（确认未通过）' % (res,)
    return False, last


def confirm_or_exit(cmd):
    """执行前的确认关卡。不通过就直接退出（fail-closed）。"""
    if os.environ.get('DSH_INTERNAL') == '1':
        return  # App 内部调用（pm grant / 看门狗探活）
    if is_readonly(cmd):
        return
    allowed, reason = request_confirm(cmd)
    if allowed:
        return
    print('USER_REJECTED: 未获授权，命令未执行')
    print('  命令：%s' % cmd)
    print('  原因：%s' % reason)
    if '不可达' in reason or '失败' in reason or '读不到' in reason or '为空' in reason:
        print('  处理：在 App「配置」页开启 ADB 开关，让确认桥运行后重试')
    print('[EXIT=1]')
    sys.exit(1)


def main():
    args = sys.argv[1:]
    port = 0
    use_su = False
    if args and args[0] == '--port':
        if len(args) >= 2:
            port = int(args[1])
        args = args[2:]
    # --su：以 root 身份执行（需手机已 root；未 root 会提示）
    if args and args[0] == '--su':
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

    # 安全关卡：非只读命令必须先拿到用户确认（--su 提权一律走确认）。
    # 放在密钥/依赖检查之后：环境本来就没就绪时不该先打扰用户点确认。
    confirm_or_exit(cmd)

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
