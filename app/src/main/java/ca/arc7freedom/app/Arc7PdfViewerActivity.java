package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Standalone Offline PDF Viewer & Multi-Page Camera Document Scanner (`Arc7 PDF & Scanner`, `v2.2.0`):
 *   - Opens any `.pdf` offline on Android 4.2 (`API 17` — no Google PDF viewer needed!)
 *   - Displays extracted page text + embedded `/DCTDecode` JPEG figures/scans with Zoom, Night Mode,
 *     Full-Document Text Search, and Page Jump
 *   - Built-in **Multi-Page Camera & Photo Document Scanner -> ISO `%PDF-1.4` Exporter** with automatic
 *     adaptive paper-whitening & high-contrast ink enhancement!
 */
public class Arc7PdfViewerActivity extends Activity {

    private static final int REQ_SCAN_CAMERA = 4101;
    public static final String EXTRA_AUTO_SCAN = "auto_start_scan";

    private Arc7PdfEngine.ParsedPdf currentDoc = null;
    private int pageIdx = 0;
    private float fontSp = 15.5f;
    private boolean nightMode = true;

    private final List<File> stagedScanImages = new ArrayList<File>();
    private File pendingCameraFile = null;

    private TextView pageBadgeTv;
    private TextView pageBodyTv;
    private ImageView pageImageView;
    private ScrollView pageScroll;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Arc7Skin.Palette pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button openBtn = Arc7Skin.actionButton(this, "Open PDF", 0xFF0284C7);
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptOpenPdf(); }
        });
        addSpaced(actions, openBtn);

        Button scanBtn = Arc7Skin.actionButton(this, "Scan -> PDF", 0xFFDC2626);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScannerSheet(); }
        });
        addSpaced(actions, scanBtn);

        Button searchBtn = Arc7Skin.actionButton(this, "Find", 0xFF334155);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptFindInPdf(); }
        });
        actions.addView(searchBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_pdf,
                "PDF Reader & Scanner",
                "Offline PDF Reader • Full-Text Search • Multi-Page Camera Document Scanner",
                actions));

        // Page Navigation Bar
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(pal.surface);
        nav.setPadding(dp(12), dp(6), dp(12), dp(6));

        addSpaced(nav, smallBtn("◀ Prev", 0xFF334155, new View.OnClickListener() {
            @Override public void onClick(View v) { stepPage(-1); }
        }));
        addSpaced(nav, smallBtn("Next ▶", 0xFF334155, new View.OnClickListener() {
            @Override public void onClick(View v) { stepPage(1); }
        }));

        pageBadgeTv = new TextView(this);
        pageBadgeTv.setText("No PDF open — tap 'Open PDF' or 'Scan -> PDF'");
        pageBadgeTv.setTextColor(pal.textPrimary);
        pageBadgeTv.setTypeface(Typeface.DEFAULT_BOLD);
        pageBadgeTv.setSingleLine(true);
        pageBadgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        nav.addView(pageBadgeTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addSpaced(nav, smallBtn("A-", 0xFF475569, new View.OnClickListener() {
            @Override public void onClick(View v) { fontSp = Math.max(11f, fontSp - 2f); renderPage(); }
        }));
        addSpaced(nav, smallBtn("A+", 0xFF475569, new View.OnClickListener() {
            @Override public void onClick(View v) { fontSp = Math.min(28f, fontSp + 2f); renderPage(); }
        }));
        nav.addView(smallBtn("Paper / Night", 0xFF7C3AED, new View.OnClickListener() {
            @Override public void onClick(View v) { nightMode = !nightMode; renderPage(); }
        }));
        root.addView(nav);

        pageScroll = new ScrollView(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(24));

        pageImageView = new ImageView(this);
        pageImageView.setAdjustViewBounds(true);
        pageImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageImageView.setVisibility(View.GONE);
        sheet.addView(pageImageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageBodyTv = new TextView(this);
        pageBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        pageBodyTv.setLineSpacing(dp(4), 1.15f);
        pageBodyTv.setText("Open any offline .pdf document from Books/, Downloads/, or MicroSD — or tap 'Scan -> PDF' above to capture paper notes, receipts, or textbook pages with your camera and export a crisp multi-page PDF!");
        sheet.addView(pageBodyTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageScroll.addView(sheet);
        root.addView(pageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        renderPage();

        Intent it = getIntent();
        if (it != null) {
            if (it.getData() != null && it.getData().getPath() != null) {
                File f = new File(it.getData().getPath());
                if (f.isFile()) loadPdf(f);
            }
            if (it.getBooleanExtra(EXTRA_AUTO_SCAN, false)) {
                showScannerSheet();
            }
        }
    }

    private Button smallBtn(String t, int col, View.OnClickListener l) {
        Button b = Arc7Skin.actionButton(this, t, col);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        b.setOnClickListener(l);
        return b;
    }

    private void addSpaced(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        row.addView(v, lp);
    }

    private void promptOpenPdf() {
        String sd = TabletOptimizerManager.findMicroSdRoot();
        File start = sd != null ? new File(sd) : Environment.getExternalStorageDirectory();
        FilePickerDialog.show(this, start, new String[]{".pdf"},
                "Select PDF Document (.pdf)", new FilePickerDialog.OnFilePicked() {
                    @Override
                    public void onPicked(File file) {
                        loadPdf(file);
                    }
                });
    }

    public void loadPdf(File f) {
        try {
            currentDoc = Arc7PdfEngine.parsePdfFile(f);
            pageIdx = 0;
            renderPage();
        } catch (Throwable t) {
            Toast.makeText(this, "Could not parse PDF: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stepPage(int d) {
        if (currentDoc == null || currentDoc.pages.isEmpty()) return;
        pageIdx = Math.max(0, Math.min(currentDoc.pages.size() - 1, pageIdx + d));
        pageScroll.scrollTo(0, 0);
        renderPage();
    }

    private void renderPage() {
        pageScroll.setBackgroundColor(nightMode ? 0xFF0F172A : 0xFFFBF9F5);
        pageBodyTv.setTextColor(nightMode ? 0xFFE2E8F0 : 0xFF1E293B);
        pageBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        if (currentDoc == null || currentDoc.pages.isEmpty()) return;

        Arc7PdfEngine.PdfPage p = currentDoc.pages.get(pageIdx);
        pageBadgeTv.setText(currentDoc.title + "  —  Page " + p.pageNumber + " / " + currentDoc.pages.size());

        if (p.embeddedJpeg != null && p.embeddedJpeg.length > 32) {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(p.embeddedJpeg, 0, p.embeddedJpeg.length, b);
            int sample = 1;
            while (b.outWidth / sample > 1400 || b.outHeight / sample > 1800) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(p.embeddedJpeg, 0, p.embeddedJpeg.length, o);
            if (bmp != null) {
                pageImageView.setImageBitmap(bmp);
                pageImageView.setVisibility(View.VISIBLE);
            } else {
                pageImageView.setVisibility(View.GONE);
            }
        } else {
            pageImageView.setImageBitmap(null);
            pageImageView.setVisibility(View.GONE);
        }

        pageBodyTv.setText(p.text == null || p.text.isEmpty()
                ? (p.embeddedJpeg != null ? "[Scanned Page Image Above]" : "[Blank Page]")
                : p.text);
    }

    private void promptFindInPdf() {
        if (currentDoc == null) {
            Toast.makeText(this, "Open a PDF first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint("Search text across all pages...");
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Search PDF Pages")
                .setView(q)
                .setPositiveButton("Find Next", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String needle = q.getText().toString().trim().toLowerCase(Locale.US);
                        if (needle.isEmpty()) return;
                        for (int off = 0; off < currentDoc.pages.size(); off++) {
                            int idx = (pageIdx + 1 + off) % currentDoc.pages.size();
                            String txt = currentDoc.pages.get(idx).text;
                            if (txt != null && txt.toLowerCase(Locale.US).contains(needle)) {
                                pageIdx = idx;
                                renderPage();
                                Toast.makeText(Arc7PdfViewerActivity.this,
                                        "Found on Page " + (idx + 1), Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        Toast.makeText(Arc7PdfViewerActivity.this, "Not found in document.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showScannerSheet() {
        String[] opts = new String[]{
                "1. Capture Page with Camera (" + stagedScanImages.size() + " staged)",
                "2. Add Existing Photo / Image from Storage",
                "3. Export Enhanced Multi-Page PDF (" + stagedScanImages.size() + " pages)",
                "4. Clear Staged Scan Pages"
        };
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Document Scanner -> PDF Studio")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            try {
                                File dir = new File(getExternalCacheDir() != null
                                        ? getExternalCacheDir() : getCacheDir(), "scans");
                                dir.mkdirs();
                                pendingCameraFile = new File(dir, "scan_" + System.currentTimeMillis() + ".jpg");
                                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                                cam.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(pendingCameraFile));
                                startActivityForResult(cam, REQ_SCAN_CAMERA);
                            } catch (Throwable t) {
                                startActivity(new Intent(Arc7PdfViewerActivity.this, Arc7CameraActivity.class));
                            }
                        } else if (which == 1) {
                            FilePickerDialog.show(Arc7PdfViewerActivity.this,
                                    Environment.getExternalStorageDirectory(),
                                    new String[]{".jpg", ".jpeg", ".png"},
                                    "Pick Photo Page to Add to Scan Batch",
                                    new FilePickerDialog.OnFilePicked() {
                                        @Override
                                        public void onPicked(File file) {
                                            stagedScanImages.add(file);
                                            Toast.makeText(Arc7PdfViewerActivity.this,
                                                    "Added page #" + stagedScanImages.size() + " — tap Scan -> PDF to export!",
                                                    Toast.LENGTH_SHORT).show();
                                            showScannerSheet();
                                        }
                                    });
                        } else if (which == 2) {
                            exportStagedScanPdf();
                        } else {
                            stagedScanImages.clear();
                            Toast.makeText(Arc7PdfViewerActivity.this, "Cleared staged scan pages.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN_CAMERA && pendingCameraFile != null && pendingCameraFile.isFile() && pendingCameraFile.length() > 0) {
            stagedScanImages.add(pendingCameraFile);
            Toast.makeText(this, "Captured scan page #" + stagedScanImages.size() + "!", Toast.LENGTH_SHORT).show();
            showScannerSheet();
        }
    }

    private void exportStagedScanPdf() {
        if (stagedScanImages.isEmpty()) {
            Toast.makeText(this, "Capture or add at least 1 page image first!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File outDir = new File(Environment.getExternalStorageDirectory(), "Documents/Scans");
            outDir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File outPdf = new File(outDir, "Scan_" + ts + ".pdf");
            Arc7PdfEngine.writeJpegFilesToPdf(stagedScanImages, true, outPdf);
            stagedScanImages.clear();
            Toast.makeText(this, "Saved PDF: " + outPdf.getAbsolutePath(), Toast.LENGTH_LONG).show();
            loadPdf(outPdf);
        } catch (Throwable t) {
            Toast.makeText(this, "PDF export error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
