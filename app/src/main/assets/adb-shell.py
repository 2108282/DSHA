#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=13
"""
DSHA 设备 shell 工具（Shizuku 优先 + ADB 无线调试双通道自动自愈 + 10分钟授权租约互通）。
用法：
  adb-shell.py <command...>         # 在设备上以 shell(uid=2000/root) 身份执行
  /root/dsh-bin/adb-shell "命令"     # 包装命令（PATH 内）
选项：
  --su                              # 以 root 身份执行（需手机支持且在配置页授权）
  --port <port>                     # 指定 ADB 无线调试连接端口（ADB 兜底备用）
策略：
  默认优先走 Shizuku 宿主桥（3090 /exec）；若 Shizuku 未就绪/未授权，自动无缝降级到 ADB 无线通道。
输出：
  stdout + "\n[EXIT=n]"（与 3090 桥保持一致的格式）
"""
import sys
import os
import time
import re
import urllib.request
import urllib.parse
import urllib.error
import json

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'

# 免确认的只读命令（见 is_readonly_cmd）。只放「无论参数怎么给都不改设备状态」的命令。
READONLY_CMDS = frozenset((
    'getprop', 'dumpsys', 'logcat', 'id', 'ps', 'df', 'free', 'uptime', 'date',
    'whoami', 'getevent', 'ls', 'stat', 'wc', 'head', 'tail', 'grep', 'cat',
    'md5sum', 'sha1sum', 'printenv', 'env', 'pwd', 'which', 'true', 'echo'))
# 命令名本身可写，只有这些子命令算只读（pm uninstall/settings put/input tap 都要确认）
READONLY_SUB = {
    'pm': frozenset(('list', 'path', 'dump')),
    'settings': frozenset(('get', 'list')),
    'cmd': frozenset(),
    'am': frozenset(('start', 'stack', 'get-config', 'to-uri', 'to-intent-uri')),
    'wm': frozenset(('size', 'density', 'displays')),
    'input': frozenset(),
    'svc': frozenset(),
}


def is_auth_lease_active():
    """检查 10 分钟临时操作授权租约是否有效（单一事实来源：/root/.dsh/.auth_lease）。"""
    lease_file = '/root/.dsh/.auth_lease'
    try:
        if os.path.exists(lease_file):
            with open(lease_file, 'r') as f:
                content = f.read().strip()
                if content.isdigit():
                    exp = int(content)
                    if exp > time.time():
                        return True
            try:
                os.remove(lease_file)
            except Exception:
                pass
    except Exception:
        pass
    return False


def run_via_shizuku_bridge(cmd):
    """通过 3090 宿主桥的 /exec 接口执行命令（Shizuku 通道）。
    服务端 DangerShellGuard 会自动拦截危险命令并弹窗确认。
    成功返回 (stdout, exit_code)，失败或不可用返回 (None, error_msg)。
    """
    token = ''
    try:
        with open('/root/.dsh/.bridge_token') as f:
            token = f.read().strip()
    except Exception:
        pass

    q = ('/exec?cmd=' + urllib.parse.quote(cmd)
         + '&token=' + urllib.parse.quote(token))

    last_err = ''
    for host in ('127.0.0.1', '[::1]'):
        try:
            url = 'http://' + host + ':3090' + q
            with urllib.request.urlopen(url, timeout=180) as r:
                body = r.read().decode('utf-8', 'replace')
            try:
                data = json.loads(body)
                raw = data.get('result', '')
            except Exception:
                raw = body

            # 检查 Shizuku 桥状态
            if raw == '[NO_SHIZUKU_PERMISSION]':
                return (None, 'SHIZUKU_NO_PERMISSION: 未授予 Shizuku 权限，请在 Shizuku 中授权 DSHA')
            if raw == '[SHIZUKU_SERVICE_NOT_READY]':
                return (None, 'SHIZUKU_NOT_READY: Shizuku 服务未就绪，请确认 Shizuku 是否正在运行')
            if raw == '[UNAUTHORIZED]':
                return (None, 'BRIDGE_UNAUTHORIZED: 3090 桥 Token 鉴权失败')
            if raw == '[USER_REJECTED]':
                return (None, 'USER_REJECTED: 用户拒绝了命令执行')

            # 提取末尾的 [EXIT=n] 标记
            m = re.search(r'\n?\[EXIT=(-?\d+)\]\s*$', raw)
            if m:
                code = int(m.group(1))
                out = raw[:m.start()]
                return (out, code)
            else:
                return (raw, 0)
        except urllib.error.URLError as e:
            last_err = str(e)
            continue
        except TimeoutError:
            return (None, 'BRIDGE_TIMEOUT: 执行超时')
        except Exception as e:
            return (None, 'BRIDGE_ERROR: %s' % e)
    return (None, 'BRIDGE_UNREACHABLE: 无法连接到 3090 宿主桥 (%s)' % last_err)


def main():
    args = sys.argv[1:]
    port = 0
    use_su = False

    clean_args = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--port' and i + 1 < len(args):
            try:
                port = int(args[i + 1])
            except Exception:
                pass
            i += 2
        elif a == '--su':
            use_su = True
            i += 1
        else:
            clean_args.append(a)
            i += 1

    # --su：以 root 身份执行（需手机已 root；未 root 会提示）
    if use_su:
        if not os.path.exists('/root/.dsh/allow-root-shell'):
            print('ROOT_NOT_ALLOWED: 未授权 root shell')
            print('请在 App「配置」页勾选「允许 root shell」并保存后重试')
            print('[EXIT=1]')
            sys.exit(1)

    if not clean_args:
        clean_args = ['id']
    cmd = ' '.join(clean_args)
    if use_su:
        cmd = "su -c '" + cmd.replace("'", "'\\''") + "'"

    # ===== 通道 1：Shizuku 宿主桥（第一默认优先通道，免开无线调试） =====
    out, code = run_via_shizuku_bridge(cmd)
    if out is not None:
        if isinstance(out, (bytes, bytearray)):
            out = out.decode('utf-8', 'replace')
        sys.stdout.write(out if out.endswith('\n') else out + '\n')
        print('[EXIT=%d]' % code)
        sys.exit(code)

    shizuku_err = code

    # ===== 通道 2：ADB 无线通道（第二降级兜底通道） =====
    has_adb_key = os.path.exists(KEY) and os.path.exists(KEYPUB)
    if not has_adb_key:
        print('NO_DEVICE_CHANNEL: 设备 Shell 双通道均未就绪')
        if shizuku_err:
            print('  - Shizuku 通道（优先通道）：%s' % shizuku_err)
        print('  - ADB 无线通道（备用通道）：未配对（请在 App「工作区 → ADB 无线配对」完成配对）')
        print('处理方式：请在「Shizuku」中为 DSHA 授予权限，或在 App 中配对 ADB 无线调试（二选一即可）。')
        print('[EXIT=1]')
        sys.exit(1)

    if not port:
        try:
            port = int(open(KEYDIR + '/connect_port').read().strip())
        except Exception:
            port = 0

    try:
        from adb_shell_wifi.adb_device import AdbDeviceTls
        from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    except ImportError as e:
        print('NO_DEVICE_CHANNEL: 设备 Shell 双通道均未就绪')
        if shizuku_err:
            print('  - Shizuku 通道（优先通道）：%s' % shizuku_err)
        print('  - ADB 无线通道（备用通道）：缺少 adb_shell_wifi 库（%s）' % e)
        print('处理方式：请在「Shizuku」中为 DSHA 授予权限，或重开 App 配置页的 ADB 开关以安装依赖。')
        print('[EXIT=1]')
        sys.exit(1)

    # ADB 执行前报备确认（有 10 分钟临时租约或只读命令直接放行）
    confirm_reason = cmd.split('#', 1)[1].strip() if '#' in cmd else ''
    if os.environ.get('DSH_INTERNAL') != '1' and not is_auth_lease_active() and not is_readonly_cmd(cmd):
        ok = request_confirm(cmd, confirm_reason)
        if not ok:
            print('USER_REJECTED: 未获授权，命令未执行')
            print('  命令：%s' % cmd)
            print('  可能原因：用户点了拒绝 / 60 秒内未确认 / 3090 确认桥不可达')
            print('  处理：在 App「配置」页启用 ADB 设备通道，确认桥运行后重试')
            print('[EXIT=1]')
            sys.exit(1)

    try:
        out = connect_with_retry(AdbDeviceTls, PythonRSASigner, cmd, port)
        if isinstance(out, (bytes, bytearray)):
            out = out.decode('utf-8', 'replace')
        out = out if isinstance(out, str) else str(out)
        sys.stdout.write(out if out.endswith('\n') else out + '\n')
        print('[EXIT=0]')
        sys.exit(0)
    except ConnectFail as e:
        print('NO_DEVICE_CHANNEL: 设备 Shell 双通道均未就绪')
        if shizuku_err:
            print('  - Shizuku 通道（优先通道）：%s' % shizuku_err)
        print('  - ADB 无线通道（备用通道）：CONNECT_FAIL %s' % e)
        print('处理方式：请在「Shizuku」中为 DSHA 授予权限，或确认手机「开发者选项→无线调试」已开启。')
        print('[EXIT=1]')
        sys.exit(1)


class ConnectFail(Exception):
    """所有候选端口都连不上（携带尝试记录，便于用户排查）"""


def run_on_port(device_cls, signer_cls, cmd, port):
    """在指定端口上连接并执行命令，返回输出。任何失败抛异常。"""
    signer = signer_cls(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
    priv_pem = open(KEY, 'rb').read()
    dev = device_cls('127.0.0.1', port)
    dev.connect(rsa_keys=[signer], auth_timeout_s=20, tls_priv_pem=priv_pem)
    try:
        try:
            return dev.shell(cmd, read_timeout_s=30, timeout_s=180)
        except TypeError:
            return dev.shell(cmd)
    finally:
        try:
            dev.close()
        except Exception:
            pass


def load_port_history():
    """最近成功过的连接端口（新→旧）。"""
    try:
        with open(KEYDIR + '/connect_port_history') as f:
            out = []
            for tok in f.read().split():
                if tok.strip().isdigit():
                    v = int(tok)
                    if 1 <= v <= 65535 and v not in out:
                        out.append(v)
            return out[:5]
    except Exception:
        return []


def remember_port(port):
    """记住成功过的端口。"""
    try:
        if not port or not (1 <= int(port) <= 65535):
            return
        hist = [p for p in load_port_history() if p != int(port)]
        hist.insert(0, int(port))
        with open(KEYDIR + '/connect_port_history', 'w') as f:
            f.write('\n'.join(str(p) for p in hist[:5]) + '\n')
    except Exception:
        pass


def connect_with_retry(device_cls, signer_cls, cmd, port):
    """连接执行 + 端口自愈。"""
    tried = []
    last = None
    for p in [port] if port else []:
        tried.append(p)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, p)
            remember_port(p)
            return out
        except Exception as e:
            last = e
    fresh = discover_conn_port()
    if fresh and fresh not in tried:
        tried.append(fresh)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, fresh)
            remember_port(fresh)
            return out
        except Exception as e:
            last = e
    for p in load_port_history():
        if p in tried:
            continue
        tried.append(p)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, p)
            remember_port(p)
            try:
                with open(KEYDIR + '/connect_port', 'w') as f:
                    f.write(str(p))
            except Exception:
                pass
            return out
        except Exception as e:
            last = e
    if 5555 not in tried:
        tried.append(5555)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, 5555)
            remember_port(5555)
            return out
        except Exception as e:
            last = e
    raise ConnectFail('%s (%s) 已尝试端口=%s' % (last, type(last).__name__, tried))


def is_readonly_cmd(cmd):
    """判定命令是否「确定只读」（免确认）。"""
    s = cmd.strip()
    if not s:
        return False
    for m in ('>', '<', '|', ';', '&', '$(', '`', '\n', '\r'):
        if m in s:
            return False
    parts = s.split()
    name = parts[0].rsplit('/', 1)[-1]
    if name == 'find':
        return not any(a.startswith('-delete') or a.startswith('-exec')
                       or a.startswith('-fprint') or a.startswith('-fls') for a in parts[1:])
    if name in READONLY_SUB:
        return len(parts) > 1 and parts[1] in READONLY_SUB[name]
    return name in READONLY_CMDS


def request_confirm(cmd, reason=''):
    """请求用户确认执行设备命令（3090 桥 /confirm，App 弹窗/通知）。"""
    token = ''
    try:
        with open('/root/.dsh/.bridge_token') as f:
            token = f.read().strip()
    except Exception:
        pass
    display = cmd if not reason else cmd + '\n\n[理由] ' + reason
    q = ('/confirm?cmd=' + urllib.parse.quote(display)
         + '&token=' + urllib.parse.quote(token))
    for host in ('127.0.0.1', '[::1]'):
        try:
            with urllib.request.urlopen('http://' + host + ':3090' + q, timeout=65) as r:
                body = r.read().decode('utf-8', 'ignore')
            return '"YES"' in body or '":YES' in body
        except TimeoutError:
            return False
        except urllib.error.URLError:
            continue
        except Exception:
            return False
    return False


def discover_conn_port(timeout_s=5):
    """mDNS 自动发现无线调试连接端口（_adb-tls-connect）。"""
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
