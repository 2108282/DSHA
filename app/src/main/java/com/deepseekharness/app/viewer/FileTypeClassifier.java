package com.deepseekharness.app.viewer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * 文件类型识别器（魔数签名优先、内容嗅探优先，不单纯依赖扩展名）。
 */
public final class FileTypeClassifier {

    public static final int SNIFF_LIMIT = 8 * 1024;

    public enum FileKind {
        TEXT, IMAGE, PDF, OFFICE, ARCHIVE, HEX, UNKNOWN
    }

    public static class FileType {
        public final FileKind kind;
        public final String subType;
        public final String extension;

        public FileType(FileKind kind, String subType, String extension) {
            this.kind = kind;
            this.subType = subType;
            this.extension = extension;
        }
    }

    public static FileType classify(File file) {
        if (file == null || !file.exists()) {
            return new FileType(FileKind.UNKNOWN, null, "");
        }
        String name = file.getName();
        String ext = getExtension(name);
        byte[] head = readHead(file, Math.max(SNIFF_LIMIT, 512));
        long size = file.length();

        // 1. 魔数识别（一级）
        FileType magic = matchMagic(head, ext, size);
        if (magic != null) {
            return magic;
        }

        // 2. 检查 BOM
        if (hasBom(head)) {
            return new FileType(FileKind.TEXT, ext, ext);
        }

        // 3. 内容嗅探（二级）
        if (size > 0 && head.length > 0) {
            if (looksBinary(head)) {
                return new FileType(FileKind.HEX, ext, ext);
            }
            return new FileType(FileKind.TEXT, ext, ext);
        }

        // 4. 空文件 / 兜底
        return new FileType(FileKind.TEXT, ext, ext);
    }

    private static String getExtension(String name) {
        int idx = name.lastIndexOf('.');
        if (idx >= 0 && idx < name.length() - 1) {
            return name.substring(idx + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    public static byte[] readHead(File file, int limit) {
        if (!file.isFile() || file.length() <= 0) return new byte[0];
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[limit];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            return Arrays.copyOf(buf, n);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static FileType matchMagic(byte[] head, String ext, long size) {
        if (head.length < 4) return null;

        // PNG: 89 50 4E 47
        if (starts(head, 0x89, 0x50, 0x4E, 0x47)) return new FileType(FileKind.IMAGE, "png", ext);
        // JPEG: FF D8 FF
        if (starts(head, 0xFF, 0xD8, 0xFF)) return new FileType(FileKind.IMAGE, "jpeg", ext);
        // GIF: GIF87a / GIF89a
        if (head.length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return new FileType(FileKind.IMAGE, "gif", ext);
        }
        // WebP: RIFF....WEBP
        if (head.length >= 12 && starts(head, 0x52, 0x49, 0x46, 0x46) &&
                head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return new FileType(FileKind.IMAGE, "webp", ext);
        }
        // PDF: %PDF-
        if (starts(head, 0x25, 0x50, 0x44, 0x46, 0x2D)) return new FileType(FileKind.PDF, "pdf", ext);

        // Office 97-2003 OLE2 复合二进制魔数: D0 CF 11 E0 A1 B1 1A E1 (doc, xls, ppt)
        if (starts(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return new FileType(FileKind.OFFICE, ext.isEmpty() ? "doc" : ext, ext);
        }

        // ZIP 族: PK\x03\x04
        if (starts(head, 0x50, 0x4B, 0x03, 0x04)) {
            // 二级区分 Office 文档 (docx, xlsx, pptx)
            if ("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext)) {
                return new FileType(FileKind.OFFICE, ext, ext);
            }
            return new FileType(FileKind.ARCHIVE, "zip", ext);
        }
        // Gzip: 1F 8B
        if (starts(head, 0x1F, 0x8B)) return new FileType(FileKind.ARCHIVE, "gzip", ext);
        // Bzip2: BZh
        if (starts(head, 0x42, 0x5A, 0x68)) return new FileType(FileKind.ARCHIVE, "bzip2", ext);
        // TAR: 检查 ustar
        if (head.length >= 262 && head[257] == 'u' && head[258] == 's' && head[259] == 't' && head[260] == 'a' && head[261] == 'r') {
            return new FileType(FileKind.ARCHIVE, "tar", ext);
        }

        // 扩展名兜底
        if ("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext) || "doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext)) {
            return new FileType(FileKind.OFFICE, ext, ext);
        }
        if ("svg".equals(ext) || "bmp".equals(ext) || "ico".equals(ext)) {
            return new FileType(FileKind.IMAGE, ext, ext);
        }

        return null;
    }

    private static boolean starts(byte[] head, int... sig) {
        if (head.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((head[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    private static boolean hasBom(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) return true;
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) return true;
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) return true;
        return false;
    }

    private static boolean looksBinary(byte[] head) {
        int nonPrintable = 0;
        for (byte b : head) {
            int val = b & 0xFF;
            if (val == 0) return true;
            if (val < 0x09 || (val > 0x0D && val < 0x20)) {
                nonPrintable++;
            }
        }
        return ((float) nonPrintable / head.length) > 0.3f;
    }
}
