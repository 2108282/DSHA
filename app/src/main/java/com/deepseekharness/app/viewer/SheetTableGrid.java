package com.deepseekharness.app.viewer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 高性能 Excel 电子表格网格控件：
 * 基于 ListView 虚拟视图回收机制，百万单元格内存恒定，测量零延迟。
 */
public final class SheetTableGrid {

    public static View createGridView(Context context, String tsvContent) {
        if (context == null || tsvContent == null || tsvContent.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("表格无内容");
            empty.setTextColor(Color.GRAY);
            empty.setPadding(32, 32, 32, 32);
            return empty;
        }

        final float dp = context.getResources().getDisplayMetrics().density;
        final int cellPadH = (int) (12 * dp);
        final int cellPadV = (int) (8 * dp);

        // 解析行与列数据（纯内存字符串切分，极速）
        String[] rawLines = tsvContent.split("\n");
        final List<String[]> rowDataList = new ArrayList<>();
        int maxCols = 0;
        for (String l : rawLines) {
            if (l.trim().isEmpty()) continue;
            String[] cols = l.split("\t", -1);
            if (cols.length > maxCols) maxCols = cols.length;
            rowDataList.add(cols);
            if (rowDataList.size() >= 2000) break; // 最多显示前 2000 行，防病态大表
        }

        final int finalMaxCols = maxCols;

        // 水平滚动包裹 ListView
        HorizontalScrollView hsv = new HorizontalScrollView(context);
        hsv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hsv.setBackgroundColor(Color.TRANSPARENT);

        ListView lv = new ListView(context);
        // 按最大列数估算宽度，确保横向可以滑出所有列（每列预留 120dp）
        int estimatedWidth = Math.max((int) (finalMaxCols * 120 * dp), context.getResources().getDisplayMetrics().widthPixels);
        lv.setLayoutParams(new HorizontalScrollView.LayoutParams(
                estimatedWidth, ViewGroup.LayoutParams.MATCH_PARENT));
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setDivider(null);

        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return rowDataList.size(); }
            @Override public Object getItem(int position) { return rowDataList.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                }

                String[] cells = rowDataList.get(position);
                boolean isHeader = (position == 0);
                boolean isSeparator = (cells.length == 1 && cells[0].startsWith("--- 工作表"));

                int rowBg = isSeparator ? Color.parseColor("#334C8DFF") :
                        (isHeader ? Color.parseColor("#284C8DFF") :
                                (position % 2 == 0 ? Color.parseColor("#15FFFFFF") : Color.parseColor("#08FFFFFF")));
                row.setBackgroundColor(rowBg);

                // 动态调整子 TextView 数量（ViewHolder 复用）
                int childCount = row.getChildCount();
                int targetCount = Math.max(cells.length, finalMaxCols);

                for (int i = 0; i < targetCount; i++) {
                    TextView tv;
                    if (i < childCount) {
                        tv = (TextView) row.getChildAt(i);
                        tv.setVisibility(View.VISIBLE);
                    } else {
                        tv = new TextView(context);
                        tv.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                (int) (120 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
                        tv.setLayoutParams(lp);
                        tv.setGravity(Gravity.CENTER_VERTICAL);
                        row.addView(tv);
                    }

                    if (isSeparator) {
                        if (i == 0) {
                            tv.setText(cells[0]);
                            tv.setTextColor(Color.parseColor("#4C8DFF"));
                            tv.setTypeface(Typeface.DEFAULT_BOLD);
                            tv.setTextSize(13);
                        } else {
                            tv.setText("");
                        }
                    } else {
                        String txt = (i < cells.length) ? cells[i] : "";
                        tv.setText(txt);
                        tv.setTextSize(isHeader ? 13 : 12);
                        tv.setTextColor(isHeader ? Color.WHITE : Color.parseColor("#E0E0E0"));
                        tv.setTypeface(isHeader ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    }
                }

                // 隐藏多余的子 View
                for (int i = targetCount; i < row.getChildCount(); i++) {
                    row.getChildAt(i).setVisibility(View.GONE);
                }

                return row;
            }
        });

        hsv.addView(lv);
        return hsv;
    }
}
