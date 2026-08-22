#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 会话损坏修复（SessionPersistenceCorruptionError 专用）

对齐 dsh packages/core/session/src/index.ts assertMessageEventShape 的强校验：
- user/message:  id + role=user + source.kind + content(array)
- assistant/message: id + role=assistant + source.kind=model + provider/model + content(array)
- tool/result:   id + role=user + source.kind=tool + callId + content=单块 tool-result
                 （toolCallId 必须 === source.callId）

修复原则：只补【缺失且可安全推导】的字段：
  * 三类：id（dsha-fixed-<seq>）、role（按类型期望值）
  * user/message：source 缺失补 plugin 占位、content 补空数组
不可安全推导的结构损坏（assistant 缺 model source、tool 缺 callId/合法块等）→ 输出
NEED_ISOLATE，由 heal-session.sh 把整个会话移入 corrupt-backup，绝不伪造数据造成
二次校验失败。

用法: python3 fix-session.py <session.jsonl.zstd>
输出: FIXED:n / NO_FIX / NEED_ISOLATE / DECODE_FAIL / NO_ZSTD / NO_FILE / NO_ARGS
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


def has_model_source(src):
    if not isinstance(src, dict):
        return False
    return (isinstance(src.get("kind"), str) and src.get("kind") == "model"
            and isinstance(src.get("provider"), str) and len(src.get("provider", "")) > 0
            and isinstance(src.get("model"), str) and len(src.get("model", "")) > 0)


def tool_result_block_ok(msg, call_id):
    """tool/result 的 content 必须是单块 {type:'tool-result', content:[], toolCallId==callId}。"""
    c = msg.get("content")
    if not isinstance(c, list) or len(c) != 1:
        return False
    b = c[0]
    return (isinstance(b, dict)
            and b.get("type") == "tool-result"
            and isinstance(b.get("content"), list)
            and b.get("toolCallId") == call_id)


def handle_event(ev, kept_len):
    """返回 (ok, fixed)：ok=False 表示该事件无法安全修复，应隔离整个会话。"""
    t = ev.get("type", "")
    if t not in ("user/message", "assistant/message", "tool/result"):
        return True, 0
    d = ev.get("data")
    if t == "user/message":
        msg = d if isinstance(d, dict) else None
    else:
        msg = d.get("message") if isinstance(d, dict) else None
    if not isinstance(msg, dict):
        return False, 0  # 结构层面就丢了，没法修

    seq = ev.get("seq", kept_len)
    fixed = 0

    # ---------- 结构性检查（不可修的先判定，避免顺手写脏） ----------
    src = msg.get("source")
    if t == "assistant/message":
        # 只允许【缺失 source】时补 plugin 占位？不行——assistant 必须 model source，
        # 补了 plugin 依然校验失败。因此必须已有合法 model source 才可继续。
        if not has_model_source(src):
            return False, fixed
    elif t == "tool/result":
        if (not isinstance(src, dict) or src.get("kind") != "tool"
                or not isinstance(src.get("callId"), str) or not src.get("callId")):
            return False, fixed
        if not tool_result_block_ok(msg, src.get("callId")):
            return False, fixed
    else:  # user/message：source 缺失/损坏可安全补 plugin 占位
        if not (isinstance(src, dict) and isinstance(src.get("kind"), str) and src.get("kind")):
            msg["source"] = {"kind": "plugin", "plugin": "dsha-fixer"}

    # ---------- 可安全修补 ----------
    mid = msg.get("id")
    if not isinstance(mid, str) or mid == "":
        msg["id"] = "dsha-fixed-" + str(seq)
        fixed += 1
    expected_role = "assistant" if t == "assistant/message" else "user"
    if msg.get("role") != expected_role:
        # role 缺失或值不符（如 'tool'/'system'）→ 统一修正为 dsh 期望值
        msg["role"] = expected_role
        fixed += 1
    if t == "user/message" and not isinstance(msg.get("content"), list):
        msg["content"] = []
        fixed += 1
    return True, fixed


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
        # zstd 魔数 28 B5 2F FD；非 zstd 按明文 JSONL 处理（compression=none 场景）
        is_zstd = raw[:4] == b"\x28\xb5\x2f\xfd"
        if is_zstd:
            data = zstd.ZstdDecompressor().decompress(
                raw, max_output_size=512 * 1024 * 1024)
        else:
            data = raw
    except Exception:
        print("DECODE_FAIL")
        sys.exit(0)

    text = data.decode("utf-8", errors="replace")
    lines = text.split(chr(10))
    kept = []
    fixed = 0
    need_isolate = False
    for line in lines:
        if not line.strip():
            continue
        try:
            ev = json.loads(line)
        except Exception:
            # 坏行：无法解析。为了保序我们跳过该行；会话是否可救由 dsh 决定，
            # 但如果后面还有有效消息，保留它们更有价值（坏行本身无法修复）。
            continue
        ok, n = handle_event(ev, len(kept))
        if not ok:
            need_isolate = True
            break
        fixed += n
        kept.append(json.dumps(ev, ensure_ascii=False))

    if need_isolate:
        # 存在无法安全修复的结构损坏：不写回（避免越修越坏），交给隔离
        print("NEED_ISOLATE")
        sys.exit(0)
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
        cctx = zstd.ZstdCompressor() if is_zstd else None
        body = (chr(10).join(kept) + chr(10)).encode("utf-8")
        new_data = cctx.compress(body) if is_zstd else body
        with open(path, "wb") as f:
            f.write(new_data)
    except Exception:
        try:
            os.rename(bak, path)
        except Exception:
            pass
        print("WRITE_FAIL")
        sys.exit(0)
    print("FIXED:" + str(fixed))


if __name__ == "__main__":
    main()