package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.deepseekharness.app.R;

/** 两种网页内核共用全屏窗口，保留挖孔安全区、输入法缩放与系统返回手势。 */
public final class WebFullscreenUi {
    /** 避免全局页面边距处理覆盖网页的全屏设置。 */
    public interface Host { }

    private WebFullscreenUi() { }

    public static void install(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        View content = activity.findViewById(android.R.id.content);
        content.setBackgroundColor(activity.getColor(R.color.surface));
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            // 临时唤出的系统栏覆盖页面；键盘和不可隐藏的窗口标题栏仍需避让。
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.captionBar());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
            return WindowInsetsCompat.CONSUMED;
        });
        hideSystemBars(activity);
        ViewCompat.requestApplyInsets(content);
    }

    public static void hideSystemBars(Activity activity) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }
}
