package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * 快捷抽屉莫奈（Material You）调色板与配色管理工具。
 * 支持在 Android 12 (API 31+) 下提取系统壁纸动态调色板，
 * 与抽屉正反色（浅色/深色）正交组合，并提供经典科技蓝灰色回退。
 */
public final class MonetThemeHelper {

    private MonetThemeHelper() {}

    /**
     * 抽屉完整配色包（包含 Android 原生 View 与 WebView 注入所需的所有颜色）
     */
    public static class Palette {
        public final int cardBgColor;
        public final int textColor;
        public final int textSecondaryColor;
        public final int lineColor;
        public final int handleColor;
        public final int borderColor;

        // WebView 样式所需字符串
        public final String inputBg;
        public final String inputBorder;
        public final String drawerBg;
        public final String menuBg;
        public final String dialogBg;
        public final String selectorBg;
        public final String menuBorder;
        public final String textPrimaryHex;
        public final String textSecondaryHex;
        public final String brandTextHex;
        public final String solidBgHex;

        public Palette(int cardBgColor, int textColor, int textSecondaryColor,
                       int lineColor, int handleColor, int borderColor,
                       String inputBg, String inputBorder, String drawerBg,
                       String menuBg, String dialogBg, String selectorBg,
                       String menuBorder, String textPrimaryHex,
                       String textSecondaryHex, String brandTextHex,
                       String solidBgHex) {
            this.cardBgColor = cardBgColor;
            this.textColor = textColor;
            this.textSecondaryColor = textSecondaryColor;
            this.lineColor = lineColor;
            this.handleColor = handleColor;
            this.borderColor = borderColor;
            this.inputBg = inputBg;
            this.inputBorder = inputBorder;
            this.drawerBg = drawerBg;
            this.menuBg = menuBg;
            this.dialogBg = dialogBg;
            this.selectorBg = selectorBg;
            this.menuBorder = menuBorder;
            this.textPrimaryHex = textPrimaryHex;
            this.textSecondaryHex = textSecondaryHex;
            this.brandTextHex = brandTextHex;
            this.solidBgHex = solidBgHex;
        }
    }

    /**
     * 安全提取系统动态色彩资源（带降级兜底）
     */
    public static int getSystemColor(Context ctx, int resId, int fallback) {
        if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ContextCompat.getColor(ctx, resId);
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    public static String toHexString(int color) {
        return String.format(Locale.US, "#%06X", (0xFFFFFF & color));
    }

    public static String toRgbaString(int color, float alpha) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return String.format(Locale.US, "rgba(%d, %d, %d, %.2f)", r, g, b, alpha);
    }

    /**
     * 根据深浅色模式、莫奈开关及不透明度，计算抽屉所需的一整套调色板
     */
    public static Palette resolve(Context ctx, boolean isDarkMode, boolean isMonet, int opacityPercent) {
        int opacity = opacityPercent;
        if (opacity < 30 || opacity > 100) {
            opacity = isDarkMode ? 80 : 88;
        }
        int alpha = (int) Math.round(opacity * 255.0 / 100.0);

        if (!isMonet || ctx == null) {
            // ================= 经典科技蓝灰配色（未开启莫奈） =================
            if (isDarkMode) {
                int cardBg = Color.argb(alpha, 0x10, 0x14, 0x1B);
                int text = Color.parseColor("#8BA0B8");
                int textSecondary = Color.parseColor("#56697E");
                int line = Color.parseColor("#302A3344");
                int handle = Color.parseColor("#704A5568");
                int border = Color.parseColor("#352A3344");

                return new Palette(
                        cardBg, text, textSecondary, line, handle, border,
                        "rgba(255, 255, 255, 0.06)",
                        "rgba(255, 255, 255, 0.12)",
                        "rgba(16, 20, 27, 0.96)",
                        "rgba(24, 29, 38, 0.96)",
                        "rgba(20, 24, 32, 0.97)",
                        "rgba(30, 36, 48, 0.96)",
                        "rgba(255, 255, 255, 0.12)",
                        "#8BA0B8",
                        "#56697E",
                        "#8BA0B8",
                        "#10141B"
                );
            } else {
                int cardBg = Color.argb(alpha, 0xF5, 0xF8, 0xFC);
                int text = Color.parseColor("#1A2230");
                int textSecondary = Color.parseColor("#64748B");
                int line = Color.parseColor("#30E2E6EE");
                int handle = Color.parseColor("#90CBD5E1");
                int border = Color.parseColor("#35CBD5E1");

                return new Palette(
                        cardBg, text, textSecondary, line, handle, border,
                        "rgba(255, 255, 255, 0.75)",
                        "rgba(0, 0, 0, 0.08)",
                        "rgba(245, 248, 252, 0.97)",
                        "rgba(255, 255, 255, 0.98)",
                        "rgba(255, 255, 255, 0.98)",
                        "rgba(240, 243, 246, 0.96)",
                        "rgba(0, 0, 0, 0.08)",
                        "#1A2230",
                        "#4A5568",
                        "#1A2230",
                        "#F5F8FC"
                );
            }
        }

        // ================= 莫奈动态取色（Material You 调色板） =================
        if (isDarkMode) {
            // 深色反色模式下的莫奈色彩映射：
            // 底色：Neutral1-900；主文字：Neutral1-100；品牌强调：Accent1-200
            int n1_900 = getSystemColor(ctx, android.R.color.system_neutral1_900, Color.rgb(0x10, 0x14, 0x1B));
            int n1_800 = getSystemColor(ctx, android.R.color.system_neutral1_800, Color.rgb(0x1E, 0x22, 0x2A));
            int n1_100 = getSystemColor(ctx, android.R.color.system_neutral1_100, Color.rgb(0xE1, 0xE2, 0xEC));
            int n2_300 = getSystemColor(ctx, android.R.color.system_neutral2_300, Color.rgb(0x8B, 0xA0, 0xB8));
            int n2_600 = getSystemColor(ctx, android.R.color.system_neutral2_600, Color.rgb(0x4A, 0x55, 0x68));
            int n2_700 = getSystemColor(ctx, android.R.color.system_neutral2_700, Color.rgb(0x2A, 0x33, 0x44));
            int a1_200 = getSystemColor(ctx, android.R.color.system_accent1_200, Color.rgb(0x9E, 0xCA, 0xFF));

            int cardBg = Color.argb(alpha, Color.red(n1_900), Color.green(n1_900), Color.blue(n1_900));
            int text = n1_100;
            int textSecondary = n2_300;
            int line = Color.argb(0x35, Color.red(n2_700), Color.green(n2_700), Color.blue(n2_700));
            int handle = n2_600;
            int border = Color.argb(0x3A, Color.red(n2_700), Color.green(n2_700), Color.blue(n2_700));

            return new Palette(
                    cardBg, text, textSecondary, line, handle, border,
                    toRgbaString(n1_800, 0.40f),
                    toRgbaString(n2_300, 0.16f),
                    toRgbaString(n1_900, 0.96f),
                    toRgbaString(n1_800, 0.96f),
                    toRgbaString(n1_900, 0.98f),
                    toRgbaString(n1_800, 0.96f),
                    toRgbaString(n2_300, 0.16f),
                    toHexString(text),
                    toHexString(textSecondary),
                    toHexString(a1_200),
                    toHexString(n1_900)
            );
        } else {
            // 浅色模式下的莫奈色彩映射：
            // 底色：Neutral1-50；主文字：Neutral1-900；品牌强调：Accent1-700
            int n1_50 = getSystemColor(ctx, android.R.color.system_neutral1_50, Color.rgb(0xF5, 0xF8, 0xFC));
            int n1_100 = getSystemColor(ctx, android.R.color.system_neutral1_100, Color.rgb(0xEE, 0xF1, 0xF6));
            int n1_900 = getSystemColor(ctx, android.R.color.system_neutral1_900, Color.rgb(0x1A, 0x22, 0x30));
            int n2_200 = getSystemColor(ctx, android.R.color.system_neutral2_200, Color.rgb(0xCB, 0xD5, 0xE1));
            int n2_300 = getSystemColor(ctx, android.R.color.system_neutral2_300, Color.rgb(0x90, 0xCB, 0xD5));
            int n2_700 = getSystemColor(ctx, android.R.color.system_neutral2_700, Color.rgb(0x4A, 0x55, 0x68));
            int a1_700 = getSystemColor(ctx, android.R.color.system_accent1_700, Color.rgb(0x00, 0x61, 0xA4));

            int cardBg = Color.argb(alpha, Color.red(n1_50), Color.green(n1_50), Color.blue(n1_50));
            int text = n1_900;
            int textSecondary = n2_700;
            int line = Color.argb(0x35, Color.red(n2_200), Color.green(n2_200), Color.blue(n2_200));
            int handle = n2_300;
            int border = Color.argb(0x40, Color.red(n2_200), Color.green(n2_200), Color.blue(n2_200));

            return new Palette(
                    cardBg, text, textSecondary, line, handle, border,
                    toRgbaString(n1_50, 0.75f),
                    toRgbaString(n2_200, 0.40f),
                    toRgbaString(n1_50, 0.97f),
                    toRgbaString(n1_100, 0.98f),
                    toRgbaString(n1_100, 0.98f),
                    toRgbaString(n1_50, 0.96f),
                    toRgbaString(n2_200, 0.40f),
                    toHexString(text),
                    toHexString(textSecondary),
                    toHexString(a1_700),
                    toHexString(n1_50)
            );
        }
    }
}
