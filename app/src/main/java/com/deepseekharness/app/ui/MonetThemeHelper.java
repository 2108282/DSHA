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
 * 快捷抽屉莫奈（Material You）调色板核心引擎。
 * 兼容小米 HyperOS/MIUI、ColorOS、OriginOS 及原生 Pixel 等全系设备：
 * 1. 通过 Root 特权直取 / 系统级 API 双轨制，穿透厂商签名墙，真实抓取系统当前壁纸的种子色；
 * 2. 基于壁纸色相（Hue）生成高饱和可感知的浅色与深色专属莫奈色阶；
 * 3. 绝不使用系统无彩中性灰，确保开启后色彩灵动、对比鲜明。
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
                // 筛选明度在 0.15 ~ 0.85 之间，且饱和度明显的彩色像素
                if (lum >= 0.15f && lum <= 0.85f && sat >= 0.15f) {
                    // 打分模型：饱和度权重 70%，适中明度权重 30%
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

        // ================= 真正提取壁纸色彩的莫奈调色板 =================
        int seed = getWallpaperSeedColor(ctx);
        float[] seedHsl = new float[3];
        ColorUtils.colorToHSL(seed, seedHsl);
        float h = seedHsl[0]; // 壁纸色相 0 ~ 360°

        if (isDarkMode) {
            // ----- 深色反色模式：以壁纸色相生成深邃微透光暗彩色 -----
            // 卡片底色：深沉暗彩（S=36%, L=9.5%），肉眼可清晰感知当前壁纸独特色彩底蕴！
            int darkCardRgb = ColorUtils.HSLToColor(new float[]{h, 0.36f, 0.095f});
            int cardBg = Color.argb(alpha, Color.red(darkCardRgb), Color.green(darkCardRgb), Color.blue(darkCardRgb));

            // 主文字与按钮：壁纸高明度淡彩（S=48%, L=86%），在深底上清晰柔和
            int text = ColorUtils.HSLToColor(new float[]{h, 0.48f, 0.86f});
            // 次级提示文字：中明度同系色（S=30%, L=62%）
            int textSecondary = ColorUtils.HSLToColor(new float[]{h, 0.30f, 0.62f});
            // 品牌强调色：高饱和微荧光色（S=80%, L=74%）
            int brand = ColorUtils.HSLToColor(new float[]{h, 0.80f, 0.74f});

            // 拖拽横条
            int handle = ColorUtils.HSLToColor(new float[]{h, 0.32f, 0.34f});
            // 分割线与细边框
            int borderRaw = ColorUtils.HSLToColor(new float[]{h, 0.30f, 0.25f});
            int line = Color.argb(0x40, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));
            int border = Color.argb(0x45, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));

            // WebView 控件同系质感颜色
            int inputInner = ColorUtils.HSLToColor(new float[]{h, 0.32f, 0.16f});
            int inputBorderColor = ColorUtils.HSLToColor(new float[]{h, 0.40f, 0.30f});
            int menuInner = ColorUtils.HSLToColor(new float[]{h, 0.35f, 0.12f});
            int dialogInner = ColorUtils.HSLToColor(new float[]{h, 0.35f, 0.10f});

            return new Palette(
                    cardBg, text, textSecondary, line, handle, border,
                    toRgbaString(inputInner, 0.45f),
                    toRgbaString(inputBorderColor, 0.25f),
                    toRgbaString(darkCardRgb, 0.96f),
                    toRgbaString(menuInner, 0.96f),
                    toRgbaString(dialogInner, 0.98f),
                    toRgbaString(menuInner, 0.96f),
                    toRgbaString(inputBorderColor, 0.25f),
                    toHexString(text),
                    toHexString(textSecondary),
                    toHexString(brand),
                    toHexString(darkCardRgb)
            );
        } else {
            // ----- 浅色模式：以壁纸色相生成通透柔和的清丽浅彩 -----
            // 卡片底色：高明度柔彩（S=28%, L=95%），通透呈现壁纸专属调性！
            int lightCardRgb = ColorUtils.HSLToColor(new float[]{h, 0.28f, 0.95f});
            int cardBg = Color.argb(alpha, Color.red(lightCardRgb), Color.green(lightCardRgb), Color.blue(lightCardRgb));

            // 主文字与按钮：壁纸极深浓郁彩色（S=65%, L=16%），保证无障碍顶级对比度
            int text = ColorUtils.HSLToColor(new float[]{h, 0.65f, 0.16f});
            // 次级提示文字（S=35%, L=42%）
            int textSecondary = ColorUtils.HSLToColor(new float[]{h, 0.35f, 0.42f});
            // 品牌强调色（S=85%, L=36%）
            int brand = ColorUtils.HSLToColor(new float[]{h, 0.85f, 0.36f});

            // 拖拽横条
            int handle = ColorUtils.HSLToColor(new float[]{h, 0.36f, 0.72f});
            // 分割线与细边框
            int borderRaw = ColorUtils.HSLToColor(new float[]{h, 0.32f, 0.82f});
            int line = Color.argb(0x40, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));
            int border = Color.argb(0x45, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));

            // WebView 控件同系质感颜色
            int inputInner = ColorUtils.HSLToColor(new float[]{h, 0.22f, 0.98f});
            int inputBorderColor = ColorUtils.HSLToColor(new float[]{h, 0.35f, 0.80f});
            int menuInner = ColorUtils.HSLToColor(new float[]{h, 0.26f, 0.97f});

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
}
