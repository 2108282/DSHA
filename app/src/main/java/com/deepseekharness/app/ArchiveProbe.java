package com.deepseekharness.app;

/**
 * 归档格式识别 + 插件布局识别 —— 纯逻辑，无 Android 依赖。
 *
 * <p><b>为什么按文件头判而不是按扩展名</b>：导入插件是用户从文件选择器挑文件，
 * 而那些文件的名字什么都可能：从 GitHub 下的 zip 常叫 {@code xxx-main.zip}，
 * 微信/QQ 转存过的会变成 {@code xxx.tar.gz.1}，有人还会把 tar.gz 手工改名成 .zip。
 * 扩展名不可信，文件头可信。
 *
 * <p><b>为什么插件布局也要判</b>：压缩包里可能是
 * <ul>
 *   <li>一个插件（根目录直接是 {@code package.json} + {@code lib/}）；</li>
 *   <li>多个插件（每个插件一个子目录，各有自己的 {@code package.json}）；</li>
 *   <li>多包了一层（{@code repo-main/插件名/package.json}，GitHub 下载的 zip 都长这样）。</li>
 * </ul>
 * 旧实现只认第二种：它把归档顶层的每个条目都当一个插件搬进 {@code node_modules}。
 * 拿一个单插件包进去，{@code package.json}、{@code lib}、{@code README.md} 会各自
 * 被当成一个「插件」搬走，结果是插件目录里多出几个垃圾条目而真插件没装上。
 */
final class ArchiveProbe {

    static final int UNKNOWN = 0;
    /** gzip 流（.tar.gz / .tgz，也可能是单文件 gz） */
    static final int GZIP = 1;
    /** zip 归档 */
    static final int ZIP = 2;
    /** 未压缩 tar */
    static final int TAR = 3;
    /** xz / bzip2 / zstd：识别得出，但当前解不了，用来给用户一句准话 */
    static final int OTHER_COMPRESSED = 4;

    private ArchiveProbe() {
    }

    /**
     * 按文件头判断格式。{@code head} 至少给 262 字节才能认出未压缩 tar
     * （tar 的 {@code ustar} magic 在 offset 257）。
     */
    static int kindOf(byte[] head) {
        if (head == null || head.length < 2) return UNKNOWN;
        int b0 = head[0] & 0xff, b1 = head[1] & 0xff;
        if (b0 == 0x1f && b1 == 0x8b) return GZIP;                 // gzip
        if (b0 == 0x50 && b1 == 0x4b) {                             // "PK"
            // 0x0304 普通、0x0506 空归档、0x0708 分卷；其余 PK 开头的不当 zip
            if (head.length >= 4) {
                int b2 = head[2] & 0xff, b3 = head[3] & 0xff;
                if ((b2 == 3 && b3 == 4) || (b2 == 5 && b3 == 6) || (b2 == 7 && b3 == 8)) return ZIP;
            }
            return ZIP;
        }
        if (b0 == 0xfd && head.length >= 6 && head[1] == '7' && head[2] == 'z'
                && head[3] == 'X' && head[4] == 'Z') return OTHER_COMPRESSED;   // xz
        if (b0 == 'B' && b1 == 'Z' && head.length >= 3 && head[2] == 'h') return OTHER_COMPRESSED; // bzip2
        if (b0 == 0x28 && head.length >= 4 && (head[1] & 0xff) == 0xb5
                && (head[2] & 0xff) == 0x2f && (head[3] & 0xff) == 0xfd) return OTHER_COMPRESSED;  // zstd
        if (head.length >= 262
                && head[257] == 'u' && head[258] == 's' && head[259] == 't'
                && head[260] == 'a' && head[261] == 'r') return TAR;
        return UNKNOWN;
    }

    /** 当前能解的格式。 */
    static boolean canExtract(int kind) {
        return kind == GZIP || kind == ZIP || kind == TAR;
    }

    /** 给用户看的格式名（失败提示里要说清「这是什么、为什么不行」）。 */
    static String kindName(int kind) {
        switch (kind) {
            case GZIP: return "tar.gz";
            case ZIP:  return "zip";
            case TAR:  return "tar";
            case OTHER_COMPRESSED: return "xz/bz2/zst";
            default:   return "未知格式";
        }
    }

    /**
     * 从解包后的相对路径列表里找出所有<b>插件根</b>（含 {@code package.json} 的目录）。
     *
     * <p>返回的是相对归档根的目录路径；空串 {@code ""} 表示<b>归档根本身就是一个插件</b>。
     *
     * <p>三条规则，每条都是踩过的：
     * <ul>
     *   <li><b>跳过 {@code node_modules/} 下的 package.json</b> —— 插件自己的依赖里全是
     *       package.json，不跳的话一个插件包会被认成几十个插件；</li>
     *   <li><b>只取深度最小的那一层</b> —— 插件内部的子包（{@code lib/foo/package.json}）
     *       不是插件；</li>
     *   <li><b>根目录有 package.json 就只认根</b> —— 这时它是单插件包，里面任何子目录的
     *       package.json 都属于它自己的结构。</li>
     * </ul>
     */
    static String[] pluginRoots(String[] relPaths) {
        java.util.List<String[]> layers = pluginRootsByDepth(relPaths);
        return layers.isEmpty() ? new String[0] : layers.get(0);
    }

    /**
     * 同 {@link #pluginRoots}，但把<b>每一层</b>候选都返回（浅的在前），让调用方在
     * 最浅那层不是真插件时能往下找。
     *
     * <p><b>为什么需要往下找</b>：GitHub 下载的 monorepo zip 长这样 ——
     * {@code repo-main/package.json} 是仓库的管理包（{@code private: true}、
     * 带 {@code workspaces}，不是插件），真插件在 {@code repo-main/plugins/*}。
     * 只取最浅一层的话，装进去的是那个管理包，真插件一个都没装上 ——
     * 而且它还会「装成功」，因为管理包本身是个合法的 npm 包。
     */
    static java.util.List<String[]> pluginRootsByDepth(String[] relPaths) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (relPaths == null) return out;
        java.util.TreeMap<Integer, java.util.LinkedHashSet<String>> byDepth = new java.util.TreeMap<>();
        for (String raw : relPaths) {
            if (raw == null) continue;
            String p = raw.replace('\\', '/');
            while (p.startsWith("./")) p = p.substring(2);
            while (p.startsWith("/")) p = p.substring(1);
            if (!p.endsWith("package.json")) continue;
            // 插件自己的依赖树里全是 package.json，一律不算插件根
            if (p.contains("node_modules/")) continue;
            String dir = p.length() == "package.json".length()
                    ? "" : p.substring(0, p.length() - "package.json".length() - 1);
            int depth = dir.isEmpty() ? 0 : dir.split("/").length;
            byDepth.computeIfAbsent(depth, k -> new java.util.LinkedHashSet<>()).add(dir);
        }
        for (java.util.Map.Entry<Integer, java.util.LinkedHashSet<String>> e : byDepth.entrySet()) {
            out.add(e.getValue().toArray(new String[0]));
        }
        return out;
    }

    /** 归档根本身就是一个插件（根有 package.json）。 */
    static boolean isSinglePlugin(String[] pluginRoots) {
        return pluginRoots != null && pluginRoots.length == 1 && pluginRoots[0].isEmpty();
    }

    /**
     * zip 条目名是否安全（防 zip slip）：不能是绝对路径、不能含 {@code ..} 段。
     *
     * <p>解包目标在 App 私有目录，写出目录外就是任意文件覆盖 —— 而插件包是用户从网上
     * 下来的，不能假设它善良。
     */
    static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty()) return false;
        String p = name.replace('\\', '/');
        if (p.startsWith("/")) return false;
        if (p.contains(":")) return false;              // Windows 盘符
        for (String seg : p.split("/")) {
            if (seg.equals("..")) return false;
        }
        return true;
    }
}
