package com.deepseekharness.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全量备份到外部存储（Download/DSHA/）：
 * rootfs 内打包 .dsh（配置+对话记录）+ .env + 日志 → 拷贝到公共下载目录。
 * Android 10+ 走 MediaStore（无需权限）；Android 9- 直接写公共目录。
 */
public final class BackupManager {

    private BackupManager() {
    }

    /** 备份互斥锁：手动/自动备份共用 root/.dsha-backup.tar.gz 中转文件，
     *  并发会互相覆盖 → 加锁串行（防备份损坏）。 */
    private static final Object BACKUP_LOCK = new Object();

    /** 自动备份固定文件名（槽位 1）。 */
    public static final String AUTO_BACKUP_NAME = "DSHA-backup-auto.tar.gz";
    /** 自动备份固定文件名（槽位 2）—— 与槽位 1 交替使用，见 {@link #backupToExternalAuto}。 */
    public static final String AUTO_BACKUP_NAME_2 = "DSHA-backup-auto-2.tar.gz";

    private static boolean isAutoName(String n) {
        return AUTO_BACKUP_NAME.equals(n) || AUTO_BACKUP_NAME_2.equals(n);
    }
    /** 手动备份最多保留份数（超出删最旧，防 Download/DSHA 无限膨胀） */
    private static final int MAX_MANUAL_KEEP = 10;

    /** 执行备份并导出，返回外部存储中的完整路径；失败返回 null */
    public static String backupToExternal(Context ctx, HarnessController c) {
        return backup(ctx, c, null);
    }

    /** 自动备份：两个固定名**交替**使用，永远留着上一次那份完整的。
     *
     *  <p>原来是一个固定名反复覆盖。两个坏处：这次备份的如果是已经坏掉的环境
     *  （会话文件被写坏、用户误删了数据），上一份好的就被盖没了；写入中途失败
     *  （空间不足）还可能留下一个截断文件，而旧的那份已经不在了。手动备份有 10 份轮换，
     *  偏偏「用户没意识到自己需要它」的自动备份只有一份 —— 顺序反了。
     *
     *  <p>交替选名字而不是改名轮转：MediaStore 上改名要多一次 update、多一处可能失败，
     *  而交替只是换个字符串。自动恢复那边按 {@code DSHA-backup-} 前缀扫描，两个槽都认。 */
    public static String backupToExternalAuto(Context ctx, HarnessController c) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
        boolean useSlot2 = !sp.getBoolean("auto_backup_slot2", false);
        String path = backup(ctx, c, useSlot2 ? AUTO_BACKUP_NAME_2 : AUTO_BACKUP_NAME);
        // 只有成功才翻转：失败时下次仍写这一槽，不会白白牺牲掉另一槽里的好备份
        if (path != null) {
            sp.edit().putBoolean("auto_backup_slot2", useSlot2).apply();
        }
        return path;
    }

    /** 内部实现。name=null 表示手动备份（时间戳命名，每次独立保留）；否则固定名覆盖。 */
    /** 最近一次备份失败的原因（UI 直接展示，别再让用户看「环境可能未安装」这种猜测） */
    private static volatile String lastError = "";

    public static String lastError() {
        return lastError;
    }

    /** 失败原因落到 rootfs（/root/.dsh/backup-last-error），自检直接读它 —— 
     *  用户报「备份没反应」时不必再猜。成功则清掉该文件。 */
    private static void recordError(HarnessController c, String why) {
        try {
            if (c == null || c.getProot() == null) return;
            File f = new File(c.getProot().getRootfsDir(), "root/.dsh/backup-last-error");
            if (why == null || why.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
            if (f.getParentFile() != null && !f.getParentFile().isDirectory()
                    && !f.getParentFile().mkdirs()) {
                return;
            }
            String body = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  " + why.replace("\n", " ") + "\n";
            java.nio.file.Files.write(f.toPath(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static String backup(Context ctx, HarnessController c, String fixedName) {
        synchronized (BACKUP_LOCK) { // 串行备份（防中转文件互相覆盖）
        try {
            lastError = "";
            // rootfs 内打包：
            // - .dsh（配置+对话）为必选项，缺失视为备份失败；
            // - <workdir>/.env 在自定义工作目录存在时加入；
            // - dsh-web.log 仅当存在时加入，不存在不失败（旧实现 tar 会因缺文件退出非零）
            String wd = c.getWorkdir();
            String dshEsc = wd.replace("'", "'\\''");
            // 备份前置整理（宽容：失败也照常备份）：
            //  · 生成 .dsha-backup-manifest.json（App/dsh 版本、workdir、bundles、link 依赖）
            //  · 把 link:/file: 本机路径插件的源码内联到 .dsha-plugin-src/
            // 目的：换设备/换版本恢复时不再因「link:/root/plugin-src/x 不存在」起不来。
            // 顺序要紧：先把 .l2s 链摊平（不然下面的 tar 直接失败），再生成清单
            runFlattenL2s(c);
            runBackupPrepare(c, wd);
            // issue #22：离线包安装的用户从来不会有 <workdir>/.env —— .env 只在「在线安装
            // harness」的最后一步写入，离线包路径不执行那步；启动 Web 时 key 由 export
            // DEEPSEEK_API_KEY 直接注入进程，不落盘。而 key 本体存在 App 的 SharedPreferences，
            // 卸载即清空。于是这类用户备份→卸载→恢复之后 key 必然为空。
            // 修法：备份前把当前 key 写进 .dsh/.dsha-apikey —— tar 清单第一项就是 .dsh，
            // 会自动带上，不必改打包参数；key 为空则跳过。
            // 备份落在 Download/DSHA（公共目录）：任何拿到存储权限的应用都读得到，
            // 而备份里有全部会话历史。把 key 也塞进去是方便，但也是把凭据摊在公共区，
            // 所以给用户一个关掉的开关（默认开，保持原有行为不变）。
            boolean includeKey = ctx
                    .getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getBoolean("backup_include_key", true);
            // 备份时的 key 处理：includeKey 才带。写进 rootfs 的 .dsh/.dsha-apikey，
            // 但**加密存储**（参考 dsh-mobile 的 Keystore 思路）—— 这份 key 会随备份
            // 进 Download/DSHA（公共目录，任何有存储权限的 App 都读得到），明文等于裸奔。
            String bkKey = includeKey ? c.getApiKey() : "";
            if (!includeKey) {
                // 上次备份可能留过这个文件，关掉开关后要真的清掉，否则形同没关
                try {
                    File old = new File(c.getProot().getRootfsDir(), "root/.dsh/.dsha-apikey");
                    if (old.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (bkKey != null && !bkKey.isEmpty()) {
                try {
                    File kf = new File(c.getProot().getRootfsDir(), "root/.dsh/.dsha-apikey");
                    if (kf.getParentFile() != null) {
                        //noinspection ResultOfMethodCallIgnored
                        kf.getParentFile().mkdirs();
                    }
                    // 加密：base64(iv):base64(ct) 的格式，恢复时 dsh 自身不读这个文件，
                    // 它只是「备份里带 key」的便利；恢复脚本会在导入后重新走 setApiKey 加密存储。
                    String enc = c.encryptKeyForBackup(bkKey);
                    java.nio.file.Files.write(kf.toPath(),
                            enc.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try {
                        java.nio.file.Files.setPosixFilePermissions(kf.toPath(),
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    } catch (Throwable e) {
                        android.util.Log.w("DSHA", "API key 备份权限设置失败（不影响）: " + e);
                    }
                } catch (Throwable e) {
                    android.util.Log.w("DSHA", "写 API key 备份文件失败（备份继续）: " + e);
                }
            }
            // 文件清单用位置参数（set --）攒，不要攒进字符串再无引号展开 ——
            // 那样 ARGS 里的引号不会被二次解析，tar 收到的是字面量 '工作目录'/.env，
            // 结果 Cannot stat → TAR_FAIL → 备份整个失败（还被报成「环境可能未安装」）。
            // 工作目录名通过 WD 变量传，赋值语境里只需一次单引号转义。
            String script = "cd /root || exit 1\n"
                    + "rm -f .dsha-backup.tar.gz\n"
                    + "[ -d .dsh ] || { echo NO_DSH_DIR; exit 1; }\n"
                    + "WD='" + dshEsc + "'\n"
                    + "set -- .dsh\n"
                    + "[ -f \"$WD/.env\" ] && set -- \"$@\" \"$WD/.env\"\n"
                    + "[ -f dsh-web.log ] && set -- \"$@\" dsh-web.log\n"
                    // 清单与内联插件源码（存在才带，名字固定，不拼接外部输入）
                    + "[ -f .dsha-backup-manifest.json ] && set -- \"$@\" .dsha-backup-manifest.json\n"
                    + "[ -d .dsha-plugin-src ] && set -- \"$@\" .dsha-plugin-src\n"
                    + "echo \"打包: $*\"\n"
                    // 不再 2>/dev/null：tar 的报错正是排查依据（execChecked 会带回输出）
                    // --ignore-failed-read：万一还有漏网的坏符号链接，只跳过它，
                    // 别让整包备份失败（数据本身已丢，留着也恢复不了）
                    // 排除项来自 UserDataPolicy —— 「什么算用户数据」只有那一份定义。
                    //   以前这里硬编码 --exclude='.dsh/.bridge_token'，而重解压那条路自己
                    //   维护另一份判断，两边定义一分裂就出过桥 token 401（现象是悬浮窗不显示
                    //   + agent 工具调用失败，完全看不出是 token）。详见 UserDataPolicy 类注释。
                    + "tar -czf .dsha-backup.tar.gz --ignore-failed-read "
                    + UserDataPolicy.tarExcludeArgs() + "\"$@\" "
                    + "|| { echo TAR_FAIL; exit 1; }\n"
                    + "test -s .dsha-backup.tar.gz || { echo EMPTY; exit 1; }\n"
                    + "echo OK\n";
            // 公开热数据软链自修复（迁移后 .dsh/sessions 等是指向
            // /sdcard/Documents/dshdata 的软链；用户若删了 Documents 目录，
            // 链接悬空，tar 会因 Cannot stat 失败）。先检查并重建：
            // 若公开侧数据还在就重建软链，否则把私有副本扶正（用残留数据）。
            repairDshaSymlinks(c);
            c.getProot().execChecked(script);
            File tmp = new File(c.getProot().getRootfsDir(), "root/.dsha-backup.tar.gz");
            if (!tmp.isFile() || tmp.length() == 0) {
                lastError = "打包文件没生成（rootfs 内 tar 未产出 .dsha-backup.tar.gz）";
                recordError(c, lastError);
                return null;
            }

            String name = fixedName != null
                    ? fixedName
                    : "DSHA-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                            .format(new Date()) + ".tar.gz";
            String path = null;
            try {
                path = Build.VERSION.SDK_INT >= 29
                        ? writeViaMediaStore(ctx, tmp, name, fixedName != null)
                        : writeDirect(tmp, name);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (path == null) {
                lastError = "导出到 Download/DSHA 失败（存储权限、空间不足或 MediaStore 拒绝）";
                recordError(c, lastError);
                return null; // 导出失败：不残留 half 备份
            }
            // 手动备份保留最近 MAX_MANUAL_KEEP 份，删最旧（防无限膨胀）
            if (fixedName == null) pruneOldManual(ctx, name);
            recordError(c, null); // 成功：清掉历史错误
            return path;
        } catch (Exception e) {
            // execChecked 失败会带回 rootfs 内的真实输出（NO_DSH_DIR / TAR_FAIL / tar 报错原文）
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (msg.contains("NO_DSH_DIR")) {
                lastError = "/root/.dsh 不存在：环境确实没装好，或工作目录被改过";
            } else if (msg.contains("TAR_FAIL")) {
                lastError = "rootfs 内打包失败：" + tail(msg, 300);
            } else if (msg.contains("EMPTY")) {
                lastError = "打包产物为空（磁盘可能已满）";
            } else {
                lastError = tail(msg, 300);
            }
            android.util.Log.w("DSHA", "备份失败: " + lastError);
            recordError(c, lastError);
            return null;
        }
        }
    }

    /**
     * 迁移到公开目录后，.dsh 下的 sessions / storages / attachments / settings.yaml
     * 是指向 /sdcard/Documents/dshdata 的软链。用户删除或清空 Documents 目录后，
     * 这些软链悬空 —— tar 备份会 Cannot stat 失败，恢复则找不到数据。
     *
     * 这里在备份前修一遍，规则：
     *  · 软链存在且指向有效目录 → 不动；
     *  · 软链悬空但公开侧数据仍在 → 重建软链（主体在公开目录，符合设计）；
     *  · 软链悬空且公开侧也没了 → 把残留私有数据扶正（有总比没有好，
     *    这是最后一次兜底，避免用户「删了 Documents 就再也备不出」）。
     *
     * 不处理 credentials / profiles / node_modules：前者刻意留私有，
     * 后两者 dsh 自己维护、且公开 FUSE 禁止软链。
     */
    private static void repairDshaSymlinks(HarnessController c) {
        try {
            String script = "cd /root/.dsh 2>/dev/null || exit 0\n"
                    + "PUB=/sdcard/Documents/dshdata\n"
                    + "for name in sessions storages attachments settings.yaml; do\n"
                    + "  if [ -L \"$name\" ]; then\n"          // 是软链
                    + "    tgt=$(readlink \"$name\")\n"
                    + "    if [ -e \"$name\" ]; then continue; fi\n"   // 有效软链，不动
                    + "    if [ -e \"$PUB/$name\" ]; then\n"       // 悬空但公开侧在
                    + "      ln -sf \"$PUB/$name\" \"$name\"; echo REPAIR_LINK_$name\n"
                    + "    else\n"                                 // 全没了，扶正残留
                    + "      echo HEAL_STRAY_$name\n"
                    + "    fi\n"
                    + "  fi\n"
                    + "done\n"
                    + "echo SYMLINK_CHECK_DONE\n";
            c.getProot().execChecked(script);
        } catch (Throwable e) {
            // 修软链失败不该让备份整体失败，只是回到「可能备份不到公开侧数据」的旧行为
            android.util.Log.w("DSHA", "修复 .dsh 软链失败（备份继续）: " + e);
        }
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /** 通用导出：把任意文件放进 Download/DSHA 并返回用户可见路径。
     *  供 3090 桥的 /app/export 用——agent 产出的报告/日志可以一键交到用户手上。 */
    public static String exportToDownloads(Context ctx, File src, String name) {
        try {
            return Build.VERSION.SDK_INT >= 29
                    ? writeViaMediaStore(ctx, src, name, true)
                    : writeDirect(src, name);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "导出到 Download 失败: " + e);
            return null;
        }
    }

    /** 备份前置整理：注入并执行 backup-prepare.py。全程宽容——任何失败都只记日志，
     *  备份本体照常进行（老包格式仍可恢复，只是少了清单与内联插件）。 */
    /** 备份前把 proot 的 .l2s 链实体化，并隔离悬空链。
     *
     *  必须做，否则备份 100% 失败：Android 私有目录禁真硬链接，proot 用
     *  --link2symlink 把 link() 模拟成「目标 → .l2s.<名>.<hash>.tmp0001 → ….0001」，
     *  而它同时劫持了 stat/lstat（为了伪造 st_nlink）。tar 必须 lstat 判断文件类型，
     *  于是遇到这些链就报错 —— 用户机上是 ELOOP（Too many levels of symbolic links），
     *  容器里是 EPERM。cat/cp -L 反而正常，因为 open 不走那条路径。
     *
     *  写入侧已由 fs-write-patch.sh 治本（一律 rename，不再产生新链），这里处理存量。
     *  返回机器可读的一行结果，仅用于日志。 */
    private static void runFlattenL2s(HarnessController c) {
        try {
            String script = c.readAsset("flatten-l2s.py");
            if (script == null || script.isEmpty()) return;
            File dst = new File(c.getProot().getRootfsDir(), "root/.dsha-flatten-l2s.py");
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(),
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String out = c.getProot().execAndRead(
                    "python3 /root/.dsha-flatten-l2s.py --root /root/.dsh 2>&1 | tail -20; "
                            + "rm -f /root/.dsha-flatten-l2s.py", 180_000);
            android.util.Log.i("DSHA", "l2s 实体化: " + (out == null ? "无输出" : out.trim()));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "l2s 实体化失败（备份可能因此失败）: " + e);
        }
    }

    private static void runBackupPrepare(HarnessController c, String workdir) {
        try {
            String script = c.readAsset("backup-prepare.py");
            if (script == null || script.isEmpty()) return;
            File dst = new File(c.getProot().getRootfsDir(), "root/.dsha-backup-prepare.py");
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(),
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String wdEsc = workdir.replace("'", "'\\''");
            String verEsc = c.getVersionNameForUa().replace("'", "");
            String out = c.getProot().execAndRead(
                    "python3 /root/.dsha-backup-prepare.py --app-version '" + verEsc
                            + "' --workdir '" + wdEsc + "' 2>&1; rm -f /root/.dsha-backup-prepare.py",
                    120_000);
            android.util.Log.i("DSHA", "备份前置整理: " + (out == null ? "无输出" : out.trim()));
            // 验证清单是否真的落地。这一步以前只写 logcat，清单没生成也照样打包，
            // 用户拿到的是「老格式」备份 —— 要等到换设备恢复时才发现插件全缺，
            // 那时原设备往往已经不在手边。现在至少让日志能一眼看出问题在哪。
            File man = new File(c.getProot().getRootfsDir(), "root/.dsha-backup-manifest.json");
            if (!man.isFile() || man.length() == 0) {
                c.logActivity("备份清单未生成，这次是老格式（能恢复，但跨设备可能缺插件）");
                android.util.Log.w("DSHA", "备份清单未生成 —— 这次备份会是老格式"
                        + "（能恢复，但跨设备可能缺插件）。前置整理输出："
                        + (out == null ? "无" : out.trim()));
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "备份前置整理失败（不影响备份）: " + e);
        }
    }

    /** Android 10+：MediaStore Downloads 集合，无需存储权限。overwrite=true 时先删同名旧条目。 */    private static String writeViaMediaStore(Context ctx, File src, String name, boolean overwrite) throws Exception {
        if (overwrite) {
            // 删除同名的旧自动备份（MediaStore 同名会新建条目，必须先清旧的）
            try {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                String[] args = {name, Environment.DIRECTORY_DOWNLOADS + "/DSHA/"};
                android.database.Cursor cur = ctx.getContentResolver().query(collection,
                        new String[]{MediaStore.MediaColumns._ID}, sel, args, null);
                if (cur != null) {
                    while (cur.moveToNext()) {
                        long id = cur.getLong(0);
                        ctx.getContentResolver().delete(
                                android.content.ContentUris.withAppendedId(collection, id), null, null);
                    }
                    cur.close();
                }
            } catch (Throwable ignored) {
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSHA");
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return Environment.getExternalStorageDirectory() + "/Download/DSHA/" + name;
    }

    /** Android 9-：直接写公共下载目录（需要 WRITE_EXTERNAL_STORAGE 权限） */
    @SuppressWarnings("deprecation")
    private static String writeDirect(File src, String name) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "DSHA");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File dst = new File(dir, name);
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return dst.getAbsolutePath();
    }

    /** 清理旧手动备份：保留最近 MAX_MANUAL_KEEP 份（不含自动备份文件），删最旧。 */
    private static void pruneOldManual(Context ctx, String justCreated) {
        try {
            java.util.List<android.net.Uri> all = new java.util.ArrayList<>();
            // 查 MediaStore（Android 10+）或直接列目录（Android 9-）
            if (Build.VERSION.SDK_INT >= 29) {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                try (android.database.Cursor cur = ctx.getContentResolver().query(collection,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.DATE_MODIFIED},
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{Environment.DIRECTORY_DOWNLOADS + "/DSHA/"}, null)) {
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            String dn = cur.getString(1);
                            if (dn == null || !dn.startsWith("DSHA-backup-") || !dn.endsWith(".tar.gz")) continue;
                            if (isAutoName(dn)) continue; // 自动备份的两个槽都不动
                            all.add(android.content.ContentUris.withAppendedId(collection, cur.getLong(0)));
                        }
                    }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "DSHA");
                File[] fs = dir.listFiles((d, n) -> n.startsWith("DSHA-backup-") && n.endsWith(".tar.gz")
                        && !isAutoName(n));
                if (fs != null) {
                    java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (int i = MAX_MANUAL_KEEP; i < fs.length; i++) {
                        //noinspection ResultOfMethodCallIgnored
                        fs[i].delete();
                    }
                }
                return;
            }
            // MediaStore：按 DATE_MODIFIED 降序，超出保留数的删最旧
            if (all.size() > MAX_MANUAL_KEEP) {
                java.util.List<Long> times = new java.util.ArrayList<>();
                try (android.database.Cursor cur = ctx.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED},
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{Environment.DIRECTORY_DOWNLOADS + "/DSHA/"}, null)) {
                    if (cur != null) {
                        java.util.Map<Long, Long> id2t = new java.util.HashMap<>();
                        while (cur.moveToNext()) {
                            long id = cur.getLong(0);
                            String dn = null;
                            try (android.database.Cursor c2 = ctx.getContentResolver().query(
                                    android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                                if (c2 != null && c2.moveToFirst()) dn = c2.getString(0);
                            } catch (Throwable ignored) {
                            }
                            if (dn == null || isAutoName(dn)) continue;
                            id2t.put(id, cur.getLong(1));
                        }
                        java.util.List<java.util.Map.Entry<Long, Long>> sorted = new java.util.ArrayList<>(id2t.entrySet());
                        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                        for (int i = MAX_MANUAL_KEEP; i < sorted.size(); i++) {
                            ctx.getContentResolver().delete(
                                    android.content.ContentUris.withAppendedId(
                                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, sorted.get(i).getKey()),
                                    null, null);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
