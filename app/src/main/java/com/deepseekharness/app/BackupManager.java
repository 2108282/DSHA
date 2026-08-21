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
    private static String backup(Context ctx, HarnessController c, String fixedName) {
        try {
            // 1. rootfs 内打包（wd 单引号包裹 + 内部 ' 转义防注入；.dsh 缺失时
            //    tar 会报错，但 test -s 兜底：有部分内容就算成功）
            String wd = c.getWorkdir().replace("'", "'\\''");
            c.getProot().execChecked("cd /root && rm -f .dsha-backup.tar.gz && "
                    + "tar -czf .dsha-backup.tar.gz .dsh '" + wd + "'/.env dsh-web.log 2>/dev/null; "
                    + "test -s .dsha-backup.tar.gz && echo OK || echo EMPTY");
            File tmp = new File(c.getProot().getRootfsDir(), "root/.dsha-backup.tar.gz");
            if (!tmp.isFile() || tmp.length() == 0) return null;

            String name = fixedName != null
                    ? fixedName
                    : "DSHA-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                            .format(new Date()) + ".tar.gz";
            String path = Build.VERSION.SDK_INT >= 29
                    ? writeViaMediaStore(ctx, tmp, name, fixedName != null)
                    : writeDirect(tmp, name);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            // 手动备份保留最近 MAX_MANUAL_KEEP 份，删最旧（防无限膨胀）
            if (fixedName == null && path != null) pruneOldManual(ctx, name);
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    /** Android 10+：MediaStore Downloads 集合，无需存储权限。overwrite=true 时先删同名旧条目。 */
    private static String writeViaMediaStore(Context ctx, File src, String name, boolean overwrite) throws Exception {
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
