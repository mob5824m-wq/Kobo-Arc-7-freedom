package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Standalone Offline EPUB / TXT / HTML E-Book Reader (`Arc7 E-Reader`) built natively
 * for the Kobo Arc 7 (`1024x600`, Android 4.2.2 `API 17`).
 */
public class Arc7EbookReaderActivity extends Activity {

    private static final String PREFS = "arc7_ereader";
    private static final String KEY_LAST_BOOK = "last_book_path";
    private static final String KEY_FONT_ZOOM = "font_zoom_pct";
    private static final String KEY_THEME     = "reader_theme"; // 0=Paper, 1=Sepia, 2=Night

    private TextView lblBookTitle;
    private TextView lblChapterPos;
    private WebView readerWebView;

    private String currentBookPath = "";
    private String currentBookTitle = "Kobo Arc 7 Freedom Owner's Handbook";
    private final List<String> chapterTitles = new ArrayList<String>();
    private final List<File> chapterFiles = new ArrayList<File>();
    private int currentChapter = 0;
    private int fontZoom = 115;
    private int themeIndex = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        topBar.addView(navButton("Open Book", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) { openBookPicker(); }
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
        lblBookTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lblBookTitle.setSingleLine(true);
        lblBookTitle.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(lblBookTitle, titleLp);

        lblChapterPos = new TextView(this);
        lblChapterPos.setTextColor(Color.parseColor("#94A3B8"));
        lblChapterPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lblChapterPos.setPadding(0, 0, dp(8), 0);
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

        readerWebView = new WebView(this);
        WebSettings ws = readerWebView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(fontZoom);
        root.addView(readerWebView, new LinearLayout.LayoutParams(
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

    @SuppressWarnings("deprecation")
    private Button navButton(String label, String colorHex, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setAllCaps(false);
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
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
                new String[]{".epub", ".txt", ".md", ".html", ".htm", ".xhtml"},
                "Open Offline Book (.epub / .txt / .html)",
                new FilePickerDialog.OnFilePicked() {
                    @Override
                    public void onPicked(File file) {
                        loadBookFile(file);
                    }
                });
    }

    private void loadBookFile(File file) {
        try {
            chapterTitles.clear();
            chapterFiles.clear();
            currentBookPath = file.getAbsolutePath();
            currentBookTitle = file.getName();

            String lower = file.getName().toLowerCase(Locale.US);
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

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putString(KEY_LAST_BOOK, currentBookPath).commit();
            int savedChap = sp.getInt("chap_" + currentBookPath.hashCode(), 0);
            if (savedChap < 0 || savedChap >= chapterFiles.size()) savedChap = 0;
            currentChapter = savedChap;
            renderCurrentChapter();
        } catch (Throwable t) {
            Toast.makeText(this, "Failed opening book: " + t.getMessage(), Toast.LENGTH_LONG).show();
            loadBuiltInStarterBook();
        }
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
        currentBookPath = "";
        currentBookTitle = "Kobo Arc 7 Freedom — Offline Reader & Tablet Guide";
        chapterTitles.clear();
        chapterFiles.clear();
        try {
            File c1 = new File(getCacheDir(), "handbook_ch1.html");
            writeTextFile(c1, wrapStyledHtml("Welcome to Arc7 E-Reader",
                    "<h1>Welcome to Arc7 E-Reader</h1>" +
                    "<p>Your Kobo Arc 7's original factory reading app was permanently locked to dead 2013 Kobo servers — " +
                    "even refusing to open DRM-free books until setup succeeded.</p>" +
                    "<p><b>Arc7 E-Reader</b> replaces it cleanly with a zero-account, 100% offline reader built for your " +
                    "1024&times;600 IPS screen:</p>" +
                    "<ul>" +
                    "<li><b>Open any DRM-free <code>.epub</code>, <code>.txt</code>, <code>.md</code>, or <code>.html</code> book</b> from your MicroSD card (<code>/storage/sdcard1</code>) or <code>Download</code> folder using <b>Open Book</b> above.</li>" +
                    "<li><b>Download tens of thousands of free classic <code>.epub</code> books right over Wi-Fi</b> from <i>Project Gutenberg</i> or <i>Standard Ebooks</i> inside <b>Arc7 Browser (TLS 1.3)</b>!</li>" +
                    "<li>Tap <b>Theme</b> anytime to switch between <b>Warm Sepia</b>, <b>Crisp Paper</b>, and <b>AMOLED Night</b> reading modes, or use <b>A- / A+</b> to set your preferred font size.</li>" +
                    "</ul>"));
            chapterTitles.add("1. Welcome & Reading DRM-Free EPUB Books");
            chapterFiles.add(c1);

            File c2 = new File(getCacheDir(), "handbook_ch2.html");
            writeTextFile(c2, wrapStyledHtml("Getting the Most Out of Your Upgraded Kobo Arc 7",
                    "<h1>Getting the Most Out of Your Upgraded Kobo Arc 7</h1>" +
                    "<ol>" +
                    "<li><b>Root + Full Factory Backup First:</b> Save your factory OS &amp; NVRAM to your external MicroSD card on Tabs 1 &amp; 3.</li>" +
                    "<li><b>Fix Clock, 2026 CAs &amp; System-Wide TLS 1.3 Proxy:</b> One tap on Tab 2 brings modern HTTPS websites AND legacy Android 4.2 apps online together.</li>" +
                    "<li><b>Install Pre-Bundled F-Droid Classic:</b> Pre-packaged inside this app — zero internet required to install the store itself, and built-in Conscrypt TLS 1.3 fetches thousands of Android 4.2 open-source apps over Wi-Fi!</li>" +
                    "<li><b>Expand Storage + Boost Speed:</b> On Tab 5, bind-mount heavy folders straight onto your MicroSD card, block resource-heavy ad networks in <code>/etc/hosts</code>, tune <code>1 GB RAM</code>, and turn on the system-wide Warm Night Filter for comfortable bedtime reading!</li>" +
                    "</ol>"));
            chapterTitles.add("2. Power-User Tips for Your Kobo Arc 7");
            chapterFiles.add(c2);
        } catch (Throwable ignored) {}
        currentChapter = 0;
        renderCurrentChapter();
    }

    private void renderCurrentChapter() {
        if (chapterFiles.isEmpty()) return;
        File f = chapterFiles.get(currentChapter);
        lblBookTitle.setText(currentBookTitle);
        lblChapterPos.setText((currentChapter + 1) + " / " + chapterFiles.size());
        readerWebView.getSettings().setTextZoom(fontZoom);

        try {
            String rawHtml = readTextFile(f);
            String baseUrl = f.getParentFile().toURI().toString();
            readerWebView.loadDataWithBaseURL(baseUrl, injectThemeCss(rawHtml), "text/html", "UTF-8", null);
            if (!currentBookPath.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("chap_" + currentBookPath.hashCode(), currentChapter).commit();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Error rendering chapter: " + t.getMessage(), Toast.LENGTH_SHORT).show();
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
        if (next < 0 || next >= chapterFiles.size()) {
            Toast.makeText(this, delta < 0 ? "Already at first chapter." : "End of book reached.", Toast.LENGTH_SHORT).show();
            return;
        }
        currentChapter = next;
        renderCurrentChapter();
    }

    private void showTableOfContents() {
        String[] items = chapterTitles.toArray(new String[chapterTitles.size()]);
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Table of Contents (" + chapterTitles.size() + " sections)")
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
