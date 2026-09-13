package com.deepseekharness.app.bridge;

import android.content.Context;
import android.util.Base64;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ADB 无线配对桥（绕过 Shizuku，通道直连设备 adbd）。
 * 把 assets 里的 adb-pair.py / adb-shell.py / adb-setup.sh 注入 rootfs，
 * 在容器内用 TLS1.3-PSK + SPAKE2 完成「无线调试配对 → 直连 adbd」，拿到 uid=2000(shell)。
 */
public final class AdbBridge {

    private static final String[] SCRIPTS = {"adb-pair.py", "adb-shell.py", "adb-setup.sh"};
    /** assets 脚本版本：每次改脚本 +1，旧 APK 的残留脚本会因版本不符被强制重注入。
     *  14：直连自检改用配对成功地址 + mDNS 重发现连接端口（127.0.0.1:5555 常连不上）。 */
    private static final String SCRIPT_VERSION = "14";

    private AdbBridge() {
    }

    public static boolean injected(ProotBootstrap proot) {
        return "YES".equals(injectedState(proot));
    }

    private static String injectedState(ProotBootstrap proot) {
        String r = proot.execAndRead(
                "test -f /root/.dsh/script-version && cat /root/.dsh/script-version || echo NO");
        if (r == null) return "UNKNOWN";
        String v = r.trim();
        if (v.isEmpty()) return "UNKNOWN";
        return SCRIPT_VERSION.equals(v) ? "YES" : "NO";
    }

    /** 幂等注入：把三个 assets 脚本 base64 写入 /root/.dsh/ 并加执行位 + 写版本标记。 */
    public static String inject(Context ctx, ProotBootstrap proot) {
        StringBuilder cmds = new StringBuilder("set -e; mkdir -p /root/.dsh; ");
        for (String name : SCRIPTS) {
            String content = readAsset(ctx, name);
            if (content.isEmpty()) continue;
            String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            cmds.append("printf '%s' '").append(b64).append("' | base64 -d > /root/.dsh/").append(name)
                    .append("; chmod +x /root/.dsh/").append(name).append("; ");
        }
        cmds.append("printf '%s' '").append(SCRIPT_VERSION).append("' > /root/.dsh/script-version; ");
        return proot.execAndRead(cmds.toString());
    }

    private static String setup(ProotBootstrap proot) {
        return proot.execAndReadWithProot("bash /root/.dsh/adb-setup.sh 2>&1", 180_000);
    }

    /** 幂等准备：注入脚本 + wheels + glibc python + Java 解包 wheels（适配 Android 无网、无 pip）。 */
    public static String ensureReady(Context ctx, ProotBootstrap proot) {
        StringBuilder sb = new StringBuilder();
        if (!injected(proot)) sb.append(inject(ctx, proot));
        if (!wheelsPresent(proot)) sb.append(injectWheels(ctx, proot));
        if (!proot.ensureGlibcPython()) {
            sb.append("GLIBC_PY_INSTALL_FAIL: 无法安装 glibc Python3\n");
        }
        sb.append(extractWheelsJava(proot)); // 直接用 Java 解 wheels（zip），绕开 pip/zlib
        if (keyPresent(proot) && depsOk(proot) && wrapperPresent(proot)) {
            return "SETUP_DONE";
        }
        sb.append(setup(proot));
        return sb.toString();
    }

    /**
     * 用 Java 把 wheels（zip）解包到 glibc python 的 dist-packages。
     * adb_shell_wifi / spake2 / cryptography 是 manylinux(glibc) 轮子，bionic 的 Termux python
     * 加载不了；glibc python + 本方法 = 与 1.1.9.1（rootfs 预装 glibc python3）等效。
     */
    public static String extractWheelsJava(ProotBootstrap proot) {
        try {
            File wheelsDir = new File(proot.getRootfsDir(), "root/.dsh/wheels");
            File site = new File(proot.getRootfsDir(), "usr/lib/python3/dist-packages");
            if (!site.isDirectory() && !site.mkdirs()) {
                return "WHEELS_EXTRACT_FAIL: 建不了 dist-packages";
            }
            File[] whls = wheelsDir.listFiles((d, n) -> n.endsWith(".whl"));
            if (whls == null || whls.length == 0) {
                return "WHEELS_EXTRACT_FAIL: wheels 目录为空";
            }
            int n = 0;
            for (File whl : whls) {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(whl)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry e = en.nextElement();
                        String name = e.getName();
                        if (name.contains("..")) continue; // 防路径穿越
                        File out = new File(site, name);
                        if (e.isDirectory()) {
                            out.mkdirs();
                            continue;
                        }
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        try (java.io.InputStream in = zf.getInputStream(e);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                            byte[] b = new byte[65536];
                            int c;
                            while ((c = in.read(b)) != -1) fos.write(b, 0, c);
                        }
                    }
                } catch (Exception ignore) {
                }
                n++;
            }
            return "WHEELS_JAVA_EXTRACTED=" + n;
        } catch (Throwable e) {
            return "WHEELS_EXTRACT_FAIL: " + SensitiveData.redact(String.valueOf(e));
        }
    }

    private static boolean wrapperPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("test -x /root/dsh-bin/adb-shell && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    private static boolean wheelsPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("ls /root/.dsh/wheels/*.whl 2>/dev/null | wc -l");
        try {
            return r != null && Integer.parseInt(r.trim()) >= 15;
        } catch (Exception e) {
            return false;
        }
    }

    /** 注入 wheels 离线包：Java 直接写 assets 的 tar.gz 进 rootfs，再 shell 解压。 */
    private static String injectWheels(Context ctx, ProotBootstrap proot) {
        try {
            java.io.File dst = new java.io.File(proot.getRootfsDir(), "root/.dsh/adb-wheels.tar.gz");
            dst.getParentFile().mkdirs();
            java.io.InputStream in;
            try {
                in = ctx.getAssets().open("adb-wheels.bin");
            } catch (java.io.IOException e1) {
                try {
                    in = ctx.getAssets().open("adb-wheels.tar.gz");
                } catch (java.io.IOException e2) {
                    return "WHEELS_INJECT_FAIL: assets 里找不到 adb-wheels.bin";
                }
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.close();
            in.close();
            String r = proot.execAndRead("mkdir -p /root/.dsh/wheels && "
                    + "M=$(head -c2 /root/.dsh/adb-wheels.tar.gz | od -An -tx1 | tr -d ' \\n'); "
                    + "if [ \"$M\" = \"1f8b\" ]; then tar xzf /root/.dsh/adb-wheels.tar.gz -C /root/.dsh/wheels/; "
                    + "else tar xf /root/.dsh/adb-wheels.tar.gz -C /root/.dsh/wheels/; fi && "
                    + "ls /root/.dsh/wheels/*.whl | wc -l");
            return SensitiveData.redact("WHEELS_INJECTED(" + total + "B): " + (r == null ? "?" : r.trim()) + " whl");
        } catch (Throwable t) {
            return "WHEELS_INJECT_FAIL: " + SensitiveData.redact(String.valueOf(t));
        }
    }

    private static boolean keyPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    private static boolean depsOk(ProotBootstrap proot) {
        String r = proot.execAndReadWithProot("python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO", 60_000);
        return r != null && r.contains("YES");
    }

    /** 单次配对。pairPort 为空时脚本内尝试 mdns 发现；host 为 App 解析出的真实 IP。 */
    public static String pair(ProotBootstrap proot, String code, String pairPort, String connectPort, String host) {
        String c = "python3 /root/.dsh/adb-pair.py --code '" + esc(code) + "'";
        if (host != null && !host.trim().isEmpty()) c += " --host " + host.trim();
        if (pairPort != null && !pairPort.trim().isEmpty()) c += " --port " + pairPort.trim();
        if (connectPort != null && !connectPort.trim().isEmpty()) c += " --connect-port " + connectPort.trim();
        String out = proot.execAndReadWithProot(c, 120_000);
        if (out != null && out.contains("PAIR_OK")) {
            grantSecureSettings(proot);
        }
        return out;
    }

    /** 配对成功后通过 adb shell（uid=2000）给本 App 授予 WRITE_SECURE_SETTINGS，
     *  之后开机广播可自动开启无线调试（保活依赖）。 */
    private static void grantSecureSettings(ProotBootstrap proot) {
        try {
            String pkg = proot.getContext().getPackageName();
            String r = proot.execAndReadWithProot("DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py pm grant "
                    + pkg + " android.permission.WRITE_SECURE_SETTINGS 2>&1 | head -2", 60_000);
            android.util.Log.i("DSHA-ADB", "WRITE_SECURE_SETTINGS 授权结果: " + SensitiveData.redact(r));
        } catch (Throwable t) {
            android.util.Log.w("DSHA-ADB", "WRITE_SECURE_SETTINGS 授权失败: "
                    + SensitiveData.redact(String.valueOf(t)));
        }
    }

    /** 状态快照：key/deps/connect_port（供 UI 展示）。 */
    public static String status(ProotBootstrap proot) {
        String cmd = "K=$(test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO); "
                + "D=$(python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO); "
                + "P=$(test -f /root/.dsh/adbkeys/connect_port && cat /root/.dsh/adbkeys/connect_port || echo -); "
                + "echo 'key='$K' deps='$D' port='$P";
        return proot.execAndReadWithProot(cmd, 60_000);
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
            // 资产在 Windows 检出时可能是 CRLF，注入容器后 bash 认不了 \r → 统一转 LF
            return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
        } catch (Exception e) {
            return "";
        }
    }
}
