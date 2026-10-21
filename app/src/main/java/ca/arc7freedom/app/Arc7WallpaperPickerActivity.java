package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen **Arc7 Wallpapers** Studio (`12 Built-In HD Themes + Custom Photo Picker`).
 * Handles BOTH App Drawer launches AND Android's system-wide `android.intent.action.SET_WALLPAPER` chooser!
 */
public class Arc7WallpaperPickerActivity extends Activity {

    private int selectedIndex = 0;
    private ImageView previewImageView;
    private TextView selectedTitleTv;
    private TextView selectedSubtitleTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E141B);

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF14212D);
        header.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView title = new TextView(this);
        title.setText("Arc7 Wallpapers  •  12 HD Art Themes + Custom Photos");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button customPhotoBtn = makeButton("Pick Custom Photo (SD / Internal)", 0xFF6A1B9A);
        customPhotoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCustomPhotoPicker();
            }
        });
        header.addView(customPhotoBtn);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Top live HD preview card
        LinearLayout topPreview = new LinearLayout(this);
        topPreview.setOrientation(LinearLayout.HORIZONTAL);
        topPreview.setGravity(Gravity.CENTER_VERTICAL);
        topPreview.setPadding(dp(16), dp(12), dp(16), dp(12));
        topPreview.setBackgroundColor(0xFF16222E);

        previewImageView = new ImageView(this);
        previewImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        topPreview.addView(previewImageView, new LinearLayout.LayoutParams(dp(260), dp(162)));

        LinearLayout metaCol = new LinearLayout(this);
        metaCol.setOrientation(LinearLayout.VERTICAL);
        metaCol.setPadding(dp(16), 0, 0, 0);

        selectedTitleTv = new TextView(this);
        selectedTitleTv.setTextColor(Color.WHITE);
        selectedTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        selectedTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        metaCol.addView(selectedTitleTv);

        selectedSubtitleTv = new TextView(this);
        selectedSubtitleTv.setTextColor(0xFF80DEEA);
        selectedSubtitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        selectedSubtitleTv.setPadding(0, dp(6), 0, dp(14));
        metaCol.addView(selectedSubtitleTv);

        Button applyBtn = makeButton("APPLY THIS HD WALLPAPER NOW", 0xFF2E7D32);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Arc7WallpaperCatalog.applyBuiltInTheme(Arc7WallpaperPickerActivity.this, selectedIndex);
                    Toast.makeText(Arc7WallpaperPickerActivity.this,
                            "Applied HD Wallpaper: " + Arc7WallpaperCatalog.TITLES[selectedIndex],
                            Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(Arc7WallpaperPickerActivity.this,
                            "Wallpaper error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        metaCol.addView(applyBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        topPreview.addView(metaCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(topPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 2-column scrollable gallery of all 12 built-in themes
        ScrollView sv = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(14), dp(12), dp(14), dp(22));

        LinearLayout currentRow = null;
        for (int i = 0; i < Arc7WallpaperCatalog.TITLES.length; i++) {
            if (i % 2 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(10);
                grid.addView(currentRow, rlp);
            }
            final int themeIdx = i;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(0xFF1A2836);
            gd.setCornerRadius(dp(8));
            gd.setStroke(dp(1), 0xFF2D4359);
            tile.setBackgroundDrawable(gd);

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap tb = Arc7WallpaperCatalog.renderTheme(themeIdx, 320, 200);
            thumb.setImageBitmap(tb);
            tile.addView(thumb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(104)));

            TextView tLabel = new TextView(this);
            tLabel.setText(Arc7WallpaperCatalog.TITLES[themeIdx]);
            tLabel.setTextColor(Color.WHITE);
            tLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tLabel.setTypeface(Typeface.DEFAULT_BOLD);
            tLabel.setPadding(0, dp(6), 0, dp(2));
            tile.addView(tLabel);

            TextView tSub = new TextView(this);
            tSub.setText(Arc7WallpaperCatalog.SUBTITLES[themeIdx]);
            tSub.setTextColor(0xFFB0BEC5);
            tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tile.addView(tSub);

            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTheme(themeIdx);
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = (i % 2 == 1) ? dp(6) : 0;
            clp.rightMargin = (i % 2 == 0) ? dp(6) : 0;
            currentRow.addView(tile, clp);
        }

        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        selectTheme(0);
    }

    private void selectTheme(int idx) {
        selectedIndex = idx;
        previewImageView.setImageBitmap(Arc7WallpaperCatalog.renderTheme(idx, 640, 400));
        selectedTitleTv.setText(Arc7WallpaperCatalog.TITLES[idx]);
        selectedSubtitleTv.setText(Arc7WallpaperCatalog.SUBTITLES[idx]);
    }

    private void showCustomPhotoPicker() {
        final List<File> photos = new ArrayList<File>();
        String[] roots = new String[]{
                "/storage/sdcard1",
                "/storage/sdcard0",
                "/sdcard",
                "/storage/emulated/0"
        };
        for (String r : roots) {
            scanPhotos(new File(r), 3, photos);
            if (photos.size() >= 60) break;
        }
        if (photos.isEmpty()) {
            Toast.makeText(this,
                    "No .jpg / .png / .webp images found yet on MicroSD or Internal Storage (drop any photo into Pictures/ or Wallpapers/ via Wi-Fi Drive!)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[photos.size()];
        for (int i = 0; i < photos.size(); i++) {
            File f = photos.get(i);
            names[i] = f.getName() + "  (" + f.getParent() + ")";
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Choose Custom Photo Wallpaper (" + photos.size() + " found)")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            File chosen = photos.get(which);
                            Arc7WallpaperCatalog.applyPhotoFile(Arc7WallpaperPickerActivity.this, chosen);
                            Toast.makeText(Arc7WallpaperPickerActivity.this,
                                    "Set custom wallpaper: " + chosen.getName(),
                                    Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Toast.makeText(Arc7WallpaperPickerActivity.this,
                                    "Failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void scanPhotos(File dir, int depth, List<File> out) {
        if (dir == null || depth < 0 || !dir.isDirectory() || out.size() >= 80) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!"Android".equals(f.getName())) {
                    scanPhotos(f, depth - 1, out);
                }
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if ((n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
                        && f.length() > 4096L) {
                    out.add(f);
                }
            }
        }
    }

    private Button makeButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(7));
        gd.setStroke(dp(1), 0x44FFFFFF);
        b.setBackgroundDrawable(gd);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
