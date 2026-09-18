package com.deepseekharness.app.ui;

import android.service.voice.VoiceInteractionService;

/**
 * 官方标准语音交互服务（VoiceInteractionService）。
 *
 * 当用户将本应用设为系统「默认数字助理」时，系统服务（VoiceInteractionManagerService）
 * 会在后台绑定此 Service 并回调 {@link #onReady()}。
 *
 * 【重要规范】
 * {@link #onReady()} 仅表示服务生命周期与系统通道建立完成，绝不能在此处主动弹窗或拉起 Activity，
 * 否则会导致用户切换助理设置或开机开服务时误弹抽屉界面。
 * 真正的手势与按键唤醒事件由系统分发至绑定的 {@link QuickChatVoiceInteractionSessionService}。
 */
public class QuickChatVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        // 仅作为服务初始化就绪标记，不主动拉起界面
    }
}
