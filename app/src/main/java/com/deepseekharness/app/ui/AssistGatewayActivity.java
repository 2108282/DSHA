package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * ACTION_ASSIST 网关 Activity。
 *
 * 小米斜拉/小白条手势（gesture_line_wake_up）触发时发送 android.intent.action.ASSIST，
 * 系统按当前 ASSISTANT 角色持有者包名查找本包内声明该 action 的组件。
 * 若本包无组件声明 ACTION_ASSIST，手势广播无人接收 → 表现为"换了助理后手势失效"。
 *
 * 本 Activity 仅为接收广播存在，立即转拉 {@link QuickChatSheetActivity} 并自我关闭。
 * 注意：声明的 intent-filter 需含 android.intent.category.DEFAULT，才能被隐式 Intent 命中。
 */
public class AssistGatewayActivity extends Activity {

    public static final String EXTRA_START_SOURCE = "EXTRA_START_SOURCE";
    public static final String SOURCE_GESTURE = "GESTURE_WAKEUP";

    private static volatile long sLastGatewayLaunchTime = 0L;
    private static final long GATEWAY_DEBOUNCE_MS = 600L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastGatewayLaunchTime < GATEWAY_DEBOUNCE_MS) {
            // 防抖守卫：600ms 内重复手势或系统 Fallback 重复唤起直接静默结束，杜绝两次拉起
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        sLastGatewayLaunchTime = now;

        Intent intent = new Intent(this, QuickChatSheetActivity.class);
        intent.setAction(Intent.ACTION_ASSIST);
        intent.putExtra(EXTRA_START_SOURCE, SOURCE_GESTURE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }
}
