package com.deepseekharness.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * WebUI 里触发的下载，统一落到 {@code Download/DSHA/下载/}（见 {@link PublicDirs}）。
 *
 * <p><b>之前这里是个洞</b>：内嵌的 GeckoView 从来没设过 {@code ContentDelegate}，
 * 而 GeckoView 对「不能内联显示的响应」默认就是丢弃 —— 也就是说 WebUI 里点导出/下载
 * 什么反应都没有，既不报错也不落文件。系统 WebView 那条路同样没有 DownloadListener。
 *
 * <p>两条路的输入不一样：GeckoView 给的是<b>已经打开的响应流</b>（{@code WebResponse.body}），
 * 系统 WebView 的 {@code DownloadListener} 只给 URL，得自己再发一次请求。所以这里出两个
 * 入口，落地部分共用。
 */
final class DownloadSink {

    private DownloadSink() {
    }

    /**
     * 把已打开的流落盘。返回用户可见路径，失败返回 null。
     *
     * <p><b>负责关闭传进来的流</b>：GeckoView 的 {@code WebResponse.body} 是一条真实的
     * 网络连接，不关就一直挂着（多下几个文件就攒出一把泄漏的连接）。语义上这个方法
     * 「消费」这条流，关闭责任放在这里比散在每个调用方靠得住。
     */
    static String save(Context ctx, InputStream in, String fileName, String mime) {
        try {
            return saveInner(ctx, in, fileName, mime);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String saveInner(Context ctx, InputStream in, String fileName, String mime) {
        if (ctx == null || in == null) return null;
        String name = sanitize(fileName);
        final String base = Environment.DIRECTORY_DOWNLOADS;
        // Android 10+：MediaStore，不需要任何存储权限
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE,
                        mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        PublicDirs.relative(base, PublicDirs.DOWNLOADS));
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                        if (os == null) return null;
                        pump(in, os);
                    }
                    return PublicDirs.display(Environment.getExternalStorageDirectory()
                            .getAbsolutePath(), base, PublicDirs.DOWNLOADS) + "/" + name;
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "下载写 MediaStore 失败，改直写: " + e);
            }
        }
        // Android 9-，或已授予「所有文件访问」
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(base),
                    PublicDirs.ROOT + "/" + PublicDirs.DOWNLOADS);
            if (dir.isDirectory() || dir.mkdirs()) {
                File dst = new File(dir, name);
                try (OutputStream os = new FileOutputStream(dst)) {
                    pump(in, os);
                }
                return dst.getAbsolutePath();
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "下载直写失败: " + e);
        }
        return null;
    }

    /** 自己去拉一次 URL 再落盘（系统 WebView 的 DownloadListener 只给 URL）。 */
    static String download(Context ctx, String url, String fileName, String mime) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() / 100 != 2) return null;
            String cd = conn.getHeaderField("Content-Disposition");
            String name = guessName(url, cd, fileName);
            try (InputStream in = conn.getInputStream()) {
                return save(ctx, in, name, mime != null && !mime.isEmpty()
                        ? mime : conn.getContentType());
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "下载失败 " + url + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 猜文件名：Content-Disposition 的 filename 优先，其次 URL 末段，最后兜底名。
     *
     * <p>纯字符串处理，刻意不用 {@code URLUtil.guessFileName} —— 那个会把没有扩展名的
     * 文件强行加 {@code .bin}，而 dsh 导出的多是 {@code .md}/{@code .json}/无扩展名的东西。
     */
    static String guessName(String url, String contentDisposition, String fallback) {
        String fromCd = fileNameFromDisposition(contentDisposition);
        if (fromCd != null && !fromCd.isEmpty()) return sanitize(fromCd);
        if (url != null) {
            String u = url;
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            int h = u.indexOf('#');
            if (h >= 0) u = u.substring(0, h);
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            int slash = u.lastIndexOf('/');
            String last = slash >= 0 ? u.substring(slash + 1) : u;
            last = decodePercent(last);
            if (!last.isEmpty()) return sanitize(last);
        }
        return sanitize(fallback == null || fallback.isEmpty() ? "download" : fallback);
    }

    /** 从 {@code Content-Disposition} 抠 filename（认 {@code filename*=UTF-8''} 与普通 filename）。 */
    static String fileNameFromDisposition(String cd) {
        if (cd == null) return null;
        String s = cd;
        int star = indexOfIgnoreCase(s, "filename*=");
        if (star >= 0) {
            String v = s.substring(star + "filename*=".length()).trim();
            int semi = v.indexOf(';');
            if (semi >= 0) v = v.substring(0, semi);
            int tick = v.indexOf("''");
            if (tick >= 0) v = v.substring(tick + 2);          // UTF-8''xxx
            return decodePercent(unquote(v.trim()));
        }
        int plain = indexOfIgnoreCase(s, "filename=");
        if (plain >= 0) {
            String v = s.substring(plain + "filename=".length()).trim();
            int semi = v.indexOf(';');
            if (semi >= 0) v = v.substring(0, semi);
            return unquote(v.trim());
        }
        return null;
    }

    /** 文件名安全化：去掉路径分隔符与文件系统不收的字符，限长。 */
    static String sanitize(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replace('\\', '_').replace('/', '_');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == ':' || ch == '*' || ch == '?' || ch == '"'
                    || ch == '<' || ch == '>' || ch == '|') {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String out = sb.toString().trim();
        while (out.startsWith(".")) out = out.substring(1);   // 别写成隐藏文件
        if (out.isEmpty()) out = "download";
        if (out.length() > 120) {
            int dot = out.lastIndexOf('.');
            String ext = (dot > 0 && out.length() - dot <= 12) ? out.substring(dot) : "";
            out = out.substring(0, 120 - ext.length()) + ext;
        }
        return out;
    }

    private static String unquote(String v) {
        String s = v;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String decodePercent(String v) {
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(java.util.Locale.US).indexOf(needle.toLowerCase(java.util.Locale.US));
    }

    private static void pump(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }
}
