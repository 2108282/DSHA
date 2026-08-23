#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检测（并可修复）dsh 生态里最致命的一个坑：@deepseek-ai/* 包的双副本。

症状极具误导性：**所有**工具调用都失败，连内置的 filesystem 都崩，报
    Cannot read properties of undefined (reading 'prepare')
看起来像 dsh 坏了，其实是模块副本问题。

机制（上游 discussion #783 / #1849 有源码级分析）：
  dsh-agent-loop 通过模块级 Symbol("@deepseek-ai/dsh-tools.scheduler") 找调度器。
  ~/.dsh/profiles/node_modules 是扁平兜底目录，每个 @deepseek-ai/* 都 symlink 回
  安装树，从而保证 Symbol 是单例。
  但 `dsh plugin add` 实际是转发给 pnpm，在 profile 目录里跑 pnpm install 会把
  @deepseek-ai/dsh-tools 物理复制进 profiles/<name>/node_modules/。
  于是同一个包有两份模块实例、两个不同的 Symbol：
  agent-loop 用副本 A 的 Symbol 去查 ctx.tools（由副本 B 注册）→ undefined → 抛错。
  两份副本版本相同、文件内容一致，所以按版本 dedupe 根本看不出问题。

判据：profile **自己的** node_modules/@deepseek-ai/ 下出现真实目录（而非符号链接）。
扁平兜底目录里的符号链接是正常结构，绝不能碰。

修复采用社区验证过的做法：删掉物理副本、改成符号链接指向安装树。
（本环境实测 dsh 自己的兜底目录就是这么做的，symlink 工作正常；proot 的
 --link2symlink 只影响 link() 系统调用，不影响主动创建的符号链接。）

用法：
  python3 check-dsh-dupes.py            # 只检测
  python3 check-dsh-dupes.py --fix      # 同版本副本改成符号链接（先备份）
"""
import json
import os
import shutil
import sys
import time

# 允许用环境变量改根目录：这样夹具测试不必碰真实的 .dsh
PROFILES = os.environ.get("DSHA_PROFILES_DIR", "/root/.dsh/profiles")
FLAT_NM = os.path.join(PROFILES, "node_modules")
SCOPE = "@deepseek-ai"
# 这几个是「一份变两份就全崩」的核心包，报告时单独点名
CRITICAL = ("dsh-tools", "cordis", "dsh-agent-loop", "dsh-session", "dsh-skill")


def is_link(path):
    """是符号链接就返回目标，否则 None。

    刻意用 readlink 而不是 os.path.islink —— proot 环境下 lstat 对某些路径
    会失败（link2symlink 扩展劫持了 stat 系列），islink 会一律返回 False，
    于是正常的符号链接会被误判成物理副本，修复动作就成了破坏动作。
    """
    try:
        return os.readlink(path)
    except OSError:
        return None


def read_version(pkg_dir):
    try:
        with open(os.path.join(pkg_dir, "package.json"), encoding="utf-8") as f:
            return json.load(f).get("version") or ""
    except Exception:
        return ""


def install_tree_dir():
    """安装树里的 @deepseek-ai 目录。优先从扁平兜底目录的符号链接反推 ——
    那是 dsh 自己维护的、最权威的指向。"""
    if os.path.isdir(FLAT_NM + "/" + SCOPE):
        for name in os.listdir(FLAT_NM + "/" + SCOPE):
            tgt = is_link(os.path.join(FLAT_NM, SCOPE, name))
            if tgt and SCOPE in tgt:
                d = os.path.dirname(os.path.abspath(tgt))
                if os.path.isdir(d):
                    return d
    for cand in (
        "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
        "/usr/local/lib/node_modules/@deepseek-ai",
    ):
        if os.path.isdir(cand):
            return cand
    return ""


def scan():
    """返回 [(profile 名, 包名, 副本目录, 副本版本, 安装树版本)]"""
    out = []
    tree = install_tree_dir()
    if not os.path.isdir(PROFILES):
        return out, tree
    for prof in sorted(os.listdir(PROFILES)):
        pdir = os.path.join(PROFILES, prof)
        # 跳过扁平兜底目录本身：它里面全是正常的符号链接
        if prof == "node_modules" or not os.path.isdir(pdir):
            continue
        scope_dir = os.path.join(pdir, "node_modules", SCOPE)
        if not os.path.isdir(scope_dir):
            continue
        for name in sorted(os.listdir(scope_dir)):
            # 跳过隐藏目录：npm 包名不能以 . 开头，而我们自己的备份目录
            # （.dsha-dupe-backup-*）恰好在这里 —— 不排掉就会把自己的备份
            # 当成新的物理副本反复报告（heal-sessions 早期踩过同一个坑）
            if name.startswith("."):
                continue
            p = os.path.join(scope_dir, name)
            if is_link(p) is not None:
                continue  # 符号链接 = 已经指向安装树，正常
            if not os.path.isdir(p):
                continue
            out.append((prof, name, p, read_version(p),
                        read_version(os.path.join(tree, name)) if tree else ""))
    return out, tree


def main():
    fix = "--fix" in sys.argv
    dupes, tree = scan()

    if not dupes:
        print("DUPES_NONE: profile 内没有 %s/* 物理副本（工具调用不会因此崩）" % SCOPE)
        return 0

    crit = [d for d in dupes if d[1] in CRITICAL]
    print("DUPES_FOUND=%d CRITICAL=%d" % (len(dupes), len(crit)))
    for prof, name, path, v1, v2 in dupes:
        mark = " ★关键包" if name in CRITICAL else ""
        print("  %s/%s%s  副本版本=%s 安装树版本=%s" % (prof, name, mark, v1 or "?", v2 or "?"))
    if crit:
        print("  ↑ 带★的会让**所有**工具调用失败（Cannot read properties of "
              "undefined (reading 'prepare')），包括内置工具")

    if not fix:
        print("DUPES_HINT: 加 --fix 可把同版本副本改成符号链接（会先备份）")
        return 0

    if not tree:
        print("DUPES_FIX_FAIL: 找不到安装树的 %s 目录，不敢动手" % SCOPE)
        return 1

    stamp = time.strftime("%Y%m%d-%H%M%S")
    fixed = skipped = failed = 0
    for prof, name, path, v1, v2 in dupes:
        target = os.path.join(tree, name)
        if not os.path.isdir(target):
            print("  跳过 %s：安装树里没有这个包（可能是插件自带的，不该删）" % name)
            skipped += 1
            continue
        # 版本不同就不动：可能是插件确实要一个不同版本，删了会让它失效。
        # 而双副本崩溃的典型形态恰恰是「同版本两份」，那种删掉最安全。
        if v1 and v2 and v1 != v2:
            print("  跳过 %s：副本 %s 与安装树 %s 版本不同，只报告不处理" % (name, v1, v2))
            skipped += 1
            continue
        try:
            bak = os.path.join(os.path.dirname(path), ".dsha-dupe-backup-" + stamp)
            os.makedirs(bak, exist_ok=True)
            shutil.move(path, os.path.join(bak, name))
            os.symlink(target, path)
            print("  已修 %s/%s → 符号链接指向安装树（原副本存到 %s）" % (prof, name, bak))
            fixed += 1
        except Exception as e:
            print("  修复 %s 失败：%r" % (name, e))
            failed += 1

    print("DUPES_FIXED=%d SKIPPED=%d FAILED=%d" % (fixed, skipped, failed))
    if fixed:
        print("DUPES_FIX_OK: 重启 Web 后工具调用即恢复")
    return 0


if __name__ == "__main__":
    sys.exit(main())
