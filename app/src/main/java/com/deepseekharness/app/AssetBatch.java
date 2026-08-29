package com.deepseekharness.app;

/**
 * 「一次容器会话里跑多个 assets 脚本」的命令拼接与输出切分（纯逻辑，不碰 Android API ——
 * 断言在 {@code tools/pure-logic-test.sh}）。
 *
 * <p><b>为什么要合并</b>：{@code HarnessController.runAssetScript} 每调一次就是一次独立的
 * 容器会话，启动器 + bash 的固定成本要付一遍。启动路径上排着 6 个幂等自愈脚本，
 * 绝大多数时候它们只是确认「补丁已经在了」就返回 —— 真正干活的时间远小于起会话的开销。
 *
 * <p><b>为什么把拼接与切分单独放这里</b>：切分错了的后果是静默的 —— 比如
 * {@code fs-write-patch.sh} 的输出被分到了别人名下，那句「PATCHED」就再也匹配不到，
 * 功能照跑但记账没了，谁也不会发现。这种「静默错配」正是本项目反复吃亏的模式
 * （市场索引的列错位、清单 hash 与文件脱节），所以做成能写断言的形状。
 */
final class AssetBatch {

    private AssetBatch() {
    }

    /** 哨兵前缀（带纳秒后缀生成，脚本自己打印的文本撞不上）。 */
    static final String SEP_PREFIX = "___DSHA_SEP_";

    static String newSeparator() {
        return SEP_PREFIX + Long.toHexString(System.nanoTime()) + "___";
    }

    /**
     * 拼命令：每个脚本前先打一行哨兵，再 {@code bash} 它，跑完删掉。
     *
     * <p>用 {@code ;} 而不是 {@code &&} 串联 —— 某个脚本失败时后面的照样跑，
     * 这与「原来每个脚本各自一次会话」的语义一致。
     */
    static String buildCommand(String sep, java.util.List<String> remoteNames) {
        StringBuilder cmd = new StringBuilder();
        for (String rn : remoteNames) {
            cmd.append("echo ").append(sep).append("; ")
               .append("bash /root/").append(rn).append("; ")
               .append("rm -f /root/").append(rn).append("; ");
        }
        return cmd.toString();
    }

    /**
     * 按哨兵切分输出，返回 {@code assetName → 该脚本的输出}。
     *
     * <p>第 0 段是第一个哨兵<b>之前</b>的内容（容器启动器的提示之类），所以脚本 i 的输出
     * 在第 {@code i+1} 段。段数不够（命令被超时打断）时后面的脚本给空串，
     * 调用方按「没输出」处理即可 —— 不要抛异常，这些脚本都是 fail-soft 的。
     */
    static java.util.Map<String, String> splitOutput(
            String sep, java.util.List<String> assetNames, String output) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String[] parts = output == null
                ? new String[0]
                : output.split(java.util.regex.Pattern.quote(sep), -1);
        for (int i = 0; i < assetNames.size(); i++) {
            out.put(assetNames.get(i), i + 1 < parts.length ? parts[i + 1].trim() : "");
        }
        return out;
    }
}
