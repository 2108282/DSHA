package com.deepseekharness.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shizuku UserService：在 root/shell（ADB）身份下执行 shell 命令。
 * 由 ShizukuShell 通过 bindUserService 绑定，进程由 Shizuku 托管。
 */
public class ShellService extends IShellService.Stub {

    @Override
    public String exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true);
            // UserService 进程由 Shizuku 托管，env 可能缺失常用 PATH：
            // 显式兜底（否则 pm/dumpsys 等找不到）
            java.util.Map<String, String> env = pb.environment();
            String oldPath = env.get("PATH");
            env.put("PATH", (oldPath == null || oldPath.isEmpty() ? "" : oldPath + ":")
                    + "/system/bin:/system/xbin:/sbin:/vendor/bin");
            Process p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            final int MAX = 256 * 1024;
            try (InputStream in = p.getInputStream()) {
                // 注意：超过 MAX 后必须继续读完（drain），否则管道写满 → 子进程阻塞 → 死锁
                while ((n = in.read(buf)) != -1) {
                    if (bos.size() < MAX) {
                        int w = Math.min(n, MAX - bos.size());
                        bos.write(buf, 0, w);
                    }
                }
            }
            // 超时强杀（30s），防命令永久挂起
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return bos.toString(StandardCharsets.UTF_8.name())
                        + "\n[EXIT=timeout] 命令执行超时(30s)已强杀";
            }
            int code = p.exitValue();
            return bos.toString(StandardCharsets.UTF_8.name()) + "\n[EXIT=" + code + "]";
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
