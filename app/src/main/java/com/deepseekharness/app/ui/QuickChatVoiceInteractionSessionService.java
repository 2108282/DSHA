package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * 官方标准语音交互会话服务（VoiceInteractionSessionService）。
 *
 * 当本应用被设为系统默认助理后，用户每次触发手势（底角斜拉、长按 Home / 小白条、语音按键）时，
 * 系统均会通过 Binder 回调当前会话的 {@link VoiceInteractionSession#onShow(Bundle, int)}。
 */
public class QuickChatVoiceInteractionSessionService extends VoiceInteractionSessionService {

    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new QuickChatVoiceSession(this);
    }

    /**
     * 自定义 VoiceInteractionSession。
     * 捕获系统手势分发的 onShow 事件，秒级拉起 DSHA 快捷抽屉，并立即隐藏系统会话窗口。
     */
    private static class QuickChatVoiceSession extends VoiceInteractionSession {

        public QuickChatVoiceSession(Context context) {
            super(context);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            Context ctx = getContext();
            if (ctx != null) {
                Intent intent = new Intent(ctx, QuickChatSheetActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                ctx.startActivity(intent);
            }
            // 立即隐藏系统默认的空浮层窗口，确保快捷抽屉独占焦点与全屏手势
            hide();
        }
    }
}
