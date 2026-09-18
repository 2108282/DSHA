package com.deepseekharness.app.ui;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

/**
 * 斜拉唤醒语音助手时的处理者。
 *
 * 系统唤醒链：斜拉手势 → 取 ASSISTANT 角色持有者包名 → 在该包内
 * 解析声明了 {@code android.service.voice.VoiceInteractionService} 的 Service
 * → 绑定并回调 {@link #onReady()}。
 *
 * 声明要求（见 AndroidManifest.xml 内本 Service 节点）：
 * <ul>
 *   <li>exported=true —— system_server 跨 uid 绑定</li>
 *   <li>permission=android.permission.BIND_VOICE_INTERACTION —— 仅系统可绑定，防第三方伪造唤醒</li>
 *   <li>intent-filter action 不可改 —— 系统设置「语音助手」候选列表即扫描此 action 生成</li>
 * </ul>
 *
 * 唤醒后直接拉起 {@link QuickChatSheetActivity}（底部快捷对话抽屉）。
 */
public class QuickChatVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        Intent intent = new Intent(this, QuickChatSheetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(intent);
    }
}
