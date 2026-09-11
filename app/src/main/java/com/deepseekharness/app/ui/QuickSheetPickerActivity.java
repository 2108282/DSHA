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
 * 抽屉文件选择独立中转 Activity：
 * 1. 独占独立任务栈 (taskAffinity="com.dsh.client.sheet_picker")，与应用主任务栈彻底物理隔离，
 *    启动它绝不会把 MainActivity (容器内部) 拉到手机前台；
 * 2. 全透明无界面、零窗口动画 (Theme.DeepseekHarness.SheetPickerTransparent)，对抽屉现有视觉画面零侵入；
 * 3. 使用标准 ActivityResultLauncher 调起系统选择器，100% 避开 singleInstance 限制；
 * 4. 选完文件后在后台线程安全转储并生成 FileProvider URI 回调抽屉，随后无感 finish。
 */
public class QuickSheetPickerActivity extends ComponentActivity {

    public interface Callback {
        void onResult(Uri[] uris);
    }

    private static Callback sCallback = null;
    private static Intent sPrimaryIntent = null;
    private static String[] sAcceptTypes = null;
    private static boolean sAllowMultiple = false;

    public static synchronized void start(Context context,
                                          Intent primaryIntent,
                                          String[] acceptTypes,
                                          boolean allowMultiple,
                                          Callback callback) {
        sCallback = callback;
        sPrimaryIntent = primaryIntent;
        sAcceptTypes = acceptTypes;
        sAllowMultiple = allowMultiple;

        Intent intent = new Intent(context, QuickSheetPickerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
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

                    final Callback callback = sCallback;
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
                    }, "sheet-picker-copy").start();
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
