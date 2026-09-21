package com.deepseekharness.app.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工业级安全 PDF 渲染器：
 * 1. 串行互斥锁：严格保障同一时刻仅 1 个页面处于 openPage 状态；
 * 2. 异步线程池：渲染完全脱离 UI 线程，彻底根除 ANR；
 * 3. 像素硬上限：限制单页面位图最大像素，防止 Adreno GPU QueueBuffer 超时崩溃；
 * 4. 显存回收：LRU 动态缓存，淘汰页面确定性 recycle()。
 */
public final class SheetPdfAdapter extends BaseAdapter {

    private static final int MAX_PAGE_PIXELS = 1920 * 1080; // 约 8MB 显存上限，安全防爆

    private final Context context;
    private final File pdfFile;
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private final int pageCount;
    private final float density;

    private final ReentrantLock renderLock = new ReentrantLock();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 只保留最近 4 页的位图，淘汰的立即物理 recycle()，绝不留给 GC 拖慢主线程
    private final LruCache<Integer, Bitmap> bitmapCache = new LruCache<Integer, Bitmap>(4) {
        @Override
        protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
            if (oldValue != null && !oldValue.isRecycled() && oldValue != newValue) {
                oldValue.recycle();
            }
        }
    };

    private boolean isReleased = false;

    public SheetPdfAdapter(Context context, File pdfFile) throws Exception {
        this.context = context;
        this.pdfFile = pdfFile;
        this.density = context.getResources().getDisplayMetrics().density;
        this.pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
        this.renderer = new PdfRenderer(pfd);
        this.pageCount = renderer.getPageCount();
    }

    @Override
    public int getCount() {
        return pageCount;
    }

    @Override
    public Object getItem(int position) {
        return position;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            FrameLayout frame = new FrameLayout(context);
            int padV = (int) (8 * density);
            int padH = (int) (12 * density);
            frame.setPadding(padH, padV, padH, padV);
            frame.setBackgroundColor(Color.TRANSPARENT);

            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            iv.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ivLp.gravity = Gravity.CENTER;
            frame.addView(iv, ivLp);

            // 占位加载指示区
            TextView tip = new TextView(context);
            tip.setTextColor(Color.parseColor("#888888"));
            tip.setTextSize(12);
            FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int) (260 * density));
            tipLp.gravity = Gravity.CENTER;
            tip.setGravity(Gravity.CENTER);
            frame.addView(tip, tipLp);

            holder = new ViewHolder(frame, iv, tip);
            frame.setTag(holder);
            convertView = frame;
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        holder.position = position;
        Bitmap cached = bitmapCache.get(position);
        if (cached != null && !cached.isRecycled()) {
            holder.imageView.setImageBitmap(cached);
            holder.imageView.setVisibility(View.VISIBLE);
            holder.loadingTip.setVisibility(View.GONE);
            return convertView;
        }

        // 缓存缺失：主线程显示极速占位符，丢给后台异步串行渲染
        holder.imageView.setImageBitmap(null);
        holder.imageView.setVisibility(View.GONE);
        holder.loadingTip.setText("第 " + (position + 1) + " / " + pageCount + " 页 · 渲染中…");
        holder.loadingTip.setVisibility(View.VISIBLE);

        final int targetPos = position;
        renderExecutor.execute(() -> {
            if (isReleased) return;
            Bitmap renderedBmp = null;
            renderLock.lock();
            try {
                if (isReleased || renderer == null) return;
                // 检查缓存
                renderedBmp = bitmapCache.get(targetPos);
                if (renderedBmp == null || renderedBmp.isRecycled()) {
                    PdfRenderer.Page page = renderer.openPage(targetPos);
                    int pageW = page.getWidth();
                    int pageH = page.getHeight();

                    int screenW = context.getResources().getDisplayMetrics().widthPixels;
                    float scale = (float) screenW / pageW;
                    int targetW = (int) (pageW * scale);
                    int targetH = (int) (pageH * scale);

                    // 像素安全熔断：防止超大尺寸把 Adreno GPU 缓冲区撑爆
                    long pixels = (long) targetW * targetH;
                    if (pixels > MAX_PAGE_PIXELS) {
                        float shrink = (float) Math.sqrt((double) MAX_PAGE_PIXELS / pixels);
                        targetW = (int) (targetW * shrink);
                        targetH = (int) (targetH * shrink);
                    }

                    Bitmap bmp = Bitmap.createBitmap(
                            Math.max(1, targetW), Math.max(1, targetH), Bitmap.Config.ARGB_8888);
                    // 预先填充白底，消除半透明底色渗透
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close(); // 必须在 finally 之前严格调用

                    bitmapCache.put(targetPos, bmp);
                    renderedBmp = bmp;
                }
            } catch (Throwable ignored) {
            } finally {
                renderLock.unlock();
            }

            final Bitmap finalBmp = renderedBmp;
            mainHandler.post(() -> {
                if (isReleased) return;
                if (holder.position == targetPos && finalBmp != null && !finalBmp.isRecycled()) {
                    holder.imageView.setImageBitmap(finalBmp);
                    holder.imageView.setVisibility(View.VISIBLE);
                    holder.loadingTip.setVisibility(View.GONE);
                }
            });
        });

        return convertView;
    }

    public void release() {
        isReleased = true;
        renderExecutor.shutdownNow();
        renderLock.lock();
        try {
            bitmapCache.evictAll();
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
            if (pfd != null) {
                pfd.close();
                pfd = null;
            }
        } catch (Exception ignored) {
        } finally {
            renderLock.unlock();
        }
    }

    private static class ViewHolder {
        final FrameLayout root;
        final ImageView imageView;
        final TextView loadingTip;
        int position;

        ViewHolder(FrameLayout root, ImageView imageView, TextView loadingTip) {
            this.root = root;
            this.imageView = imageView;
            this.loadingTip = loadingTip;
        }
    }
}
