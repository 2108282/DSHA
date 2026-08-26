#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 备份恢复整理（在 rootfs 内运行，宽容优先）。

设计原则：**能恢复多少就恢复多少，绝不因为一处不认识就整包失败。**
兼容以下差异（都实测过或按用户报告覆盖）：
  · 包内布局不同：.dsh / root/.dsh / 任意层级/.dsh / stage 本身就是 .dsh 内容
  · 工作目录名不同：备份里 old-wd/.env → 落到本机当前 workdir/.env
  · 跨设备的本机路径插件：link:/root/plugin-src/x 在新机不存在 →
    用备份内联的源码（.dsha-plugin-src）落地并重写路径；找不到则把该 bundle
    摘掉（宁可少一个插件，也要让 dsh web 能启动），并在报告里列出
  · 老备份（无 manifest）：走启发式推断，不报错

用法：
  python3 restore-merge.py --stage /root/.dsha-restore-stage \
      --root /root --workdir deepseek-harness
输出：人话报告；最后一行 RESTORE_OK / RESTORE_PARTIAL / RESTORE_EMPTY
"""
import json
import os
import shutil
import sys
import time

TS = time.strftime("%Y%m%d-%H%M%S")
GLOBAL_NM = (
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
    "/usr/local/lib/node_modules",
)
PLUGIN_SRC_DIRNAME = "plugin-src"
INLINE_DIRNAME = ".dsha-plugin-src"
report = []
partial = False


def say(msg):
    report.append(msg)


def arg(name, default=""):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return default


def local_path_dep(spec):
    if not isinstance(spec, str):
        return None
    for prefix in ("link:", "file:"):
        if spec.startswith(prefix):
            return spec[len(prefix):]
    return None


# 老版本 dsh 的配置文件名和现在不一样（config.yaml / dsh.yaml），而「刚配好
# API key 还没对话过」的备份里连 sessions 都没有。原先只认
# sessions/profiles/settings.* 四个探针，上面这两类老备份会被判成
# 「备份里没找到 .dsh」→ 配置和 API key 全丢，用户看到的就是「和老版本不兼容」。
DSH_PROBES = ("sessions", "profiles", "settings.yaml", "settings.json",
              "config.yaml", "config.json", "dsh.yaml", "dsh.json",
              ".env", "agents", "skills", "credentials.json", "auth.json",
              "mcp.json", "history")


def looks_like_dsh(path):
    """目录内容像不像 .dsh 本体。判据故意放宽 —— 漏认的代价是用户配置全丢。"""
    for probe in DSH_PROBES:
        if os.path.exists(os.path.join(path, probe)):
            return True
    return False


def count_sessions(dsh_dir):
    """数一下 .dsh 里有多少个会话文件（用于恢复前后对比）"""
    n = 0
    root = os.path.join(dsh_dir, "sessions")
    if not os.path.isdir(root):
        return 0
    for _r, _d, files in os.walk(root):
        n += sum(1 for f in files if f.startswith("session.jsonl"))
    return n


def dir_nonempty(path):
    try:
        return bool(os.listdir(path))
    except OSError:
        return False


def find_dsh_dir(stage):
    """在 stage 里定位 .dsh：优先名为 .dsh 且内容像的最浅路径；
    其次 stage 自身就是 .dsh 内容（老包/手工包）。"""
    best = None
    fallback = None
    best_depth = 10 ** 6
    for root, dirs, _files in os.walk(stage):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        for d in list(dirs):
            if d != ".dsh":
                continue
            p = os.path.join(root, d)
            depth = p.count(os.sep)
            if looks_like_dsh(p) and depth < best_depth:
                best, best_depth = p, depth
            elif fallback is None and dir_nonempty(p):
                # 内容不匹配任何已知探针，但目录名就叫 .dsh 且非空 ——
                # 名字本身已是强信号，宁可多恢复一个目录，也不能漏掉用户的配置。
                fallback = p
    if best:
        return best
    if fallback:
        return fallback
    if looks_like_dsh(stage):
        return stage
    return None


def find_env_file(stage, workdir):
    """定位备份里的 .env：优先同名工作目录下的，其次任意 .env（取最浅）。"""
    cands = []
    for root, dirs, files in os.walk(stage):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".dsh")]
        for f in files:
            if f == ".env":
                cands.append(os.path.join(root, f))
    if not cands:
        return None
    cands.sort(key=lambda p: (os.path.basename(os.path.dirname(p)) != workdir, p.count(os.sep)))
    return cands[0]


def move_aside(path):
    """把已存在的路径挪成 .pre-restore-<ts> 备份（恢复的安全网，不删用户数据）"""
    if not os.path.exists(path):
        return None
    bak = "%s.pre-restore-%s" % (path, TS)
    try:
        os.rename(path, bak)
        return bak
    except Exception:
        try:
            shutil.rmtree(path)
        except Exception:
            pass
        return None


def restore_dsh(stage, root):
    global partial
    src = find_dsh_dir(stage)
    if src is None:
        say("· 备份里没找到 .dsh（配置/对话）——跳过，其余内容照常恢复")
        partial = True
        return False
    dst = os.path.join(root, ".dsh")
    bak = move_aside(dst)
    try:
        shutil.move(src, dst)
    except Exception as e:
        # move 失败（跨设备等）→ 退回复制
        try:
            shutil.copytree(src, dst, symlinks=True, dirs_exist_ok=True)
        except Exception as e2:
            say("· .dsh 恢复失败：%s / %s" % (e, e2))
            if bak:
                try:
                    os.rename(bak, dst)
                    say("· 已回滚到恢复前的数据")
                except Exception:
                    pass
            partial = True
            return False
    say("· 已恢复 .dsh（配置 + 对话记录）%s" % ("，原数据留存在 " + os.path.basename(bak) if bak else ""))
    # 健全性对比：恢复后的会话数远少于恢复前，说明这个备份很可能不完整
    # （选错文件、备份中断都会这样）。数据其实还在 .pre-restore-* 里，
    # 但用户看到 RESTORE_OK 就以为万事大吉，过几天才发现「历史没了」，
    # 那时已经分不清该回退哪个目录了。所以这里必须说出来。
    if bak:
        old_n, new_n = count_sessions(bak), count_sessions(dst)
        if old_n > 0 and new_n * 2 < old_n:
            partial = True
            say("· ⚠ 恢复前有 %d 个对话，恢复后只剩 %d —— 这个备份可能不完整。"
                "原数据完整保留在 %s，要回退就把它改名回 .dsh"
                % (old_n, new_n, os.path.basename(bak)))
    return True


def _link_target_or_self(path):
    """目标是有效软链时给出它指向的真实路径。

    数据要落在公开目录里，保持「主体在 Documents/dshdata、私有目录只留链接」这个
    迁移后的布局 —— 直接把链接换成目录，卸载 App 时数据就又跟着私有目录一起没了。
    """
    if os.path.islink(path) and os.path.exists(path):
        try:
            return os.path.realpath(path)
        except Exception:
            return path
    return path


def _land(src, dst):
    """把 src 落到 dst，原数据先挪成 .pre-restore-<ts>（恢复的安全网）。"""
    bak = move_aside(dst)
    parent = os.path.dirname(dst)
    if parent:
        os.makedirs(parent, exist_ok=True)
    try:
        shutil.move(src, dst)
    except Exception:
        if os.path.isdir(src):
            shutil.copytree(src, dst, symlinks=True, dirs_exist_ok=True)
        else:
            shutil.copy2(src, dst)
    return bak


def find_stage_dir(stage, name):
    """在 stage 里找名为 name 的目录（最浅优先）；找不到返回 None。"""
    best, best_depth = None, 10 ** 6
    for root, dirs, _files in os.walk(stage):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        if name in dirs:
            p = os.path.join(root, name)
            depth = p.count(os.sep)
            if depth < best_depth:
                best, best_depth = p, depth
    return best


def restore_pub_snapshot(stage, root, only=None):
    """把包里的 .dsha-pub/<name> 写回真实位置。

    sessions / storages / attachments / settings.yaml 在设备上通常是指向内部存储
    Documents/dshdata 的**符号链接**，而 tar 默认只存链接本身（实测包里只有一行
    lrwxrwxrwx，对话一条都没进去）。同机恢复看不出问题 —— 链接指回公开目录，数据
    还在那儿；换设备恢复就是悬空链接、对话全空。备份端因此额外做了一份解引用快照，
    这里把它落回去，且优先于 .dsh/ 下的同名链接。
    """
    global partial
    src_dir = find_stage_dir(stage, ".dsha-pub")
    if not src_dir:
        return False
    done = []
    try:
        names = sorted(os.listdir(src_dir))
    except OSError:
        return False
    for name in names:
        if only and name not in only:
            continue
        src = os.path.join(src_dir, name)
        dst = _link_target_or_self(os.path.join(root, ".dsh", name))
        try:
            bak = _land(src, dst)
            done.append(name + ("（原数据留存 %s）" % os.path.basename(bak) if bak else ""))
        except Exception as e:
            partial = True
            say("· 从快照恢复 %s 失败：%s" % (name, e))
    if done:
        say("· 已从快照恢复热数据：%s" % "、".join(done))
        return True
    return False


def restore_dsh_subtree(stage, root, subdirs):
    """部分备份：只把指定子树合并进现有 .dsh，其余内容一律不动。

    **绝不能走 restore_dsh** —— 那是「整个 .dsh 挪走再替换」。拿一个只含对话的包
    那么做，等于把用户的配置和插件全换掉；原数据虽然留在 .pre-restore-*，但用户
    看到 RESTORE_OK 就不会去找，等发现时已经分不清该回退哪个目录。
    """
    global partial
    src_dsh = find_dsh_dir(stage)
    if src_dsh is None:
        return False
    dst_dsh = os.path.join(root, ".dsh")
    try:
        os.makedirs(dst_dsh, exist_ok=True)
    except OSError:
        pass
    done = 0
    for sub in subdirs:
        src = os.path.join(src_dsh, sub)
        if not os.path.exists(src):
            continue
        dst = _link_target_or_self(os.path.join(dst_dsh, sub))
        try:
            bak = _land(src, dst)
            say("· 已恢复 .dsh/%s%s"
                % (sub, "，原数据留存在 " + os.path.basename(bak) if bak else ""))
            done += 1
        except Exception as e:
            partial = True
            say("· 恢复 .dsh/%s 失败：%s" % (sub, e))
    return done > 0


def restore_env(stage, root, workdir):
    global partial
    src = find_env_file(stage, workdir)
    if src is None:
        say("· 备份里没有 .env（API Key）——跳过（可在配置页重新填）")
        return False
    dst_dir = os.path.join(root, workdir)
    dst = os.path.join(dst_dir, ".env")
    try:
        os.makedirs(dst_dir, exist_ok=True)
        if os.path.exists(dst):
            try:
                shutil.copyfile(dst, dst + ".pre-restore-" + TS)
            except Exception:
                pass
        shutil.copyfile(src, dst)
        from_wd = os.path.basename(os.path.dirname(src))
        if from_wd and from_wd != workdir:
            say("· 已恢复 .env（备份里的工作目录是「%s」，已落到本机的「%s」）" % (from_wd, workdir))
        else:
            say("· 已恢复 .env（API Key）")
        return True
    except Exception as e:
        say("· .env 恢复失败：%s" % e)
        partial = True
        return False


def restore_inlined_plugins(stage, root):
    """把备份内联的插件源码落地到 /root/plugin-src/<name>，返回 {name: 目标路径}"""
    landed = {}
    src_root = None
    for root_dir, dirs, _f in os.walk(stage):
        if os.path.basename(root_dir) == INLINE_DIRNAME:
            src_root = root_dir
            break
        dirs[:] = [d for d in dirs if d != "node_modules"]
    if not src_root:
        return landed
    dst_root = os.path.join(root, PLUGIN_SRC_DIRNAME)
    for name in sorted(os.listdir(src_root)):
        s = os.path.join(src_root, name)
        if not os.path.isdir(s):
            continue
        d = os.path.join(dst_root, name)
        try:
            os.makedirs(dst_root, exist_ok=True)
            if os.path.isdir(d):
                shutil.rmtree(d, ignore_errors=True)
            shutil.copytree(s, d, symlinks=True)
            landed[name] = d
        except Exception as e:
            say("· 插件源码 %s 落地失败：%s" % (name, e))
    if landed:
        say("· 已从备份还原 %d 个本机插件源码：%s" % (len(landed), "、".join(sorted(landed))))
    return landed


def pkg_dir_ok(path):
    return os.path.isdir(path) and os.path.isfile(os.path.join(path, "package.json"))


def ensure_nm_link(prof_dir, name, target):
    """在 profile 的 node_modules 里补一条指向本机插件目录的符号链接。

    只改 package.json 的 link: 路径还不够：dsh 解析 bundle 走 node 的模块解析，
    node_modules/<name> 没有条目时依然报 cannot resolve profile bundle
    （正常是 pnpm install 建的链接，恢复后还没跑过 install）。
    """
    nm = os.path.join(prof_dir, "node_modules")
    link = os.path.join(nm, name)
    try:
        os.makedirs(nm, exist_ok=True)
        # 用 readlink 而不是 os.path.islink —— proot 下 lstat 被劫持，islink 对
        # 这些链恒返回 False，已经建好的正确链接会被下面的 isdir 分支当成普通目录处理
        try:
            cur = os.readlink(link)
        except OSError:
            cur = None
        if cur is not None:
            if os.path.realpath(link) == os.path.realpath(target):
                return True
            os.unlink(link)
        elif os.path.isdir(link):
            return True  # 已有实体目录（pnpm 装的真包），不动
        elif os.path.exists(link):
            os.remove(link)
        os.symlink(target, link)
        return True
    except Exception as e:
        say("· node_modules 补链失败（%s）：%s" % (name, e))
        return False


def bundle_resolvable(name, prof_dir, deps):
    spec = deps.get(name)
    p = local_path_dep(spec)
    if p and pkg_dir_ok(p):
        return True
    if pkg_dir_ok(os.path.join(prof_dir, "node_modules", name)):
        return True
    for base in GLOBAL_NM:
        if pkg_dir_ok(os.path.join(base, name)):
            return True
    return False


def fix_profiles(root, landed):
    """link 依赖重映射 + bundle 预检：不可解析的 bundle 摘掉，保证 dsh web 能起。"""
    global partial
    profiles = os.path.join(root, ".dsh", "profiles")
    if not os.path.isdir(profiles):
        return
    remapped, dropped, kept_missing, auto_installable = [], [], [], []
    for prof in sorted(os.listdir(profiles)):
        prof_dir = os.path.join(profiles, prof)
        pkg_path = os.path.join(prof_dir, "package.json")
        if not os.path.isfile(pkg_path):
            continue
        try:
            with open(pkg_path) as f:
                pkg = json.load(f)
        except Exception as e:
            say("· profile「%s」的 package.json 读不动（%s），跳过修正" % (prof, e))
            partial = True
            continue
        deps = pkg.get("dependencies") or {}
        changed = False
        # 1. 本机路径依赖：不存在就换成本机能找到的路径；存在的顺手补 node_modules 链接
        for name in list(deps):
            p = local_path_dep(deps[name])
            if p is None:
                continue
            if pkg_dir_ok(p):
                ensure_nm_link(prof_dir, name, p)
                continue
            cand = landed.get(name) or os.path.join(root, PLUGIN_SRC_DIRNAME, name)
            if pkg_dir_ok(cand):
                deps[name] = "link:" + cand
                ensure_nm_link(prof_dir, name, cand)
                remapped.append("%s→%s" % (name, cand))
                changed = True
            else:
                del deps[name]
                dropped.append(name)
                # 源码没了，但 npm 上可能有同名包 —— 交给 App 后台静默试装（失败无感）
                auto_installable.append(name)
                changed = True
        # 2. bundles 预检：解析不了的摘掉（内置插件由 App 启动时自动补回）
        dsh = pkg.get("dsh")
        prof_node = (dsh or {}).get("profile") if isinstance(dsh, dict) else None
        bundles = prof_node.get("bundles") if isinstance(prof_node, dict) else None
        if isinstance(bundles, list):
            keep = []
            for b in bundles:
                if not isinstance(b, str) or not b:
                    changed = True
                    continue
                if bundle_resolvable(b, prof_dir, deps):
                    keep.append(b)
                else:
                    kept_missing.append(b)
                    # 依赖里还留着 registry 版本号（^1.2.3 / npm:… ）→ 可以自动装回
                    spec = deps.get(b)
                    if isinstance(spec, str) and spec and local_path_dep(spec) is None:
                        auto_installable.append(b)
                    changed = True
            if keep != bundles:
                prof_node["bundles"] = keep
        if changed:
            try:
                # 原子写 + fsync：这是 profile 的核心文件，
                # 半个 JSON 会让 dsh 完全无法加载该 profile
                tmp_pkg = pkg_path + ".dsha-tmp"
                with open(tmp_pkg, "w") as f:
                    json.dump(pkg, f, ensure_ascii=False, indent=2)
                    f.write("\n")
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp_pkg, pkg_path)
            except Exception as e:
                say("· profile「%s」写回失败：%s" % (prof, e))
                partial = True
    if remapped:
        say("· 插件路径已按本机重映射：%s" % "、".join(remapped))
    if dropped:
        say("· 找不到源码、已从依赖里摘除：%s" % "、".join(dropped))
    if kept_missing:
        partial = True
        say("· 以下插件本机缺失，已暂时从启用列表摘掉：%s" % "、".join(sorted(set(kept_missing))))
    # 机器可读：仍有 registry 版本号（^1.2.3 / npm: 之类）的缺失插件可以自动补装，
    # App 侧据此在后台静默 dsh plugin add 装回；源码彻底丢失的只能人工重装。
    if auto_installable:
        print("MISSING_PLUGINS: %s" % ",".join(sorted(set(auto_installable))))


def read_manifest(stage):
    for root_dir, dirs, files in os.walk(stage):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        for f in files:
            if f == ".dsha-backup-manifest.json" or f == "backup-manifest.json":
                try:
                    with open(os.path.join(root_dir, f)) as fh:
                        return json.load(fh)
                except Exception:
                    return None
    return None


def main():
    stage = arg("stage", "/root/.dsha-restore-stage")
    root = arg("root", "/root")
    workdir = arg("workdir", "deepseek-harness") or "deepseek-harness"
    if not os.path.isdir(stage):
        print("恢复失败：解压目录不存在（%s）" % stage)
        print("RESTORE_EMPTY")
        return 1
    man = read_manifest(stage)
    # 备份范围的判定顺序：清单里的 scope 最权威（备份时写下的事实）→ App 从文件名
    # 推断出来的 --scope 兜底（老包没有清单，或用户重命名过文件）→ 最后缺省 full
    # （老备份的语义就是全量）。反过来让 --scope 覆盖清单是不对的：文件名可以被改，
    # 清单不会。
    scope = ((man.get("scope") if man else "") or arg("scope", "") or "full").strip() or "full"
    if man:
        say("· 备份来自 App %s / dsh %s（格式 v%s）"
            % (man.get("appVersion", "?"), man.get("dshVersion", "?"), man.get("formatVersion", "?")))
    else:
        say("· 老备份（无清单文件），按内容自动识别恢复")

    if scope == "sessions":
        say("· 这是「只对话」备份：只覆盖对话记录，配置与插件保持现状")
        ok_dsh = restore_dsh_subtree(stage, root, ["sessions"])
        # 快照后跑：它才是真数据（.dsh/sessions 在设备上多半只是个软链）
        ok_dsh = restore_pub_snapshot(stage, root, only=["sessions"]) or ok_dsh
    elif scope == "plugins":
        say("· 这是「只插件」备份：只覆盖插件，对话与配置保持现状")
        ok_dsh = restore_dsh_subtree(stage, root, ["profiles"])
        landed = restore_inlined_plugins(stage, root)
        fix_profiles(root, landed)
        ok_dsh = ok_dsh or bool(landed)
    else:
        ok_dsh = restore_dsh(stage, root)
        restore_env(stage, root, workdir)
        landed = restore_inlined_plugins(stage, root)
        fix_profiles(root, landed)
        # 全量也要落快照：.dsh 里的 sessions 等可能只是软链
        restore_pub_snapshot(stage, root)
    try:
        shutil.rmtree(stage, ignore_errors=True)
    except Exception:
        pass
    text = "\n".join(report)
    # 报告落盘便于事后排查（宽容：写不了也不影响恢复结果）
    try:
        rp = os.path.join(root, ".dsh", "restore-report.txt")
        if os.path.isdir(os.path.dirname(rp)):
            with open(rp, "a") as f:
                f.write("== 恢复 %s ==\n%s\n" % (TS, text))
    except Exception:
        pass
    print(text)
    if not ok_dsh:
        print("RESTORE_PARTIAL" if report else "RESTORE_EMPTY")
    else:
        print("RESTORE_PARTIAL" if partial else "RESTORE_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
