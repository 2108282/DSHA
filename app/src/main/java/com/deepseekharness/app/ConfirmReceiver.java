package com.deepseekharness.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import java.io.File;

/**
 * 后台安全确认、助手提问与任务状态通知的按钮/输入接收器：
 * - 用户点确认/提问选项或就地输入回复
 * - 运行中通知点击「🛑 停止任务」紧急制动
 * - 完成/终止通知就地输入「💬 继续对话/重新输入」继续交流
 */
public class ConfirmReceiver extends BroadcastReceiver {

    public static final String ACTION_ALLOW = "com.deepseekharness.app.CONFIRM_ALLOW";
    public static final String ACTION_DENY = "com.deepseekharness.app.CONFIRM_DENY";
    public static final String ACTION_ASK_ANSWER = "com.deepseekharness.app.ASK_ANSWER";
    public static final String ACTION_ASK_REPLY = "com.deepseekharness.app.ASK_REPLY";
    public static final String ACTION_TASK_REPLY = "com.deepseekharness.app.TASK_REPLY";
    public static final String ACTION_STOP_TASK = "com.deepseekharness.app.STOP_TASK";

    /** 确认/提问序号：由 HttpShellService 校验，过期点击（锁屏残留通知、通知历史、
     *  手表转发）会被丢弃，不会误授权给当前请求。（吸收上游 PR#24） */
    public static final String EXTRA_EPOCH = "confirm_epoch";
    public static final String EXTRA_ANSWER = "ask_answer";
    public static final String EXTRA_REPLY_TEXT = "ask_reply_text";
    public static final String EXTRA_TASK_REPLY_TEXT = "task_reply_text";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        if (act == null) return;

        HttpShellService svc = HttpShellService.instance();

        if (ACTION_STOP_TASK.equals(act)) {
            handleStopTask(context);
            return;
        }

        if (ACTION_TASK_REPLY.equals(act)) {
            handleTaskReply(context, intent);
            return;
        }

        if (svc != null) {
            long epoch = intent.getLongExtra(EXTRA_EPOCH, -1L);
            if (ACTION_ASK_REPLY.equals(act)) {
                CharSequence cs = null;
                try {
                    Bundle result = RemoteInput.getResultsFromIntent(intent);
                    cs = result != null ? result.getCharSequence(EXTRA_REPLY_TEXT) : null;
                } catch (Throwable ignored) {}
                String text = cs != null ? cs.toString().trim() : "";
                if (!text.isEmpty()) {
                    svc.resolveAsk(text, epoch);
                }
            } else if (ACTION_ASK_ANSWER.equals(act)) {
                svc.resolveAsk(intent.getStringExtra(EXTRA_ANSWER), epoch);
            } else if (ACTION_ALLOW.equals(act)) {
                svc.resolveConfirm(true, epoch);
            } else if (ACTION_DENY.equals(act)) {
                svc.resolveConfirm(false, epoch);
            }
        }
    }

    private void handleStopTask(Context ctx) {
        // 1. 取消运行中实时状态通知
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Constants.NOTIF_TASK_RUNNING);
        }

        // 2. 立即注销清理租约文件，关闭设备操作权限
        try {
            HarnessController hc = HarnessController.get(ctx);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                File lf = new File(hc.getProot().getRootfsDir(), "root/.dsh/.auth_lease");
                if (lf.exists()) lf.delete();
            }
        } catch (Throwable ignored) {}

        // 3. 震动反馈 150ms
        try {
            android.os.Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm =
                        (android.os.VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v != null) {
                v.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {}

        // 4. Toast 提示
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "⚠️ 智能体任务已被用户紧急终止", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });

        // 5. 发送「任务已终止」通知（带 RemoteInput 重新输入框）
        showStoppedNotification(ctx);
    }

    private void handleTaskReply(Context ctx, Intent intent) {
        CharSequence cs = null;
        try {
            Bundle result = RemoteInput.getResultsFromIntent(intent);
            cs = result != null ? result.getCharSequence(EXTRA_TASK_REPLY_TEXT) : null;
        } catch (Throwable ignored) {}
        final String text = cs != null ? cs.toString().trim() : "";
        if (text.isEmpty()) return;

        // 1. 取消已完成/已终止通知
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Constants.NOTIF_TASK);
            nm.cancel(Constants.NOTIF_TASK_STOPPED);
        }

        // 2. Toast 提示收到新指令
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "✓ 收到新指令：" + (text.length() > 20 ? text.substring(0, 20) + "…" : text), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });

        // 3. 打开 App 首页以展示并继续执行会话
        try {
            Intent openIntent = new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(openIntent);
        } catch (Throwable ignored) {}
    }

    private void showStoppedNotification(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    "dsh_agent_channel", "Agent 通知",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("智能体任务状态通知");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Intent openAppIntent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 201, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 挂载 RemoteInput 重新输入组件
        RemoteInput remoteInput = new RemoteInput.Builder(EXTRA_TASK_REPLY_TEXT)
                .setLabel("输入新指令重新开始...")
                .build();
        Intent replyIntent = new Intent(ctx, ConfirmReceiver.class)
                .setAction(ACTION_TASK_REPLY);
        PendingIntent replyPi = PendingIntent.getBroadcast(ctx, 202, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));

        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_launch, "💬 重新输入", replyPi)
                .addRemoteInput(remoteInput)
                .build();

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, "dsh_agent_channel")
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("⚠️ DSHA · 任务已终止")
                .setContentText("已按指令停止操作。如需调整，可直接在下方回复新指令。")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("已按指令停止操作。如需调整，可直接在下方回复新指令。"))
                .setContentIntent(contentPi)
                .addAction(replyAction)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Constants.NOTIF_TASK_STOPPED, nb.build());
    }
}
