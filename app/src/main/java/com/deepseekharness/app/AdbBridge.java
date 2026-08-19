package com.deepseekharness.app;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * ADB 无线配对桥（绕过 Shizuku，通道直连设备 adbd）。
 *
 * 原理：把 assets 里的 adb-pair.py / adb-shell.py / adb-setup.sh 注入 rootfs
 * /root/.dsh/，用用户反馈并实测可行的协议栈（TLS1.3-PSK + SPAKE2(AOSP)）在
 * 容器内完成「无线调试配对 → 直连 adbd」，拿到 uid=2000(shell) 权限。
 *
 * 设备内 agent 用法：/root/dsh-bin/adb-shell "<命令>"（PATH 已含 /root/dsh-bin）
 * 或 App 内 curl 不适用（容器内即本地）。
 */
public final class AdbBridge {

    private static final String[] SCRIPTS = {"adb-pair.py", "adb-shell.py", "adb-setup.sh"};

    private AdbBridge() {
    }

    /** assets 脚本是否已注入 rootfs */
    public static boolean injected(ProotBootstrap proot) {
        String r = proot.execAndRead("test -f /root/.dsh/adb-pair.py && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** 幂等注入：把三个 assets 脚本 base64 写入 /root/.dsh/ 并加执行位 */
    public static String inject(Context ctx, ProotBootstrap proot) {
        StringBuilder cmds = new StringBuilder("set -e; mkdir -p /root/.dsh; ");
        for (String name : SCRIPTS) {
            String content = readAsset(ctx, name);
            if (content.isEmpty()) continue;
            String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            cmds.append("printf '%s' '").append(b64).append("' | base64 -d > /root/.dsh/").append(name)
                    .append("; chmod +x /root/.dsh/").append(name).append("; ");
        }
        return proot.execAndRead(cmds.toString());
    }

    /** 幂等安装：Python 依赖 + ADB 密钥 + /root/dsh-bin/adb-shell 包装（仅环境未就绪时调用） */
    public static String setup(ProotBootstrap proot) {
        return proot.execAndRead("bash /root/.dsh/adb-setup.sh 2>&1");
    }

    /** 幂等准备：注入脚本 + 确保依赖/密钥（首次较慢，此后秒回）。返回执行日志 */
    public static String ensureReady(Context ctx, ProotBootstrap proot) {
        StringBuilder sb = new StringBuilder();
        if (!injected(proot)) {
            sb.append(inject(ctx, proot));
        }
        if (keyPresent(proot) && depsOk(proot)) {
            return "SETUP_DONE"; // 环境已就绪，跳过安装
        }
        sb.append(setup(proot));
        return sb.toString();
    }

    /** 密钥是否已生成 */
    public static boolean keyPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** 依赖是否已装（adb_shell_wifi + spake2_cffi） */
    public static boolean depsOk(ProotBootstrap proot) {
        String r = proot.execAndRead("python3 -c 'import adb_shell_wifi,spake2_cffi' 2>/dev/null && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** 单次配对。pairPort 为空时脚本内尝试 mdns 发现；connectPort 默认 5555 */
    public static String pair(ProotBootstrap proot, String code, String pairPort, String connectPort) {
        String c = "python3 /root/.dsh/adb-pair.py --code '" + esc(code) + "'";
        if (pairPort != null && !pairPort.trim().isEmpty()) {
            c += " --port " + pairPort.trim();
        }
        if (connectPort != null && !connectPort.trim().isEmpty()) {
            c += " --connect-port " + connectPort.trim();
        }
        return proot.execAndRead(c);
    }

    /** 状态快照：key/deps/connect_port（供 UI 展示） */
    public static String status(ProotBootstrap proot) {
        String cmd = "K=$(test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO); "
                + "D=$(python3 -c 'import adb_shell_wifi,spake2_cffi' 2>/dev/null && echo YES || echo NO); "
                + "P=$(test -f /root/.dsh/adbkeys/connect_port && cat /root/.dsh/adbkeys/connect_port || echo -); "
                + "echo 'key='$K' deps='$D' port='$P";
        return proot.execAndRead(cmd);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }

    private static String readAsset(Context ctx, String name) {
        try {
            java.io.InputStream in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
