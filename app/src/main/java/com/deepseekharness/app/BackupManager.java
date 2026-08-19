package com.deepseekharness.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 备份到外部存储（Download/DSHA/）：
 * 1) 手动全量备份：rootfs 内打包 .dsh（配置+对话记录）+ .env + 日志 → 拷贝到公共下载目录。
 * 2) 升级迁移：版本变化时自动备份（autoBackupForUpgrade），并在全新/丢数据时提供恢复
 *    （findLatestSnapshot + restoreSnapshotToRootfs）。
 * Android 10+ 走 MediaStore（无需权限）；Android 9- 直接写公共目录。
 */
public final class BackupManager {

    private static final String DIR = "DSHA";

    private BackupManager() {
    }

    // ================= 手动 / 迁移备份 =================

    /** 执行备份并导出，返回外部存储中的完整路径；失败返回 null */
    public static String backupToExternal(Context ctx, HarnessController c) {
        File tmp = packSnapshot(c);
        if (tmp == null) return null;
        String name = "DSHA-backup-" + ts() + ".tar.gz";
        String path = writeOut(ctx, tmp, name);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return path;
    }

    /** 升级迁移自动备份：版本 from → to 变化时调用，产出独立命名的快照 */
    public static String autoBackupForUpgrade(Context ctx, HarnessController c,
                                              String from, String to) {
        File tmp = packSnapshot(c);
        if (tmp == null) return null;
        String name = "DSHA-migration-" + from + "-to-" + to + "-" + ts() + ".tar.gz";
        String path = writeOut(ctx, tmp, name);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return path;
    }

    /** rootfs 内打包 .dsh + .env + 日志，返回临时文件；失败返回 null */
    private static File packSnapshot(HarnessController c) {
        try {
            String wd = c.getWorkdir();
            c.getProot().execChecked("cd /root && rm -f .dsha-backup.tar.gz && "
                    + "tar -czf .dsha-backup.tar.gz .dsh " + wd + "/.env dsh-web.log 2>/dev/null; "
                    + "test -s .dsha-backup.tar.gz && echo OK || echo EMPTY");
            File tmp = new File(c.getProot().getRootfsDir(), "root/.dsha-backup.tar.gz");
            if (!tmp.isFile() || tmp.length() == 0) return null;
            return tmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 恢复 =================

    /**
     * 在 Download/DSHA 找最新一份备份/迁移快照。
     * 返回引用串：MediaStore 条目 = "uri:<content://…>"，直接文件 = "file:<绝对路径>"；没有则返回 null。
     */
    public static String findLatestSnapshot(Context ctx) {
        List<Snapshot> all = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor cur = ctx.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATE_ADDED
                    },
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                            + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"DSHA-backup-%.tar.gz", "DSHA-migration-%.tar.gz"},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        long id = cur.getLong(0);
                        String name = cur.getString(1);
                        long added = cur.getLong(2);
                        all.add(new Snapshot(name,
                                "uri:" + MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                                        .appendEncodedPath(String.valueOf(id)).toString(),
                                added));
                    }
                }
            } catch (Exception ignored) {
                // 查不到（无权限/清单差异）时落回直接扫描
            }
        }
        File dir = directDownloadDir();
        if (dir != null) {
            File[] files = dir.listFiles((d, n) ->
                    (n.startsWith("DSHA-backup-") || n.startsWith("DSHA-migration-"))
                            && n.endsWith(".tar.gz"));
            if (files != null) {
                for (File f : files) {
                    all.add(new Snapshot(f.getName(), "file:" + f.getAbsolutePath(), f.lastModified()));
                }
            }
        }
        if (all.isEmpty()) return null;
        Collections.sort(all, (a, b) -> Long.compare(b.time, a.time));
        return all.get(0).ref;
    }

    /** 把快照恢复到 rootfs /root（.dsh/.env/日志与打包时同路径还原） */
    public static boolean restoreSnapshotToRootfs(Context ctx, HarnessController c, String ref) {
        try {
            File target = new File(c.getProot().getRootfsDir(), "root/.dsha-restore.tar.gz");
            try (InputStream in = openSnapshotStream(ctx, ref);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null || out == null) return false;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            String r = c.getProot().execAndRead(
                    "cd /root && tar -xzf .dsha-restore.tar.gz && "
                            + "rm -f .dsha-restore.tar.gz && echo OK");
            return r != null && r.contains("OK");
        } catch (Exception e) {
            return false;
        }
    }

    private static InputStream openSnapshotStream(Context ctx, String ref) throws Exception {
        if (ref.startsWith("uri:")) {
            return ctx.getContentResolver().openInputStream(Uri.parse(ref.substring(4)));
        }
        if (ref.startsWith("file:")) {
            return new FileInputStream(ref.substring(5));
        }
        return new FileInputStream(ref);
    }

    // ================= 存储写入 =================

    /** Android 10+：MediaStore Downloads 集合，无需存储权限 */
    private static String writeViaMediaStore(Context ctx, File src, String name) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return Environment.getExternalStorageDirectory() + "/Download/" + DIR + "/" + name;
    }

    /** Android 9-：直接写公共下载目录（需要 WRITE_EXTERNAL_STORAGE 权限） */
    @SuppressWarnings("deprecation")
    private static String writeDirect(File src, String name) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DIR);
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

    /** 统一出口：把 rootfs 内打包好的文件写到外部 Download/DSHA，返回路径 */
    private static String writeOut(Context ctx, File tmp, String name) {
        try {
            return Build.VERSION.SDK_INT >= 29
                    ? writeViaMediaStore(ctx, tmp, name)
                    : writeDirect(tmp, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static File directDownloadDir() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DIR);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    private static String ts() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    /** 快照条目（合并 MediaStore + 直接扫描，按时间倒序取最新） */
    private static final class Snapshot {
        final String name;
        final String ref;
        final long time;

        Snapshot(String name, String ref, long time) {
            this.name = name;
            this.ref = ref;
            this.time = time;
        }
    }
}
