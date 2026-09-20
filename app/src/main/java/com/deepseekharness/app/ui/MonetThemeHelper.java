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

    public interface OnThemeSeedReadyListener {
        void onThemeSeedReady(int seedColor);
    }

    private static volatile Integer sCachedSeedColor = null;
    private static final java.util.concurrent.ExecutorService sSamplingExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 清除壁纸/屏幕颜色缓存（在设置页切换或手动刷新时调用） */
    public static void clearCache(Context context) {
        sCachedSeedColor = null;
        if (context != null) {
            try {
                File cacheFile = new File(context.getCacheDir(), "wallpaper_monet_seed.jpg");
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
                File rawFile = new File(context.getCacheDir(), "screen_sampling.raw");
                if (rawFile.exists()) {
                    rawFile.delete();
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void clearCache() {
        sCachedSeedColor = null;
    }

    private static volatile boolean sIsSampling = false;

    /**
     * 变色龙机制：异步提取当前屏幕画面上半部分（避开抽屉遮挡）的代表色。
     * 采样完成后若识别到新环境色，通过回调通知主线程刷新主题。
     */
    public static void refreshAdaptiveSeedAsync(Context context, OnThemeSeedReadyListener listener) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        if (sIsSampling) return;
        sIsSampling = true;
        sSamplingExecutor.execute(() -> {
            try {
                int screenSeed = extractScreenAdaptiveSeed(appCtx);
                // 核心关键修复：只有当从真实屏幕上成功采样出鲜艳环境色时，才允许更新与通知！
                // 若屏幕当前为纯黑白、息屏或正在渲染过渡，坚决保持现有色彩，绝对不重置回原样！
                if (screenSeed != 0) {
                    Integer oldSeed = sCachedSeedColor;
                    sCachedSeedColor = screenSeed;
                    android.util.Log.i("DSHA_MONET", "Screen sample success: seed=" + toHexString(screenSeed)
                            + " (old=" + (oldSeed != null ? toHexString(oldSeed) : "null") + ")");
                    if (listener != null) {
                        if (oldSeed == null || isSignificantlyDifferent(oldSeed, screenSeed)) {
                            listener.onThemeSeedReady(screenSeed);
                        }
                    }
                } else {
                    android.util.Log.d("DSHA_MONET", "Screen sample skipped: no valid vibrant pixels, keeping current theme");
                }
            } catch (Throwable t) {
                android.util.Log.w("DSHA_MONET", "Screen sampling error: " + t.getMessage());
            } finally {
                sIsSampling = false;
            }
        });
    }

    /** 在手势唤醒的第 0 毫秒（此时屏幕无抽屉无遮罩）执行预抓帧 */
    public static void triggerPreCapture(Context context) {
        refreshAdaptiveSeedAsync(context, null);
    }

    private static boolean isSignificantlyDifferent(int c1, int c2) {
        float[] hsl1 = new float[3];
        float[] hsl2 = new float[3];
        ColorUtils.colorToHSL(c1, hsl1);
        ColorUtils.colorToHSL(c2, hsl2);
        float hueDiff = Math.abs(hsl1[0] - hsl2[0]);
        if (hueDiff > 180f) hueDiff = 360f - hueDiff;
        return hueDiff > 12f || Math.abs(hsl1[1] - hsl2[1]) > 0.20f;
    }

    /** 从屏幕上半部分（Y 在 8% ~ 42% 之间）提取当前运行画面的鲜艳代表色 */
    private static int extractScreenAdaptiveSeed(Context context) {
        int extracted = 0;
        File rawFile = new File(context.getCacheDir(), "screen_sampling.raw");
        try {
            String rawPath = rawFile.getAbsolutePath();
            // -d 0 强制指定 Display 0 物理合成图层，穿透小米澎湃 OS 对半透明 Window 的图层过滤
            String cmd = "screencap -d 0 " + rawPath + " 2>/dev/null || screencap " + rawPath + "; chmod 666 " + rawPath;
            HttpShellService.execRootCommand(cmd);

            if (rawFile.exists() && rawFile.length() > 16) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(rawFile, "r")) {
                    byte[] hdr = new byte[16];
                    raf.readFully(hdr);
                    int w = ((hdr[0] & 0xFF)) | ((hdr[1] & 0xFF) << 8) | ((hdr[2] & 0xFF) << 16) | ((hdr[3] & 0xFF) << 24);
                    int h = ((hdr[4] & 0xFF)) | ((hdr[5] & 0xFF) << 8) | ((hdr[6] & 0xFF) << 16) | ((hdr[7] & 0xFF) << 24);
                    if (w > 0 && h > 0 && w <= 8192 && h <= 8192) {
                        // 避开系统状态栏（顶部 8%）与抽屉可能覆盖的下半区（> 42%）
                        int yStart = (int) (h * 0.08f);
                        int yEnd = (int) (h * 0.42f);
                        int stepY = Math.max(1, (yEnd - yStart) / 22);
                        int stepX = Math.max(1, w / 22);
                        int stride = w * 4;
                        byte[] row = new byte[stride];
                        float[] hsl = new float[3];
                        int bestColor = 0;
                        float bestScore = -1f;

                        for (int y = yStart; y < yEnd; y += stepY) {
                            raf.seek(16L + (long) y * stride);
                            raf.readFully(row);
                            for (int x = stepX / 2; x < w; x += stepX) {
                                int offset = x * 4;
                                int r = row[offset] & 0xFF;
                                int g = row[offset + 1] & 0xFF;
                                int b = row[offset + 2] & 0xFF;
                                int pixel = Color.rgb(r, g, b);
                                ColorUtils.colorToHSL(pixel, hsl);
                                float sat = hsl[1];
                                float lum = hsl[2];
                                // 黑雾逆向宽容阈值：放宽明度至 0.05 ~ 0.90，饱和度 >= 0.07，确保 Dim 蒙层下仍能准确抓取真实色相
                                if (lum >= 0.05f && lum <= 0.90f && sat >= 0.07f) {
                                    float score = sat * 0.8f + (1.0f - Math.abs(lum - 0.45f) * 2f) * 0.2f;
                                    if (score > bestScore) {
                                        bestScore = score;
                                        bestColor = pixel;
                                    }
                                }
                            }
                        }
                        if (bestColor != 0) {
                            extracted = bestColor;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (rawFile.exists()) {
                rawFile.delete();
            }
        }

        return extracted;
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

        // ================= 提取屏幕/壁纸自适应色彩的莫奈调色板 =================
        int seed = (sCachedSeedColor != null) ? sCachedSeedColor : getWallpaperSeedColor(ctx);
        float[] seedHsl = new float[3];
        ColorUtils.colorToHSL(seed, seedHsl);
        float h = seedHsl[0]; // 环境色相 0 ~ 360°

        if (isDarkMode) {
            // ----- 深色反色模式：以环境色相生成深邃微透光暗彩色，文字彻底柔化护眼，杜绝高亮刺眼 -----
            // 卡片底色：深沉暗彩（S=24%, L=8.5%），深邃沉静，带有环境色高级底蕴
            int darkCardRgb = ColorUtils.HSLToColor(new float[]{h, 0.24f, 0.085f});
            int cardBg = Color.argb(alpha, Color.red(darkCardRgb), Color.green(darkCardRgb), Color.blue(darkCardRgb));

            // 主文字与按钮：采用护眼高质感中性暖灰白（S=5%, L=88%），对比度极高且柔和舒适，绝不刺眼！
            int text = ColorUtils.HSLToColor(new float[]{h, 0.05f, 0.88f});
            // 次级提示文字：微色温静音中灰（S=8%, L=60%），柔和耐看
            int textSecondary = ColorUtils.HSLToColor(new float[]{h, 0.08f, 0.60f});
            // 品牌强调色：环境色专属调色（S=65%, L=64%），兼顾醒目辨识度与柔和雅致
            int brand = ColorUtils.HSLToColor(new float[]{h, 0.65f, 0.64f});

            // 拖拽横条：低调内敛同系色
            int handle = ColorUtils.HSLToColor(new float[]{h, 0.20f, 0.28f});
            // 分割线与细边框：细腻微光
            int borderRaw = ColorUtils.HSLToColor(new float[]{h, 0.22f, 0.22f});
            int line = Color.argb(0x35, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));
            int border = Color.argb(0x3A, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));

            // WebView 控件同系质感颜色
            int inputInner = ColorUtils.HSLToColor(new float[]{h, 0.20f, 0.14f});
            int inputBorderColor = ColorUtils.HSLToColor(new float[]{h, 0.28f, 0.26f});
            int menuInner = ColorUtils.HSLToColor(new float[]{h, 0.22f, 0.11f});
            int dialogInner = ColorUtils.HSLToColor(new float[]{h, 0.24f, 0.09f});

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
            // ----- 浅色模式：以环境色相生成通透柔和的清丽浅彩 -----
            int lightCardRgb = ColorUtils.HSLToColor(new float[]{h, 0.22f, 0.96f});
            int cardBg = Color.argb(alpha, Color.red(lightCardRgb), Color.green(lightCardRgb), Color.blue(lightCardRgb));

            // 主文字与按钮：深邃墨色（S=18%, L=14%），阅读清晰舒适
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
}
