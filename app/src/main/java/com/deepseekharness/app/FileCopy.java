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
     * <p>遇到符号链接：读出目标原样重建，<b>不</b>递归进去。极少数文件系统不允许建软链时
     * 退回「按内容复制」——宁可多占空间也不丢数据，但会计入返回值，好让调用方记一行日志。
     *
     * @return 退回按内容复制的软链个数（0 = 全部原样保留）
     */
    static int copyPreservingLinks(File src, File dst) throws IOException {
        int[] fallbacks = {0};
        copy(src, dst, fallbacks);
        return fallbacks[0];
    }

    private static void copy(File src, File dst, int[] fallbacks) throws IOException {
        Path sp = src.toPath();
        if (Files.isSymbolicLink(sp)) {
            if (relink(sp, dst.toPath())) return;
            fallbacks[0]++;
            // 落到下面按内容复制（这时 isDirectory/isFile 会跟随链接，正是我们要的退路）
        }
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录: " + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copy(c, new File(dst, c.getName()), fallbacks);
                }
            }
        } else if (src.isFile()) {
            copyFile(src, dst);
        }
        // 其它类型（socket / fifo / 悬空链接读不出目标）一律跳过：rootfs 里没有值得搬的
    }

    /** 原样重建一根软链；建不出来返回 false（调用方退回复制内容）。 */
    private static boolean relink(Path src, Path dst) {
        try {
            Path target = Files.readSymbolicLink(src);
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.deleteIfExists(dst);
            Files.createSymbolicLink(dst, target);
            return true;
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
}
