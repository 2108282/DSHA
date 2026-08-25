package com.deepseekharness.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 屏幕顶部的流式悬浮条：把 agent 正在生成的内容像歌词一样实时显示出来。
 *
 * <p><b>为什么是自绘悬浮窗，而不是「状态栏歌词」。</b> 真正的状态栏歌词（MIUI/HyperOS 那种）
 * 没有公开接口：能免 root 做到的只有 Flyme / exTHmUI 这类认
 * {@code FLAG_ALWAYS_SHOW_TICKER + FLAG_ONLY_UPDATE_TICKER} 的 ROM，其余机型都要靠
 * StatusBarLyric 这类 Xposed 模块 hook 系统界面。DSHA 主打免 ROOT 免 Termux，不能把核心
 * 功能压在 root 上。所以默认走 {@code TYPE_APPLICATION_OVERLAY}：一次性授权、全 ROM 通用、
 * 位置与样式我们自己控。（装了 Lyric-Getter / SuperLyric 的用户以后可以额外对接，
 * 那是锦上添花，不是前提。）
 *
 * <p><b>不用 Service。</b> 悬浮窗只需要 {@code WindowManager} 和一个 View，而调用方
 * （{@link HttpShellService}）本身就活在前台服务进程里 —— 再包一层 Service 只会多一份
 * Android 8+ 的后台启动限制要伺候。所以这里是纯静态控制器，生命周期跟着 App 进程。
 *
 * <p><b>多实例</b>：dsh 可以同时跑多个会话，每个会话都在吐字。按 sessionKey 分桶，
 * 悬浮条只渲染「最后活跃」的那一路，并在多路并发时给它加一个短标识前缀，
 * 否则两个会话的输出会交织成乱码。
 */
final class OverlayController {

    /** 最多显示这么多字符：悬浮条只有一行，太长就从左边推掉（歌词式滚动的最简形态）。 */
    private static final int MAX_CHARS = 64;
    /** 同时记住这么多会话，超了就淘汰最早的（正常场景一两路，防跑飞）。 */
    private static final int MAX_SESSIONS = 8;
    /** 没有新内容多久后自动淡出。 */
    private static final long IDLE_HIDE_MS = 6000;

    private static final Object LOCK = new Object();
    /** sessionKey → 该会话当前显示的文本。LinkedHashMap 便于按插入顺序淘汰。 */
    private static final Map<String, String> BUFFERS = new LinkedHashMap<>();

    private static Handler main;
    private static WindowManager wm;
    private static View root;
    private static TextView label;
    private static String activeKey = "";
    private static Runnable hideTask;

    private OverlayController() {
    }

    /** 用户是否已经授予悬浮窗权限。没有权限时所有 push 直接丢弃（不弹系统弹窗骚扰）。 */
    static boolean permitted(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 功能开关（配置页控制）。默认关闭 —— 屏幕上实时显示 AI 输出，旁边的人也看得见。 */
    static boolean enabled(Context ctx) {
        try {
            return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getBoolean("overlay_stream", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 推一段内容到悬浮条。
     *
     * @param sessionKey 会话标识（同一会话的增量会拼接；空串按单会话处理）
     * @param kind       {@code delta} 追加增量 · {@code tool} 工具状态（整行替换）
     *                   · {@code text} 整行替换 · {@code done} 本轮结束 · {@code clear} 立刻收起
     * @param text       内容；{@code kind=tool} 时若只给了工具名，这里会套成「正在使用 X」
     */
    static void push(Context ctx, String sessionKey, String kind, String text) {
        if (ctx == null || !enabled(ctx) || !permitted(ctx)) return;
        final String key = sessionKey == null || sessionKey.isEmpty() ? "-" : sessionKey;
        final String k = kind == null ? "delta" : kind;
        String line;
        synchronized (LOCK) {
            if ("clear".equals(k)) {
                BUFFERS.remove(key);
                if (key.equals(activeKey)) activeKey = "";
                hideNow(ctx);
                return;
            }
            String prev = BUFFERS.get(key);
            if (prev == null) prev = "";
            String next;
            if ("delta".equals(k)) {
                next = prev + (text == null ? "" : text);
            } else if ("tool".equals(k)) {
                next = toolLine(text);
            } else if ("done".equals(k)) {
                next = prev;                      // 保留最后一句，让它自然淡出
            } else {
                next = text == null ? "" : text;  // text / 其它
            }
            next = tail(collapse(next));
            BUFFERS.put(key, next);
            while (BUFFERS.size() > MAX_SESSIONS) {
                String oldest = BUFFERS.keySet().iterator().next();
                BUFFERS.remove(oldest);
            }
            activeKey = key;
            boolean multi = BUFFERS.size() > 1;
            line = multi ? shortTag(key) + " " + next : next;
        }
        show(ctx, line);
    }

    /** 工具名 → 人话。插件侧已经会做这层映射，这里兜底：至少别让用户看到裸的内部名。 */
    private static String toolLine(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) return "正在使用工具…";
        // 插件已经给出完整句子（含「正在」）就直接用
        if (name.contains("正在") || name.contains(" ")) return name;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("bash") || lower.contains("shell") || lower.contains("exec")) {
            return "⚙ 正在执行命令";
        }
        if (lower.startsWith("read") || lower.contains("cat")) return "⚙ 正在读取文件";
        if (lower.startsWith("write") || lower.startsWith("edit")
                || lower.contains("patch")) return "⚙ 正在修改文件";
        if (lower.contains("glob") || lower.contains("grep")
                || lower.contains("search")) return "⚙ 正在搜索";
        if (lower.contains("fetch") || lower.contains("web")
                || lower.contains("http")) return "⚙ 正在联网查资料";
        if (lower.contains("todo")) return "⚙ 正在整理任务清单";
        if (lower.contains("task") || lower.contains("agent")) return "⚙ 正在派子任务";
        return "⚙ 正在使用 " + name;
    }

    /** 会话标识压成两三个字符的前缀，多路并发时用来分辨谁在说话。 */
    private static String shortTag(String key) {
        String s = key.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5]", "");
        if (s.isEmpty()) return "[·]";
        return "[" + s.substring(Math.max(0, s.length() - 2)) + "]";
    }

    /** 换行、连续空白压成单空格 —— 悬浮条只有一行，原样塞进去会看起来像卡住。 */
    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String tail(String s) {
        if (s == null) return "";
        return s.length() <= MAX_CHARS ? s : s.substring(s.length() - MAX_CHARS);
    }

    // ================= 窗口 =================

    private static void show(Context ctx, String line) {
        Handler h = mainHandler();
        h.post(() -> {
            try {
                ensureView(ctx);
                if (label != null) label.setText(line);
                if (root != null && root.getVisibility() != View.VISIBLE) {
                    root.setVisibility(View.VISIBLE);
                }
                if (hideTask != null) h.removeCallbacks(hideTask);
                hideTask = () -> hideNow(ctx);
                h.postDelayed(hideTask, IDLE_HIDE_MS);
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "悬浮条更新失败: " + e);
            }
        });
    }

    private static void hideNow(Context ctx) {
        Handler h = mainHandler();
        h.post(() -> {
            try {
                if (root != null) root.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 彻底移除窗口（关开关 / 撤权限时调用）。 */
    static void teardown(Context ctx) {
        Handler h = mainHandler();
        h.post(() -> {
            synchronized (LOCK) {
                BUFFERS.clear();
                activeKey = "";
            }
            try {
                if (wm != null && root != null) wm.removeViewImmediate(root);
            } catch (Throwable ignored) {
            }
            root = null;
            label = null;
            wm = null;
        });
    }

    private static Handler mainHandler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static void ensureView(Context ctx) {
        if (root != null) return;
        Context app = ctx.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        TextView tv = new TextView(app);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.START);   // 从左边截，新字始终可见
        int padH = dp(app, 12), padV = dp(app, 6);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(app, 16));
        bg.setColor(Color.argb(0xD8, 0x11, 0x14, 0x1A));   // 半透明深底，浅色壁纸上也读得清
        tv.setBackground(bg);
        tv.setVisibility(View.GONE);
        // 点一下先收起来：悬浮窗最烦人的就是挡住东西又赶不走
        tv.setOnClickListener(v -> v.setVisibility(View.GONE));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // NOT_FOCUSABLE：不抢输入焦点（否则输入法会被顶掉）
        // NOT_TOUCH_MODAL：条子以外的触摸照常传给下面的应用
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        lp.format = android.graphics.PixelFormat.TRANSLUCENT;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // 贴在状态栏下沿：不遮挡时钟与刘海，也不跟系统的下拉手势抢区域
        lp.y = dp(app, 34);

        try {
            wm.addView(tv, lp);
            root = tv;
            label = tv;
        } catch (Throwable e) {
            // 权限被撤、或某些 ROM 拒绝 → 安静降级，不影响 agent 干活
            android.util.Log.w("DSHA", "悬浮条创建失败（权限被撤？）: " + e);
            root = null;
            label = null;
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
