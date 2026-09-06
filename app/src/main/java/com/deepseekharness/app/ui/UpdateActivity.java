package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.util.UpdatePolicy;

/** 更新由用户选择通道和确认安装；下载任务不依赖页面生命周期。 */
public final class UpdateActivity extends AppCompatActivity {
    private UpdateRepository repository;
    private boolean resumeInstall;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_update);
        repository = new ViewModelProvider(this).get(UpdateRepository.class);
        ((TextView) findViewById(R.id.update_current)).setText("当前 " + BuildConfig.VERSION_NAME + " · 版本码 " + BuildConfig.VERSION_CODE
                + (BuildConfig.LOW_ANDROID ? " · 兼容版" : " · 标准版"));
        RadioGroup channels = findViewById(R.id.update_channels);
        channels.check(UpdatePolicy.PREVIEW.equals(repository.channel()) ? R.id.update_preview : R.id.update_stable);
        channels.setOnCheckedChangeListener((g, id) -> repository.setChannel(id == R.id.update_preview ? UpdatePolicy.PREVIEW : UpdatePolicy.STABLE));
        findViewById(R.id.update_back).setOnClickListener(v -> finish());
        findViewById(R.id.update_check).setOnClickListener(v -> repository.check());
        findViewById(R.id.update_download).setOnClickListener(v -> repository.download());
        findViewById(R.id.update_cancel).setOnClickListener(v -> repository.cancel());
        findViewById(R.id.update_install).setOnClickListener(v -> install());
        findViewById(R.id.update_browser).setOnClickListener(v -> {
            UpdateRepository.State state = repository.state().getValue();
            AboutDialog.openBrowser(this, state != null && state.release != null ? state.release.pageUrl
                    : "https://github.com/qiannianhuanxiang/DSHA/releases");
        });
        repository.state().observe(this, state -> {
            ((TextView) findViewById(R.id.update_status)).setText(state.message);
            ((TextView) findViewById(R.id.update_notes)).setText(state.release == null ? "稳定通道只接收稳定版；预览通道也接收后续稳定版。自动匹配当前高/低版本，版本码不增加时不会提示更新。"
                    : state.release.version + " · " + String.format(java.util.Locale.ROOT, "%.2f MiB", state.release.bytes / 1048576.0) + "\n\n" + state.release.notes);
            ProgressBar progress = findViewById(R.id.update_progress);
            progress.setVisibility(state.busy ? android.view.View.VISIBLE : android.view.View.GONE);
            progress.setIndeterminate(state.total <= 0);
            if (state.total > 0) progress.setProgress((int) (state.downloaded * 100 / state.total));
            ((TextView) findViewById(R.id.update_bytes)).setText(state.total > 0 ? String.format(java.util.Locale.ROOT,
                    "%.1f / %.1f MiB", state.downloaded / 1048576.0, state.total / 1048576.0) : "");
            findViewById(R.id.update_check).setEnabled(!state.busy);
            findViewById(R.id.update_download).setEnabled(!state.busy && state.release != null);
            findViewById(R.id.update_install).setEnabled(!state.busy && state.apk != null);
            findViewById(R.id.update_cancel).setVisibility(state.busy ? android.view.View.VISIBLE : android.view.View.GONE);
            for (int i = 0; i < channels.getChildCount(); i++) channels.getChildAt(i).setEnabled(!state.busy);
        });
        if (saved == null) repository.check();
    }
    private void install() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                resumeInstall = true;
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))); return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", repository.installableApk());
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) { Toast.makeText(this, "无法安装：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (resumeInstall) {
            resumeInstall = false;
            if (android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) install();
            else Toast.makeText(this, "未允许安装更新，可稍后重试", Toast.LENGTH_SHORT).show();
        }
    }
}
