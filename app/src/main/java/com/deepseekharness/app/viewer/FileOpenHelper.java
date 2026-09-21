package com.deepseekharness.app.viewer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Locale;

/**
 * 文件外部打开助手：生成受信任的 content:// URI 并呼出系统「打开方式」弹窗。
 */
public final class FileOpenHelper {

    public static void openWithSystem(Context context, File file) {
        if (context == null || file == null || !file.exists()) {
            if (context != null) Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String mime = getMimeType(file.getName());
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".updates",
                    file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(intent, "打开方式");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(context, "未找到支持打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    public static String getMimeType(String fileName) {
        if (fileName == null) return "*/*";
        int idx = fileName.lastIndexOf('.');
        if (idx >= 0 && idx < fileName.length() - 1) {
            String ext = fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
            switch (ext) {
                case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "xls": return "application/vnd.ms-excel";
                case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "doc": return "application/msword";
                case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "ppt": return "application/vnd.ms-powerpoint";
                case "pdf": return "application/pdf";
                case "apk": return "application/vnd.android.package-archive";
                case "zip": return "application/zip";
                case "tar": return "application/x-tar";
                case "gz": case "tgz": return "application/gzip";
                case "png": return "image/png";
                case "jpg": case "jpeg": return "image/jpeg";
                case "webp": return "image/webp";
                case "gif": return "image/gif";
                case "txt": case "log": case "py": case "js": case "ts": case "json": case "yaml": case "yml": case "sh": case "java":
                    return "text/plain";
                default:
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    return mime != null ? mime : "*/*";
            }
        }
        return "*/*";
    }
}
