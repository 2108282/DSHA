package com.deepseekharness.app;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 重解压内置环境之前「把 {@code /root} 下的用户数据挪到安全处」这件事的<b>唯一</b>实现。
 *
 * <p>为什么单独成类而不是留在 {@link ProotBootstrap} 里：这是整个 App 里最不能出错的一段
 * —— 它的下一步就是 {@code deleteRecursively(rootfs)}。而 {@code ProotBootstrap} 依赖
 * Context / SharedPreferences / AssetManager，用 javac 根本编不动，于是这段逻辑长期只能靠
 * 读代码判断对不对。1.1.9 那次「工作区内容全部丢失」正是出在这里。搬成纯 java.io 的类之后，
 * {@code tools/data-preserve-test.sh} 可以真造目录、真造软链、真让复制失败，把行为跑出来看。
 *
 * <p>三条契约：
 * <ul>
 *   <li><b>rename 优先</b>：{@code dataBak} 与 rootfs 在同一个文件系统（都在 {@code files/linux}
 *       下），rename 是 O(1)、不占额外空间、软链原样带走。复制要付「数据大小 ×2」的空间和
 *       好几分钟 —— 对话几个 GB 的用户正是在这一步空间不够，而那时 rootfs 已经删了。</li>
 *   <li><b>fail-closed</b>：任何一项没能<b>原样</b>保住就中止，绝不「记一行 warning 然后继续」。</li>
 *   <li><b>中止要能回滚</b>：先把已经 rename 走的挪回 {@code /root} 再抛，让 rootfs 回到
 *       升级前的样子；挪不回去的必须在文案里点名落在哪个目录。</li>
 * </ul>
 */
final class DataPreserve {

    /** 内置插件实体的前缀。新 APK 会按 BUILTIN_ASSET_VERSION 重新注入，不必保，
     *  而且新版往往就是要换掉它们。 */
    static final String BUILTIN_PREFIX = "dsha-";

    /** 一次保护的结果，只用来写日志。 */
    static final class Result {
        final int moved;
        final int copied;

        Result(int moved, int copied) {
            this.moved = moved;
            this.copied = copied;
        }

        @Override
        public String toString() {
            return "挪走 " + moved + " 项、复制 " + copied + " 项";
        }
    }

    private DataPreserve() {
    }

    /**
     * 把 {@code rootHome} 下的用户数据搬进 {@code dataBak}（{@code dataBak} 需已存在）。
     *
     * @throws IOException 有一项保不住。抛之前已经尽力把先前 rename 走的挪回原位，
     *                     消息里带回滚结果 —— 调用方直接中止升级即可，<b>不要</b>接住继续。
     */
    static Result preserve(File rootHome, File dataBak) throws IOException {
        File[] kids = rootHome.isDirectory() ? rootHome.listFiles() : null;
        if (kids == null) return new Result(0, 0);
        int moved = 0, copied = 0;
        List<String> movedNames = new ArrayList<>();
        for (File k : kids) {
            if (k.getName().startsWith(BUILTIN_PREFIX)) continue;
            File dst = new File(dataBak, k.getName());
            if (k.renameTo(dst)) {
                moved++;
                movedNames.add(k.getName());
                continue;
            }
            try {
                FileCopy.copyPreservingLinks(k, dst);
                copied++;
            } catch (Throwable e) {
                String back = rollback(dataBak, rootHome, movedNames);
                throw new IOException("升级已中止（rootfs 未改动）：无法原样保住 "
                        + k.getName() + " —— " + e + "\n" + back
                        + "\n先做一次备份、或把 /root 下那一项（尤其是软链）整理好再重试。");
            }
        }
        return new Result(moved, copied);
    }

    /**
     * 把已经 rename 进 {@code dataBak} 的条目挪回 {@code rootHome}，返回一句给用户看的结果。
     *
     * <p>只挪 {@code movedNames} 里的项：「复制成功」那些的原件从没离开 {@code /root}，
     * 留在保护目录里的只是冗余副本。
     */
    static String rollback(File dataBak, File rootHome, List<String> movedNames) {
        int back = 0, stuck = 0;
        for (String n : movedNames) {
            File from = new File(dataBak, n);
            File to = new File(rootHome, n);
            // 这里必须用 existsNoFollow：File.exists() 会跟随链接，而一根**悬空**软链
            //（换设备之后 .dsh/sessions、settings.yaml 指向的公开目录不在，就是这个样子）
            // 会被判成「不存在」直接跳过 —— 那根链接就永远留在保护目录里，用户的 /root 下
            // 少一项，而回滚报告还说「与升级前一致」。这恰好是最该保住的一类数据。
            if (!FileCopy.existsNoFollow(from)) continue;
            if (FileCopy.existsNoFollow(to) || !from.renameTo(to)) {
                stuck++;
            } else {
                back++;
            }
        }
        if (stuck == 0) {
            // 保护目录里可能还留着「复制成功」那些项的副本（复制不删原件），所以要递归删；
            // File.delete() 对非空目录只会静默失败，留下一个 .data-preserve-* 占空间。
            deleteRecursively(dataBak);
            return "已挪回 " + back + " 项，rootfs 与升级前一致。";
        }
        return "已挪回 " + back + " 项，还有 " + stuck + " 项留在 " + dataBak.getName()
                + "（那就是升级前的 /root 内容，可以手动取回）。";
    }

    /** 递归删除，<b>不跟随符号链接</b>（跟随会把公开目录里的真数据一起删掉）。 */
    static void deleteRecursively(File f) {
        if (f.isDirectory() && !FileCopy.isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
