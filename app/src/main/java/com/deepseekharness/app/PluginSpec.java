package com.deepseekharness.app;

/**
 * 插件来源（spec）的识别与校验 —— 纯逻辑，覆盖 <b>pnpm 支持的全部来源</b>。
 *
 * <p><b>为什么要按 pnpm 的全集来做</b>：{@code dsh plugin --profile <名字> <参数…>} 是一个
 * 很薄的 pnpm 转发器（官方文档原话），所以 pnpm 认识的每一种来源都是一种合法的插件安装方式。
 * 之前这里是一条字符白名单：
 *
 * <pre>
 *   s.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+")   // 只放过 npm 包名
 *   s.startsWith("github:") …                            // git 只认 github
 * </pre>
 *
 * 于是 {@code #commit} 锁定、{@code #path:} 子目录、{@code gitlab:}／{@code bitbucket:}、
 * 完整 git URL、远程 tarball、{@code jsr:}、命名 registry、本地 .tgz —— 全部被挡在门外，
 * 而它们在插件作者的 README 里到处都是。
 *
 * <p><b>安全边界换了地方</b>：字符白名单当初是为了防命令注入，但现在每一个 spec 都要过
 * {@link ShellQuote#arg}（POSIX 单引号，带 round-trip 断言），注入已经在那一层解决了。
 * 这里改成管两件白名单管不了的事：
 * <ul>
 *   <li><b>不能以 {@code -} 开头</b> —— 那会被 pnpm 当成命令行选项，{@code --force} 之类
 *       悄悄改变命令语义。这是引号挡不住的一类，也是这个类最要紧的一条。</li>
 *   <li>不含控制字符、长度有上限、形态得是 pnpm 认识的某一种 —— 认不出来就别拿去跑，
 *       让用户看到「不认识这个来源」而不是一屏 pnpm 堆栈。</li>
 * </ul>
 */
final class PluginSpec {

    static final int UNKNOWN = 0;
    /** npm registry：{@code pkg} / {@code @scope/pkg} / {@code pkg@1.2.3} / {@code pkg@^2} */
    static final int NPM = 1;
    /** JSR registry：{@code jsr:@scope/name} */
    static final int JSR = 2;
    /** 命名 registry：{@code gh:@org/pkg} / {@code npmjs:left-pad} / 自定义 alias */
    static final int NAMED_REGISTRY = 3;
    /** git 简写：{@code owner/repo}、{@code github:owner/repo}、{@code gitlab:…}、{@code bitbucket:…}，可带 {@code #ref} */
    static final int GIT_SHORTHAND = 4;
    /** 完整 git URL：{@code git+https://…}、{@code git+ssh://…}、{@code https://host/o/r.git}、{@code git@host:o/r.git} */
    static final int GIT_URL = 5;
    /** 远程 tarball：http(s) URL，且不是 git 形态 */
    static final int TARBALL_URL = 6;
    /** 本地目录：{@code ./dir}、{@code /abs/dir}、{@code file:…}、{@code link:…} */
    static final int LOCAL_DIR = 7;
    /** 本地压缩包：{@code ./pkg.tgz}、{@code /abs/pkg.tar.gz} */
    static final int LOCAL_TARBALL = 8;

    private PluginSpec() {
    }

    /** 长度上限：pnpm 的 spec 再长也用不到这个数，超了基本是粘错东西。 */
    private static final int MAX_LEN = 512;

    /** 识别来源类型；认不出来返回 {@link #UNKNOWN}。 */
    static int classify(String spec) {
        if (!basicallySane(spec)) return UNKNOWN;
        String s = spec.trim();

        // 本地路径与显式协议（这两个前缀是 dsh/pnpm 对本地 checkout 的写法）
        if (s.startsWith("file:") || s.startsWith("link:")) {
            return looksLikeTarballPath(s) ? LOCAL_TARBALL : LOCAL_DIR;
        }
        if (s.startsWith("./") || s.startsWith("../") || s.startsWith("/")) {
            return looksLikeTarballPath(s) ? LOCAL_TARBALL : LOCAL_DIR;
        }

        // git 的完整 URL 形态：git+ 前缀、或 .git 结尾、或 scp 风格 git@host:path
        String low = s.toLowerCase(java.util.Locale.US);
        String beforeRef = stripRef(low);
        if (low.startsWith("git+") || low.startsWith("git://")) return GIT_URL;
        if (low.startsWith("git@") && s.contains(":")) return GIT_URL;
        if (beforeRef.startsWith("http://") || beforeRef.startsWith("https://")) {
            // pnpm 的判据：.git 结尾（或 git+ 前缀）算 git，否则当远程 tarball
            return beforeRef.endsWith(".git") ? GIT_URL : TARBALL_URL;
        }

        // jsr: 与命名 registry（都是 <alias>:<包名> 形态）。
        // 找协议冒号只能在 # 之前找 —— git ref 里合法地带着冒号
        // （#semver:^2.0.0、#path:/packages/app），在整串上找会把它误当成协议，
        // 于是这些形态全被判成「不认识」。
        String head = stripRef(s);
        int colon = head.indexOf(':');
        if (colon > 0) {
            String scheme = head.substring(0, colon);
            String rest = s.substring(colon + 1);      // 保留 #ref 交给下游判据
            if (!scheme.matches("[A-Za-z][A-Za-z0-9+._-]*")) return UNKNOWN;
            if (scheme.equals("jsr")) {
                return npmNameWithRange(rest) ? JSR : UNKNOWN;
            }
            if (scheme.equals("github") || scheme.equals("gitlab") || scheme.equals("bitbucket")) {
                return gitShorthandBody(rest) ? GIT_SHORTHAND : UNKNOWN;
            }
            // 其它 alias：gh: / npmjs: / 用户在 pnpm-workspace.yaml 里映射的名字
            return npmNameWithRange(rest) ? NAMED_REGISTRY : UNKNOWN;
        }

        // 没有协议：要么 owner/repo（git 简写，pnpm 省略 provider 时默认 github），
        // 要么 npm 包名。区别在于 @scope/name 是 npm，而 owner/repo 不带 @。
        if (s.startsWith("@")) {
            return npmNameWithRange(s) ? NPM : UNKNOWN;
        }
        if (gitShorthandBody(s)) return GIT_SHORTHAND;
        return npmNameWithRange(s) ? NPM : UNKNOWN;
    }

    /** 这个 spec 能不能拿去跑（识别得出形态，且没有会改变命令语义的东西）。 */
    static boolean isUsable(String spec) {
        return classify(spec) != UNKNOWN;
    }

    /** 给用户看的来源说明（安装前的预检提示里用）。 */
    static String describe(int kind) {
        switch (kind) {
            case NPM:            return "npm registry";
            case JSR:            return "JSR registry";
            case NAMED_REGISTRY: return "指定的 registry";
            case GIT_SHORTHAND:  return "git 仓库（简写）";
            case GIT_URL:        return "git 仓库（完整 URL）";
            case TARBALL_URL:    return "远程压缩包";
            case LOCAL_DIR:      return "本地目录";
            case LOCAL_TARBALL:  return "本地压缩包";
            default:             return "无法识别的来源";
        }
    }

    /** 这个来源装出来的东西是不是「源码」（可能缺构建产物，要么 prepare、要么自己构建）。 */
    static boolean shipsSourceOnly(int kind) {
        return kind == GIT_SHORTHAND || kind == GIT_URL;
    }

    /** {@code #} 之后的 git ref 部分（分支/标签/commit/semver:/path: 组合）；没有则空串。 */
    static String refOf(String spec) {
        if (spec == null) return "";
        int h = spec.indexOf('#');
        return h >= 0 ? spec.substring(h + 1) : "";
    }

    /** git ref 里的 {@code path:} 子目录（monorepo 用）；没有则空串。 */
    static String subPathOf(String spec) {
        String ref = refOf(spec);
        if (ref.isEmpty()) return "";
        for (String part : ref.split("&")) {
            String p = part.trim();
            if (p.startsWith("path:")) {
                String v = p.substring("path:".length()).trim();
                while (v.startsWith("/")) v = v.substring(1);
                return v;
            }
        }
        return "";
    }

    /** 严格的 npm <b>包名</b>（可带 scope）—— 这跟「安装来源」是两件事。
     *
     *  <p>安置目录名、导出文件名、已装插件的标识用这条：{@code owner/repo} 是合法的
     *  安装来源却不是合法包名，拿它当目录名会在 node_modules 里造出一层假的 scope。 */
    static boolean isPackageName(String name) {
        if (!basicallySane(name)) return false;
        return name.trim().matches("(?:@[A-Za-z0-9._~-]+/)?[A-Za-z0-9._~-]+");
    }

    // ── 内部判据 ──

    /**
     * 三条一票否决：空、太长、含控制字符，以及<b>以 {@code -} 开头</b>。
     *
     * <p>最后那条是这个类存在的首要理由：spec 会作为参数交给 pnpm，以 {@code -} 开头的
     * 字符串会被当成选项（{@code --force}、{@code -g}…），引号挡不住这种语义偷换。
     */
    private static boolean basicallySane(String spec) {
        if (spec == null) return false;
        String s = spec.trim();
        if (s.isEmpty() || s.length() > MAX_LEN) return false;
        if (s.startsWith("-")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;   // 控制字符、换行、制表
        }
        return true;
    }

    /** 去掉 {@code #ref} 之后的主体。 */
    private static String stripRef(String s) {
        int h = s.indexOf('#');
        return h >= 0 ? s.substring(0, h) : s;
    }

    private static boolean looksLikeTarballPath(String s) {
        String p = stripRef(s).toLowerCase(java.util.Locale.US);
        return p.endsWith(".tgz") || p.endsWith(".tar.gz") || p.endsWith(".tar");
    }

    /** npm 包名（可带 {@code @版本范围}）。版本范围允许 pnpm 认的那些符号。 */
    private static boolean npmNameWithRange(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return false;
        String name = s, range = "";
        // @scope/name@range：从第二个 @ 起才是版本
        int at = s.startsWith("@") ? s.indexOf('@', 1) : s.indexOf('@');
        if (at > 0) {
            name = s.substring(0, at);
            range = s.substring(at + 1);
        }
        if (!name.matches("(?:@[A-Za-z0-9._~-]+/)?[A-Za-z0-9._~-]+")) return false;
        if (range.isEmpty()) return true;
        // tag（next/latest/beta）或 semver 范围（^ ~ > < = 空格 || x * -）
        return range.matches("[A-Za-z0-9.^~><=*|\\s+_-]+");
    }

    /** git 简写的主体：{@code owner/repo}（可带 .git 与 {@code #ref}）。 */
    private static boolean gitShorthandBody(String raw) {
        String s = stripRef(raw == null ? "" : raw.trim());
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        if (!s.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) return false;
        // ref 部分（分支/标签/commit/semver:/path:，& 组合）只做温和校验：
        // 里面允许出现 / : ^ < > = . 这些，但不许有空白
        String ref = refOf(raw);
        return ref.isEmpty() || ref.matches("[A-Za-z0-9._~:/^<>=*&+-]+");
    }
}
