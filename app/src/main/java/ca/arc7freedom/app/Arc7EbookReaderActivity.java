package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Standalone Offline EPUB / TXT / HTML E-Book Reader (`Arc7 E-Reader`) — upgraded in v1.4.0 with:
 *   - **Upgrade #7:** Built-in **70,000+ Free Classic E-Book Storefront** (`Project Gutenberg` /
 *     `Gutendex` over native `arc7tls` `TLS 1.3` — search any title/author OR browse Top Classics,
 *     1-tap download `.epub` straight to `Books/` and read immediately!).
 *   - **Upgrade #8:** Full **Offline Color Comic / Manga (`.cbz`) Reader Mode** on the `1024x600`
 *     IPS display (natural page sorting, memory-safe downsampled Bitmap decoding on `1 GB RAM`,
 *     full-width page scroll, and per-comic page bookmark resume!).
 */
public class Arc7EbookReaderActivity extends Activity {

    private static final String PREFS = "arc7_ereader";
    private static final String KEY_LAST_BOOK = "last_book_path";
    private static final String KEY_FONT_ZOOM = "font_zoom_pct";
    private static final String KEY_THEME     = "reader_theme"; // 0=Paper, 1=Sepia, 2=Night

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell rootShell;
    private NetworkCertManager certManager;

    private TextView lblBookTitle;
    private TextView lblChapterPos;
    private WebView readerWebView;
    private ScrollView comicScrollView;
    private ImageView comicPageImage;

    private String currentBookPath = "";
    private String currentBookTitle = "Kobo Arc 7 Freedom Owner's Handbook";
    private boolean isComicMode = false;
    private final List<String> chapterTitles = new ArrayList<String>();
    private final List<File> chapterFiles = new ArrayList<File>();
    private final List<String> comicEntryNames = new ArrayList<String>();
    private int currentChapter = 0;
    private int fontZoom = 115;
    private int themeIndex = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootShell = new RootShell(this);
        certManager = new NetworkCertManager(this, rootShell);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        fontZoom = sp.getInt(KEY_FONT_ZOOM, 115);
        themeIndex = sp.getInt(KEY_THEME, 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(Color.parseColor("#1E293B"));

        topBar.addView(navButton("Open Book/.CBZ", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) { openBookPicker(); }
        }));
        /* Upgrade #7: 1-Tap 70,000+ Free Classic E-Book Storefront */
        topBar.addView(navButton("Free Store", "#16A34A", new View.OnClickListener() {
            @Override public void onClick(View v) { openFreeBookStoreDialog(); }
        }));
        topBar.addView(navButton("TOC", "#475569", new View.OnClickListener() {
            @Override public void onClick(View v) { showTableOfContents(); }
        }));
        topBar.addView(navButton("< Prev", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { jumpChapter(-1); }
        }));
        topBar.addView(navButton("Next >", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { jumpChapter(1); }
        }));

        lblBookTitle = new TextView(this);
        lblBookTitle.setTextColor(Color.WHITE);
        lblBookTitle.setTypeface(Typeface.DEFAULT_BOLD);
        lblBookTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lblBookTitle.setSingleLine(true);
        lblBookTitle.setPadding(dp(6), 0, dp(6), 0);
        topBar.addView(lblBookTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lblChapterPos = new TextView(this);
        lblChapterPos.setTextColor(Color.parseColor("#94A3B8"));
        lblChapterPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lblChapterPos.setPadding(0, 0, dp(6), 0);
        topBar.addView(lblChapterPos);

        topBar.addView(navButton("A-", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { adjustFont(-15); }
        }));
        topBar.addView(navButton("A+", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { adjustFont(+15); }
        }));
        topBar.addView(navButton("Theme", "#7C3AED", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTheme(); }
        }));
        topBar.addView(navButton("Close", "#64748B", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        root.addView(topBar);

        FrameLayout contentFrame = new FrameLayout(this);

        readerWebView = new WebView(this);
        WebSettings ws = readerWebView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(fontZoom);
        contentFrame.addView(readerWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* Upgrade #8: Color Comic / Manga `.cbz` Full-Width Page Viewer */
        comicScrollView = new ScrollView(this);
        comicScrollView.setBackgroundColor(Color.BLACK);
        comicScrollView.setVisibility(View.GONE);
        comicPageImage = new ImageView(this);
        comicPageImage.setAdjustViewBounds(true);
        comicPageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        comicPageImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpChapter(1);
            }
        });
        comicScrollView.addView(comicPageImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentFrame.addView(comicScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        String intentPath = null;
        Intent it = getIntent();
        if (it != null && it.getData() != null && "file".equalsIgnoreCase(it.getData().getScheme())) {
            intentPath = it.getData().getPath();
        }
        String lastBook = sp.getString(KEY_LAST_BOOK, "");
        if (intentPath != null && new File(intentPath).exists()) {
            loadBookFile(new File(intentPath));
        } else if (!lastBook.isEmpty() && new File(lastBook).exists()) {
            loadBookFile(new File(lastBook));
        } else {
            loadBuiltInStarterBook();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null && "file".equalsIgnoreCase(intent.getData().getScheme())) {
            String p = intent.getData().getPath();
            if (p != null && new File(p).exists()) {
                loadBookFile(new File(p));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Button navButton(String label, String colorHex, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(5), dp(8), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(Color.parseColor(colorHex));
        b.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void openBookPicker() {
        String sd = TabletOptimizerManager.findMicroSdRoot();
        File startDir = sd != null ? new File(sd) : new File("/sdcard");
        FilePickerDialog.show(this, startDir,
                new String[]{".epub", ".cbz", ".txt", ".md", ".html", ".htm", ".xhtml"},
                "Open Offline Book (.epub / .txt) or Color Comic / Manga (.cbz)",
                new FilePickerDialog.OnFilePicked() {
                    @Override
                    public void onPicked(File file) {
                        loadBookFile(file);
                    }
                });
    }

    /* ========================================================================
     * Upgrade #7: Built-In 70,000+ Free Classic E-Book Storefront (Gutendex TLS 1.3)
     * ======================================================================== */

    private void openFreeBookStoreDialog() {
        final EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Leave blank for Top Popular Classics, or type e.g. Sherlock Holmes / Jules Verne");

        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Free Classic E-Book Storefront (70,000+ Books — Zero Account!)")
                .setMessage("Search Project Gutenberg's entire public-domain EPUB catalog directly over TLS 1.3 and download straight into your Kobo Arc 7 `Books/` library:")
                .setView(searchInput)
                .setPositiveButton("Search / Browse Top Books", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        queryGutendexCatalog(searchInput.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void queryGutendexCatalog(final String query) {
        Toast.makeText(this, "Searching 70,000+ free EPUB books via TLS 1.3...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = query.isEmpty()
                            ? "https://gutendex.com/books/?mime_type=application%2Fepub%2Bzip"
                            : ("https://gutendex.com/books/?mime_type=application%2Fepub%2Bzip&search="
                               + URLEncoder.encode(query, "UTF-8"));
                    File bundle = certManager.ensureCaBundleFile();
                    File outJson = new File(getCacheDir(), "gutendex_results.json");
                    RootShell.ExecResult r = rootShell.execCommandArray(new String[]{
                        rootShell.getTlsEngineBinary().getAbsolutePath(),
                        "fetch", bundle.getAbsolutePath(), apiUrl, outJson.getAbsolutePath()
                    }, null);
                    if (r.exitCode != 0 || !outJson.exists()) {
                        throw new RuntimeException("Check Wi-Fi connection (" + r.stdout.trim() + ")");
                    }
                    String raw = readTextFile(outJson);
                    JSONObject obj = new JSONObject(raw);
                    JSONArray arr = obj.getJSONArray("results");

                    final List<String> displayItems = new ArrayList<String>();
                    final List<String> epubUrls = new ArrayList<String>();
                    final List<String> bookTitles = new ArrayList<String>();

                    for (int i = 0; i < Math.min(25, arr.length()); i++) {
                        JSONObject b = arr.getJSONObject(i);
                        String title = b.optString("title", "Untitled");
                        String author = "";
                        JSONArray authors = b.optJSONArray("authors");
                        if (authors != null && authors.length() > 0) {
                            author = authors.getJSONObject(0).optString("name", "");
                        }
                        JSONObject formats = b.optJSONObject("formats");
                        if (formats == null) continue;
                        String epubUrl = formats.optString("application/epub+zip", "");
                        if (epubUrl.isEmpty()) continue;

                        displayItems.add(title + (author.isEmpty() ? "" : (" — " + author)));
                        epubUrls.add(epubUrl);
                        bookTitles.add(title);
                    }

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (displayItems.isEmpty()) {
                                Toast.makeText(Arc7EbookReaderActivity.this, "No matching EPUBs found.", Toast.LENGTH_LONG).show();
                                return;
                            }
                            new AlertDialog.Builder(Arc7EbookReaderActivity.this, AlertDialog.THEME_HOLO_DARK)
                                    .setTitle("Tap Any Book to Download & Read Immediately")
                                    .setItems(displayItems.toArray(new String[displayItems.size()]),
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface d, int idx) {
                                                    downloadAndOpenFreeBook(bookTitles.get(idx), epubUrls.get(idx));
                                                }
                                            })
                                    .setNegativeButton("Close", null)
                                    .show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7EbookReaderActivity.this,
                                    "Store query failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void downloadAndOpenFreeBook(final String title, final String epubUrl) {
        Toast.makeText(this, "Downloading \"" + title + "\" over TLS 1.3...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String sd = TabletOptimizerManager.findMicroSdRoot();
                    File booksDir = new File(sd != null ? sd : Environment.getExternalStorageDirectory().getAbsolutePath(), "Books");
                    booksDir.mkdirs();
                    String safeName = title.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
                    if (safeName.length() > 64) safeName = safeName.substring(0, 64).trim();
                    if (safeName.isEmpty()) safeName = "Gutenberg_Book";
                    final File outEpub = new File(booksDir, safeName + ".epub");

                    File bundle = certManager.ensureCaBundleFile();
                    RootShell.ExecResult r = rootShell.execCommandArray(new String[]{
                        rootShell.getTlsEngineBinary().getAbsolutePath(),
                        "fetch", bundle.getAbsolutePath(), epubUrl, outEpub.getAbsolutePath()
                    }, null);
                    if (r.exitCode == 0 && outEpub.exists() && outEpub.length() > 512) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(Arc7EbookReaderActivity.this,
                                        "Saved to " + outEpub.getAbsolutePath() + " — opening now!",
                                        Toast.LENGTH_SHORT).show();
                                loadBookFile(outEpub);
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /* ========================================================================
     * Book & Color `.CBZ` Comic Loader
     * ======================================================================== */

    private void loadBookFile(File file) {
        try {
            chapterTitles.clear();
            chapterFiles.clear();
            comicEntryNames.clear();
            currentBookPath = file.getAbsolutePath();
            currentBookTitle = file.getName();

            String lower = file.getName().toLowerCase(Locale.US);
            if (lower.endsWith(".cbz")) {
                /* Upgrade #8: Color Comic / Manga `.cbz` Mode */
                isComicMode = true;
                parseCbzComicArchive(file);
                if (comicEntryNames.isEmpty()) {
                    Toast.makeText(this, "No image pages found inside " + file.getName(), Toast.LENGTH_LONG).show();
                    isComicMode = false;
                    loadBuiltInStarterBook();
                    return;
                }
            } else {
                isComicMode = false;
                if (lower.endsWith(".epub")) {
                    parseEpubArchive(file);
                } else {
                    File singleOut = new File(getBookCacheDir(file), "single.html");
                    String raw = readTextFile(file);
                    if (!lower.endsWith(".html") && !lower.endsWith(".htm") && !lower.endsWith(".xhtml")) {
                        raw = "<pre style='white-space:pre-wrap;font-family:Georgia,serif;line-height:1.65;'>"
                                + escapeHtml(raw) + "</pre>";
                    }
                    writeTextFile(singleOut, wrapStyledHtml(currentBookTitle, raw));
                    chapterTitles.add(file.getName());
                    chapterFiles.add(singleOut);
                }
                if (chapterFiles.isEmpty()) {
                    Toast.makeText(this, "No readable chapters found in " + file.getName(), Toast.LENGTH_LONG).show();
                    loadBuiltInStarterBook();
                    return;
                }
            }

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putString(KEY_LAST_BOOK, currentBookPath).commit();
            int total = totalSections();
            int savedChap = sp.getInt("chap_" + currentBookPath.hashCode(), 0);
            if (savedChap < 0 || savedChap >= total) savedChap = 0;
            currentChapter = savedChap;
            renderCurrentChapter();
        } catch (Throwable t) {
            Toast.makeText(this, "Failed opening file: " + t.getMessage(), Toast.LENGTH_LONG).show();
            loadBuiltInStarterBook();
        }
    }

    private int totalSections() {
        return isComicMode ? comicEntryNames.size() : chapterFiles.size();
    }

    private void parseCbzComicArchive(File cbzFile) throws Exception {
        ZipFile zf = new ZipFile(cbzFile);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String n = ze.getName();
                String low = n.toLowerCase(Locale.US);
                if (low.contains("__macosx")) continue;
                if (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                        || low.endsWith(".webp") || low.endsWith(".gif")) {
                    comicEntryNames.add(n);
                }
            }
        } finally {
            zf.close();
        }
        /* Natural alphanumeric page sort so `page_2.jpg` always precedes `page_10.jpg`! */
        Collections.sort(comicEntryNames, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return naturalCompare(a, b);
            }
        });
        for (int i = 0; i < comicEntryNames.size(); i++) {
            chapterTitles.add("Page " + (i + 1) + "  (" + comicEntryNames.get(i) + ")");
        }
    }

    private static int naturalCompare(String a, String b) {
        int ia = 0, ib = 0, la = a.length(), lb = b.length();
        while (ia < la && ib < lb) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int sa = ia, sb = ib;
                while (ia < la && Character.isDigit(a.charAt(ia))) ia++;
                while (ib < lb && Character.isDigit(b.charAt(ib))) ib++;
                long na = Long.parseLong(a.substring(sa, Math.min(ia, sa + 16)));
                long nb = Long.parseLong(b.substring(sb, Math.min(ib, sb + 16)));
                if (na != nb) return na < nb ? -1 : 1;
            } else {
                char ua = Character.toLowerCase(ca), ub = Character.toLowerCase(cb);
                if (ua != ub) return ua - ub;
                ia++; ib++;
            }
        }
        return la - lb;
    }

    private File getBookCacheDir(File src) {
        File d = new File(getCacheDir(), "book_" + Math.abs(src.getAbsolutePath().hashCode()));
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void parseEpubArchive(File epubFile) throws Exception {
        File extractDir = getBookCacheDir(epubFile);
        ZipFile zf = new ZipFile(epubFile);
        try {
            byte[] buf = new byte[32768];
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory()) continue;
                File out = new File(extractDir, ze.getName());
                if (!out.getCanonicalPath().startsWith(extractDir.getCanonicalPath() + File.separator)) {
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                InputStream is = zf.getInputStream(ze);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    is.close();
                    fos.close();
                }
            }
        } finally {
            zf.close();
        }

        File containerXml = new File(extractDir, "META-INF/container.xml");
        String opfRelPath = null;
        if (containerXml.exists()) {
            XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
            xpp.setInput(new StringReader(readTextFile(containerXml)));
            int ev = xpp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "rootfile".equalsIgnoreCase(xpp.getName())) {
                    opfRelPath = xpp.getAttributeValue(null, "full-path");
                    if (opfRelPath != null) break;
                }
                ev = xpp.next();
            }
        }
        if (opfRelPath == null) return;

        File opfFile = new File(extractDir, opfRelPath);
        if (!opfFile.exists()) return;
        File opfDir = opfFile.getParentFile();

        Map<String, String> idToHref = new HashMap<String, String>();
        List<String> spineIds = new ArrayList<String>();
        XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
        xpp.setInput(new StringReader(readTextFile(opfFile)));
        int ev = xpp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = xpp.getName();
                if (tag != null && (tag.equalsIgnoreCase("dc:title") || tag.equalsIgnoreCase("title"))) {
                    String t = xpp.nextText();
                    if (t != null && !t.trim().isEmpty()) currentBookTitle = t.trim();
                } else if ("item".equalsIgnoreCase(tag)) {
                    String id = xpp.getAttributeValue(null, "id");
                    String href = xpp.getAttributeValue(null, "href");
                    if (id != null && href != null) idToHref.put(id, Uri.decode(href));
                } else if ("itemref".equalsIgnoreCase(tag)) {
                    String idref = xpp.getAttributeValue(null, "idref");
                    if (idref != null) spineIds.add(idref);
                }
            }
            ev = xpp.next();
        }

        int idx = 1;
        for (String idref : spineIds) {
            String href = idToHref.get(idref);
            if (href == null) continue;
            File chapFile = new File(opfDir, href);
            if (chapFile.exists()) {
                chapterFiles.add(chapFile);
                chapterTitles.add("Chapter " + idx + " (" + chapFile.getName() + ")");
                idx++;
            }
        }
    }

    private void loadBuiltInStarterBook() {
        isComicMode = false;
        currentBookPath = "";
        currentBookTitle = "Kobo Arc 7 Freedom v1.4 — E-Reader, Free Store & Comic Guide";
        chapterTitles.clear();
        chapterFiles.clear();
        try {
            File c1 = new File(getCacheDir(), "handbook_ch1.html");
            writeTextFile(c1, wrapStyledHtml("Welcome to Arc7 E-Reader + Comic Viewer",
                    "<h1>Welcome to Arc7 E-Reader v1.4</h1>" +
                    "<p>Your Kobo Arc 7's factory reader is permanently bricked against dead 2013 servers — " +
                    "and even black-and-white E-Ink readers cannot display color comics or magazines!</p>" +
                    "<ul>" +
                    "<li><b>NEW — 70,000+ Free Classic E-Books Built Right In:</b> Tap the green <b>Free Store</b> button above to search or browse <i>Project Gutenberg</i> over TLS 1.3 and download any <code>.epub</code> straight into <code>Books/</code>!</li>" +
                    "<li><b>NEW — Full Color Comic &amp; Manga <code>.CBZ</code> Reader:</b> Tap <b>Open Book/.CBZ</b> to read full-color comics, graphic novels, and manga crisp on your <code>1024&times;600</code> IPS screen (tap the page or <b>Next &gt;</b> to turn pages!).</li>" +
                    "<li><b>Warm Sepia / Crisp Paper / Night Themes:</b> Tap <b>Theme</b> or <b>A- / A+</b> anytime.</li>" +
                    "</ul>"));
            chapterTitles.add("1. Welcome, Free 70,000-Book Store & .CBZ Comics");
            chapterFiles.add(c1);
        } catch (Throwable ignored) {}
        currentChapter = 0;
        renderCurrentChapter();
    }

    private void renderCurrentChapter() {
        int total = totalSections();
        if (total == 0) return;
        lblBookTitle.setText(currentBookTitle);
        lblChapterPos.setText((currentChapter + 1) + " / " + total);

        if (!currentBookPath.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("chap_" + currentBookPath.hashCode(), currentChapter).commit();
        }

        if (isComicMode) {
            readerWebView.setVisibility(View.GONE);
            comicScrollView.setVisibility(View.VISIBLE);
            comicScrollView.scrollTo(0, 0);
            renderComicPageBitmap();
            return;
        }

        comicScrollView.setVisibility(View.GONE);
        readerWebView.setVisibility(View.VISIBLE);
        readerWebView.getSettings().setTextZoom(fontZoom);

        File f = chapterFiles.get(currentChapter);
        try {
            String rawHtml = readTextFile(f);
            String baseUrl = f.getParentFile().toURI().toString();
            readerWebView.loadDataWithBaseURL(baseUrl, injectThemeCss(rawHtml), "text/html", "UTF-8", null);
        } catch (Throwable t) {
            Toast.makeText(this, "Error rendering chapter: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void renderComicPageBitmap() {
        ZipFile zf = null;
        try {
            zf = new ZipFile(new File(currentBookPath));
            ZipEntry entry = zf.getEntry(comicEntryNames.get(currentChapter));
            if (entry == null) return;
            InputStream is = zf.getInputStream(entry);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            byte[] data = baos.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 2400) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            comicPageImage.setImageBitmap(bmp);
        } catch (Throwable t) {
            Toast.makeText(this, "Error decoding comic page: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
    }

    private String injectThemeCss(String html) {
        String bg = themeIndex == 2 ? "#111827" : (themeIndex == 1 ? "#FBF3E4" : "#FFFFFF");
        String fg = themeIndex == 2 ? "#E5E7EB" : (themeIndex == 1 ? "#2C221E" : "#111827");
        String link = themeIndex == 2 ? "#60A5FA" : "#0369A1";
        String style = "<style>html,body{background:" + bg + " !important;color:" + fg +
                " !important;font-family:Georgia,serif;line-height:1.68;padding:18px 26px;max-width:760px;margin:0 auto;}" +
                "a{color:" + link + " !important;}img{max-width:100% !important;height:auto !important;}</style>";
        int headClose = html.toLowerCase(Locale.US).indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + style + html.substring(headClose);
        }
        return style + html;
    }

    private String wrapStyledHtml(String title, String bodyHtml) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>" + escapeHtml(title) +
               "</title></head><body>" + bodyHtml + "</body></html>";
    }

    private void jumpChapter(int delta) {
        int next = currentChapter + delta;
        if (next < 0 || next >= totalSections()) {
            Toast.makeText(this, delta < 0 ? "Already at beginning." : "End reached!", Toast.LENGTH_SHORT).show();
            return;
        }
        currentChapter = next;
        renderCurrentChapter();
    }

    private void showTableOfContents() {
        String[] items = chapterTitles.toArray(new String[chapterTitles.size()]);
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Sections / Pages (" + chapterTitles.size() + ")")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        currentChapter = which;
                        renderCurrentChapter();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void adjustFont(int delta) {
        fontZoom = Math.max(70, Math.min(220, fontZoom + delta));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FONT_ZOOM, fontZoom).commit();
        readerWebView.getSettings().setTextZoom(fontZoom);
    }

    private void cycleTheme() {
        themeIndex = (themeIndex + 1) % 3;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_THEME, themeIndex).commit();
        renderCurrentChapter();
    }

    private static String readTextFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            char[] buf = new char[16384];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            br.close();
        }
        return sb.toString();
    }

    private static void writeTextFile(File f, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
