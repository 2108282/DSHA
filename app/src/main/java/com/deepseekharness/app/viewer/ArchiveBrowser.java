package com.deepseekharness.app.viewer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 压缩包只读枚举器：内存流式解析，不解压落盘，防御路径穿越。
 */
public final class ArchiveBrowser {

    public static class Entry {
        public final String path;
        public final String name;
        public final boolean isDirectory;
        public final long size;
        public final long lastModified;

        public Entry(String path, String name, boolean isDirectory, long size, long lastModified) {
            this.path = path;
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    public static List<Entry> listEntries(File file) {
        List<Entry> list = new ArrayList<>();
        if (file == null || !file.isFile()) return list;

        String name = file.getName().toLowerCase();
        try {
            if (name.endsWith(".zip") || name.endsWith(".apk") || name.endsWith(".jar") || name.endsWith(".epub")) {
                try (ZipFile zf = new ZipFile(file, StandardCharsets.UTF_8)) {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry ze = en.nextElement();
                        String entryName = sanitizePath(ze.getName());
                        String base = getBaseName(entryName);
                        list.add(new Entry(entryName, base, ze.isDirectory(), ze.getSize(), ze.getTime()));
                        if (list.size() >= 5000) break; // 防止超级大包耗尽内存
                    }
                }
            } else if (name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar.bz2")) {
                InputStream fis = new FileInputStream(file);
                InputStream is = new BufferedInputStream(fis);
                if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                    is = new GzipCompressorInputStream(is);
                } else if (name.endsWith(".tar.bz2")) {
                    is = new BZip2CompressorInputStream(is);
                }
                try (TarArchiveInputStream tis = new TarArchiveInputStream(is)) {
                    TarArchiveEntry te;
                    while ((te = tis.getNextTarEntry()) != null) {
                        String entryName = sanitizePath(te.getName());
                        String base = getBaseName(entryName);
                        list.add(new Entry(entryName, base, te.isDirectory(), te.getSize(), te.getModTime().getTime()));
                        if (list.size() >= 5000) break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 预览压缩包内某一文本文件的内容（上限 1MB） */
    public static String readEntryText(File file, String entryPath) {
        String name = file.getName().toLowerCase();
        try {
            if (name.endsWith(".zip") || name.endsWith(".apk") || name.endsWith(".jar")) {
                try (ZipFile zf = new ZipFile(file, StandardCharsets.UTF_8)) {
                    ZipEntry ze = zf.getEntry(entryPath);
                    if (ze != null && !ze.isDirectory()) {
                        try (InputStream in = zf.getInputStream(ze)) {
                            return readStreamLimited(in, 1024 * 1024);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readStreamLimited(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int toWrite = Math.min(n, limit - total);
            bos.write(buf, 0, toWrite);
            total += toWrite;
            if (total >= limit) break;
        }
        return bos.toString("UTF-8");
    }

    private static String sanitizePath(String path) {
        if (path == null) return "";
        path = path.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    private static String getBaseName(String path) {
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
