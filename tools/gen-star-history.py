#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自己画 star history 曲线，不依赖第三方服务。

**为什么要自己画**：GitHub 从 2026-06-30 起把 stargazers API（谁在什么时候 star 的）
限制成只有仓库的 admin/collaborator 能读。star-history.com 的服务器不是我们的
collaborator，所以 README 里那种「实时拉取」的嵌入图对几乎所有仓库都挂了 ——
页面会提示「GitHub 限制了对星标数据的访问」。他们给的变通办法是把一个能读你仓库的
token 加密后放进 URL；那要去第三方网站操作，而且等于把凭据托管在别人那里。

这里换成自己生成：Actions 里用 GITHUB_TOKEN（对本仓库天然有读权限）拉数据，
把曲线画成仓库内的静态 SVG。好处是图永远显示得出来、数据在自己手里、
不受第三方服务与限流影响；代价是曲线按计划任务刷新（每周一次），不是实时的。

用法：
    GITHUB_TOKEN=<token> python3 tools/gen-star-history.py
产物：
    docs/star-history.svg        浅色主题
    docs/star-history-dark.svg   深色主题（README 用 <picture> 按系统主题切）
    docs/star-history.json       原始数据点（按天聚合），便于以后换画法或核对
"""
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

REPO = os.environ.get("STAR_REPO", "qiannianhuanxiang/DSHA")
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs")
W, H = 820, 420
PAD_L, PAD_R, PAD_T, PAD_B = 62, 24, 44, 52


def api(url, token, accept="application/vnd.github+json"):
    req = urllib.request.Request(url, headers={
        "Authorization": "token " + token,
        "Accept": accept,
        "User-Agent": "DSHA-star-history",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r), r.headers.get("Link", "")


def next_link(link_header):
    """从 Link 头里取 rel="next" 的 URL；没有就返回 None。"""
    for part in link_header.split(","):
        if 'rel="next"' in part:
            return part.split(";")[0].strip().strip("<>")
    return None


def fetch_stars(token):
    """拉全部 stargazers 的 starred_at（升序）。"""
    url = ("https://api.github.com/repos/%s/stargazers?per_page=100" % REPO)
    stamps = []
    while url:
        try:
            data, link = api(url, token, "application/vnd.github.star+json")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:300]
            print("拉 stargazers 失败 HTTP %s：%s" % (e.code, body), file=sys.stderr)
            if e.code in (401, 403, 404):
                print("提示：2026-06-30 起这个接口只对仓库 admin/collaborator 开放，"
                      "token 需要能读本仓库（GITHUB_TOKEN 在自己仓库的 Actions 里就够）。",
                      file=sys.stderr)
            raise
        for it in data:
            at = it.get("starred_at")
            if at:
                stamps.append(at)
        url = next_link(link)
    stamps.sort()
    return stamps


def daily_series(stamps):
    """按天聚合成累计曲线：[(date, 累计数), …]。点少、曲线平滑、文件也小。"""
    out, total = [], 0
    for at in stamps:
        day = at[:10]
        total += 1
        if out and out[-1][0] == day:
            out[-1][1] = total
        else:
            out.append([day, total])
    return [(d, n) for d, n in out]


def nice_max(v):
    """把 y 轴上限取整到好看的刻度。"""
    if v <= 10:
        return 10
    for step in (10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000):
        if v <= step * 5:
            return ((v + step - 1) // step) * step
    return v


def days_between(a, b):
    fmt = "%Y-%m-%d"
    return (datetime.strptime(b, fmt) - datetime.strptime(a, fmt)).days


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def render(series, dark):
    """画 SVG。刻意不用任何绘图库 —— 这脚本要在干净的 runner 上零依赖跑起来。"""
    if not series:
        return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="60">'
                '<text x="10" y="35">还没有 star 数据</text></svg>' % W)
    bg = "#0d1117" if dark else "#ffffff"
    fg = "#e6edf3" if dark else "#24292f"
    sub = "#8b949e" if dark else "#57606a"
    grid = "#30363d" if dark else "#e1e4e8"
    line = "#58a6ff" if dark else "#0969da"
    fill = "rgba(88,166,255,0.16)" if dark else "rgba(9,105,218,0.10)"

    first_day, last_day = series[0][0], series[-1][0]
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    if days_between(last_day, today) > 0:
        series = series + [(today, series[-1][1])]  # 曲线画到今天，末尾持平
        last_day = today
    span = max(days_between(first_day, last_day), 1)
    ymax = nice_max(series[-1][1])
    plot_w = W - PAD_L - PAD_R
    plot_h = H - PAD_T - PAD_B

    def px(day):
        return PAD_L + plot_w * days_between(first_day, day) / span

    def py(n):
        return PAD_T + plot_h * (1 - n / ymax)

    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
             'viewBox="0 0 %d %d" font-family="-apple-system,BlinkMacSystemFont,'
             '\'Segoe UI\',Helvetica,Arial,sans-serif">' % (W, H, W, H)]
    parts.append('<rect width="%d" height="%d" fill="%s"/>' % (W, H, bg))
    parts.append('<text x="%d" y="26" fill="%s" font-size="15" font-weight="600">%s</text>'
                 % (PAD_L - 2, fg, esc(REPO)))
    parts.append('<text x="%d" y="26" fill="%s" font-size="13" text-anchor="end">'
                 '%d stars</text>' % (W - PAD_R, sub, series[-1][1]))

    # y 轴：5 条网格线
    for i in range(6):
        v = ymax * i // 5
        y = py(v)
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="%s" '
                     'stroke-width="1"/>' % (PAD_L, y, W - PAD_R, y, grid))
        parts.append('<text x="%d" y="%.1f" fill="%s" font-size="11" '
                     'text-anchor="end">%d</text>' % (PAD_L - 8, y + 4, sub, v))

    # x 轴：4 个日期刻度（含首尾）
    ticks = 4
    for i in range(ticks + 1):
        d = days_between(first_day, last_day) * i // ticks
        day = (datetime.strptime(first_day, "%Y-%m-%d").toordinal() + d)
        day = datetime.fromordinal(day).strftime("%Y-%m-%d")
        x = px(day)
        parts.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s" '
                     'stroke-width="1"/>' % (x, PAD_T, x, H - PAD_B, grid))
        parts.append('<text x="%.1f" y="%d" fill="%s" font-size="11" '
                     'text-anchor="middle">%s</text>'
                     % (x, H - PAD_B + 18, sub, day[5:]))

    pts = " ".join("%.1f,%.1f" % (px(d), py(n)) for d, n in series)
    parts.append('<polygon points="%.1f,%.1f %s %.1f,%.1f" fill="%s"/>'
                 % (px(first_day), py(0), pts, px(last_day), py(0), fill))
    parts.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="2.5" '
                 'stroke-linejoin="round" stroke-linecap="round"/>' % (pts, line))
    parts.append('<circle cx="%.1f" cy="%.1f" r="3.5" fill="%s"/>'
                 % (px(last_day), py(series[-1][1]), line))
    parts.append('<text x="%d" y="%d" fill="%s" font-size="10">'
                 '数据截至 %s · GitHub stargazers API</text>'
                 % (PAD_L - 2, H - 12, sub, today))
    parts.append("</svg>")
    return "".join(parts)


def main():
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("需要 GITHUB_TOKEN（能读本仓库即可）", file=sys.stderr)
        return 1
    stamps = fetch_stars(token)
    series = daily_series(stamps)
    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)
    with open(os.path.join(OUT_DIR, "star-history.svg"), "w", encoding="utf-8") as f:
        f.write(render(series, dark=False))
    with open(os.path.join(OUT_DIR, "star-history-dark.svg"), "w", encoding="utf-8") as f:
        f.write(render(series, dark=True))
    with open(os.path.join(OUT_DIR, "star-history.json"), "w", encoding="utf-8") as f:
        # 刻意**不写**「生成时间」这类每次都变的字段：写了的话，即使一颗新 star 都没有，
        # 每次跑都会产出一次「文件变了」的提交 —— 首次上线就撞到了，CI 那趟自动提交
        # 改的就只有这一行。只留与数据本身有关的东西，数据没变就完全没有 diff。
        json.dump({"repo": REPO, "total": series[-1][1] if series else 0,
                   "latest": series[-1][0] if series else None,
                   "points": [{"date": d, "stars": n} for d, n in series]},
                  f, ensure_ascii=False, indent=1)
    print("STAR_HISTORY_OK %s：%d stars，%d 个数据点（%s → %s）"
          % (REPO, series[-1][1], len(series), series[0][0], series[-1][0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
