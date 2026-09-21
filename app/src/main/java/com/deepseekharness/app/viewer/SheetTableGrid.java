package com.deepseekharness.app.viewer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

/**
 * 极简 Excel 电子表格网格控件：支持水平与垂直双向滚动、表头高亮与斑马纹交替底色。
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

        float dp = context.getResources().getDisplayMetrics().density;
        int cellPadH = (int) (12 * dp);
        int cellPadV = (int) (8 * dp);

        ScrollView vertScroll = new ScrollView(context);
        vertScroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vertScroll.setBackgroundColor(Color.TRANSPARENT);

        HorizontalScrollView horizScroll = new HorizontalScrollView(context);
        horizScroll.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        horizScroll.setBackgroundColor(Color.TRANSPARENT);

        TableLayout table = new TableLayout(context);
        table.setLayoutParams(new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        table.setBackgroundColor(Color.parseColor("#33888888")); // 细网格边框底色

        String[] lines = tsvContent.split("\n");
        boolean isHeader = true;

        for (int rowIdx = 0; rowIdx < lines.length; rowIdx++) {
            String line = lines[rowIdx];
            if (line.trim().isEmpty()) continue;

            if (line.startsWith("--- 工作表")) {
                // 工作表分界行
                TableRow sepRow = new TableRow(context);
                TextView sepText = new TextView(context);
                sepText.setText(line);
                sepText.setTextColor(Color.parseColor("#4C8DFF"));
                sepText.setTextSize(13);
                sepText.setTypeface(Typeface.DEFAULT_BOLD);
                sepText.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
                sepRow.addView(sepText);
                table.addView(sepRow);
                isHeader = true;
                continue;
            }

            TableRow row = new TableRow(context);
            // 斑马纹交替行底色
            int rowBg = isHeader ? Color.parseColor("#284C8DFF") :
                    (rowIdx % 2 == 0 ? Color.parseColor("#1E1E1E") : Color.parseColor("#262626"));
            row.setBackgroundColor(rowBg);

            String[] cells = line.split("\t", -1);
            for (String cellText : cells) {
                TextView tv = new TextView(context);
                tv.setText(cellText);
                tv.setTextSize(isHeader ? 13 : 12);
                tv.setTextColor(isHeader ? Color.WHITE : Color.parseColor("#E0E0E0"));
                if (isHeader) tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(tv);
            }

            table.addView(row);
            if (isHeader) isHeader = false;
        }

        horizScroll.addView(table);
        vertScroll.addView(horizScroll);
        return vertScroll;
    }
}
