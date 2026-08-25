#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""selftest.py 里 heal_hard_inject 的断言集。

用法：python3 tools/selftest-heal-test.py

为什么单独测这一项：它会**改用户 rootfs 里的插件源码**，而且改的是「Web 起不起来」
这条最关键的路径。改错的代价不是提示不准，是把「插件不激活」变成「插件一跑就炸」
（只删掉模块级 inject 声明、没把 section 调用包进 ctx.inject 的话，运行时读未声明
的服务会直接抛 cannot get property "systemPrompt" without inject）。

测试全在临时目录里造假 rootfs，不碰真实环境。
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets")

OLD_PLUGIN = """\
// DSHA builtin plugin（简化样本，结构与 0.1.9 一致）
export const inject = ['systemPrompt']

const PROMPT = 'device shell guide'

export function apply(ctx) {
  // 通道 1：标准模式 systemPrompt 注入
  ctx.systemPrompt.section({
    name: 'dsh:device-shell-guide',
    order: 150,
    text: PROMPT,
  })

  ctx.on('agent/pre-step', () => {})
}
"""

# 只有硬依赖、没有可识别的 section 调用 —— 修一半比不修更糟，必须整体放弃
UNKNOWN_SHAPE = """\
export const inject = ['systemPrompt']
export function apply(ctx) {
  ctx.systemPrompt.section(buildSectionSomehow())
}
"""

pass_n = 0
fail_n = 0


def check(name, cond, extra=""):
    global pass_n, fail_n
    if cond:
        pass_n += 1
        print("  ok   " + name)
    else:
        fail_n += 1
        print("  FAIL " + name + ("\n         " + extra if extra else ""))


def load_selftest(dsh_home, guide_dir):
    """加载 selftest 模块并把目标路径指到临时目录。"""
    sys.path.insert(0, ASSETS)
    os.environ["DSHA_DSH_HOME"] = dsh_home
    for m in list(sys.modules):
        if m == "selftest":
            del sys.modules[m]
    import selftest as st          # noqa: E402  （必须在设好环境变量之后 import）
    st.GUIDE_DIR = guide_dir
    st.GUIDE_TARGET_PATTERNS = (
        guide_dir + "/lib/index.js",
        dsh_home + "/profiles/*/node_modules/dsh-device-shell-guide/lib/index.js",
    )
    return st


def write(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)


def node_check(path):
    if not shutil.which("node"):
        return True             # 没有 node 就不判（CI 上有）
    r = subprocess.run(["node", "--check", path],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if r.returncode != 0:
        print("         node --check 输出：" + (r.stdout or "").strip())
    return r.returncode == 0


def main():
    tmp = tempfile.mkdtemp(prefix="dsha-heal-")
    try:
        dsh_home = os.path.join(tmp, "dsh")
        guide = os.path.join(tmp, "guide")
        src = guide + "/lib/index.js"
        prof = dsh_home + "/profiles/web/node_modules/dsh-device-shell-guide/lib/index.js"
        write(src, OLD_PLUGIN)
        write(prof, OLD_PLUGIN)      # 两个副本都要修 —— 只改一个是老毛病

        st = load_selftest(dsh_home, guide)

        # ---- 1. 旧版：两处都该被修好 ----
        st.rows.clear()
        st.counts.update({k: 0 for k in st.counts})
        st.heal_hard_inject()
        state, title, detail = st.rows[-1]
        check("旧版被判为需要修补", state == "FAIL" and "已修补" in title,
              "实际：%s / %s" % (state, title))
        body = open(src, encoding="utf-8").read()
        check("模块级 inject 已失效", st.HARD_INJECT_RE.search(body) is None
              and "export const inject" not in body)
        check("section 已包进 ctx.inject", "ctx.inject(['systemPrompt']" in body)
        check("PROMPT 仍被用上", "text: PROMPT" in body)
        check("修补后是合法 JS", node_check(src))
        check("原件留了 .dsha-bak", os.path.isfile(src + ".dsha-bak"))
        check("profile 里那份也修了",
              "ctx.inject(['systemPrompt']" in open(prof, encoding="utf-8").read())
        check("报告点名了两个路径", detail.count("/lib/index.js") >= 2)

        # ---- 2. 幂等：再跑一次应该是 PASS，且不再写 .bak ----
        bak_before = open(src + ".dsha-bak", encoding="utf-8").read()
        st.rows.clear()
        st.heal_hard_inject()
        state2, title2, _ = st.rows[-1]
        check("第二次跑判为健康", state2 == "PASS", "实际：%s / %s" % (state2, title2))
        check(".dsha-bak 没被二次覆盖",
              open(src + ".dsha-bak", encoding="utf-8").read() == bak_before)

        # ---- 3. 结构不认识：一个字都不能改 ----
        tmp2 = tempfile.mkdtemp(prefix="dsha-heal2-")
        dsh2 = os.path.join(tmp2, "dsh")
        guide2 = os.path.join(tmp2, "guide")
        odd = guide2 + "/lib/index.js"
        write(odd, UNKNOWN_SHAPE)
        st2 = load_selftest(dsh2, guide2)
        st2.rows.clear()
        st2.heal_hard_inject()
        state3, title3, _ = st2.rows[-1]
        check("认不出的结构报「修不了」", state3 == "FAIL" and "修不了" in title3,
              "实际：%s / %s" % (state3, title3))
        check("认不出时文件保持原样",
              open(odd, encoding="utf-8").read() == UNKNOWN_SHAPE)
        check("认不出时不留 .dsha-bak", not os.path.exists(odd + ".dsha-bak"))
        shutil.rmtree(tmp2, ignore_errors=True)

        # ---- 4. 一个副本都没有 → SKIP，不该报错 ----
        tmp3 = tempfile.mkdtemp(prefix="dsha-heal3-")
        st3 = load_selftest(os.path.join(tmp3, "dsh"), os.path.join(tmp3, "guide"))
        st3.rows.clear()
        st3.heal_hard_inject()
        check("没有任何副本时 SKIP", st3.rows[-1][0] == "SKIP")
        shutil.rmtree(tmp3, ignore_errors=True)

        # ---- 5. 仓库里当前的插件源码本身不该再有硬依赖 ----
        repo_plugin = os.path.join(ASSETS, "device-shell-guide", "lib", "index.js")
        if os.path.isfile(repo_plugin):
            src_body = open(repo_plugin, encoding="utf-8").read()
            check("仓库内置插件已是作用域注入",
                  st.HARD_INJECT_RE.search(src_body) is None
                  and "ctx.inject(['systemPrompt']" in src_body)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("")
    if fail_n:
        print("失败 %d 条（通过 %d）" % (fail_n, pass_n))
        return 1
    print("全部通过：%d 条" % pass_n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
