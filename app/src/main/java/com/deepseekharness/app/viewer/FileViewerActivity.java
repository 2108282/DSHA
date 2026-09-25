package com.deepseekharness.app.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * 原生全功能文件查看与编辑器：
 * 支持 Sora Editor 代码编辑保存、原生缩放看图、原生 PDF 分页、压缩包内存预览与十六进制查看。
 */
public class FileViewerActivity extends Activity {

    public static final String EXTRA_PATH = "file_path";

    public static void open(Context context, String path) {
        Intent intent = new Intent(context, FileViewerActivity.class);
        intent.putExtra(EXTRA_PATH, path);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private File currentFile;
    private FileTypeClassifier.FileType fileType;
    private FrameLayout contentContainer;
    private TextView titleView;
    private TextView subTitleView;
    private Button btnSave;
    private Button btnHex;

    // 查看组件实例
    private CodeEditor codeEditor;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfPfd;
    private android.util.LruCache<Integer, Bitmap> pdfBitmapCache;
    private java.util.concurrent.ExecutorService pdfExecutor;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "文件路径为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentFile = new File(path);
        if (!currentFile.exists() || !currentFile.isFile()) {
            Toast.makeText(this, "文件不存在：" + path, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fileType = FileTypeClassifier.classify(currentFile);
        initUi();
        loadFile(false);
    }

    private void initUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        // 1. 顶栏
        RelativeLayout header = new RelativeLayout(this);
        int pad = dp(12);
        header.setPadding(pad, dp(8), pad, dp(8));
        header.setBackgroundColor(Color.parseColor("#1E1E1E"));
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // 返回按钮
        TextView btnBack = new TextView(this);
        btnBack.setId(View.generateViewId());
        btnBack.setText("‹ 关闭");
        btnBack.setTextColor(Color.parseColor("#4C8DFF"));
        btnBack.setTextSize(15);
        btnBack.setGravity(Gravity.CENTER_VERTICAL);
        btnBack.setOnClickListener(v -> finish());
        RelativeLayout.LayoutParams lpBack = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lpBack.addRule(RelativeLayout.ALIGN_PARENT_START);
        header.addView(btnBack, lpBack);

        // 标题容器
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        RelativeLayout.LayoutParams lpTitle = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lpTitle.addRule(RelativeLayout.RIGHT_OF, btnBack.getId());
        lpTitle.setMarginStart(dp(12));
        titleBox.setLayoutParams(lpTitle);

        titleView = new TextView(this);
        titleView.setText(currentFile.getName());
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleBox.addView(titleView);

        subTitleView = new TextView(this);
        subTitleView.setText(formatSize(currentFile.length()) + " · " + fileType.kind.name());
        subTitleView.setTextColor(Color.parseColor("#888888"));
        subTitleView.setTextSize(11);
        titleBox.addView(subTitleView);
        header.addView(titleBox);

        // 右侧操作按钮组
        LinearLayout rightGroup = new LinearLayout(this);
        rightGroup.setOrientation(LinearLayout.HORIZONTAL);
        RelativeLayout.LayoutParams lpRight = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lpRight.addRule(RelativeLayout.ALIGN_PARENT_END);
        rightGroup.setLayoutParams(lpRight);

        btnHex = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnHex.setText("Hex");
        btnHex.setTextColor(Color.parseColor("#B0B0B0"));
        btnHex.setTextSize(13);
        btnHex.setOnClickListener(v -> loadHexView());
        rightGroup.addView(btnHex);

        btnSave = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnSave.setText("保存");
        btnSave.setTextColor(Color.parseColor("#4C8DFF"));
        btnSave.setTextSize(13);
        btnSave.setVisibility(View.GONE);
        btnSave.setOnClickListener(v -> saveTextContent());
        rightGroup.addView(btnSave);

        header.addView(rightGroup);
        root.addView(header);

        // 2. 主内容容器
        contentContainer = new FrameLayout(this);
        contentContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        root.addView(contentContainer);

        setContentView(root);
    }

    private void loadFile(boolean forceHex) {
        contentContainer.removeAllViews();
        btnSave.setVisibility(View.GONE);

        if (forceHex || fileType.kind == FileTypeClassifier.FileKind.HEX) {
            loadHexView();
            return;
        }

        switch (fileType.kind) {
            case TEXT:
                loadEditorView();
                break;
            case IMAGE:
                loadImageView();
                break;
            case PDF:
                loadPdfView();
                break;
            case ARCHIVE:
                loadArchiveView();
                break;
            default:
                loadHexView();
                break;
        }
    }

    // ---------------- 1. Sora Editor 代码/文本编辑器 ----------------
    private void loadEditorView() {
        if (currentFile.length() > 5 * 1024 * 1024) {
            Toast.makeText(this, "文件大于5MB，自动切换为十六进制查看", Toast.LENGTH_SHORT).show();
            loadHexView();
            return;
        }

        btnSave.setVisibility(View.VISIBLE);
        codeEditor = new CodeEditor(this);
        codeEditor.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        codeEditor.setColorScheme(new EditorColorScheme());
        codeEditor.setTextSize(14);
        codeEditor.setLineNumberEnabled(true);
        codeEditor.setWordwrap(true);

        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(currentFile.toPath());
            codeEditor.setText(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Toast.makeText(this, "读取文本失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        contentContainer.addView(codeEditor);
    }

    private void saveTextContent() {
        if (codeEditor == null) return;
        try {
            String text = codeEditor.getText().toString();
            File parent = currentFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(parent != null ? parent : currentFile.getParentFile(), "." + currentFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (tmp.renameTo(currentFile) || (currentFile.delete() && tmp.renameTo(currentFile))) {
                Toast.makeText(this, "✓ 已安全保存", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存覆盖失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "保存出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 2. 原生可手势缩放图片查看 ----------------
    private void loadImageView() {
        TouchImageView iv = new TouchImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setBackgroundColor(Color.BLACK);

        try {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(currentFile.getAbsolutePath());
            if (bitmap != null) {
                iv.setImageBitmap(bitmap);
            } else {
                Toast.makeText(this, "无法解码图片", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "图片过大无法加载", Toast.LENGTH_SHORT).show();
        }
        contentContainer.addView(iv);
    }

    // ---------------- 3. 原生 PDF 分页查看 ----------------
    private void loadPdfView() {
        try {
            pdfPfd = ParcelFileDescriptor.open(currentFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(pdfPfd);

            int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
            int cacheSize = Math.max(1024, maxMemory / 8);
            pdfBitmapCache = new android.util.LruCache<Integer, Bitmap>(cacheSize) {
                @Override
                protected int sizeOf(Integer key, Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };
            pdfExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

            ListView listView = new ListView(this);
            listView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            listView.setDivider(null);
            final int screenWidth = getResources().getDisplayMetrics().widthPixels;

            listView.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return pdfRenderer != null ? pdfRenderer.getPageCount() : 0; }
                @Override public Object getItem(int position) { return position; }
                @Override public long getItemId(int position) { return position; }
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    ImageView pageView;
                    if (convertView instanceof ImageView) {
                        pageView = (ImageView) convertView;
                    } else {
                        pageView = new ImageView(FileViewerActivity.this);
                        pageView.setLayoutParams(new ListView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        pageView.setAdjustViewBounds(true);
                        pageView.setPadding(0, 0, 0, dp(8));
                    }

                    pageView.setTag(position);

                    Bitmap cached = pdfBitmapCache != null ? pdfBitmapCache.get(position) : null;
                    if (cached != null && !cached.isRecycled()) {
                        pageView.setImageBitmap(cached);
                    } else {
                        pageView.setImageDrawable(null);
                        pageView.setMinimumHeight(dp(200));
                        final int pos = position;
                        if (pdfExecutor != null && !pdfExecutor.isShutdown()) {
                            pdfExecutor.execute(() -> {
                                if (pdfRenderer == null) return;
                                try {
                                    Bitmap bmp = null;
                                    synchronized (pdfRenderer) {
                                        if (pdfRenderer == null) return;
                                        PdfRenderer.Page page = pdfRenderer.openPage(pos);
                                        int width = screenWidth > 0 ? screenWidth : 1080;
                                        int height = (int) ((float) width / page.getWidth() * page.getHeight());
                                        if (height <= 0) height = dp(200);
                                        bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                        page.close();
                                    }
                                    if (bmp != null) {
                                        final Bitmap finalBmp = bmp;
                                        if (pdfBitmapCache != null) {
                                            pdfBitmapCache.put(pos, finalBmp);
                                        }
                                        runOnUiThread(() -> {
                                            if (isFinishing() || isDestroyed()) return;
                                            if (Integer.valueOf(pos).equals(pageView.getTag())) {
                                                pageView.setImageBitmap(finalBmp);
                                            }
                                        });
                                    }
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    }
                    return pageView;
                }
            });
            contentContainer.addView(listView);
        } catch (Exception e) {
            Toast.makeText(this, "PDF打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            loadHexView();
        }
    }

    // ---------------- 4. 压缩包内存只读浏览 ----------------
    private void loadArchiveView() {
        List<ArchiveBrowser.Entry> entries = ArchiveBrowser.listEntries(currentFile);
        ListView lv = new ListView(this);
        lv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(FileViewerActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(16), dp(10), dp(16), dp(10));
                    TextView tvName = new TextView(FileViewerActivity.this);
                    tvName.setId(101);
                    tvName.setTextColor(Color.WHITE);
                    tvName.setTextSize(14);
                    row.addView(tvName);

                    TextView tvInfo = new TextView(FileViewerActivity.this);
                    tvInfo.setId(102);
                    tvInfo.setTextColor(Color.parseColor("#888888"));
                    tvInfo.setTextSize(11);
                    row.addView(tvInfo);
                }

                ArchiveBrowser.Entry e = entries.get(position);
                TextView tvName = row.findViewById(101);
                TextView tvInfo = row.findViewById(102);
                tvName.setText((e.isDirectory ? "📁 " : "📄 ") + e.path);
                tvInfo.setText(formatSize(e.size));
                return row;
            }
        });
        lv.setOnItemClickListener((parent, view, position, id) -> {
            ArchiveBrowser.Entry e = entries.get(position);
            if (!e.isDirectory) {
                String text = ArchiveBrowser.readEntryText(currentFile, e.path);
                if (text != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(e.name)
                            .setMessage(text.length() > 3000 ? text.substring(0, 3000) + "\n\n(已截断显示)" : text)
                            .setPositiveButton("确定", null)
                            .show();
                } else {
                    Toast.makeText(this, "该文件不支持直接预览文本", Toast.LENGTH_SHORT).show();
                }
            }
        });
        contentContainer.addView(lv);
    }

    // ---------------- 5. 十六进制 64KB 随机读查看器 ----------------
    private void loadHexView() {
        btnSave.setVisibility(View.GONE);
        contentContainer.removeAllViews();

        long len = currentFile.length();
        int totalRows = HexDumper.rowCount(len);

        ListView lv = new ListView(this);
        lv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lv.setBackgroundColor(Color.parseColor("#0D0D0D"));
        lv.setDivider(null);

        lv.setAdapter(new BaseAdapter() {
            private int cachedBlockIndex = -1;
            private List<HexDumper.HexRow> cachedRows = new ArrayList<>();

            @Override public int getCount() { return totalRows; }
            @Override public Object getItem(int position) { return position; }
            @Override public long getItemId(int position) { return position; }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv;
                if (convertView instanceof TextView) {
                    tv = (TextView) convertView;
                } else {
                    tv = new TextView(FileViewerActivity.this);
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setTextSize(11);
                    tv.setTextColor(Color.parseColor("#D4D4D4"));
                    tv.setPadding(dp(8), dp(2), dp(8), dp(2));
                }

                long offset = HexDumper.rowOffset(position);
                int blockIdx = HexDumper.blockOf(offset);
                if (blockIdx != cachedBlockIndex) {
                    cachedBlockIndex = blockIdx;
                    byte[] blk = HexDumper.readBlock(currentFile, blockIdx);
                    cachedRows = HexDumper.formatBlock(blk, (long) blockIdx * HexDumper.BLOCK_SIZE);
                }

                int localRow = position % (HexDumper.BLOCK_SIZE / HexDumper.ROW_BYTES);
                if (localRow >= 0 && localRow < cachedRows.size()) {
                    HexDumper.HexRow r = cachedRows.get(localRow);
                    tv.setText(String.format("%08X  %s  |%s|", r.offset, r.hex, r.ascii));
                }
                return tv;
            }
        });
        contentContainer.addView(lv);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdfExecutor != null) {
                pdfExecutor.shutdownNow();
            }
            if (pdfRenderer != null) {
                synchronized (pdfRenderer) {
                    pdfRenderer.close();
                    pdfRenderer = null;
                }
            }
            if (pdfPfd != null) pdfPfd.close();
            if (pdfBitmapCache != null) {
                pdfBitmapCache.evictAll();
            }
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** 支持双指缩放与拖动的 ImageView */
    private static class TouchImageView extends androidx.appcompat.widget.AppCompatImageView implements View.OnTouchListener {
        private final Matrix matrix = new Matrix();
        private final Matrix savedMatrix = new Matrix();
        private static final int NONE = 0;
        private static final int DRAG = 1;
        private static final int ZOOM = 2;
        private int mode = NONE;
        private final PointF start = new PointF();
        private final PointF mid = new PointF();
        private float oldDist = 1f;

        public TouchImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            setOnTouchListener(this);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    start.set(event.getX(), event.getY());
                    mode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist = spacing(event);
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix);
                        midPoint(mid, event);
                        mode = ZOOM;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                    } else if (mode == ZOOM) {
                        float newDist = spacing(event);
                        if (newDist > 10f) {
                            matrix.set(savedMatrix);
                            float scale = newDist / oldDist;
                            matrix.postScale(scale, scale, mid.x, mid.y);
                        }
                    }
                    break;
            }
            setImageMatrix(matrix);
            return true;
        }

        private float spacing(MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void midPoint(PointF point, MotionEvent event) {
            point.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2);
        }
    }
}
