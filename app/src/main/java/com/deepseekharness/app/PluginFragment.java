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
    /** 当前排序：0 star / 1 名称 */
    private int sortMode = 0;
    /** 仅显示兼容插件（过滤 ❌不兼容） */
    private boolean filterIncompat = false;
    /** 当前搜索词（供过滤/排序后刷新视图复用） */
    private String searchQuery = "";

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
        // ===== GitHub 仓库链接安装（市场顶部）=====
        final android.widget.EditText githubInput = view.findViewById(R.id.githubInstallInput);
        TextView btnGithubInstall = view.findViewById(R.id.btnGithubInstall);
        if (githubInput != null && btnGithubInstall != null) {
            java.util.function.Consumer<String> doGithubInstall = (link) -> {
                String u = link == null ? "" : link.trim();
                if (u.isEmpty()) {
                    Toast.makeText(requireContext(), "请粘贴 GitHub 仓库链接", Toast.LENGTH_SHORT).show();
                    return;
                }
                status.setText("正在解析并安装 " + u + " …");
                new Thread(() -> {
                    String out = c.installFromGithubUrl(u);
                    runOnUiThreadSafely(() -> showInstallResult(u, u, out));
                }).start();
            };
            btnGithubInstall.setOnClickListener(v -> doGithubInstall.accept(githubInput.getText().toString()));
            // 输入框回车触发
            githubInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    doGithubInstall.accept(githubInput.getText().toString());
                    return true;
                }
                return false;
            });
        }
        view.findViewById(R.id.actionBar).setVisibility(View.GONE);

        // 搜索：按名称过滤（忽略大小写）
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchQuery = s.toString();
                if (mode == Mode.MARKET) {
                    refreshMarketView();
                } else if (mode == Mode.INSTALLED) {
                    String q = searchQuery.trim().toLowerCase();
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
            styleTab(btnMarket, true);
            styleTab(btnInstalled, false);
            view.findViewById(R.id.actionBar).setVisibility(View.GONE);
            view.findViewById(R.id.chkHideBuiltin).setVisibility(View.GONE);
            showMarket();
        });
        btnInstalled.setOnClickListener(v -> {
            mode = Mode.INSTALLED;
            styleTab(btnMarket, false);
            styleTab(btnInstalled, true);
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

    private void styleTab(TextView tab, boolean on) {
        tab.setBackgroundResource(on ? R.drawable.bg_tab_on : R.drawable.bg_tab);
        tab.setTextColor(requireContext().getColor(on ? R.color.primary : R.color.text_muted));
    }

    /** 排序下拉菜单：点一下展开选择，不用一直点循环。
     *  菜单里同时提供「仅显示兼容」勾选项（过滤 ❌不兼容，⏳待定/未测保留）。 */
    private void showSortMenu(android.view.View anchor) {
        final String[] options = {"⭐ Star 数", "🔤 名称 A-Z"};
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        for (int i = 0; i < options.length; i++) {
            pm.getMenu().add(0, i, 0, options[i]);
        }
        // 分隔线 + 过滤开关（勾选态与 filterIncompat 同步）
        pm.getMenu().add(0, 100, 0, "仅显示兼容");
        pm.getMenu().getItem(2).setCheckable(true).setChecked(filterIncompat);
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 100) {
                filterIncompat = !filterIncompat;
                item.setChecked(filterIncompat);
                if (mode == Mode.MARKET) refreshMarketView();
                return true;
            }
            sortMode = item.getItemId();
            ((android.widget.TextView) anchor).setText(options[sortMode].replace("排序：", ""));
            if (mode == Mode.MARKET) refreshMarketView();
            return true;
        });
        pm.show();
    }

    /** 安全解析 star 数（外部数据源格式变化不崩溃） */
    private static int safeStar(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void applySort() {
        final int sm = sortMode;
        Collections.sort(items, (a, b) -> {
            switch (sm) {
                case 0: // star 降序
                    int sa = safeStar(a[1]);
                    int sb = safeStar(b[1]);
                    return sb - sa;
                default: // 名称
                    return a[0].toLowerCase().compareTo(b[0].toLowerCase());
            }
        });
    }

    /** 判断某条目是否"不兼容"（兼容新旧索引格式的多种表述）：
     *  ❌不兼容 / ❌ 不兼容 / 不兼容 / 运行级不兼容 等一律命中；
     *  但"可用"类表述（✅可用/✅ 可用/可用）绝不误判。 */
    private static boolean isIncompat(String compat) {
        if (compat == null) return false;
        String c = compat.trim();
        if (c.startsWith("❌")) return true;
        return c.contains("不兼容") && !c.contains("可用");
    }

    /** 刷新市场视图：按 搜索词 + 仅兼容开关 过滤，再排序，更新列表与状态栏。
     * 基于全量 items 每次重新计算，保证各条件可叠加。 */
    private void refreshMarketView() {
        if (mode != Mode.MARKET) return;
        applySort();
        java.util.List<String[]> filtered = new java.util.ArrayList<>();
        String q = searchQuery.trim().toLowerCase();
        int skipped = 0;
        for (String[] it : items) {
            if (!q.isEmpty() && !it[0].toLowerCase().contains(q)) continue;
            // 仅兼容开关：滤掉不兼容条目（⏳待定/未测 保留，未知兼容性不误杀）
            if (filterIncompat && isIncompat(it[3])) {
                skipped++;
                continue;
            }
            filtered.add(it);
        }
        adapter.setData(filtered, true);
        String hint = "共 " + filtered.size() + " 个插件";
        if (!q.isEmpty()) hint += "（搜索：\"" + q + "\"）";
        if (filterIncompat) hint += " · 仅显示兼容（已滤 " + skipped + " 条不兼容）";
        hint += " · 点击查看详情/安装" + cacheHint();
        status.setText(hint);
    }

    /** 线程回调安全切主线程（Fragment detach 后不再崩溃）：未 attach 则丢弃 */
    private void runOnUiThreadSafely(java.lang.Runnable r) {
        if (!isAdded()) return;
        android.app.Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(r);
    }

    private void showMarket() {
        if (!items.isEmpty()) {
            refreshMarketView();
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
                refreshMarketView();
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

    /** 批量异步拉取市场列表 star 数（GitHub search API，每批 ~80 仓库）。
     *  注意匿名 API 限流 10 次/分钟 + 1700+ 条全拉需要 22 批 × 6s ≈ 2 分钟，且几乎必然 403。
     *  → 只刷新【前 1 批】（当前页可见的 80 条，1 个请求，限流内轻松完成）；
     *    其余条目保留索引自带 star。遇 403/失败立即停止（不浪费配额）。 */
    private void fetchStars(java.util.List<String[]> items) {
        if (items == null || items.isEmpty()) return;
        new Thread(() -> {
            StringBuilder q = new StringBuilder("q=");
            int n = 0;
            java.util.List<Integer> idxs = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(items.size(), 80); i++) {
                String u = items.get(i)[6].replace("https://github.com/", "").replace("http://github.com/", "");
                if (u.contains("/") && !u.startsWith("http")) {
                    if (n > 0) q.append("+");
                    q.append("repo:").append(u);
                    idxs.add(i);
                    n++;
                }
            }
            if (n == 0) return;
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
                    conn.setRequestProperty("User-Agent", "DSHA/" + c.getVersionNameForUa());
                    if (conn.getResponseCode() != 200) {
                        conn.disconnect();
                        continue; // 403 = 限流 → 直接放弃，不再重试其他源
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
                    if (arr != null) {
                        for (int k = 0; k < arr.length(); k++) {
                            org.json.JSONObject o = arr.optJSONObject(k);
                            String full = o.optString("full_name", "");
                            long star = o.optLong("stargazers_count", 0);
                            for (int idx : idxs) {
                                String fu = items.get(idx)[6].replace("https://github.com/", "").replace("http://github.com/", "");
                                if (full.equalsIgnoreCase(fu)) {
                                    items.get(idx)[1] = String.valueOf(star);
                                    break;
                                }
                            }
                        }
                    }
                    break; // 成功即止
                } catch (Exception ignored) {
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
                    // 1.5s 延迟回调期间用户可能已离开本页：全程用 applicationContext，
                    // 不能在回调里再 requireContext()（fragment detach 后必抛异常闪退）
                    final android.content.Context app = requireContext().getApplicationContext();
                    android.content.Intent stop = new android.content.Intent(app, HarnessService.class)
                            .setAction(HarnessService.ACTION_STOP);
                    app.startService(stop);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        android.content.Intent i = new android.content.Intent(app, HarnessService.class);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            app.startForegroundService(i);
                        } else {
                            app.startService(i);
                        }
                        if (isAdded()) status.setText("WebUI 已重启");
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
                            // 同 showInstallResult：延迟回调用 applicationContext，防 detach 后闪退
                            final android.content.Context app = requireContext().getApplicationContext();
                            android.content.Intent stop = new android.content.Intent(app, HarnessService.class)
                                    .setAction(HarnessService.ACTION_STOP);
                            app.startService(stop);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                android.content.Intent i = new android.content.Intent(app, HarnessService.class);
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    app.startForegroundService(i);
                                } else {
                                    app.startService(i);
                                }
                                android.widget.Toast.makeText(app, "正在重启 Web UI…", android.widget.Toast.LENGTH_SHORT).show();
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
