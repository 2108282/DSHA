package com.deepseekharness.app;
import com.deepseekharness.app.util.Compat;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.TarGzipExtractor;
import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 备份与恢复：rootfs 内打包 .dsh → 导出到 Download/DSHA → 恢复时解压 + restore-merge.py 合并。
 * 每一步都有验证：备份后验证归档条目数与大小、导出后验证文件大小一致、恢复后验证 .dsh 落地。
 */
public final class BackupManager {

    private BackupManager() {
    }

    private static final Object BACKUP_LOCK = new Object();
    public static final String LATEST_BACKUP_NAME = "DSHA-backup-latest.tar.gz";
    private static volatile String lastError = "";

    public static String lastError() {
        return SensitiveData.redact(lastError);
    }

    public static String backupToExternal(Context ctx, HarnessController c) {
        return backup(ctx, c, BackupScope.FULL);
    }

    public static String backupToExternal(Context ctx, HarnessController c, int scope) {
        if (scope != BackupScope.FULL && scope != BackupScope.SESSIONS
                && scope != BackupScope.SETTINGS && scope != BackupScope.PLUGINS) {
            scope = BackupScope.FULL;
        }
        return backup(ctx, c, scope);
    }

    // ==================== 备份 ====================

    private static String backup(Context ctx, HarnessController c, int scope) {
        synchronized (BACKUP_LOCK) {
            lastError = "";
            try {
                if (!c.proot().isEnvironmentReady()) {
                    lastError = "环境未就绪，无法备份（请先启动一次）";
                    return null;
                }
                // 1. 写 manifest（含 scope，恢复端靠它决定合并范围）
                File manifest = new File(c.proot().getRootfsDir(), "root/.dsha-backup-manifest.json");
                if (manifest.getParentFile() != null) manifest.getParentFile().mkdirs();
                Compat.write(manifest, manifestJson(scope).getBytes(java.nio.charset.StandardCharsets.UTF_8));

                // 2. rootfs 内打包 + 验证条目数
                String out = c.proot().execChecked(buildTarScript(scope));
                manifest.delete();

                File tmp = new File(c.proot().getRootfsDir(), "root/.dsha-backup.tar.gz");
                if (!tmp.isFile() || tmp.length() == 0) {
                    lastError = "打包产物未生成（tar 没产出 .dsha-backup.tar.gz）";
                    return null;
                }
                // 验证：归档里确实有内容
                int entries = parseEntries(out);
                if (entries <= 0) {
                    lastError = "打包产物为空（磁盘可能已满，或该范围没有内容）";
                    return null;
                }

                // 3. 导出到 Download/DSHA（原子发布）
                String path = exportArchive(ctx, tmp, LATEST_BACKUP_NAME);
                tmp.delete();
                if (path == null) {
                    lastError = "导出到 Download/DSHA 失败（存储权限或空间不足）";
                    return null;
                }

                // 4. 验证导出文件确实存在且非空
                File exported = resolveDownloadFile(ctx, LATEST_BACKUP_NAME);
                if (exported == null || !exported.isFile() || exported.length() == 0) {
                    lastError = "导出后验证失败：Download/DSHA 里没有找到有效备份文件";
                    return null;
                }
                return path;
            } catch (Exception e) {
                lastError = classifyError(e);
                return null;
            }
        }
    }

    private static String manifestJson(int scope) {
        return "{\"formatVersion\":1,\"scope\":\"" + BackupScope.id(scope)
                + "\",\"appVersion\":\"0.2.0-rewrite\",\"dshVersion\":\"0.1.2-alpha.4\","
                + "\"createdAt\":\"" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date())
                + "\"}";
    }

    private static String buildTarScript(int scope) {
        String[] paths = BackupScope.dshPaths(scope);
        StringBuilder sb = new StringBuilder();
        sb.append("cd /root || exit 1\n")
          .append("rm -f .dsha-backup.tar.gz\n")
          .append("[ -d .dsh ] || { echo NO_DSH_DIR; exit 1; }\n");

        if (paths.length == 0) {
            // 全量备份：动态扫描 workspace.json，把用户工作区的项目文件收集进 .dsha-workspaces 一同归档
            sb.append("python3 -c '\n")
              .append("import json, os, shutil\n")
              .append("ws_stage = \"/root/.dsha-workspaces\"\n")
              .append("shutil.rmtree(ws_stage, ignore_errors=True)\n")
              .append("ws_file = \"/root/.dsh/storages/workspace.json\"\n")
              .append("meta = {}\n")
              .append("if os.path.isfile(ws_file):\n")
              .append("    try:\n")
              .append("        with open(ws_file, \"r\", encoding=\"utf-8\") as f:\n")
              .append("            data = json.load(f)\n")
              .append("        for wid, rec in (data.get(\"tables\", {}).get(\"workspaces\", {}) or {}).items():\n")
              .append("            p = (rec or {}).get(\"path\")\n")
              .append("            t = (rec or {}).get(\"title\", \"工作区\")\n")
              .append("            if not p or not os.path.isdir(p) or p in (\"/\", \"/root\", \"/sdcard\", \"/storage/emulated/0\", \"/sdcard/Download\"):\n")
              .append("                continue\n")
              .append("            dst = os.path.join(ws_stage, wid)\n")
              .append("            os.makedirs(dst, exist_ok=True)\n")
              .append("            meta[wid] = {\"title\": t, \"orig_path\": p}\n")
              .append("            ign = lambda d, files: {f for f in files if f in (\"node_modules\", \".git\", \"__pycache__\", \".pnpm-store\", \"dist\", \".next\", \".cache\", \".dsh\")}\n")
              .append("            shutil.copytree(p, dst, dirs_exist_ok=True, ignore=ign)\n")
              .append("        if meta:\n")
              .append("            with open(os.path.join(ws_stage, \"meta.json\"), \"w\", encoding=\"utf-8\") as mf:\n")
              .append("                json.dump(meta, mf, ensure_ascii=False)\n")
              .append("    except Exception:\n")
              .append("        pass\n")
              .append("' 2>/dev/null || true\n");
        }

        sb.append("set --\n");
        if (paths.length == 0) {
            sb.append("set -- .dsh\n")
              .append("[ -d .dsha-workspaces ] && set -- \"$@\" .dsha-workspaces\n");
        } else {
            for (String p : paths) {
                sb.append("[ -e ").append(ShellQuote.arg(p)).append(" ] && set -- \"$@\" ")
                  .append(ShellQuote.arg(p)).append("\n");
            }
        }
        sb.append("[ -f .dsha-backup-manifest.json ] && set -- \"$@\" .dsha-backup-manifest.json\n")
          .append("[ $# -gt 0 ] || { echo NOTHING_TO_PACK; exit 1; }\n")
          .append("echo \"打包: $*\"\n")
          .append("tar -czf .dsha-backup.tar.gz --ignore-failed-read \"$@\" || { echo TAR_FAIL; exit 1; }\n")
          .append("rm -rf .dsha-workspaces\n")
          .append("test -s .dsha-backup.tar.gz || { echo EMPTY; exit 1; }\n")
          .append("CNT=$(tar -tzf .dsha-backup.tar.gz 2>/dev/null | wc -l)\n")
          .append("echo \"VERIFY_ENTRIES=$CNT\"\n")
          .append("echo OK\n");
        return sb.toString();
    }

    private static int parseEntries(String out) {
        if (out == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("VERIFY_ENTRIES=(\\d+)").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String classifyError(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.contains("NO_DSH_DIR")) return "/root/.dsh 不存在：环境没装好或工作目录被改过";
        if (msg.contains("TAR_FAIL")) return "rootfs 内打包失败：" + tail(msg);
        if (msg.contains("EMPTY")) return "打包产物为空（磁盘可能已满）";
        if (msg.contains("NOTHING_TO_PACK")) return "这个范围里没有可备份的内容（比如还没有对话）";
        return tail(msg);
    }

    private static String tail(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= 300 ? s : "…" + s.substring(s.length() - 300);
    }

    // ==================== 导出 ====================

    private static String exportArchive(Context ctx, File src, String name) throws Exception {
        // 拥有所有文件访问权限（Android 11+ MANAGE_EXTERNAL_STORAGE）或系统低于 Android 10 时，直接文件写入最可靠、绝无重命名冲突
        if (Build.VERSION.SDK_INT < 29 || (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())) {
            String direct = writeDirect(src, name);
            if (direct != null) return direct;
        }
        // 走 MediaStore 前先清理同名旧记录，防止系统重命名为 (1)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                ctx.getContentResolver().delete(collection,
                        MediaStore.MediaColumns.DISPLAY_NAME + " = ?", new String[]{name});
            } catch (Throwable ignored) {
            }
            return writeViaMediaStore(ctx, src, name);
        }
        return writeDirect(src, name);
    }

    @android.annotation.TargetApi(29)
    private static String writeViaMediaStore(Context ctx, File src, String name) throws Exception {
        final String relPath = Environment.DIRECTORY_DOWNLOADS + "/DSHA";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "." + name + ".tmp-" + UUID.randomUUID());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("MediaStore 无法创建条目");
        boolean published = false;
        try {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("MediaStore 无法打开输出");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (ctx.getContentResolver().update(uri, publish, null, null) != 1) {
                throw new java.io.IOException("MediaStore 无法发布");
            }
            published = true;
            return Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/DSHA/" + name;
        } finally {
            if (!published) {
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) { }
            }
        }
    }

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

    private static File resolveDownloadFile(Context ctx, String name) {
        try {
            File f = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "DSHA/" + name);
            if (f.isFile()) return f;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 把 Download/DSHA 下的备份拷进 App 缓存，返回可读副本。
     * 直接 File 读在 scoped storage 下会 EACCES（文件 owner 是 media_rw），
     * 先用 MediaStore 的 content uri 打开。拷进缓存也保证恢复期间原文件被移动/删除也不影响。
     */
    private static File copyToAppCache(Context ctx, File backup) throws Exception {
        // 能直接读（旧设备/授权过）就直接用原文件，省一次拷贝
        try (FileInputStream probe = new FileInputStream(backup)) {
            return backup;
        } catch (Exception directFailed) {
            // 走 MediaStore
            String name = backup.getName();
            android.database.Cursor cur = null;
            try {
                // 用 Files collection：tar.gz 不被 Downloads collection 索引（findLatestBackup 已踩）
                // Android 6-10 没有 MediaStore.VOLUME_EXTERNAL（API 29），退回字面量 "external"
                Uri collection = MediaStore.Files.getContentUri(
                        android.os.Build.VERSION.SDK_INT >= 29
                                ? MediaStore.VOLUME_EXTERNAL : "external");
                cur = ctx.getContentResolver().query(collection,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{"%DSHA%"}, null);
                Uri hit = null;
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String n = cur.getString(1);
                        if (n != null && n.equals(name)) {
                            long id = cur.getLong(0);
                            hit = MediaStore.Files.getContentUri(
                                    android.os.Build.VERSION.SDK_INT >= 29
                                            ? MediaStore.VOLUME_EXTERNAL : "external")
                                    .buildUpon().appendPath(String.valueOf(id)).build();
                            break;
                        }
                    }
                }
                if (hit == null) throw new java.io.IOException(
                        "MediaStore 里没找到 " + name + "（Download/DSHA 下）");
                File cache = new File(ctx.getCacheDir(), "restore-" + name);
                try (InputStream in = ctx.getContentResolver().openInputStream(hit);
                     OutputStream out = new FileOutputStream(cache)) {
                    if (in == null) throw new java.io.IOException("无法打开备份的 content uri");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                return cache;
            } finally {
                if (cur != null) cur.close();
            }
        }
    }

    // ==================== 恢复 ====================

    /**
     * 从归档恢复：宽松解压到 stage → restore-merge.py 合并 → 验证 .dsh 落地。
     * 返回人话报告；失败抛异常（带清晰原因）。
     */
    /**
     * 从归档恢复（SAF content uri 版）：宽松解压到 stage → restore-merge.py 合并 → 验证 .dsh 落地。
     * 返回人话报告；失败抛异常（带清晰原因）。
     * 用 content uri 读，绕开 scoped storage 对 Download/DSHA 的 EACCES 与 MediaStore 视图问题。
     */
    public static String restoreFromBackup(Context ctx, HarnessController c, Uri backupUri)
            throws Exception {
        File rootDir = c.proot().getRootfsDir();
        // 0. 把 SAF 授权的内容读进 rootfs 中转
        File src = new File(rootDir, "root/.dsha-restore-src.tar.gz");
        try (InputStream in = ctx.getContentResolver().openInputStream(backupUri);
             FileOutputStream out = new FileOutputStream(src)) {
            if (in == null) throw new java.io.IOException("无法打开所选备份文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return restoreFromStaged(ctx, c, src);
    }

    /** 从归档恢复（本地 File 版，MediaStore 拷贝兜底后调用）。 */
    public static String restoreFromBackup(Context ctx, HarnessController c, File backup)
            throws Exception {
        File rootDir = c.proot().getRootfsDir();
        // scoped storage：Download/DSHA 的文件 owner 是 media_rw，App 直接 File 读会 EACCES。
        //    先用 MediaStore 把备份拷进 App 缓存再恢复。
        File readable = copyToAppCache(ctx, backup);
        File src = new File(rootDir, "root/.dsha-restore-src.tar.gz");
        try (FileInputStream in = new FileInputStream(readable);
             FileOutputStream out = new FileOutputStream(src)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return restoreFromStaged(ctx, c, src);
    }

    /** 共享的恢复执行：src 已是 rootfs 内的归档副本。 */
    private static String restoreFromStaged(Context ctx, HarnessController c, File src)
            throws Exception {
        File rootDir = c.proot().getRootfsDir();
        // 2. 宽松解压到 stage
        File stage = new File(rootDir, "root/.dsha-restore-stage");
        deleteRecursively(stage);
        stage.mkdirs();
        TarGzipExtractor.extract(src, stage);

        // 3. 注入 restore-merge.py 并执行（先确保 python3 可用）
        if (!c.proot().ensureBundledPython()) {
            throw new java.io.IOException("无法安装内置 Python3（restore-merge.py 需要）");
        }
        String script = c.readAsset("restore-merge.py");
        if (script == null || script.isEmpty()) throw new java.io.IOException("restore-merge.py 缺失");
        File sf = new File(rootDir, "root/.dsha-restore-merge.py");
        Compat.write(sf, script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String workdir = c.config().getWorkdir();
        String wd = workdir == null || workdir.isEmpty() ? "deepseek-harness" : workdir;
        String out = c.proot().execAndReadWithProot(
                "P=$(command -v python3 || command -v python); "
                        + "if [ -n \"$P\" ]; then \"$P\" /root/.dsha-restore-merge.py"
                        + " --stage /root/.dsha-restore-stage --root /root --workdir " + ShellQuote.arg(wd)
                        + " 2>&1; else echo NO_PYTHON; fi; "
                        + "rm -f /root/.dsha-restore-merge.py",
                240_000);

        // 4. 验证恢复结果
        File dsh = new File(rootDir, "root/.dsh");
        boolean committed = out != null && out.contains("RESTORE_DSH_COMMITTED");
        boolean ok = out != null && (out.contains("RESTORE_OK") || out.contains("RESTORE_PARTIAL"));
        if (!dsh.isDirectory()) {
            throw new java.io.IOException("恢复后 .dsh 不存在（合并失败）：\n" + tail(out));
        }
        if (!ok && !committed) {
            throw new java.io.IOException("恢复未确认成功（restore-merge.py 未输出 RESTORE_OK/PARTIAL）：\n" + tail(out));
        }
        // 5. 验证 .dsh 里确有内容（不是空壳）
        int sessionCount = countSessions(c);
        return "恢复完成（" + (committed ? "已提交" : "部分恢复") + "）"
                + "\n会话目录数：" + sessionCount
                + "\n\n" + tail(out);
    }

    private static int countSessions(HarnessController c) {
        try {
            File sessions = new File(c.proot().getRootfsDir(), "root/.dsh/sessions");
            if (!sessions.isDirectory()) return 0;
            String[] children = sessions.list();
            return children == null ? 0 : children.length;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ==================== 通用导出（3090 /app/export 用） ====================

    public static String exportToDownloads(Context ctx, File src, String name) {
        try {
            return exportArchive(ctx, src, name);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "导出失败: " + SensitiveData.redact(String.valueOf(e)));
            return null;
        }
    }

    /** 找到最近一次备份文件（返回可直接读的 File，找不到返回 null）。 */
    public static File findLatestBackup(Context ctx) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
                // 文件名可能是 latest 或 MediaStore 冲突重命名的 "latest (1)"，用前缀匹配
                String sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
                try (android.database.Cursor cur = ctx.getContentResolver().query(collection,
                        new String[]{MediaStore.MediaColumns._ID}, sel,
                        new String[]{"DSHA-backup-latest%"}, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        Uri uri = android.content.ContentUris.withAppendedId(collection, cur.getLong(0));
                        File tmp = new File(ctx.getCacheDir(), "restore-backup.tar.gz");
                        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                             FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        return tmp;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        File f = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "DSHA/" + LATEST_BACKUP_NAME);
        return f.isFile() ? f : null;
    }
}
