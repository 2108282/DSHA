package com.deepseekharness.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 递归复制，**符号链接原样重建、绝不跟随**（纯逻辑，断言在 {@code tools/pure-logic-test.sh}，
 * 真实文件树的往返在 {@code tools/extract-roundtrip-test.sh}）。
 *
 * <p><b>为什么必须单独拿出来</b>：重解压内置环境（v1→v2 这类升级）之前要把用户数据挪到
 * {@code .data-preserve-*}，原先那份实现用的是 {@code File.isDirectory()} / {@code isFile()}
 * —— 这两个方法<b>会跟随符号链接</b>。而 1.1.7 之后 {@code .dsh/sessions}、{@code storages}、
 * {@code attachments}、{@code settings.yaml} 全是指向 {@code /sdcard/Documents/dshdata} 的
 * 软链（「卸载不丢数据」就是这么实现的）。于是那条路径实际发生的是：
 *
 * <ol>
 *   <li>备份时把公开目录里的<b>全部对话</b>复制进 {@code .data-preserve-*}（几百 MB 到几 GB）；</li>
 *   <li>还原时在新 rootfs 里建出<b>真目录</b>，软链没了；</li>
 *   <li>下次启动 {@code migrate-public-data.sh} 撞上「私有与公开都有数据」的冲突分支，
 *       把公开那份改名成 {@code sessions.conflict-<时间戳>} 留在磁盘上 —— 又一份完整副本。</li>
 * </ol>
 *
 * 结果是一次升级的峰值空间需求变成「对话大小 ×3 + rootfs 约 1GB」，升级完磁盘上还永久多
 * 一份对话副本；空间不够时解压会在 rootfs 已被删除之后失败。保留链接之后这份备份只有几 KB。
 */
final class FileCopy {

    private FileCopy() {
    }

    /**
     * 递归复制 {@code src} 到 {@code dst}。
     *
     * <p>遇到符号链接：读出目标原样重建，<b>不</b>递归进去。<b>无法原样重建就直接失败</b>
     * —— 调用方必须中止 rootfs 切换并保留旧数据。跟随复制或静默跳过都会破坏数据边界：
     * 悬空的 sessions/settings 链接没有可读的退路，而跟随一个目录链接会把「几 KB 的
     * 保护性快照」变成「几 GB 的半份拷贝」，恰好在空间不够时炸在最坏的位置。
     */
    static void copyPreservingLinks(File src, File dst) throws IOException {
        copy(src, dst);
    }

    private static void copy(File src, File dst) throws IOException {
        Path sp = src.toPath();
        if (Files.isSymbolicLink(sp)) {
            if (relink(sp, dst.toPath())) return;
            throw new IOException("无法原样保留符号链接: " + src);
        }
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录: " + dst);
            }
            File[] children = src.listFiles();
            if (children == null) {
                // 读目录失败 ≠ 空目录。当成保护失败抛出去，别让「只复制了半棵 .dsh」
                // 之后还继续做离线包切换。
                throw new IOException("无法读取目录: " + src);
            }
            for (File c : children) {
                copy(c, new File(dst, c.getName()));
            }
        } else if (src.isFile()) {
            copyFile(src, dst);
        } else {
            // 设备节点 / FIFO 不该出现在 .dsh 里。这里失败比静默丢掉一个不认识的条目
            // 更能保住数据完整性。
            throw new IOException("无法复制未知文件类型: " + src);
        }
    }

    /** 原样重建一根软链；建不出来返回 false（调用方一律当失败处理，不再退回复制内容）。 */
    private static boolean relink(Path src, Path dst) {
        Path target;
        try {
            target = Files.readSymbolicLink(src);
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.deleteIfExists(dst);
            Files.createSymbolicLink(dst, target);
            return true;
        } catch (Throwable e) {
        }
        // Android 的 NIO 实现有时会拒掉建链接这一步，即使底层的 App 私有文件系统本身
        // 支持软链。所以再用平台原语试一次 —— 这仍然是「忠实重建」的第二次尝试，
        // 不是退回复制数据。
        try {
            target = Files.readSymbolicLink(src);
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.deleteIfExists(dst);
            android.system.Os.symlink(target.toString(), dst.toString());
            return Files.isSymbolicLink(dst);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 复制单个文件（保留可执行位 —— rootfs 里的脚本丢了执行位就跑不起来）。 */
    static void copyFile(File src, File dst) throws IOException {
        if (dst.getParentFile() != null && !dst.getParentFile().exists()
                && !dst.getParentFile().mkdirs()) {
            throw new IOException("无法创建父目录: " + dst.getParentFile());
        }
        Files.copy(src.toPath(), dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        if (src.canExecute() && !dst.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            dst.setExecutable(true, false);
        }
    }

    /** 这个路径是不是一根软链（不跟随判断，给调用方做日志/断言用）。 */
    static boolean isLink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }

    /** 软链指向哪里；不是软链或读不出来返回空串。 */
    static String linkTarget(File f) {
        try {
            return Files.readSymbolicLink(f.toPath()).toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 存在性判断，<b>不跟随</b>软链（悬空链接也算存在）。 */
    static boolean existsNoFollow(File f) {
        return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /** 这个路径本身是不是一根符号链接（不看目标存不存在）。
     *  递归删除必须先问这一句 —— {@code File.isDirectory()} 对「指向目录的软链」返回 true，
     *  跟着递归进去就会把链接目标里的东西删掉，而我们的 sessions/settings 正指向公开目录。 */
    static boolean isSymlink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }
}
