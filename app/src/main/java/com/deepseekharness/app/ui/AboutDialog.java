package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 关于对话框：GitHub 仓库 / QQ 交流群入口（欢迎页 + 设置页 + 顶栏共用）。 */
public final class AboutDialog {

    public static final String GITHUB_URL = "https://github.com/DSH-APP/DSHA";
    public static final String GITHUB_ROOT_URL = "https://github.com/2108282/DSHA-FR";
    public static final String QQ_GROUP = "975836806";

    private AboutDialog() {
    }

    public static void show(Context ctx) {
        String version = "unknown";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("DSHA v" + version)
                .setMessage("DeepSeek Harness 安卓启动器\n" + ctx.getString(com.deepseekharness.app.R.string.edition_description) + "\n\n"
                        + "1. 本仓库地址：" + GITHUB_ROOT_URL + "\n"
                        + "2. 非root仓库地址（其他作者）：" + GITHUB_URL + "\n"
                        + "3. qq交流群（其他作者）：" + QQ_GROUP)
                .setPositiveButton("Root仓库", (d, w) -> openBrowser(ctx, GITHUB_ROOT_URL))
                .setNeutralButton("原版DSHA仓库", (d, w) -> openBrowser(ctx, GITHUB_URL))
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
