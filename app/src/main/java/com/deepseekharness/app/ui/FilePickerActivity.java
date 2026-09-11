package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 透明文件选择中转 Activity：
 * 专为解决 singleInstance / 独立任务栈 Activity 无法通过 startActivityForResult 可靠接收系统文件选择器结果的问题。
 * 自身使用标准任务栈模式，通过 ActivityResultLauncher 调起系统选择器，
 * 并在拿到结果后在后台线程完成安全转储与 FileProvider 本地 URI 封装，最后回调给目标页面。
 */
public class FilePickerActivity extends ComponentActivity {

    public interface FilePickerCallback {
        void onResult(Uri[] uris);
    }

    private static FilePickerCallback sCallback = null;
    private static Intent sPrimaryIntent = null;
    private static String[] sAcceptTypes = null;
    private static boolean sAllowMultiple = false;

    public static synchronized void start(Context context,
                                          Intent primaryIntent,
                                          String[] acceptTypes,
                                          boolean allowMultiple,
                                          FilePickerCallback callback) {
        sCallback = callback;
        sPrimaryIntent = primaryIntent;
        sAcceptTypes = acceptTypes;
        sAllowMultiple = allowMultiple;

        Intent intent = new Intent(context, FilePickerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private ActivityResultLauncher<Intent> launcher;
    private boolean isHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        launcher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (isHandled) return;
                    isHandled = true;

                    final FilePickerCallback callback = sCallback;
                    sCallback = null;

                    Uri[] selected = WebChromeClient.FileChooserParams.parseResult(
                            result.getResultCode(), result.getData());
                    if (selected == null && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            selected = new Uri[]{uri};
                        } else if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            if (count > 0) {
                                selected = new Uri[count];
                                for (int i = 0; i < count; i++) {
                                    selected[i] = result.getData().getClipData().getItemAt(i).getUri();
                                }
                            }
                        }
                    }

                    if (selected == null || selected.length == 0) {
                        if (callback != null) callback.onResult(null);
                        finish();
                        overridePendingTransition(0, 0);
                        return;
                    }

                    final Uri[] chosen = selected;
                    final Context app = getApplicationContext();
                    new Thread(() -> {
                        ArrayList<File> copied = new ArrayList<>();
                        try {
                            copied = WebUploads.copy(app, Arrays.asList(chosen));
                            Uri[] local = new Uri[copied.size()];
                            for (int i = 0; i < local.length; i++) {
                                local[i] = androidx.core.content.FileProvider.getUriForFile(
                                        app, app.getPackageName() + ".updates", copied.get(i));
                                try {
                                    app.grantUriPermission(app.getPackageName(), local[i], Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } catch (Throwable ignored) {}
                            }
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (callback != null) callback.onResult(local);
                                finish();
                                overridePendingTransition(0, 0);
                            });
                        } catch (Exception error) {
                            WebUploads.clean(copied);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (callback != null) callback.onResult(null);
                                Toast.makeText(app, "上传失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                                finish();
                                overridePendingTransition(0, 0);
                            });
                        }
                    }, "sheet-file-trampoline").start();
                });

        Intent target = sPrimaryIntent;
        try {
            if (target != null) {
                launcher.launch(target);
            } else {
                throw new RuntimeException("Primary intent is null");
            }
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, sAllowMultiple)
                        .putExtra(Intent.EXTRA_MIME_TYPES, sAcceptTypes);
                launcher.launch(WebUploads.fallback(fallback));
            } catch (Exception ex) {
                if (sCallback != null) {
                    sCallback.onResult(null);
                    sCallback = null;
                }
                Toast.makeText(this, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show();
                finish();
                overridePendingTransition(0, 0);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (!isHandled && sCallback != null) {
            sCallback.onResult(null);
            sCallback = null;
        }
        super.onDestroy();
    }
}
