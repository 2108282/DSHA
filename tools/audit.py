#!/usr/bin/env python3
"""一次性代码审计扫描：把可疑模式连上下文打出来，人工复核后再改。

不追求零误报 —— 目的是别漏。每条规则都写清「为什么可疑」，
复核时能立刻判断是真问题还是本来就该这样。
"""
import re
import sys
import pathlib

SRC = pathlib.Path('app/src/main/java/com/deepseekharness/app')
ASSETS = pathlib.Path('app/src/main/assets')

findings = []


def add(kind, path, line, text, why):
    findings.append((kind, str(path), line, text.strip()[:120], why))


def scan_java(p):
    lines = p.read_text(encoding='utf-8', errors='replace').split('\n')
    src = '\n'.join(lines)

    # 1) 字符串用 == 比较（Java 里比的是引用，偶尔碰巧相等更难查）
    for i, ln in enumerate(lines, 1):
        if re.search(r'(String\s+\w+\s*[=!]=\s*"|"\s*[=!]=\s*\w+)', ln) and '//' not in ln.split('==')[0]:
            add('字符串 == 比较', p.name, i, ln, '应当用 equals()，== 比的是引用')

    # 2) edit() 之后没有 apply/commit（写了不落盘，重启就丢）
    for m in re.finditer(r'\.edit\(\)((?:(?!apply\(\)|commit\(\)).){0,200})', src, re.S):
        seg = m.group(0)
        if 'apply()' not in seg and 'commit()' not in seg:
            ln = src[:m.start()].count('\n') + 1
            add('prefs 未提交', p.name, ln, seg.split('\n')[0], 'edit() 后缺 apply/commit，改动不会落盘')

    # 3) HttpURLConnection 打开后没 disconnect（连接池占用 + 半开连接）
    if 'openConnection()' in src and 'disconnect()' not in src:
        ln = src[:src.index('openConnection()')].count('\n') + 1
        add('连接未关闭', p.name, ln, 'openConnection() 无 disconnect()', '连接不释放，重复调用会耗尽连接池')

    # 4) new Thread 未 setDaemon（非 daemon 线程会拖住进程退出）
    for m in re.finditer(r'new Thread\((?:[^;]{0,400}?)\)\s*(?:\.start\(\)|;)', src, re.S):
        seg = m.group(0)
        ln = src[:m.start()].count('\n') + 1
        tail = src[m.end():m.end() + 200]
        if 'setDaemon' not in seg and 'setDaemon' not in tail:
            add('线程非 daemon', p.name, ln, seg.split('\n')[0], '非 daemon 线程会阻止进程正常退出')

    # 5) catch 块完全空（连日志都没有）—— 出问题时无迹可查
    for m in re.finditer(r'catch\s*\([^)]*\)\s*\{\s*\}', src):
        ln = src[:m.start()].count('\n') + 1
        add('空 catch', p.name, ln, m.group(0).replace('\n', ' '), '异常被彻底吞掉，故障无法定位')

    # 6) Thread.sleep 硬编码大数值（卡 UI 或拖慢流程）
    for i, ln in enumerate(lines, 1):
        m = re.search(r'Thread\.sleep\((\d{4,})\)', ln)
        if m and int(m.group(1)) >= 3000:
            add('长 sleep', p.name, i, ln, '硬编码等待 %sms，应当轮询条件而不是死等' % m.group(1))

    # 7) 可能的整数除法丢精度（进度计算常见）
    for i, ln in enumerate(lines, 1):
        if re.search(r'\(int\)\s*\(?\s*\w+\s*\*\s*100\s*/', ln) or re.search(r'\d+\s*\*\s*\w+\s*/\s*\w+\s*\)', ln):
            if 'progress' in ln.lower() or 'percent' in ln.lower() or 'pct' in ln.lower():
                add('整数除法', p.name, i, ln, '先乘后除还是先除后乘，影响进度精度')


def scan_py(p):
    lines = p.read_text(encoding='utf-8', errors='replace').split('\n')
    src = '\n'.join(lines)

    # bare except（连 KeyboardInterrupt/SystemExit 一起吞）
    for i, ln in enumerate(lines, 1):
        if re.match(r'\s*except\s*:', ln):
            add('裸 except', p.name, i, ln, '会吞掉 KeyboardInterrupt 等，应指明异常类型')

    # 用 os.path.islink 判断链接（proot 下 lstat 被劫持，恒返回 False）
    for i, ln in enumerate(lines, 1):
        if 'os.path.islink' in ln:
            add('islink 不可信', p.name, i, ln, 'proot 下 lstat 被劫持，应改用 os.readlink()')

    # 写文件没有先写临时再改名（中途断电/被杀会留半个文件）
    for m in re.finditer(r"open\((['\"][^'\"]+['\"]|[\w.\[\]]+),\s*['\"]w", src):
        ln = src[:m.start()].count('\n') + 1
        seg = src[max(0, m.start() - 300):m.start()]
        if '.tmp' not in seg and 'NamedTemporary' not in seg:
            add('非原子写', p.name, ln, m.group(0), '直接覆盖写，中断会留下损坏文件')


def main():
    for p in sorted(SRC.glob('*.java')):
        scan_java(p)
    for p in sorted(ASSETS.glob('*.py')):
        scan_py(p)

    by_kind = {}
    for f in findings:
        by_kind.setdefault(f[0], []).append(f)

    print('=== 汇总（%d 项）===' % len(findings))
    for k, v in sorted(by_kind.items(), key=lambda x: -len(x[1])):
        print('  %-14s %d' % (k, len(v)))
    print()
    want = sys.argv[1] if len(sys.argv) > 1 else None
    for k, v in sorted(by_kind.items(), key=lambda x: -len(x[1])):
        if want and want != k:
            continue
        print('--- %s（%s）---' % (k, v[0][4]))
        for kind, path, line, text, why in v[:40]:
            print('  %s:%s  %s' % (path, line, text))
        print()


main()
