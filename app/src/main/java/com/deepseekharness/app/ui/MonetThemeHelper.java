package com.deepseekharness.app.ui;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.deepseekharness.app.HttpShellService;

import java.io.File;
import java.util.Locale;

/**
 * 快捷抽屉莫奈（Material You）调色板核心引擎：
 * 1. 回归纯粹系统壁纸取色（Root 特权直取 / 原生 WallpaperColors 双轨制）；
 * 2. 严格限制：反色（深色模式）下坚决不使用莫奈取色，保持纯正经典深色反色高对比体验；
 * 3. 仅在浅色模式下生效，将系统壁纸种子色融入抽屉浅彩微光中。
 */
public final class MonetThemeHelper {

    private MonetThemeHelper() {}

    private static volatile Integer sCachedSeedColor = null;

    /** 清除壁纸颜色缓存（在设置页切换或手动刷新时调用） */
    public static void clearCache(Context context) {
        sCachedSeedColor = null;
        if (context != null) {
            try {
                File cacheFile = new File(context.getCacheDir(), "wallpaper_monet_seed.jpg");
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void clearCache() {
        sCachedSeedColor = null;
    }

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
     * 从当前系统壁纸中提取最具辨识度的鲜艳种子色（Seed Color）
     */
    public static int getWallpaperSeedColor(Context context) {
        if (sCachedSeedColor != null) {
            return sCachedSeedColor;
        }
        if (context == null) {
            return Color.parseColor("#10B981");
        }

        int extracted = 0;

        // 阶段 1：通过 Root 特权通道直取系统壁纸图片（穿透小米澎湃 OS/MIUI/OPPO/vivo 等系统壁纸签名墙）
        try {
            File cacheFile = new File(context.getCacheDir(), "wallpaper_monet_seed.jpg");
            if (!cacheFile.exists() || cacheFile.length() <= 0) {
                String copyCmd = "cp /data/system/users/0/wallpaper " + cacheFile.getAbsolutePath()
                        + " 2>/dev/null || cp /data/system/users/0/wallpaper_orig " + cacheFile.getAbsolutePath()
                        + " 2>/dev/null || cp /data/system/users/0/blurwallpaper " + cacheFile.getAbsolutePath()
                        + " 2>/dev/null; chmod 666 " + cacheFile.getAbsolutePath() + " 2>/dev/null";
                HttpShellService.execRootCommand(copyCmd);
            }

            if (cacheFile.exists() && cacheFile.length() > 0) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 16;
                Bitmap bmp = BitmapFactory.decodeFile(cacheFile.getAbsolutePath(), opts);
                if (bmp != null) {
                    extracted = extractVibrantFromBitmap(bmp);
                    bmp.recycle();
                }
            }
        } catch (Throwable ignored) {}

        // 阶段 2：优先尝试系统级 WallpaperColors（适用于原生 Pixel/AOSP 系统，API 27+）
        if (extracted == 0) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(context);
                if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                    if (wc != null) {
                        Color p = wc.getPrimaryColor();
                        if (p != null && getSaturation(p.toArgb()) >= 0.12f) {
                            extracted = p.toArgb();
                        }
                        if (extracted == 0 && wc.getSecondaryColor() != null) {
                            int sc = wc.getSecondaryColor().toArgb();
                            if (getSaturation(sc) >= 0.12f) extracted = sc;
                        }
                        if (extracted == 0 && wc.getTertiaryColor() != null) {
                            int tc = wc.getTertiaryColor().toArgb();
                            if (getSaturation(tc) >= 0.12f) extracted = tc;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 阶段 3：如果原生 API 允许，尝试直接获取壁纸 Drawable 采样
        if (extracted == 0) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(context);
                if (wm != null) {
                    Drawable d = wm.getDrawable();
                    if (d instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) d).getBitmap();
                        extracted = extractVibrantFromBitmap(bmp);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 阶段 4：如果依然未果，尝试 AOSP Accent 主色（API 31+）
        if (extracted == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                int a1 = ContextCompat.getColor(context, android.R.color.system_accent1_500);
                if (getSaturation(a1) >= 0.12f) {
                    extracted = a1;
                }
            } catch (Throwable ignored) {}
        }

        // 阶段 5：保底活力翡翠绿（与护眼绿同系）
        if (extracted == 0) {
            extracted = Color.parseColor("#10B981");
        }

        sCachedSeedColor = extracted;
        return extracted;
    }

    /** 计算颜色饱和度 */
    private static float getSaturation(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        return hsl[1];
    }

    /** 对壁纸位图快速网格采样，抓取饱和度最高且明度适中的代表色 */
    private static int extractVibrantFromBitmap(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return 0;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);

        int bestColor = 0;
        float bestScore = -1f;
        float[] hsl = new float[3];

        for (int x = stepX / 2; x < w; x += stepX) {
            for (int y = stepY / 2; y < h; y += stepY) {
                int pixel = bmp.getPixel(x, y);
                ColorUtils.colorToHSL(pixel, hsl);
                float sat = hsl[1];
                float lum = hsl[2];
                if (lum >= 0.15f && lum <= 0.85f && sat >= 0.12f) {
                    float score = sat * 0.7f + (1.0f - Math.abs(lum - 0.5f) * 2f) * 0.3f;
                    if (score > bestScore) {
                        bestScore = score;
                        bestColor = pixel;
                    }
                }
            }
        }
        return bestColor;
    }

    /**
     * 根据深浅色模式、莫奈开关及不透明度，计算抽屉所需的一整套调色板。
     * 【关键规则】：打开反色（isDarkMode == true）之后，坚决不使用莫奈取色，保持纯正经典深色反色！
     */
    public static Palette resolve(Context ctx, boolean isDarkMode, boolean isMonet, int opacityPercent) {
        int opacity = opacityPercent;
        if (opacity < 30 || opacity > 100) {
            opacity = isDarkMode ? 80 : 88;
        }
        int alpha = (int) Math.round(opacity * 255.0 / 100.0);

        // 打开反色之后坚决不使用莫奈取色（或未开启莫奈）→ 严格使用经典方案
        if (isDarkMode || !isMonet || ctx == null) {
            if (isDarkMode) {
                // ================= 经典深色反色配色（科技蓝灰黑，极佳对比度） =================
                return new Palette(
                        Color.argb(alpha, 0x10, 0x14, 0x1B),
                        Color.parseColor("#8BA0B8"),
                        Color.parseColor("#56697E"),
                        Color.parseColor("#302A3344"),
                        Color.parseColor("#704A5568"),
                        Color.parseColor("#352A3344"),
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
                // ================= 经典浅色配色 =================
                return new Palette(
                        Color.argb(alpha, 0xF5, 0xF8, 0xFC),
                        Color.parseColor("#1A2230"),
                        Color.parseColor("#64748B"),
                        Color.parseColor("#30E2E6EE"),
                        Color.parseColor("#90CBD5E1"),
                        Color.parseColor("#35CBD5E1"),
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

        // ================= 仅在浅色模式且开启莫奈时：基于壁纸生成通透浅彩调色板 =================
        int seed = getWallpaperSeedColor(ctx);
        float[] seedHsl = new float[3];
        ColorUtils.colorToHSL(seed, seedHsl);
        float h = seedHsl[0]; // 壁纸色相 0 ~ 360°

        int lightCardRgb = ColorUtils.HSLToColor(new float[]{h, 0.22f, 0.96f});
        int cardBg = Color.argb(alpha, Color.red(lightCardRgb), Color.green(lightCardRgb), Color.blue(lightCardRgb));

        // 主文字与按钮：深邃墨色（S=18%, L=14%），保证清晰可读
        int text = ColorUtils.HSLToColor(new float[]{h, 0.18f, 0.14f});
        // 次级提示文字（S=14%, L=38%）
        int textSecondary = ColorUtils.HSLToColor(new float[]{h, 0.14f, 0.38f});
        // 品牌强调色（S=75%, L=35%）
        int brand = ColorUtils.HSLToColor(new float[]{h, 0.75f, 0.35f});

        // 拖拽横条
        int handle = ColorUtils.HSLToColor(new float[]{h, 0.28f, 0.70f});
        // 分割线与细边框
        int borderRaw = ColorUtils.HSLToColor(new float[]{h, 0.25f, 0.82f});
        int line = Color.argb(0x35, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));
        int border = Color.argb(0x3A, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));

        // WebView 控件同系质感颜色
        int inputInner = ColorUtils.HSLToColor(new float[]{h, 0.18f, 0.98f});
        int inputBorderColor = ColorUtils.HSLToColor(new float[]{h, 0.25f, 0.82f});
        int menuInner = ColorUtils.HSLToColor(new float[]{h, 0.20f, 0.97f});

        return new Palette(
                cardBg, text, textSecondary, line, handle, border,
                toRgbaString(inputInner, 0.75f),
                toRgbaString(inputBorderColor, 0.35f),
                toRgbaString(lightCardRgb, 0.97f),
                toRgbaString(menuInner, 0.98f),
                toRgbaString(menuInner, 0.98f),
                toRgbaString(lightCardRgb, 0.96f),
                toRgbaString(inputBorderColor, 0.35f),
                toHexString(text),
                toHexString(textSecondary),
                toHexString(brand),
                toHexString(lightCardRgb)
        );
    }
}
