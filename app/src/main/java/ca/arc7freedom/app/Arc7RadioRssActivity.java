package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.StringReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Standalone Live Internet Radio (`30,000+` Stations), Podcast Player/Downloader &
 * Offline RSS News Reader (`Arc7 Radio, Podcasts & Feeds`, `v2.2.0`):
 *
 *   - **Tab 1 — World Radio (live Radio-Browser directory + TLS 1.3):**
 *       Screen-off background streaming via `Arc7AudioService` + smooth fade-out **Sleep Timer**!
 *   - **Tab 2 — Podcasts (Search + Episode List + 1-Tap Offline MP3 Download):**
 *       Searches global podcasts via TLS 1.3, lists episodes from RSS enclosures, streams live OR
 *       saves `.mp3`/`.m4a` straight to `/sdcard/Podcasts/`!
 *   - **Tab 3 — Offline RSS / Atom Reader:**
 *       Syncs ad-free readable tech/world news feeds offline (plus custom feed URLs)!
 */
public class Arc7RadioRssActivity extends Activity {

    private static final String PREFS = "arc7_radio_rss";
    private static final String KEY_CUSTOM_FEEDS = "custom_rss_urls";
    private static final String KEY_OFFLINE_RSS_CACHE = "offline_rss_cache";

    private static final String RADIO_BROWSER_WEB_URL = "http://www.radio-browser.info/gui/#/";
    private static final String[] RADIO_BROWSER_API_HOSTS = new String[]{
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
    };

    public static final class RadioStation {
        public final String name;
        public final String countryCode;
        public final String streamUrl;

        public RadioStation(String name, String countryCode, String streamUrl) {
            this.name = name;
            this.countryCode = countryCode;
            this.streamUrl = streamUrl;
        }

        public String displayName() {
            return name + (countryCode.length() > 0 ? "  (" + countryCode + ")" : "");
        }
    }

    private static final String[][] DEFAULT_FEEDS = new String[][]{
            {"CBC News — Kitchener-Waterloo (Local)", "https://www.cbc.ca/webfeed/rss/rss-canada-kitchenerwaterloo"},
            {"CBC News — Top Stories (Canada)", "https://www.cbc.ca/webfeed/rss/rss-topstories"},
            {"Hacker News — Frontpage", "https://hnrss.org/frontpage"},
            {"BBC News — World", "https://feeds.bbci.co.uk/news/world/rss.xml"},
            {"Ars Technica — Technology Lab", "https://feeds.arstechnica.com/arstechnica/index"}
    };

    public static List<RadioStation> buildWaterlooRegionStations() {
        List<RadioStation> local = new ArrayList<RadioStation>();
        local.add(new RadioStation(
                "CBC Radio One — Waterloo Region 89.1 FM (Kitchener-Waterloo CBLA-FM-2)",
                "CA",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/CBLAFM2.mp3"));
        local.add(new RadioStation(
                "CBC Radio One — Southern Ontario 99.1 FM (CBLA-FM)",
                "CA",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/CBLAFM.mp3"));
        local.add(new RadioStation(
                "CBC Music — Southern Ontario & Waterloo Region 94.1 FM (CBL-FM)",
                "CA",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/CBLFM.mp3"));
        local.add(new RadioStation(
                "98.5 CKWR — Waterloo Region Community Radio (Kitchener-Waterloo)",
                "CA",
                "https://stream2.statsradio.com:8124/stream"));
        local.add(new RadioStation(
                "CityNews 570 (CKGL 570 AM — Kitchener-Waterloo News, Traffic & Weather)",
                "CA",
                "https://rogers-hls.leanstream.co/rogers/kit570.stream/icy"));
        local.add(new RadioStation(
                "96.7 CHYM FM (Kitchener-Waterloo)",
                "CA",
                "https://rogers-hls.leanstream.co/rogers/kit967.stream/icy"));
        local.add(new RadioStation(
                "Country 106.7 (CIKZ-FM Kitchener-Waterloo)",
                "CA",
                "https://rogers-hls.leanstream.co/rogers/kit1067.stream/icy"));
        local.add(new RadioStation(
                "91.5 The Beat (CKBT-FM Kitchener-Waterloo)",
                "CA",
                "https://live.leanstream.co/CKBTFM-MP3"));
        local.add(new RadioStation(
                "107.5 Dave Rocks (CJDV-FM Kitchener-Waterloo Classic Rock)",
                "CA",
                "https://live.leanstream.co/CJDVFM-MP3"));
        local.add(new RadioStation(
                "105.3 Virgin Radio (CFCA-FM Kitchener-Waterloo)",
                "CA",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/CFCAFM.mp3"));
        local.add(new RadioStation(
                "99.5 Bounce Radio (CKKW-FM Kitchener-Waterloo)",
                "CA",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/CKKWFM.mp3"));
        local.add(new RadioStation(
                "CFRU 93.3 FM — Guelph & Wellington-Waterloo Community Radio",
                "CA",
                "https://stream.cfru.ca/cfru128.mp3"));
        local.add(new RadioStation(
                "88.3 CJIQ FM — Conestoga College Waterloo Region (Kitchener)",
                "CA",
                "https://peridot.streamguys1.com:5185/live"));
        return local;
    }

    public static List<RadioStation> filterWaterlooRegionStations(String query) {
        List<RadioStation> all = buildWaterlooRegionStations();
        if (query == null || query.trim().length() == 0) return all;
        String q = query.trim().toLowerCase(Locale.US);
        if (q.equals("local") || q.equals("waterloo") || q.equals("waterloo region")
                || q.equals("kitchener") || q.equals("kitchener-waterloo")
                || q.equals("cambridge") || q.equals("kw") || q.equals("ontario")) {
            return all;
        }
        String[] tokens = q.split("\\s+");
        List<RadioStation> matched = new ArrayList<RadioStation>();
        for (int i = 0; i < all.size(); i++) {
            RadioStation st = all.get(i);
            String hay = (st.name + " " + st.countryCode + " waterloo region kitchener cambridge guelph ontario").toLowerCase(Locale.US);
            boolean ok = true;
            for (int t = 0; t < tokens.length; t++) {
                if (tokens[t].length() > 0 && !hay.contains(tokens[t])) {
                    ok = false;
                    break;
                }
            }
            if (ok) matched.add(st);
        }
        return matched;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell shell;
    private NetworkCertManager certMgr;
    private Arc7Skin.Palette pal;
    private int activeTab = 0; // 0=Radio, 1=Podcasts, 2=RSS
    private LinearLayout bodyBox;

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

        LinearLayout hdrBtns = new LinearLayout(this);
        hdrBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button playPause = Arc7Skin.actionButton(this, "⏯ Play/Pause", 0xFF059669);
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent svc = new Intent(Arc7RadioRssActivity.this, Arc7AudioService.class);
                svc.setAction(Arc7AudioService.ACTION_TOGGLE);
                startService(svc);
            }
        });
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ppLp.rightMargin = dp(6);
        hdrBtns.addView(playPause, ppLp);

        Button sleepBtn = Arc7Skin.actionButton(this, "🌙 Sleep Timer", 0xFF7C3AED);
        sleepBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptSleepTimer(); }
        });
        hdrBtns.addView(sleepBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_radio,
                "Radio & Podcasts",
                "30,000+ Live Radio Stations • Podcast MP3 Downloader • Offline RSS News",
                hdrBtns));

        // 3-Tab Switcher
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(pal.surface);
        tabs.setPadding(dp(12), dp(8), dp(12), dp(8));

        String[] names = new String[]{"📻 World Radio", "🎙 Podcasts", "📰 Offline RSS News"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = Arc7Skin.actionButton(this, names[i], i == activeTab ? pal.accentDark : 0xFF334155);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) lp.rightMargin = dp(8);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeTab = idx;
                    renderTab();
                }
            });
            tabs.addView(b, lp);
        }
        root.addView(tabs);

        ScrollView sv = new ScrollView(this);
        bodyBox = new LinearLayout(this);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.setPadding(dp(16), dp(14), dp(16), dp(22));
        sv.addView(bodyBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        renderTab();
    }

    private void promptSleepTimer() {
        final String[] labels = new String[]{
                "Off (Cancel Sleep Timer)", "15 Minutes (Smooth Fade-Out)",
                "30 Minutes (Smooth Fade-Out)", "45 Minutes (Smooth Fade-Out)", "60 Minutes (Smooth Fade-Out)"
        };
        final int[] mins = new int[]{0, 15, 30, 45, 60};
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Bedside Audio Fade-Out Sleep Timer")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent svc = new Intent(Arc7RadioRssActivity.this, Arc7AudioService.class);
                        svc.setAction(Arc7AudioService.ACTION_SLEEP_TIMER);
                        svc.putExtra(Arc7AudioService.EXTRA_SLEEP_MINS, mins[w]);
                        startService(svc);
                        Toast.makeText(Arc7RadioRssActivity.this,
                                mins[w] == 0 ? "Sleep timer turned off." : ("Sleep timer set for " + mins[w] + " min with 30s fade-out!"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void startBackgroundStream(String title, String url) {
        Intent svc = new Intent(this, Arc7AudioService.class);
        svc.setAction(Arc7AudioService.ACTION_PLAY_STREAM);
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_URL, url);
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_TITLE, title);
        startService(svc);
        Toast.makeText(this, "Streaming in background: " + title, Toast.LENGTH_SHORT).show();
    }

    private void renderTab() {
        bodyBox.removeAllViews();
        if (activeTab == 0) renderRadioTab();
        else if (activeTab == 1) renderPodcastsTab();
        else renderRssTab();
    }

    private void renderRadioTab() {
        LinearLayout searchCard = Arc7Skin.card(this, pal);
        final EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint("Search stations (e.g. CBC Radio One Waterloo, Kitchener, CKWR, jazz, BBC)...");
        searchCard.addView(q);

        Button searchBtn = Arc7Skin.actionButton(this, "Search Local & Radio-Browser Directory", 0xFF0284C7);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { searchRadioDirectory(q.getText().toString().trim()); }
        });
        searchCard.addView(searchBtn, slp);

        Button directoryBtn = Arc7Skin.actionButton(this, "Open radio-browser.info", 0xFF334155);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        directoryBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RADIO_BROWSER_WEB_URL)));
                } catch (Throwable t) {
                    Toast.makeText(Arc7RadioRssActivity.this,
                            "Could not open radio-browser.info: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        searchCard.addView(directoryBtn, dlp);
        bodyBox.addView(searchCard, cardSpace());

        // Local Waterloo Region & Ontario Stations Card (CBC Radio One 89.1 FM Kitchener-Waterloo + local FM/AM)
        LinearLayout localCard = Arc7Skin.card(this, pal);
        addTitle(localCard, "WATERLOO REGION & LOCAL ONTARIO STATIONS (KITCHENER • WATERLOO • CAMBRIDGE • GUELPH)");
        final TextView localStatus = new TextView(this);
        localStatus.setTextColor(pal.textMuted);
        localStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        localStatus.setText("Tap any local Waterloo Region / Kitchener-Waterloo station to stream live:");
        localCard.addView(localStatus);

        final LinearLayout localRows = new LinearLayout(this);
        localRows.setOrientation(LinearLayout.VERTICAL);
        localCard.addView(localRows);
        addRadioStationRows(localRows, buildWaterlooRegionStations());
        bodyBox.addView(localCard, cardSpace());
        enrichWaterlooStationsFromDirectoryAsync(localRows, localStatus);

        LinearLayout popularCard = Arc7Skin.card(this, pal);
        addTitle(popularCard, "POPULAR STATIONS FROM RADIO-BROWSER.INFO");
        final TextView stationStatus = new TextView(this);
        stationStatus.setTextColor(pal.textMuted);
        stationStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        stationStatus.setText("Loading top-voted directory stations...");
        popularCard.addView(stationStatus);

        final LinearLayout stationRows = new LinearLayout(this);
        stationRows.setOrientation(LinearLayout.VERTICAL);
        popularCard.addView(stationRows);

        Button refreshBtn = Arc7Skin.actionButton(this, "Refresh Popular Stations", 0xFF1E293B);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadTopRadioBrowserStations(stationRows, stationStatus); }
        });
        popularCard.addView(refreshBtn, rlp);
        bodyBox.addView(popularCard);
        loadTopRadioBrowserStations(stationRows, stationStatus);
    }

    private void enrichWaterlooStationsFromDirectoryAsync(final LinearLayout localRows, final TextView localStatus) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<RadioStation> merged = new ArrayList<RadioStation>(buildWaterlooRegionStations());
                Set<String> seenUrls = new HashSet<String>();
                for (int i = 0; i < merged.size(); i++) {
                    seenUrls.add(merged.get(i).streamUrl);
                }
                String[] regionalTerms = new String[]{"Kitchener", "Waterloo", "CBC%20Radio%20One"};
                for (int t = 0; t < regionalTerms.length; t++) {
                    try {
                        List<RadioStation> extra = fetchRadioBrowserStations(
                                "/json/stations/byname/" + regionalTerms[t]
                                        + "?limit=10&hidebroken=true&order=votes&reverse=true");
                        for (int k = 0; k < extra.size(); k++) {
                            RadioStation st = extra.get(k);
                            if (seenUrls.add(st.streamUrl)) {
                                merged.add(st);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (merged.size() > buildWaterlooRegionStations().size()) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            localRows.removeAllViews();
                            localStatus.setText(merged.size() + " Waterloo Region & Ontario stations ready to stream:");
                            addRadioStationRows(localRows, merged);
                        }
                    });
                }
            }
        }, "Arc7-WaterlooRadio").start();
    }

    private void loadTopRadioBrowserStations(final LinearLayout rows, final TextView status) {
        rows.removeAllViews();
        status.setText("Loading top-voted directory stations...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<RadioStation> stations = fetchRadioBrowserStations("/json/stations/topvote/20");
                    ui.post(new Runnable() {
                        @Override public void run() {
                            rows.removeAllViews();
                            if (stations.isEmpty()) {
                                status.setText("No stations returned. Search above or open radio-browser.info.");
                                return;
                            }
                            status.setText(stations.size() + " live stations from the Radio-Browser directory:");
                            addRadioStationRows(rows, stations);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            status.setText("Radio-Browser is temporarily unreachable. Search again or open radio-browser.info.");
                            Toast.makeText(Arc7RadioRssActivity.this,
                                    "Radio-Browser error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void addRadioStationRows(LinearLayout parent, List<RadioStation> stations) {
        for (int i = 0; i < stations.size(); i++) {
            final RadioStation station = stations.get(i);
            Button row = Arc7Skin.actionButton(this, "▶  " + station.displayName(), 0xFF1E293B);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startBackgroundStream(station.displayName(), station.streamUrl);
                }
            });
            parent.addView(row, lp);
        }
    }

    private List<RadioStation> fetchRadioBrowserStations(String path) throws Exception {
        Throwable lastError = null;
        for (int i = 0; i < RADIO_BROWSER_API_HOSTS.length; i++) {
            try {
                String raw = Arc7BookSources.fetchStringViaTls13(
                        Arc7RadioRssActivity.this, shell, certMgr, RADIO_BROWSER_API_HOSTS[i] + path);
                return parseRadioBrowserStations(raw);
            } catch (Throwable t) {
                lastError = t;
            }
        }
        throw new Exception("All Radio-Browser API servers failed", lastError);
    }

    public static List<RadioStation> parseRadioBrowserStations(String json) throws Exception {
        List<RadioStation> stations = new ArrayList<RadioStation>();
        Set<String> seen = new HashSet<String>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url_resolved", "").trim();
            if (url.length() == 0) url = o.optString("url", "").trim();
            if (!(url.startsWith("http://") || url.startsWith("https://"))) continue;
            String name = o.optString("name", "").trim();
            if (name.length() == 0) name = "Radio Station";
            String country = o.optString("countrycode", "").trim();
            String uuid = o.optString("stationuuid", "").trim();
            String identity = uuid.length() > 0 ? uuid : (name.toLowerCase(Locale.US) + "|" + url);
            if (!seen.add(identity)) continue;
            stations.add(new RadioStation(name, country, url));
        }
        return stations;
    }

    private void searchRadioDirectory(final String term) {
        if (term.isEmpty()) return;
        Toast.makeText(this, "Searching local Waterloo Region & Radio-Browser directory...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<RadioStation> stations = new ArrayList<RadioStation>(filterWaterlooRegionStations(term));
                Set<String> seenUrls = new HashSet<String>();
                for (int i = 0; i < stations.size(); i++) {
                    seenUrls.add(stations.get(i).streamUrl);
                }
                Throwable dirErr = null;
                try {
                    String encodedTerm = URLEncoder.encode(term, "UTF-8").replace("+", "%20");
                    List<RadioStation> remote = fetchRadioBrowserStations(
                            "/json/stations/byname/" + encodedTerm
                                    + "?limit=25&hidebroken=true&order=votes&reverse=true");
                    for (int i = 0; i < remote.size(); i++) {
                        RadioStation st = remote.get(i);
                        if (seenUrls.add(st.streamUrl)) stations.add(st);
                    }
                    String low = term.toLowerCase(Locale.US);
                    if (low.contains("waterloo") || low.contains("kitchener") || low.contains("cbc")) {
                        String[] extraQueries = new String[]{"Kitchener", "Waterloo", "CBC%20Radio%20One"};
                        for (int qIdx = 0; qIdx < extraQueries.length; qIdx++) {
                            try {
                                List<RadioStation> more = fetchRadioBrowserStations(
                                        "/json/stations/byname/" + extraQueries[qIdx]
                                                + "?limit=15&hidebroken=true&order=votes&reverse=true");
                                for (int k = 0; k < more.size(); k++) {
                                    RadioStation st = more.get(k);
                                    if (seenUrls.add(st.streamUrl)) stations.add(st);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    dirErr = t;
                }
                final Throwable finalErr = dirErr;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (stations.isEmpty()) {
                            Toast.makeText(Arc7RadioRssActivity.this,
                                    finalErr != null ? ("Radio search error: " + finalErr.getMessage()) : "No stations found.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        final String[] names = new String[stations.size()];
                        for (int i = 0; i < stations.size(); i++) names[i] = stations.get(i).displayName();
                        new AlertDialog.Builder(Arc7RadioRssActivity.this, AlertDialog.THEME_HOLO_DARK)
                                .setTitle("Tap Any Station to Stream")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        RadioStation station = stations.get(w);
                                        startBackgroundStream(station.displayName(), station.streamUrl);
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    private void renderPodcastsTab() {
        LinearLayout card = Arc7Skin.card(this, pal);
        addTitle(card, "SEARCH & DOWNLOAD PODCAST EPISODES OFFLINE");
        final EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint("Search podcasts (e.g. Hardcore History, Science Friday, Linux, Darknet Diaries)...");
        card.addView(q);

        Button searchBtn = Arc7Skin.actionButton(this, "Search Podcasts (TLS 1.3)", 0xFF059669);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { searchPodcasts(q.getText().toString().trim()); }
        });
        card.addView(searchBtn, slp);

        Button openSavedBtn = Arc7Skin.actionButton(this, "Open Downloaded Episodes in Arc7 Audio", 0xFF334155);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(8);
        openSavedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Arc7RadioRssActivity.this, Arc7AudioPlayerActivity.class));
            }
        });
        card.addView(openSavedBtn, olp);
        bodyBox.addView(card);
    }

    private void searchPodcasts(final String query) {
        if (query.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "https://itunes.apple.com/search?media=podcast&limit=20&term="
                            + URLEncoder.encode(query, "UTF-8");
                    String raw = Arc7BookSources.fetchStringViaTls13(Arc7RadioRssActivity.this, shell, certMgr, api);
                    JSONArray res = new JSONObject(raw).optJSONArray("results");
                    final List<String> names = new ArrayList<String>();
                    final List<String> feeds = new ArrayList<String>();
                    if (res != null) {
                        for (int i = 0; i < res.length(); i++) {
                            JSONObject o = res.getJSONObject(i);
                            String f = o.optString("feedUrl", "");
                            if (f.isEmpty()) continue;
                            names.add(o.optString("collectionName", "Podcast") + " — " + o.optString("artistName", ""));
                            feeds.add(f);
                        }
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (names.isEmpty()) {
                                Toast.makeText(Arc7RadioRssActivity.this, "No podcasts found.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            new AlertDialog.Builder(Arc7RadioRssActivity.this, AlertDialog.THEME_HOLO_DARK)
                                    .setTitle("Select Podcast to Load Episodes")
                                    .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            loadPodcastEpisodes(names.get(w), feeds.get(w));
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7RadioRssActivity.this, "Podcast search failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadPodcastEpisodes(final String showTitle, final String rssUrl) {
        Toast.makeText(this, "Fetching episodes...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String xml = Arc7BookSources.fetchStringViaTls13(Arc7RadioRssActivity.this, shell, certMgr, rssUrl);
                    final List<RssItem> items = parseRssOrAtomXml(xml, true);
                    final String[] labels = new String[items.size()];
                    for (int i = 0; i < items.size(); i++) labels[i] = "🎧 " + items.get(i).title;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (items.isEmpty()) {
                                Toast.makeText(Arc7RadioRssActivity.this, "No audio episodes found in feed.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            new AlertDialog.Builder(Arc7RadioRssActivity.this, AlertDialog.THEME_HOLO_DARK)
                                    .setTitle(showTitle)
                                    .setItems(labels, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, final int idx) {
                                            final RssItem ep = items.get(idx);
                                            new AlertDialog.Builder(Arc7RadioRssActivity.this, AlertDialog.THEME_HOLO_DARK)
                                                    .setTitle(ep.title)
                                                    .setMessage(ep.summary)
                                                    .setPositiveButton("Stream Now", new DialogInterface.OnClickListener() {
                                                        @Override
                                                        public void onClick(DialogInterface d2, int w2) {
                                                            startBackgroundStream(ep.title, ep.audioOrLink);
                                                        }
                                                    })
                                                    .setNeutralButton("Download Offline MP3", new DialogInterface.OnClickListener() {
                                                        @Override
                                                        public void onClick(DialogInterface d2, int w2) {
                                                            downloadEpisodeOffline(ep);
                                                        }
                                                    })
                                                    .setNegativeButton("Cancel", null)
                                                    .show();
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void downloadEpisodeOffline(final RssItem ep) {
        Toast.makeText(this, "Downloading episode offline to /sdcard/Podcasts...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(Environment.getExternalStorageDirectory(), "Podcasts");
                    dir.mkdirs();
                    String safe = ep.title.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
                    if (safe.length() > 56) safe = safe.substring(0, 56);
                    final File dst = new File(dir, safe + ".mp3");
                    Arc7BookSources.fetchFileViaTls13(shell, certMgr, ep.audioOrLink, dst);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7RadioRssActivity.this,
                                    "Saved offline: " + dst.getAbsolutePath(), Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7RadioRssActivity.this, "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void renderRssTab() {
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout topCard = Arc7Skin.card(this, pal);
        addTitle(topCard, "OFFLINE-SYNCED RSS / ATOM NEWS FEEDS");

        for (int i = 0; i < DEFAULT_FEEDS.length; i++) {
            final String fn = DEFAULT_FEEDS[i][0];
            final String fu = DEFAULT_FEEDS[i][1];
            addFeedButton(topCard, fn, fu);
        }
        String custom = sp.getString(KEY_CUSTOM_FEEDS, "");
        if (!custom.isEmpty()) {
            String[] lines = custom.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                String u = lines[i].trim();
                if (!u.isEmpty()) addFeedButton(topCard, "Custom Feed: " + u, u);
            }
        }

        Button addBtn = Arc7Skin.actionButton(this, "+ Add Custom RSS / Atom URL", 0xFF0284C7);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText in = new EditText(Arc7RadioRssActivity.this);
                in.setSingleLine(true);
                in.setHint("https://example.com/feed.xml");
                new AlertDialog.Builder(Arc7RadioRssActivity.this, AlertDialog.THEME_HOLO_DARK)
                        .setTitle("Add RSS / Atom Feed")
                        .setView(in)
                        .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String u = in.getText().toString().trim();
                                if (!u.isEmpty()) {
                                    String cur = sp.getString(KEY_CUSTOM_FEEDS, "");
                                    sp.edit().putString(KEY_CUSTOM_FEEDS, cur.isEmpty() ? u : (cur + "\n" + u)).commit();
                                    renderTab();
                                }
                            }
                        })
                        .show();
            }
        });
        topCard.addView(addBtn, alp);
        bodyBox.addView(topCard, cardSpace());

        // Cached Offline Headlines
        String cachedText = sp.getString(KEY_OFFLINE_RSS_CACHE, "");
        if (!cachedText.isEmpty()) {
            LinearLayout cacheCard = Arc7Skin.card(this, pal);
            addTitle(cacheCard, "LAST SYNCED OFFLINE HEADLINES & SUMMARIES");
            TextView tv = new TextView(this);
            tv.setText(cachedText);
            tv.setTextColor(pal.textPrimary);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tv.setLineSpacing(dp(3), 1.12f);
            cacheCard.addView(tv);
            bodyBox.addView(cacheCard);
        }
    }

    private void addFeedButton(LinearLayout parent, final String label, final String url) {
        Button b = Arc7Skin.actionButton(this, "📰  " + label, 0xFF1E293B);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { syncRssFeed(label, url); }
        });
        parent.addView(b, lp);
    }

    private void syncRssFeed(final String label, final String url) {
        Toast.makeText(this, "Syncing offline feed: " + label, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String xml = Arc7BookSources.fetchStringViaTls13(Arc7RadioRssActivity.this, shell, certMgr, url);
                    List<RssItem> items = parseRssOrAtomXml(xml, false);
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== ").append(label).append(" (Saved Offline) ===\n\n");
                    for (int i = 0; i < Math.min(20, items.size()); i++) {
                        RssItem it = items.get(i);
                        sb.append(i + 1).append(". ").append(it.title).append('\n');
                        if (!it.summary.isEmpty()) sb.append("   ").append(it.summary).append('\n');
                        sb.append('\n');
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_OFFLINE_RSS_CACHE, sb.toString()).commit();
                    ui.post(new Runnable() {
                        @Override public void run() { renderTab(); }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7RadioRssActivity.this, "RSS sync failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    public static final class RssItem {
        public String title = "";
        public String summary = "";
        public String audioOrLink = "";
    }

    public static List<RssItem> parseRssOrAtomXml(String xml, boolean requireEnclosure) throws Exception {
        List<RssItem> out = new ArrayList<RssItem>();
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser xp = f.newPullParser();
        xp.setInput(new StringReader(xml));
        int ev;
        RssItem cur = null;
        while ((ev = xp.next()) != XmlPullParser.END_DOCUMENT && out.size() < 35) {
            if (ev == XmlPullParser.START_TAG) {
                String t = xp.getName();
                if ("item".equalsIgnoreCase(t) || "entry".equalsIgnoreCase(t)) {
                    cur = new RssItem();
                } else if (cur != null) {
                    if ("title".equalsIgnoreCase(t)) {
                        cur.title = xp.nextText().trim();
                    } else if ("description".equalsIgnoreCase(t) || "summary".equalsIgnoreCase(t)) {
                        String raw = xp.nextText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                        cur.summary = raw.length() > 260 ? raw.substring(0, 260) + "..." : raw;
                    } else if ("enclosure".equalsIgnoreCase(t)) {
                        String u = xp.getAttributeValue(null, "url");
                        if (u != null && !u.isEmpty()) cur.audioOrLink = u;
                    } else if ("link".equalsIgnoreCase(t) && cur.audioOrLink.isEmpty()) {
                        String href = xp.getAttributeValue(null, "href");
                        cur.audioOrLink = (href != null && !href.isEmpty()) ? href : xp.nextText().trim();
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String t = xp.getName();
                if (("item".equalsIgnoreCase(t) || "entry".equalsIgnoreCase(t)) && cur != null) {
                    if (!cur.title.isEmpty() && (!requireEnclosure || !cur.audioOrLink.isEmpty())) {
                        out.add(cur);
                    }
                    cur = null;
                }
            }
        }
        return out;
    }

    private void addTitle(LinearLayout box, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(pal.accent);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        box.addView(tv);
    }

    private LinearLayout.LayoutParams cardSpace() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        return lp;
    }
}
