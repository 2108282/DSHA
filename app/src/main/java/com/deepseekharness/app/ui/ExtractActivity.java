package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Fmt;

/**
 * 首次运行解压门禁：解压内置 Ubuntu 环境，完成后进主界面。
 * 进度用速率 + 剩余时间平滑显示（节流 400ms），避免数字乱跳。
 */
public class ExtractActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView detailText;
    private TextView errorText;
    private ProgressBar bar;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extract);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        statusText = findViewById(R.id.extract_status);
        detailText = findViewById(R.id.extract_detail);
        errorText = findViewById(R.id.extract_error);
        bar = findViewById(R.id.extract_bar);
        progressBar = findViewById(R.id.extract_progress);
        bar.setVisibility(View.VISIBLE);

        String ver = "unknown";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        boolean force = getIntent().getBooleanExtra("force_extract", false);
        statusText.setText("DSHA v" + ver + (force ? "\n正在升级内置环境…" : "\n正在检查内置环境…"));

        startExtraction(force);
    }

    private void startExtraction(final boolean force) {
        HarnessController controller = new HarnessController(this);
        new Thread(() -> {
            try {
                if (!force && controller.isEnvironmentReady()) {
                    runOnUi(() -> statusText.setText("内置环境已就绪"));
                    Thread.sleep(400);
                    proceed();
                    return;
                }
                if (!controller.hasOfflineBundle()) {
                    runOnUi(() -> {
                        bar.setVisibility(View.GONE);
                        progressBar.setVisibility(View.GONE);
                        detailText.setVisibility(View.GONE);
                        errorText.setVisibility(View.VISIBLE);
                        errorText.setText("APK 里没找到内置环境包。\n"
                                + "请用完整 APK（含 offline-rootfs）安装。");
                        statusText.setText("无法解压");
                    });
                    return;
                }
                runOnUi(() -> {
                    statusText.setText("正在解压内置环境…");
                    progressBar.setVisibility(View.VISIBLE);
                    detailText.setVisibility(View.VISIBLE);
                    detailText.setText("准备中…");
                });
                final long[] lastUi = {0};
                final Fmt.RateMeter meter = new Fmt.RateMeter();
                try { controller.stopWeb(); } catch (Throwable ignored) {}
                controller.proot().ensureRuntimeFiles();
                controller.proot().extractOfflineBundle((done, total) -> {
                    final double rate = meter.feed(done);
                    long now = System.currentTimeMillis();
                    if (now - lastUi[0] < 400) return;
                    lastUi[0] = now;
                    final long eta = meter.eta(done, total);
                    final int per1000 = total > 0 ? (int) (done * 1000 / total) : 0;
                    final String detail = (total > 0
                            ? Fmt.bytes(done) + " / " + Fmt.bytes(total)
                            : Fmt.bytes(done) + " 已解压")
                            + " · " + Fmt.rate(rate)
                            + (eta >= 0 ? " · 剩余约 " + Fmt.eta(eta) : "");
                    runOnUi(() -> {
                        if (total > 0) {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(per1000);
                            statusText.setText("正在解压环境… " + (per1000 / 10) + "%");
                        } else {
                            progressBar.setIndeterminate(true);
                            statusText.setText("正在解压环境…");
                        }
                        detailText.setText(detail);
                    });
                });
                runOnUi(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(1000);
                    statusText.setText("环境准备完成");
                    detailText.setText("解压完成，正在进入主界面…");
                });
                // 解压完成后先补一次内置插件注册（幂等，不阻塞），
                // 进主界面后四个内置插件即为已注册状态
                try {
                    controller.proot().registerBuiltinPlugins();
                } catch (Throwable ignored) {
                }
                Thread.sleep(300);
                proceed();
            } catch (Exception e) {
                runOnUi(() -> {
                    bar.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    detailText.setVisibility(View.GONE);
                    errorText.setVisibility(View.VISIBLE);
                    errorText.setText("解压失败：" + e.getMessage());
                    statusText.setText("解压失败（本页不会自动跳走）");
                });
            }
        }, "extract-offline").start();
    }

    private void proceed() {
        runOnUi(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("skip_extract", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void runOnUi(Runnable r) {
        main.post(r);
    }
}
