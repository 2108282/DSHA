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

    /** 自动备份固定文件名（自动覆盖上一个自动备份；与手动备份独立） */
    public static final String AUTO_BACKUP_NAME = "DSHA-backup-auto.tar.gz";
    /** 手动备份最多保留份数（超出删最旧，防 Download/DSHA 无限膨胀） */
    private static final int MAX_MANUAL_KEEP = 10;

    /** 执行备份并导出，返回外部存储中的完整路径；失败返回 null */
    public static String backupToExternal(Context ctx, HarnessController c) {
        return backup(ctx, c, null);
    }

    /** 自动备份：固定文件名，自动覆盖上一个自动备份（与手动备份独立，手动每次保留） */
    public static String backupToExternalAuto(Context ctx, HarnessController c) {
        return backup(ctx, c, AUTO_BACKUP_NAME);
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
                    + "tar -czf .dsha-backup.tar.gz --ignore-failed-read \"$@\" "
                    + "|| { echo TAR_FAIL; exit 1; }\n"
                    + "test -s .dsha-backup.tar.gz || { echo EMPTY; exit 1; }\n"
                    + "echo OK\n";
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
                            if (AUTO_BACKUP_NAME.equals(dn)) continue; // 自动备份不动
                            all.add(android.content.ContentUris.withAppendedId(collection, cur.getLong(0)));
                        }
                    }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "DSHA");
                File[] fs = dir.listFiles((d, n) -> n.startsWith("DSHA-backup-") && n.endsWith(".tar.gz")
                        && !AUTO_BACKUP_NAME.equals(n));
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
                            if (dn == null || AUTO_BACKUP_NAME.equals(dn)) continue;
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
