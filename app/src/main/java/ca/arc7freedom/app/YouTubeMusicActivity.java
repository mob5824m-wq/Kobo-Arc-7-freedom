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
import java.util.Locale;

/**
 * Standalone YouTube Music client for Kobo Arc 7 (Android 4.x). Public access only;
 * local collections are not account sync and provider authentication denials are not bypassed.
 *
 * Authentic Morphe Pure-Black AMOLED YT Music experience:
 *   - Native `music.youtube.com` `WEB_REMIX` + `ANDROID_MUSIC 7.12` Morphe Stream Spoofer
 *   - True screen-off background playback (`PARTIAL_WAKE_LOCK` + High-Perf `WifiLock`) via `Arc7AudioService`
 *   - Millisecond-synced Karaoke Lyrics (`lrclib.net` + tap-to-seek!)
 *   - Up-Next Radio Mix Queue, Shuffle, Repeat, Liked Songs, 5-Band Hardware Equalizer, Sleep Timer & Offline `.m4a` Downloads
 *   - Full live Android Media Notification + Clock Standby Now-Playing integration!
 */
public class YouTubeMusicActivity extends Arc7Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alive = true;

    private Arc7AudioService audioService;
    private boolean bound = false;
    private String searchMode="songs";
    private Arc7DownloadUi downloads;
    private int musicRequest;
    private int quickPlayRequest=-1;
    private List<MorpheEngine.MediaItem> pendingMusicQueue;
    private int pendingMusicIndex;

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
    private String loadedLyricsKey = "";
    private int lyricsRequest;
    private Thread lyricsThread;
    private boolean lyricsLoading;
    private boolean userScrubbing = false;
    private String activeRightMode = "browse"; // "browse" | "queue" | "lyrics"

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!alive || !bound) return;
            audioService = ((Arc7AudioService.LocalBinder) binder).getService();
            if(pendingMusicQueue!=null) { List<MorpheEngine.MediaItem> queue=pendingMusicQueue; pendingMusicQueue=null; playItems(queue,pendingMusicIndex); }
            syncNowPlayingUi();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            audioService = null;
            // Registration remains bound until explicitly unbound, even after a process death.
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
        root.setBackgroundDrawable(Arc7Skin.pageDrawable(musicPalette()));

        Arc7MediaHeader header = new Arc7MediaHeader(this, musicPalette(), R.drawable.ic_ytmusic,
                "YouTube Music", "Search songs, artists, albums…", "Songs",
                new Runnable() { @Override public void run() { performMusicSearch(searchEt.getText().toString(), searchMode); } },
                new Runnable() { @Override public void run() { showHeaderOptions(); } });
        searchEt = header.getSearchInput();
        final Button searchType = header.getModeButton();
        searchType.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            final String[] modes = {"songs", "artists", "albums", "playlists", "community_playlists"};
            final String[] labels = {"Songs", "Artists", "Albums", "Playlists", "Community playlists"};
            new AlertDialog.Builder(YouTubeMusicActivity.this).setTitle("Search type")
                    .setSingleChoiceItems(labels, java.util.Arrays.asList(modes).indexOf(searchMode), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            searchMode = modes[which];
                            searchType.setText((which == 4 ? "Community" : labels[which]) + " ▾");
                            searchType.setContentDescription("Search type: " + labels[which]);
                            searchEt.setHint("Search " + labels[which].toLowerCase(Locale.US) + "…");
                            dialog.dismiss();
                            performMusicSearch(searchEt.getText().toString(), searchMode);
                        }
                    }).setNegativeButton("Cancel", null).show();
        } });
        root.addView(header);

        // Mood / Quick Picks / Library Filter Bar
        HorizontalScrollView moodScroll = new HorizontalScrollView(this);
        moodScroll.setHorizontalScrollBarEnabled(false);
        moodScroll.setBackgroundColor(0x00000000);
        LinearLayout moodRow = new LinearLayout(this);
        moodRow.setOrientation(LinearLayout.HORIZONTAL);
        moodRow.setPadding(dp(10), dp(6), dp(10), dp(6));

        final String[][] moods = new String[][]{
                {"▶ Quick play", "@quickplay"},
                {"My playlists & albums", "@playlists"},
                {"+ New playlist", "@newplaylist"},
                {"Play results", "@playall"},
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
                    if ("@quickplay".equals(q)) quickPlay();
                    else if ("@playlists".equals(q)) showMusicPlaylists();
                    else if ("@newplaylist".equals(q)) createMusicPlaylist(null);
                    else if ("@playall".equals(q)) playItems(browseResults,0);
                    else if ("@liked".equals(q)) showLikedSongs();
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
        playerDeck.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, musicPalette(), 28));
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
        bound=bindService(svcIntent, conn, Context.BIND_AUTO_CREATE);
        ui.postDelayed(liveTicker, 500L);

        if (getIntent() != null && getIntent().getData() != null) {
            String vid = MorpheEngine.extractVideoIdFromUrlOrQuery(getIntent().getDataString());
            if (vid != null) {
                final MorpheEngine.MediaItem single = new MorpheEngine.MediaItem();
                single.videoId = vid;
                single.title = "Loading Track...";
                single.isMusicTrack = true;
                List<MorpheEngine.MediaItem> queue=new ArrayList<MorpheEngine.MediaItem>(); queue.add(single); playItems(queue,0);
            }
        }
        performMusicSearch("top hits music 2026", "songs");
    }


    /** Launch the video APK with the same URL; never run a second copy in this app. */
    private void openYouTubeApp(String watchUrl) {
        Intent intent = new Intent(this, YouTubeActivity.class);
        intent.setData(android.net.Uri.parse(watchUrl));
        Arc7UiAnimator.startAnimated(this, intent);
    }

    private void showHeaderOptions() {
        new AlertDialog.Builder(this).setTitle("YouTube Music options")
                .setItems(new String[]{"Equalizer", "Sleep timer", "Morphe / playback settings", "Open YouTube", "Playback diagnostics / sound test", "App tools · logs, RAM & storage"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showEqualizerDialog();
                        else if (which == 1) showSleepTimerDialog();
                        else if (which == 2) showMorpheMusicSettings();
                        else if (which == 3) openYouTubeApp("https://www.youtube.com/");
                        else if (which == 4) startActivity(new Intent(YouTubeMusicActivity.this, Arc7PlaybackDiagnosticsActivity.class));
                        else ca.arc7freedom.support.AppSupport.open(YouTubeMusicActivity.this);
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void performMusicSearch(final String query, final String mode) {
        if (query == null || query.trim().isEmpty()) return;
        final String clean = query.trim();
        final int request=++musicRequest;
        String directVid = "artists".equals(mode) ? null : MorpheEngine.extractVideoIdFromUrlOrQuery(clean);
        if (directVid != null) {
            MorpheEngine.MediaItem single = new MorpheEngine.MediaItem();
            single.videoId = directVid;
            single.title = "Playing Track";
            single.isMusicTrack = true;
            List<MorpheEngine.MediaItem> q = new ArrayList<MorpheEngine.MediaItem>();
            q.add(single);
            playItems(q,0);
            return;
        }
        activeRightMode = "browse";
        browseResults.clear(); rightPaneList.removeAllViews();
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
                            if(!alive || request!=musicRequest) return;
                            rightPaneHeaderTv.setText(mode.replace('_',' ') + " · " + items.size() + (items.isEmpty() ? " public results. Try another query." : " results"));
                            browseResults.clear();
                            browseResults.addAll(items);
                            renderTrackRows(items, false);
                            if(quickPlayRequest==request) { quickPlayRequest=-1; playItems(items,0); }
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if(!alive || request!=musicRequest) return;
                            quickPlayRequest=-1; rightPaneHeaderTv.setText("Music search unavailable: "+t.getMessage());
                            rightPaneList.removeAllViews();
                        }
                    });
                }
            }
        }).start();
    }

    private void renderTrackRows(List<MorpheEngine.MediaItem> source, final boolean isQueueView) {
        final List<MorpheEngine.MediaItem> items=new ArrayList<MorpheEngine.MediaItem>(source);
        rightPaneList.removeAllViews();
        if(!isQueueView) { browseResults.clear(); browseResults.addAll(new ArrayList<MorpheEngine.MediaItem>(items)); }
        int activeIdx = audioService != null ? audioService.getMorpheQueueIndex() : -1;

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final MorpheEngine.MediaItem it = items.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));

            GradientDrawable bg = (isQueueView && idx == activeIdx)
                    ? Arc7Skin.buttonDrawable(this, 0xFF58152B, 24)
                    : Arc7Skin.cardDrawable(this, musicPalette(), 24);
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

            String sub = it.isArtist() ? "ARTIST" + (it.author.length() == 0 ? "" : " · " + it.author)
                    : it.isCollection() ? it.collectionType.toUpperCase(Locale.US)+" · "+it.author : it.author;
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
                Button addQBtn = pillBtn(it.isBrowsable() ? "Open" : "+ Queue", 0xFF27272A);
                addQBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(it.isArtist()) { openMusicArtist(it); return; }
                        if(it.isCollection()) { openMusicCollection(it); return; }
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
                    if(it.isArtist()) { openMusicArtist(it); return; }
                    if(it.isCollection()) { openMusicCollection(it); return; }
                    if (isQueueView && audioService != null) {
                        audioService.startMorpheQueueAt(idx);
                    } else {
                        playItems(items,idx);
                    }
                }
            });

            row.setOnLongClickListener(new View.OnLongClickListener(){ public boolean onLongClick(View v){
                if(it.isArtist()) { openMusicArtist(it); return true; }
                if(it.isCollection()) {
                    MorpheEngine.addOrToggleSavedItem(YouTubeMusicActivity.this,"ytm_playlists",it,true);
                    Toast.makeText(YouTubeMusicActivity.this,"Updated saved playlists & albums",Toast.LENGTH_SHORT).show();
                } else chooseMusicPlaylist(it);
                return true;
            }});
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(7);
            rightPaneList.addView(row, rlp);
        }
    }

    private void playItems(List<MorpheEngine.MediaItem> items,int start) {
        List<MorpheEngine.MediaItem> playable=new ArrayList<MorpheEngine.MediaItem>(); int selected=0;
        for(int i=0;i<items.size();i++) { MorpheEngine.MediaItem item=items.get(i); if(!item.isBrowsable() && (item.videoId.length()>0 || item.localFilePath.length()>0)) { if(i==start) selected=playable.size(); playable.add(item); } }
        if(playable.isEmpty()) { Toast.makeText(this,"Open an album or playlist to load its songs first.",Toast.LENGTH_LONG).show(); return; }
        if(audioService==null) { pendingMusicQueue=playable; pendingMusicIndex=selected; return; }
        audioService.playMorpheQueue(playable,selected,"YouTube Music","ca.arc7freedom.app.YouTubeMusicActivity");
    }
    private void quickPlay() {
        List<MorpheEngine.MediaItem> liked=MorpheEngine.loadSavedList(this,"ytm_liked");
        if(!liked.isEmpty()) { playItems(liked,0); return; }
        if(!browseResults.isEmpty() && !browseResults.get(0).isBrowsable()) { playItems(browseResults,0); return; }
        performMusicSearch("popular music official audio","songs"); quickPlayRequest=musicRequest;
    }
    private void showMusicPlaylists() {
        ++musicRequest; activeRightMode="browse";
        List<MorpheEngine.MediaItem> saved=MorpheEngine.loadSavedList(this,"ytm_playlists");
        rightPaneHeaderTv.setText("My playlists & albums · "+saved.size()+" · long-press a song to add it; long-press a collection to remove it");
        renderTrackRows(saved,false);
    }
    private void openMusicArtist(final MorpheEngine.MediaItem artist) {
        final int request = ++musicRequest; quickPlayRequest = -1; activeRightMode = "browse";
        final List<MorpheEngine.MediaItem> previous = new ArrayList<MorpheEngine.MediaItem>(browseResults);
        browseResults.clear(); rightPaneList.removeAllViews(); rightPaneHeaderTv.setText("Loading artist · " + artist.title + "…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                final List<MorpheEngine.MediaItem> items = MorpheEngine.browseMusicArtist(YouTubeMusicActivity.this, artist);
                ui.post(new Runnable() { @Override public void run() {
                    if (!alive || request != musicRequest) return;
                    rightPaneHeaderTv.setText(artist.title + " · songs & releases (up to 60 public items) · tap an album to open");
                    renderTrackRows(items, false);
                    Button back = pillBtn("← Back to artist results", 0xFF334155);
                    back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                        ++musicRequest; quickPlayRequest = -1;
                        rightPaneHeaderTv.setText("Artists · " + previous.size() + " results"); renderTrackRows(previous, false);
                    } });
                    rightPaneList.addView(back, 0);
                } });
            } catch (final Exception error) { ui.post(new Runnable() { @Override public void run() {
                if (!alive || request != musicRequest) return;
                browseResults.clear(); rightPaneList.removeAllViews(); rightPaneHeaderTv.setText("Artist unavailable: " + error.getMessage());
                Button back = pillBtn("← Back to artist results", 0xFF334155);
                back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    ++musicRequest; rightPaneHeaderTv.setText("Artists · " + previous.size() + " results"); renderTrackRows(previous, false);
                } }); rightPaneList.addView(back);
            } }); }
        } }, "Arc7-Music-Artist").start();
    }

    private void openMusicCollection(final MorpheEngine.MediaItem item) {
        final int request=++musicRequest; activeRightMode="browse";
        browseResults.clear(); rightPaneList.removeAllViews(); rightPaneHeaderTv.setText("Loading "+item.title+"…");
        new Thread(new Runnable(){ public void run(){
            try {
                final List<MorpheEngine.MediaItem> tracks=MorpheEngine.browseMusicCollection(YouTubeMusicActivity.this,item);
                ui.post(new Runnable(){ public void run(){ if(!alive || request!=musicRequest) return;
                    rightPaneHeaderTv.setText(item.title+" · "+tracks.size()+" loaded (first 60 public tracks) · Play results starts the queue");
                    renderTrackRows(tracks,false);
                    Button save=pillBtn("Save album / playlist to library",0xFF334155); save.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                        MorpheEngine.addOrToggleSavedItem(YouTubeMusicActivity.this,"ytm_playlists",item,false);
                        Toast.makeText(YouTubeMusicActivity.this,"Saved collection",Toast.LENGTH_SHORT).show();
                    }}); rightPaneList.addView(save,0);
                }});
            } catch(final Exception e){ ui.post(new Runnable(){ public void run(){ if(alive && request==musicRequest) { rightPaneList.removeAllViews(); rightPaneHeaderTv.setText("Collection unavailable: "+e.getMessage()); } }}); }
        }},"Arc7-Music-Collection").start();
    }
    private void createMusicPlaylist(final MorpheEngine.MediaItem first) {
        final EditText title=new EditText(this); title.setHint("Playlist name"); title.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("New local playlist").setView(title).setPositiveButton("Create",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
            String name=title.getText().toString().trim(); if(name.length()==0 || name.length()>100) { Toast.makeText(YouTubeMusicActivity.this,"Use a name of 1–100 characters",Toast.LENGTH_LONG).show(); return; }
            MorpheEngine.MediaItem playlist=new MorpheEngine.MediaItem(); playlist.title=name; playlist.playlistId="local:"+java.util.UUID.randomUUID().toString(); playlist.collectionType="playlist";
            MorpheEngine.addOrToggleSavedItem(YouTubeMusicActivity.this,"ytm_playlists",playlist,false);
            List<MorpheEngine.MediaItem> songs=new ArrayList<MorpheEngine.MediaItem>(); if(first!=null) songs.add(first);
            MorpheEngine.saveList(YouTubeMusicActivity.this,"ytm_local_"+playlist.playlistId.substring(6),songs); showMusicPlaylists();
        }}).setNegativeButton("Cancel",null).show();
    }
    private void chooseMusicPlaylist(final MorpheEngine.MediaItem song) {
        final List<MorpheEngine.MediaItem> local=new ArrayList<MorpheEngine.MediaItem>();
        for(MorpheEngine.MediaItem item:MorpheEngine.loadSavedList(this,"ytm_playlists")) if(item.playlistId.startsWith("local:")) local.add(item);
        String[] labels=new String[local.size()+1]; labels[0]="+ New local playlist"; for(int i=0;i<local.size();i++) labels[i+1]=local.get(i).title;
        new AlertDialog.Builder(this).setTitle("Add song to playlist").setItems(labels,new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
            if(which==0) createMusicPlaylist(song);
            else { MorpheEngine.addOrToggleSavedItem(YouTubeMusicActivity.this,"ytm_local_"+local.get(which-1).playlistId.substring(6),song,false); Toast.makeText(YouTubeMusicActivity.this,"Added to local playlist",Toast.LENGTH_SHORT).show(); }
        }}).setNegativeButton("Cancel",null).show();
    }

    private void renderQueuePane() {
        ++musicRequest; quickPlayRequest=-1;
        activeRightMode = "queue";
        List<MorpheEngine.MediaItem> q = audioService != null
                ? audioService.getMorpheQueueSnapshot() : new ArrayList<MorpheEngine.MediaItem>();
        rightPaneHeaderTv.setText("\uD83D\uDCCB Up Next Queue (" + q.size() + " tracks)");
        renderTrackRows(q, true);
    }

    private void renderLyricsPane() {
        ++musicRequest; quickPlayRequest=-1;
        activeRightMode = "lyrics";
        rightPaneHeaderTv.setText("\uD83C\uDFA4 Synced Karaoke Lyrics (tap any line to jump!)");
        rightPaneList.removeAllViews();

        if (currentLyrics.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(lyricsLoading ? "Fetching lyrics for this song…" : "No lyrics available for this song. Lyrics depend on provider coverage.");
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
        if (!alive) return;
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
        if (snap.active && getPackageName().equals(snap.sourcePkg) && snap.title.length() > 0) {
            trackTitleTv.setText(snap.title);
            trackArtistTv.setText(snap.artist.length() > 0 ? snap.artist : snap.sourceApp);
            Bitmap bmp = snap.artwork;
            if (bmp != null) coverArtIv.setImageBitmap(bmp);

            MorpheEngine.MediaItem current=audioService==null ? null : audioService.getCurrentMorpheItem();
            final String key=(current==null ? "" : current.videoId+"\n"+current.localFilePath)+"\n"+snap.title+"\n"+snap.artist;
            if (!key.equals(loadedLyricsKey)) {
                resetLyrics(); loadedLyricsKey=key; lyricsLoading=true;
                final int request=lyricsRequest;
                final String tTitle=snap.title, tArtist=snap.artist;
                final Context app=getApplicationContext();
                lyricsThread=new Thread(new Runnable() {
                    @Override public void run() {
                        final List<MorpheEngine.LyricLine> lrc=MorpheEngine.fetchSyncedLyrics(app,tTitle,tArtist);
                        if (Thread.currentThread().isInterrupted()) return;
                        ui.post(new Runnable() { @Override public void run() {
                            if (!alive || request!=lyricsRequest || !key.equals(loadedLyricsKey)) return;
                            lyricsLoading=false; lyricsThread=null;
                            currentLyrics.clear(); currentLyrics.addAll(lrc);
                            if ("lyrics".equals(activeRightMode)) renderLyricsPane();
                        }});
                    }
                },"Arc7-Music-Lyrics"); lyricsThread.start();
            }
        } else if (loadedLyricsKey.length()>0) resetLyrics();
        if (audioService != null && audioService.getPlaybackStatus().length() > 0) {
            MorpheEngine.MediaItem pending = audioService.getCurrentMorpheItem();
            if (pending != null) trackTitleTv.setText(pending.title);
            trackArtistTv.setText(audioService.getPlaybackStatus());
        }
    }

    private void resetLyrics() {
        ++lyricsRequest;
        if (lyricsThread!=null) lyricsThread.interrupt(); lyricsThread=null;
        lyricsLoading=false; loadedLyricsKey=""; currentLyrics.clear();
        if (liveLyricBannerTv!=null) liveLyricBannerTv.setText("");
    }

    private void showLikedSongs() {
        ++musicRequest; quickPlayRequest=-1;
        activeRightMode = "browse";
        List<MorpheEngine.MediaItem> liked = MorpheEngine.loadSavedList(this, "ytm_liked");
        rightPaneHeaderTv.setText("\u2764 Liked Songs (" + liked.size() + ")");
        renderTrackRows(liked, false);
    }

    private void showOfflineMusicLibrary() {
        ++musicRequest; quickPlayRequest=-1;
        activeRightMode = "browse";
        List<MorpheEngine.MediaItem> list = new ArrayList<MorpheEngine.MediaItem>();
        File dir = new File(Environment.getExternalStorageDirectory(), "Music/YouTubeMusic");
        File[] files = dir.exists() ? dir.listFiles() : null;
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isFile() || !files[i].getName().toLowerCase(Locale.US).endsWith(".m4a")) continue;
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
        final MorpheEngine.MediaItem song=audioService==null ? null : audioService.getCurrentMorpheItem();
        if(song==null || song.localFilePath.length()>0) {
            Toast.makeText(this,"Play an online song before downloading M4A.",Toast.LENGTH_LONG).show(); return;
        }
        if(downloads==null) downloads=new Arc7DownloadUi(this);
        downloads.start(song.title,new Arc7DownloadUi.Task(){
            @Override public File run(MorpheEngine.DownloadProgressListener progress)throws Exception {
                MorpheEngine.ResolvedPlayback resolved=MorpheEngine.resolveStreams(YouTubeMusicActivity.this,song.videoId,true);
                try { return MorpheEngine.downloadResolvedOffline(YouTubeMusicActivity.this,resolved,song.title,true,progress); }
                finally { MorpheEngine.cancelPlayback(resolved); }
            }
        });
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

    private Arc7Skin.Palette musicPalette() {
        Arc7Skin.Palette p = Arc7Skin.current(this);
        // Music's artwork/transport typography stays white, including under the daylight OS skin.
        return p.lightMode ? Arc7Skin.paletteByIndex(0) : p;
    }

    private Button pillBtn(String label,int color) {
        Button button=Arc7Skin.actionButton(this,label,color); button.setTextSize(TypedValue.COMPLEX_UNIT_SP,12f); return button;
    }

    private LinearLayout.LayoutParams marginRightLp(int dpVal) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(dpVal);
        return lp;
    }

    @Override
    protected void onDestroy() {
        alive = false; ++musicRequest; quickPlayRequest=-1; pendingMusicQueue=null;
        resetLyrics();
        if(downloads!=null) downloads.close();
        ui.removeCallbacksAndMessages(null);
        if (bound) {
            try { unbindService(conn); } catch (Throwable ignored) {}
            bound = false;
        }
        audioService=null;
        super.onDestroy();
    }
}
