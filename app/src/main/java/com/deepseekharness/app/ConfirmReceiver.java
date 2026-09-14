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

import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.MainActivity;
import com.deepseekharness.app.ui.QuickChatSheetActivity;
import com.deepseekharness.app.util.Constants;

import java.io.File;

/**
 * 后台安全确认、助手提问与任务状态通知的按钮/输入接收器：
 * - 用户点确认/提问选项或就地输入回复
 * - 运行中通知点击「🛑 停止任务」紧急制动（向容器注入 cancel 标志并终止活动工具子进程）
 * - 完成/终止通知就地输入「💬 继续对话/重新输入」继续交流（向容器写入 prompt 指令自动开启新轮次）
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
                triggerVibrate(context, 50);
                writeApprovalDecision("allowed-once");
                svc.resolveConfirm(true, epoch);
            } else if (ACTION_DENY.equals(act)) {
                triggerVibrate(context, 50);
                writeApprovalDecision("rejected");
                svc.resolveConfirm(false, epoch);
            }
        } else {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && (ACTION_ALLOW.equals(act) || ACTION_DENY.equals(act))) {
                nm.cancel(Constants.NOTIF_SHELL_CONFIRM);
            }
            if (ACTION_ALLOW.equals(act)) {
                writeApprovalDecision("allowed-once");
            } else if (ACTION_DENY.equals(act)) {
                writeApprovalDecision("rejected");
            }
        }
    }

    private void handleStopTask(Context ctx) {
        HttpShellService.isTaskActive = false;
        HarnessService.onTaskStateChanged(ctx, false);
        // 1. 取消运行中实时状态通知
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Constants.NOTIF_TASK);
            nm.cancel(Constants.NOTIF_TASK_RUNNING);
            nm.cancel(Constants.NOTIF_TASK_STOPPED);
        }

        // 2. 写入 Native 容器私有的取消标志文件（隔离独立，绝不影响其他容器或宿主进程）
        new Thread(() -> {
            try {
                String cmd = "mkdir -p /data/adb/dsha/rootfs/root/.dsh 2>/dev/null && "
                        + "touch /data/adb/dsha/rootfs/root/.dsh/.cancel_requested && "
                        + "chmod 666 /data/adb/dsha/rootfs/root/.dsh/.cancel_requested 2>/dev/null; "
                        + "rm -f /data/adb/dsha/rootfs/root/.dsh/.auth_lease 2>/dev/null";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable ignored) {}
        }, "stop-task-cancel").start();

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

        // 4. 先走通知：发送「任务已终止」通知写入「任务结果与交互」独立通道（带 RemoteInput 重新输入框）
        showStoppedNotification(ctx);

        // 5. 再走 Toast 提示
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "⚠️ 智能体任务已被用户紧急终止", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
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

        // 2. 将待发送指令写入容器 pending_prompt，由 dsh-task-notifier 插件自动注入 DSH 开启新轮次
        try {
            HarnessController hc = HarnessController.get(ctx);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                File dshDir = new File(hc.getProot().getRootfsDir(), "root/.dsh");
                if (!dshDir.exists()) dshDir.mkdirs();
                File promptFile = new File(dshDir, ".pending_prompt");
                java.nio.file.Files.write(promptFile.toPath(),
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}

        // 3. Toast 提示收到新指令
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "✓ 收到新指令：" + (text.length() > 20 ? text.substring(0, 20) + "…" : text), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });

        // 4. 唤起快捷对话抽屉继续会话
        try {
            Intent openIntent = new Intent(ctx, QuickChatSheetActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("dsh_reply_text", text);
            ctx.startActivity(openIntent);
        } catch (Throwable ignored) {}
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

        Intent openAppIntent = new Intent(ctx, QuickChatSheetActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 205, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 点击「💬 返回对话」直接从屏幕底部唤起抽屉弹层
        Intent actionIntent = new Intent(ctx, QuickChatSheetActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent actionPi = PendingIntent.getActivity(ctx, 206, actionIntent,
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
        nb.setOnlyAlertOnce(false);

        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Constants.NOTIF_TASK, nb.build());
        } catch (Throwable ignored) {}
    }

    private static void triggerVibrate(Context ctx, int ms) {
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
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {}
    }

    private static void writeApprovalDecision(String decision) {
        new Thread(() -> {
            try {
                String cmd = "mkdir -p /data/adb/dsha/rootfs/root/.dsh 2>/dev/null && "
                        + "echo -n '" + decision + "' > /data/adb/dsha/rootfs/root/.dsh/.approval_decision && "
                        + "chmod 666 /data/adb/dsha/rootfs/root/.dsh/.approval_decision 2>/dev/null";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable ignored) {}
        }, "approval-decision-writer").start();
    }
}
