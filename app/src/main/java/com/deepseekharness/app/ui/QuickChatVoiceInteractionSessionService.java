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
 * 唤醒时系统创建会话并回调 {@link #onNewSession(Bundle)}，在此直接拉起
 * {@link QuickChatSheetActivity}（底部快捷对话抽屉），由其接管全部交互。
 */
public class QuickChatVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        Intent intent = new Intent(this, QuickChatSheetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return super.onNewSession(args);
    }
}
