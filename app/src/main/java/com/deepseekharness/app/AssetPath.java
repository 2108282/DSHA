package com.deepseekharness.app;

/**
 * 增量更新清单里 asset 名的合法性判定 —— 纯字符串逻辑，<b>不依赖任何 Android API</b>
 * （所以 tools/pure-logic-test.sh 能用 javac 直接跑断言）。
 *
 * <p>为什么需要它：{@link RuntimeUpdater} 把清单里的 {@code asset} 直接当成覆盖层
 * 下的相对路径用（{@code new File(overlayDir, asset)}）。一个写成
 * {@code ../shared_prefs/deepseekharness.xml} 的名字就能覆盖 SharedPreferences ——
 * API key 密文和 LAN token 都在那里；写成 {@code ../linux/ubuntu/root/...} 就能直接
 * 往 rootfs 里投文件。
 *
 * <p>清单是签过名的，所以这不是「谁都能利用」的洞。但签名是<b>唯一</b>一道屏障就太
 * 单薄了，而且我们自己也可能产出这种名字：{@code gen-runtime-manifest.py} 用
 * {@code os.path.relpath} 算相对路径，assets 下一旦出现指向外部的符号链接（或者有人
 * 改了脚本里的 ASSETS 常量），产出的就是带 {@code ../} 的 asset 名 —— 客户端照着写，
 * 谁都不会注意到文件落到了私有目录的别处。判据放在这里，两边都过一遍。
 */
final class AssetPath {

    /** 路径长度上限。我们自己最长的也就 device-shell-guide/lib/index.js 这种量级。 */
    private static final int MAX_LEN = 200;

    private AssetPath() {
    }

    /**
     * asset 名是否可以安全地当作覆盖层下的相对路径。
     *
     * <p>只放行我们自己会生成的形状：{@code [A-Za-z0-9._-]} 加正斜杠分隔，
     * 不含 {@code ..} 路径段、不以 {@code /} 开头、没有空段。其余一律拒绝 ——
     * 白名单比黑名单可靠，反正 asset 名的取值范围本来就窄。
     */
    static boolean isSafe(String asset) {
        if (asset == null || asset.isEmpty() || asset.length() > MAX_LEN) return false;
        if (asset.charAt(0) == '/' || asset.charAt(0) == '\\') return false;
        if (asset.indexOf('\\') >= 0) return false;      // Windows 分隔符：不认，免得绕过按 / 切分的判断
        if (asset.indexOf('\u0000') >= 0) return false;  // NUL 截断
        if (asset.endsWith("/")) return false;           // 目录名不是文件
        for (String seg : asset.split("/", -1)) {
            if (seg.isEmpty()) return false;             // 空段（"a//b" 或首尾斜杠）
            if (seg.equals(".") || seg.equals("..")) return false;
            for (int i = 0; i < seg.length(); i++) {
                char ch = seg.charAt(i);
                boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9')
                        || ch == '.' || ch == '_' || ch == '-';
                if (!ok) return false;
            }
        }
        return true;
    }
}
