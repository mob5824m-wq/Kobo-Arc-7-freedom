package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone `YouTube Music` (Morphe Edition — `v2.4.0`) for Kobo Arc 7 (`Android 4.2.2`).
 *
 * Authentic Morphe Pure-Black AMOLED YT Music experience:
 *   - Native `music.youtube.com` `WEB_REMIX` + `ANDROID_MUSIC 7.12` Morphe Stream Spoofer
 *   - True screen-off background playback (`PARTIAL_WAKE_LOCK` + High-Perf `WifiLock`) via `Arc7AudioService`
 *   - Millisecond-synced Karaoke Lyrics (`lrclib.net` + tap-to-seek!)
 *   - Up-Next Radio Mix Queue, Shuffle, Repeat, Liked Songs, 5-Band Hardware Equalizer, Sleep Timer & Offline `.m4a` Downloads
 *   - Full live Android Media Notification + Clock Standby Now-Playing integration!
 */
public class YouTubeMusicActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alive = true;

    private Arc7AudioService audioService;
    private boolean bound = false;

    private ImageView coverArtIv;
    private TextView trackTitleTv;
    private TextView trackArtistTv;
    private TextView liveLyricBannerTv;
    private SeekBar seekBar;
    private TextView posTv;
    private TextView durTv;
    private Button playPauseBtn;
    private Button shuffleBtn;
    private Button repeatBtn;

    private EditText searchEt;
    private TextView rightPaneHeaderTv;
    private LinearLayout rightPaneList;

    private final List<MorpheEngine.MediaItem> browseResults = new ArrayList<MorpheEngine.MediaItem>();
    private final List<MorpheEngine.LyricLine> currentLyrics = new ArrayList<MorpheEngine.LyricLine>();
    private String loadedLyricsTrackTitle = "";
    private boolean userScrubbing = false;
    private String activeRightMode = "browse"; // "browse" | "queue" | "lyrics"

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            audioService = ((Arc7AudioService.LocalBinder) binder).getService();
            bound = true;
            syncNowPlayingUi();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            audioService = null;
            bound = false;
        }
    };

    private final Runnable liveTicker = new Runnable() {
        @Override
        public void run() {
            if (alive) {
                syncNowPlayingUi();
                ui.postDelayed(this, 600L);
            }
        }
    };

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Arc7TlsGatewayService.ensureStarted(this);
        Arc7AudioService.ensureWatcherRunning(this);
        MorpheEngine.ensureStreamProxyStarted(this);

        boolean landscape = getResources().getDisplayMetrics().widthPixels
                >= getResources().getDisplayMetrics().heightPixels;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF050508);

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF0E0E15);
        topBar.setPadding(dp(12), dp(7), dp(12), dp(7));

        ImageView logoIv = new ImageView(this);
        logoIv.setImageResource(R.drawable.ic_ytmusic);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(32), dp(32));
        llp.rightMargin = dp(8);
        topBar.addView(logoIv, llp);

        TextView appTitle = new TextView(this);
        appTitle.setText("YouTube Music");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams atlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        atlp.rightMargin = dp(10);
        topBar.addView(appTitle, atlp);

        searchEt = new EditText(this);
        searchEt.setHint("Search songs, artists, albums, playlists...");
        searchEt.setHintTextColor(0xFF71717A);
        searchEt.setTextColor(Color.WHITE);
        searchEt.setSingleLine(true);
        searchEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        searchEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xFF191924);
        sbg.setCornerRadius(dp(18));
        sbg.setStroke(dp(1), 0xFF2D2D3F);
        searchEt.setBackgroundDrawable(sbg);
        searchEt.setPadding(dp(14), dp(7), dp(14), dp(7));
        searchEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                performMusicSearch(searchEt.getText().toString(), "songs");
                return true;
            }
        });
        LinearLayout.LayoutParams selp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        selp.rightMargin = dp(6);
        topBar.addView(searchEt, selp);

        Button searchBtn = pillBtn("\uD83D\uDD0D Songs", 0xFFFF0033);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { performMusicSearch(searchEt.getText().toString(), "songs"); }
        });
        topBar.addView(searchBtn, marginRightLp(6));

        Button eqBtn = pillBtn("\uD83C\uDF9A EQ", 0xFF1F2937);
        eqBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEqualizerDialog(); }
        });
        topBar.addView(eqBtn, marginRightLp(6));

        Button sleepBtn = pillBtn("\uD83C\uDF19 Sleep", 0xFF1F2937);
        sleepBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSleepTimerDialog(); }
        });
        topBar.addView(sleepBtn, marginRightLp(6));

        Button morpheBtn = pillBtn("\u2728 Morphe", 0xFF059669);
        morpheBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMorpheMusicSettings(); }
        });
        topBar.addView(morpheBtn);
        root.addView(topBar);

        // Mood / Quick Picks / Library Filter Bar
        HorizontalScrollView moodScroll = new HorizontalScrollView(this);
        moodScroll.setHorizontalScrollBarEnabled(false);
        moodScroll.setBackgroundColor(0xFF0A0A10);
        LinearLayout moodRow = new LinearLayout(this);
        moodRow.setOrientation(LinearLayout.HORIZONTAL);
        moodRow.setPadding(dp(10), dp(6), dp(10), dp(6));

        final String[][] moods = new String[][]{
                {"\uD83D\uDD25 Quick Picks", "top hits music 2026"},
                {"\uD83C\uDFA7 Chill & Lofi", "chill lofi beats study"},
                {"\u26A1 Energy & Workout", "workout hype hits"},
                {"\uD83C\uDFB8 Rock Classics", "classic rock anthems"},
                {"\uD83C\uDFA4 Hip-Hop & R&B", "hip hop rnb hits"},
                {"\uD83C\uDFB9 Focus & Jazz", "instrumental jazz piano"},
                {"\u2764 Liked Songs", "@liked"},
                {"\u2B07 Offline Library", "@offline"},
                {"\uD83D\uDCCB Up Next Queue", "@queue"},
                {"\uD83C\uDFA4 Synced Lyrics", "@lyrics"}
        };
        for (int i = 0; i < moods.length; i++) {
            final String label = moods[i][0];
            final String q = moods[i][1];
            Button b = pillBtn(label, 0xFF181824);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("@liked".equals(q)) showLikedSongs();
                    else if ("@offline".equals(q)) showOfflineMusicLibrary();
                    else if ("@queue".equals(q)) renderQueuePane();
                    else if ("@lyrics".equals(q)) renderLyricsPane();
                    else performMusicSearch(q, "songs");
                }
            });
            moodRow.addView(b, marginRightLp(6));
        }
        moodScroll.addView(moodRow);
        root.addView(moodScroll);

        // Main Split Content Area
        LinearLayout splitBody = new LinearLayout(this);
        splitBody.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        // Left / Top Now-Playing Studio Deck
        LinearLayout playerDeck = new LinearLayout(this);
        playerDeck.setOrientation(LinearLayout.VERTICAL);
        playerDeck.setGravity(Gravity.CENTER_HORIZONTAL);
        playerDeck.setBackgroundColor(0xFF0D0D15);
        playerDeck.setPadding(dp(16), dp(12), dp(16), dp(12));

        int artSize = landscape ? dp(172) : dp(124);
        coverArtIv = new ImageView(this);
        coverArtIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverArtIv.setImageResource(R.drawable.ic_ytmusic);
        playerDeck.addView(coverArtIv, new LinearLayout.LayoutParams(artSize, artSize));

        trackTitleTv = new TextView(this);
        trackTitleTv.setText("Pick a song or Quick Pick");
        trackTitleTv.setTextColor(Color.WHITE);
        trackTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        trackTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        trackTitleTv.setGravity(Gravity.CENTER);
        trackTitleTv.setMaxLines(2);
        trackTitleTv.setPadding(0, dp(10), 0, dp(2));
        playerDeck.addView(trackTitleTv);

        trackArtistTv = new TextView(this);
        trackArtistTv.setText("Morphe Ad-Free  \u2022  Screen-Off M4A  \u2022  Synced Lyrics");
        trackArtistTv.setTextColor(0xFFA1A1AA);
        trackArtistTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        trackArtistTv.setGravity(Gravity.CENTER);
        trackArtistTv.setSingleLine(true);
        playerDeck.addView(trackArtistTv);

        liveLyricBannerTv = new TextView(this);
        liveLyricBannerTv.setTextColor(0xFFFF4D6D);
        liveLyricBannerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        liveLyricBannerTv.setTypeface(Typeface.DEFAULT_BOLD);
        liveLyricBannerTv.setGravity(Gravity.CENTER);
        liveLyricBannerTv.setPadding(dp(4), dp(6), dp(4), dp(4));
        liveLyricBannerTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renderLyricsPane(); }
        });
        playerDeck.addView(liveLyricBannerTv);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) posTv.setText(NowPlayingManager.formatDurationMs(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userScrubbing = true; }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userScrubbing = false;
                if (audioService != null) audioService.seekAbsoluteMs(sb.getProgress());
            }
        });
        playerDeck.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout timesRow = new LinearLayout(this);
        timesRow.setOrientation(LinearLayout.HORIZONTAL);
        posTv = new TextView(this);
        posTv.setText("0:00");
        posTv.setTextColor(0xFFA1A1AA);
        posTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        timesRow.addView(posTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durTv = new TextView(this);
        durTv.setText("0:00");
        durTv.setTextColor(0xFFA1A1AA);
        durTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        timesRow.addView(durTv);
        playerDeck.addView(timesRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Transport Controls Row
        LinearLayout transportRow = new LinearLayout(this);
        transportRow.setOrientation(LinearLayout.HORIZONTAL);
        transportRow.setGravity(Gravity.CENTER);
        transportRow.setPadding(0, dp(8), 0, dp(6));

        shuffleBtn = pillBtn("\uD83D\uDD00", 0xFF1F2937);
        shuffleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioService == null) return;
                boolean next = !audioService.isShuffleEnabled();
                audioService.setShuffleEnabled(next);
                Toast.makeText(YouTubeMusicActivity.this,
                        "Shuffle: " + (next ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                syncNowPlayingUi();
            }
        });
        transportRow.addView(shuffleBtn, marginRightLp(6));

        Button prevBtn = pillBtn("\u23EE", 0xFF27272A);
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (audioService != null) audioService.stepQueue(-1); }
        });
        transportRow.addView(prevBtn, marginRightLp(6));

        playPauseBtn = pillBtn("\u25B6 Play", 0xFFFF0033);
        playPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (audioService != null) audioService.togglePlayPause(); }
        });
        transportRow.addView(playPauseBtn, marginRightLp(6));

        Button nextBtn = pillBtn("\u23ED", 0xFF27272A);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (audioService != null) audioService.stepQueue(1); }
        });
        transportRow.addView(nextBtn, marginRightLp(6));

        repeatBtn = pillBtn("\uD83D\uDD01 Off", 0xFF1F2937);
        repeatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioService == null) return;
                int next = (audioService.getRepeatMode() + 1) % 3;
                audioService.setRepeatMode(next);
                syncNowPlayingUi();
            }
        });
        transportRow.addView(repeatBtn);
        playerDeck.addView(transportRow);

        // Secondary Action Row: Like, Lyrics, Download M4A, Watch Video in YouTube
        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER);

        Button likeBtn = pillBtn("\u2764 Like", 0xFF1F2937);
        likeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MorpheEngine.MediaItem cur = audioService != null ? audioService.getCurrentMorpheItem() : null;
                if (cur != null) {
                    MorpheEngine.addOrToggleSavedItem(YouTubeMusicActivity.this, "ytm_liked", cur, true);
                    Toast.makeText(YouTubeMusicActivity.this, "Updated Liked Songs!", Toast.LENGTH_SHORT).show();
                }
            }
        });
        extraRow.addView(likeBtn, marginRightLp(6));

        Button lyricsBtn = pillBtn("\uD83C\uDFA4 Lyrics", 0xFF1F2937);
        lyricsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renderLyricsPane(); }
        });
        extraRow.addView(lyricsBtn, marginRightLp(6));

        Button dlM4aBtn = pillBtn("\u2B07 Save M4A", 0xFF0284C7);
        dlM4aBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadCurrentSongOffline(); }
        });
        extraRow.addView(dlM4aBtn, marginRightLp(6));

        Button videoSwitchBtn = pillBtn("\uD83C\uDFAC Video", 0xFF27272A);
        videoSwitchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MorpheEngine.MediaItem cur = audioService != null ? audioService.getCurrentMorpheItem() : null;
                if (cur != null && cur.videoId.length() >= 8) {
                    openYouTubeApp("https://www.youtube.com/watch?v=" + cur.videoId);
                }
            }
        });
        extraRow.addView(videoSwitchBtn);
        playerDeck.addView(extraRow);

        if (landscape) {
            splitBody.addView(playerDeck, new LinearLayout.LayoutParams(
                    dp(370), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            splitBody.addView(playerDeck, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Right / Bottom Scrollable Songs, Queue & Lyrics Pane
        LinearLayout rightWrap = new LinearLayout(this);
        rightWrap.setOrientation(LinearLayout.VERTICAL);
        rightWrap.setPadding(dp(12), dp(8), dp(12), dp(8));

        rightPaneHeaderTv = new TextView(this);
        rightPaneHeaderTv.setText("\uD83D\uDD25 Quick Picks");
        rightPaneHeaderTv.setTextColor(Color.WHITE);
        rightPaneHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        rightPaneHeaderTv.setTypeface(Typeface.DEFAULT_BOLD);
        rightPaneHeaderTv.setPadding(dp(4), dp(2), dp(4), dp(8));
        rightWrap.addView(rightPaneHeaderTv);

        ScrollView listScroll = new ScrollView(this);
        rightPaneList = new LinearLayout(this);
        rightPaneList.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(rightPaneList);
        rightWrap.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        splitBody.addView(rightWrap, new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1f));

        root.addView(splitBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);

        Intent svcIntent = new Intent(this, Arc7AudioService.class);
        startService(svcIntent);
        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE);
        ui.postDelayed(liveTicker, 500L);

        if (getIntent() != null && getIntent().getData() != null) {
            String vid = MorpheEngine.extractVideoIdFromUrlOrQuery(getIntent().getDataString());
            if (vid != null) {
                final MorpheEngine.MediaItem single = new MorpheEngine.MediaItem();
                single.videoId = vid;
                single.title = "Loading Track...";
                single.isMusicTrack = true;
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (audioService != null) {
                            List<MorpheEngine.MediaItem> q = new ArrayList<MorpheEngine.MediaItem>();
                            q.add(single);
                            audioService.playMorpheQueue(q, 0, "YouTube Music",
                                    "ca.arc7freedom.app.YouTubeMusicActivity");
                        }
                    }
                }, 250L);
            }
        }
        performMusicSearch("top hits music 2026", "songs");
    }


    /**
     * Opens the SEPARATE `ca.arc7freedom.youtube` app (own package/task/icon) with the current
     * track, falling back to the in-suite activity.
     */
    private void openYouTubeApp(String watchUrl) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(android.net.Uri.parse(watchUrl));
        i.setComponent(new ComponentName("ca.arc7freedom.youtube", "ca.arc7freedom.app.YouTubeActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent fallback = new Intent(this, YouTubeActivity.class);
            fallback.setData(android.net.Uri.parse(watchUrl));
            Arc7UiAnimator.startAnimated(this, fallback);
        } catch (Throwable ignored) {}
    }

    private void performMusicSearch(final String query, final String mode) {
        if (query == null || query.trim().isEmpty()) return;
        final String clean = query.trim();
        String directVid = MorpheEngine.extractVideoIdFromUrlOrQuery(clean);
        if (directVid != null && audioService != null) {
            MorpheEngine.MediaItem single = new MorpheEngine.MediaItem();
            single.videoId = directVid;
            single.title = "Playing Track";
            single.isMusicTrack = true;
            List<MorpheEngine.MediaItem> q = new ArrayList<MorpheEngine.MediaItem>();
            q.add(single);
            audioService.playMorpheQueue(q, 0, "YouTube Music", "ca.arc7freedom.app.YouTubeMusicActivity");
            return;
        }
        activeRightMode = "browse";
        rightPaneHeaderTv.setText("Searching YouTube Music for \"" + clean + "\"...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<MorpheEngine.MediaItem> items =
                            MorpheEngine.searchYouTubeMusic(YouTubeMusicActivity.this, clean, mode);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            rightPaneHeaderTv.setText("Tracks & Mixes  \u2022  " + items.size() + " songs");
                            browseResults.clear();
                            browseResults.addAll(items);
                            renderTrackRows(items, false);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            rightPaneHeaderTv.setText("Offline — showing saved local M4A tracks");
                            showOfflineMusicLibrary();
                        }
                    });
                }
            }
        }).start();
    }

    private void renderTrackRows(final List<MorpheEngine.MediaItem> items, final boolean isQueueView) {
        rightPaneList.removeAllViews();
        int activeIdx = audioService != null ? audioService.getMorpheQueueIndex() : -1;

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final MorpheEngine.MediaItem it = items.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor((isQueueView && idx == activeIdx) ? 0xFF28121E : 0xFF12121C);
            bg.setCornerRadius(dp(11));
            bg.setStroke(dp(1), (isQueueView && idx == activeIdx) ? 0xFFFF0033 : 0xFF222233);
            row.setBackgroundDrawable(bg);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);

            TextView t = new TextView(this);
            t.setText(((isQueueView && idx == activeIdx) ? "\u25B6  " : "") + it.title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine(true);
            info.addView(t);

            String sub = it.author;
            if (it.album != null && it.album.length() > 0) sub += "  \u2022  " + it.album;
            if (it.durationText != null && it.durationText.length() > 0) sub += "  \u2022  " + it.durationText;
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(0xFFA1A1AA);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            s.setSingleLine(true);
            info.addView(s);

            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!isQueueView) {
                Button addQBtn = pillBtn("+ Queue", 0xFF27272A);
                addQBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (audioService != null) {
                            audioService.appendToMorpheQueue(it);
                            Toast.makeText(YouTubeMusicActivity.this,
                                    "Added to Queue: " + it.title, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                row.addView(addQBtn);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (audioService == null) return;
                    if (isQueueView) {
                        audioService.startMorpheQueueAt(idx);
                    } else {
                        audioService.playMorpheQueue(items, idx, "YouTube Music",
                                "ca.arc7freedom.app.YouTubeMusicActivity");
                    }
                }
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(7);
            rightPaneList.addView(row, rlp);
        }
    }

    private void renderQueuePane() {
        activeRightMode = "queue";
        List<MorpheEngine.MediaItem> q = audioService != null
                ? audioService.getMorpheQueueSnapshot() : new ArrayList<MorpheEngine.MediaItem>();
        rightPaneHeaderTv.setText("\uD83D\uDCCB Up Next Queue (" + q.size() + " tracks)");
        renderTrackRows(q, true);
    }

    private void renderLyricsPane() {
        activeRightMode = "lyrics";
        rightPaneHeaderTv.setText("\uD83C\uDFA4 Synced Karaoke Lyrics (tap any line to jump!)");
        rightPaneList.removeAllViews();

        if (currentLyrics.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Fetching synced lyrics for current song...");
            tv.setTextColor(0xFFA1A1AA);
            tv.setPadding(dp(8), dp(12), dp(8), dp(12));
            rightPaneList.addView(tv);
            return;
        }
        long posMs = audioService != null ? audioService.getPositionMs() : 0L;
        int activeLine = findActiveLyricIndex(posMs);

        for (int i = 0; i < currentLyrics.size(); i++) {
            final MorpheEngine.LyricLine line = currentLyrics.get(i);
            TextView ltv = new TextView(this);
            ltv.setText(line.text);
            ltv.setTextSize(TypedValue.COMPLEX_UNIT_SP, i == activeLine ? 17f : 14f);
            ltv.setTypeface(i == activeLine ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            ltv.setTextColor(i == activeLine ? 0xFFFF4D6D : 0xFFD4D4D8);
            ltv.setPadding(dp(8), dp(7), dp(8), dp(7));
            if (line.timeMs >= 0L) {
                ltv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (audioService != null) audioService.seekAbsoluteMs((int) line.timeMs);
                    }
                });
            }
            rightPaneList.addView(ltv);
        }
    }

    private int findActiveLyricIndex(long posMs) {
        int active = -1;
        for (int i = 0; i < currentLyrics.size(); i++) {
            long t = currentLyrics.get(i).timeMs;
            if (t >= 0L && posMs >= t) active = i;
        }
        return active;
    }

    private void syncNowPlayingUi() {
        NowPlayingManager.Snapshot snap = NowPlayingManager.getSnapshot(null);
        if (audioService != null) {
            boolean resolving = audioService.isResolvingMorpheTrack();
            boolean playing = audioService.isPlaying();
            playPauseBtn.setText(resolving ? "\u23F3 Loading..." : (playing ? "\u23F8 Pause" : "\u25B6 Play"));
            shuffleBtn.setText(audioService.isShuffleEnabled() ? "\uD83D\uDD00 On" : "\uD83D\uDD00");
            int rep = audioService.getRepeatMode();
            repeatBtn.setText(rep == Arc7AudioService.REPEAT_ONE ? "\uD83D\uDD02 One"
                    : (rep == Arc7AudioService.REPEAT_ALL ? "\uD83D\uDD01 All" : "\uD83D\uDD01 Off"));

            int dur = audioService.getDurationMs();
            int pos = audioService.getPositionMs();
            if (!userScrubbing && dur > 0) {
                seekBar.setMax(dur);
                seekBar.setProgress(pos);
            }
            posTv.setText(NowPlayingManager.formatDurationMs(pos));
            durTv.setText(NowPlayingManager.formatDurationMs(dur));

            int activeLine = findActiveLyricIndex(pos);
            if (activeLine >= 0 && activeLine < currentLyrics.size()) {
                liveLyricBannerTv.setText("\u266A " + currentLyrics.get(activeLine).text);
            }
        }
        if (snap.active && snap.title.length() > 0) {
            trackTitleTv.setText(snap.title);
            trackArtistTv.setText(snap.artist.length() > 0 ? snap.artist : snap.sourceApp);
            Bitmap bmp = snap.artwork;
            if (bmp != null) coverArtIv.setImageBitmap(bmp);

            if (!snap.title.equals(loadedLyricsTrackTitle)) {
                loadedLyricsTrackTitle = snap.title;
                currentLyrics.clear();
                liveLyricBannerTv.setText("");
                final String tTitle = snap.title;
                final String tArtist = snap.artist;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<MorpheEngine.LyricLine> lrc =
                                MorpheEngine.fetchSyncedLyrics(YouTubeMusicActivity.this, tTitle, tArtist);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (tTitle.equals(loadedLyricsTrackTitle)) {
                                    currentLyrics.clear();
                                    currentLyrics.addAll(lrc);
                                    if ("lyrics".equals(activeRightMode)) renderLyricsPane();
                                }
                            }
                        });
                    }
                }).start();
            }
        }
    }

    private void showLikedSongs() {
        activeRightMode = "browse";
        List<MorpheEngine.MediaItem> liked = MorpheEngine.loadSavedList(this, "ytm_liked");
        rightPaneHeaderTv.setText("\u2764 Liked Songs (" + liked.size() + ")");
        renderTrackRows(liked, false);
    }

    private void showOfflineMusicLibrary() {
        activeRightMode = "browse";
        List<MorpheEngine.MediaItem> list = new ArrayList<MorpheEngine.MediaItem>();
        File dir = new File(Environment.getExternalStorageDirectory(), "Music/YouTubeMusic");
        File[] files = dir.exists() ? dir.listFiles() : null;
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isFile()) continue;
                MorpheEngine.MediaItem m = new MorpheEngine.MediaItem();
                m.videoId = "offline_" + i;
                m.title = files[i].getName();
                m.author = "Offline M4A (" + (files[i].length() / 1024L) + " KB)";
                m.localFilePath = files[i].getAbsolutePath();
                m.isMusicTrack = true;
                list.add(m);
            }
        }
        rightPaneHeaderTv.setText("\u2B07 Offline M4A Songs (" + list.size() + ")");
        renderTrackRows(list, false);
    }

    private void downloadCurrentSongOffline() {
        final MorpheEngine.MediaItem cur = audioService != null ? audioService.getCurrentMorpheItem() : null;
        if (cur == null || cur.videoId.startsWith("offline_")) return;
        Toast.makeText(this, "Downloading M4A track: " + cur.title, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MorpheEngine.ResolvedPlayback res =
                            MorpheEngine.resolveStreams(YouTubeMusicActivity.this, cur.videoId, true);
                    MorpheEngine.StreamVariant best = !res.audioStreams.isEmpty()
                            ? res.audioStreams.get(0)
                            : (!res.progressiveVideos.isEmpty()
                                    ? res.progressiveVideos.get(0) : res.videoOnlyStreams.get(0));
                    final File saved = MorpheEngine.downloadStreamOffline(
                            YouTubeMusicActivity.this, best, cur.title, true, null);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(YouTubeMusicActivity.this,
                                    "Saved M4A offline: " + saved.getName(), Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(YouTubeMusicActivity.this,
                                    "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void showEqualizerDialog() {
        int cur = audioService != null ? audioService.getCurrentEqPreset() : 0;
        new AlertDialog.Builder(this)
                .setTitle("\uD83C\uDF9A Morphe Hardware Equalizer & Bass Boost")
                .setSingleChoiceItems(Arc7AudioService.EQ_PRESET_NAMES, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (audioService != null) audioService.applyEqualizerPreset(which);
                        d.dismiss();
                        Toast.makeText(YouTubeMusicActivity.this,
                                "EQ Preset: " + Arc7AudioService.EQ_PRESET_NAMES[which], Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showSleepTimerDialog() {
        final int[] mins = new int[]{0, 15, 30, 45, 60, 90};
        String[] labels = new String[]{
                "Turn Off Sleep Timer",
                "15 Minutes (Smooth Fade-Out)",
                "30 Minutes (Smooth Fade-Out)",
                "45 Minutes (Smooth Fade-Out)",
                "60 Minutes (Smooth Fade-Out)",
                "90 Minutes (Smooth Fade-Out)"
        };
        new AlertDialog.Builder(this)
                .setTitle("\uD83C\uDF19 Bedside Sleep Timer")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Arc7AudioService.startSleepTimer(YouTubeMusicActivity.this, mins[which]);
                        Toast.makeText(YouTubeMusicActivity.this,
                                mins[which] == 0 ? "Sleep timer cancelled"
                                        : ("Sleep timer set for " + mins[which] + " minutes"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showMorpheMusicSettings() {
        final String[] opts = new String[]{
                "SponsorBlock Non-Music Intro/Outro Skip: "
                        + (MorpheEngine.isSponsorBlockEnabled(this) ? "ENABLED" : "OFF"),
                "Spoof Audio Stream Client: "
                        + MorpheEngine.SPOOF_CLIENT_LABELS[MorpheEngine.getPreferredSpoofClient(this)]
        };
        new AlertDialog.Builder(this)
                .setTitle("\u2728 YouTube Music Morphe Patches")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            MorpheEngine.setSponsorBlockEnabled(YouTubeMusicActivity.this,
                                    !MorpheEngine.isSponsorBlockEnabled(YouTubeMusicActivity.this));
                        } else {
                            new AlertDialog.Builder(YouTubeMusicActivity.this)
                                    .setTitle("Spoof Stream Client")
                                    .setSingleChoiceItems(MorpheEngine.SPOOF_CLIENT_LABELS,
                                            MorpheEngine.getPreferredSpoofClient(YouTubeMusicActivity.this),
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface d2, int idx) {
                                                    MorpheEngine.setPreferredSpoofClient(YouTubeMusicActivity.this, idx);
                                                    d2.dismiss();
                                                }
                                            })
                                    .show();
                        }
                    }
                })
                .setPositiveButton("Close", null)
                .show();
    }

    private Button pillBtn(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        b.setBackgroundDrawable(g);
        return b;
    }

    private LinearLayout.LayoutParams marginRightLp(int dpVal) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(dpVal);
        return lp;
    }

    @Override
    protected void onDestroy() {
        alive = false;
        ui.removeCallbacks(liveTicker);
        if (bound) {
            try { unbindService(conn); } catch (Throwable ignored) {}
            bound = false;
        }
        super.onDestroy();
    }
}
