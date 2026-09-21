package com.deepseekharness.app.viewer;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 十六进制转储格式化器：64KB 随机块读取，内存恒定。
 */
public final class HexDumper {

    public static final int ROW_BYTES = 16;
    public static final int BLOCK_SIZE = 64 * 1024;
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    public static class HexRow {
        public final long offset;
        public final String hex;
        public final String ascii;

        public HexRow(long offset, String hex, String ascii) {
            this.offset = offset;
            this.hex = hex;
            this.ascii = ascii;
        }
    }

    public static int rowCount(long fileLength) {
        if (fileLength <= 0) return 0;
        return (int) ((fileLength + ROW_BYTES - 1) / ROW_BYTES);
    }

    public static long rowOffset(int rowIndex) {
        return (long) rowIndex * ROW_BYTES;
    }

    public static int blockOf(long offset) {
        return (int) (offset / BLOCK_SIZE);
    }

    public static byte[] readBlock(File file, int blockIndex) {
        if (file == null || !file.isFile() || file.length() <= 0) return new byte[0];
        long offset = (long) blockIndex * BLOCK_SIZE;
        if (offset >= file.length()) return new byte[0];

        int toRead = (int) Math.min((long) BLOCK_SIZE, file.length() - offset);
        byte[] buf = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            int n = raf.read(buf);
            if (n <= 0) return new byte[0];
            return buf;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static List<HexRow> formatBlock(byte[] data, long baseOffset) {
        List<HexRow> rows = new ArrayList<>((data.length + ROW_BYTES - 1) / ROW_BYTES);
        int off = 0;
        while (off < data.length) {
            int len = Math.min(ROW_BYTES, data.length - off);
            byte[] slice = new byte[len];
            System.arraycopy(data, off, slice, 0, len);
            rows.add(formatRow(slice, baseOffset + off));
            off += ROW_BYTES;
        }
        return rows;
    }

    public static HexRow formatRow(byte[] data, long offset) {
        int n = Math.min(ROW_BYTES, data.length);
        StringBuilder hex = new StringBuilder(ROW_BYTES * 3);
        StringBuilder ascii = new StringBuilder(ROW_BYTES);

        for (int i = 0; i < ROW_BYTES; i++) {
            if (i > 0) hex.append(' ');
            if (i < n) {
                int b = data[i] & 0xFF;
                hex.append(HEX_DIGITS[b >>> 4]).append(HEX_DIGITS[b & 0x0F]);
                ascii.append(b >= 0x20 && b <= 0x7E ? (char) b : '.');
            } else {
                hex.append("  ");
                ascii.append(' ');
            }
        }
        return new HexRow(offset, hex.toString(), ascii.toString());
    }

    /** 粗估文件前 64KB 字节的信息熵 (0.0 ~ 8.0) */
    public static double entropyOf(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return 0.0;
        byte[] head = readBlock(file, 0);
        if (head.length == 0) return 0.0;

        int[] counts = new int[256];
        for (byte b : head) {
            counts[b & 0xFF]++;
        }
        double entropy = 0.0;
        double total = head.length;
        for (int count : counts) {
            if (count > 0) {
                double p = count / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }
}
