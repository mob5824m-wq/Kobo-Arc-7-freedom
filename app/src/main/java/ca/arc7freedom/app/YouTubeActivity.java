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
import android.media.AudioManager;
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
 * Native playback for legacy Android, subject to YouTube availability and ROM codec support:
 *   - Bounded visionOS / iOS / TV player requests, HLS TLS relay and classic MP4 remux
 *     (legacy clients remain manually selectable, not the entire automatic attempt budget)
 *   - Hardware-accelerated H.264/AAC MP4 playback over local `206 Partial Content` TLS 1.3 Range Proxy
 *   - Ad-free Home / Trending / Search / Subscriptions / Watch History / Offline MP4 Downloads
 *   - Live SponsorBlock auto-skip + Return YouTube Dislike counts + Comments + Screen-Off Background Play!
 */
public class YouTubeActivity extends Arc7Activity {

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
    private ScrollView pageScroll;

    private final List<MorpheEngine.MediaItem> currentFeed = new ArrayList<MorpheEngine.MediaItem>();
    private int currentFeedIdx = -1;
    private MorpheEngine.ResolvedPlayback currentPlayback = null;
    private Arc7DownloadUi downloads;
    private int feedRequestGeneration;
    private volatile int videoRequestGeneration;
    private Thread resolutionThread;
    private boolean videoPrepared;
    private Runnable videoPrepareTimeout;
    private volatile String activeResolutionVideoId = "";
    private boolean userScrubbing = false;
    private boolean isFullscreen = false;
    private boolean handedOffToBackgroundAudio = false;
    private boolean audioRepairAttempted;
    private MediaPlayer foregroundPlayer;
    private AudioManager audioManager;
    private boolean hasVideoAudioFocus;
    private String videoAudioStatus = "No foreground player is ready.";
    private String videoContainer = "not independently reported";
    private int videoErrorWhat, videoErrorExtra;
    private boolean videoPlayWhenPrepared = true;
    private final BroadcastReceiver videoNoisy = new BroadcastReceiver() {
        public void onReceive(Context context,Intent intent) {
            if(intent==null||!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())||handedOffToBackgroundAudio)return;
            videoPlayWhenPrepared=false;
            if(videoPrepared&&videoView!=null)try{videoView.pause();}catch(RuntimeException ignored){}
            releaseVideoAudioFocus();
            ca.arc7freedom.support.DebugLog.event("video.headphones_disconnected");
        }
    };

    private AudioManager.OnAudioFocusChangeListener videoAudioFocus;

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
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        Arc7TlsGatewayService.ensureStarted(this);
        Arc7AudioService.ensureWatcherRunning(this);
        MorpheEngine.ensureStreamProxyStarted(this);

        rootStack = new FrameLayout(this);
        rootStack.setBackgroundColor(0xFF09090D);

        mainChrome = new LinearLayout(this);
        mainChrome.setOrientation(LinearLayout.VERTICAL);
        mainChrome.setBackgroundColor(0xFF09090D);

        // Keep secondary actions out of the search field, including on narrow portrait screens.
        Arc7MediaHeader header = new Arc7MediaHeader(this, Arc7Skin.current(this), R.drawable.ic_youtube,
                "YouTube", "Search YouTube or paste video link…", null,
                new Runnable() { @Override public void run() { runUserSearchOrUrl(searchEt.getText().toString()); } },
                new Runnable() { @Override public void run() { showHeaderOptions(); } });
        searchEt = header.getSearchInput();
        mainChrome.addView(header);

        // Category & Library Shelf Bar
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(0xFF0E0E14);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(10), dp(6), dp(10), dp(6));

        final String[][] shelves = new String[][]{
                {"\uD83D\uDD25 Trending", ""},
                {"Subscriptions · pinned channels", "@pins"},
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
                    if ("@pins".equals(q)) showPinnedChannels();
                    else if ("@saved".equals(q)) showLocalSavedShelf("yt_saved", "Saved Videos & Channels");
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
        pageScroll = new ScrollView(this);
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

        Button soundBtn = pillBtn("\uD83D\uDD0A Sound", 0xFF0F766E);
        soundBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSoundDialog(); }
        });
        ctrlRow.addView(soundBtn, marginRightLp(6));

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

        Button subscribe=pillBtn("+ Pin channel",0xFF334155);
        subscribe.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            if(currentPlayback!=null && currentPlayback.channelId.matches("UC[A-Za-z0-9_-]{22}")) {
                MorpheEngine.MediaItem channel=new MorpheEngine.MediaItem(); channel.channelId=currentPlayback.channelId; channel.title=currentPlayback.author;
                MorpheEngine.addOrToggleSavedItem(YouTubeActivity.this,"yt_pinned_channels",channel,false);
                Toast.makeText(YouTubeActivity.this,"Subscribed locally — see Pinned Channels. No Google account changes.",Toast.LENGTH_LONG).show();
            } else promptPinnedChannel();
        }}); ctrlRow.addView(subscribe,marginRightLp(6));

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

        setContentView(rootStack);
        Arc7UiAnimator.attachActivityAnimations(this);

        registerReceiver(fgControlReceiver, new IntentFilter(NowPlayingManager.ACTION_YOUTUBE_FG_CONTROL));
        registerReceiver(videoNoisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
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
        Arc7UiAnimator.startAnimated(this, new Intent(this, YouTubeMusicActivity.class));
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

    private void showHeaderOptions() {
        new AlertDialog.Builder(this).setTitle("YouTube options")
                .setItems(new String[]{"Open YouTube Music", "Morphe / playback settings", "Background audio diagnostics / sound test", "App tools · logs, RAM & storage"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) openYouTubeMusicApp(); else if (which == 1) showMorphePatchesDialog();
                        else if (which == 2) startActivity(new Intent(YouTubeActivity.this, Arc7PlaybackDiagnosticsActivity.class));
                        else ca.arc7freedom.support.AppSupport.open(YouTubeActivity.this);
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void runUserSearchOrUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        final int feedRequest=++feedRequestGeneration;
        final String q = raw.trim();
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
                            if(!alive || feedRequest!=feedRequestGeneration) return;
                            feedHeaderTv.setText("Results for \"" + q + "\" (" + res.size() + ")");
                            renderFeedItems(res);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if(!alive || feedRequest!=feedRequestGeneration) return;
                            feedHeaderTv.setText("Search error: " + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void loadTopicFeedAsync(final String topicQuery, final String titleLabel) {
        final int feedRequest=++feedRequestGeneration;
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
                            if(!alive || feedRequest!=feedRequestGeneration) return;
                            feedHeaderTv.setText(titleLabel + " (" + res.size() + ")");
                            renderFeedItems(res);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if(!alive || feedRequest!=feedRequestGeneration) return;
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
                        "Preparing background audio — controls appear after playback starts.",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        }, Context.BIND_AUTO_CREATE);
    }

    private void playLocalVideoFile(MorpheEngine.MediaItem it) {
        ++videoRequestGeneration;
        audioRepairAttempted = false;
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        if (resolutionThread != null) resolutionThread.interrupt();
        cancelVideoPrepareTimeout();
        MorpheEngine.cancelPlayback(currentPlayback);
        try { videoView.stopPlayback(); } catch (Throwable ignored) {}
        NowPlayingManager.clearSession(this);
        Intent stopAudio = new Intent(this, Arc7AudioService.class);
        stopAudio.setAction(Arc7AudioService.ACTION_STOP_DISMISS);
        startService(stopAudio);
        handedOffToBackgroundAudio = false;
        videoPlayWhenPrepared=true;videoErrorWhat=videoErrorExtra=0;
        playerOverlayStatusTv.setVisibility(View.GONE);
        videoTitleTv.setText(it.title);
        videoChannelTv.setText("Offline Downloaded Media  \u2022  " + it.localFilePath);
        MorpheEngine.ResolvedPlayback offline = new MorpheEngine.ResolvedPlayback();
        offline.videoId = it.videoId;
        offline.title = it.title;
        offline.author = "Offline Download";
        offline.spoofClientUsed = "OFFLINE_MP4";
        currentPlayback = offline;
        prepareForegroundVideo(offline, Uri.fromFile(new File(it.localFilePath)), videoRequestGeneration);
    }

    private void playVideoById(final String videoId, final String initialTitle, final String initialAuthor) {
        final int request = ++videoRequestGeneration;
        audioRepairAttempted = false;
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        cancelVideoPrepareTimeout();
        videoPrepared = false;
        if (resolutionThread != null) resolutionThread.interrupt();
        try { videoView.stopPlayback(); } catch (Throwable ignored) {}
        MorpheEngine.cancelPlayback(currentPlayback);
        currentPlayback = null;
        NowPlayingManager.clearSession(this);
        handedOffToBackgroundAudio = false;
        videoPlayWhenPrepared=true;videoErrorWhat=videoErrorExtra=0;
        activeResolutionVideoId = videoId;
        // Pause background music service so streams never overlap!
        Intent pauseBg = new Intent(this, Arc7AudioService.class);
        pauseBg.setAction(Arc7AudioService.ACTION_STOP_DISMISS);
        startService(pauseBg);

        if (pageScroll != null) {
            pageScroll.scrollTo(0, 0);
        }
        playerOverlayStatusTv.setVisibility(View.VISIBLE);
        playerOverlayStatusTv.setText("Connecting to YouTube playback services...");
        videoTitleTv.setText(initialTitle);
        videoChannelTv.setText(initialAuthor);
        rydStatsTv.setText("");

        resolutionThread = new Thread(new Runnable() {
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
                                            if (alive && request == videoRequestGeneration && videoId.equals(activeResolutionVideoId)) {
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
                            if (!alive || request != videoRequestGeneration || !videoId.equals(activeResolutionVideoId)) {
                                MorpheEngine.cancelPlayback(res); return;
                            }
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

                            if (res.hlsManifestUrl.length() > 0) qualityBtn.setText(res.muxNote);

                            MorpheEngine.addOrToggleSavedItem(YouTubeActivity.this, "yt_history", toMediaItem(res), false);

                            if (res.localVideoProxyUrl == null || res.localVideoProxyUrl.length() == 0) {
                                playerOverlayStatusTv.setVisibility(View.VISIBLE);
                                playerOverlayStatusTv.setText("No playable H.264/AAC stream could be built for this video.\n"
                                        + (res.muxNote.length() > 0 ? res.muxNote : "Try another Morphe client in the \u2728 menu."));
                                return;
                            }
                            playerOverlayStatusTv.setText("Preparing Android 4.2 video...\n" + res.muxNote);
                            prepareForegroundVideo(res, Uri.parse(res.localVideoProxyUrl), request);
                            videoView.requestFocus();
                            loadPlaybackMetadataAsync(res, videoId);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive || request != videoRequestGeneration || !videoId.equals(activeResolutionVideoId)) return;
                            playerOverlayStatusTv.setVisibility(View.VISIBLE);
                            playerOverlayStatusTv.setText("Could not load stream: " + t.getMessage());
                        }
                    });
                }
            }
        }, "Arc7-YouTube-Resolve");
        resolutionThread.start();
    }

    private void cancelVideoPrepareTimeout() {
        if (videoPrepareTimeout != null) {
            ui.removeCallbacks(videoPrepareTimeout);
            videoPrepareTimeout = null;
        }
    }

    private void prepareForegroundVideo(final MorpheEngine.ResolvedPlayback playback, Uri uri,
                                         final int request) {
        cancelVideoPrepareTimeout();
        videoPrepared = false;
        foregroundPlayer = null;
        videoAudioStatus = "Checking the prepared stream for an audio track...";
        String localMime=MorpheStreamServer.getMimeType(uri.toString());
        videoContainer="video/mp4".equals(localMime)?"MP4":"audio/mp4".equals(localMime)?"M4A / MP4":"audio/aac".equals(localMime)?"AAC / ADTS":"not independently reported";
        ca.arc7freedom.support.DebugLog.event("video.prepare_started");
        releaseVideoAudioFocus();
        NowPlayingManager.setYouTubeForegroundVideoActive(false);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                if (!alive || request != videoRequestGeneration || currentPlayback != playback
                        || handedOffToBackgroundAudio) return;
                try {
                    LegacyPlaybackAudio.enable(mp);
                    foregroundPlayer = mp;
                    videoAudioStatus = LegacyPlaybackAudio.trackState(mp) == LegacyPlaybackAudio.PRESENT
                            ? "The device reports an audio track. Media volume and the selected output still apply."
                            : "This ROM cannot report prepared audio tracks; audio could not be confirmed by the device.";
                    ca.arc7freedom.support.DebugLog.event("video.prepared_with_audio_check");
                    boolean canStart = videoPlayWhenPrepared && takeVideoAudioFocus();
                    if (canStart) videoView.start();
                    videoPrepared = true;
                    cancelVideoPrepareTimeout();
                    // Readiness belongs to THIS prepared request, not its metadata response.
                    NowPlayingManager.setYouTubeForegroundVideoActive(true);
                    playerOverlayStatusTv.setVisibility(canStart ? View.GONE : View.VISIBLE);
                    if (!canStart) {
                        playerOverlayStatusTv.setText(videoPlayWhenPrepared ? "Another app has audio focus. Pause it, then tap Play." : "Paused after headphone disconnect. Tap Play when ready.");
                        playPauseBtn.setText("\u25B6 Play");
                    }
                    try {
                        if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
                            Toast.makeText(YouTubeActivity.this, "Media volume is zero. Use Sound or Volume Up to hear this video.", Toast.LENGTH_LONG).show();
                    } catch (RuntimeException unavailable) {}
                } catch (final java.io.IOException missingAudio) {
                    // Do not release/reopen VideoView inside its native prepared callback.
                    ui.post(new Runnable() {
                        @Override public void run() { retryForegroundWithAudio(playback, request, missingAudio.getMessage()); }
                    });
                } catch (Throwable error) { failForegroundVideo(playback, request, error.toString()); }
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                if (request != videoRequestGeneration || currentPlayback != playback || handedOffToBackgroundAudio) return;
                releaseVideoAudioFocus();
                foregroundPlayer = null;
                videoPrepared = false;
                NowPlayingManager.clearSession(YouTubeActivity.this);
                stepVideoInFeed(1);
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                if (!alive || request != videoRequestGeneration || currentPlayback != playback || handedOffToBackgroundAudio) return true;
                videoErrorWhat=what;videoErrorExtra=extra;
                ca.arc7freedom.support.DebugLog.number("video.error_what",what);ca.arc7freedom.support.DebugLog.number("video.error_extra",extra);
                failForegroundVideo(playback, request, LegacyMediaPolicy.error(what,extra));
                return true;
            }
        });
        videoPrepareTimeout = new Runnable() {
            @Override public void run() {
                if (!videoPrepared) failForegroundVideo(playback, request,
                        "Video preparation timed out; no playable media was received");
            }
        };
        ui.postDelayed(videoPrepareTimeout, 125000L);
        try { videoView.setVideoURI(uri); }
        catch (Throwable error) { failForegroundVideo(playback, request, error.toString()); }
    }

    private boolean takeVideoAudioFocus() {
        if(hasVideoAudioFocus)return true;
        if(audioManager==null)return false;
        releaseVideoAudioFocus();
        final MediaPlayer owner=foregroundPlayer;
        final int request=videoRequestGeneration;
        videoAudioFocus=new AudioManager.OnAudioFocusChangeListener(){
            public void onAudioFocusChange(final int change){
                final AudioManager.OnAudioFocusChangeListener callback=this;
                ui.post(new Runnable(){public void run(){
                    if(!alive||videoAudioFocus!=callback||request!=videoRequestGeneration||foregroundPlayer!=owner||!videoPrepared||owner==null||handedOffToBackgroundAudio)return;
                    ca.arc7freedom.support.DebugLog.number("video.focus_change",change);
                    try{
                        if(change==AudioManager.AUDIOFOCUS_GAIN){hasVideoAudioFocus=true;owner.setVolume(1f,1f);}
                        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)owner.setVolume(.2f,.2f);
                        else if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
                            if(videoView.isPlaying())videoView.pause();playPauseBtn.setText("\u25B6 Play");hasVideoAudioFocus=false;
                            if(change==AudioManager.AUDIOFOCUS_LOSS)releaseVideoAudioFocus();
                        }
                    }catch(RuntimeException error){ca.arc7freedom.support.DebugLog.error("video.focus_operation_failed",error);}
                }});
            }
        };
        try {hasVideoAudioFocus=audioManager.requestAudioFocus(videoAudioFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;}
        catch(RuntimeException denied){ca.arc7freedom.support.DebugLog.error("video.focus_request_failed",denied);}
        return hasVideoAudioFocus;
    }

    private void releaseVideoAudioFocus() {
        AudioManager.OnAudioFocusChangeListener old=videoAudioFocus;videoAudioFocus=null;
        if (audioManager != null && old!=null) {
            try { audioManager.abandonAudioFocus(old); } catch (RuntimeException ignored) {}
        }
        hasVideoAudioFocus = false;
    }

    private void showSoundDialog() {
        if (audioManager == null) return;
        try {
            final int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(12), dp(18), dp(12));
            final TextView label = new TextView(this);
            label.setText("Media volume: " + current + " / " + maximum);
            box.addView(label);
            SeekBar volume = new SeekBar(this);
            volume.setMax(maximum); volume.setProgress(current);
            volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (!fromUser) return;
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                        label.setText("Media volume: " + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + " / " + maximum);
                    } catch (RuntimeException denied) {
                        label.setText("Could not change media volume. Try the tablet's volume buttons.");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            box.addView(volume);
            TextView details = new TextView(this);
            details.setPadding(0, dp(12), 0, 0);
            details.setText("Foreground player: " + (videoPrepared ? "prepared" : "not ready / failed / idle")
                    + "\nAudio focus: " + hasVideoAudioFocus + "\nBuffering: " + (videoView==null?"not reported":videoView.getBufferPercentage()+"%")
                    + "\nLast MediaPlayer error: " + videoErrorWhat + "/" + videoErrorExtra
                    + "\nContainer: " + videoContainer + "\nCodec: not independently reported by this player"
                    + "\n\nAudio compatibility check\n" + videoAudioStatus
                    + "\n\nVolume buttons control media, not the ringer. Headphones/Bluetooth keep their selected output; this app does not force speaker routing or raise volume automatically."
                    + (handedOffToBackgroundAudio ? "\nBackground Audio is active; foreground track details do not describe that player." : ""));
            box.addView(details);
            new AlertDialog.Builder(this).setTitle("YouTube sound").setView(box)
                    .setNeutralButton("Record counters in log",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                        ca.arc7freedom.support.DebugLog.number("video.snapshot_prepared",videoPrepared?1:0);
                        ca.arc7freedom.support.DebugLog.number("video.snapshot_focus",hasVideoAudioFocus?1:0);
                        ca.arc7freedom.support.DebugLog.number("video.snapshot_buffer_percent",videoView==null?-1:videoView.getBufferPercentage());
                        ca.arc7freedom.support.DebugLog.number("video.snapshot_error_what",videoErrorWhat);
                        ca.arc7freedom.support.DebugLog.number("video.snapshot_error_extra",videoErrorExtra);
                        ca.arc7freedom.support.AppSupport.open(YouTubeActivity.this);
                    }})
                    .setPositiveButton("Done", null).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "Use the tablet's media-volume controls.", Toast.LENGTH_LONG).show();
        }
    }

    private void retryForegroundWithAudio(final MorpheEngine.ResolvedPlayback playback, final int request,
                                          final String reason) {
        if (!alive || request != videoRequestGeneration || currentPlayback != playback || handedOffToBackgroundAudio) return;
        if (audioRepairAttempted) { failForegroundVideo(playback, request, reason); return; }
        audioRepairAttempted = true;
        final int recovery = ++videoRequestGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        cancelVideoPrepareTimeout();
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        videoPrepared = false;
        NowPlayingManager.clearSession(this);
        try { videoView.stopPlayback(); } catch (Throwable ignored) {}
        MorpheEngine.cancelPlayback(playback);
        playback.localVideoProxyUrl = ""; playback.localAudioProxyUrl = "";
        videoAudioStatus = reason;
        playerOverlayStatusTv.setVisibility(View.VISIBLE);
        playerOverlayStatusTv.setText("This stream opened without audio. Preparing a combined H.264 + AAC stream...");
        resolutionThread = new Thread(new Runnable() {
            @Override public void run() {
                final String repaired = MorpheEngine.startVideoWithAudioFallback(YouTubeActivity.this, playback);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!alive || recovery != videoRequestGeneration || currentPlayback != playback || handedOffToBackgroundAudio) {
                            MorpheStreamServer.cancel(repaired); return;
                        }
                        if (repaired.length() == 0) {
                            failForegroundVideo(playback, recovery, reason
                                    + ". No compatible combined H.264/AAC fallback is available for this video.");
                            return;
                        }
                        playback.localVideoProxyUrl = repaired; playback.localAudioProxyUrl = repaired;
                        playback.muxedLocally = true; playback.muxNote = "H.264 + AAC audio recovery";
                        qualityBtn.setText("H.264 + AAC (audio recovery)");
                        prepareForegroundVideo(playback, Uri.parse(repaired), recovery);
                    }
                });
            }
        }, "Arc7-YouTube-Audio-Recovery");
        resolutionThread.start();
    }

    private void failForegroundVideo(MorpheEngine.ResolvedPlayback playback, int request, String error) {
        if (!alive || request != videoRequestGeneration || currentPlayback != playback || handedOffToBackgroundAudio) return;
        cancelVideoPrepareTimeout();
        videoPrepared = false;
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        videoAudioStatus = "Playback failed: " + error;
        ca.arc7freedom.support.DebugLog.event("video.playback_failed");
        NowPlayingManager.clearSession(this);
        try { videoView.stopPlayback(); } catch (Throwable ignored) {}
        if (!audioRepairAttempted && playback.localAudioProxyUrl.length() > 0
                && !playback.localAudioProxyUrl.equals(playback.localVideoProxyUrl)) {
            switchCurrentToBackgroundAudio(false);
            return;
        }
        String preparation = MorpheStreamServer.getFailure(playback.localVideoProxyUrl);
        MorpheEngine.cancelPlayback(playback);
        playerOverlayStatusTv.setVisibility(View.VISIBLE);
        playerOverlayStatusTv.setText("Playback failed: " + error
                + (preparation.length() > 0 ? "\n" + preparation : "")
                + "\nTry another video/client in Morphe. Metadata alone is not playable media.");
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
        cancelVideoPrepareTimeout();
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        videoPrepared = false;
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
        playerOverlayStatusTv.setText("\uD83C\uDFA7 Preparing background audio\n"
                + currentPlayback.title + "\n(Controls appear only after the player is ready.)");
        if (closeForegroundVideo) {
            Toast.makeText(this, "Preparing background audio — you can lock the screen while it loads.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleForegroundVideo() {
        if (videoView == null || currentPlayback == null || !videoPrepared || handedOffToBackgroundAudio) return;
        if (videoView.isPlaying()) {
            videoPlayWhenPrepared=false;
            videoView.pause();
            releaseVideoAudioFocus();
            playPauseBtn.setText("\u25B6 Play");
        } else {
            videoPlayWhenPrepared=true;
            if (!takeVideoAudioFocus()) {
                Toast.makeText(this, "Audio is in use by another app. Pause it and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                if (foregroundPlayer != null) foregroundPlayer.setVolume(1.0f, 1.0f);
            } catch (RuntimeException releasedSurfacePlayer) {
                // VideoView releases its native player when the surface disappears.
                // Wait for the new prepared callback rather than touching that old player.
                foregroundPlayer = null;
                videoPrepared = false;
                releaseVideoAudioFocus();
                return;
            }
            videoView.start();
            playerOverlayStatusTv.setVisibility(View.GONE);
            NowPlayingManager.setYouTubeForegroundVideoActive(true);
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
        ++videoRequestGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        cancelVideoPrepareTimeout();
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        videoAudioStatus = "No foreground player is ready.";
        videoPrepared = false;
        NowPlayingManager.setYouTubeForegroundVideoActive(false);
        try { if (videoView != null) videoView.stopPlayback(); } catch (Throwable ignored) {}
        if (!handedOffToBackgroundAudio) {
            MorpheEngine.cancelPlayback(currentPlayback);
            NowPlayingManager.clearSession(this);
        }
        currentPlayback = null;
        activeResolutionVideoId = "";
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
                        if (currentPlayback == null) return;
                        final MorpheEngine.ResolvedPlayback selectedPlayback = currentPlayback;
                        final int curPos = videoPrepared && videoView != null ? videoView.getCurrentPosition() : 0;
                        final int request = ++videoRequestGeneration;
                        if (resolutionThread != null) resolutionThread.interrupt();
                        // The old focus callback belongs to the old request. Do not leave that
                        // player running unprotected while a new quality resolves, or hold a
                        // completed cache job that prevents admission in the low-RAM profile.
                        cancelVideoPrepareTimeout();releaseVideoAudioFocus();
                        foregroundPlayer=null;videoPrepared=false;
                        try { videoView.stopPlayback(); } catch (Throwable ignored) {}
                        MorpheEngine.cancelPlayback(selectedPlayback);
                        selectedPlayback.localVideoProxyUrl="";selectedPlayback.localAudioProxyUrl="";
                        NowPlayingManager.clearSession(YouTubeActivity.this);
                        final MorpheEngine.StreamVariant picked = variants.get(which);
                        final boolean soundOnly = audioOnly.get(which).booleanValue();
                        qualityBtn.setText("\uD83C\uDF9E Building " + picked.label + " ...");
                        playerOverlayStatusTv.setVisibility(View.VISIBLE);
                        playerOverlayStatusTv.setText("Preparing the selected quality. Playback is paused until it is ready.");
                        resolutionThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String slotUrl = soundOnly
                                        ? MorpheEngine.startAudioForVariant(YouTubeActivity.this, picked)
                                        : MorpheEngine.startMuxForVariant(YouTubeActivity.this, selectedPlayback, picked);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!alive || request != videoRequestGeneration || currentPlayback != selectedPlayback) {
                                            MorpheStreamServer.cancel(slotUrl); MorpheHlsRelay.cancel(slotUrl); return;
                                        }
                                        if (slotUrl == null || slotUrl.length() == 0) {
                                            qualityBtn.setText("\uD83C\uDF9E Quality unavailable");
                                            return;
                                        }
                                        qualityBtn.setText("\uD83C\uDF9E " + picked.label);
                                        try { videoView.stopPlayback(); } catch (Throwable ignored) {}
                                        MorpheEngine.cancelPlayback(selectedPlayback);
                                        selectedPlayback.localVideoProxyUrl = slotUrl;
                                        selectedPlayback.localAudioProxyUrl = slotUrl;
                                        handedOffToBackgroundAudio = false;
                                        audioRepairAttempted = false;
                                        prepareForegroundVideo(selectedPlayback, Uri.parse(slotUrl), request);
                                        videoView.requestFocus();
                                        if (curPos > 1500) videoView.seekTo(curPos);
                                    }
                                });
                            }
                        }, "Arc7-YouTube-Quality");
                        resolutionThread.start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptOfflineDownload() {
        if (currentPlayback == null) return;
        final MorpheEngine.ResolvedPlayback download=currentPlayback;
        new AlertDialog.Builder(this).setTitle("Download Offline: "+download.title)
            .setItems(new String[]{"MP4 video — Movies/YouTube", "M4A audio — Music/YouTubeMusic"},new DialogInterface.OnClickListener(){
                @Override public void onClick(DialogInterface d,int which){
                    final boolean audioOnly=which==1;
                    if(downloads==null) downloads=new Arc7DownloadUi(YouTubeActivity.this);
                    downloads.start(download.title,new Arc7DownloadUi.Task(){
                        @Override public File run(MorpheEngine.DownloadProgressListener progress)throws Exception {
                            return MorpheEngine.downloadResolvedOffline(YouTubeActivity.this,download,download.title,audioOnly,progress);
                        }
                    });
                }
            }).setNegativeButton("Cancel",null).show();
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

    private void showPinnedChannels() {
        ++feedRequestGeneration;
        feedHeaderTv.setText("Subscriptions · pinned on this device only (not Google account subscriptions)");
        feedList.removeAllViews(); currentFeed.clear(); currentFeedIdx=-1;
        Button add=pillBtn("+ Add channel by @handle / channel URL",0xFF334155); add.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ promptPinnedChannel(); }}); feedList.addView(add);
        final List<MorpheEngine.MediaItem> channels=MorpheEngine.loadSavedList(this,"yt_pinned_channels");
        for(final MorpheEngine.MediaItem channel:channels) {
            LinearLayout row=Arc7Skin.card(this,Arc7Skin.current(this));
            TextView title=new TextView(this); title.setText(channel.title+"\n"+channel.channelId); title.setTextColor(Color.WHITE); row.addView(title);
            Button open=pillBtn("Latest uploads",0xFF334155); open.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ openPinnedChannel(channel); }}); row.addView(open);
            Button remove=pillBtn("Unsubscribe / unpin locally",0xFF334155); remove.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                MorpheEngine.addOrToggleSavedItem(YouTubeActivity.this,"yt_pinned_channels",channel,true); showPinnedChannels();
            }}); row.addView(remove); feedList.addView(row);
        }
    }
    private void promptPinnedChannel() {
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(16),dp(8),dp(16),dp(8));
        final EditText name=new EditText(this); name.setHint("Channel label (optional)"); name.setSingleLine(true); form.addView(name);
        final EditText url=new EditText(this); url.setHint("@handle, UC… id or YouTube channel URL"); url.setSingleLine(true); form.addView(url);
        new AlertDialog.Builder(this).setTitle("Subscribe / pin channel locally").setView(form).setPositiveButton("Pin",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int which){
            final String input=url.getText().toString(), label=name.getText().toString();
            final int feedRequest=++feedRequestGeneration;
            new Thread(new Runnable(){ public void run(){
                try { final MorpheEngine.MediaItem channel=MorpheEngine.resolvePinnedChannel(YouTubeActivity.this,input,label);
                    ui.post(new Runnable(){ public void run(){ if(!alive) return; MorpheEngine.addOrToggleSavedItem(YouTubeActivity.this,"yt_pinned_channels",channel,false); if(feedRequest==feedRequestGeneration) showPinnedChannels(); }});
                } catch(final Exception e){ ui.post(new Runnable(){ public void run(){ if(alive) Toast.makeText(YouTubeActivity.this,"Could not pin channel: "+e.getMessage(),Toast.LENGTH_LONG).show(); }}); }
            }},"Arc7-Pin-Channel").start();
        }}).setNegativeButton("Cancel",null).show();
    }
    private void openPinnedChannel(final MorpheEngine.MediaItem channel) {
        final int feedRequest=++feedRequestGeneration;
        feedHeaderTv.setText("Loading recent public uploads from "+channel.title+"…");
        new Thread(new Runnable(){ public void run(){
            try { final List<MorpheEngine.MediaItem> videos=MorpheEngine.pinnedChannelVideos(YouTubeActivity.this,channel.channelId);
                ui.post(new Runnable(){ public void run(){ if(!alive || feedRequest!=feedRequestGeneration) return; feedHeaderTv.setText(channel.title+" · "+videos.size()+" recent uploads (YouTube's public feed)"); renderFeedItems(videos); }});
            } catch(final Exception e){ ui.post(new Runnable(){ public void run(){ if(alive && feedRequest==feedRequestGeneration) feedHeaderTv.setText("Channel feed unavailable: "+e.getMessage()); }}); }
        }},"Arc7-Channel-Feed").start();
    }

    private void showLocalSavedShelf(String key, String label) {
        ++feedRequestGeneration;
        List<MorpheEngine.MediaItem> saved = MorpheEngine.loadSavedList(this, key);
        feedHeaderTv.setText(label + " (" + saved.size() + ")");
        renderFeedItems(saved);
    }

    private void showOfflineDownloadsShelf() {
        ++feedRequestGeneration;
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
        m.channelId = r.channelId;
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
        iv.setTag(url);
        Bitmap cached;
        synchronized(THUMB_CACHE){
            java.util.Iterator<String> keys=THUMB_CACHE.keySet().iterator();
            while(THUMB_CACHE.size()>ca.arc7freedom.support.RuntimePolicy.thumbnailEntries()&&keys.hasNext()){keys.next();keys.remove();}
            cached=THUMB_CACHE.get(url);
            int edge=ca.arc7freedom.support.RuntimePolicy.artworkEdge();
            if(cached!=null&&(cached.getWidth()>edge||cached.getHeight()>edge)){THUMB_CACHE.remove(url);cached=null;}
        }
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(url);
        ca.arc7freedom.support.RuntimePolicy.artwork(new Runnable() {
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
                        byte[] bytes = bos.toByteArray();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                        int edge = ca.arc7freedom.support.RuntimePolicy.artworkEdge();
                        int sample = 1;
                        while (o.outWidth / sample > edge || o.outHeight / sample > edge) sample *= 2;
                        o.inJustDecodeBounds = false; o.inSampleSize = sample;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                        if (bmp != null) {
                            synchronized (THUMB_CACHE) {
                                THUMB_CACHE.put(url, bmp);
                                while (THUMB_CACHE.size() > ca.arc7freedom.support.RuntimePolicy.thumbnailEntries()) {
                                    java.util.Iterator<String> keys = THUMB_CACHE.keySet().iterator(); if (!keys.hasNext()) break; keys.next(); keys.remove();
                                }
                            }
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (alive && url.equals(iv.getTag())) iv.setImageBitmap(bmp);
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
        });
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
        alive = false;
        releaseVideoAudioFocus();
        foregroundPlayer = null;
        if(downloads!=null) downloads.close();
        ++videoRequestGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        if (!handedOffToBackgroundAudio) MorpheEngine.cancelPlayback(currentPlayback);
        cancelVideoPrepareTimeout();
        ui.removeCallbacks(progressTicker);
        try { unregisterReceiver(fgControlReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(videoNoisy); } catch (Throwable ignored) {}
        if (!handedOffToBackgroundAudio) {
            stopForegroundVideo();
        }
        super.onDestroy();
    }
}
