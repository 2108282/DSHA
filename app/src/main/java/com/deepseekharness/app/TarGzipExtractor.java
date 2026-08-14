package com.deepseekharness.app;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 流式 tar.gz 解压器，不依赖系统 tar（Android toybox tar 对 Ubuntu rootfs
 * 的符号链接、pax 长文件名、权限位支持差，会导致解压失败或闪退）。
 *
 * 支持：普通文件、目录、符号链接、硬链接、GNU/pax 长文件名；特殊文件安全跳过。
 */
public final class TarGzipExtractor {

    private static final int BLOCK = 512;

    private TarGzipExtractor() {}

    public static void extract(File tarball, File dest) throws IOException {
        extract(tarball, dest, 0);
    }

    public static void extract(File tarball, File dest, int strip) throws IOException {
        try (InputStream raw = new FileInputStream(tarball);
             GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16))) {
            byte[] header = new byte[BLOCK];
            byte[] buf = new byte[8192];
            String pendingName = null;

            while (true) {
                if (!readFull(gz, header, BLOCK)) break;          // EOF
                if (isZeroBlock(header)) {
                    if (!readFull(gz, header, BLOCK)) break;      // 结束块
                    if (isZeroBlock(header)) break;               // 双零块 = 结束
                    continue;                                     // 单零块 = padding，继续
                }

                String name = parseString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int mode = (int) parseOctal(header, 100, 8);
                int type = header[156] & 0xFF;
                String linkname = parseString(header, 157, 100);

                // 长文件名头（GNU 'L' / pax 'x'）
                if (type == 'L' || type == 'x') {
                    byte[] longData = new byte[clampSize(size)];
                    readFull(gz, longData, longData.length);
                    skipPadding(gz, size);
                    pendingName = (type == 'L')
                            ? parseString(longData, 0, longData.length)
                            : parsePaxPath(longData);
                    continue;
                }

                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }

                // ustar prefix
                String prefix = parseString(header, 345, 155);
                if (prefix != null && !prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }

                // 去掉前 strip 层目录（用于去掉 tarball 的顶层目录）
                if (strip > 0) {
                    for (int i = 0; i < strip; i++) {
                        int idx = name.indexOf('/');
                        if (idx < 0) { name = null; break; }
                        name = name.substring(idx + 1);
                    }
                    if (name == null || name.isEmpty()) {
                        skipPadding(gz, size);
                        continue;
                    }
                }

                File out = new File(dest, name);

                switch (type) {
                    case '0':
                    case 0:
                    case '7':
                        writeFile(gz, out, size, mode, buf);
                        break;
                    case '5':
                        out.mkdirs();
                        skipPadding(gz, size);
                        break;
                    case '2':
                        out.getParentFile().mkdirs();
                        try {
                            Os.symlink(linkname, out.getAbsolutePath());
                        } catch (Throwable ignored) {
                        }
                        skipPadding(gz, size);
                        break;
                    case '1':
                        out.getParentFile().mkdirs();
                        try {
                            Os.link(new File(dest, linkname).getAbsolutePath(), out.getAbsolutePath());
                        } catch (Throwable ignored) {
                        }
                        skipPadding(gz, size);
                        break;
                    default:
                        // 设备节点等特殊文件：安全跳过
                        skipPadding(gz, size);
                        break;
                }
            }
        }
    }

    private static void writeFile(InputStream in, File out, long size, int mode, byte[] buf)
            throws IOException {
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            long remaining = size;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("tar 数据意外结束");
                fos.write(buf, 0, n);
                remaining -= n;
            }
        }
        chmodBestEffort(out, mode);
        skipPadding(in, size);
    }

    private static void chmodBestEffort(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode & 0777);
        } catch (Throwable ignored) {
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        long remaining = pad;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static boolean readFull(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) return off == 0 ? false : (off == len);
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String parseString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static String parsePaxPath(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : s.split("\n")) {
            if (line.startsWith("path=")) return line.substring(5);
        }
        return null;
    }

    private static int clampSize(long size) {
        return (int) Math.min(size, 64 * 1024);
    }
}
