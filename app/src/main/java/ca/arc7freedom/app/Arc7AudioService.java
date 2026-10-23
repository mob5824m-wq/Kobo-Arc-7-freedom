package ca.arc7freedom.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Universal Background Screen-Off Music, YouTube Music (Morphe), YouTube Background Audio,
 * Live Internet Radio, Audiobook & System-Wide Media Notification Service (`v2.4.0`).
 */
public class Arc7AudioService extends Service {

    public static final int NOTIF_ID_MEDIA = 4207;

    public static final String ACTION_TOGGLE               = "ca.arc7freedom.app.AUDIO_TOGGLE";
    public static final String ACTION_PREV                 = "ca.arc7freedom.app.AUDIO_PREV";
    public static final String ACTION_NEXT                 = "ca.arc7freedom.app.AUDIO_NEXT";
    public static final String ACTION_REWIND_30            = "ca.arc7freedom.app.AUDIO_REWIND_30";
    public static final String ACTION_FFWD_30              = "ca.arc7freedom.app.AUDIO_FFWD_30";
    public static final String ACTION_PLAY_STREAM          = "ca.arc7freedom.app.AUDIO_PLAY_STREAM";
    public static final String ACTION_SLEEP_TIMER          = "ca.arc7freedom.app.AUDIO_SLEEP_TIMER";
    public static final String ACTION_STOP_DISMISS         = "ca.arc7freedom.app.AUDIO_STOP_DISMISS";
    public static final String ACTION_REFRESH_NOTIFICATION = "ca.arc7freedom.app.AUDIO_REFRESH_NOTIF";
    public static final String ACTION_ENSURE_WATCHER       = "ca.arc7freedom.app.AUDIO_ENSURE_WATCHER";

    public static final String EXTRA_STREAM_URL   = "stream_url";
    public static final String EXTRA_STREAM_TITLE = "stream_title";
    public static final String EXTRA_STREAM_ARTIST = "stream_artist";
    public static final String EXTRA_STREAM_SOURCE = "stream_source";
    public static final String EXTRA_STREAM_THUMB  = "stream_thumb";
    public static final String EXTRA_LAUNCH_CLASS  = "launch_class";
    public static final String EXTRA_SLEEP_MINS   = "sleep_minutes";

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    public static final String[] EQ_PRESET_NAMES = new String[]{
            "Flat / Studio Reference",
            "Morphe Warm Bass",
            "Deep Sub-Bass Boost",
            "Clear Vocals / Podcast",
            "Acoustic & Classical",
            "Electronic / Pop Punch",
            "Rock & Live Stage"
    };

    private static final String PREFS = "arc7_audio_bookmarks";

    public class LocalBinder extends Binder {
        public Arc7AudioService getService() {
            return Arc7AudioService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private WifiManager.WifiLock wifiLock;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private int currentEqPreset = 0;

    // Local file queue (`Music` app)
    private final List<File> playlist = new ArrayList<File>();
    private int currentIndex = -1;

    // Morphe YouTube Music / YouTube Background remote track queue
    private final List<MorpheEngine.MediaItem> morpheQueue = new ArrayList<MorpheEngine.MediaItem>();
    private int morpheIndex = -1;
    private boolean shuffleEnabled = false;
    private int repeatMode = REPEAT_OFF;
    private final List<MorpheEngine.SkipSegment> activeSkipSegments = new ArrayList<MorpheEngine.SkipSegment>();
    private volatile boolean resolvingMorpheTrack = false;

    private String activeStreamTitle = null;
    private String activeStreamArtist = "";
    private String activeSourceApp = "Music";
    private String activeLaunchClass = "ca.arc7freedom.app.Arc7AudioPlayerActivity";
    private String activeArtworkUrl = "";
    private long sleepDeadlineMs = 0L;
    private boolean foregroundActive = false;

    private final BroadcastReceiver externalMediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            NowPlayingManager.onExternalMediaBroadcast(context, intent);
        }
    };

    private final Runnable checkpointTask = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            handleSleepFadeTick();
            handleSponsorBlockAudioSkipTick();

            if (isPlaying()) {
                publishOwnPlaybackState(true);
            } else {
                // Detect any external app audio (`VLC`, browser HTML5, games, etc.) automatically!
                boolean ext = NowPlayingManager.syncExternalAudioIfNeeded(getApplicationContext(), false);
                if (ext || NowPlayingManager.getSnapshot(null).active) {
                    updateMediaNotificationFromSnapshot();
                }
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private void handleSleepFadeTick() {
        if (sleepDeadlineMs > 0L) {
            long remMs = sleepDeadlineMs - System.currentTimeMillis();
            if (remMs <= 0L) {
                sleepDeadlineMs = 0L;
                setPlayerVolumeSafe(1.0f);
                pausePlayback();
            } else if (remMs <= 30000L) {
                float vol = Math.max(0.03f, remMs / 30000f);
                setPlayerVolumeSafe(vol * vol);
            }
        }
    }

    private void handleSponsorBlockAudioSkipTick() {
        if (player == null || activeSkipSegments.isEmpty() || !MorpheEngine.isSponsorBlockEnabled(this)) {
            return;
        }
        try {
            if (!player.isPlaying()) return;
            float posSec = player.getCurrentPosition() / 1000f;
            for (int i = 0; i < activeSkipSegments.size(); i++) {
                MorpheEngine.SkipSegment seg = activeSkipSegments.get(i);
                if (posSec >= seg.startSec && posSec < seg.endSec - 0.6f) {
                    player.seekTo((int) (seg.endSec * 1000f));
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void setPlayerVolumeSafe(float v) {
        if (player != null) {
            try { player.setVolume(v, v); } catch (Throwable ignored) {}
        }
    }

    public static void ensureWatcherRunning(Context ctx) {
        if (ctx == null) return;
        try {
            Intent svc = new Intent(ctx.getApplicationContext(), Arc7AudioService.class);
            svc.setAction(ACTION_ENSURE_WATCHER);
            ctx.getApplicationContext().startService(svc);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Arc7AudioHighPerfWifi");
                wifiLock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {}

        try {
            IntentFilter f = new IntentFilter();
            f.addAction("com.android.music.metachanged");
            f.addAction("com.android.music.playstatechanged");
            f.addAction("com.android.music.playbackcomplete");
            f.addAction("com.android.music.queuechanged");
            f.addAction("org.videolan.vlc.player.newtrack");
            registerReceiver(externalMediaReceiver, f);
        } catch (Throwable ignored) {}

        handler.postDelayed(checkpointTask, 1000L);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_ENSURE_WATCHER.equals(act)) {
                return START_STICKY;
            } else if (ACTION_REFRESH_NOTIFICATION.equals(act)) {
                updateMediaNotificationFromSnapshot();
                return START_STICKY;
            } else if (ACTION_STOP_DISMISS.equals(act)) {
                stopAndDismissNotification();
                return START_STICKY;
            } else if (ACTION_TOGGLE.equals(act)) {
                if (NowPlayingManager.isYouTubeForegroundVideoActive()) {
                    NowPlayingManager.dispatchTransportAction(this, ACTION_TOGGLE);
                    return START_STICKY;
                }
                NowPlayingManager.Snapshot snap = NowPlayingManager.getSnapshot(this);
                if (snap.active && !getPackageName().equals(snap.sourcePkg)) {
                    NowPlayingManager.dispatchTransportAction(this, ACTION_TOGGLE);
                    return START_STICKY;
                }
                togglePlayPause();
            } else if (ACTION_PREV.equals(act)) {
                if (NowPlayingManager.isYouTubeForegroundVideoActive()
                        || !getPackageName().equals(NowPlayingManager.getSnapshot(this).sourcePkg)) {
                    NowPlayingManager.dispatchTransportAction(this, ACTION_PREV);
                    return START_STICKY;
                }
                stepQueue(-1);
            } else if (ACTION_NEXT.equals(act)) {
                if (NowPlayingManager.isYouTubeForegroundVideoActive()
                        || !getPackageName().equals(NowPlayingManager.getSnapshot(this).sourcePkg)) {
                    NowPlayingManager.dispatchTransportAction(this, ACTION_NEXT);
                    return START_STICKY;
                }
                stepQueue(1);
            } else if (ACTION_REWIND_30.equals(act)) {
                seekRelativeMs(-15000);
            } else if (ACTION_FFWD_30.equals(act)) {
                seekRelativeMs(30000);
            } else if (ACTION_PLAY_STREAM.equals(act)) {
                final String url = intent.getStringExtra(EXTRA_STREAM_URL);
                final String title = intent.getStringExtra(EXTRA_STREAM_TITLE);
                final String artist = intent.getStringExtra(EXTRA_STREAM_ARTIST);
                final String src = intent.getStringExtra(EXTRA_STREAM_SOURCE);
                final String thumb = intent.getStringExtra(EXTRA_STREAM_THUMB);
                final String cls = intent.getStringExtra(EXTRA_LAUNCH_CLASS);
                if (url != null && !url.isEmpty()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            playStreamUrlFull(url, title, artist, src, thumb, cls);
                        }
                    }).start();
                }
            } else if (ACTION_SLEEP_TIMER.equals(act)) {
                setSleepTimerMinutes(intent.getIntExtra(EXTRA_SLEEP_MINS, 30));
            }
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), getCurrentTitle(), isPlaying());
        }
        return START_STICKY;
    }

    /* ==================== Rich Android 4.2 Foreground/Ongoing Media Notification ==================== */

    public void updateMediaNotificationFromSnapshot() {
        try {
            NowPlayingManager.Snapshot snap = NowPlayingManager.getSnapshot(null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (!snap.active) {
                if (foregroundActive) {
                    foregroundActive = false;
                    stopForeground(true);
                }
                nm.cancel(NOTIF_ID_MEDIA);
                return;
            }

            PendingIntent openPi = PendingIntent.getActivity(
                    this, 700, NowPlayingManager.buildOpenPlayerIntent(this, snap),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent prevPi = serviceActionPi(701, ACTION_PREV);
            PendingIntent togglePi = serviceActionPi(702, ACTION_TOGGLE);
            PendingIntent nextPi = serviceActionPi(703, ACTION_NEXT);
            PendingIntent rewPi = serviceActionPi(704, ACTION_REWIND_30);
            PendingIntent ffwdPi = serviceActionPi(705, ACTION_FFWD_30);
            PendingIntent stopPi = serviceActionPi(706, ACTION_STOP_DISMISS);

            long livePos = snap.livePositionMs();
            String timeBadge = snap.durationMs > 0L
                    ? (NowPlayingManager.formatDurationMs(livePos) + " / " + NowPlayingManager.formatDurationMs(snap.durationMs))
                    : (snap.playing ? "LIVE" : "PAUSED");
            String subLine = (snap.artist != null && snap.artist.length() > 0)
                    ? (snap.artist + "  \u2022  " + snap.sourceApp + "  \u2022  " + timeBadge)
                    : (snap.sourceApp + "  \u2022  " + timeBadge);

            int smallIconRes = resolveSmallIconForSource(snap.sourceApp);

            RemoteViews compact = new RemoteViews(getPackageName(), R.layout.notif_media_compact);
            compact.setTextViewText(R.id.notif_media_title, snap.title);
            compact.setTextViewText(R.id.notif_media_subtitle, subLine);
            compact.setTextViewText(R.id.notif_btn_toggle, snap.playing ? "\u23F8" : "\u25B6");
            if (snap.artwork != null) {
                compact.setImageViewBitmap(R.id.notif_media_art, snap.artwork);
            } else {
                compact.setImageViewResource(R.id.notif_media_art, smallIconRes);
            }
            compact.setOnClickPendingIntent(R.id.notif_media_root, openPi);
            compact.setOnClickPendingIntent(R.id.notif_btn_prev, prevPi);
            compact.setOnClickPendingIntent(R.id.notif_btn_toggle, togglePi);
            compact.setOnClickPendingIntent(R.id.notif_btn_next, nextPi);
            compact.setOnClickPendingIntent(R.id.notif_btn_close, stopPi);

            RemoteViews expanded = new RemoteViews(getPackageName(), R.layout.notif_media_expanded);
            expanded.setTextViewText(R.id.notif_media_source,
                    (snap.playing ? "\u266B NOW PLAYING  \u2022  " : "\u23F8 PAUSED  \u2022  ")
                            + snap.sourceApp.toUpperCase(Locale.US) + "  \u2022  " + timeBadge);
            expanded.setTextViewText(R.id.notif_media_title, snap.title);
            expanded.setTextViewText(R.id.notif_media_subtitle,
                    snap.artist != null && snap.artist.length() > 0 ? snap.artist : snap.sourceApp);
            expanded.setTextViewText(R.id.notif_btn_toggle, snap.playing ? "\u23F8 Pause" : "\u25B6 Play");
            int progPermille = (snap.durationMs > 0L)
                    ? (int) Math.min(1000L, (livePos * 1000L) / snap.durationMs)
                    : (snap.playing ? 1000 : 0);
            expanded.setProgressBar(R.id.notif_media_progress, 1000, progPermille, false);
            if (snap.artwork != null) {
                expanded.setImageViewBitmap(R.id.notif_media_art, snap.artwork);
            } else {
                expanded.setImageViewResource(R.id.notif_media_art, smallIconRes);
            }
            expanded.setOnClickPendingIntent(R.id.notif_media_root, openPi);
            expanded.setOnClickPendingIntent(R.id.notif_btn_rew, rewPi);
            expanded.setOnClickPendingIntent(R.id.notif_btn_prev, prevPi);
            expanded.setOnClickPendingIntent(R.id.notif_btn_toggle, togglePi);
            expanded.setOnClickPendingIntent(R.id.notif_btn_next, nextPi);
            expanded.setOnClickPendingIntent(R.id.notif_btn_ffwd, ffwdPi);
            expanded.setOnClickPendingIntent(R.id.notif_btn_close, stopPi);

            Notification.Builder nb = new Notification.Builder(this)
                    .setSmallIcon(smallIconRes)
                    .setContentTitle(snap.title)
                    .setContentText(subLine)
                    .setContentIntent(openPi)
                    .setDeleteIntent(stopPi)
                    .setOngoing(snap.playing);
            if (Build.VERSION.SDK_INT >= 16) {
                nb.setPriority(Notification.PRIORITY_HIGH);
            }
            Notification n = Build.VERSION.SDK_INT >= 16 ? nb.build() : nb.getNotification();
            n.contentView = compact;
            if (Build.VERSION.SDK_INT >= 16) {
                n.bigContentView = expanded;
            }

            if (snap.playing) {
                startForeground(NOTIF_ID_MEDIA, n);
                foregroundActive = true;
            } else {
                if (foregroundActive) {
                    foregroundActive = false;
                    stopForeground(false);
                }
                nm.notify(NOTIF_ID_MEDIA, n);
            }
        } catch (Throwable ignored) {}
    }

    private int resolveSmallIconForSource(String src) {
        if (src == null) return R.drawable.ic_audio;
        String l = src.toLowerCase(Locale.US);
        if (l.contains("youtube music")) return R.drawable.ic_ytmusic;
        if (l.contains("youtube")) return R.drawable.ic_youtube;
        if (l.contains("radio") || l.contains("podcast")) return R.drawable.ic_radio;
        if (l.contains("recorder")) return R.drawable.ic_recorder;
        if (l.contains("vlc")) return R.drawable.ic_vlc;
        return R.drawable.ic_audio;
    }

    private PendingIntent serviceActionPi(int reqCode, String action) {
        Intent svc = new Intent(this, Arc7AudioService.class);
        svc.setAction(action);
        return PendingIntent.getService(this, reqCode, svc, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void publishOwnPlaybackState(boolean playing) {
        NowPlayingManager.setYouTubeForegroundVideoActive(false);
        NowPlayingManager.publishBuiltIn(
                getApplicationContext(),
                activeSourceApp,
                activeLaunchClass,
                getCleanTitle(),
                activeStreamArtist,
                "",
                activeArtworkUrl,
                null,
                playing,
                getPositionMs(),
                getDurationMs());
    }

    public synchronized void stopAndDismissNotification() {
        pausePlayback();
        releasePlayer();
        morpheIndex = -1;
        activeStreamTitle = null;
        NowPlayingManager.clearSession(getApplicationContext());
        if (foregroundActive) {
            foregroundActive = false;
            stopForeground(true);
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID_MEDIA);
    }

    /* ==================== Morphe YouTube Music & YouTube Background Queue ==================== */

    public void playMorpheQueue(final List<MorpheEngine.MediaItem> items,
                                final int startIdx,
                                final String sourceLabel,
                                final String launchActivityCls) {
        if (items == null || items.isEmpty()) return;
        synchronized (this) {
            morpheQueue.clear();
            morpheQueue.addAll(items);
            if (shuffleEnabled && morpheQueue.size() > 2 && startIdx >= 0 && startIdx < morpheQueue.size()) {
                MorpheEngine.MediaItem chosen = morpheQueue.remove(startIdx);
                Collections.shuffle(morpheQueue);
                morpheQueue.add(0, chosen);
                morpheIndex = 0;
            } else {
                morpheIndex = Math.max(0, Math.min(items.size() - 1, startIdx));
            }
            activeSourceApp = sourceLabel != null ? sourceLabel : "YouTube Music";
            activeLaunchClass = launchActivityCls != null
                    ? launchActivityCls
                    : "ca.arc7freedom.app.YouTubeMusicActivity";
        }
        startMorpheQueueAt(morpheIndex);
    }

    public void appendToMorpheQueue(MorpheEngine.MediaItem item) {
        if (item == null) return;
        synchronized (this) {
            morpheQueue.add(item);
        }
    }

    public List<MorpheEngine.MediaItem> getMorpheQueueSnapshot() {
        synchronized (this) {
            return new ArrayList<MorpheEngine.MediaItem>(morpheQueue);
        }
    }

    public int getMorpheQueueIndex() {
        return morpheIndex;
    }

    public MorpheEngine.MediaItem getCurrentMorpheItem() {
        synchronized (this) {
            if (morpheIndex >= 0 && morpheIndex < morpheQueue.size()) {
                return morpheQueue.get(morpheIndex);
            }
            return null;
        }
    }

    public void startMorpheQueueAt(final int idx) {
        final MorpheEngine.MediaItem item;
        synchronized (this) {
            if (idx < 0 || idx >= morpheQueue.size()) return;
            morpheIndex = idx;
            currentIndex = -1;
            item = morpheQueue.get(idx);
            activeStreamTitle = item.title;
            activeStreamArtist = item.author != null ? item.author : "";
            activeArtworkUrl = item.bestThumb();
            activeSkipSegments.clear();
        }
        resolvingMorpheTrack = true;
        publishOwnPlaybackState(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Play downloaded offline `.m4a` / `.mp4` instantly if present!
                    if (item.localFilePath != null && item.localFilePath.length() > 0
                            && new File(item.localFilePath).exists()) {
                        resolvingMorpheTrack = false;
                        playPreparedDataSource(item.localFilePath, item.title, item.author,
                                activeSourceApp, item.bestThumb(), activeLaunchClass, true);
                        return;
                    }
                    MorpheEngine.ResolvedPlayback res = MorpheEngine.resolveStreams(
                            getApplicationContext(), item.videoId, true);
                    if (res.author != null && res.author.length() > 0 && item.author.length() == 0) {
                        item.author = res.author;
                    }
                    if (res.title != null && res.title.length() > 0) {
                        item.title = res.title;
                    }
                    synchronized (Arc7AudioService.this) {
                        activeSkipSegments.clear();
                        activeSkipSegments.addAll(res.skipSegments);
                        // Auto-populate Up-Next Radio Mix if single track was tapped!
                        if (morpheQueue.size() == 1 && !res.relatedItems.isEmpty()) {
                            for (int i = 0; i < res.relatedItems.size() && morpheQueue.size() < 25; i++) {
                                morpheQueue.add(res.relatedItems.get(i));
                            }
                        }
                    }
                    resolvingMorpheTrack = false;
                    String playable = res.localAudioProxyUrl;
                    if (playable == null || playable.length() == 0) {
                        playable = res.localVideoProxyUrl;
                    }
                    if (playable == null || playable.length() == 0) {
                        throw new Exception("No playable stream" + (res.muxNote.length() > 0 ? " (" + res.muxNote + ")" : ""));
                    }
                    playPreparedDataSource(
                            playable,
                            item.title,
                            item.author,
                            activeSourceApp,
                            res.thumbnailUrl.length() > 0 ? res.thumbnailUrl : item.bestThumb(),
                            activeLaunchClass,
                            true);
                } catch (final Throwable t) {
                    resolvingMorpheTrack = false;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(),
                                    "Stream error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    public boolean isResolvingMorpheTrack() {
        return resolvingMorpheTrack;
    }

    public boolean isShuffleEnabled() { return shuffleEnabled; }
    public void setShuffleEnabled(boolean s) { shuffleEnabled = s; }
    public int getRepeatMode() { return repeatMode; }
    public void setRepeatMode(int mode) { repeatMode = mode % 3; }

    /* ==================== Equalizer & BassBoost Hardware DSP ==================== */

    public synchronized void applyEqualizerPreset(int presetIdx) {
        currentEqPreset = Math.max(0, Math.min(EQ_PRESET_NAMES.length - 1, presetIdx));
        if (player == null) return;
        try {
            if (equalizer == null) {
                equalizer = new Equalizer(0, player.getAudioSessionId());
                equalizer.setEnabled(true);
            }
            if (bassBoost == null) {
                bassBoost = new BassBoost(0, player.getAudioSessionId());
                bassBoost.setEnabled(true);
            }
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            short maxGain = range != null && range.length > 1 ? range[1] : 1200;
            // Curves across low -> mid -> high bands (in permille of maxGain)
            int[][] curves = new int[][]{
                    {0, 0, 0, 0, 0},
                    {550, 320, 40, 180, 300},
                    {850, 500, -80, 80, 200},
                    {-200, 120, 620, 520, 180},
                    {220, 180, 120, 340, 450},
                    {620, 220, -120, 320, 580},
                    {520, 260, -60, 360, 520}
            };
            int[] curve = curves[currentEqPreset];
            for (short b = 0; b < bands; b++) {
                int cIdx = (b * (curve.length - 1)) / Math.max(1, bands - 1);
                short lvl = (short) ((curve[cIdx] * maxGain) / 1000);
                equalizer.setBandLevel(b, lvl);
            }
            short boostStrength = (short) (currentEqPreset == 2 ? 850 : (currentEqPreset == 1 ? 500 : 120));
            bassBoost.setStrength(boostStrength);
        } catch (Throwable ignored) {}
    }

    public int getCurrentEqPreset() {
        return currentEqPreset;
    }

    /* ==================== Stream & Local File Playback Engine ==================== */

    public synchronized void playStreamUrl(String streamUrl, final String title) {
        playStreamUrlFull(streamUrl, title, "Live Stream", "Radio & Podcasts",
                "", "ca.arc7freedom.app.Arc7RadioRssActivity");
    }

    public synchronized void playStreamUrlFull(String streamUrl,
                                               String title,
                                               String artist,
                                               String sourceApp,
                                               String thumbUrl,
                                               String launchCls) {
        morpheIndex = -1;
        currentIndex = -1;
        String effectiveUrl = streamUrl;
        try {
            if (streamUrl.startsWith("https://")) {
                RootShell sh = new RootShell(getApplicationContext());
                NetworkCertManager cm = new NetworkCertManager(getApplicationContext(), sh);
                cm.ensureCaBundleFile();
                cm.startBrowserGateway();
                int port = NetworkCertManager.BROWSER_GATEWAY_PORT;
                effectiveUrl = "http://127.0.0.1:" + port + "/__arc7_raw?u="
                        + URLEncoder.encode(streamUrl, "UTF-8");
            }
        } catch (Throwable ignored) {}
        playPreparedDataSource(effectiveUrl,
                title == null || title.isEmpty() ? "Live Internet Stream" : title,
                artist != null ? artist : "",
                sourceApp != null ? sourceApp : "Radio & Podcasts",
                thumbUrl != null ? thumbUrl : "",
                launchCls != null ? launchCls : "ca.arc7freedom.app.Arc7RadioRssActivity",
                false);
    }

    private synchronized void playPreparedDataSource(final String dataSource,
                                                     final String title,
                                                     final String artist,
                                                     final String sourceApp,
                                                     final String thumbUrl,
                                                     final String launchCls,
                                                     final boolean autoAdvanceQueueOnComplete) {
        saveCurrentPosition();
        releasePlayer();
        activeStreamTitle = title;
        activeStreamArtist = artist != null ? artist : "";
        activeSourceApp = sourceApp != null ? sourceApp : "Music";
        activeArtworkUrl = thumbUrl != null ? thumbUrl : "";
        activeLaunchClass = launchCls != null ? launchCls : "ca.arc7freedom.app.Arc7AudioPlayerActivity";

        try {
            acquireWifiLockSafe();
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(dataSource);
            player.setVolume(1.0f, 1.0f);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (autoAdvanceQueueOnComplete && !morpheQueue.isEmpty()) {
                        if (repeatMode == REPEAT_ONE) {
                            startMorpheQueueAt(morpheIndex);
                        } else if (morpheIndex + 1 < morpheQueue.size()) {
                            startMorpheQueueAt(morpheIndex + 1);
                        } else if (repeatMode == REPEAT_ALL) {
                            startMorpheQueueAt(0);
                        } else {
                            publishOwnPlaybackState(false);
                        }
                    } else {
                        publishOwnPlaybackState(false);
                    }
                }
            });
            player.prepare();
            applyEqualizerPreset(currentEqPreset);
            player.start();
            publishOwnPlaybackState(true);
        } catch (Throwable t) {
            releasePlayer();
        }
    }

    public synchronized void loadTrackAndFolderQueue(File pickedAudioFile) {
        morpheQueue.clear();
        morpheIndex = -1;
        activeStreamTitle = null;
        playlist.clear();
        File parent = pickedAudioFile.getParentFile();
        if (parent != null && parent.isDirectory()) {
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                Arrays.sort(siblings, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : siblings) {
                    if (f.isFile() && isAudioName(f.getName())) playlist.add(f);
                }
            }
        }
        if (playlist.isEmpty()) playlist.add(pickedAudioFile);
        currentIndex = 0;
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).getAbsolutePath().equals(pickedAudioFile.getAbsolutePath())) {
                currentIndex = i;
                break;
            }
        }
        startTrackAtIndex(currentIndex, true);
    }

    public static void startSleepTimer(Context ctx, int minutes) {
        Intent svc = new Intent(ctx, Arc7AudioService.class);
        svc.setAction(ACTION_SLEEP_TIMER);
        svc.putExtra(EXTRA_SLEEP_MINS, minutes);
        ctx.startService(svc);
    }

    public static boolean isAudioName(String n) {
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".aac") ||
               l.endsWith(".ogg") || l.endsWith(".flac") || l.endsWith(".wav") || l.endsWith(".opus");
    }

    public synchronized void startTrackAtIndex(int idx, boolean restoreBookmark) {
        if (idx < 0 || idx >= playlist.size()) return;
        saveCurrentPosition();
        releasePlayer();
        activeStreamTitle = null;
        morpheIndex = -1;
        currentIndex = idx;
        final File track = playlist.get(idx);
        activeSourceApp = "Music";
        activeLaunchClass = "ca.arc7freedom.app.Arc7AudioPlayerActivity";
        activeStreamArtist = track.getParentFile() != null ? track.getParentFile().getName() : "Local Library";
        activeArtworkUrl = "";

        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(track.getAbsolutePath());
            player.setVolume(1.0f, 1.0f);
            player.prepare();
            applyEqualizerPreset(currentEqPreset);

            if (restoreBookmark) {
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                int savedMs = sp.getInt("pos_" + track.getAbsolutePath().hashCode(), 0);
                if (savedMs > 5000 && savedMs < player.getDuration() - 8000) {
                    player.seekTo(savedMs);
                }
            }
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("pos_" + track.getAbsolutePath().hashCode(), 0).commit();
                    if (repeatMode == REPEAT_ONE) {
                        startTrackAtIndex(currentIndex, false);
                    } else if (currentIndex + 1 < playlist.size()) {
                        startTrackAtIndex(currentIndex + 1, true);
                    } else if (repeatMode == REPEAT_ALL && !playlist.isEmpty()) {
                        startTrackAtIndex(0, false);
                    } else {
                        publishOwnPlaybackState(false);
                    }
                }
            });
            player.start();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_track", track.getAbsolutePath()).commit();
            publishOwnPlaybackState(true);
        } catch (Throwable t) {
            releasePlayer();
        }
    }

    public synchronized void togglePlayPause() {
        if (player == null) {
            if (!morpheQueue.isEmpty() && morpheIndex >= 0) {
                startMorpheQueueAt(morpheIndex);
                return;
            }
            String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_track", "");
            if (!last.isEmpty() && new File(last).exists()) {
                loadTrackAndFolderQueue(new File(last));
            }
            return;
        }
        if (player.isPlaying()) {
            pausePlayback();
        } else {
            acquireWifiLockSafe();
            player.setVolume(1.0f, 1.0f);
            player.start();
            publishOwnPlaybackState(true);
        }
    }

    public synchronized void pausePlayback() {
        if (player != null && player.isPlaying()) {
            saveCurrentPosition();
            player.pause();
            releaseWifiLockSafe();
            publishOwnPlaybackState(false);
        }
    }

    public synchronized void seekRelativeMs(int deltaMs) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            if (dur <= 0) return;
            int next = Math.max(0, Math.min(dur, player.getCurrentPosition() + deltaMs));
            player.seekTo(next);
            saveCurrentPosition();
            publishOwnPlaybackState(player.isPlaying());
        } catch (Throwable ignored) {}
    }

    public synchronized void seekAbsoluteMs(int posMs) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            if (dur <= 0) return;
            player.seekTo(Math.max(0, Math.min(dur, posMs)));
            publishOwnPlaybackState(player.isPlaying());
        } catch (Throwable ignored) {}
    }

    public synchronized void stepTrack(int delta) {
        stepQueue(delta);
    }

    public void stepQueue(int delta) {
        if (!morpheQueue.isEmpty() && morpheIndex >= 0) {
            int next = morpheIndex + delta;
            if (next < 0) next = repeatMode == REPEAT_ALL ? morpheQueue.size() - 1 : 0;
            if (next >= morpheQueue.size()) {
                if (repeatMode == REPEAT_ALL) next = 0;
                else return;
            }
            startMorpheQueueAt(next);
            return;
        }
        synchronized (this) {
            int next = currentIndex + delta;
            if (next >= 0 && next < playlist.size()) {
                startTrackAtIndex(next, false);
            }
        }
    }

    public void setSleepTimerMinutes(int minutes) {
        if (minutes <= 0) {
            sleepDeadlineMs = 0L;
            setPlayerVolumeSafe(1.0f);
        } else {
            sleepDeadlineMs = System.currentTimeMillis() + minutes * 60000L;
        }
    }

    public long remainingSleepSeconds() {
        if (sleepDeadlineMs <= 0L) return 0L;
        return Math.max(0L, (sleepDeadlineMs - System.currentTimeMillis()) / 1000L);
    }

    public List<File> getPlaylistSnapshot() {
        return new ArrayList<File>(playlist);
    }

    public int getCurrentIndex() { return currentIndex; }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int getPositionMs() {
        if (player == null) return 0;
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public int getDurationMs() {
        if (player == null) return 0;
        try {
            int d = player.getDuration();
            return d > 0 ? d : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public String getCleanTitle() {
        if (activeStreamTitle != null && activeStreamTitle.length() > 0) return activeStreamTitle;
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex).getName();
        }
        return "Not Playing";
    }

    public String getCurrentTitle() {
        if (activeStreamTitle != null) {
            return activeStreamArtist.length() > 0
                    ? (activeStreamTitle + "  \u2022  " + activeStreamArtist)
                    : activeStreamTitle;
        }
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex).getName();
        }
        return "No track selected — tap 'Open Track / Folder' above";
    }

    private synchronized void saveCurrentPosition() {
        if (player != null && currentIndex >= 0 && currentIndex < playlist.size()) {
            try {
                File f = playlist.get(currentIndex);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("pos_" + f.getAbsolutePath().hashCode(), player.getCurrentPosition()).commit();
            } catch (Throwable ignored) {}
        }
    }

    private void acquireWifiLockSafe() {
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWifiLockSafe() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {}
    }

    private synchronized void releasePlayer() {
        if (equalizer != null) {
            try { equalizer.release(); } catch (Throwable ignored) {}
            equalizer = null;
        }
        if (bassBoost != null) {
            try { bassBoost.release(); } catch (Throwable ignored) {}
            bassBoost = null;
        }
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        releaseWifiLockSafe();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkpointTask);
        try { unregisterReceiver(externalMediaReceiver); } catch (Throwable ignored) {}
        saveCurrentPosition();
        releasePlayer();
        super.onDestroy();
    }
}
