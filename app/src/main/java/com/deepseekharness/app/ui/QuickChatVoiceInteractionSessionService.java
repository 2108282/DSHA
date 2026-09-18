package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * 语音交互会话服务（VoiceInteractionServiceInfo 解析的必填项）。
 *
 * 系统 ASSISTANT 候选资格要求 VoiceInteractionService 的元数据中必须声明 sessionService，
 * 否则 getParseError() 返回 "No sessionService specified"，候选列表不显示本应用。
 *
 * 唤醒时系统创建会话并回调 {@link #onNewSession(Bundle)}，必须返回一个非空
 * VoiceInteractionSession 实例（不能 super 返回 null）。在此返回空会话，
 * 并直接拉起 {@link QuickChatSheetActivity}（底部快捷对话抽屉）接管全部交互。
 */
public class QuickChatVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        Intent intent = new Intent(this, QuickChatSheetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(intent);
        return new VoiceInteractionSession(this);
    }
}
