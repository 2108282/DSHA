package com.deepseekharness.app;

/**
 * GitHub 链接解析 —— 纯逻辑，<b>保留 monorepo 子目录</b>。
 *
 * <p>为什么要单独一份：原来的解析一律 {@code repo.indexOf('/')} 截断，
 * {@code github.com/owner/repo/tree/main/plugins/turn-guard} 会被解析成
 * {@code owner/repo} —— 装的是**整个仓库根**而不是那个插件。而 monorepo（一个仓库放
 * 多个插件）在 dsh 插件生态里很常见，用户从浏览器复制的链接天然带 {@code /tree/分支/子目录}。
 *
 * <p>踩过的后果就是「提示安装成功，插件管理页空无一物」：装上的是 monorepo 根那个
 * 管理用的 package.json，它不是插件，所以既不在 bundles 里也没有 dsh 字段，
 * 插件列表自然看不到它。
 */
final class GitHubRef {

    /** 仓库拥有者。 */
    final String owner;
    /** 仓库名。 */
    final String repo;
    /** 分支（URL 里没写就是空串，调用方自行按 main / master 试）。 */
    final String branch;
    /** 仓库内子目录，没有则为空串。 */
    final String subdir;

    private GitHubRef(String owner, String repo, String branch, String subdir) {
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
        this.subdir = subdir;
    }

    /** {@code owner/repo}。 */
    String slug() {
        return owner + "/" + repo;
    }

    /** 有子目录 = 这是 monorepo 里的一个包，不能按整仓库装。 */
    boolean hasSubdir() {
        return !subdir.isEmpty();
    }

    /** 拉 raw 文件用的路径前缀（含分支，分支未知时用给定默认值）。 */
    String rawPrefix(String fallbackBranch) {
        String br = branch.isEmpty() ? fallbackBranch : branch;
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + br
                + (subdir.isEmpty() ? "" : "/" + subdir);
    }

    /**
     * 解析各种形态的 GitHub 链接；认不出返回 null。
     *
     * <pre>
     *   owner/repo
     *   https://github.com/owner/repo(.git)
     *   https://github.com/owner/repo/tree/main/plugins/x     → subdir=plugins/x branch=main
     *   https://github.com/owner/repo/blob/main/plugins/x/package.json → subdir=plugins/x
     *   github:owner/repo
     * </pre>
     */
    static GitHubRef parse(String url) {
        if (url == null) return null;
        String core = url.trim();
        if (core.isEmpty()) return null;
        if (core.startsWith("github:")) core = core.substring("github:".length());
        int g = core.indexOf("github.com/");
        if (g >= 0) core = core.substring(g + "github.com/".length());
        int q = core.indexOf('?');
        if (q >= 0) core = core.substring(0, q);
        int h = core.indexOf('#');
        if (h >= 0) core = core.substring(0, h);
        while (core.startsWith("/")) core = core.substring(1);
        while (core.endsWith("/")) core = core.substring(0, core.length() - 1);
        if (core.endsWith(".git")) core = core.substring(0, core.length() - 4);
        String[] seg = core.split("/");
        if (seg.length < 2 || seg[0].isEmpty() || seg[1].isEmpty()) return null;
        String owner = seg[0], repo = seg[1], branch = "", subdir = "";
        // tree/blob 之后是 <分支>/<路径…>
        if (seg.length >= 4 && (seg[2].equals("tree") || seg[2].equals("blob"))) {
            branch = seg[3];
            StringBuilder sb = new StringBuilder();
            for (int i = 4; i < seg.length; i++) {
                if (sb.length() > 0) sb.append('/');
                sb.append(seg[i]);
            }
            subdir = sb.toString();
            // blob 链接指到的是文件（…/package.json）→ 子目录是它的父目录
            if (seg[2].equals("blob") && subdir.contains("/")) {
                subdir = subdir.substring(0, subdir.lastIndexOf('/'));
            } else if (seg[2].equals("blob")) {
                subdir = "";
            }
        }
        return new GitHubRef(owner, repo, branch, subdir);
    }
}
