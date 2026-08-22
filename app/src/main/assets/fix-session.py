#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 会话损坏修复（SessionPersistenceCorruptionError）
修复 session.jsonl.zstd 里缺 message.id 的事件（补 dsha-fixed-<seq>），
顺带修 role/source/content。原文件备份为 .corrupt-<ts>。
用法: python3 fix-session.py <session.jsonl.zstd>
"""
import json
import os
import sys
import time

try:
    import zstandard as zstd
except ImportError:
    print("NO_ZSTD")
    sys.exit(0)


def main():
    if len(sys.argv) < 2:
        print("NO_ARGS")
        sys.exit(0)
    path = sys.argv[1]
    if not os.path.isfile(path):
        print("NO_FILE")
        sys.exit(0)
    try:
        raw = open(path, "rb").read()
        data = zstd.ZstdDecompressor().decompress(
            raw, max_output_size=512 * 1024 * 1024)
    except Exception:
        print("DECODE_FAIL")
        sys.exit(0)

    text = data.decode("utf-8", errors="replace")
    lines = text.split(chr(10))
    kept = []
    fixed = 0
    for line in lines:
        if not line.strip():
            continue
        try:
            ev = json.loads(line)
        except Exception:
            continue
        t = ev.get("type", "")
        d = ev.get("data")
        seq = ev.get("seq", len(kept))
        if t == "user/message":
            msg = d if isinstance(d, dict) else None
        elif t in ("assistant/message", "tool/result"):
            msg = d.get("message") if isinstance(d, dict) else None
        else:
            msg = None
        if isinstance(msg, dict):
            mid = msg.get("id")
            if not isinstance(mid, str) or mid == "":
                msg["id"] = "dsha-fixed-" + str(seq)
                fixed += 1
            if "role" not in msg and t != "tool/result":
                msg["role"] = "assistant" if t == "assistant/message" else "user"
            src = msg.get("source")
            if not isinstance(src, dict) or not isinstance(src.get("kind"), str) or src.get("kind") == "":
                msg["source"] = {"kind": "plugin", "plugin": "dsha-fixer"}
            if not isinstance(msg.get("content"), list):
                msg["content"] = []
        kept.append(json.dumps(ev, ensure_ascii=False))

    if fixed == 0:
        print("NO_FIX")
        sys.exit(0)

    bak = path + ".corrupt-" + time.strftime("%Y%m%d-%H%M%S")
    try:
        os.rename(path, bak)
    except Exception:
        print("BAK_FAIL")
        sys.exit(0)
    try:
        cctx = zstd.ZstdCompressor()
        new_data = cctx.compress((chr(10).join(kept) + chr(10)).encode("utf-8"))
        with open(path, "wb") as f:
            f.write(new_data)
    except Exception:
        # 写回失败：恢复备份
        try:
            os.rename(bak, path)
        except Exception:
            pass
        print("WRITE_FAIL")
        sys.exit(0)
    print("FIXED:" + str(fixed))


if __name__ == "__main__":
    main()
