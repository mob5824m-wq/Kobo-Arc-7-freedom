package ca.arc7freedom.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * True Landscape Dual-Pane Split-Screen Multitasking (`Arc7 Split View`, `v2.2.0`):
 *
 *   Run ANY TWO of these side-by-side simultaneously on the Kobo Arc 7 `1280x800` IPS panel:
 *     0 = TLS 1.3 Web Browser (routed through local TLS 1.3 HTTPS gateway!)
 *     1 = E-Book Reader (`.fb2`, `.fb2.zip`, `.epub`, `.txt`, `.pdf`)
 *     2 = Notes Scratchpad (auto-saved side-by-side research notes!)
 *     3 = Dictionary & Wikipedia Lookup
 *     4 = Scientific Quick Calculator
 *
 *   Includes 1-tap `30% / 50% / 70%` width presets + `⇄ Swap Panes`!
 */
public class Arc7SplitViewActivity extends Activity {

    private static final String PREFS = "arc7_split_view";
    private static final String[] PANE_NAMES = new String[]{
            "🌐 TLS 1.3 Web", "📚 E-Book / PDF", "📝 Research Notes",
            "📖 Dict & Wiki", "🧮 Calculator"
    };

    private RootShell shell;
    private NetworkCertManager certMgr;
    private Arc7Skin.Palette pal;

    private int leftMode = 1;  // E-Book / PDF by default
    private int rightMode = 2; // Notes by default
    private float leftRatio = 0.50f;

    private FrameLayout leftHost;
    private FrameLayout rightHost;
    private LinearLayout splitRow;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell = new RootShell(this);
        certMgr = new NetworkCertManager(this, shell);
        pal = Arc7Skin.current(this);

        // Ensure local TLS 1.3 gateway is warm for dual-pane web browsing
        new Thread(new Runnable() {
            @Override public void run() { try { certMgr.startBrowserGateway(); } catch (Throwable ignored) {} }
        }).start();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        leftMode = sp.getInt("left_mode", 1);
        rightMode = sp.getInt("right_mode", 2);
        leftRatio = sp.getFloat("left_ratio", 0.50f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        // Compact multitasking control bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(pal.surface);
        topBar.setPadding(dp(8), dp(5), dp(8), dp(5));

        Button backBtn = Arc7Skin.actionButton(this, "← Home", 0xFF334155);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        topBar.addView(backBtn);

        topBar.addView(makePaneSpinner(true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String[] ratios = new String[]{"35|65", "50|50", "65|35"};
        final float[] rVals = new float[]{0.35f, 0.50f, 0.65f};
        for (int i = 0; i < 3; i++) {
            final float rv = rVals[i];
            Button rb = Arc7Skin.actionButton(this, ratios[i], 0xFF0284C7);
            rb.setPadding(dp(8), dp(5), dp(8), dp(5));
            rb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    leftRatio = rv;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("left_ratio", leftRatio).commit();
                    applyWeights();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(4);
            topBar.addView(rb, lp);
        }

        Button swapBtn = Arc7Skin.actionButton(this, "⇄ Swap", 0xFF7C3AED);
        swapBtn.setPadding(dp(9), dp(5), dp(9), dp(5));
        swapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int tmp = leftMode;
                leftMode = rightMode;
                rightMode = tmp;
                saveModes();
                recreate();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(5);
        slp.rightMargin = dp(5);
        topBar.addView(swapBtn, slp);

        topBar.addView(makePaneSpinner(false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(topBar);

        splitRow = new LinearLayout(this);
        splitRow.setOrientation(LinearLayout.HORIZONTAL);

        leftHost = new FrameLayout(this);
        rightHost = new FrameLayout(this);

        View divider = new View(this);
        divider.setBackgroundColor(pal.accent);

        splitRow.addView(leftHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, leftRatio));
        splitRow.addView(divider, new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT));
        splitRow.addView(rightHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - leftRatio));

        root.addView(splitRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);

        populatePane(leftHost, leftMode, "left");
        populatePane(rightHost, rightMode, "right");
    }

    private Spinner makePaneSpinner(final boolean isLeft) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, PANE_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setSelection(isLeft ? leftMode : rightMode);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                int cur = isLeft ? leftMode : rightMode;
                if (pos != cur) {
                    if (isLeft) leftMode = pos; else rightMode = pos;
                    saveModes();
                    populatePane(isLeft ? leftHost : rightHost, pos, isLeft ? "left" : "right");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        return s;
    }

    private void saveModes() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("left_mode", leftMode).putInt("right_mode", rightMode).commit();
    }

    private void applyWeights() {
        leftHost.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, leftRatio));
        rightHost.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - leftRatio));
        splitRow.requestLayout();
    }

    private void populatePane(FrameLayout host, int mode, final String slotKey) {
        host.removeAllViews();
        if (mode == 0) host.addView(buildWebPane());
        else if (mode == 1) host.addView(buildBookPane());
        else if (mode == 2) host.addView(buildNotesPane(slotKey));
        else if (mode == 3) host.addView(Arc7DictionaryWikiActivity.buildEmbeddedLookupPane(this, shell, certMgr, pal));
        else host.addView(buildCalcPane());
    }

    private static String wrapGatewayUrl(String rawUrl) {
        try {
            return "http://127.0.0.1:" + NetworkCertManager.BROWSER_GATEWAY_PORT
                    + "/proxy?u=" + java.net.URLEncoder.encode(rawUrl, "UTF-8");
        } catch (Throwable t) {
            return rawUrl;
        }
    }

    private View buildWebPane() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final EditText urlEt = new EditText(this);
        urlEt.setSingleLine(true);
        urlEt.setText("https://lite.duckduckgo.com/lite/");
        urlEt.setImeOptions(EditorInfo.IME_ACTION_GO);

        final WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(wrapGatewayUrl(url));
                return true;
            }
        });
        urlEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                String u = urlEt.getText().toString().trim();
                if (!u.startsWith("http")) u = "https://" + u;
                wv.loadUrl(wrapGatewayUrl(u));
                return true;
            }
        });
        wv.loadUrl(wrapGatewayUrl("https://lite.duckduckgo.com/lite/"));
        box.addView(urlEt);
        box.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private View buildBookPane() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(10));

        final List<File> books = new ArrayList<File>();
        File dir = new File(Environment.getExternalStorageDirectory(), "Books");
        File[] arr = dir.listFiles();
        if (arr != null) for (File f : arr) if (f.isFile()) books.add(f);

        final TextView readerTv = new TextView(this);
        readerTv.setTextColor(0xFF2B2118);
        readerTv.setBackgroundColor(0xFFF6ECD9);
        readerTv.setPadding(dp(14), dp(12), dp(14), dp(16));
        readerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        readerTv.setTypeface(Typeface.SERIF);
        readerTv.setLineSpacing(dp(4), 1.25f);

        if (books.isEmpty()) {
            readerTv.setText("Place books in `/sdcard/Books` or download free `.fb2` / `.epub` / `.pdf` "
                    + "books in Arc7 E-Reader (VK Books / Project Gutenberg / Open Library / Standard Ebooks)!");
        } else {
            String[] names = new String[books.size()];
            for (int i = 0; i < books.size(); i++) names[i] = books.get(i).getName();
            Spinner picker = new Spinner(this);
            picker.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    try {
                        File f = books.get(pos);
                        String nm = f.getName().toLowerCase();
                        if (nm.endsWith(".pdf")) {
                            Arc7PdfEngine.ParsedPdf pdf = Arc7PdfEngine.parsePdfFile(f);
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < pdf.pages.size(); i++) sb.append(pdf.pages.get(i).text).append("\n\n");
                            readerTv.setText(sb.toString());
                        } else if (nm.endsWith(".fb2") || nm.endsWith(".fb2.zip")) {
                            Arc7BookSources.Fb2ParsedBook doc = Arc7BookSources.parseFb2File(f);
                            StringBuilder sb = new StringBuilder();
                            for (String ch : doc.chapterHtmls) sb.append(android.text.Html.fromHtml(ch)).append("\n\n");
                            readerTv.setText(sb.toString());
                        } else {
                            readerTv.setText("Open full E-Reader for formatted `.epub` / `.cbz` pagination: " + f.getName());
                        }
                    } catch (Throwable t) {
                        readerTv.setText("Could not open book: " + t.getMessage());
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
            box.addView(picker);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(readerTv);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private View buildNotesPane(final String slotKey) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));

        final EditText et = new EditText(this);
        et.setGravity(Gravity.TOP | Gravity.LEFT);
        et.setHint("Type split-screen study / research notes here (saved directly to Notes folder)...");
        et.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("notes_" + slotKey, ""));
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(pal.surface);
        et.setPadding(dp(12), dp(12), dp(12), dp(12));

        Button save = Arc7Skin.actionButton(this, "💾 Save Note to /sdcard/Documents/Notes", 0xFF059669);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String txt = et.getText().toString();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("notes_" + slotKey, txt).commit();
                try {
                    File dir = new File(new File(Environment.getExternalStorageDirectory(), "Documents"), "Notes");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(dir, "SplitView-Note.txt"));
                    try { fos.write(txt.getBytes("UTF-8")); } finally { fos.close(); }
                    Toast.makeText(Arc7SplitViewActivity.this, "Saved SplitView-Note.txt!", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });

        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(save);
        return box;
    }

    private View buildCalcPane() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        final EditText exprEt = new EditText(this);
        exprEt.setHint("Expression e.g. (1280 * 800) / 1024");
        exprEt.setSingleLine(true);

        final TextView ansTv = new TextView(this);
        ansTv.setTextColor(pal.accent);
        ansTv.setTypeface(Typeface.DEFAULT_BOLD);
        ansTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        ansTv.setPadding(0, dp(10), 0, dp(10));
        ansTv.setText("= 0");

        Button eval = Arc7Skin.actionButton(this, "= Evaluate Expression", 0xFF0284C7);
        eval.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    double val = evalSimpleMath(exprEt.getText().toString());
                    ansTv.setText("= " + val);
                } catch (Throwable t) {
                    ansTv.setText("Syntax error");
                }
            }
        });
        box.addView(exprEt);
        box.addView(eval);
        box.addView(ansTv);
        return box;
    }

    public static double evalSimpleMath(final String str) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < str.length()) ? str.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }
            double parse() { nextChar(); return parseExpression(); }
            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) { x = parseExpression(); eat(')'); }
                else {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(str.substring(startPos, this.pos));
                }
                return x;
            }
        }.parse();
    }
}
