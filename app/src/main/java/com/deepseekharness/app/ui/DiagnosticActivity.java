package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;

public final class DiagnosticActivity extends AppCompatActivity {
    private DiagnosticRepository repository;

    @Override
    protected void onCreate(Bundle saved) {
        ThemeController.apply(this);
        super.onCreate(saved);
        setContentView(R.layout.activity_diagnostics);
        repository = new ViewModelProvider(this).get(DiagnosticRepository.class);

        findViewById(R.id.diagnostic_back).setOnClickListener(v -> finish());
        findViewById(R.id.diagnostic_repair).setOnClickListener(v -> repository.repairNetworkTools());
        findViewById(R.id.diagnostic_plugins).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, MainActivity.class).putExtra("open_plugins", true)));

        repository.report.observe(this, text -> ((TextView) findViewById(R.id.diagnostic_report)).setText(text));
        repository.busy.observe(this, busy -> {
            ((TextView) findViewById(R.id.diagnostic_status)).setText(busy ? "正在检查环境…" : "诊断完成（报告保留在本机）");
            findViewById(R.id.diagnostic_repair).setEnabled(!busy);
        });
        if (saved == null) repository.generate();
    }
}
