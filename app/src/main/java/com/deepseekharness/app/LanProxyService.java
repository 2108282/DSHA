package com.deepseekharness.app;

import android.content.Context;
import android.util.Log;

import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.Compat;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网访问控制器 (LAN Manager).
 *
 * <p>遵循架构契约：局域网 3081 监听与转发功能完全交由底层核心模块 (lan-proxy.sh) 运行与守护。
 * APK 仅负责：
 * 1. 局域网访问功能的开启与关闭开关；
 * 2. 局域网访问 Token 的查看、生成与更换。</p>
 */
public final class LanProxyService {

    private static final String TAG = "DSHA-LanManager";
    private static final String PREF_LAN_TOKEN = Constants.KEY_LAN_TOKEN_V2;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService ASYNC_EXEC = Executors.newSingleThreadExecutor();

    public static final int LAN_PORT = 3081;
    public static final int DEFAULT_BACKEND_PORT = 3080;

    private static final String LAN_SCRIPT = "/data/adb/dsha/scripts/lan-proxy.sh";
    private static final String BRIDGE_TOKEN_PATH = "/data/adb/dsha/rootfs/root/.dsh/.bridge_token";
    private static final String LAN_TOKEN_PATH = "/data/adb/dsha/rootfs/root/.dsh/.lan_token";

    private static volatile String cachedToken = "";

    private LanProxyService() {
    }

    /** 核心模块命令执行（后台静默完成） */
    private static void execRootCmd(String cmd) {
        ASYNC_EXEC.execute(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable t) {
                Log.w(TAG, "执行 Root 命令失败: " + cmd, t);
            }
        });
    }

    /** 启动核心模块 3081 局域网反向代理 */
    public static void start(Context ctx) {
        execRootCmd(LAN_SCRIPT + " start");
    }

    public static void start(String rootfsDir, Context ctx) {
        start(ctx);
    }

    public static void start(String rootfsDir, Context ctx, int backend) {
        start(ctx);
    }

    public static void start(String rootfsDir, Context ctx, int backend, long generation) {
        start(ctx);
    }

    /** 停止核心模块 3081 局域网反向代理 */
    public static void stop() {
        execRootCmd(LAN_SCRIPT + " stop");
    }

    public static void stop(long generation) {
        stop();
    }

    public static void stopLanListener() {
        stop();
    }

    /** 检测局域网 3081 端口是否处于监听可用状态（轻量探测，毫秒级返回） */
    public static boolean isBound() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", LAN_PORT), 150);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 获取当前局域网访问 Token（优先从核心模块文件读取，确保与核心模块严格一致） */
    public static synchronized String getLanToken(Context ctx) {
        if (isValidLanToken(cachedToken)) return cachedToken;

        // 1. 尝试直接读取底层 Token 文件
        String fileToken = readTokenFromFile();
        if (isValidLanToken(fileToken)) {
            cachedToken = fileToken;
            return fileToken;
        }

        // 2. 尝试从 SharedPreferences 读取
        String stored = "";
        try {
            if (ctx != null) {
                stored = ctx.getApplicationContext()
                        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                        .getString(PREF_LAN_TOKEN, "");
            }
        } catch (Throwable ignored) {
        }
        if (isValidLanToken(stored)) {
            cachedToken = stored;
            // 异步将此 Token 同步写入底层
            syncTokenToRoot(stored);
            return stored;
        }

        // 3. 生成新 Token 并同步
        return regenerateLanToken(ctx);
    }

    /** 重新生成局域网访问 Token 并即刻同步到底层核心模块 */
    public static synchronized String regenerateLanToken(Context ctx) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        cachedToken = generated;

        try {
            if (ctx != null) {
                ctx.getApplicationContext()
                        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                        .edit().putString(PREF_LAN_TOKEN, generated).apply();
            }
        } catch (Throwable ignored) {
        }

        syncTokenToRoot(generated);
        return generated;
    }

    private static void syncTokenToRoot(String token) {
        if (!isValidLanToken(token)) return;
        execRootCmd(LAN_SCRIPT + " token " + token);
    }

    private static String readTokenFromFile() {
        for (String path : new String[]{LAN_TOKEN_PATH, BRIDGE_TOKEN_PATH}) {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] data = Compat.readAllBytes(fis);
                    String str = new String(data, StandardCharsets.UTF_8).trim();
                    if (isValidLanToken(str)) return str;
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    private static boolean isValidLanToken(String value) {
        return value != null && value.length() >= 4 && value.length() <= 64 && value.matches("[A-Za-z0-9_-]+");
    }

    // 兼容方法存根（保留以防外部类编译失败）
    public static boolean setDshAuthCookie(String cookie, long generation) {
        return true;
    }

    public static void clearDshAuth(long generation) {
    }

    public static boolean hasDshAuth(long generation) {
        return true;
    }
}
