package com.deepseekharness.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;

/** SAF bridge for file managers such as MT. It exposes only the app's rootfs tree. */
public final class DshaDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private static final String ROOT = "root";
    private static final String[] ROOT_PROJECTION = {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};

    private File base() {
        android.content.Context context = getContext();
        if (context == null) return new File("/data/empty");
        return new File(context.getFilesDir(), "linux/ubuntu/root");
    }
    private File file(String id) throws FileNotFoundException {
        if (id == null || id.isEmpty() || ROOT.equals(id)) return base();
        File f = new File(base(), id);
        try {
            String b = base().getCanonicalPath(), p = f.getCanonicalPath();
            if (!p.equals(b) && !p.startsWith(b + File.separator)) throw new SecurityException("outside root");
            return f;
        } catch (java.io.IOException e) { throw new FileNotFoundException("invalid document"); }
    }
    private String id(File f) throws java.io.IOException {
        String b = base().getCanonicalPath(), p = f.getCanonicalPath();
        return p.equals(b) ? ROOT : p.substring(b.length() + 1).replace(File.separatorChar, '/');
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT_PROJECTION : projection);
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT);
        r.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT);
        r.add(DocumentsContract.Root.COLUMN_TITLE, "DSHA");
        r.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_LOCAL_ONLY);
        r.add(DocumentsContract.Root.COLUMN_ICON, com.deepseekharness.app.R.mipmap.ic_launcher);
        r.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        return c;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_PROJECTION : projection);
        include(c, file(id), id); return c;
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_PROJECTION : projection);
        File p = file(parentId); File[] fs = p.listFiles();
        if (fs != null) for (File f : fs) try { include(c, f, id(f)); } catch (Exception ignored) {}
        return c;
    }
    private void include(MatrixCursor c, File f, String id) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, f.getName().isEmpty() ? "DSHA" : f.getName());
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream");
        r.add(DocumentsContract.Document.COLUMN_FLAGS, f.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE);
        r.add(DocumentsContract.Document.COLUMN_SIZE, f.isFile() ? f.length() : null);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified());
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        int m = mode.contains("w") ? ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file(id), m);
    }
    @Override public String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        File p = file(parentId); File out = new File(p, displayName);
        try { if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType) ? !out.mkdirs() : !out.createNewFile()) throw new java.io.IOException(); return id(out); }
        catch (Exception e) { throw new FileNotFoundException("cannot create document"); }
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { File f=file(id); if (!f.delete()) throw new FileNotFoundException("cannot delete document"); }
    @Override public String getDocumentType(String id) throws FileNotFoundException { File f=file(id); return f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"; }
}
