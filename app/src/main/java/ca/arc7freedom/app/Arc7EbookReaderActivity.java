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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * Standalone Offline & Multi-Store E-Book / Comic / PDF Reader (`Arc7 E-Reader`, `v2.2.0`):
 *
 *   - **4 Built-In TLS 1.3 Book Sources (`Book Store` button):**
 *       1. **`vk.com` (`VK Books & Documents`)** — Searches `vk.com` `docs.search` API + public
 *          `site:vk.com` book documents (`EPUB`, `FB2`, `FB2.ZIP`, `PDF`, `CBZ`, `TXT`), plus
 *          **1-Tap Sign In to `vk.com`** & **Live `m.vk.com` Book Browser Interceptor**!
 *       2. **`Project Gutenberg`** (70,000+ free public-domain EPUBs via `gutendex.com`)
 *       3. **`Open Library & Internet Archive`** (`openlibrary.org` + `archive.org`)
 *       4. **`Standard Ebooks`** (curated typography editions)
 *   - **Full Multi-Format Offline Rendering:**
 *       `.epub`, `.fb2`, `.fb2.zip`, `.pdf` (inline or `Arc7 PDF & Scanner`), `.cbz` color comics,
 *       `.txt`, `.md`, and `.html`!
 *   - **Bookmarks, Reading Progress %, 4 Reading Paper Themes** (*Paper*, *Kobo Sepia*, *Slate Dusk*,
 *     *AMOLED True Black*), **& Instant Word Dictionary / Wikipedia Lookup (`Define`)**!
 */
public class Arc7EbookReaderActivity extends Activity {

    private static final String PREFS = "arc7_ereader";
    private static final String KEY_LAST_BOOK = "last_book_path";
    private static final String KEY_FONT_ZOOM = "font_zoom_pct";
    private static final String KEY_THEME     = "reader_theme"; // 0=Paper, 1=Sepia, 2=Slate, 3=AMOLED
    private static final String KEY_SOURCE    = "last_book_source";
    private static final String KEY_VK_FMT    = "last_vk_format_filter";
    private static final String KEY_BOOKMARKS = "bookmarks_list";

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

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        // Customizable Arc7 Top Bar + Scrollable E-Reader Toolbar
        LinearLayout headerRight = new LinearLayout(this);
        headerRight.setOrientation(LinearLayout.HORIZONTAL);
        headerRight.addView(navButton("VK Books", "#2563EB", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(KEY_SOURCE, Arc7BookSources.SOURCE_VK).commit();
                openFreeBookStoreDialog();
            }
        }));
        headerRight.addView(navButton("Book Store (4)", "#16A34A", new View.OnClickListener() {
            @Override public void onClick(View v) { openFreeBookStoreDialog(); }
        }));

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_ereader,
                "Arc7 E-Reader & VK Books",
                "vk.com + Gutenberg + Open Library + Standard Ebooks • EPUB / FB2 / PDF / CBZ",
                headerRight));

        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolScroll.setBackgroundColor(pal.surface);
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));

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
        lblBookTitle.setTextColor(pal.textPrimary);
        lblBookTitle.setTypeface(Typeface.DEFAULT_BOLD);
        lblBookTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lblBookTitle.setSingleLine(true);
        lblBookTitle.setMaxWidth(dp(230));
        lblBookTitle.setPadding(dp(6), 0, dp(6), 0);
        topBar.addView(lblBookTitle);

        lblChapterPos = new TextView(this);
        lblChapterPos.setTextColor(pal.textMuted);
        lblChapterPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lblChapterPos.setPadding(0, 0, dp(8), 0);
        topBar.addView(lblChapterPos);

        topBar.addView(navButton("Bookmark ★", "#D97706", new View.OnClickListener() {
            @Override public void onClick(View v) { showBookmarksDialog(); }
        }));
        topBar.addView(navButton("Define / Wiki", "#0D9488", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Arc7EbookReaderActivity.this, Arc7DictionaryWikiActivity.class));
            }
        }));
        topBar.addView(navButton("A-", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { adjustFont(-15); }
        }));
        topBar.addView(navButton("A+", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { adjustFont(+15); }
        }));
        topBar.addView(navButton("Theme", "#7C3AED", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTheme(); }
        }));
        toolScroll.addView(topBar);
        root.addView(toolScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        Arc7UiAnimator.attachActivityAnimations(this);

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
        File startDir = sd != null ? new File(sd) : Environment.getExternalStorageDirectory();
        FilePickerDialog.show(this, startDir,
                new String[]{".epub", ".fb2", ".zip", ".pdf", ".cbz", ".txt", ".md", ".html", ".htm", ".xhtml"},
                "Open Book (.epub / .fb2 / .pdf / .txt) or Comic (.cbz)",
                new FilePickerDialog.OnFilePicked() {
                    @Override
                    public void onPicked(File file) {
                        loadBookFile(file);
                    }
                });
    }

    /* ==================== Multi-Source Book Storefront (`vk.com` + 3 Free Catalogs) ==================== */

    private void openFreeBookStoreDialog() {
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final int[] selectedSource = new int[]{sp.getInt(KEY_SOURCE, Arc7BookSources.SOURCE_VK)};
        final int[] vkFmt = new int[]{sp.getInt(KEY_VK_FMT, 0)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(8));

        final Button sourceBtn = Arc7Skin.actionButton(
                this, "Source: " + Arc7BookSources.SOURCE_NAMES[selectedSource[0]] + " ▾", 0xFF1D4ED8);
        box.addView(sourceBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Button fmtBtn = Arc7Skin.actionButton(
                this, "VK Format: " + Arc7BookSources.VK_FORMAT_FILTERS[vkFmt[0]] + " ▾", 0xFF334155);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(6);
        box.addView(fmtBtn, flp);

        LinearLayout vkToolsRow = new LinearLayout(this);
        vkToolsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams vtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vtlp.topMargin = dp(6);
        vkToolsRow.setLayoutParams(vtlp);

        final Button vkLoginBtn = Arc7Skin.actionButton(
                this, Arc7BookSources.getVkToken(this).isEmpty() ? "Sign in to vk.com" : "✓ VK Connected",
                0xFF2563EB);
        LinearLayout.LayoutParams bHalf = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bHalf.rightMargin = dp(6);
        vkToolsRow.addView(vkLoginBtn, bHalf);

        Button vkWebBtn = Arc7Skin.actionButton(this, "Browse m.vk.com", 0xFF0F766E);
        vkToolsRow.addView(vkWebBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(vkToolsRow);

        final EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Title, author, or direct https://vk.com/doc... link");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        box.addView(searchInput, elp);

        final AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Book Store — vk.com + Gutenberg + Open Library + Standard Ebooks")
                .setView(box)
                .setPositiveButton("Search Books", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        queryUnifiedCatalog(selectedSource[0], searchInput.getText().toString().trim(), vkFmt[0]);
                    }
                })
                .setNegativeButton("Close", null)
                .create();

        sourceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(Arc7EbookReaderActivity.this, AlertDialog.THEME_HOLO_DARK)
                        .setTitle("Choose Book Source")
                        .setSingleChoiceItems(Arc7BookSources.SOURCE_NAMES, selectedSource[0],
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface sd, int which) {
                                        selectedSource[0] = which;
                                        sp.edit().putInt(KEY_SOURCE, which).commit();
                                        sourceBtn.setText("Source: " + Arc7BookSources.SOURCE_NAMES[which] + " ▾");
                                        sd.dismiss();
                                    }
                                })
                        .show();
            }
        });

        fmtBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(Arc7EbookReaderActivity.this, AlertDialog.THEME_HOLO_DARK)
                        .setTitle("VK Book Format Filter")
                        .setSingleChoiceItems(Arc7BookSources.VK_FORMAT_FILTERS, vkFmt[0],
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface fd, int which) {
                                        vkFmt[0] = which;
                                        sp.edit().putInt(KEY_VK_FMT, which).commit();
                                        fmtBtn.setText("VK Format: " + Arc7BookSources.VK_FORMAT_FILTERS[which] + " ▾");
                                        fd.dismiss();
                                    }
                                })
                        .show();
            }
        });

        vkLoginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7BookSources.showVkAccountDialog(Arc7EbookReaderActivity.this, certManager,
                        new Arc7BookSources.TokenSavedCallback() {
                            @Override
                            public void onTokenChanged(String newToken) {
                                vkLoginBtn.setText(newToken.isEmpty() ? "Sign in to vk.com" : "✓ VK Connected");
                            }
                        });
            }
        });

        vkWebBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String q = searchInput.getText().toString().trim();
                String target = "https://m.vk.com/docs";
                if (!q.isEmpty()) {
                    try {
                        target = "https://m.vk.com/search?c[section]=statuses&q="
                                + URLEncoder.encode(q + " epub fb2", "UTF-8");
                    } catch (Throwable ignored) {}
                }
                dlg.dismiss();
                Arc7BookSources.openVkInteractiveWebSheet(
                        Arc7EbookReaderActivity.this, certManager, target,
                        new Arc7BookSources.TokenSavedCallback() {
                            @Override public void onTokenChanged(String newToken) {}
                        },
                        new Arc7BookSources.DirectBookInterceptCallback() {
                            @Override
                            public void onBookUrlIntercepted(String titleHint, String url) {
                                Arc7BookSources.BookHit hit = new Arc7BookSources.BookHit();
                                hit.title = titleHint;
                                hit.downloadUrl = url;
                                hit.sourceBadge = "vk.com";
                                downloadAndOpenBookHit(hit);
                            }
                        });
            }
        });

        dlg.show();
    }

    private void queryUnifiedCatalog(final int sourceId, final String query, final int vkFormatFilter) {
        Toast.makeText(this, "Searching " + Arc7BookSources.SOURCE_NAMES[sourceId] + " via TLS 1.3...",
                Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Arc7BookSources.BookHit> hits = Arc7BookSources.searchBooks(
                            Arc7EbookReaderActivity.this, rootShell, certManager,
                            sourceId, query, vkFormatFilter);
                    final String[] lines = new String[hits.size()];
                    for (int i = 0; i < hits.size(); i++) lines[i] = hits.get(i).displayLine();

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (hits.isEmpty()) {
                                Toast.makeText(Arc7EbookReaderActivity.this,
                                        "No matching books found — try another query or 'Browse m.vk.com'.",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            new AlertDialog.Builder(Arc7EbookReaderActivity.this, AlertDialog.THEME_HOLO_DARK)
                                    .setTitle("Tap Any Book to Download & Read Offline (" + hits.size() + ")")
                                    .setItems(lines, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int idx) {
                                            downloadAndOpenBookHit(hits.get(idx));
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

    private void downloadAndOpenBookHit(final Arc7BookSources.BookHit hit) {
        Toast.makeText(this, "Downloading \"" + hit.title + "\" over TLS 1.3...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String sd = TabletOptimizerManager.findMicroSdRoot();
                    File booksDir = new File(sd != null ? sd : Environment.getExternalStorageDirectory().getAbsolutePath(), "Books");
                    final File downloaded = Arc7BookSources.downloadBookHitToLibrary(
                            Arc7EbookReaderActivity.this, rootShell, certManager, hit, booksDir);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7EbookReaderActivity.this,
                                    "Saved to " + downloaded.getAbsolutePath() + " — opening now!",
                                    Toast.LENGTH_SHORT).show();
                            loadBookFile(downloaded);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7EbookReaderActivity.this,
                                    "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /* ==================== Bookmarks Manager ==================== */

    private void showBookmarksDialog() {
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String raw = sp.getString(KEY_BOOKMARKS, "");
        final List<String> entries = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        labels.add("★ Bookmark Current Position (" + currentBookTitle + " — Sec " + (currentChapter + 1) + ")");
        entries.add("__ADD__");
        if (!raw.isEmpty()) {
            String[] lines = raw.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                String[] parts = lines[i].split("\\|", 3);
                if (parts.length == 3) {
                    entries.add(lines[i]);
                    labels.add("📖 " + parts[2] + " (Sec " + (Integer.parseInt(parts[1]) + 1) + ")");
                }
            }
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Reading Bookmarks")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            String rec = currentBookPath + "|" + currentChapter + "|" + currentBookTitle.replace("|", " ");
                            String next = rec + (raw.isEmpty() ? "" : ("\n" + raw));
                            sp.edit().putString(KEY_BOOKMARKS, next).commit();
                            Toast.makeText(Arc7EbookReaderActivity.this, "Bookmark saved!", Toast.LENGTH_SHORT).show();
                        } else {
                            String[] parts = entries.get(which).split("\\|", 3);
                            File f = new File(parts[0]);
                            int chap = Integer.parseInt(parts[1]);
                            if (f.exists()) loadBookFile(f);
                            if (chap >= 0 && chap < totalSections()) {
                                currentChapter = chap;
                                renderCurrentChapter();
                            }
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /* ==================== Multi-Format Book Loader (`.epub`, `.fb2`, `.pdf`, `.cbz`, `.txt`) ==================== */

    private int totalSections() {
        return isComicMode ? comicEntryNames.size() : chapterFiles.size();
    }

    public void loadBookFile(File file) {
        try {
            chapterTitles.clear();
            chapterFiles.clear();
            comicEntryNames.clear();
            isComicMode = false;
            currentBookPath = file.getAbsolutePath();
            currentBookTitle = file.getName();

            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".pdf")) {
                parsePdfIntoReader(file);
            } else if (name.endsWith(".fb2") || name.endsWith(".fb2.zip")) {
                parseFb2IntoReader(file);
            } else if (name.endsWith(".cbz")) {
                isComicMode = true;
                parseCbzComicArchive(file);
            } else if (name.endsWith(".epub") || name.endsWith(".zip")) {
                String realExt = Arc7BookSources.sniffRealBookExtension(file, "epub");
                if ("fb2.zip".equals(realExt)) parseFb2IntoReader(file);
                else if ("pdf".equals(realExt)) parsePdfIntoReader(file);
                else parseEpubArchive(file);
            } else if (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")) {
                chapterTitles.add(file.getName());
                chapterFiles.add(file);
            } else {
                String plain = readTextFile(file);
                File htmlOut = new File(getBookCacheDir(file), "plain.html");
                writeTextFile(htmlOut, wrapStyledHtml(file.getName(),
                        "<pre style='white-space:pre-wrap;font-family:Georgia,serif;'>" +
                        escapeHtml(plain) + "</pre>"));
                chapterTitles.add(file.getName());
                chapterFiles.add(htmlOut);
            }

            if (totalSections() == 0) {
                Toast.makeText(this, "No readable chapters/pages found in " + file.getName(), Toast.LENGTH_LONG).show();
                loadBuiltInStarterBook();
                return;
            }

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putString(KEY_LAST_BOOK, currentBookPath).commit();
            currentChapter = Math.min(sp.getInt("chap_" + currentBookPath.hashCode(), 0), totalSections() - 1);
            renderCurrentChapter();
        } catch (Throwable t) {
            Toast.makeText(this, "Failed to open book: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void parseFb2IntoReader(File file) throws Exception {
        Arc7BookSources.Fb2ParsedBook fb2 = Arc7BookSources.parseFb2File(file);
        currentBookTitle = fb2.title + (fb2.author.isEmpty() ? "" : (" — " + fb2.author));
        File cacheDir = getBookCacheDir(file);
        for (int i = 0; i < fb2.chapterHtmls.size(); i++) {
            File chFile = new File(cacheDir, "fb2_ch_" + i + ".html");
            writeTextFile(chFile, wrapStyledHtml(fb2.chapterTitles.get(i), fb2.chapterHtmls.get(i)));
            chapterTitles.add(fb2.chapterTitles.get(i));
            chapterFiles.add(chFile);
        }
    }

    private void parsePdfIntoReader(File file) throws Exception {
        Arc7PdfEngine.ParsedPdf pdf = Arc7PdfEngine.parsePdfFile(file);
        currentBookTitle = pdf.title;
        File cacheDir = getBookCacheDir(file);
        for (int i = 0; i < pdf.pages.size(); i++) {
            Arc7PdfEngine.PdfPage p = pdf.pages.get(i);
            StringBuilder html = new StringBuilder();
            html.append("<h3>Page ").append(p.pageNumber).append("</h3>");
            if (p.embeddedJpeg != null && p.embeddedJpeg.length > 32) {
                File imgF = new File(cacheDir, "pdf_p_" + p.pageNumber + ".jpg");
                FileOutputStream fos = new FileOutputStream(imgF);
                try { fos.write(p.embeddedJpeg); } finally { fos.close(); }
                html.append("<p><img src='").append(imgF.getName()).append("' style='max-width:100%;'/></p>");
            }
            if (p.text != null && !p.text.isEmpty()) {
                html.append("<pre style='white-space:pre-wrap;font-family:Georgia,serif;'>")
                    .append(escapeHtml(p.text)).append("</pre>");
            }
            File ch = new File(cacheDir, "pdf_p_" + p.pageNumber + ".html");
            writeTextFile(ch, wrapStyledHtml("Page " + p.pageNumber, html.toString()));
            chapterTitles.add("Page " + p.pageNumber);
            chapterFiles.add(ch);
        }
    }

    private void parseCbzComicArchive(File cbzFile) throws Exception {
        ZipFile zf = new ZipFile(cbzFile);
        try {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory()) continue;
                String lower = ze.getName().toLowerCase(Locale.US);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                    comicEntryNames.add(ze.getName());
                }
            }
        } finally {
            zf.close();
        }
        Collections.sort(comicEntryNames, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareToIgnoreCase(b);
            }
        });
        for (int i = 0; i < comicEntryNames.size(); i++) {
            chapterTitles.add("Page " + (i + 1) + " (" + new File(comicEntryNames.get(i)).getName() + ")");
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
        isComicMode = false;
        currentBookPath = "";
        currentBookTitle = "Kobo Arc 7 Freedom v2.2 — E-Reader & VK Books Guide";
        chapterTitles.clear();
        chapterFiles.clear();
        try {
            File c1 = new File(getCacheDir(), "handbook_ch1.html");
            writeTextFile(c1, wrapStyledHtml("Welcome to Arc7 E-Reader + VK Books + PDF & Comic Viewer",
                    "<h1>Welcome to Arc7 E-Reader v2.2</h1>" +
                    "<p>Read offline in <b>EPUB, FB2, FB2.ZIP, PDF, CBZ &amp; TXT</b> with <b>4 live TLS 1.3 book sources</b>:</p>" +
                    "<ul>" +
                    "<li><b>VK Books (<code>vk.com</code>):</b> Tap <b>VK Books</b> above to search millions of <code>.epub</code>, <code>.fb2</code>, and <code>.pdf</code> books on <code>vk.com</code> (works immediately via public VK search, or tap <b>Sign in to vk.com</b> / <b>Browse m.vk.com</b> for full account access!).</li>" +
                    "<li><b>Project Gutenberg (70,000+), Open Library &amp; Standard Ebooks:</b> Tap <b>Book Store (4)</b> to switch sources anytime.</li>" +
                    "<li><b>Bookmarks, Instant Dictionary/Wikipedia &amp; 4 Reading Themes:</b> Use <b>Bookmark ★</b>, <b>Define / Wiki</b>, <b>Theme</b>, or <b>A- / A+</b> above.</li>" +
                    "</ul>"));
            chapterTitles.add("1. VK Books (vk.com), 3 Free Libraries & Multi-Format Reader");
            chapterFiles.add(c1);
        } catch (Throwable ignored) {}
        currentChapter = 0;
        renderCurrentChapter();
    }

    private void renderCurrentChapter() {
        int total = totalSections();
        if (total == 0) return;
        int pct = Math.round(((currentChapter + 1) * 100f) / Math.max(1, total));
        lblBookTitle.setText(currentBookTitle);
        lblChapterPos.setText((currentChapter + 1) + "/" + total + " (" + pct + "%)");

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
        String bg = themeIndex == 3 ? "#000000"
                : (themeIndex == 2 ? "#111827" : (themeIndex == 1 ? "#FBF3E4" : "#FFFFFF"));
        String fg = themeIndex == 3 ? "#D1D5DB"
                : (themeIndex == 2 ? "#E5E7EB" : (themeIndex == 1 ? "#2C221E" : "#111827"));
        String link = (themeIndex >= 2) ? "#60A5FA" : "#0369A1";
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
        themeIndex = (themeIndex + 1) % 4;
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
