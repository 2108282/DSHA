package com.deepseekharness.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 内置离线环境解压页：欢迎页之后、检测到根 rootfs 未就绪时显示。
 * 从 assets 解压预装好的 rootfs 整包，完成后进入主界面。
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

        startExtraction();
    }

    private void startExtraction() {
        ProotBootstrap proot = new ProotBootstrap(this);
        new Thread(() -> {
            try {
                if (proot.isInstalled()) {
                    // 已就绪，直接进主界面
                    proceed();
                    return;
                }
                bar.setVisibility(ProgressBar.VISIBLE);
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
                runOnUiThread(() -> {
                    errorText.setVisibility(TextView.VISIBLE);
                    errorText.setText("解压失败：" + e.getMessage() + "\n请重试或重新安装 APK。");
                    statusText.append("\n点击返回可退出。");
                });
            }
        }, "extract-offline").start();
    }

    /** 进入主界面并关闭本页 */
    private void proceed() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
