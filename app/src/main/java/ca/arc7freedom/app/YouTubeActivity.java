package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone Native `YouTube` App (Morphe Edition — `v2.4.0`) for Kobo Arc 7 (`Android 4.2.2`).
 *
 * Solves EVERY failure of legacy YouTube & WebView on Android 4.2:
 *   - Multi-client Morphe InnerTube Stream Spoofer (`Android VR 1.64`, `visionOS 1.03`, `Android XR 1.69`,
 *     `Android Music 7.12`, `iOS 21.26`, + Cloud Piped/Invidious failover)
 *   - Hardware-accelerated H.264/AAC MP4 playback over local `206 Partial Content` TLS 1.3 Range Proxy
 *   - Ad-free Home / Trending / Search / Subscriptions / Watch History / Offline MP4 Downloads
 *   - Live SponsorBlock auto-skip + Return YouTube Dislike counts + Comments + Screen-Off Background Play!
 */
public class YouTubeActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alive = true;

    private FrameLayout rootStack;
    private LinearLayout mainChrome;
    private FrameLayout playerStage;
    private VideoView videoView;
    private TextView playerOverlayStatusTv;
    private SeekBar seekBar;
    private TextView timeLabelTv;
    private Button playPauseBtn;
    private Button qualityBtn;
    private Button fullscreenBtn;

    private TextView videoTitleTv;
    private TextView videoChannelTv;
    private TextView rydStatsTv;
    private EditText searchEt;
    private LinearLayout feedList;
    private TextView feedHeaderTv;

    private final List<MorpheEngine.MediaItem> currentFeed = new ArrayList<MorpheEngine.MediaItem>();
    private int currentFeedIdx = -1;
    private MorpheEngine.ResolvedPlayback currentPlayback = null;
    private volatile String activeResolutionVideoId = "";
    private boolean userScrubbing = false;
    private boolean isFullscreen = false;
    private boolean handedOffToBackgroundAudio = false;

    private static final Map<String, Bitmap> THUMB_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 64;
                }
            });

    private final BroadcastReceiver fgControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String cmd = intent != null ? intent.getStringExtra("cmd") : null;
            if (cmd == null) return;
            if (Arc7AudioService.ACTION_TOGGLE.equals(cmd)) {
                toggleForegroundVideo();
            } else if (Arc7AudioService.ACTION_PREV.equals(cmd)) {
                stepVideoInFeed(-1);
            } else if (Arc7AudioService.ACTION_NEXT.equals(cmd)) {
                stepVideoInFeed(1);
            } else if (Arc7AudioService.ACTION_REWIND_30.equals(cmd)) {
                seekVideoRelative(-15000);
            } else if (Arc7AudioService.ACTION_FFWD_30.equals(cmd)) {
                seekVideoRelative(30000);
            } else if (Arc7AudioService.ACTION_STOP_DISMISS.equals(cmd)) {
                stopForegroundVideo();
            }
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            try {
                if (videoView != null && currentPlayback != null && NowPlayingManager.isYouTubeForegroundVideoActive()) {
                    int pos = Math.max(0, videoView.getCurrentPosition());
                    int dur = Math.max(0, videoView.getDuration());
                    boolean playing = videoView.isPlaying();
                    playPauseBtn.setText(playing ? "\u23F8 Pause" : "\u25B6 Play");

                    if (!userScrubbing && dur > 0) {
                        seekBar.setMax(dur);
                        seekBar.setProgress(pos);
                    }
                    timeLabelTv.setText(NowPlayingManager.formatDurationMs(pos)
                            + " / " + NowPlayingManager.formatDurationMs(dur > 0 ? dur : currentPlayback.durationSec * 1000L));

                    // Real-time SponsorBlock segment auto-skip!
                    if (playing && MorpheEngine.isSponsorBlockEnabled(YouTubeActivity.this)) {
                        float sec = pos / 1000f;
                        synchronized (currentPlayback.skipSegments) {
                            for (int i = 0; i < currentPlayback.skipSegments.size(); i++) {
                                MorpheEngine.SkipSegment seg = currentPlayback.skipSegments.get(i);
                                if (sec >= seg.startSec && sec < seg.endSec - 0.7f) {
                                    int jumpMs = (int) (seg.endSec * 1000f);
                                    videoView.seekTo(jumpMs);
                                    Toast.makeText(YouTubeActivity.this,
                                            "\u23ED SponsorBlock skipped " + seg.category
                                                    + " (" + Math.round(seg.endSec - seg.startSec) + "s)",
                                            Toast.LENGTH_SHORT).show();
                                    break;
                                }
                            }
                        }
                    }

                    // Publish live audio state to Android Notification & Clock Standby Display!
                    NowPlayingManager.publishBuiltIn(
                            YouTubeActivity.this,
                            "YouTube",
                            "ca.arc7freedom.app.YouTubeActivity",
                            currentPlayback.title,
                            currentPlayback.author,
                            currentPlayback.spoofClientUsed,
                            currentPlayback.thumbnailUrl,
                            null,
                            playing,
                            pos,
                            dur > 0 ? dur : currentPlayback.durationSec * 1000L);
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 1000L);
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

        rootStack = new FrameLayout(this);
        rootStack.setBackgroundColor(0xFF09090D);

        mainChrome = new LinearLayout(this);
        mainChrome.setOrientation(LinearLayout.VERTICAL);
        mainChrome.setBackgroundColor(0xFF09090D);

        // Top Bar: Logo + Search Input + Search Button + YT Music Switch + Morphe Patches Settings
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF12121A);
        topBar.setPadding(dp(10), dp(7), dp(10), dp(7));

        ImageView iconIv = new ImageView(this);
        iconIv.setImageResource(R.drawable.ic_youtube);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
        ilp.rightMargin = dp(8);
        topBar.addView(iconIv, ilp);

        TextView brandTv = new TextView(this);
        brandTv.setText("YouTube");
        brandTv.setTextColor(Color.WHITE);
        brandTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        brandTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = dp(10);
        topBar.addView(brandTv, blp);

        searchEt = new EditText(this);
        searchEt.setHint("Search YouTube or paste video link...");
        searchEt.setHintTextColor(0xFF71717A);
        searchEt.setTextColor(Color.WHITE);
        searchEt.setSingleLine(true);
        searchEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        searchEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xFF1C1C26);
        sbg.setCornerRadius(dp(18));
        sbg.setStroke(dp(1), 0xFF2E2E3E);
        searchEt.setBackgroundDrawable(sbg);
        searchEt.setPadding(dp(14), dp(7), dp(14), dp(7));
        searchEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                runUserSearchOrUrl(searchEt.getText().toString());
                return true;
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.rightMargin = dp(6);
        topBar.addView(searchEt, slp);

        Button goBtn = pillBtn("\uD83D\uDD0D Search", 0xFFE11D48);
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runUserSearchOrUrl(searchEt.getText().toString()); }
        });
        topBar.addView(goBtn, marginRightLp(6));

        Button ytmBtn = pillBtn("\uD83C\uDFB5 YT Music", 0xFF27272A);
        ytmBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openYouTubeMusicApp();
            }
        });
        topBar.addView(ytmBtn, marginRightLp(6));

        Button morpheBtn = pillBtn("\u2728 Morphe", 0xFF059669);
        morpheBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMorphePatchesDialog(); }
        });
        topBar.addView(morpheBtn);
        mainChrome.addView(topBar);

        // Category & Library Shelf Bar
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(0xFF0E0E14);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(10), dp(6), dp(10), dp(6));

        final String[][] shelves = new String[][]{
                {"\uD83D\uDD25 Trending", ""},
                {"\uD83C\uDFB6 Music Videos", "official music videos 2026"},
                {"\uD83C\uDFAE Gaming", "gaming highlights walkthrough"},
                {"\uD83D\uDCBB Tech & Coding", "technology hardware review"},
                {"\uD83D\uDD2C Science", "science documentary space"},
                {"\uD83D\uDCF0 News Live", "world news live"},
                {"\u2B50 Saved", "@saved"},
                {"\uD83D\uDD53 History", "@history"},
                {"\u2B07 Downloads", "@downloads"}
        };
        for (int i = 0; i < shelves.length; i++) {
            final String label = shelves[i][0];
            final String q = shelves[i][1];
            Button chip = pillBtn(label, 0xFF1E1E2A);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("@saved".equals(q)) showLocalSavedShelf("yt_saved", "Saved Videos & Channels");
                    else if ("@history".equals(q)) showLocalSavedShelf("yt_history", "Watch History");
                    else if ("@downloads".equals(q)) showOfflineDownloadsShelf();
                    else loadTopicFeedAsync(q, label);
                }
            });
            chipRow.addView(chip, marginRightLp(6));
        }
        chipScroll.addView(chipRow);
        mainChrome.addView(chipScroll);

        // Responsive Split Body: Video Stage + Controls on Top/Left + Feed/Related List Below
        ScrollView pageScroll = new ScrollView(this);
        LinearLayout pageBody = new LinearLayout(this);
        pageBody.setOrientation(LinearLayout.VERTICAL);

        // Hardware H.264 Video Stage
        playerStage = new FrameLayout(this);
        playerStage.setBackgroundColor(Color.BLACK);
        int stageHeight = Math.min(dp(275), getResources().getDisplayMetrics().heightPixels * 46 / 100);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(200), stageHeight));

        videoView = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        playerStage.addView(videoView, vlp);

        playerOverlayStatusTv = new TextView(this);
        playerOverlayStatusTv.setText("Tap any video below or search to watch ad-free with SponsorBlock + Morphe");
        playerOverlayStatusTv.setTextColor(0xFFE4E4E7);
        playerOverlayStatusTv.setGravity(Gravity.CENTER);
        playerOverlayStatusTv.setPadding(dp(20), dp(16), dp(20), dp(16));
        playerOverlayStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        playerStage.addView(playerOverlayStatusTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        pageBody.addView(playerStage, stageLp);

        // Transport & Scrubber Bar
        LinearLayout transportBar = new LinearLayout(this);
        transportBar.setOrientation(LinearLayout.VERTICAL);
        transportBar.setBackgroundColor(0xFF14141D);
        transportBar.setPadding(dp(12), dp(6), dp(12), dp(8));

        LinearLayout scrubRow = new LinearLayout(this);
        scrubRow.setOrientation(LinearLayout.HORIZONTAL);
        scrubRow.setGravity(Gravity.CENTER_VERTICAL);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && currentPlayback != null) {
                    timeLabelTv.setText(NowPlayingManager.formatDurationMs(progress)
                            + " / " + NowPlayingManager.formatDurationMs(sb.getMax()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userScrubbing = true; }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userScrubbing = false;
                if (videoView != null && currentPlayback != null) {
                    videoView.seekTo(sb.getProgress());
                }
            }
        });
        scrubRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        timeLabelTv = new TextView(this);
        timeLabelTv.setText("0:00 / 0:00");
        timeLabelTv.setTextColor(0xFFA1A1AA);
        timeLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        timeLabelTv.setPadding(dp(8), 0, 0, 0);
        scrubRow.addView(timeLabelTv);
        transportBar.addView(scrubRow);

        HorizontalScrollView btnScroll = new HorizontalScrollView(this);
        btnScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setPadding(0, dp(4), 0, 0);

        Button prevBtn = pillBtn("\u23EE Prev", 0xFF27272A);
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stepVideoInFeed(-1); }
        });
        ctrlRow.addView(prevBtn, marginRightLp(6));

        playPauseBtn = pillBtn("\u25B6 Play", 0xFFE11D48);
        playPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleForegroundVideo(); }
        });
        ctrlRow.addView(playPauseBtn, marginRightLp(6));

        Button nextBtn = pillBtn("\u23ED Next", 0xFF27272A);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stepVideoInFeed(1); }
        });
        ctrlRow.addView(nextBtn, marginRightLp(6));

        Button bgPlayBtn = pillBtn("\uD83C\uDFA7 Background Audio", 0xFF7C3AED);
        bgPlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchCurrentToBackgroundAudio(true); }
        });
        ctrlRow.addView(bgPlayBtn, marginRightLp(6));

        qualityBtn = pillBtn("\uD83C\uDF9E Auto HD", 0xFF1F2937);
        qualityBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showQualityPickerDialog(); }
        });
        ctrlRow.addView(qualityBtn, marginRightLp(6));

        Button saveBtn = pillBtn("\u2B50 Save", 0xFF1F2937);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPlayback == null) return;
                MorpheEngine.MediaItem m = toMediaItem(currentPlayback);
                MorpheEngine.addOrToggleSavedItem(YouTubeActivity.this, "yt_saved", m, true);
                Toast.makeText(YouTubeActivity.this, "Updated Saved list!", Toast.LENGTH_SHORT).show();
            }
        });
        ctrlRow.addView(saveBtn, marginRightLp(6));

        Button commentsBtn = pillBtn("\uD83D\uDCAC Comments", 0xFF1F2937);
        commentsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCommentsDialog(); }
        });
        ctrlRow.addView(commentsBtn, marginRightLp(6));

        Button dlBtn = pillBtn("\u2B07 Download MP4/M4A", 0xFF0284C7);
        dlBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptOfflineDownload(); }
        });
        ctrlRow.addView(dlBtn, marginRightLp(6));

        fullscreenBtn = pillBtn("\u26F6 Fullscreen", 0xFF0F766E);
        fullscreenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFullscreenMode(); }
        });
        ctrlRow.addView(fullscreenBtn);

        btnScroll.addView(ctrlRow);
        transportBar.addView(btnScroll);

        // Video Metadata + Return YouTube Dislike Stats
        videoTitleTv = new TextView(this);
        videoTitleTv.setText("Welcome to YouTube (Morphe Edition)");
        videoTitleTv.setTextColor(Color.WHITE);
        videoTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        videoTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        videoTitleTv.setPadding(0, dp(8), 0, dp(2));
        transportBar.addView(videoTitleTv);

        videoChannelTv = new TextView(this);
        videoChannelTv.setText("Ad-Free  \u2022  SponsorBlock Enabled  \u2022  Return YouTube Dislike  \u2022  Background Audio");
        videoChannelTv.setTextColor(0xFFA1A1AA);
        videoChannelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        videoChannelTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPlayback != null && currentPlayback.author.length() > 0) {
                    searchEt.setText(currentPlayback.author);
                    runUserSearchOrUrl(currentPlayback.author);
                }
            }
        });
        transportBar.addView(videoChannelTv);

        rydStatsTv = new TextView(this);
        rydStatsTv.setTextColor(0xFF34D399);
        rydStatsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        rydStatsTv.setPadding(0, dp(3), 0, 0);
        transportBar.addView(rydStatsTv);

        pageBody.addView(transportBar);

        feedHeaderTv = new TextView(this);
        feedHeaderTv.setText("\uD83D\uDD25 Trending Videos");
        feedHeaderTv.setTextColor(0xFFF4F4F5);
        feedHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        feedHeaderTv.setTypeface(Typeface.DEFAULT_BOLD);
        feedHeaderTv.setPadding(dp(14), dp(10), dp(14), dp(6));
        pageBody.addView(feedHeaderTv);

        feedList = new LinearLayout(this);
        feedList.setOrientation(LinearLayout.VERTICAL);
        feedList.setPadding(dp(10), 0, dp(10), dp(20));
        pageBody.addView(feedList);

        pageScroll.addView(pageBody);
        mainChrome.addView(pageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootStack.addView(mainChrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                playerOverlayStatusTv.setVisibility(View.GONE);
                NowPlayingManager.setYouTubeForegroundVideoActive(true);
                videoView.start();
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                stepVideoInFeed(1);
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                // Automatic hardware fallback: if progressive stream hiccups, switch seamlessly to audio+artwork stream!
                if (currentPlayback != null && currentPlayback.localAudioProxyUrl.length() > 0) {
                    playerOverlayStatusTv.setVisibility(View.VISIBLE);
                    playerOverlayStatusTv.setText("\uD83C\uDFA7 Playing Morphe Direct AAC Audio Stream\n" + currentPlayback.title);
                    switchCurrentToBackgroundAudio(false);
                    return true;
                }
                playerOverlayStatusTv.setVisibility(View.VISIBLE);
                playerOverlayStatusTv.setText("Playback error (" + what + ", " + extra + ") — tap \u2728 Morphe to try another client"
                        + (currentPlayback != null && currentPlayback.muxNote.length() > 0
                        ? ("\nLast mux: " + currentPlayback.muxNote) : ""));
                return true;
            }
        });

        setContentView(rootStack);
        Arc7UiAnimator.attachActivityAnimations(this);

        registerReceiver(fgControlReceiver, new IntentFilter(NowPlayingManager.ACTION_YOUTUBE_FG_CONTROL));
        ui.postDelayed(progressTicker, 500L);

        handleIncomingIntent(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent, false);
    }


    /**
     * Opens the SEPARATE `ca.arc7freedom.ytmusic` app when it is installed (each app has its
     * own task + icon in Settings -> Apps), falling back to the in-suite activity.
     */
    private void openYouTubeMusicApp() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new android.content.ComponentName(
                "ca.arc7freedom.ytmusic", "ca.arc7freedom.app.YouTubeMusicActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Arc7UiAnimator.startAnimated(this, new Intent(this, YouTubeMusicActivity.class));
        } catch (Throwable ignored) {}
    }

    private void handleIncomingIntent(Intent intent, boolean loadDefaultFeedIfNone) {
        if (intent != null && intent.getData() != null) {
            String url = intent.getDataString();
            String vid = MorpheEngine.extractVideoIdFromUrlOrQuery(url);
            if (vid != null) {
                playVideoById(vid, vid, "");
                return;
            }
        }
        if (intent != null && intent.getStringExtra("query") != null) {
            String q = intent.getStringExtra("query");
            searchEt.setText(q);
            runUserSearchOrUrl(q);
            return;
        }
        if (loadDefaultFeedIfNone) {
            loadTopicFeedAsync("", "\uD83D\uDD25 Trending Videos");
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Morphe Background Play Patch: seamlessly continue audio in background when pressing Home!
        if (MorpheEngine.isBackgroundPlayEnabled(this)
                && currentPlayback != null
                && videoView != null
                && videoView.isPlaying()) {
            switchCurrentToBackgroundAudio(false);
        }
    }

    private void runUserSearchOrUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String q = raw.trim();
        String directVid = MorpheEngine.extractVideoIdFromUrlOrQuery(q);
        if (directVid != null) {
            playVideoById(directVid, directVid, "");
            return;
        }
        feedHeaderTv.setText("Searching YouTube for \"" + q + "\"...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<MorpheEngine.MediaItem> res = MorpheEngine.searchYouTubeVideos(YouTubeActivity.this, q);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            feedHeaderTv.setText("Results for \"" + q + "\" (" + res.size() + ")");
                            renderFeedItems(res);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            feedHeaderTv.setText("Search error: " + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void loadTopicFeedAsync(final String topicQuery, final String titleLabel) {
        feedHeaderTv.setText("Loading " + titleLabel + "...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<MorpheEngine.MediaItem> res =
                            MorpheEngine.fetchYouTubeFeedTopic(YouTubeActivity.this, topicQuery);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            feedHeaderTv.setText(titleLabel + " (" + res.size() + ")");
                            renderFeedItems(res);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            feedHeaderTv.setText("Offline or network busy (" + t.getMessage() + ") — showing Downloads");
                            showOfflineDownloadsShelf();
                        }
                    });
                }
            }
        }).start();
    }

    private void renderFeedItems(List<MorpheEngine.MediaItem> items) {
        currentFeed.clear();
        currentFeed.addAll(items);
        feedList.removeAllViews();

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No videos found right now.");
            empty.setTextColor(0xFFA1A1AA);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            feedList.addView(empty);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final MorpheEngine.MediaItem it = items.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF14141E);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), 0xFF232334);
            card.setBackgroundDrawable(bg);

            final ImageView thumbIv = new ImageView(this);
            thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbIv.setImageResource(R.drawable.ic_youtube);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(128), dp(72));
            tlp.rightMargin = dp(12);
            card.addView(thumbIv, tlp);
            loadThumbnailAsync(it.bestThumb(), thumbIv);

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);

            TextView titleTv = new TextView(this);
            titleTv.setText(it.title);
            titleTv.setTextColor(Color.WHITE);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setMaxLines(2);
            meta.addView(titleTv);

            StringBuilder sub = new StringBuilder();
            if (it.author.length() > 0) sub.append(it.author);
            if (it.durationText.length() > 0) {
                if (sub.length() > 0) sub.append("  \u2022  ");
                sub.append(it.durationText);
            }
            if (it.viewCountText.length() > 0) {
                if (sub.length() > 0) sub.append("  \u2022  ");
                sub.append(it.viewCountText);
            }
            TextView subTv = new TextView(this);
            subTv.setText(sub.toString());
            subTv.setTextColor(0xFFA1A1AA);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            subTv.setPadding(0, dp(3), 0, 0);
            meta.addView(subTv);

            card.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button audioQuickBtn = pillBtn("\uD83C\uDFA7", 0xFF27272A);
            audioQuickBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopForegroundVideo();
                    Intent svc = new Intent(YouTubeActivity.this, Arc7AudioService.class);
                    startService(svc);
                    List<MorpheEngine.MediaItem> subQueue =
                            new ArrayList<MorpheEngine.MediaItem>(currentFeed);
                    playViaBackgroundServiceQueue(subQueue, idx);
                }
            });
            card.addView(audioQuickBtn);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentFeedIdx = idx;
                    if (it.localFilePath != null && it.localFilePath.length() > 0
                            && new File(it.localFilePath).exists()) {
                        playLocalVideoFile(it);
                    } else {
                        playVideoById(it.videoId, it.title, it.author);
                    }
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(8);
            feedList.addView(card, clp);
        }
    }

    private void playViaBackgroundServiceQueue(final List<MorpheEngine.MediaItem> queue, final int idx) {
        bindService(new Intent(this, Arc7AudioService.class), new android.content.ServiceConnection() {
            @Override
            public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                Arc7AudioService svc = ((Arc7AudioService.LocalBinder) binder).getService();
                svc.playMorpheQueue(queue, idx, "YouTube", "ca.arc7freedom.app.YouTubeActivity");
                unbindService(this);
                Toast.makeText(YouTubeActivity.this,
                        "Playing in background — control from Notification or Clock Standby!",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        }, Context.BIND_AUTO_CREATE);
    }

    private void playLocalVideoFile(MorpheEngine.MediaItem it) {
        handedOffToBackgroundAudio = false;
        playerOverlayStatusTv.setVisibility(View.GONE);
        videoTitleTv.setText(it.title);
        videoChannelTv.setText("Offline Downloaded Media  \u2022  " + it.localFilePath);
        MorpheEngine.ResolvedPlayback offline = new MorpheEngine.ResolvedPlayback();
        offline.videoId = it.videoId;
        offline.title = it.title;
        offline.author = "Offline Download";
        offline.spoofClientUsed = "OFFLINE_MP4";
        currentPlayback = offline;
        videoView.setVideoPath(it.localFilePath);
        videoView.start();
    }

    private void playVideoById(final String videoId, final String initialTitle, final String initialAuthor) {
        handedOffToBackgroundAudio = false;
        activeResolutionVideoId = videoId;
        // Pause background music service so streams never overlap!
        Intent pauseBg = new Intent(this, Arc7AudioService.class);
        pauseBg.setAction(Arc7AudioService.ACTION_STOP_DISMISS);
        startService(pauseBg);

        playerOverlayStatusTv.setVisibility(View.VISIBLE);
        playerOverlayStatusTv.setText("Connecting to YouTube playback services...");
        videoTitleTv.setText(initialTitle);
        videoChannelTv.setText(initialAuthor);
        rydStatsTv.setText("");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final MorpheEngine.ResolvedPlayback res = MorpheEngine.resolveStreams(
                            YouTubeActivity.this, videoId, false,
                            new MorpheEngine.ResolutionProgressListener() {
                                @Override
                                public void onProgress(final String message) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (alive && videoId.equals(activeResolutionVideoId)) {
                                                playerOverlayStatusTv.setVisibility(View.VISIBLE);
                                                playerOverlayStatusTv.setText(message);
                                            }
                                        }
                                    });
                                }
                            });
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive || !videoId.equals(activeResolutionVideoId)) return;
                            currentPlayback = res;
                            videoTitleTv.setText(res.title);
                            String views = res.viewCount.length() > 0
                                    ? formatNumberString(res.viewCount) + " views  \u2022  " : "";
                            videoChannelTv.setText(res.author + "  \u2022  " + views
                                    + "Morphe Client: " + res.spoofClientUsed + " (tap channel to browse)");
                            updatePlaybackMetadataViews(res);

                            if (res.muxedLocally) {
                                qualityBtn.setText("\uD83C\uDF9E " + res.muxNote + " (Morphe Mux)");
                            } else if (!res.progressiveVideos.isEmpty()) {
                                qualityBtn.setText("\uD83C\uDF9E " + res.progressiveVideos.get(0).label);
                            } else if (!res.videoOnlyStreams.isEmpty()) {
                                qualityBtn.setText("\uD83C\uDF9E " + res.videoOnlyStreams.get(0).label);
                            }

                            MorpheEngine.addOrToggleSavedItem(YouTubeActivity.this, "yt_history", toMediaItem(res), false);

                            if (res.localVideoProxyUrl == null || res.localVideoProxyUrl.length() == 0) {
                                playerOverlayStatusTv.setVisibility(View.VISIBLE);
                                playerOverlayStatusTv.setText("No playable H.264/AAC stream could be built for this video.\n"
                                        + (res.muxNote.length() > 0 ? res.muxNote : "Try another Morphe client in the \u2728 menu."));
                                return;
                            }
                            videoView.setVideoURI(Uri.parse(res.localVideoProxyUrl));
                            videoView.requestFocus();
                            loadPlaybackMetadataAsync(res, videoId);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive || !videoId.equals(activeResolutionVideoId)) return;
                            playerOverlayStatusTv.setVisibility(View.VISIBLE);
                            playerOverlayStatusTv.setText("Could not load stream: " + t.getMessage());
                        }
                    });
                }
            }
        }, "Arc7-YouTube-Resolve").start();
    }

    private void updatePlaybackMetadataViews(MorpheEngine.ResolvedPlayback res) {
        StringBuilder rydLine = new StringBuilder();
        if (res.likesCount >= 0L) {
            rydLine.append("\uD83D\uDC4D ").append(NumberFormat.getInstance(Locale.US).format(res.likesCount));
        }
        if (res.dislikesCount >= 0L) {
            if (rydLine.length() > 0) rydLine.append("    ");
            rydLine.append("\uD83D\uDC4E ").append(NumberFormat.getInstance(Locale.US).format(res.dislikesCount));
        }
        int segmentCount;
        synchronized (res.skipSegments) { segmentCount = res.skipSegments.size(); }
        if (segmentCount > 0) {
            if (rydLine.length() > 0) rydLine.append("    \u2022    ");
            rydLine.append("\u23ED ").append(segmentCount).append(" SponsorBlock segments ready");
        }
        rydStatsTv.setText(rydLine.toString());

        if (!res.relatedItems.isEmpty() && currentFeed.isEmpty()) {
            feedHeaderTv.setText("Up Next");
            renderFeedItems(res.relatedItems);
        }
    }

    private void loadPlaybackMetadataAsync(final MorpheEngine.ResolvedPlayback res, final String videoId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                MorpheEngine.enrichPlaybackMetadata(YouTubeActivity.this, res);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (alive && currentPlayback == res && videoId.equals(activeResolutionVideoId)) {
                            updatePlaybackMetadataViews(res);
                        }
                    }
                });
            }
        }, "Arc7-YouTube-Metadata").start();
    }

    private void switchCurrentToBackgroundAudio(boolean closeForegroundVideo) {
        if (currentPlayback == null || handedOffToBackgroundAudio) return;
        handedOffToBackgroundAudio = true;
        NowPlayingManager.setYouTubeForegroundVideoActive(false);
        try { videoView.stopPlayback(); } catch (Throwable ignored) {}

        String audioUrl = MorpheEngine.ensureAudioOnlyUrl(this, currentPlayback);
        if (audioUrl == null || audioUrl.length() == 0) {
            audioUrl = currentPlayback.localVideoProxyUrl;
        }
        Intent svc = new Intent(this, Arc7AudioService.class);
        svc.setAction(Arc7AudioService.ACTION_PLAY_STREAM);
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_URL, audioUrl);
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_TITLE, currentPlayback.title);
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_ARTIST, currentPlayback.author);
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_SOURCE, "YouTube");
        svc.putExtra(Arc7AudioService.EXTRA_STREAM_THUMB, currentPlayback.thumbnailUrl);
        svc.putExtra(Arc7AudioService.EXTRA_LAUNCH_CLASS, "ca.arc7freedom.app.YouTubeActivity");
        startService(svc);

        playerOverlayStatusTv.setVisibility(View.VISIBLE);
        playerOverlayStatusTv.setText("\uD83C\uDFA7 Playing in Background Audio Mode\n"
                + currentPlayback.title + "\n(Active in Notification Shade & Clock Standby!)");
        if (closeForegroundVideo) {
            Toast.makeText(this, "Background audio active — safe to lock screen or switch apps!",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleForegroundVideo() {
        if (videoView == null || currentPlayback == null) return;
        if (videoView.isPlaying()) {
            videoView.pause();
            playPauseBtn.setText("\u25B6 Play");
        } else {
            videoView.start();
            playPauseBtn.setText("\u23F8 Pause");
        }
    }

    private void seekVideoRelative(int deltaMs) {
        if (videoView == null || currentPlayback == null) return;
        int dur = videoView.getDuration();
        if (dur <= 0) return;
        videoView.seekTo(Math.max(0, Math.min(dur, videoView.getCurrentPosition() + deltaMs)));
    }

    private void stepVideoInFeed(int delta) {
        if (currentFeed.isEmpty()) return;
        int next = currentFeedIdx + delta;
        if (next < 0) next = 0;
        if (next >= currentFeed.size()) next = 0;
        currentFeedIdx = next;
        MorpheEngine.MediaItem it = currentFeed.get(next);
        playVideoById(it.videoId, it.title, it.author);
    }

    private void stopForegroundVideo() {
        NowPlayingManager.setYouTubeForegroundVideoActive(false);
        try { if (videoView != null) videoView.stopPlayback(); } catch (Throwable ignored) {}
    }

    private void toggleFullscreenMode() {
        isFullscreen = !isFullscreen;
        if (isFullscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            ViewGroup parent = (ViewGroup) playerStage.getParent();
            if (parent != null) parent.removeView(playerStage);
            mainChrome.setVisibility(View.GONE);
            rootStack.addView(playerStage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Toast.makeText(this, "Press Back button anytime to exit Fullscreen", Toast.LENGTH_SHORT).show();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            ViewGroup parent = (ViewGroup) playerStage.getParent();
            if (parent != null) parent.removeView(playerStage);
            mainChrome.setVisibility(View.VISIBLE);
            int stageHeight = Math.min(dp(275), getResources().getDisplayMetrics().heightPixels * 46 / 100);
            ((LinearLayout) ((ScrollView) mainChrome.getChildAt(2)).getChildAt(0)).addView(
                    playerStage, 0, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(200), stageHeight)));
        }
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            toggleFullscreenMode();
            return;
        }
        super.onBackPressed();
    }

    private void showQualityPickerDialog() {
        if (currentPlayback == null) return;
        final List<String> labels = new ArrayList<String>();
        final List<MorpheEngine.StreamVariant> variants = new ArrayList<MorpheEngine.StreamVariant>();
        final List<Boolean> audioOnly = new ArrayList<Boolean>();
        for (int i = 0; i < currentPlayback.videoOnlyStreams.size(); i++) {
            MorpheEngine.StreamVariant sv = currentPlayback.videoOnlyStreams.get(i);
            labels.add("\uD83C\uDFAC Video: " + sv.label);
            variants.add(sv);
            audioOnly.add(Boolean.FALSE);
        }
        for (int i = 0; i < currentPlayback.progressiveVideos.size(); i++) {
            MorpheEngine.StreamVariant sv = currentPlayback.progressiveVideos.get(i);
            labels.add("\uD83C\uDFAC Video: " + sv.label + " (muxed)");
            variants.add(sv);
            audioOnly.add(Boolean.FALSE);
        }
        for (int i = 0; i < currentPlayback.audioStreams.size(); i++) {
            MorpheEngine.StreamVariant sv = currentPlayback.audioStreams.get(i);
            labels.add("\uD83C\uDFA7 Audio Only: " + sv.label);
            variants.add(sv);
            audioOnly.add(Boolean.TRUE);
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Select Stream Quality")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final MorpheEngine.StreamVariant picked = variants.get(which);
                        final boolean soundOnly = audioOnly.get(which).booleanValue();
                        final int curPos = videoView != null ? videoView.getCurrentPosition() : 0;
                        qualityBtn.setText("\uD83C\uDF9E Building " + picked.label + " ...");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String slotUrl = soundOnly
                                        ? MorpheEngine.startAudioForVariant(YouTubeActivity.this, picked)
                                        : MorpheEngine.startMuxForVariant(YouTubeActivity.this, currentPlayback, picked);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (slotUrl == null || slotUrl.length() == 0) {
                                            qualityBtn.setText("\uD83C\uDF9E Quality unavailable");
                                            return;
                                        }
                                        qualityBtn.setText("\uD83C\uDF9E " + picked.label);
                                        videoView.setVideoURI(Uri.parse(slotUrl));
                                        videoView.requestFocus();
                                        if (curPos > 1500) videoView.seekTo(curPos);
                                        videoView.start();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptOfflineDownload() {
        if (currentPlayback == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Download Offline: " + currentPlayback.title)
                .setItems(new String[]{
                        "\uD83C\uDFAC Download MP4 Video (/sdcard/Movies/YouTube/)",
                        "\uD83C\uDFB5 Download M4A Music Audio (/sdcard/Music/YouTubeMusic/)"
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final boolean audioOnly = (which == 1);
                        final MorpheEngine.StreamVariant target = audioOnly
                                ? (!currentPlayback.audioStreams.isEmpty()
                                        ? currentPlayback.audioStreams.get(0)
                                        : (!currentPlayback.progressiveVideos.isEmpty()
                                                ? currentPlayback.progressiveVideos.get(0)
                                                : currentPlayback.videoOnlyStreams.get(0)))
                                : (!currentPlayback.videoOnlyStreams.isEmpty()
                                        ? currentPlayback.videoOnlyStreams.get(0)
                                        : (!currentPlayback.progressiveVideos.isEmpty()
                                                ? currentPlayback.progressiveVideos.get(0)
                                                : currentPlayback.audioStreams.get(0)));
                        Toast.makeText(YouTubeActivity.this, "Starting offline download...", Toast.LENGTH_SHORT).show();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    final File f = MorpheEngine.downloadStreamOffline(
                                            YouTubeActivity.this, target, currentPlayback.title, audioOnly, null);
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(YouTubeActivity.this,
                                                    "Saved offline: " + f.getName(), Toast.LENGTH_LONG).show();
                                        }
                                    });
                                } catch (final Throwable t) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(YouTubeActivity.this,
                                                    "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCommentsDialog() {
        if (currentPlayback == null) return;
        Toast.makeText(this, "Loading comments...", Toast.LENGTH_SHORT).show();
        final String vid = currentPlayback.videoId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MorpheEngine.CommentItem> list = MorpheEngine.fetchVideoComments(YouTubeActivity.this, vid);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        if (list.isEmpty()) {
                            sb.append("Comments are disabled or unavailable for this video.");
                        } else {
                            for (int i = 0; i < list.size(); i++) {
                                MorpheEngine.CommentItem c = list.get(i);
                                sb.append("\u25CF ").append(c.author)
                                        .append("  (\uD83D\uDC4D ").append(c.likes).append("  ").append(c.timeAgo).append(")\n")
                                        .append(c.text).append("\n\n");
                            }
                        }
                        ScrollView sv = new ScrollView(YouTubeActivity.this);
                        TextView tv = new TextView(YouTubeActivity.this);
                        tv.setText(sb.toString());
                        tv.setTextColor(Color.WHITE);
                        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                        sv.addView(tv);
                        new AlertDialog.Builder(YouTubeActivity.this)
                                .setTitle("Comments (" + list.size() + ")")
                                .setView(sv)
                                .setPositiveButton("Close", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void showLocalSavedShelf(String key, String label) {
        List<MorpheEngine.MediaItem> saved = MorpheEngine.loadSavedList(this, key);
        feedHeaderTv.setText(label + " (" + saved.size() + ")");
        renderFeedItems(saved);
    }

    private void showOfflineDownloadsShelf() {
        List<MorpheEngine.MediaItem> list = new ArrayList<MorpheEngine.MediaItem>();
        File[] dirs = new File[]{
                new File(Environment.getExternalStorageDirectory(), "Movies/YouTube"),
                new File(Environment.getExternalStorageDirectory(), "Music/YouTubeMusic")
        };
        for (int d = 0; d < dirs.length; d++) {
            File[] files = dirs[d].exists() ? dirs[d].listFiles() : null;
            if (files == null) continue;
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isFile()) continue;
                MorpheEngine.MediaItem m = new MorpheEngine.MediaItem();
                m.videoId = "local_" + i;
                m.title = files[i].getName();
                m.author = "Offline File (" + (files[i].length() / 1024L) + " KB)";
                m.localFilePath = files[i].getAbsolutePath();
                list.add(m);
            }
        }
        feedHeaderTv.setText("Offline Downloads (" + list.size() + ")");
        renderFeedItems(list);
    }

    private void showMorphePatchesDialog() {
        final String[] opts = new String[]{
                "SponsorBlock Auto-Skip: " + (MorpheEngine.isSponsorBlockEnabled(this) ? "ENABLED" : "OFF"),
                "Return YouTube Dislike: " + (MorpheEngine.isReturnDislikeEnabled(this) ? "ENABLED" : "OFF"),
                "Background Audio on Home/Screen-Off: " + (MorpheEngine.isBackgroundPlayEnabled(this) ? "ENABLED" : "OFF"),
                "Spoof Stream Client: " + MorpheEngine.SPOOF_CLIENT_LABELS[MorpheEngine.getPreferredSpoofClient(this)]
        };
        new AlertDialog.Builder(this)
                .setTitle("\u2728 Morphe Patches & Stream Spoof")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            MorpheEngine.setSponsorBlockEnabled(YouTubeActivity.this,
                                    !MorpheEngine.isSponsorBlockEnabled(YouTubeActivity.this));
                            showMorphePatchesDialog();
                        } else if (which == 1) {
                            MorpheEngine.setReturnDislikeEnabled(YouTubeActivity.this,
                                    !MorpheEngine.isReturnDislikeEnabled(YouTubeActivity.this));
                            showMorphePatchesDialog();
                        } else if (which == 2) {
                            MorpheEngine.setBackgroundPlayEnabled(YouTubeActivity.this,
                                    !MorpheEngine.isBackgroundPlayEnabled(YouTubeActivity.this));
                            showMorphePatchesDialog();
                        } else {
                            new AlertDialog.Builder(YouTubeActivity.this)
                                    .setTitle("Morphe Spoof Video Streams Client")
                                    .setSingleChoiceItems(MorpheEngine.SPOOF_CLIENT_LABELS,
                                            MorpheEngine.getPreferredSpoofClient(YouTubeActivity.this),
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface d2, int idx) {
                                                    MorpheEngine.setPreferredSpoofClient(YouTubeActivity.this, idx);
                                                    d2.dismiss();
                                                }
                                            })
                                    .show();
                        }
                    }
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private static MorpheEngine.MediaItem toMediaItem(MorpheEngine.ResolvedPlayback r) {
        MorpheEngine.MediaItem m = new MorpheEngine.MediaItem();
        m.videoId = r.videoId;
        m.title = r.title;
        m.author = r.author;
        m.durationSec = r.durationSec;
        m.durationText = NowPlayingManager.formatDurationMs(r.durationSec * 1000L);
        m.thumbnailUrl = r.thumbnailUrl;
        return m;
    }

    private static String formatNumberString(String s) {
        try {
            return NumberFormat.getInstance(Locale.US).format(Long.parseLong(s));
        } catch (Throwable ignored) {
            return s;
        }
    }

    private void loadThumbnailAsync(final String url, final ImageView iv) {
        if (url == null || !url.startsWith("http")) return;
        Bitmap cached = THUMB_CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(url);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    String gw = "http://127.0.0.1:" + NetworkCertManager.DEFAULT_PROXY_PORT
                            + "/__arc7_raw?u=" + Uri.encode(url);
                    conn = (HttpURLConnection) new URL(gw).openConnection(Proxy.NO_PROXY);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(7000);
                    if (conn.getResponseCode() == 200) {
                        in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0 && bos.size() < 1024 * 1024) bos.write(buf, 0, n);
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        final Bitmap bmp = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size(), o);
                        if (bmp != null) {
                            THUMB_CACHE.put(url, bmp);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
                                }
                            });
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
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
        ui.removeCallbacks(progressTicker);
        try { unregisterReceiver(fgControlReceiver); } catch (Throwable ignored) {}
        if (!handedOffToBackgroundAudio) {
            stopForegroundVideo();
        }
        super.onDestroy();
    }
}
