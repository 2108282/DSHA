#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 一键自检（只读，绝不改环境）。

把过去要人工一条条粘的终端命令固化成一次运行：环境、3090 桥、ADB 通道、
设备引导插件、write 补丁、会话健康、内置插件、备份、守卫、版本标记。
每项给 PASS / FAIL / SKIP，FAIL 带下一步怎么办。

用法（期望版本由 App 传入，脚本自己不硬编码）：
  python3 selftest.py --script-ver 9 --guard-ver 10 --step6 4 --assets 7 --guide-ver 0.1.6
"""
import json
import os
import subprocess
import sys
import time

DSH_HOME = "/root/.dsh"
SESSIONS = DSH_HOME + "/sessions"
GUIDE_DIR = "/root/dsha-device-shell-guide"
FS_LOCAL_CANDIDATES = (
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
    "/usr/local/lib/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
)
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"

rows = []          # (状态, 标题, 说明)
counts = {"PASS": 0, "FAIL": 0, "SKIP": 0}


def add(state, title, detail=""):
    rows.append((state, title, detail))
    counts[state] = counts.get(state, 0) + 1


def arg(name, default=""):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return default


def sh(cmd, timeout=20):
    """跑一条命令拿输出（只用于查询类命令）"""
    try:
        p = subprocess.run(["bash", "-lc", cmd], capture_output=True, timeout=timeout)
        return (p.stdout or b"").decode("utf-8", "replace").strip()
    except Exception as e:
        return "ERR:%s" % e


def read(path, limit=4096):
    try:
        with open(path, "rb") as f:
            return f.read(limit).decode("utf-8", "replace")
    except Exception:
        return ""


# ===================== 1. 运行环境 =====================
def check_env():
    node = sh("command -v node >/dev/null && node -v || echo NONE")
    dsh_ver = sh("command -v dsh >/dev/null && dsh --version 2>/dev/null | head -1 || echo NONE")
    if node.startswith("v"):
        add("PASS", "运行环境", "node %s，dsh %s" % (node, dsh_ver if dsh_ver != "NONE" else "未装（可能走源码模式）"))
    else:
        add("FAIL", "运行环境", "rootfs 里没有 node —— 到分步安装页跑③安装 Node")


# ===================== 1.5 基础命令齐备 =====================
def check_tools():
    """缺命令是最难猜的一类故障：脚本挂在半路，报错却指向别处
    （比如 rootfs 没装 unzip，ADB 配对就卡在「pip 仍不可用」）。"""
    need = ["python3", "tar", "curl", "git", "xz"]
    opt = ["unzip", "zstd", "wget"]
    out = sh("for c in %s; do command -v $c >/dev/null && echo \"$c Y\" || echo \"$c N\"; done"
             % " ".join(need + opt))
    have = {}
    for line in out.split("\n"):
        parts = line.split()
        if len(parts) == 2:
            have[parts[0]] = parts[1] == "Y"
    missing = [c for c in need if not have.get(c, False)]
    miss_opt = [c for c in opt if not have.get(c, False)]
    if missing:
        add("FAIL", "基础命令", "缺 %s —— 到「安装」页重跑②基础工具" % "、".join(missing))
    else:
        note = ("；可选缺失 %s（脚本内有兜底，不影响使用）" % "、".join(miss_opt)) if miss_opt else ""
        add("PASS", "基础命令", "%d 项必需命令齐备%s" % (len(need), note))


# ===================== 2. 3090 桥 =====================
def check_bridge():
    token = read(DSH_HOME + "/.bridge_token").strip()
    if not token:
        add("FAIL", "3090 桥 token", "缺 %s/.bridge_token —— 打开 App 后台会自动生成，重开一次 App" % DSH_HOME)
        return
    import urllib.request
    import urllib.error
    import urllib.parse
    ok_hosts, bodies = [], []
    for host in ("127.0.0.1", "[::1]"):
        url = ("http://%s:3090/exec?cmd=%s&token=%s"
               % (host, urllib.parse.quote("echo dsha-selftest"), urllib.parse.quote(token)))
        try:
            with urllib.request.urlopen(url, timeout=8) as r:
                bodies.append(r.read().decode("utf-8", "replace"))
            ok_hosts.append(host)
        except Exception:
            pass
    if not ok_hosts:
        # 没开 ADB 设备通道时桥本来就不会启动，这种情况不算失败
        adb_on = arg("adb-on", "1") == "1"
        status = read(DSH_HOME + "/.bridge_status").strip()
        if status.startswith("fail"):
            # App 侧记下了绑定失败的真实原因（端口被占等），直接摊开说
            add("FAIL", "3090 桥启动", status[5:].strip() or "绑定失败（原因未记录）")
        elif status == "stopped":
            add("SKIP" if not adb_on else "FAIL", "3090 桥",
                "桥已停止（设备桥服务没在跑）—— 重开 App，或在「配置」页勾选 ADB 设备通道并保存")
        else:
            add("FAIL" if adb_on else "SKIP", "3090 桥连通",
                "两个回环地址都连不上 —— App 需在运行中，且「配置」页勾过「启用 ADB 设备通道」并保存"
                if adb_on else "未启用 ADB 设备通道，桥不启动（正常）")
        return
    body = bodies[0]
    if '"result"' not in body:
        add("FAIL", "3090 桥响应", "响应不含 result 字段：%s" % body[:80])
        return
    if "[UNAUTHORIZED]" in body:
        add("FAIL", "3090 桥鉴权", "token 不匹配 —— 删掉 .bridge_token 后重开 App 让它重签")
        return
    # 合法 JSON 检查：旧版本输出 {"result":YES} 不带引号，客户端判定会全线失效
    try:
        json.loads(body)
        json_ok = True
    except Exception:
        json_ok = False
    add("PASS" if json_ok else "FAIL", "3090 桥",
        "可达地址 %s；响应%s合法 JSON" % ("+".join(ok_hosts), "是" if json_ok else "不是"))
    # 顺带抽查 App 层接口（agent 能直接调的那批能力）
    try:
        url = "http://127.0.0.1:3090/app/device?token=" + urllib.parse.quote(token)
        with urllib.request.urlopen(url, timeout=8) as r:
            d = json.loads(r.read().decode("utf-8", "replace")).get("result", "")
        first = d.split("\n")[0] if d else ""
        add("PASS" if "model=" in d else "FAIL", "App 层接口",
            first if "model=" in d else "/app/device 返回异常：%s" % d[:80])
    except Exception as e:
        add("FAIL", "App 层接口", "/app/device 调不通：%s（旧版 App 没有这些端点）" % e)


# ===================== 3. ADB 通道脚本 =====================
def check_adb(want_ver):
    cur = read(DSH_HOME + "/script-version").strip()
    if not cur:
        add("SKIP", "ADB 脚本", "未注入（没开 ADB 设备通道就正常）")
        return
    if want_ver and cur != want_ver:
        add("FAIL", "ADB 脚本版本", "rootfs=%s 期望=%s —— 重开一次「配置」页的 ADB 开关，或重跑步骤⑥" % (cur, want_ver))
    else:
        add("PASS", "ADB 脚本版本", "v%s" % cur)
    # 只读白名单：必须只放行真正只读的命令
    path = DSH_HOME + "/adb-shell.py"
    if not os.path.isfile(path):
        add("SKIP", "ADB 只读白名单", "adb-shell.py 未注入")
        return
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location("dsha_adb", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        fn = getattr(mod, "is_readonly_cmd", None)
        if fn is None:
            add("FAIL", "ADB 只读白名单", "还是老版判定（只看首个 token）—— 重注入 ADB 脚本")
            return
        cases = [("getprop ro.product.model", True), ("echo x > /sdcard/f", False),
                 ("pm uninstall com.x", False), ("settings put global x 1", False),
                 ("input tap 1 2", False), ("ls; rm -rf /sdcard/x", False)]
        bad = [c for c, want in cases if bool(fn(c)) != want]
        if bad:
            add("FAIL", "ADB 只读白名单", "判定异常：%s" % "、".join(bad))
        else:
            add("PASS", "ADB 只读白名单", "%d 条用例全部符合预期" % len(cases))
    except Exception as e:
        add("FAIL", "ADB 只读白名单", "加载 adb-shell.py 失败：%s" % e)


# ===================== 4. 设备引导插件（会话损坏根因） =====================
def check_guide(want_ver):
    pkg = os.path.join(GUIDE_DIR, "package.json")
    idx = os.path.join(GUIDE_DIR, "lib", "index.js")
    if not os.path.isfile(pkg):
        add("SKIP", "设备引导插件", "未安装（没跑过步骤⑥）")
        return
    try:
        ver = json.loads(read(pkg, 65536)).get("version", "?")
    except Exception:
        ver = "?"
    body = read(idx, 200000)
    has_id = "randomUUID" in body
    if not has_id:
        add("FAIL", "设备引导插件", "注入的消息没补 message.id（会把会话写坏）—— 重跑步骤⑥")
    elif want_ver and ver != want_ver:
        add("FAIL", "设备引导插件版本", "当前 %s 期望 %s —— 重跑步骤⑥" % (ver, want_ver))
    else:
        add("PASS", "设备引导插件", "v%s，注入消息带 id" % ver)


# ===================== 5. write 发布补丁 =====================
def check_write_patch():
    target = next((p for p in FS_LOCAL_CANDIDATES if os.path.isfile(p)), None)
    if target is None:
        add("SKIP", "write 补丁", "找不到 dsh-fs-local（dsh 可能装在别处）")
        return
    if "DSHA_L2S_FIX" in read(target, 400000):
        add("PASS", "write 补丁", "已生效（新建文件走 rename，不会变悬空链接）")
    else:
        add("FAIL", "write 补丁", "未打 —— 重开一次 App（启动自愈会补），或到启动页点一次启动")
    mark = read("/root/.dsha-hardlink").strip()
    if mark.startswith("ok"):
        add("PASS", "硬链接支持", "文件系统支持真实硬链接，proot 未启用 link2symlink")
    elif mark:
        add("SKIP", "硬链接支持", "不支持（Android 私有目录常态）：%s" % mark[:90])


# ===================== 6. 会话健康（只统计，不修） =====================
def check_sessions():
    if not os.path.isdir(SESSIONS):
        add("SKIP", "会话文件", "还没有会话目录")
        return
    names = ("session.jsonl", "session.jsonl.zstd")
    files, strays = [], 0
    for root, dirs, fs in os.walk(SESSIONS):
        for f in fs:
            if f in names:
                files.append(os.path.join(root, f))
            elif f.startswith("session.jsonl"):
                strays += 1  # .corrupt-* / .pre-fix-* 之类残留
    if strays:
        add("SKIP", "会话目录残留", "%d 个历史备份文件留在 sessions 里（老版本 heal 产物，不影响使用）" % strays)
    if not files:
        add("SKIP", "会话文件", "目录为空")
        return
    try:
        import zstandard as zstd
    except ImportError:
        add("SKIP", "会话可读性", "容器内没装 zstandard，跳过解码抽查（%d 个会话）" % len(files))
        return
    import io
    bad, missing_id = [], 0
    for p in sorted(files, key=os.path.getmtime, reverse=True)[:5]:  # 抽查最近 5 个
        raw = open(p, "rb").read()
        try:
            if raw[:4] == ZSTD_MAGIC:
                data = zstd.ZstdDecompressor().stream_reader(io.BytesIO(raw)).read()
            else:
                data = raw
        except Exception as e:
            bad.append("%s(%s)" % (os.path.basename(os.path.dirname(p)), e))
            continue
        for line in data.decode("utf-8", "replace").split("\n"):
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except Exception:
                continue
            t = ev.get("type", "")
            if t in ("user/message", "assistant/message", "tool/result"):
                d = ev.get("data")
                m = d if t == "user/message" else (d.get("message") if isinstance(d, dict) else None)
                if isinstance(m, dict) and not m.get("id"):
                    missing_id += 1
    if bad:
        add("FAIL", "会话可读性", "解码失败：%s —— 启动时的会话自愈会尝试修复/隔离" % "、".join(bad[:3]))
    elif missing_id:
        add("FAIL", "会话完整性", "抽查发现 %d 条消息缺 id（历史遗留）—— 重开 App 让自愈修复" % missing_id)
    else:
        add("PASS", "会话健康", "共 %d 个会话，抽查最近 %d 个：可解码、无缺 id"
            % (len(files), min(5, len(files))))


# ===================== 7. 内置插件可解析 =====================
def check_bundles():
    prof = os.path.join(DSH_HOME, "profiles", "web", "package.json")
    if not os.path.isfile(prof):
        add("SKIP", "profile bundles", "还没有 web profile")
        return
    try:
        pkg = json.loads(read(prof, 200000))
    except Exception as e:
        add("FAIL", "profile bundles", "package.json 解析失败：%s" % e)
        return
    deps = pkg.get("dependencies") or {}
    bundles = (((pkg.get("dsh") or {}).get("profile") or {}).get("bundles")) or []
    nm = os.path.join(DSH_HOME, "profiles", "web", "node_modules")
    globals_ = ("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
                "/usr/local/lib/node_modules")
    missing = []
    for b in bundles:
        spec = deps.get(b, "")
        p = spec[5:] if isinstance(spec, str) and spec.startswith(("link:", "file:")) else None
        if p and os.path.isfile(os.path.join(p, "package.json")):
            continue
        if os.path.isfile(os.path.join(nm, b, "package.json")):
            continue
        if any(os.path.isfile(os.path.join(g, b, "package.json")) for g in globals_):
            continue
        missing.append(b)
    if missing:
        add("FAIL", "profile bundles", "%d/%d 个解析不到：%s —— 到「市场」重装，或重跑步骤⑥补内置插件"
            % (len(missing), len(bundles), "、".join(missing)))
    else:
        add("PASS", "profile bundles", "%d 个全部可解析" % len(bundles))


# ===================== 8. 备份可用性 =====================
def check_backup():
    d = "/sdcard/Download/DSHA"
    if not os.path.isdir(d):
        add("SKIP", "备份", "还没备份过（配置页可设自动备份）")
        return
    packs = [os.path.join(d, f) for f in os.listdir(d)
             if f.lower().endswith((".tar.gz", ".tgz")) and f.lower().startswith("dsha-")]
    if not packs:
        add("SKIP", "备份", "Download/DSHA 里没有备份包")
        return
    latest = max(packs, key=os.path.getmtime)
    listing = sh("tar -tzf %s 2>/dev/null | head -80" % latest.replace(" ", "\\ "), timeout=60)
    has_dsh = ".dsh/" in listing
    has_manifest = "backup-manifest.json" in listing
    age = (time.time() - os.path.getmtime(latest)) / 86400.0
    detail = "%s（%.1f 天前，%.0fMB）" % (os.path.basename(latest), age, os.path.getsize(latest) / 1048576.0)
    if not has_dsh:
        add("FAIL", "备份内容", detail + " 里没有 .dsh —— 重新备份一次")
    elif not has_manifest:
        add("SKIP", "备份格式", detail + " 是老格式（无清单）：能恢复，但跨设备可能缺插件；重新备份即升级到 v2")
    else:
        add("PASS", "备份", detail + " 含 .dsh 与清单，可跨设备恢复")


# ===================== 9. 危险命令守卫 + 版本标记 =====================
def check_guard(want_guard, want_step6, want_assets):
    v = read("/root/dsh-bin/.version").strip()
    if not os.path.isdir("/root/dsh-bin"):
        add("FAIL", "危险命令守卫", "/root/dsh-bin 不存在 —— 跑步骤⑥装安全守卫")
    elif want_guard and v != want_guard:
        add("FAIL", "守卫版本", "当前 %s 期望 %s —— 重跑步骤⑥" % (v or "无", want_guard))
    else:
        add("PASS", "危险命令守卫", "dsh-bin v%s，确认脚本 %s" % (v, "在" if os.path.isfile("/root/dsh-confirm.sh") else "缺失"))
    s6 = read(DSH_HOME + "/step6.version").strip()
    av = read(DSH_HOME + "/builtin-assets.version").strip()
    if want_step6 and want_assets and (s6 != want_step6 or av != want_assets):
        add("FAIL", "步骤⑥版本标记", "rootfs=%s|%s 期望=%s|%s —— 重开 App 会自动重跑⑥，或手动点⑥"
            % (s6 or "无", av or "无", want_step6, want_assets))
    elif s6:
        add("PASS", "步骤⑥版本标记", "%s|%s" % (s6, av))


def main():
    print("=== DSHA 自检 · %s ===" % time.strftime("%Y-%m-%d %H:%M:%S"))
    for fn, args in (
        (check_env, ()),
        (check_tools, ()),
        (check_bridge, ()),
        (check_adb, (arg("script-ver"),)),
        (check_guide, (arg("guide-ver"),)),
        (check_write_patch, ()),
        (check_sessions, ()),
        (check_bundles, ()),
        (check_backup, ()),
        (check_guard, (arg("guard-ver"), arg("step6"), arg("assets"))),
    ):
        try:
            fn(*args)
        except Exception as e:
            add("FAIL", fn.__name__, "自检项自身出错：%r" % e)

    icon = {"PASS": "✅", "FAIL": "❌", "SKIP": "➖"}
    for state, title, detail in rows:
        print("%s %s" % (icon[state], title))
        if detail:
            print("    %s" % detail)
    print("")
    print("=== 汇总：%d 通过 / %d 失败 / %d 跳过 ===" % (counts["PASS"], counts["FAIL"], counts["SKIP"]))
    if counts["FAIL"] == 0:
        print("全部关键项通过。")
    else:
        print("上面标 ❌ 的项按提示处理；处理后再跑一次自检。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
