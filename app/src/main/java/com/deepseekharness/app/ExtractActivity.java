package com.deepseekharness.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 内置环境解压页：欢迎页之后<strong>必须</strong>经过这里。
 * 找不到包就停在本页把诊断打出来，绝不再偷偷跳去安装页。
 */
public class ExtractActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView errorText;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extract);

        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        statusText = findViewById(R.id.extract_status);
        errorText = findViewById(R.id.extract_error);
        bar = findViewById(R.id.extract_bar);
        bar.setVisibility(ProgressBar.VISIBLE);

        String ver = "1.1.2";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        statusText.setText("DSHA v" + ver + "\n正在检查内置环境…");

        startExtraction();
    }

    private void startExtraction() {
        ProotBootstrap proot = new ProotBootstrap(this);
        new Thread(() -> {
            try {
                if (proot.isOfflineExtracted()) {
                    runOnUiThread(() -> statusText.setText("内置环境已就绪"));
                    Thread.sleep(400);
                    proceed();
                    return;
                }
                if (!proot.hasOfflineBundle()) {
                    final String diag = proot.diagnoseBundle();
                    runOnUiThread(() -> {
                        bar.setVisibility(ProgressBar.GONE);
                        errorText.setVisibility(TextView.VISIBLE);
                        errorText.setText("APK 里没找到内置环境包。\n"
                                + "请确认安装的是 Actions 里解压出来的 app-debug.apk。\n\n"
                                + diag);
                        statusText.setText("无法解压");
                    });
                    return;
                }
                runOnUiThread(() -> statusText.setText("正在解压内置环境…"));
                proot.extractOfflineBundle((done, total) -> runOnUiThread(() -> {
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        statusText.setText("正在解压环境… " + pct + "%");
                    } else {
                        statusText.setText("正在解压环境… "
                                + (done / (1024 * 1024)) + " MB");
                    }
                }));
                runOnUiThread(() -> statusText.setText("环境准备完成"));
                Thread.sleep(300);
                proceed();
            } catch (Exception e) {
                final String diag = proot.diagnoseBundle() + "\n\n" + proot.diagnoseRootfs();
                runOnUiThread(() -> {
                    errorText.setVisibility(TextView.VISIBLE);
                    errorText.setText("解压失败：" + e.getMessage() + "\n\n" + diag);
                    statusText.setText("解压失败（本页不会自动跳走）");
                });
            }
        }, "extract-offline").start();
    }

    private void proceed() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("skip_extract", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
