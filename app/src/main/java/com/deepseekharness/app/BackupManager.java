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
            // 1. rootfs 内打包
            String wd = c.getWorkdir();
            c.getProot().execChecked("cd /root && rm -f .dsha-backup.tar.gz && "
                    + "tar -czf .dsha-backup.tar.gz .dsh " + wd + "/.env dsh-web.log 2>/dev/null; "
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
}
