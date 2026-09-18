package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.webkit.WebView;

/**
 * 专为 DSHA 移动端定制的 WebView 容器：
 * 纯 Android Native 层重写软键盘输入法（IME）与按键调度，
 * 将软键盘右下角强制设为“换行（↵）”并将回车按键转换为多行换行（Shift+Enter），
 * 杜绝移动端点击回车直接触发网页消息发送的问题；完全不修改 DSH 网页源码，亦不影响局域网电脑访问。
 */
public class DshaWebView extends WebView {

    public DshaWebView(Context context) {
        super(context);
    }

    public DshaWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DshaWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection target = super.onCreateInputConnection(outAttrs);
        if (target == null) {
            return null;
        }

        if (outAttrs != null) {
            // 1. 移除发送、完成等动作标志，通知系统软键盘显示为换行弯箭头（↵）
            outAttrs.imeOptions &= ~EditorInfo.IME_ACTION_SEND;
            outAttrs.imeOptions &= ~EditorInfo.IME_ACTION_GO;
            outAttrs.imeOptions &= ~EditorInfo.IME_ACTION_SEARCH;
            outAttrs.imeOptions &= ~EditorInfo.IME_ACTION_DONE;
            outAttrs.imeOptions &= ~EditorInfo.IME_MASK_ACTION;
            outAttrs.imeOptions |= EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION;

            // 2. 注入多行文本标记，使第三方输入法识别为普通富文本/换行框
            outAttrs.inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        }

        // 3. 包装 InputConnection，拦截软键盘派发的 Enter 按键与 Action 动作
        return new InputConnectionWrapper(target, true) {
            @Override
            public boolean performEditorAction(int editorAction) {
                sendShiftEnter();
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    if (!event.isShiftPressed() && !event.isCtrlPressed() && !event.isAltPressed() && !event.isMetaPressed()) {
                        KeyEvent shiftEnter = new KeyEvent(
                                event.getDownTime(),
                                event.getEventTime(),
                                event.getAction(),
                                KeyEvent.KEYCODE_ENTER,
                                event.getRepeatCount(),
                                KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON
                        );
                        return super.sendKeyEvent(shiftEnter);
                    }
                }
                return super.sendKeyEvent(event);
            }

            private void sendShiftEnter() {
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0,
                        KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
                KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0,
                        KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
                sendKeyEvent(down);
                sendKeyEvent(up);
            }
        };
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 4. 兼顾外接/物理实体键盘：若用户单按普通 Enter，同样转为 Shift+Enter 实现换行
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            if (!event.isShiftPressed() && !event.isCtrlPressed() && !event.isAltPressed() && !event.isMetaPressed()) {
                KeyEvent shiftEnter = new KeyEvent(
                        event.getDownTime(),
                        event.getEventTime(),
                        event.getAction(),
                        KeyEvent.KEYCODE_ENTER,
                        event.getRepeatCount(),
                        KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON
                );
                return super.dispatchKeyEvent(shiftEnter);
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
