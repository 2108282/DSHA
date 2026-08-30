package com.deepseekharness.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 后台安全确认与助手提问通知的按钮接收器：用户点通知按钮后
 * 通知 HttpShellService 释放挂起的确认或问答。
 */
public class ConfirmReceiver extends BroadcastReceiver {

    public static final String ACTION_ALLOW = "com.deepseekharness.app.CONFIRM_ALLOW";
    public static final String ACTION_DENY = "com.deepseekharness.app.CONFIRM_DENY";
    public static final String ACTION_ASK_ANSWER = "com.deepseekharness.app.ASK_ANSWER";
    /** 确认/提问序号：由 HttpShellService 校验，过期点击（锁屏残留通知、通知历史、
     *  手表转发）会被丢弃，不会误授权给当前请求。（吸收上游 PR#24） */
    public static final String EXTRA_EPOCH = "confirm_epoch";
    public static final String EXTRA_ANSWER = "ask_answer";

    @Override
    public void onReceive(Context context, Intent intent) {
        HttpShellService svc = HttpShellService.instance();
        if (svc != null && intent != null) {
            String act = intent.getAction();
            long epoch = intent.getLongExtra(EXTRA_EPOCH, -1L);
            if (ACTION_ASK_ANSWER.equals(act)) {
                svc.resolveAsk(intent.getStringExtra(EXTRA_ANSWER), epoch);
            } else if (ACTION_ALLOW.equals(act)) {
                svc.resolveConfirm(true, epoch);
            } else if (ACTION_DENY.equals(act)) {
                svc.resolveConfirm(false, epoch);
            }
        }
    }
}
