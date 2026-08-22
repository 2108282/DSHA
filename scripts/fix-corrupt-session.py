#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DSHA 会话损坏修复工具（SessionPersistenceCorruptionError）
========================================================
错误：session event at seq N lacks an identified message
根因：某 user/message、assistant/message 或 tool/result 事件的
      message.id 缺失/为空 → dsh 校验失败。

修复策略（不删事件，只补 id——JSONL 的 seq 必须连续）：
1. 解压 session.jsonl.zstd
2. 逐行解析，找出缺 message.id 的事件
3. 补一个唯一 id（dsha-fixed-<seq>）
4. 重新压缩（原文件先备份为 .corrupt-<ts>）

用法：
  python3 fix-corrupt-session.py <session.jsonl.zstd>
需要：python3 + zstandard 模块（无则 pip install zstandard）
"""
import json
import os
import sys
import time

def main():
    if len(sys.argv) < 2:
        print("用法: python3 fix-corrupt-session.py <session.jsonl.zstd>")
        sys.exit(1)
    path = sys.argv[1]
    if not os.path.isfile(path):
        print(f"文件不存在: {path}")
        sys.exit(1)

    # 解压
    try:
        import zstandard as zstd
    except ImportError:
        print("缺少 zstandard 模块，先安装: pip install zstandard")
        sys.exit(1)

    with open(path, 'rb') as f:
        raw = f.read()
    data = zstd.ZstdDecompressor().decompress(raw, max_output_size=512 * 1024 * 1024)
    text = data.decode('utf-8', errors='replace')

    # 逐行处理
    lines = text.split('\n')
    fixed = 0
    kept = []
    for i, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            print(f"  !! 第 {i+1} 行 JSON 解析失败（无法修复，跳过该行）: {line[:80]}")
            continue

        ev_type = ev.get('type', '')
        data_obj = ev.get('data')
        seq = ev.get('seq', i)
        # user/message: data 本身是 message；assistant/message、tool/result: data.message
        if ev_type == 'user/message':
            msg = data_obj if isinstance(data_obj, dict) else None
        elif ev_type in ('assistant/message', 'tool/result'):
            msg = data_obj.get('message') if isinstance(data_obj, dict) else None
        else:
            msg = None

        changed = False
        if isinstance(msg, dict):
            mid = msg.get('id')
            if not isinstance(mid, str) or mid == '':
                msg['id'] = f'dsha-fixed-{seq}'
                changed = True
            # 顺带修 role/source/content 校验（防止连环报错）
            if 'role' not in msg and ev_type != 'tool/result':
                msg['role'] = 'assistant' if ev_type == 'assistant/message' else 'user'
                changed = True
            src = msg.get('source')
            if not isinstance(src, dict) or not isinstance(src.get('kind'), str) or src.get('kind') == '':
                msg['source'] = {'kind': 'plugin', 'plugin': 'dsha-fixer'}
                changed = True
            if not isinstance(msg.get('content'), list):
                msg['content'] = []
                changed = True

        if changed:
            fixed += 1
            print(f"  修复 seq={seq} ({ev_type}): 补 message.id=dsha-fixed-{seq}")
        kept.append(json.dumps(ev, ensure_ascii=False))

    if fixed == 0:
        print("未发现缺 message.id 的事件（可能损坏在其他字段，或文件未损坏）")
        print("可尝试: 检查是否 seq 重复（另一类损坏，需删重复段）")
        sys.exit(0)

    # 备份原文件
    bak = path + f".corrupt-{time.strftime('%Y%m%d-%H%M%S')}"
    os.rename(path, bak)
    print(f"已备份原文件: {bak}")

    # 重新压缩写回
    new_text = '\n'.join(kept) + '\n'
    cctx = zstd.ZstdCompressor()
    with open(path, 'wb') as f:
        f.write(cctx.compress(new_text.encode('utf-8')))
    print(f"修复完成: 补 {fixed} 个 id → {path}")
    print("重启 WebUI 后会话应可加载（该会话从损坏点后可能缺部分内容，但主体保留）")

if __name__ == '__main__':
    main()
