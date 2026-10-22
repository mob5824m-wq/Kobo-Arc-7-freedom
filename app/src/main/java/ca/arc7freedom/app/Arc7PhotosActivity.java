package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full Skinned Standalone **Arc7 Photos** Gallery & Non-Destructive Photo Studio (`v2.0.0`):
 *   - Scans all photos & videos across Internal Storage & MicroSD with fast cached thumbnails
 *   - Dynamic Album filter bar + 4-column responsive thumbnail grid
 *   - Full-screen pinch-to-zoom (`ScaleGestureDetector`) + pan + double-tap viewer
 *   - Built-in Photo Studio: Rotate 90°, Mirror Flip, 7 Live Color Filters (`Original`,
 *     `Vivid Pop`, `Warm Golden`, `Noir B&W`, `Vintage Sepia`, `Cool Cinema`, `E-Ink Crisp`),
 *     Save High-Res Copy, 1-Tap Set as Arc7 Wallpaper, Slideshow, Delete, & Open Video in VLC!
 */
public class Arc7PhotosActivity extends Activity {

    private static final String[] FILTER_NAMES = new String[]{
            "Original", "Vivid Pop", "Warm Golden", "Noir B&W",
            "Vintage Sepia", "Cool Cinema", "E-Ink Crisp"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(3);
    private LruCache<String, Bitmap> thumbCache;

    private final List<File> allMedia = new ArrayList<File>();
    private final List<File> visibleMedia = new ArrayList<File>();
    private final Map<String, List<File>> albumMap = new LinkedHashMap<String, List<File>>();
    private String selectedAlbum = "All Photos & Videos";

    private LinearLayout albumBar;
    private GridView gridView;
    private PhotoGridAdapter adapter;
    private TextView countBannerTv;

    // Full-screen viewer & editor state
    private LinearLayout viewerOverlay;
    private ZoomableImageView fullImageView;
    private TextView viewerTitleTv;
    private int currentIndex = 0;
    private Bitmap currentBitmap = null;
    private int currentRotationDeg = 0;
    private boolean currentFlippedH = false;
    private int currentFilterIdx = 0;
    private boolean slideshowRunning = false;

    private final Runnable slideshowTick = new Runnable() {
        @Override
        public void run() {
            if (!slideshowRunning || visibleMedia.isEmpty()) return;
            openItemAt((currentIndex + 1) % visibleMedia.size());
            ui.postDelayed(this, 3500L);
        }
    };

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
        thumbCache = new LruCache<String, Bitmap>(Math.max(4096, cacheKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        FrameLayout rootFrame = new FrameLayout(this);

        LinearLayout browsePage = new LinearLayout(this);
        browsePage.setOrientation(LinearLayout.VERTICAL);
        browsePage.setBackgroundColor(pal.bgPage);

        LinearLayout topRight = new LinearLayout(this);
        topRight.setOrientation(LinearLayout.HORIZONTAL);

        Button refreshBtn = Arc7Skin.actionButton(this, "Rescan", 0xFF334155);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanMediaAsync();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.rightMargin = dp(8);
        topRight.addView(refreshBtn, rlp);

        Button camBtn = Arc7Skin.actionButton(this, "Open Camera", 0xFF059669);
        camBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Arc7PhotosActivity.this, Arc7CameraActivity.class));
            }
        });
        topRight.addView(camBtn);

        browsePage.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_photos,
                "Photos",
                "Albums • Pinch-to-Zoom • Photo Filter Studio • Slideshow • Wallpaper",
                topRight));

        HorizontalScrollView albumScroll = new HorizontalScrollView(this);
        albumScroll.setHorizontalScrollBarEnabled(false);
        albumScroll.setBackgroundColor(pal.surface);
        albumBar = new LinearLayout(this);
        albumBar.setOrientation(LinearLayout.HORIZONTAL);
        albumBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        albumScroll.addView(albumBar);
        browsePage.addView(albumScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        countBannerTv = new TextView(this);
        countBannerTv.setText("Scanning photos & videos across Internal Storage + MicroSD...");
        countBannerTv.setTextColor(pal.textMuted);
        countBannerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        countBannerTv.setPadding(dp(14), dp(8), dp(14), dp(6));
        browsePage.addView(countBannerTv);

        gridView = new GridView(this);
        gridView.setNumColumns(4);
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setHorizontalSpacing(dp(6));
        gridView.setVerticalSpacing(dp(6));
        gridView.setPadding(dp(10), dp(4), dp(10), dp(14));
        adapter = new PhotoGridAdapter();
        gridView.setAdapter(adapter);
        browsePage.addView(gridView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootFrame.addView(browsePage);

        // Full-Screen Photo Viewer + Live Photo Studio Overlay
        buildViewerOverlay(rootFrame);
        setContentView(rootFrame);
        Arc7UiAnimator.attachActivityAnimations(this);

        scanMediaAsync();
        if (getIntent() != null && getIntent().getData() != null
                && "file".equalsIgnoreCase(getIntent().getData().getScheme())) {
            File f = new File(getIntent().getData().getPath());
            if (f.isFile()) {
                visibleMedia.add(0, f);
                openItemAt(0);
            }
        }
    }

    private void buildViewerOverlay(FrameLayout rootFrame) {
        viewerOverlay = new LinearLayout(this);
        viewerOverlay.setOrientation(LinearLayout.VERTICAL);
        viewerOverlay.setBackgroundColor(0xF605080D);
        viewerOverlay.setVisibility(View.GONE);

        // Top viewer bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        top.setBackgroundColor(0xCC0F172A);

        Button backBtn = Arc7Skin.actionButton(this, "◀ Back", 0xFF334155);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeViewer();
            }
        });
        top.addView(backBtn);

        viewerTitleTv = new TextView(this);
        viewerTitleTv.setTextColor(Color.WHITE);
        viewerTitleTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        viewerTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        viewerTitleTv.setPadding(dp(12), 0, dp(12), 0);
        viewerTitleTv.setSingleLine(true);
        top.addView(viewerTitleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button slideBtn = Arc7Skin.actionButton(this, "Slideshow", 0xFF0284C7);
        slideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                slideshowRunning = !slideshowRunning;
                ui.removeCallbacks(slideshowTick);
                if (slideshowRunning) {
                    Toast.makeText(Arc7PhotosActivity.this, "Slideshow started!", Toast.LENGTH_SHORT).show();
                    ui.postDelayed(slideshowTick, 3000L);
                } else {
                    Toast.makeText(Arc7PhotosActivity.this, "Slideshow paused", Toast.LENGTH_SHORT).show();
                }
            }
        });
        top.addView(slideBtn);
        viewerOverlay.addView(top);

        // Pinch-to-Zoom Photo Stage
        fullImageView = new ZoomableImageView(this);
        viewerOverlay.addView(fullImageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom Studio Edit & Action Bar
        HorizontalScrollView botScroll = new HorizontalScrollView(this);
        botScroll.setBackgroundColor(0xDD0F172A);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(10), dp(8), dp(10), dp(8));

        addStudioToolButton(tools, "◀ Prev", 0xFF334155, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!visibleMedia.isEmpty()) {
                    openItemAt((currentIndex - 1 + visibleMedia.size()) % visibleMedia.size());
                }
            }
        });
        addStudioToolButton(tools, "Next ▶", 0xFF334155, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!visibleMedia.isEmpty()) {
                    openItemAt((currentIndex + 1) % visibleMedia.size());
                }
            }
        });
        addStudioToolButton(tools, "Rotate 90°", 0xFF0284C7, new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentRotationDeg = (currentRotationDeg + 90) % 360;
                refreshRenderedEditorPreview();
            }
        });
        addStudioToolButton(tools, "Flip Mirror", 0xFF0284C7, new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentFlippedH = !currentFlippedH;
                refreshRenderedEditorPreview();
            }
        });
        addStudioToolButton(tools, "Filter", 0xFF7C3AED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentFilterIdx = (currentFilterIdx + 1) % FILTER_NAMES.length;
                refreshRenderedEditorPreview();
                Toast.makeText(Arc7PhotosActivity.this,
                        "Filter: " + FILTER_NAMES[currentFilterIdx], Toast.LENGTH_SHORT).show();
            }
        });
        addStudioToolButton(tools, "Save Copy", 0xFF059669, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveEditedPhotoCopy();
            }
        });
        addStudioToolButton(tools, "Set Wallpaper", 0xFFD97706, new View.OnClickListener() {
            @Override public void onClick(View v) {
                applyCurrentAsWallpaper();
            }
        });
        addStudioToolButton(tools, "Delete", 0xFFDC2626, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDeleteCurrent();
            }
        });

        botScroll.addView(tools);
        viewerOverlay.addView(botScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootFrame.addView(viewerOverlay);
    }

    private void addStudioToolButton(LinearLayout parent, String text, int color, View.OnClickListener listener) {
        Button b = Arc7Skin.actionButton(this, text, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setOnClickListener(listener);
        parent.addView(b, lp);
    }

    private void scanMediaAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<File> found = new ArrayList<File>();
                String[] roots = new String[]{
                        Environment.getExternalStorageDirectory().getAbsolutePath(),
                        "/storage/sdcard1"
                };
                for (int i = 0; i < roots.length; i++) {
                    File d = new File(roots[i]);
                    if (d.isDirectory()) collectImagesAndVideos(d, 0, found);
                }
                Collections.sort(found, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.valueOf(b.lastModified()).compareTo(Long.valueOf(a.lastModified()));
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        allMedia.clear();
                        allMedia.addAll(found);
                        rebuildAlbums();
                        applyAlbumSelection(selectedAlbum);
                    }
                });
            }
        }, "Arc7PhotosScanner").start();
    }

    private static boolean isVideoFile(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".3gp")
                || n.endsWith(".webm") || n.endsWith(".avi") || n.endsWith(".mov");
    }

    private static void collectImagesAndVideos(File dir, int depth, List<File> out) {
        if (depth > 5 || out.size() >= 1500) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if ("Android".equals(n) || "cacerts".equals(n)) continue;
                collectImagesAndVideos(f, depth + 1, out);
            } else if (f.length() > 1024) {
                String lo = n.toLowerCase(Locale.US);
                if (lo.endsWith(".jpg") || lo.endsWith(".jpeg") || lo.endsWith(".png")
                        || lo.endsWith(".webp") || lo.endsWith(".gif") || lo.endsWith(".bmp")
                        || isVideoFile(f)) {
                    out.add(f);
                }
            }
        }
    }

    private void rebuildAlbums() {
        albumMap.clear();
        albumMap.put("All Photos & Videos", new ArrayList<File>(allMedia));
        for (int i = 0; i < allMedia.size(); i++) {
            File f = allMedia.get(i);
            String folder = f.getParentFile() != null ? f.getParentFile().getName() : "Storage";
            List<File> bucket = albumMap.get(folder);
            if (bucket == null) {
                bucket = new ArrayList<File>();
                albumMap.put(folder, bucket);
            }
            bucket.add(f);
        }

        albumBar.removeAllViews();
        for (final Map.Entry<String, List<File>> entry : albumMap.entrySet()) {
            boolean active = entry.getKey().equals(selectedAlbum);
            Button chip = Arc7Skin.actionButton(
                    this, entry.getKey() + " (" + entry.getValue().size() + ")",
                    active ? 0xFF0284C7 : 0xFF334155);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyAlbumSelection(entry.getKey());
                }
            });
            albumBar.addView(chip, lp);
        }
    }

    private void applyAlbumSelection(String album) {
        if (!albumMap.containsKey(album)) album = "All Photos & Videos";
        selectedAlbum = album;
        visibleMedia.clear();
        List<File> list = albumMap.get(album);
        if (list != null) visibleMedia.addAll(list);
        countBannerTv.setText(visibleMedia.isEmpty()
                ? "No photos or videos yet — tap 'Open Camera' above to take photos or record HD video!"
                : (album + "  •  " + visibleMedia.size() + " items (tap any photo for Pinch-Zoom & Photo Filter Studio)"));
        rebuildAlbums();
        adapter.notifyDataSetChanged();
    }

    private void openItemAt(int idx) {
        if (idx < 0 || idx >= visibleMedia.size()) return;
        File f = visibleMedia.get(idx);
        if (isVideoFile(f)) {
            Intent vlc = new Intent(Intent.ACTION_VIEW);
            vlc.setDataAndType(Uri.fromFile(f), "video/*");
            vlc.setPackage("org.videolan.vlc");
            vlc.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(vlc);
                return;
            } catch (Throwable ignored) {
                Intent any = new Intent(Intent.ACTION_VIEW);
                any.setDataAndType(Uri.fromFile(f), "video/*");
                startActivity(any);
                return;
            }
        }
        currentIndex = idx;
        currentRotationDeg = 0;
        currentFlippedH = false;
        currentFilterIdx = 0;
        currentBitmap = decodeSampledBitmap(f, 1280);
        viewerTitleTv.setText(f.getName() + "  (" + (idx + 1) + "/" + visibleMedia.size() + ")");
        viewerOverlay.setVisibility(View.VISIBLE);
        refreshRenderedEditorPreview();
    }

    private void closeViewer() {
        slideshowRunning = false;
        ui.removeCallbacks(slideshowTick);
        viewerOverlay.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (viewerOverlay != null && viewerOverlay.getVisibility() == View.VISIBLE) {
            closeViewer();
            return;
        }
        super.onBackPressed();
    }

    private Bitmap buildTransformedBitmap() {
        if (currentBitmap == null) return null;
        Matrix m = new Matrix();
        if (currentFlippedH) m.postScale(-1f, 1f);
        if (currentRotationDeg != 0) m.postRotate(currentRotationDeg);
        Bitmap base = Bitmap.createBitmap(
                currentBitmap, 0, 0, currentBitmap.getWidth(), currentBitmap.getHeight(), m, true);
        if (currentFilterIdx == 0) return base;

        Bitmap filtered = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(filtered);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(resolveColorMatrix(currentFilterIdx)));
        c.drawBitmap(base, 0, 0, p);
        return filtered;
    }

    private static ColorMatrix resolveColorMatrix(int idx) {
        ColorMatrix cm = new ColorMatrix();
        if (idx == 1) { // Vivid Pop
            cm.setSaturation(1.45f);
        } else if (idx == 2) { // Warm Golden
            cm.set(new float[]{
                    1.12f, 0, 0, 0, 12,
                    0, 1.04f, 0, 0, 6,
                    0, 0, 0.88f, 0, -6,
                    0, 0, 0, 1, 0});
        } else if (idx == 3) { // Noir B&W
            cm.setSaturation(0f);
        } else if (idx == 4) { // Vintage Sepia
            ColorMatrix bw = new ColorMatrix();
            bw.setSaturation(0f);
            ColorMatrix tint = new ColorMatrix();
            tint.setScale(1.12f, 0.98f, 0.78f, 1.0f);
            bw.postConcat(tint);
            return bw;
        } else if (idx == 5) { // Cool Cinema
            cm.set(new float[]{
                    0.92f, 0, 0, 0, -4,
                    0, 1.02f, 0, 0, 2,
                    0, 0, 1.16f, 0, 12,
                    0, 0, 0, 1, 0});
        } else if (idx == 6) { // E-Ink Crisp
            cm.setSaturation(0f);
            ColorMatrix contrast = new ColorMatrix(new float[]{
                    1.35f, 0, 0, 0, -35,
                    0, 1.35f, 0, 0, -35,
                    0, 0, 1.35f, 0, -35,
                    0, 0, 0, 1, 0});
            cm.postConcat(contrast);
        }
        return cm;
    }

    private void refreshRenderedEditorPreview() {
        Bitmap bmp = buildTransformedBitmap();
        if (bmp != null) {
            fullImageView.setPhotoBitmap(bmp);
        }
    }

    private void saveEditedPhotoCopy() {
        Bitmap bmp = buildTransformedBitmap();
        if (bmp == null) return;
        try {
            File outDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Arc7Edited");
            if (!outDir.exists()) outDir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dst = new File(outDir, "ARC7_EDIT_" + stamp + ".jpg");
            FileOutputStream fos = new FileOutputStream(dst);
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            fos.close();
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)));
            Toast.makeText(this, "Saved edited photo -> " + dst.getName(), Toast.LENGTH_LONG).show();
            scanMediaAsync();
        } catch (Throwable t) {
            Toast.makeText(this, "Save failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyCurrentAsWallpaper() {
        Bitmap bmp = buildTransformedBitmap();
        if (bmp == null) return;
        try {
            WallpaperManager.getInstance(this).setBitmap(bmp);
            Toast.makeText(this, "Set photo as your Kobo Arc 7 Home Wallpaper!", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Wallpaper error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteCurrent() {
        if (currentIndex < 0 || currentIndex >= visibleMedia.size()) return;
        final File target = visibleMedia.get(currentIndex);
        new AlertDialog.Builder(this)
                .setTitle("Delete Photo?")
                .setMessage("Delete '" + target.getName() + "' permanently?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        target.delete();
                        closeViewer();
                        scanMediaAsync();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static Bitmap decodeSampledBitmap(File f, int maxEdge) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sample = 1;
            int largest = Math.max(opts.outWidth, opts.outHeight);
            while (largest / sample > maxEdge) sample *= 2;
            BitmapFactory.Options load = new BitmapFactory.Options();
            load.inSampleSize = sample;
            load.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), load);
        } catch (Throwable t) {
            return null;
        }
    }

    private class PhotoGridAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleMedia.size(); }
        @Override public Object getItem(int pos) { return visibleMedia.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            final File f = visibleMedia.get(position);
            FrameLayout cell = (FrameLayout) convertView;
            final ImageView iv;
            final TextView badge;
            if (cell == null) {
                cell = new FrameLayout(Arc7PhotosActivity.this);
                cell.setBackgroundColor(0xFF16202E);

                iv = new ImageView(Arc7PhotosActivity.this);
                iv.setId(2001);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));

                badge = new TextView(Arc7PhotosActivity.this);
                badge.setId(2002);
                badge.setTextColor(Color.WHITE);
                badge.setBackgroundColor(0xAA000000);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.RIGHT);
                cell.addView(badge, blp);
            } else {
                iv = (ImageView) cell.findViewById(2001);
                badge = (TextView) cell.findViewById(2002);
            }

            final String key = f.getAbsolutePath();
            iv.setTag(key);
            if (isVideoFile(f)) {
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                iv.setImageResource(R.drawable.ic_vlc);
                badge.setText("▶ VIDEO");
                badge.setVisibility(View.VISIBLE);
            } else {
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                badge.setVisibility(View.GONE);
                Bitmap cached = thumbCache.get(key);
                if (cached != null) {
                    iv.setImageBitmap(cached);
                } else {
                    iv.setImageResource(R.drawable.ic_photos);
                    thumbPool.execute(new Runnable() {
                        @Override
                        public void run() {
                            final Bitmap b = decodeSampledBitmap(f, 260);
                            if (b != null) {
                                thumbCache.put(key, b);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (key.equals(iv.getTag())) iv.setImageBitmap(b);
                                    }
                                });
                            }
                        }
                    });
                }
            }
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openItemAt(position);
                }
            });
            return cell;
        }
    }

    /** Multi-touch Pinch-to-Zoom + Drag + Double-Tap Reset ImageView for Android 4.2+. */
    private static class ZoomableImageView extends ImageView {
        private final Matrix drawMatrix = new Matrix();
        private float scale = 1.0f;
        private float lastTouchX;
        private float lastTouchY;
        private Bitmap bmp;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;

        ZoomableImageView(Context ctx) {
            super(ctx);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    float next = Math.max(1.0f, Math.min(5.0f, scale * factor));
                    float ratio = next / scale;
                    scale = next;
                    drawMatrix.postScale(ratio, ratio, detector.getFocusX(), detector.getFocusY());
                    setImageMatrix(drawMatrix);
                    return true;
                }
            });
            tapDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.15f) {
                        fitCenter();
                    } else {
                        scale = 2.4f;
                        drawMatrix.postScale(2.4f, 2.4f, e.getX(), e.getY());
                        setImageMatrix(drawMatrix);
                    }
                    return true;
                }
            });
        }

        void setPhotoBitmap(Bitmap b) {
            bmp = b;
            setImageBitmap(b);
            post(new Runnable() {
                @Override
                public void run() {
                    fitCenter();
                }
            });
        }

        void fitCenter() {
            if (bmp == null || getWidth() <= 0 || getHeight() <= 0) return;
            scale = 1.0f;
            drawMatrix.reset();
            float sx = (float) getWidth() / (float) bmp.getWidth();
            float sy = (float) getHeight() / (float) bmp.getHeight();
            float s = Math.min(sx, sy);
            float dx = (getWidth() - bmp.getWidth() * s) * 0.5f;
            float dy = (getHeight() - bmp.getHeight() * s) * 0.5f;
            drawMatrix.postScale(s, s);
            drawMatrix.postTranslate(dx, dy);
            setImageMatrix(drawMatrix);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            tapDetector.onTouchEvent(ev);
            scaleDetector.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
            } else if (action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress() && scale > 1.02f) {
                float dx = ev.getX() - lastTouchX;
                float dy = ev.getY() - lastTouchY;
                drawMatrix.postTranslate(dx, dy);
                setImageMatrix(drawMatrix);
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
            }
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        thumbPool.shutdownNow();
        super.onDestroy();
    }
}
