#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 会话自愈主程序（Python 实现，避开 proot/find/link2symlink 的 bash 坑）。

职责：
1. os.walk 遍历 /root/.dsh/sessions 下所有 session.jsonl.zstd（或 .jsonl 明文），
   不依赖 find/bash glob，符号链接目录也能穿透（os.walk followlinks=False 已够）。
2. 对每个文件调用“修复判定”：
   - zstd 缺失 → 记日志并跳过（由 heal-session.sh 负责装）
   - 缺 id/role/source/content 可安全补 → 补并写回（原文件备份 .corrupt-<ts>）
   - 结构无法安全修复（assistant 缺 model source / tool 缺 callId 或结果块）→ 隔离到 corrupt-backup
3. 全程写 /root/.dsh/heal.log（无条件创建），供排查。
"""
import datetime
import json
import os
import shutil
import sys
import time

SESSIONS_ROOT = "/root/.dsh/sessions"
CORRUPT_ROOT = "/root/.dsh/corrupt-backup"
LOG_PATH = "/root/.dsh/heal.log"

try:
    import zstandard as zstd
    HAVE_ZSTD = True
except ImportError:
    HAVE_ZSTD = False

ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def zstd_load(raw, max_size=512 * 1024 * 1024):
    """流式解压（兼容 dsh 的流式 zstd：帧头无 content size、尾部 torn）。

    dsh 用 zstd compressobj/stream 边写边压缩，帧头通常不声明 content size，
    单帧 decompress() 会报 'could not determine content size'。这里改用
    decompressobj 流式累积：能解多少解多少，坏尾巴原样保留（dsh 的 torn-tail
    机制同理）。
    """
    if raw[:4] != ZSTD_MAGIC:
        return raw  # 明文 JSONL
    try:
        return zstd.ZstdDecompressor().decompress(raw, max_output_size=max_size)
    except Exception:
        pass
    out = b""
    try:
        dobj = zstd.ZstdDecompressor().decompressobj()
        out = dobj.decompress(raw[:max_size + 4096])
    except Exception:
        pass
    return out


def log(msg):
    try:
        with open(LOG_PATH, "a") as f:
            f.write("[%s] %s\n" % (datetime.datetime.now().strftime("%F %T"), msg))
    except Exception:
        pass
    print(msg)


def has_model_source(src):
    return (isinstance(src, dict)
            and isinstance(src.get("kind"), str) and src.get("kind") == "model"
            and isinstance(src.get("provider"), str) and len(src.get("provider", "")) > 0
            and isinstance(src.get("model"), str) and len(src.get("model", "")) > 0)


def tool_result_block_ok(msg, call_id):
    c = msg.get("content")
    if not isinstance(c, list) or len(c) != 1:
        return False
    b = c[0]
    return (isinstance(b, dict)
            and b.get("type") == "tool-result"
            and isinstance(b.get("content"), list)
            and b.get("toolCallId") == call_id)


def handle_event(ev, kept_len):
    t = ev.get("type", "")
    if t not in ("user/message", "assistant/message", "tool/result"):
        return True, 0
    d = ev.get("data")
    msg = d if t == "user/message" else (d.get("message") if isinstance(d, dict) else None)
    if not isinstance(msg, dict):
        return False, 0
    seq = ev.get("seq", kept_len)
    fixed = 0
    src = msg.get("source")
    if t == "assistant/message":
        if not has_model_source(src):
            return False, fixed
    elif t == "tool/result":
        if (not isinstance(src, dict) or src.get("kind") != "tool"
                or not isinstance(src.get("callId"), str) or not src.get("callId")):
            return False, fixed
        if not tool_result_block_ok(msg, src.get("callId")):
            return False, fixed
    else:
        if not (isinstance(src, dict) and isinstance(src.get("kind"), str) and src.get("kind")):
            msg["source"] = {"kind": "plugin", "plugin": "dsha-fixer"}
    mid = msg.get("id")
    if not isinstance(mid, str) or mid == "":
        msg["id"] = "dsha-fixed-" + str(seq)
        fixed += 1
    expected_role = "assistant" if t == "assistant/message" else "user"
    if msg.get("role") != expected_role:
        msg["role"] = expected_role
        fixed += 1
    if t == "user/message" and not isinstance(msg.get("content"), list):
        msg["content"] = []
        fixed += 1
    return True, fixed


def fix_file(path):
    """返回 (action, detail)：action ∈ fixed/isolate/no_fix/decode_fail/no_zstd"""
    sz = 0
    try:
        sz = os.path.getsize(path)
    except Exception:
        sz = 0
    if 0 < sz < 50:
        return "isolate", "极小文件 <%dB" % sz
    if not HAVE_ZSTD:
        return "no_zstd", "zstandard 未安装"
    try:
        raw = open(path, "rb").read()
        is_zstd = raw[:4] == ZSTD_MAGIC
        data = zstd_load(raw)
        if is_zstd and data == b"":
            return "decode_fail", "zstd 流式解码无内容"
    except Exception:
        return "decode_fail", "zstd 解码失败"
    try:
        text = data.decode("utf-8", errors="replace")
    except Exception:
        return "decode_fail", "文本解码失败"
    kept, fixed, need_isolate = [], 0, False
    for line in text.split("\n"):
        s = line.strip()
        if not s:
            continue
        try:
            ev = json.loads(s)
        except Exception:
            continue
        ok, n = handle_event(ev, len(kept))
        if not ok:
            need_isolate = True
            break
        fixed += n
        kept.append(json.dumps(ev, ensure_ascii=False))
    if need_isolate:
        return "isolate", "存在无法安全修复的事件"
    if fixed == 0:
        return "no_fix", "未发现缺 id 等可修问题"
    bak = path + ".corrupt-" + time.strftime("%Y%m%d-%H%M%S")
    try:
        os.rename(path, bak)
    except Exception:
        return "bake_fail", "备份失败"
    try:
        body = ("\n".join(kept) + "\n").encode("utf-8")
        new_data = zstd.ZstdCompressor().compress(body) if is_zstd else body
        with open(path, "wb") as f:
            f.write(new_data)
    except Exception:
        try:
            os.rename(bak, path)
        except Exception:
            pass
        return "write_fail", "写回失败（已还原）"
    return "fixed", "已补 %d 处（备份 %s）" % (fixed, os.path.basename(bak))


def isolate_file(path, reason):
    try:
        rel = os.path.relpath(path, SESSIONS_ROOT)
        dst = os.path.join(CORRUPT_ROOT, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(path, dst)
        log("已隔离 (%s): %s" % (reason, rel))
        return True
    except Exception as e:
        log("隔离失败(%s): %s err=%s" % (reason, path, e))
        return False


def main():
    try:
        os.makedirs(LOG_PATH.rpartition("/")[0], exist_ok=True)
    except Exception:
        pass
    log("== 会话自愈开始 zstd=%s paths=%s ==" % (HAVE_ZSTD, SESSIONS_ROOT))
    scanned = fixed = isolated = 0
    if os.path.isdir(SESSIONS_ROOT):
        for root, dirs, files in os.walk(SESSIONS_ROOT):
            # 跳过 corrupt-backup 本身
            if CORRUPT_ROOT.startswith(root):
                dirs[:] = []
                continue
            for fn in files:
                if fn.startswith("session.jsonl"):
                    p = os.path.join(root, fn)
                    scanned += 1
                    try:
                        action, detail = fix_file(p)
                    except Exception as e:
                        action, detail = "error", repr(e)
                    if action == "fixed":
                        fixed += 1
                        log("已修复会话(%s): %s" % (detail, p))
                    elif action == "isolate":
                        if isolate_file(p, detail):
                            isolated += 1
                    elif action in ("no_fix",):
                        log("无需修复，保留: %s" % p)
                    elif action == "no_zstd":
                        log("跳过(缺 zstandard): %s" % p)
                    else:
                        log("异常(%s): %s %s" % (action, detail, p))
    log("== 会话自愈结束 scanned=%d fixed=%d isolated=%d ==" % (scanned, fixed, isolated))
    if fixed > 0 or isolated > 0:
        print("SESSION_HEALED (scanned=%d fixed=%d isolated=%d)" % (scanned, fixed, isolated))
    elif scanned == 0:
        print("SESSION_OK")
    else:
        print("SESSION_HEALED_NONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())