package com.deepseekharness.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/** 关于对话框：GitHub 仓库 / QQ 交流群入口（欢迎页 + 配置页版本号共用） */
public final class AboutDialog {

    public static final String GITHUB_URL = "https://github.com/qiannianhuanxiang/DSHA";
    public static final String QQ_GROUP = "960636357";

    private AboutDialog() {
    }

    public static void show(Context ctx) {
        String version = "unknown";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(ctx)
                .setTitle("DSHA v" + version)
                .setMessage("DeepSeek Harness 安卓启动器\n\n"
                        + "🌟 GitHub：" + GITHUB_URL + "\n"
                        + "🐧 QQ 交流群：" + QQ_GROUP)
                .setPositiveButton("GitHub", (d, w) -> openBrowser(ctx, GITHUB_URL))
                .setNeutralButton("QQ 群", (d, w) -> openQQGroup(ctx))
                .setNegativeButton("关闭", null)
                .show();
    }

    public static void openBrowser(Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, "打不开，请手动访问：" + url, Toast.LENGTH_SHORT).show();
        }
    }

    public static void openQQGroup(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1"
                            + "&uin=" + QQ_GROUP + "&card_type=group"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, "打不开 QQ，请手动搜索群号：" + QQ_GROUP, Toast.LENGTH_SHORT).show();
        }
    }
}
