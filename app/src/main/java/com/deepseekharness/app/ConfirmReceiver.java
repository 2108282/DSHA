package com.deepseekharness.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import java.io.File;

public class ConfirmReceiver extends BroadcastReceiver {

    public static final String ACTION_ALLOW = "com.deepseekharness.app.CONFIRM_ALLOW";
    public static final String ACTION_DENY = "com.deepseekharness.app.CONFIRM_DENY";
    public static final String ACTION_ASK_ANSWER = "com.deepseekharness.app.ASK_ANSWER";
    public static final String ACTION_ASK_REPLY = "com.deepseekharness.app.ASK_REPLY";
    public static final String ACTION_STOP_TASK = "com.deepseekharness.app.STOP_TASK";

    public static final String EXTRA_EPOCH = "confirm_epoch";
    public static final String EXTRA_ANSWER = "ask_answer";
    public static final String EXTRA_REPLY_TEXT = "ask_reply_text";

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

        if (svc != null) {
            long epoch = intent.getLongExtra(EXTRA_EPOCH, -1L);
            if (ACTION_ASK_REPLY.equals(act)) {
                CharSequence cs = null;
                try {
                    Bundle result = RemoteInput.getResultsFromIntent(intent);
                    cs = result != null ? result.getCharSequence(EXTRA_REPLY_TEXT) : null;
                } catch (Throwable ignored) {}
                String text = cs != null ? cs.toString().trim() : "";
                if (!text.isEmpty()) svc.resolveAsk(text, epoch);
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
        // 1. 取消运行中通知 2003 与历史旧通知
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Constants.NOTIF_TASK);
            nm.cancel(Constants.NOTIF_TASK_RUNNING);
            nm.cancel(Constants.NOTIF_TASK_STOPPED);
        }

        // 2. 立即注销清理租约文件，关闭设备操作权限；通知 DSH 插件停止 Agent 工作
        try {
            HarnessController hc = HarnessController.get(ctx);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                File dshDir = new File(hc.getProot().getRootfsDir(), "root/.dsh");
                if (!dshDir.exists()) dshDir.mkdirs();

                File lf = new File(dshDir, ".auth_lease");
                if (lf.exists()) lf.delete();

                File cancelFlag = new File(dshDir, ".cancel_requested");
                cancelFlag.createNewFile();

                new Thread(() -> {
                    try {
                        hc.getProot().execAndRead("killall -9 bash python3 2>/dev/null || true");
                    } catch (Throwable ignored) {}
                }, "stop-task-kill").start();
            }
        } catch (Throwable ignored) {}

        showStoppedNotification(ctx);
    }

    private void showStoppedNotification(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    Constants.CHANNEL_TASK_RESULT, "任务结果与交互",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("智能体任务完成、异常结束或终止时的结果通知");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        // 解耦抽屉组件，点击通知直接唤起主界面
        Intent openAppIntent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 201, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent actionIntent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent actionPi = PendingIntent.getActivity(ctx, 202, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_alarm_white, "💬 返回对话", actionPi)
                .build();

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, Constants.CHANNEL_TASK_RESULT)
                .setSmallIcon(R.drawable.ic_whale_logo)
                .setContentTitle("⚠️ 任务已终止")
                .setContentText("已按指令停止操作。点击查看或继续对话。")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("已按指令停止操作。点击查看或继续对话。"))
                .setContentIntent(contentPi)
                .addAction(replyAction)
                .setOngoing(true)
                .setAutoCancel(true);

        HttpShellService.attachFocusCapsule(ctx, nb, "⚠️ 任务已终止", "已按指令停止操作。点击查看或继续对话。", "任务状态", "返回对话", "已终止", actionPi, true);
        nb.setOnlyAlertOnce(false); // 👈 坚决解除静默压制，确保弹出悬浮卡

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Constants.NOTIF_TASK, nb.build());
    }
}
