package com.deepseekharness.app.ui;

import android.content.Intent;
import android.speech.RecognitionService;

/**
 * 语音识别服务（VoiceInteractionServiceInfo 解析的必填项）。
 *
 * 系统 ASSISTANT 候选资格要求元数据中必须声明 recognitionService，否则
 * getParseError() 返回 "No recognitionService specified"，候选列表不显示本应用。
 *
 * 本类仅为满足候选资格存在；唤醒后的实际输入由 {@link QuickChatSheetActivity} 自行处理，
 * 不走系统 RecognitionService 通路，因此所有回调直接返回错误（未使用）。
 * RecognitionService 是 abstract，必须实现 onStartListening/onStopListening/onCancel。
 */
public class QuickChatRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        if (listener != null) {
            listener.error(1); // ERROR_NETWORK_TIMEOUT：表示不走此通路
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        // 无操作
    }

    @Override
    protected void onCancel(Callback listener) {
        // 无操作
    }
}
