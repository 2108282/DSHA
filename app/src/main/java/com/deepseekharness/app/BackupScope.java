package com.deepseekharness.app;

/**
 * 备份范围的<b>唯一</b>定义：全量 / 只对话 / 只插件。
 *
 * <p>一处定义，三处派生 —— 备份时 tar 打哪些路径、恢复时合并哪些子树、文件名叫什么，
 * 全都从这里出。这三件事必须一致：备份打了 A 而恢复只合并 B，用户就会拿到一个
 * 「恢复成功但东西没回来」的包，而且看不出哪一步错了。同一个理由见
 * {@link UserDataPolicy} 的类注释 —— 那个类是「什么算用户数据」的唯一定义，
 * 这个类是「一次备份覆盖多大范围」的唯一定义。
 *
 * <p><b>文件名前缀是向后兼容的关键</b>：老版本按 {@code DSHA-backup-} 前缀扫描备份，
 * 并且恢复时会把整个 {@code .dsh} 挪走再替换。要是把部分备份也叫 {@code DSHA-backup-*}，
 * 老版本（以及自动恢复提示）会把「只含对话的包」当全量恢复 ——
 * 配置与插件全部丢失。所以部分备份刻意用别的前缀，老版本<b>看不见</b>它们。
 *
 * <p>这个类刻意不碰 Android API，纯字符串与数组处理，好让 {@code tools/pure-logic-test.sh}
 * 直接下断言。
 */
final class BackupScope {

    /** 全量：配置 + 对话 + 插件 + 工作区 .env + 日志。与历史行为一致。 */
    static final int FULL = 0;
    /** 只对话：{@code .dsh/sessions}。换设备只想把聊天记录带走时用。 */
    static final int SESSIONS = 1;
    /** 只插件：profile 声明 + 内联的本机插件源码。 */
    static final int PLUGINS = 2;

    /** UI 与对话框里展示的顺序（也是选项顺序）。 */
    static final int[] ALL = { FULL, SESSIONS, PLUGINS };

    private BackupScope() {
    }

    /** 写进备份清单（manifest）的标识。恢复端靠它决定合并范围，所以不能改字面量。 */
    static String id(int scope) {
        switch (scope) {
            case SESSIONS: return "sessions";
            case PLUGINS:  return "plugins";
            default:       return "full";
        }
    }

    /** 从清单里的标识反解。认不出来一律当全量 —— 老备份没有这个字段，而它们就是全量。 */
    static int fromId(String id) {
        if (id == null) return FULL;
        String s = id.trim();
        if (s.equals("sessions")) return SESSIONS;
        if (s.equals("plugins")) return PLUGINS;
        return FULL;
    }

    /** 文件名前缀。部分备份刻意不叫 DSHA-backup-（见类注释）。 */
    static String fileNamePrefix(int scope) {
        switch (scope) {
            case SESSIONS: return "DSHA-sessions-";
            case PLUGINS:  return "DSHA-plugins-";
            default:       return "DSHA-backup-";
        }
    }

    /** 按文件名判断范围（用户手动选包恢复时先看名字，清单里的 scope 优先级更高）。 */
    static int fromFileName(String name) {
        if (name == null) return FULL;
        String n = name.trim();
        if (n.startsWith("DSHA-sessions-")) return SESSIONS;
        if (n.startsWith("DSHA-plugins-")) return PLUGINS;
        return FULL;
    }

    /** 这个范围的包会不会被老版本当成全量备份（= 文件名带 DSHA-backup- 前缀）。 */
    static boolean visibleToLegacyScan(int scope) {
        return fileNamePrefix(scope).equals("DSHA-backup-");
    }

    /** 给用户看的名字。 */
    static String label(int scope) {
        switch (scope) {
            case SESSIONS: return "只备份对话";
            case PLUGINS:  return "只备份插件";
            default:       return "全量备份";
        }
    }

    /** 给用户看的一句话说明（跟在选项名下面，所以要短）。 */
    static String describe(int scope) {
        switch (scope) {
            case SESSIONS:
                return "只打包对话，恢复时不动配置与插件";
            case PLUGINS:
                return "只打包插件，恢复时不动对话";
            default:
                return "配置 + 对话 + 插件，换机或重装用这个";
        }
    }

    /**
     * {@code .dsh} 下要打包的子路径；空数组表示<b>整个 {@code .dsh}</b>。
     *
     * <p>返回的是相对 {@code /root} 的路径，直接进 tar 的位置参数。
     */
    static String[] dshPaths(int scope) {
        switch (scope) {
            case SESSIONS: return new String[] { ".dsh/sessions" };
            case PLUGINS:  return new String[] { ".dsh/profiles" };
            default:       return new String[0];   // 空 = 整个 .dsh
        }
    }

    /**
     * 恢复时要合并的 {@code .dsh} 子目录名；空数组表示整目录替换（全量的老行为）。
     *
     * <p>必须与 {@link #dshPaths(int)} 一一对应 —— 备份打了 sessions 而恢复合并 profiles
     * 这种错位，纯逻辑断言会当场抓住。
     */
    static String[] mergeSubdirs(int scope) {
        switch (scope) {
            case SESSIONS: return new String[] { "sessions" };
            case PLUGINS:  return new String[] { "profiles" };
            default:       return new String[0];
        }
    }

    /** 这个范围要不要带工作区的 {@code .env} 与 {@code dsh-web.log}（只有全量要）。 */
    static boolean includesWorkdirFiles(int scope) {
        return scope == FULL;
    }

    /** 这个范围要不要内联本机路径插件的源码（全量与插件备份都要）。 */
    static boolean includesPluginSrc(int scope) {
        return scope == FULL || scope == PLUGINS;
    }

    /**
     * 这个范围要不要把公开目录里的热数据解引用快照进包。
     *
     * <p>{@code .dsh/sessions}、{@code storages}、{@code attachments}、{@code settings.yaml}
     * 在数据迁移之后都是指向 {@code /sdcard/Documents/dshdata} 的<b>符号链接</b>，而
     * {@code tar} 默认存链接本身不跟随（实测：包里只有一行
     * {@code lrwxrwxrwx .dsh/sessions -> …}，对话一条都没进去）。同机恢复看不出问题
     * —— 链接指回公开目录，数据还在那儿；<b>换设备恢复就是悬空链接，对话全空</b>。
     *
     * <p>不能简单给 tar 加 {@code -h}：那是全局开关，会把 {@code node_modules} 里
     * 每个 {@code link:} 插件也展开一遍，包会爆掉。所以只对这四个已知条目做解引用快照，
     * 放在包内的 {@code .dsha-pub/} 下。老版本不认识这个目录，会照旧恢复 {@code .dsh}
     * ——行为不比今天差，属于纯增量。
     */
    static boolean needsPublicDataSnapshot(int scope) {
        return scope == FULL || scope == SESSIONS;
    }

    /** 公开目录里会被软链出去的热数据条目（与 BackupManager 的软链自修复同一份名单）。 */
    static final String[] PUBLIC_HOT_ENTRIES = {
            "sessions", "storages", "attachments", "settings.yaml",
    };

    /** 包内承载公开数据快照的目录名（相对 {@code /root}）。 */
    static final String PUB_SNAPSHOT_DIR = ".dsha-pub";

    /** 这个范围需要快照哪些公开条目：只对话时没必要把 storages/attachments 全带上。 */
    static String[] snapshotEntries(int scope) {
        if (scope == SESSIONS) return new String[] { "sessions" };
        if (scope == FULL) return PUBLIC_HOT_ENTRIES.clone();
        return new String[0];
    }
}
