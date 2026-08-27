package com.deepseekharness.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用 <b>官方 patch 层</b>开关插件 —— 纯逻辑，只做文本，不碰文件。
 *
 * <p><b>为什么换机制</b>：我们原来的启用/禁用是给 {@code node_modules} 里的目录改
 * {@code .disabled} 后缀。那是野路子，代价一路摊在用户身上：
 * <ul>
 *   <li>包从 node_modules 消失 → dsh 的 reconcile 把它从 bundles 里剪掉，必须重启才生效；</li>
 *   <li>搬文件会失败（实体丢失、链接悬空），于是有了「空文件占位」、「幽灵插件」
 *       那一串补丁（issue #9）；</li>
 *   <li>pnpm 下次 install 时会觉得依赖状态不一致。</li>
 * </ul>
 * 官方的做法是往 profile 的 {@code cordis.patch.yml} 写一行 {@code disabled: true} 覆盖
 * 目标 loader 行：DSH 的 HMR 约 1 秒重组，<b>不用重启</b>，loader 每次启动都会重新应用，
 * 包也一直老老实实待在 node_modules 里。社区市场 dsh-market 用的就是这条（它又是从
 * dsh-plugin-hub 移植的），2.2k star 验证过。
 *
 * <p><b>只在自己的区块里改，区块外一字不动</b>：这个文件是<b>用户自己的</b> patch 层，
 * 里面可能有他手写的行。我们不去解析、更不重排他的内容 —— 只在文件尾部维护一段
 * 由标记围起来的区块，整段由我们生成。放在尾部也正好符合 patch 语义：后应用的层按行胜出，
 * 我们的 disabled 才盖得住前面 bundle 层的那一行。
 */
final class PatchToggle {

    /** 区块起始标记。 */
    static final String BEGIN = "# >>> DSHA managed (plugin toggles) >>>";
    /** 区块结束标记。 */
    static final String END = "# <<< DSHA managed <<<";

    private PatchToggle() {
    }

    /** 读出 DSHA 区块里当前被禁用的 loader 行 id。 */
    static Set<String> disabledIds(String yaml) {
        Set<String> out = new LinkedHashSet<>();
        if (yaml == null || yaml.isEmpty()) return out;
        String[] lines = yaml.split("\n", -1);
        boolean inBlock = false;
        String pendingId = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals(BEGIN)) {
                inBlock = true;
                continue;
            }
            if (line.equals(END)) {
                inBlock = false;
                continue;
            }
            if (!inBlock) continue;
            if (line.startsWith("- id:")) {
                pendingId = unquote(line.substring("- id:".length()).trim());
            } else if (line.startsWith("disabled:") && pendingId != null) {
                String v = line.substring("disabled:".length()).trim();
                if (v.equals("true")) out.add(pendingId);
                pendingId = null;
            }
        }
        return out;
    }

    /**
     * 生成新的 patch 文本：DSHA 区块里禁用 {@code ids} 列出的 loader 行。
     *
     * <p>{@code ids} 为空时连标记一起去掉 —— 一个空区块留在用户文件里是噪音，
     * 而且以后看不出它是我们的还是他自己删空的。
     */
    static String withDisabled(String yaml, Set<String> ids) {
        String body = stripBlock(yaml == null ? "" : yaml);
        if (ids == null || ids.isEmpty()) return body;
        StringBuilder sb = new StringBuilder(body);
        // 与前面内容之间留一个空行；文件本身为空时不要开头的空行
        String trimmedTail = sb.toString();
        while (trimmedTail.endsWith("\n")) trimmedTail = trimmedTail.substring(0, trimmedTail.length() - 1);
        sb.setLength(0);
        sb.append(trimmedTail);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(BEGIN).append('\n');
        sb.append("# 这一段由 DSHA 的插件开关维护，手改会被覆盖；上面的内容不会被动。\n");
        for (String id : ids) {
            if (id == null || id.trim().isEmpty()) continue;
            sb.append("- id: ").append(quoteIfNeeded(id.trim())).append('\n');
            sb.append("  disabled: true\n");
        }
        sb.append(END).append('\n');
        return sb.toString();
    }

    /** 去掉 DSHA 区块（含标记），其余原样保留。 */
    static String stripBlock(String yaml) {
        if (yaml == null || yaml.isEmpty()) return "";
        String[] lines = yaml.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.equals(BEGIN)) {
                inBlock = true;
                continue;
            }
            if (t.equals(END)) {
                inBlock = false;
                continue;
            }
            if (inBlock) continue;
            sb.append(raw).append('\n');
        }
        // 末尾规范化成「恰好一个换行」。原来写的是 if (endsWith("\n\n")) 去掉一个 ——
        // 只够抵消 split(-1) 末尾那个空串带来的换行，抵不掉 withDisabled 为分隔区块
        // 而加的那个空行。结果每装一次区块再去掉，文件末尾就攒下一个空行：
        // 实测反复开关 10 轮，文件比原文多一个 \n（33 → 34 字节）。
        String out = sb.toString();
        while (out.endsWith("\n\n")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /**
     * 从插件<b>自己的</b> {@code cordis.patch.yml} 里抠出它 insert 进 loader 的行 id。
     *
     * <p>要禁用一个插件，patch 得按 <b>id</b> 定位那一行，而 id 是插件作者在自己的
     * patch 里定的（{@code - insert: - id: hello, name: dsh-hello-plugin}），
     * 跟包名往往不一样 —— 拿包名去写 disabled 是写不中的。
     *
     * <p>刻意只认 {@code id:} 这一个键、不做完整 YAML 解析：这里只需要一串标识符，
     * 引进一个 YAML 库来读两行字不值得，也多一个可能出错的地方。
     */
    static List<String> insertedIds(String pluginPatchYaml) {
        List<String> out = new ArrayList<>();
        if (pluginPatchYaml == null || pluginPatchYaml.isEmpty()) return out;
        for (String raw : pluginPatchYaml.split("\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            String v = null;
            if (line.startsWith("- id:")) {
                v = line.substring("- id:".length()).trim();
            } else if (line.startsWith("id:")) {
                v = line.substring("id:".length()).trim();
            }
            if (v == null) continue;
            v = unquote(v);
            // 去掉行内注释
            int hash = v.indexOf(" #");
            if (hash > 0) v = v.substring(0, hash).trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /**
     * 往 {@code pnpm-workspace.yaml} 的 {@code allowBuilds} 里授权一个包（幂等）。
     *
     * <p>这是 dsh 自己在错误消息里给出的修法：git 装来的插件靠 {@code prepare} 脚本在安装时
     * 构建，而 pnpm ≥10 在得到显式允许前拒绝执行它，第一次 {@code add} 因此必然失败 ——
     *
     * <pre>
     *   allowBuilds:
     *     dsh-hello-plugin: true
     * </pre>
     *
     * <p>比我们自己 clone + 构建轻得多，所以该排在那条路前面。
     *
     * <p>同样只做追加、不重排用户已有的内容：这个文件还管着 workspace 的其它配置。
     */
    static String withAllowBuild(String yaml, String pkgKey) {
        String key = pkgKey == null ? "" : pkgKey.trim();
        if (key.isEmpty()) return yaml == null ? "" : yaml;
        String text = yaml == null ? "" : yaml;
        String quoted = key.matches("[A-Za-z0-9._-]+") ? key : "'" + key.replace("'", "''") + "'";
        // 已经授权过就别再写一遍（这个方法会被重试逻辑反复调）
        for (String raw : text.split("\n", -1)) {
            String t = raw.trim();
            if ((t.startsWith(key + ":") || t.startsWith(quoted + ":")) && t.endsWith("true")) {
                return text;
            }
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
            if (!inserted && lines[i].trim().equals("allowBuilds:")) {
                sb.append("  ").append(quoted).append(": true\n");
                inserted = true;
            }
        }
        String out = sb.toString();
        if (!inserted) {
            while (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
            if (!out.isEmpty()) out += "\n";
            out += "allowBuilds:\n  " + quoted + ": true\n";
        }
        return out;
    }

    private static String unquote(String v) {
        String s = v == null ? "" : v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** id 里出现 YAML 特殊字符时加引号（包名里的 {@code @} 与 {@code /} 都算）。 */
    private static String quoteIfNeeded(String id) {
        if (id.matches("[A-Za-z0-9._-]+")) return id;
        return "'" + id.replace("'", "''") + "'";
    }
}
