package com.deepseekharness.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.deepseekharness.app.util.SensitiveData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.UUID;

/**
 * DocumentsProvider：直达 DSHA 后端 Linux 容器核心配置与插件目录（/data/adb/dsha/rootfs/root/.dsh）。
 *
 * <p><b>定位与架构</b>：前后端分离后，DSHA 运行时下沉为 KernelSU / Magisk 原生 Linux chroot 环境，
 * 核心配置、插件源码、会话历史与凭证位于 {@code /data/adb/dsha/rootfs/root/.dsh}。
 * 本 Provider 通过轻量 Root 特权中继与缓存自动回写机制，让 MT 管理器 / 系统 DocumentsUI 无缝直达后端，
 * 支持在 MT 管理器中直接浏览、新建、重命名、删除，以及在线编辑保存配置文件。
 */
public class DshaDocumentsProvider extends DocumentsProvider {

    private static final String TAG = "DshaDocs";

    /** MT 管理器/系统在 DocumentsProvider 列表里看到的根 ID。 */
    private static final String ROOT_ID = "dsha-root";
    /** root 的 document_id：非空串，保证 DocumentsUI / MT 管理器可正常下钻。 */
    private static final String ROOT_DOC_ID = "root";

    /** 后端真实目标物理基准路径（直达 .dsh 核心配置与插件目录）。 */
    public static final String BACKEND_ROOT_PATH = "/data/adb/dsha/rootfs/root/.dsh";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
    };

    private HandlerThread mSyncThread;
    private Handler mSyncHandler;
    private File mCacheDir;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            mCacheDir = new File(ctx.getCacheDir(), "saf_cache");
            if (!mCacheDir.exists()) {
                mCacheDir.mkdirs();
            }
        }
        mSyncThread = new HandlerThread("DshaDocsSync");
        mSyncThread.start();
        mSyncHandler = new Handler(mSyncThread.getLooper());
        Log.i(TAG, "onCreate initialized for backend path: " + BACKEND_ROOT_PATH);
        return true;
    }

    private File getRealFile(String docId) throws FileNotFoundException {
        String rel = (docId == null || docId.isEmpty() || ROOT_DOC_ID.equals(docId)) ? "" : docId;
        if (rel.isEmpty()) {
            return new File(BACKEND_ROOT_PATH);
        }
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        File target = new File(BACKEND_ROOT_PATH, rel);
        try {
            String canonRoot = new File(BACKEND_ROOT_PATH).getCanonicalPath();
            String canonTarget = target.getCanonicalPath();
            if (!canonTarget.equals(canonRoot) && !canonTarget.startsWith(canonRoot + File.separator)) {
                throw new FileNotFoundException("路径越界: " + docId);
            }
            return target;
        } catch (IOException e) {
            throw new FileNotFoundException("非法路径: " + docId);
        }
    }

    private String docIdForPath(String absPath) {
        if (absPath == null || absPath.isEmpty() || absPath.equals(BACKEND_ROOT_PATH) || absPath.equals(BACKEND_ROOT_PATH + "/")) {
            return ROOT_DOC_ID;
        }
        if (absPath.startsWith(BACKEND_ROOT_PATH + "/")) {
            return absPath.substring(BACKEND_ROOT_PATH.length() + 1);
        }
        return absPath;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Log.i(TAG, "queryRoots");
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        String rootTitle = "DSHA";
        String summary = "DSH 核心配置、插件源码与会话数据 (/root/.dsh)";
        // 支持子项遍历、搜索、在根下创建文件/目录、本地存储标记
        int flags = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                | DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
                | DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                | DocumentsContract.Root.FLAG_LOCAL_ONLY;

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID);
        row.add(DocumentsContract.Root.COLUMN_TITLE, rootTitle);
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, summary);
        row.add(DocumentsContract.Root.COLUMN_FLAGS, flags);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, new String[]{"*/*"});
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        Log.i(TAG, "queryDocument docId=" + documentId);
        File realFile = getRealFile(documentId);
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));

        if (ROOT_DOC_ID.equals(documentId) || documentId == null || documentId.isEmpty()) {
            includeItem(result, ROOT_DOC_ID, "DSHA", true, 0, System.currentTimeMillis());
            return result;
        }

        StatResult st = statFile(realFile.getAbsolutePath());
        if (st == null) {
            throw new FileNotFoundException("文件不存在: " + documentId);
        }
        includeItem(result, documentId, realFile.getName(), st.isDir, st.size, st.mtime);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        Log.i(TAG, "queryChildDocuments parent=" + parentDocumentId);
        File parent = getRealFile(parentDocumentId);
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));

        String parentPath = parent.getAbsolutePath();
        // 健壮列出子项（含隐藏文件，排除 . 和 ..，单个批量命令毫秒级返回）
        String cmd = "DIR=" + escapeShellArg(parentPath) + "; "
                + "for f in \"$DIR\"/* \"$DIR\"/.*; do "
                + "  [ -e \"$f\" ] || continue; "
                + "  bn=$(basename \"$f\"); "
                + "  [ \"$bn\" = \".\" ] || [ \"$bn\" = \"..\" ] && continue; "
                + "  toybox stat -c \"%n|%s|%Y|%f\" \"$f\" 2>/dev/null; "
                + "done";

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length >= 4) {
                        String fullPath = parts[0];
                        long size = parseLongSafe(parts[1], 0);
                        long mtime = parseLongSafe(parts[2], 0) * 1000L;
                        String modeHex = parts[3];
                        int mode = parseIntHexSafe(modeHex, 0);
                        boolean isDir = (mode & 0x4000) != 0;

                        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                        String docId = docIdForPath(fullPath);
                        includeItem(result, docId, name, isDir, size, mtime);
                    }
                }
            }
            p.waitFor();
        } catch (Throwable e) {
            Log.e(TAG, "queryChildDocuments error: " + e.getMessage(), e);
        }

        scheduleCacheCleanup();

        Bundle extras = new Bundle();
        extras.putBoolean(DocumentsContract.EXTRA_LOADING, false);
        result.setExtras(extras);
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        if (!ROOT_ID.equals(rootId) || query == null || query.trim().isEmpty()) return null;
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        String cleanQuery = query.trim().toLowerCase();

        String cmd = "toybox find " + escapeShellArg(BACKEND_ROOT_PATH) + " -iname " + escapeShellArg("*" + cleanQuery + "*")
                + " -exec toybox stat -c \"%n|%s|%Y|%f\" {} + 2>/dev/null | head -n 100";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length >= 4) {
                        String fullPath = parts[0];
                        long size = parseLongSafe(parts[1], 0);
                        long mtime = parseLongSafe(parts[2], 0) * 1000L;
                        String modeHex = parts[3];
                        int mode = parseIntHexSafe(modeHex, 0);
                        boolean isDir = (mode & 0x4000) != 0;

                        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                        String docId = docIdForPath(fullPath);
                        includeItem(result, docId, name, isDir, size, mtime);
                    }
                }
            }
            p.waitFor();
        } catch (Throwable e) {
            Log.e(TAG, "querySearchDocuments error: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = getRealFile(parentDocumentId);
            File child = getRealFile(documentId);
            return child.getCanonicalPath().startsWith(parent.getCanonicalPath() + File.separator);
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File f = getRealFile(documentId);
        StatResult st = statFile(f.getAbsolutePath());
        if (st != null && st.isDir) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        return getMimeType(f.getName());
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        return super.openDocumentThumbnail(documentId, sizeHint, signal);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        Log.i(TAG, "openDocument docId=" + documentId + ", mode=" + mode);
        File realFile = getRealFile(documentId);
        StatResult st = statFile(realFile.getAbsolutePath());
        if (st != null && st.isDir) {
            throw new FileNotFoundException("目录不能直接打开: " + documentId);
        }

        File cacheDir = mCacheDir;
        if (cacheDir == null) {
            Context ctx = getContext();
            cacheDir = ctx != null ? ctx.getCacheDir() : new File("/data/local/tmp");
        }
        File tempFile = new File(cacheDir, "dsh_" + UUID.randomUUID().toString().substring(0, 8) + "_" + realFile.getName());

        boolean isWrite = mode.contains("w") || mode.contains("W") || mode.contains("rw");

        if (st != null) {
            // 原文件存在：以 Root 复制至私有缓存以供安全打开与编辑
            String cpCmd = "cp -f " + escapeShellArg(realFile.getAbsolutePath()) + " " + escapeShellArg(tempFile.getAbsolutePath())
                    + " && chmod 600 " + escapeShellArg(tempFile.getAbsolutePath());
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cpCmd});
                p.waitFor();
            } catch (Throwable e) {
                throw new FileNotFoundException("读取文件失败: " + e.getMessage());
            }
        } else if (isWrite) {
            // 原文件不存在但模式为写入：创建本地空文件
            try {
                if (!tempFile.createNewFile()) {
                    throw new IOException("无法创建临时缓存文件");
                }
            } catch (IOException e) {
                throw new FileNotFoundException("无法创建新文件: " + e.getMessage());
            }
        } else {
            throw new FileNotFoundException("文件不存在: " + documentId);
        }

        int pfdMode = ParcelFileDescriptor.parseMode(mode);

        if (!isWrite) {
            try {
                return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (IOException e) {
                throw new FileNotFoundException(SensitiveData.redact(String.valueOf(e)));
            }
        }

        // 写入模式：注册 OnCloseListener，用户在 MT 管理器中保存退出后自动同步写回真实后端文件
        try {
            return ParcelFileDescriptor.open(tempFile, pfdMode, mSyncHandler, new ParcelFileDescriptor.OnCloseListener() {
                @Override
                public void onClose(IOException ioException) {
                    if (ioException == null && tempFile.exists()) {
                        Log.i(TAG, "Syncing modified file back to: " + realFile.getAbsolutePath());
                        String syncCmd = "mkdir -p " + escapeShellArg(realFile.getParent()) + " 2>/dev/null; "
                                + "cp -f " + escapeShellArg(tempFile.getAbsolutePath()) + " " + escapeShellArg(realFile.getAbsolutePath());
                        try {
                            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", syncCmd});
                            p.waitFor();
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to sync back file: " + t.getMessage(), t);
                        }
                    }
                    tempFile.delete();
                }
            });
        } catch (IOException e) {
            throw new FileNotFoundException(SensitiveData.redact(String.valueOf(e)));
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        Log.i(TAG, "createDocument parent=" + parentDocumentId + ", name=" + displayName + ", mime=" + mimeType);
        File parent = getRealFile(parentDocumentId);
        File target = new File(parent, displayName);
        boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);

        String cmd = isDir ? ("mkdir -p " + escapeShellArg(target.getAbsolutePath()))
                           : ("touch " + escapeShellArg(target.getAbsolutePath()));
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (p.waitFor() != 0) {
                throw new FileNotFoundException("创建失败");
            }
            return docIdForPath(target.getAbsolutePath());
        } catch (Throwable e) {
            throw new FileNotFoundException("创建失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Log.i(TAG, "deleteDocument docId=" + documentId);
        if (ROOT_DOC_ID.equals(documentId)) {
            throw new FileNotFoundException("禁止删除根目录");
        }
        File target = getRealFile(documentId);
        String cmd = "rm -rf " + escapeShellArg(target.getAbsolutePath());
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (p.waitFor() != 0) {
                throw new FileNotFoundException("删除失败");
            }
        } catch (Throwable e) {
            throw new FileNotFoundException("删除失败: " + e.getMessage());
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        Log.i(TAG, "renameDocument docId=" + documentId + ", newName=" + displayName);
        if (ROOT_DOC_ID.equals(documentId)) {
            throw new FileNotFoundException("禁止重命名根目录");
        }
        File src = getRealFile(documentId);
        File dst = new File(src.getParentFile(), displayName);
        String cmd = "mv " + escapeShellArg(src.getAbsolutePath()) + " " + escapeShellArg(dst.getAbsolutePath());
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (p.waitFor() != 0) {
                throw new FileNotFoundException("重命名失败");
            }
            return docIdForPath(dst.getAbsolutePath());
        } catch (Throwable e) {
            throw new FileNotFoundException("重命名失败: " + e.getMessage());
        }
    }

    private void scheduleCacheCleanup() {
        if (mSyncHandler != null) {
            mSyncHandler.post(() -> {
                if (mCacheDir == null || !mCacheDir.isDirectory()) return;
                long now = System.currentTimeMillis();
                File[] files = mCacheDir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (now - f.lastModified() > 1800_000L) { // 清理超过 30 分钟的临时缓存
                        f.delete();
                    }
                }
            });
        }
    }

    private void includeItem(MatrixCursor result, String docId, String name, boolean isDir, long size, long lastModified) {
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, isDir ? DocumentsContract.Document.MIME_TYPE_DIR : getMimeType(name));
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified);
        row.add(DocumentsContract.Document.COLUMN_SIZE, isDir ? 0 : size);

        int flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                | DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        if (isDir) {
            flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        }
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
    }

    private static class StatResult {
        final long size;
        final long mtime;
        final boolean isDir;
        StatResult(long size, long mtime, boolean isDir) {
            this.size = size;
            this.mtime = mtime;
            this.isDir = isDir;
        }
    }

    private static StatResult statFile(String path) {
        String cmd = "toybox stat -c \"%s|%Y|%f\" " + escapeShellArg(path) + " 2>/dev/null";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    String[] parts = line.trim().split("\\|");
                    if (parts.length >= 3) {
                        long size = parseLongSafe(parts[0], 0);
                        long mtime = parseLongSafe(parts[1], 0) * 1000L;
                        int mode = parseIntHexSafe(parts[2], 0);
                        boolean isDir = (mode & 0x4000) != 0;
                        return new StatResult(size, mtime, isDir);
                    }
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private static long parseLongSafe(String str, long def) {
        try {
            return Long.parseLong(str.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int parseIntHexSafe(String str, int def) {
        try {
            return Integer.parseInt(str.trim(), 16);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String getMimeType(String name) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name);
        if (ext != null && !ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (mime != null) return mime;
        }
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "text/yaml";
            if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
            if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "text/x-shellscript";
            if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
            if (lower.endsWith(".py")) return "text/x-python";
            if (lower.endsWith(".zstd")) return "application/zstd";
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return "application/gzip";
        }
        return "application/octet-stream";
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null && projection.length > 0 ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null && projection.length > 0 ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }
}
