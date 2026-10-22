package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Instant Offline + TLS 1.3 Dictionary & Distraction-Free Wikipedia Reader
 * (`Arc7 Dictionary & Wikipedia`, `v2.2.0`):
 *
 *   - Instant built-in offline English literary lexicon + live `api.dictionaryapi.dev` (Wiktionary
 *     data source over `arc7tls` TLS 1.3: phonetics, parts of speech, definitions & examples!)
 *   - Fast zero-bloat Wikipedia Encyclopedia (`en.wikipedia.org/api/rest_v1/page/summary/...` +
 *     MediaWiki full extract over TLS 1.3) with **1-Tap Save Article Offline**!
 */
public class Arc7DictionaryWikiActivity extends Activity {

    private static final String PREFS = "arc7_dict_wiki";
    private static final String KEY_SAVED = "saved_wiki_articles";
    private static final Map<String, String> OFFLINE_LEXICON = new HashMap<String, String>();

    static {
        OFFLINE_LEXICON.put("ephemeral", "adjective — Lasting for a very short time; transitory.");
        OFFLINE_LEXICON.put("serendipity", "noun — The occurrence of happy or beneficial discoveries by chance.");
        OFFLINE_LEXICON.put("ubiquitous", "adjective — Present, appearing, or found everywhere.");
        OFFLINE_LEXICON.put("pragmatic", "adjective — Dealing with things sensibly and realistically.");
        OFFLINE_LEXICON.put("eloquent", "adjective — Fluent, persuasive, and expressive in speech or writing.");
        OFFLINE_LEXICON.put("resilient", "adjective — Able to withstand or recover quickly from difficult conditions.");
        OFFLINE_LEXICON.put("paradigm", "noun — A typical example, pattern, or model of something.");
        OFFLINE_LEXICON.put("lucid", "adjective — Expressed clearly; easy to understand; luminous.");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell shell;
    private NetworkCertManager certMgr;
    private Arc7Skin.Palette pal;
    private EditText queryEt;
    private TextView resultTitleTv;
    private TextView resultBodyTv;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell = new RootShell(this);
        certMgr = new NetworkCertManager(this, shell);
        pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        Button saveOfflineBtn = Arc7Skin.actionButton(this, "★ Save Article Offline", 0xFFD97706);
        saveOfflineBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveCurrentOffline(); }
        });

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_dictionary,
                "Wikipedia & Dictionary",
                "Offline + Wiktionary Definitions • Clean Ad-Free Wikipedia • Offline Saved Articles",
                saveOfflineBtn));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(pal.surface);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        queryEt = new EditText(this);
        queryEt.setSingleLine(true);
        queryEt.setHint("Type any word or Wikipedia topic (e.g. Quantum mechanics, Kitchener)...");
        bar.addView(queryEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button dictBtn = Arc7Skin.actionButton(this, "Define Word", 0xFF0284C7);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(8);
        dictBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lookupDefinition(queryEt.getText().toString().trim()); }
        });
        bar.addView(dictBtn, dlp);

        Button wikiBtn = Arc7Skin.actionButton(this, "Wikipedia", 0xFF059669);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dp(6);
        wikiBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lookupWikipedia(queryEt.getText().toString().trim()); }
        });
        bar.addView(wikiBtn, wlp);

        Button savedBtn = Arc7Skin.actionButton(this, "Saved", 0xFF334155);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svlp.leftMargin = dp(6);
        savedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSavedArticles(); }
        });
        bar.addView(savedBtn, svlp);

        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout cardWrap = new LinearLayout(this);
        cardWrap.setOrientation(LinearLayout.VERTICAL);
        cardWrap.setPadding(dp(16), dp(14), dp(16), dp(22));

        LinearLayout card = Arc7Skin.card(this, pal);
        resultTitleTv = new TextView(this);
        resultTitleTv.setText("Instant Dictionary & Clean Wikipedia Reader");
        resultTitleTv.setTextColor(pal.accent);
        resultTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        resultTitleTv.setTypeface(Typeface.SERIF, Typeface.BOLD);
        card.addView(resultTitleTv);

        resultBodyTv = new TextView(this);
        resultBodyTv.setText("Type any English word above and tap 'Define Word' for instant offline + Wiktionary definitions, or tap 'Wikipedia' for distraction-free full encyclopedic articles with 1-tap offline saving!");
        resultBodyTv.setTextColor(pal.textPrimary);
        resultBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        resultBodyTv.setLineSpacing(dp(4), 1.18f);
        resultBodyTv.setPadding(0, dp(10), 0, 0);
        card.addView(resultBodyTv);

        cardWrap.addView(card);
        sv.addView(cardWrap);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
    }

    public static View buildEmbeddedLookupPane(final Context ctx, final RootShell shell,
                                               final NetworkCertManager certMgr, final Arc7Skin.Palette pal) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Arc7Skin.dp(ctx, 10), Arc7Skin.dp(ctx, 10), Arc7Skin.dp(ctx, 10), Arc7Skin.dp(ctx, 10));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setHint("Word or Wikipedia topic...");
        row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView outTv = new TextView(ctx);
        outTv.setTextColor(pal.textPrimary);
        outTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        outTv.setPadding(0, Arc7Skin.dp(ctx, 8), 0, 0);

        Button goWiki = Arc7Skin.actionButton(ctx, "Wiki / Define", 0xFF0284C7);
        goWiki.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String term = et.getText().toString().trim();
                if (term.isEmpty()) return;
                outTv.setText("Looking up '" + term + "' over TLS 1.3...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = fetchWikiExtractSync(ctx, shell, certMgr, term);
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() { outTv.setText(res); }
                        });
                    }
                }).start();
            }
        });
        row.addView(goWiki);
        box.addView(row);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(outTv);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private void lookupDefinition(final String word) {
        if (word.isEmpty()) return;
        String local = OFFLINE_LEXICON.get(word.toLowerCase(Locale.US));
        resultTitleTv.setText(word);
        resultBodyTv.setText(local != null
                ? ("[Offline Instant Lexicon]\n" + local + "\n\nFetching full Wiktionary entry over TLS 1.3...")
                : "Fetching Wiktionary definition over TLS 1.3...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://api.dictionaryapi.dev/api/v2/entries/en/"
                            + URLEncoder.encode(word.toLowerCase(Locale.US), "UTF-8");
                    String raw = Arc7BookSources.fetchStringViaTls13(Arc7DictionaryWikiActivity.this, shell, certMgr, url);
                    JSONArray arr = new JSONArray(raw);
                    JSONObject e0 = arr.getJSONObject(0);
                    StringBuilder sb = new StringBuilder();
                    String phon = e0.optString("phonetic", "");
                    if (!phon.isEmpty()) sb.append("Pronunciation: ").append(phon).append("\n\n");
                    JSONArray meanings = e0.optJSONArray("meanings");
                    if (meanings != null) {
                        for (int m = 0; m < meanings.length(); m++) {
                            JSONObject mo = meanings.getJSONObject(m);
                            sb.append("• [").append(mo.optString("partOfSpeech", "")).append("]\n");
                            JSONArray defs = mo.optJSONArray("definitions");
                            if (defs != null) {
                                for (int d = 0; d < Math.min(4, defs.length()); d++) {
                                    JSONObject defObj = defs.getJSONObject(d);
                                    sb.append("   ").append(d + 1).append(". ").append(defObj.optString("definition", "")).append('\n');
                                    String ex = defObj.optString("example", "");
                                    if (!ex.isEmpty()) sb.append("      \"").append(ex).append("\"\n");
                                }
                            }
                            sb.append('\n');
                        }
                    }
                    final String text = sb.toString();
                    ui.post(new Runnable() {
                        @Override public void run() { resultBodyTv.setText(text); }
                    });
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void lookupWikipedia(final String topic) {
        if (topic.isEmpty()) return;
        resultTitleTv.setText("Wikipedia: " + topic);
        resultBodyTv.setText("Loading full clean Wikipedia article via TLS 1.3...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String body = fetchWikiExtractSync(Arc7DictionaryWikiActivity.this, shell, certMgr, topic);
                ui.post(new Runnable() {
                    @Override public void run() { resultBodyTv.setText(body); }
                });
            }
        }).start();
    }

    public static String fetchWikiExtractSync(Context ctx, RootShell shell,
                                              NetworkCertManager certMgr, String topic) {
        try {
            String api = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1"
                    + "&redirects=1&format=json&titles=" + URLEncoder.encode(topic, "UTF-8");
            String raw = Arc7BookSources.fetchStringViaTls13(ctx, shell, certMgr, api);
            JSONObject pages = new JSONObject(raw).getJSONObject("query").getJSONObject("pages");
            JSONArray keys = pages.names();
            if (keys != null && keys.length() > 0) {
                JSONObject p = pages.getJSONObject(keys.getString(0));
                String ex = p.optString("extract", "");
                if (!ex.isEmpty()) return ex;
            }
            return "No Wikipedia article found for '" + topic + "'.";
        } catch (Throwable t) {
            return "Offline / network unavailable: " + t.getMessage();
        }
    }

    private void saveCurrentOffline() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cur = sp.getString(KEY_SAVED, "");
        String block = "=== " + resultTitleTv.getText() + " ===\n" + resultBodyTv.getText() + "\n\n";
        sp.edit().putString(KEY_SAVED, block + cur).commit();
        Toast.makeText(this, "Saved offline!", Toast.LENGTH_SHORT).show();
    }

    private void showSavedArticles() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SAVED, "");
        resultTitleTv.setText("Saved Offline Articles & Definitions");
        resultBodyTv.setText(s.isEmpty() ? "No saved articles yet — tap '★ Save Article Offline' anytime!" : s);
    }
}
