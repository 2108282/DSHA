package com.deepseekharness.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 插件控制器：插件市场（awesome-dsh-plugins 快照，支持 star/名称/分类/兼容性排序 + 一键安装）
 * + 已装插件管理（启用/禁用/导入/导出）
 */
public class PluginFragment extends Fragment {
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 市场缓存年龄提示（" · 缓存于 N 分钟前"）；无缓存返回空串 */
    private String cacheHint() {
        long age = c.getMarketCacheAgeMs();
        if (age < 0) return "";
        return String.format(java.util.Locale.US, " · 缓存于 %d 分钟前", age / 60000);
    }

    private enum Mode { MARKET, INSTALLED }

    private Mode mode = Mode.MARKET;
    private final List<String[]> items = new ArrayList<>();
    private final List<String[]> installed = new ArrayList<>();
    private PluginAdapter adapter;
    private HarnessController c;
    private TextView status;
    /** 当前排序：0 star / 1 名称 / 2 分类 / 3 兼容性 */
    private int sortMode = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_plugins, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        c = HarnessController.get(requireContext());
        adapter = new PluginAdapter();
        RecyclerView rv = view.findViewById(R.id.pluginList);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        status = view.findViewById(R.id.statusText);

        TextView btnMarket = view.findViewById(R.id.btnMarket);
        TextView btnInstalled = view.findViewById(R.id.btnInstalled);
        TextView btnSort = view.findViewById(R.id.btnSort);
        android.widget.EditText searchBox = view.findViewById(R.id.pluginSearch);
        view.findViewById(R.id.actionBar).setVisibility(View.GONE);

        // 搜索：按名称过滤（忽略大小写）
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim().toLowerCase();
                if (mode == Mode.MARKET) {
                    java.util.List<String[]> filtered = new java.util.ArrayList<>();
                    for (String[] it : items) {
                        if (q.isEmpty() || it[0].toLowerCase().contains(q)) filtered.add(it);
                    }
                    adapter.setData(filtered, true);
                    status.setText("共 " + filtered.size() + " 个插件" + (q.isEmpty() ? " · 点击查看详情/安装" : "（搜索：" + q + "）"));
                } else if (mode == Mode.INSTALLED) {
                    java.util.List<String[]> filtered = new java.util.ArrayList<>();
                    for (String[] it : installed) {
                        if (q.isEmpty() || it[0].toLowerCase().contains(q)) filtered.add(it);
                    }
                    adapter.setData(filtered, false);
                    status.setText("已装 " + filtered.size() + " 个插件 · 开关启用/禁用" + (q.isEmpty() ? "" : "（搜索：" + q + "）"));
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        btnMarket.setOnClickListener(v -> {
            mode = Mode.MARKET;
            view.findViewById(R.id.actionBar).setVisibility(View.GONE);
            view.findViewById(R.id.chkHideBuiltin).setVisibility(View.GONE);
            showMarket();
        });
        btnInstalled.setOnClickListener(v -> {
            mode = Mode.INSTALLED;
            view.findViewById(R.id.actionBar).setVisibility(View.VISIBLE);
            view.findViewById(R.id.chkHideBuiltin).setVisibility(View.VISIBLE);
            showInstalled();
        });
        btnSort.setOnClickListener(v -> showSortMenu(btnSort));

        // 强制刷新市场缓存（清缓存 → 重新拉网络）
        TextView btnRefresh = view.findViewById(R.id.btnRefresh);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                status.setText("已清除缓存，正在重新拉取…");
                c.refreshMarketIndex();
                items.clear();
                showMarket();
            });
        }

        view.findViewById(R.id.btnExport).setOnClickListener(v -> exportPlugins());
        view.findViewById(R.id.btnImport).setOnClickListener(v -> importPlugins());
        // 隐藏自带插件开关：记住选择，切换时刷新已装列表
        final android.widget.CheckBox hideCb = view.findViewById(R.id.chkHideBuiltin);
        hideCb.setChecked(requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("hide_builtin", false));
        hideCb.setOnCheckedChangeListener((b, isChecked) -> {
            requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("hide_builtin", isChecked).apply();
            showInstalled();
        });

        showMarket();
    }

    /** 排序下拉菜单：点一下展开选择，不用一直点循环 */
    private void showSortMenu(android.view.View anchor) {
        final String[] options = {"⭐ Star 数", "🔤 名称 A-Z", "✅ 兼容性"};
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        for (int i = 0; i < options.length; i++) {
            pm.getMenu().add(0, i, 0, options[i]);
        }
        pm.setOnMenuItemClickListener(item -> {
            sortMode = item.getItemId();
            ((android.widget.TextView) anchor).setText(options[sortMode].replace("排序：", ""));
            if (mode == Mode.MARKET && !items.isEmpty()) applySort();
            return true;
        });
        pm.show();
    }

    private void applySort() {
        final int sm = sortMode;
        Collections.sort(items, (a, b) -> {
            switch (sm) {
                case 0: // star 降序
                    int sa = Integer.parseInt(a[1].isEmpty() ? "0" : a[1]);
                    int sb = Integer.parseInt(b[1].isEmpty() ? "0" : b[1]);
                    return sb - sa;
                case 1: // 名称
                    return a[0].toLowerCase().compareTo(b[0].toLowerCase());
                default: // 兼容性：✅ 可用 > ⏳未测 > ❌不兼容 > 未知
                    return rankCompat(a[3]) - rankCompat(b[3]);
            }
        });
        adapter.notifyDataSetChanged();
    }

    /** 线程回调安全切主线程（Fragment detach 后不再崩溃）：未 attach 则丢弃 */
    private void runOnUiThreadSafely(java.lang.Runnable r) {
        if (!isAdded()) return;
        android.app.Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(r);
    }

    private int rankCompat(String v) {
        if (v.startsWith("✅")) return 0;
        if (v.startsWith("⏳")) return 1;
        if (v.startsWith("❌")) return 2;
        return 3;
    }

    private void showMarket() {
        if (!items.isEmpty()) {
            applySort();
            adapter.setData(items, true);
            status.setText("共 " + items.size() + " 个插件 · 点击查看详情/安装" + cacheHint());
            return;
        }
        status.setText("正在拉取插件市场…");
        new Thread(() -> {
            String json = c.fetchMarketIndex();
            List<String[]> list = json == null ? new ArrayList<>() : HarnessController.parseMarketTable(json);
            runOnUiThreadSafely(() -> {
                if (list.isEmpty()) {
                    status.setText("市场拉取失败（网络不通？）");
                    return;
                }
                items.clear();
                items.addAll(list);
                applySort();
                adapter.setData(items, true);
                status.setText("共 " + items.size() + " 个插件 · 点击查看详情/安装" + cacheHint());
                fetchStars(items); // 异步批量拉真实 star 数
            });
        }).start();
    }

    private void showInstalled() {
        final boolean hide = requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("hide_builtin", false);
        new Thread(() -> {
            String[][] pl = c.listPlugins(hide);
            runOnUiThreadSafely(() -> {
                installed.clear();
                if (pl == null || pl.length == 0) {
                    status.setText("未发现已装插件（目录 " + String.join("/", HarnessController.PLUGIN_DIRS) + "）");
                    adapter.setData(new ArrayList<>(), false);
                    return;
                }
                for (String[] p : pl) installed.add(p);
                adapter.setData(installed, false);
                status.setText("已装 " + installed.size() + " 个插件 · 开关启用/禁用");
            });
        }).start();
    }

    private void exportPlugins() {
        status.setText("正在导出插件…");
        new Thread(() -> {
            String path = c.exportPlugins();
            runOnUiThreadSafely(() -> {
                if (path == null) {
                    status.setText("导出失败（打包出错）");
                    Toast.makeText(requireContext(), "导出失败：打包出错", Toast.LENGTH_LONG).show();
                } else if ("NO_PLUGINS".equals(path)) {
                    status.setText("没有已启用的插件可导出（先去市场安装或确认插件已启用）");
                    Toast.makeText(requireContext(), "没有可导出的插件", Toast.LENGTH_LONG).show();
                } else {
                    status.setText("已导出：" + path);
                    Toast.makeText(requireContext(), "插件包已导出到 " + path, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void importPlugins() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("application/gzip");
        startActivityForResult(intent, 1001);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri == null) return;
            status.setText("正在导入插件…");
            new Thread(() -> {
                try {
                    File tmp = new File(requireContext().getCacheDir(), "plugin-import.tar.gz");
                    try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while (in != null && (n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    boolean ok = c.importPlugins(tmp);
                    runOnUiThreadSafely(() -> {
                        if (ok) {
                            Toast.makeText(requireContext(), "导入成功，重启 WebUI 生效", Toast.LENGTH_LONG).show();
                            showInstalled();
                        } else {
                            Toast.makeText(requireContext(), "导入失败", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThreadSafely(() ->
                            Toast.makeText(requireContext(), "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    /** 详情弹窗：star/作者/更新日期 + 安装按钮 */
    private void showDetail(String[] it) {
        String owner = it[2];
        String repo = it[6].endsWith("/") ? "" : it[6].substring(it[6].lastIndexOf('/') + 1);
        String msg = "⭐ " + it[1] + " · 👤 " + (owner.isEmpty() ? "?" : owner)
                + "\n兼容性：" + it[3] + "\n分类：" + it[4]
                + "\n\n" + it[5]
                + "\n\n🔗 " + it[6] + "\n\n更新日期：查询中…";

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(requireContext())
                .setTitle(it[0])
                .setMessage(msg)
                .setPositiveButton("安装", (d, w) -> startAutoInstall(it, owner, repo))
                .setNeutralButton("复制仓库链接", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", it[6]));
                    Toast.makeText(requireContext(), "链接已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();

        // 异步拉取更新日期/作者/star 刷新弹窗
        if (!owner.isEmpty() && !repo.isEmpty()) {
            new Thread(() -> {
                String[] info = c.fetchRepoInfo(owner, repo);
                if (info == null) return;
                runOnUiThreadSafely(() -> {
                    if (!dlg.isShowing()) return;
                    dlg.setMessage("⭐ " + info[1] + " · 👤 " + (info[2].isEmpty() ? owner : info[2])
                            + "\n兼容性：" + it[3] + "\n分类：" + it[4]
                            + "\n\n" + it[5]
                            + "\n\n🔗 " + it[6]
                            + "\n\n📅 最近更新：" + (info[0].isEmpty() ? "未知" : info[0]));
                });
            }).start();
        }
    }

    /** 批量异步拉取市场列表 star 数（GitHub search API，一次最多 ~80 仓库；失败则保持 0 显示"—"） */
    private void fetchStars(java.util.List<String[]> items) {
        if (items == null || items.isEmpty()) return;
        final long t0 = System.currentTimeMillis();
        new Thread(() -> {
            int size = items.size();
            for (int base = 0; base < size; base += 80) {
                StringBuilder q = new StringBuilder("q=");
                int n = 0;
                java.util.List<Integer> idxs = new java.util.ArrayList<>();
                for (int i = base; i < Math.min(size, base + 80); i++) {
                    String u = items.get(i)[6].replace("https://github.com/", "").replace("http://github.com/", "");
                    if (u.contains("/") && !u.startsWith("http")) {
                        if (n > 0) q.append("+");
                        q.append("repo:").append(u);
                        idxs.add(i);
                        n++;
                    }
                }
                if (n == 0) continue;
                String uApi = "https://api.github.com/search/repositories?" + q + "&per_page=100";
                String[] urls = {
                        HarnessController.gitHubProxy(uApi),
                        uApi,
                        "https://ghfast.top/" + uApi
                };
                for (String u : urls) {
                    try {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(12000);
                        conn.setRequestProperty("User-Agent", "DSHA/1.1.0-mobile");
                        if (conn.getResponseCode() != 200) {
                            conn.disconnect();
                            continue;
                        }
                        StringBuilder sb = new StringBuilder();
                        String l;
                        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                                conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                        while ((l = br.readLine()) != null) {
                            sb.append(l);
                            if (sb.length() > 400000) break;
                        }
                        conn.disconnect();
                        org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                        org.json.JSONArray arr = j.optJSONArray("items");
                        if (arr == null) continue;
                        for (int k = 0; k < arr.length(); k++) {
                            org.json.JSONObject o = arr.optJSONObject(k);
                            String full = o.optString("full_name", "");
                            long star = o.optLong("stargazers_count", 0);
                            for (int idx : idxs) {
                                String fu = items.get(idx)[6].replace("https://github.com/", "").replace("http://github.com/", "").replace("/", "/");
                                if (full.equalsIgnoreCase(fu)) {
                                    items.get(idx)[1] = String.valueOf(star);
                                    break;
                                }
                            }
                        }
                        break; // 成功则跳过一个源
                    } catch (Exception ignored) {
                    }
                }
            }
            runOnUiThreadSafely(() -> {
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }).start();
    }

    /** 一键安装：点一下就全自动（解析 npm 名 → 安装 → 提示），无二次确认 */
    private void startAutoInstall(String[] it, String owner, String repo) {
        final String display = it[0];
        status.setText("正在解析并安装 " + display + " …");
        new Thread(() -> {
            String npmName = c.fetchNpmName(owner, repo);
            if (npmName == null) {
                runOnUiThreadSafely(() -> {
                    status.setText("无法安装 " + display + "（未发布 npm）");
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle("无法安装：" + display)
                            .setMessage("未在该仓库找到 package.json / npm 包名，可能未发布 npm，只能源码安装。\n\n仓库：\n" + it[6])
                            .setPositiveButton("复制仓库链接", (d, w) -> {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                        requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", it[6]));
                                Toast.makeText(requireContext(), "链接已复制", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                });
                return;
            }
            status.setText("正在安装 " + npmName + " …");
            // npm 名找不到时自动回退 github:owner/repo（市场条目多为仅 GitHub 发布的仓库插件）
            String out = c.installPlugin(npmName, "github:" + owner + "/" + repo);
            final String fOut = out;
            runOnUiThreadSafely(() -> showInstallResult(npmName, display, fOut));
        }).start();
    }

    /** 安装结果（成功/失败）弹窗 + 重启 WebUI 按钮 */
    private void showInstallResult(String pkg, String display, String out) {
        boolean ok = out != null && out.contains("INSTALL_EXIT=0");
        status.setText((ok ? "✅ 安装成功 " : "❌ 安装失败 ") + display + (ok ? "，重启 WebUI 生效" : ""));
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle((ok ? "✅ 安装成功：" : "❌ 安装失败：") + display)
                .setMessage(out == null ? "无输出" : out)
                .setPositiveButton("重启 WebUI", (d, w) -> {
                    android.content.Intent stop = new android.content.Intent(requireContext(), HarnessService.class)
                            .setAction(HarnessService.ACTION_STOP);
                    requireContext().startService(stop);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        android.content.Intent i = new android.content.Intent(requireContext(), HarnessService.class);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            requireContext().startForegroundService(i);
                        } else {
                            requireContext().startService(i);
                        }
                        status.setText("WebUI 已重启");
                    }, 1500);
                })
                .setNegativeButton("关闭", null)
                .show();
    }


    private void doInstall(String pkg) {
        status.setText("正在安装 " + pkg + " …");
        new Thread(() -> {
            String out = c.installPlugin(pkg);
            runOnUiThreadSafely(() -> {
                status.setText("安装结果：" + (out == null ? "无输出" : out.replace("\n", " ").substring(0, Math.min(200, out.length()))));
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("安装完成")
                        .setMessage(out == null ? "无输出" : out)
                        .setPositiveButton("重启 WebUI", (d, w) -> {
                            android.content.Intent stop = new android.content.Intent(requireContext(), HarnessService.class)
                                    .setAction(HarnessService.ACTION_STOP);
                            requireContext().startService(stop);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                android.content.Intent i = new android.content.Intent(requireContext(), HarnessService.class);
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    requireContext().startForegroundService(i);
                                } else {
                                    requireContext().startService(i);
                                }
                                Toast.makeText(requireContext(), "正在重启 Web UI…", Toast.LENGTH_SHORT).show();
                            }, 1500);
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        }).start();
    }

    private class PluginAdapter extends RecyclerView.Adapter<PluginAdapter.VH> {

        private List<String[]> data = new ArrayList<>();
        private boolean isMarket = true;

        void setData(List<String[]> d, boolean market) {
            data = d;
            isMarket = market;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_plugin, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            String[] it = data.get(pos);
            if (isMarket) {
                h.name.setText(it[0]);
                h.desc.setText(it[5]);
                h.status.setText("⭐ " + it[1] + " · 👤 " + (it[2].isEmpty() ? "?" : it[2]) + " · " + it[3] + " · " + it[4]);
                h.installBtn.setVisibility(View.VISIBLE);
                h.switchView.setVisibility(View.GONE);
                h.itemView.setOnClickListener(v -> showDetail(it));
                h.installBtn.setOnClickListener(v -> startAutoInstall(it, it[2], it[6].substring(it[6].lastIndexOf('/') + 1)));
            } else {
                h.name.setText(it[0]);
                h.desc.setText("");
                boolean enabled = "启用".equals(it[1]);
                h.status.setText(enabled ? "已启用" : "已禁用");
                h.installBtn.setVisibility(View.GONE);
                h.switchView.setVisibility(View.VISIBLE);
                h.itemView.setOnClickListener(null); // 防止 RecyclerView 复用到市场的点击监听
                // 长按卸载（问题插件一键移除）
                h.itemView.setOnLongClickListener(v -> {
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle("卸载插件：" + it[0])
                            .setMessage("将执行：dsh plugin --profile web remove " + it[0] + "\n\n确定卸载？")
                            .setPositiveButton("卸载", (d, w) -> {
                                status.setText("正在卸载 " + it[0] + " …");
                                new Thread(() -> {
                                    String out = c.removePlugin(it[0]);
                                    runOnUiThreadSafely(() -> {
                                        status.setText("卸载结果：" + (out == null ? "无输出" : out.replace("\n", " ").substring(0, Math.min(150, out.length()))));
                                        Toast.makeText(requireContext(), "卸载完成，重启 WebUI 生效", Toast.LENGTH_SHORT).show();
                                        showInstalled();
                                    });
                                }).start();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                });
                h.switchView.setOnCheckedChangeListener(null);
                h.switchView.setChecked(enabled);
                h.switchView.setOnCheckedChangeListener((btn, checked) -> {
                    boolean ok = c.togglePlugin(it[0], checked);
                    if (ok) {
                        it[1] = checked ? "启用" : "禁用";
                        h.status.setText(checked ? "已启用" : "已禁用");
                        Toast.makeText(requireContext(), it[0] + (checked ? " 已启用（重启 WebUI 生效）" : " 已禁用"), Toast.LENGTH_SHORT).show();
                    } else {
                        btn.setChecked(!checked);
                        Toast.makeText(requireContext(), "操作失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name, desc, status;
            android.widget.Switch switchView;
            TextView installBtn;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.pluginName);
                desc = v.findViewById(R.id.pluginDesc);
                status = v.findViewById(R.id.pluginStatus);
                switchView = v.findViewById(R.id.pluginSwitch);
                installBtn = v.findViewById(R.id.pluginInstall);
            }
        }
    }
}
