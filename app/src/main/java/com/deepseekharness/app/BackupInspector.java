package com.deepseekharness.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 恢复前的备份体检：**只读不写**地把整个 .tar.gz 走一遍。
 *
 * <p>为什么需要它：恢复走的是 {@code TarGzipExtractor.extractLenient} —— 宽容跳过坏条目
 * 是对的（数据已经丢了，别让整次恢复失败），但它同时也会把「整个包被截断」这种情况
 * 掩盖成「恢复完成，N 个异常条目已跳过」。备份文件被截断在现实里很常见：存储写满、
 * 云同步传一半、用户手动拷贝中断。用户看到「恢复完成」就以为好了，其实少了一半会话。
 *
 * <p>gzip 自带 CRC32 与长度尾部，完整读一遍就能发现截断和损坏 —— 不需要额外的校验文件，
 * 所以对「用户自己从别处拷来的包」（SAF 选择，拿不到旁文件）同样有效。
 * 顺便在这一趟里把「这到底是不是 DSHA 备份」和「里面有多少东西」一起统计出来：
 * 恢复一个不含 {@code .dsh} 的随便什么 tar 包，只会把用户的环境搞乱。
 *
 * <p>代价是多读一遍（顺序读，几秒），换来的是「恢复前就知道这份备份能不能用」。
 */
final class BackupInspector {

    /** tar 块大小。 */
    private static final int BLOCK = 512;
    /** 单个条目大小上限（防畸形头里的天文数字把我们卷进死循环）。 */
    private static final long MAX_ENTRY = 8L * 1024 * 1024 * 1024;

    static final class Info {
        /** 整个 gzip 流是否能完整读到底（CRC 与长度都对）。 */
        boolean readable;
        /** readable=false 时的原因（给用户看的中文说明）。 */
        String error = "";
        /** 里面有没有 .dsh —— DSHA 备份的标志。 */
        boolean looksLikeDsha;
        /** 有没有备份清单（新版备份才有；老备份没有不算问题）。 */
        boolean hasManifest;
        /** 条目总数。 */
        int entries;
        /** .dsh/sessions 下的文件数，用来告诉用户「这份备份里有多少对话」。 */
        int sessionFiles;
        /** 解压后总字节。 */
        long uncompressed;
        /** 清单里记的备份方 App 版本（读不到就是空串）。 */
        String appVersion = "";

        String humanSize() {
            long v = uncompressed;
            if (v >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f GB", v / 1073741824.0);
            if (v >= 1024L * 1024) return String.format(java.util.Locale.US, "%.1f MB", v / 1048576.0);
            if (v >= 1024) return (v / 1024) + " KB";
            return v + " B";
        }
    }

    private BackupInspector() {
    }

    static Info inspect(File tgz) {
        Info info = new Info();
        if (tgz == null || !tgz.isFile() || tgz.length() == 0) {
            info.error = "备份文件不存在或为空";
            return info;
        }
        byte[] header = new byte[BLOCK];
        byte[] skip = new byte[64 * 1024];
        try (InputStream fin = new FileInputStream(tgz);
             GZIPInputStream in = new GZIPInputStream(fin, 64 * 1024)) {
            int zeroBlocks = 0;
            while (true) {
                if (!readFully(in, header, BLOCK)) {
                    // tar 规范要求以两个全零块收尾，但现实里的包经常直接断在末尾。
                    // 数据都读完了才断，不算损坏 —— 真正的截断会在下面的数据段被发现。
                    break;
                }
                if (isZeroBlock(header)) {
                    if (++zeroBlocks >= 2) break;
                    continue;
                }
                zeroBlocks = 0;
                String name = cString(header, 0, 100);
                char type = (char) (header[156] & 0xFF);
                long size = parseOctal(header, 124, 12);
                if (size < 0 || size > MAX_ENTRY) {
                    info.error = "备份内部结构异常（条目 " + name + " 的长度不合法）";
                    return info;
                }
                info.entries++;
                if (type == '0' || type == 0 || type == '7') {
                    info.uncompressed += size;
                }
                // 「是不是 DSHA 备份」的判据刻意放得很宽：只要路径里出现 .dsh/ 或几个
                // 特征文件名就算。历史上所有版本的备份都是 `cd /root && tar … .dsh`
                // （查过 git，从第一版 c1f7df3 起就是这样），所以前缀一直是 .dsh/；
                // 但用户可能自己重新打过包、加了顶层目录，或者 tar 实现写成 ./.dsh/。
                // 判据宁可宽 —— 它只用来给提示，不用来拒绝恢复。
                if (name.contains(".dsh/") || name.equals(".dsh")
                        || name.endsWith("/.dsh") || name.contains("/sessions/")
                        || name.endsWith("settings.yaml")
                        || name.endsWith(".dsha-backup-manifest.json")) {
                    info.looksLikeDsha = true;
                }
                if (name.contains("sessions/") && size > 0) {
                    info.sessionFiles++;
                }
                boolean isManifest = name.endsWith(".dsha-backup-manifest.json")
                        || name.endsWith("backup-manifest.json");
                if (isManifest) {
                    info.hasManifest = true;
                }
                long padded = (size + BLOCK - 1) / BLOCK * BLOCK;
                if (isManifest && size > 0 && size < 256 * 1024) {
                    // 清单很小，顺手读出来取备份方的版本 —— 恢复时能告诉用户
                    // 「这份备份来自 vX」，也为将来按版本跑迁移留个入口
                    byte[] body = new byte[(int) size];
                    if (!readFully(in, body, body.length)) {
                        info.error = "备份文件不完整（读清单时提前结束）";
                        return info;
                    }
                    info.appVersion = extractAppVersion(
                            new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    long rest = padded - size;
                    if (!skipExactly(in, skip, rest)) {
                        info.error = "备份文件不完整（清单后提前结束）";
                        return info;
                    }
                } else if (!skipExactly(in, skip, padded)) {
                    info.error = "备份文件不完整（在条目 " + name + " 处提前结束）";
                    return info;
                }
            }
            // 走到这里说明 gzip 尾部的 CRC 与长度都已校验通过（close 时会验）
            info.readable = true;
            return info;
        } catch (java.util.zip.ZipException e) {
            info.error = "不是有效的 gzip 压缩包，或内容已损坏（" + brief(e) + "）";
            return info;
        } catch (java.io.EOFException e) {
            info.error = "备份文件被截断（传输/拷贝没完成，或存储写满）";
            return info;
        } catch (IOException e) {
            info.error = "读取失败：" + brief(e);
            return info;
        } catch (Throwable e) {
            info.error = "体检失败：" + brief(e);
            return info;
        }
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** 从清单 JSON 里取 App 版本。不引 JSON 解析：清单是我们自己生成的，字段名固定。 */
    private static String extractAppVersion(String json) {
        for (String key : new String[]{"\"app_version\"", "\"appVersion\"", "\"dsha_version\""}) {
            int i = json.indexOf(key);
            if (i < 0) continue;
            int c = json.indexOf(':', i + key.length());
            if (c < 0) continue;
            int q1 = json.indexOf('"', c + 1);
            if (q1 < 0) continue;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) continue;
            String v = json.substring(q1 + 1, q2).trim();
            if (!v.isEmpty() && v.length() <= 32) return v;
        }
        return "";
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    private static boolean skipExactly(InputStream in, byte[] scratch, long n) throws IOException {
        long left = n;
        while (left > 0) {
            int want = (int) Math.min(scratch.length, left);
            int r = in.read(scratch, 0, want);
            if (r < 0) return false;
            left -= r;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) {
            if (v != 0) return false;
        }
        return true;
    }

    private static String cString(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** tar 头里的八进制字段（可能带前导空格、以空格或 NUL 收尾）。 */
    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            int ch = b[i] & 0xFF;
            if (ch == 0 || ch == ' ') {
                if (any) break;     // 收尾
                continue;           // 前导空白
            }
            if (ch < '0' || ch > '7') return -1;
            v = v * 8 + (ch - '0');
            any = true;
        }
        return any ? v : 0;
    }
}
