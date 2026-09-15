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
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, QuickChatSheetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
