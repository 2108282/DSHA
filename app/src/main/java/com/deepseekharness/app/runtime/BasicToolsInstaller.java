package com.deepseekharness.app.runtime;

import android.content.Context;
import android.util.Base64;

import com.deepseekharness.app.util.SensitiveData;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** 安装页基础工具修复，网络与容器操作均在调用方工作线程执行。 */
public final class BasicToolsInstaller {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final Context context;
    private final ProotBootstrap proot;

    public BasicToolsInstaller(Context context, ProotBootstrap proot) {
        this.context = context.getApplicationContext();
        this.proot = proot;
    }

    public String[] repair() {
        if (!RUNNING.compareAndSet(false, true))
            return new String[]{"② 基础工具修复正在进行，请稍后查看。", "⚠️ 正在修复"};
        try {
            if (!proot.isEnvironmentReady())
                return new String[]{"② 请先完成第 1 步 Linux 环境。", "❌ 环境未就绪"};
            proot.ensureRuntimeFiles();
            if (!proot.ensureBundledPython())
                return new String[]{"② 内置 Python 修复失败，请重试并查看错误信息。", "❌ Python 未就绪"};
            String encoded;
            try (InputStream input = context.getAssets().open("install-basic-tools.sh")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            }
            String out = proot.execAndRead("printf '%s' '" + encoded
                    + "' | base64 -d | tr -d '\\r' | /bin/bash", 300_000);
            boolean ok = out != null && java.util.Arrays.asList(out.split("\\r?\\n")).contains("DSHA_TOOLS_OK");
            String detail = out == null || out.trim().isEmpty() ? "没有收到检查结果，请重试。"
                    : SensitiveData.redact(out.replace("DSHA_TOOLS_OK", "").trim());
            if (ok) {
                StringBuilder versions = new StringBuilder();
                for (String line : detail.split("\\r?\\n")) {
                    if (line.startsWith("curl ") || line.startsWith("git version ") || line.startsWith("Python "))
                        versions.append(line).append('\n');
                }
                detail = versions.toString().trim();
            }
            if (detail.length() > 6000) detail = detail.substring(detail.length() - 6000);
            return new String[]{"② 基础工具：\n" + detail,
                    ok ? "✅ curl / git / Python 已就绪" : "❌ 未完成，请检查网络或查看输出"};
        } catch (Exception error) {
            return new String[]{"② 修复失败：" + SensitiveData.redact(String.valueOf(error)), "❌ 修复失败"};
        } finally {
            RUNNING.set(false);
        }
    }
}
