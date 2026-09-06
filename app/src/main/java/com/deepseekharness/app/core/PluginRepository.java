package com.deepseekharness.app.core;

import android.app.Application;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activity 范围的插件操作状态；任务只持有 Application，切页/旋转不会丢失结果。 */
public final class PluginRepository extends AndroidViewModel {
    private static final long MAX_ARCHIVE = 256L * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicBoolean working = new AtomicBoolean();
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(Collections.emptyList(), false, "选择链接安装或导入本地插件包"));
    private volatile List<Item> items = Collections.emptyList();

    public static final class Item {
        public final String name, description, version, source;
        public final boolean enabled, builtin, official, available, exportable, deletable;
        Item(JSONObject json) {
            name = json.optString("name");
            description = json.optString("description");
            version = json.optString("version");
            source = json.optString("source");
            enabled = json.optBoolean("enabled");
            builtin = json.optBoolean("builtin");
            official = json.optBoolean("official");
            available = json.optBoolean("available");
            exportable = json.optBoolean("exportable");
            deletable = json.optBoolean("deletable");
        }
    }

    public static final class State {
        public final List<Item> items;
        public final boolean busy;
        public final String message;
        State(List<Item> items, boolean busy, String message) {
            this.items = items;
            this.busy = busy;
            this.message = message;
        }
    }

    public PluginRepository(@NonNull Application app) { super(app); }
    public LiveData<State> state() { return state; }
    public boolean isBusy() { return working.get(); }

    public void selectionMessage(String message) {
        if (!working.get()) state.setValue(new State(items, false, message));
    }

    private interface Work { String run(ProotBootstrap proot) throws Exception; }

    private void submit(String progress, Work work) {
        if (!working.compareAndSet(false, true)) {
            state.setValue(new State(items, true, "上一项插件操作仍在进行，请完成后重新选择。"));
            return;
        }
        state.setValue(new State(items, true, progress));
        IO.execute(() -> {
            String message;
            ProotBootstrap proot = HarnessController.get(getApplication()).proot();
            try {
                if (!proot.isEnvironmentReady()) throw new IOException("环境未就绪，请先完成解压 / 安装");
                message = work.run(proot);
            } catch (Exception error) {
                message = "操作失败：" + SensitiveData.redact(String.valueOf(error.getMessage()));
            }
            // 操作结果单独保存；刷新不能把错误/部分成功覆盖成「同步完成」。
            try {
                if (proot.isEnvironmentReady()) items = loadItems(proot);
            } catch (Exception error) {
                message += "\n列表未能同步：" + SensitiveData.redact(String.valueOf(error.getMessage()));
            }
            State completed = new State(items, false, message);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                working.set(false);
                state.setValue(completed);
            });
        });
    }

    public void refresh() {
        submit("正在同步插件状态…", proot -> {
            String output = proot.registerBuiltinPlugins();
            if (output.contains("FAIL") || output.contains("ERROR:"))
                throw new IOException(shortError(output));
            return output.contains("PARTIAL") ? "部分内置插件待修复，请检查环境"
                    : "插件状态已同步；启用、禁用或安装后请到启动页重启 Web";
        });
    }

    public void install(PluginSource source) {
        submit("正在下载并安装：" + source.description(), proot ->
                operationMessage(result(proot.runPluginManager(source.command()))));
    }

    public void importArchive(Uri uri) {
        submit("正在导入插件包…", proot -> {
            File temporary = File.createTempFile("plugin-import-", ".bin", getApplication().getCacheDir());
            String container = "/root/.dsh/plugin-upload-" + UUID.randomUUID() + ".bin";
            try {
                try (InputStream in = getApplication().getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temporary)) {
                    copy(in, out);
                }
                if (!proot.pushFileIntoContainer(temporary, container)) throw new IOException("插件包写入容器失败");
                return operationMessage(result(proot.runPluginManager("import " + ShellQuote.arg(container))));
            } finally {
                temporary.delete();
                cleanup(proot, container);
            }
        });
    }

    public void exportArchives(List<String> names, Uri uri) {
        submit("正在打包并保存插件…", proot -> {
            File temporary = File.createTempFile("plugin-export-", ".tar.gz", getApplication().getCacheDir());
            String container = "/root/.dsh/plugin-export-" + UUID.randomUUID() + ".tar.gz";
            boolean saved = false;
            try {
                JSONObject output = result(proot.runPluginManager("export "
                        + ShellQuote.arg(new JSONArray(names).toString()) + " " + ShellQuote.arg(container)));
                if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
                if (!proot.pullFileFromContainer(container, temporary)) throw new IOException("插件包取回失败");
                try (InputStream in = new FileInputStream(temporary);
                     OutputStream out = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                    copy(in, out);
                }
                saved = true;
                return "已将 " + names.size() + " 个插件导出到所选位置，可在另一台 DSHA 中导入";
            } finally {
                temporary.delete();
                cleanup(proot, container);
                if (!saved) {
                    try { DocumentsContract.deleteDocument(getApplication().getContentResolver(), uri); }
                    catch (Exception ignored) { }
                }
            }
        });
    }

    public void setEnabled(Item item, boolean enable) {
        submit("正在" + (enable ? "启用 " : "禁用 ") + item.name, proot -> {
            String output = proot.setPluginEnabled(item.name, enable);
            if (!output.contains("BUILTIN_REGISTER_OK")) throw new IOException(shortError(output));
            return "已" + (enable ? "启用 " : "禁用 ") + item.name + "；重启 Web 后生效";
        });
    }

    public void delete(Item item) {
        if (!item.deletable) return;
        submit("正在删除 " + item.name + "…", proot -> {
            JSONObject output = result(proot.runPluginManager("delete " + ShellQuote.arg(item.name)));
            if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
            return output.getString("message");
        });
    }

    private List<Item> loadItems(ProotBootstrap proot) throws Exception {
        JSONObject output = result(proot.runPluginManager("list"));
        if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
        JSONArray array = output.getJSONArray("items");
        List<Item> next = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) next.add(new Item(array.getJSONObject(i)));
        return Collections.unmodifiableList(next);
    }

    private static JSONObject result(String output) throws Exception {
        String text = output == null ? "" : output.trim();
        String result = com.deepseekharness.app.util.PluginOutput.resultJson(text);
        if (result.isEmpty()) throw new IOException(shortError(text));
        JSONObject json = new JSONObject(result);
        if (!json.has("status") || !json.has("message")) throw new IOException("插件操作返回了不完整的结果");
        // error/partial 也保留脚本给出的完整原因；不能靠输出中出现一次 OK 判断整个操作成功。
        json.put("message", SensitiveData.redact(json.getString("message")));
        return json;
    }

    private static String operationMessage(JSONObject result) throws Exception {
        String status = result.getString("status");
        return ("partial".equals(status) ? "部分完成：\n" : "error".equals(status) ? "安装失败：\n" : "")
                + result.getString("message");
    }

    private static String shortError(String text) {
        if (text == null || text.isEmpty()) return "没有收到插件管理结果";
        if (text.equals("ENV_NOT_READY")) return "环境未就绪，请先完成解压 / 安装";
        String safe = SensitiveData.redact(text.trim());
        return safe.length() <= 800 ? safe : safe.substring(safe.length() - 800);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) throw new IOException("无法打开所选文件，请重新选择位置");
        long size = 0;
        byte[] bytes = new byte[65536];
        int count;
        while ((count = in.read(bytes)) != -1) {
            size += count;
            if (size > MAX_ARCHIVE) throw new IOException("插件包不能超过 256 MiB");
            out.write(bytes, 0, count);
        }
        if (size == 0) throw new IOException("插件包为空");
    }

    private static void cleanup(ProotBootstrap proot, String path) {
        // 只清理由本类生成的单个暂存文件，不递归清理插件目录。
        new File(proot.getRootfsDir(), path.substring(1)).delete();
    }
}
