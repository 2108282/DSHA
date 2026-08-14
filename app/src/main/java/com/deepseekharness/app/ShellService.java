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
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            final int MAX = 256 * 1024;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) != -1) {
                    if (bos.size() < MAX) {
                        int w = Math.min(n, MAX - bos.size());
                        bos.write(buf, 0, w);
                    }
                }
            }
            int code = p.waitFor();
            return bos.toString(StandardCharsets.UTF_8.name()) + "\n[EXIT=" + code + "]";
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
