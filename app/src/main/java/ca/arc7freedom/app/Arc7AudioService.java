package ca.arc7freedom.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import ca.arc7freedom.support.DebugLog;
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
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Universal Background Screen-Off Music, YouTube Music (Morphe), YouTube Background Audio,
 * Live Internet Radio, Audiobook & Owned Media Notification Service (`v2.4.0`).
 */
public class Arc7AudioService extends Service {

    public static final int NOTIF_ID_MEDIA = 4207;

    public static final String ACTION_PLAY = "ca.arc7freedom.app.AUDIO_PLAY";
    public static final String ACTION_PAUSE = "ca.arc7freedom.app.AUDIO_PAUSE";
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
    // Native operations/readiness share this service's monitor with replacement/release.
    // Android 4.2 getDuration() while Preparing posts asynchronous MEDIA_ERROR(-38, 0):
    // catching Java exceptions does not protect the player from an early UI/checkpoint poll.
    private MediaPlayer player;
    private ca.arc7freedom.support.MediaCache.Lease playerCacheLease;
    private String selfTestFormat = "";
    private volatile boolean playerPrepared = false;
    // Wall-clock position anchor: Android 4.2 Stagefright reports INVALID_OPERATION (or a
    // stuck 0) from getCurrentPosition() for proxied/adaptive Morphe streams, so the seek
    // bar would never move. Anchor the position at play/seek time and advance it by elapsed
    // time while playing; re-anchor whenever the player reports a plausible position.
    private long posAnchorElapsedMs = 0L;
    private int posAnchorMs = 0;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audioManager;
    private final AudioFocusPolicy focus = new AudioFocusPolicy();
    private boolean mediaButtonsRegistered;
    private static volatile boolean mediaButtonOwner;
    public static boolean acceptsMediaButtons() { return mediaButtonOwner; }
    private boolean playWhenPrepared = true;
    private float sleepVolume = 1f;
    private int bufferingPercent = -1, lastErrorWhat, lastErrorExtra;
    private String diagnosticState = "Idle";
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) headphonesDisconnected();
        }
    };
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
    private volatile String playbackStatus = "";
    private volatile int playbackGeneration;
    private Thread resolutionThread;
    private String activeDataSource = "";
    private String radioFallbackUrl = "";
    private int radioFallbackGeneration=-1;
    private MorpheEngine.ResolvedPlayback activeMorphePlayback;
    private boolean audioCompatibilityAttempted;
    private Runnable prepareTimeout;

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
            scrobbleCheckpointTick();

            if (isPlaying()) {
                publishOwnPlaybackState(true);
            } else {
                // External metadata is for Clock/widgets only. Never mirror another APK's notification.
                NowPlayingManager.syncExternalAudioIfNeeded(getApplicationContext(), false);
                updateMediaNotificationFromSnapshot();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private void handleSleepFadeTick() {
        if (sleepDeadlineMs > 0L) {
            long remMs = sleepDeadlineMs - System.currentTimeMillis();
            if (remMs <= 0L) {
                sleepDeadlineMs = 0L;
                playWhenPrepared = false;
                setPlayerVolumeSafe(1.0f);
                pausePlayback();
            } else if (remMs <= 30000L) {
                float vol = Math.max(0.03f, remMs / 30000f);
                setPlayerVolumeSafe(vol * vol);
            }
        }
    }

    private synchronized void handleSponsorBlockAudioSkipTick() {
        if (player == null || !playerPrepared || activeSkipSegments.isEmpty() || !MorpheEngine.isSponsorBlockEnabled(this)) {
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

    /* ==================== ListenBrainz Scrobble (Morphe / YT Music) ====================
     * Opt-in via Morphe settings in YouTube Music. Listens only count real listening:
     * decoder-position deltas while the player is playing (seeks don't count), submitted once
     * ≥ half the track (or 4 minutes) has been heard, or the track played to its end.
     * Only Morphe (YouTube / YouTube Music) tracks are scrobbled; local files and live
     * radio never are. */
    private String scrobbleKey = "";
    private String scrobbleTitle = "";
    private String scrobbleArtist = "";
    private String scrobbleAlbum = "";
    private long scrobbleDurationSec = 0L;
    private long scrobbleStartedAtSec = 0L;
    private long scrobbleListenedMs = 0L;
    private long scrobbleLastPositionMs = -1L;
    private boolean scrobbleSubmitted = false;
    private int scrobbleGeneration = -1;

    private boolean scrobbleEligibleSource() {
        if (activeMorphePlayback != null) return true;
        return "YouTube Music".equals(activeSourceApp) || "YouTube".equals(activeSourceApp);
    }

    private void resetScrobbleState() {
        scrobbleKey = "";
        scrobbleTitle = "";
        scrobbleArtist = "";
        scrobbleAlbum = "";
        scrobbleDurationSec = 0L;
        scrobbleStartedAtSec = 0L;
        scrobbleListenedMs = 0L;
        scrobbleLastPositionMs = -1L;
        scrobbleSubmitted = false;
    }

    /** Call when playback of a track actually starts. Sends `playing_now` when configured. Idempotent per playback generation. */
    private synchronized void scrobbleOnTrackStarted() {
        if (scrobbleKey.length() > 0 && scrobbleGeneration == playbackGeneration) return; // already tracking this track
        resetScrobbleState();
        scrobbleGeneration = playbackGeneration;
        if (!scrobbleEligibleSource()) return;
        MorpheEngine.MediaItem item = (morpheIndex >= 0 && morpheIndex < morpheQueue.size()) ? morpheQueue.get(morpheIndex) : null;
        String title = activeStreamTitle != null && activeStreamTitle.length() > 0
                ? activeStreamTitle : (item != null ? item.title : "");
        String artist = activeStreamArtist != null && activeStreamArtist.length() > 0
                ? activeStreamArtist : (item != null ? item.author : "");
        String album = item != null ? item.album : "";
        long dur = item != null ? item.durationSec : 0L;
        if (activeMorphePlayback != null && activeMorphePlayback.durationSec > 0L) {
            dur = dur > 0L ? Math.min(dur, activeMorphePlayback.durationSec) : activeMorphePlayback.durationSec;
        }
        if (!ListenBrainzEngine.isScrobblable(title, artist, dur)) return;
        scrobbleTitle = title == null ? "" : title.trim();
        scrobbleArtist = artist == null ? "" : artist.trim();
        scrobbleAlbum = album == null ? "" : album.trim();
        scrobbleDurationSec = Math.max(0L, dur);
        scrobbleKey = scrobbleArtist + "\n" + scrobbleTitle + "\n" + System.currentTimeMillis();
        scrobbleStartedAtSec = System.currentTimeMillis() / 1000L;
        scrobbleLastPositionMs = -1L;
        if (ListenBrainzEngine.isConfigured(getApplicationContext())) {
            ListenBrainzEngine.submitNowPlaying(getApplicationContext(), scrobbleTitle, scrobbleArtist,
                    scrobbleAlbum, scrobbleDurationSec);
        }
    }

    /**
     * 1-second heartbeat while the player runs; accumulates genuine listened time from the
     * decoder position, so seeks never count as listening. Forward jumps > 5 s are treated as
     * seeks (not listened time); backward jumps contribute nothing. Works even when the
     * platform cannot report a wall-clock-reliable duration.
     */
    private synchronized void scrobbleCheckpointTick() {
        if (scrobbleKey.length() == 0) return;
        boolean playing = false;
        long pos = -1L;
        try {
            playing = player != null && playerPrepared && player.isPlaying();
            if (playing) pos = Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {}
        if (!playing || pos < 0L) {
            scrobbleLastPositionMs = pos;
            return;
        }
        if (scrobbleLastPositionMs >= 0L) {
            long delta = pos - scrobbleLastPositionMs;
            if (delta > 0L && delta <= 5000L) scrobbleListenedMs += delta;
        }
        scrobbleLastPositionMs = pos;
        maybeSubmitScrobbleLocked(false);
    }

    private void maybeSubmitScrobbleLocked(boolean naturalEnd) {
        if (scrobbleSubmitted || scrobbleKey.length() == 0) return;
        long listened = scrobbleListenedMs;
        if (naturalEnd && scrobbleDurationSec > 0L) listened = Math.max(listened, scrobbleDurationSec * 1000L);
        if (listened < ListenBrainzEngine.thresholdMs(scrobbleDurationSec)) return;
        ListenBrainzEngine.submitScrobble(getApplicationContext(), scrobbleTitle, scrobbleArtist,
                scrobbleAlbum, scrobbleDurationSec, scrobbleStartedAtSec);
        scrobbleSubmitted = true;
    }

    /** Flush a pending scrobble (natural track end counts as fully heard); then reset state. */
    private synchronized void flushPendingScrobble(boolean naturalEnd) {
        maybeSubmitScrobbleLocked(naturalEnd);
        resetScrobbleState();
    }

    private synchronized void setPlayerVolumeSafe(float v) {
        sleepVolume = Math.max(0f, Math.min(1f, v));
        applyPlayerVolume();
    }

    private synchronized void applyPlayerVolume() {
        if (player != null && playerPrepared) {
            float volume = sleepVolume * (focus.ducked() ? 0.2f : 1f);
            try { player.setVolume(volume, volume); } catch (Throwable error) { DebugLog.error("audio.volume_failed", error); }
        }
    }

    private synchronized boolean startPreparedPlayback(MediaPlayer mp) {
        if (player != mp || !playerPrepared) return false;
        resolvingMorpheTrack = false;
        if (!playWhenPrepared) {
            diagnosticState = "Paused"; playbackStatus = "Paused before start. Tap Play when ready.";
            releaseWifiLockSafe(); publishOwnPlaybackState(false); return false;
        }
        boolean granted = focus.held();
        if (!granted) {
            abandonFocus(false); // invalidate queued callbacks from an earlier explicit focus request
            final int request = playbackGeneration;
            final MediaPlayer target = mp;
            focusListener = new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(final int change) {
                    final AudioManager.OnAudioFocusChangeListener callback = this;
                    handler.post(new Runnable() { public void run() { synchronized (Arc7AudioService.this) {
                        if (focusListener == callback && player == target && playbackGeneration == request) handleFocusChange(change);
                    }}});
                }
            };
            try { granted = audioManager != null && audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
            catch (Throwable unavailable) { DebugLog.error("audio.focus_request_failed", unavailable); }
            focus.requested(granted);
        }
        if (!granted) {
            diagnosticState = "Waiting for audio focus"; playbackStatus = "Another app owns audio focus. Tap Play to retry.";
            releaseWifiLockSafe(); DebugLog.event("audio.focus_denied"); publishOwnPlaybackState(false); return false;
        }
        try {
            if (!mediaButtonsRegistered && audioManager != null) {
                audioManager.registerMediaButtonEventReceiver(new ComponentName(this, Arc7MediaButtonReceiver.class));
                mediaButtonsRegistered = true; mediaButtonOwner = true;
            }
        } catch (Throwable unavailable) { DebugLog.error("audio.media_buttons_unavailable", unavailable); }
        acquireWifiLockSafe(); applyPlayerVolume();
        mp.start();
        anchorPosition(safePlayerPosition());
        scrobbleOnTrackStarted();
        diagnosticState = "Playing"; playbackStatus = "";
        DebugLog.event("audio.started");
        publishOwnPlaybackState(true);
        return true;
    }

    private synchronized void abandonFocus(boolean unregisterButtons) {
        focus.abandon();
        AudioManager.OnAudioFocusChangeListener previous = focusListener;
        focusListener = null;
        try { if (audioManager != null && previous != null) audioManager.abandonAudioFocus(previous); } catch (Throwable ignored) {}
        if (unregisterButtons && mediaButtonsRegistered) {
            try { audioManager.unregisterMediaButtonEventReceiver(new ComponentName(this, Arc7MediaButtonReceiver.class)); } catch (Throwable ignored) {}
            mediaButtonsRegistered = false; mediaButtonOwner = false;
        }
    }

    private synchronized void handleFocusChange(int change) {
        int action = focus.change(change, isPlaying(), playbackGeneration);
        DebugLog.number("audio.focus_change", change);
        if (action == AudioFocusPolicy.PAUSE) {
            if (player != null && playerPrepared && isPlaying()) {
                try { saveCurrentPosition(); player.pause(); } catch (Throwable e) { DebugLog.error("audio.focus_pause_failed", e); }
                releaseWifiLockSafe(); diagnosticState = "Paused for audio focus"; publishOwnPlaybackState(false);
            }
            if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus(true);
        } else if (action == AudioFocusPolicy.RESUME) {
            if (player != null && playerPrepared && playWhenPrepared) {
                try { startPreparedPlayback(player); } catch (Throwable e) { failPlayback(playbackGeneration, e); }
            }
        } else if (action == AudioFocusPolicy.DUCK || action == AudioFocusPolicy.RESTORE) applyPlayerVolume();
    }

    private synchronized void headphonesDisconnected() {
        if (player == null && !resolvingMorpheTrack) return;
        playWhenPrepared = false;
        pausePlayback(); abandonFocus(false);
        diagnosticState = "Paused after headphone disconnect";
        playbackStatus = "Headphones disconnected. Tap Play to resume; reconnect never auto-resumes.";
        DebugLog.event("audio.headphones_disconnected");
    }

    /** Report contains state/counters only, never titles, URLs, tokens or local paths. */
    public synchronized String getDiagnosticReport() {
        String mime = MorpheStreamServer.getMimeType(activeDataSource);
        String container = "audio/mp4".equals(mime) ? "classic M4A / MP4" : "video/mp4".equals(mime) ? "MP4" : "audio/aac".equals(mime) ? "AAC / ADTS" : "not independently reported";
        return "Player: " + diagnosticState + "\nPrepared: " + playerPrepared
                + "\nPlaying: " + isPlaying() + "\nAudio focus: " + focus.held()
                + "\nDucked: " + focus.ducked() + "\nBuffering: " + (bufferingPercent < 0 ? "not reported" : bufferingPercent + "%")
                + "\nPosition / duration: " + getPositionMs() + " / " + getDurationMs() + " ms"
                + "\nContainer: " + container
                + (selfTestFormat.length() == 0 ? "" : "\nVerified offline fixture: " + selfTestFormat)
                + "\nCodec: " + (activeMorphePlayback != null && activeMorphePlayback.verifiedAudio ? "AAC-LC (verified configuration)" : "not independently reported")
                + "\nLast MediaPlayer error: " + lastErrorWhat + "/" + lastErrorExtra
                + "\nCompatibility retry used: " + audioCompatibilityAttempted
                + "\nPrepared/started is not proof of audible output. Use the offline clips and confirm what you hear.";
    }

    public synchronized void recordDiagnosticSnapshot() {
        DebugLog.number("audio.snapshot_prepared", playerPrepared ? 1 : 0);
        DebugLog.number("audio.snapshot_playing", isPlaying() ? 1 : 0);
        DebugLog.number("audio.snapshot_focus", focus.held() ? 1 : 0);
        DebugLog.number("audio.snapshot_buffer_percent", bufferingPercent);
        DebugLog.number("audio.snapshot_position_ms", getPositionMs());
        DebugLog.number("audio.snapshot_duration_ms", getDurationMs());
        DebugLog.number("audio.snapshot_error_what", lastErrorWhat);
        DebugLog.number("audio.snapshot_error_extra", lastErrorExtra);
    }

    /** Explicit user-confirmed offline test; never contacts a provider or changes system volume. */
    public synchronized void playDiagnosticClip(File file, int codec) {
        int request = ++playbackGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        saveCurrentPosition(); releasePlayer(); clearMorpheResolution();
        morpheIndex = -1; currentIndex = -1; playWhenPrepared = true; sleepVolume = 1f;
        lastErrorWhat = lastErrorExtra = 0;
        setSleepTimerMinutes(0);
        selfTestFormat = codec == 0 ? "PCM 16-bit mono / WAV" : codec == 1 ? "MP3 mono" : "AAC-LC mono / classic M4A";
        DebugLog.number("audio.self_test_started", codec);
        playPreparedDataSource(file.getAbsolutePath(), "Offline sound test", "Generated quiet test tone", "Sound test", "",
                "ca.arc7freedom.app.Arc7PlaybackDiagnosticsActivity", false, request);
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
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try { registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); }
        catch (Throwable unavailable) { DebugLog.error("audio.noisy_receiver_failed", unavailable); }
        DebugLog.event("audio.service_created");
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

        // Remove a notification left by a pre-fix watcher or a killed playback process.
        updateMediaNotificationFromSnapshot();
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
            if (NowPlayingManager.isYouTubeForegroundVideoActive()
                    && (ACTION_STOP_DISMISS.equals(act) || ACTION_REWIND_30.equals(act) || ACTION_FFWD_30.equals(act))) {
                NowPlayingManager.dispatchTransportAction(this,act);
                return START_STICKY;
            }
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
            } else if (ACTION_PLAY.equals(act)) {
                synchronized (this) { if (player != null && playerPrepared && !isPlaying()) { playWhenPrepared = true; try { startPreparedPlayback(player); } catch (Throwable e) { failPlayback(playbackGeneration, e); } } }
            } else if (ACTION_PAUSE.equals(act)) {
                synchronized (this) { playWhenPrepared = false; pausePlayback(); }
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

            if (!MediaNotificationPolicy.mayPost(getPackageName(), snap.sourcePkg, snap.active,
                    playerPrepared, NowPlayingManager.isYouTubeForegroundVideoActive())) {
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
                    (snap.playing ? "NOW PLAYING  ·  " : "PAUSED  ·  ")
                            + snap.sourceApp.toUpperCase(Locale.US) + "  ·  " + timeBadge);
            expanded.setTextViewText(R.id.notif_media_title, snap.title);
            expanded.setTextViewText(R.id.notif_media_subtitle,
                    snap.artist != null && snap.artist.length() > 0 ? snap.artist : snap.sourceApp);
            expanded.setTextViewText(R.id.notif_btn_toggle, snap.playing ? "Pause" : "Play");
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
                if (!foregroundActive) {
                    startForeground(NOTIF_ID_MEDIA, n);
                    foregroundActive = true;
                } else {
                    nm.notify(NOTIF_ID_MEDIA, n);
                }
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
        ++playbackGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        resolvingMorpheTrack = false;
        playbackStatus = "Stopped"; diagnosticState = "Stopped"; playWhenPrepared = false;
        DebugLog.event("audio.stopped");
        pausePlayback();
        diagnosticState = "Stopped";
        releasePlayer(); clearMorpheResolution();
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
        final int request;
        final String source, launch;
        synchronized (this) {
            if (idx < 0 || idx >= morpheQueue.size()) return;
            request = ++playbackGeneration;
            if (resolutionThread != null) resolutionThread.interrupt();
            saveCurrentPosition(); releasePlayer(); clearMorpheResolution();
            morpheIndex = idx; currentIndex = -1;
            item = morpheQueue.get(idx);
            source = activeSourceApp; launch = activeLaunchClass;
            activeStreamTitle = item.title;
            activeStreamArtist = item.author != null ? item.author : "";
            activeArtworkUrl = item.bestThumb();
            activeSkipSegments.clear();
            resolvingMorpheTrack = true;
            selfTestFormat = "";
            playWhenPrepared = true; sleepVolume = 1f; lastErrorWhat = lastErrorExtra = 0;
            diagnosticState = "Resolving"; DebugLog.event("audio.resolve_started");
            playbackStatus = "Resolving playable audio...";
            publishOwnPlaybackState(false);
        }
        resolutionThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (item.localFilePath != null && item.localFilePath.length() > 0
                            && new File(item.localFilePath).exists()) {
                        playPreparedDataSource(item.localFilePath, item.title, item.author,
                                source, item.bestThumb(), launch, true, request);
                        return;
                    }
                    final MorpheEngine.ResolvedPlayback res = MorpheEngine.resolveStreams(
                            getApplicationContext(), item.videoId, true, new MorpheEngine.ResolutionProgressListener() {
                                @Override public void onProgress(String message) {
                                    synchronized (Arc7AudioService.this) {
                                        if (request == playbackGeneration) playbackStatus = message;
                                    }
                                }
                            });
                    synchronized (Arc7AudioService.this) {
                        if (request != playbackGeneration) { MorpheEngine.cancelPlayback(res); return; }
                        activeMorphePlayback = res;
                        if (res.durationSec > 0L && item.durationSec <= 0L) item.durationSec = res.durationSec;
                    }
                    if (res.author != null && res.author.length() > 0 && (item.author == null || item.author.length() == 0)) item.author = res.author;
                    if (res.title != null && res.title.length() > 0) item.title = res.title;
                    String playable = res.localAudioProxyUrl;
                    if (playable == null || playable.length() == 0) playable = res.localVideoProxyUrl;
                    if (playable == null || playable.length() == 0) throw new Exception("No playable audio stream");
                    playPreparedDataSource(playable, item.title, item.author, source,
                            res.thumbnailUrl.length() > 0 ? res.thumbnailUrl : item.bestThumb(), launch, true, request);
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                MorpheEngine.enrichPlaybackMetadata(getApplicationContext(), res);
                                synchronized (Arc7AudioService.this) {
                                    if (request != playbackGeneration) return;
                                    activeSkipSegments.clear(); activeSkipSegments.addAll(res.skipSegments);
                                    if (morpheQueue.size() == 1) {
                                        for (int i = 0; i < res.relatedItems.size() && morpheQueue.size() < 25; i++) morpheQueue.add(res.relatedItems.get(i));
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }, "Arc7-Audio-Metadata").start();
                } catch (Throwable error) { failPlayback(request, error); }
            }
        }, "Arc7-Audio-Resolve");
        resolutionThread.start();
    }

    public boolean isResolvingMorpheTrack() { return resolvingMorpheTrack; }
    public String getPlaybackStatus() {
        String preparing = MorpheStreamServer.getPreparationStatus(activeDataSource);
        return resolvingMorpheTrack && preparing.length() > 0 ? preparing : playbackStatus;
    }

    private synchronized void failPlayback(int request, final Throwable error) {
        if (request != playbackGeneration) return;
        String preparationError = MorpheStreamServer.getFailure(activeDataSource);
        releasePlayer(); resolvingMorpheTrack = false;
        diagnosticState = "Failed"; DebugLog.error("audio.playback_failed", error);
        playbackStatus = "Playback failed: " + (preparationError.length() > 0 ? preparationError
                : (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()));
        NowPlayingManager.clearSession(getApplicationContext());
        updateMediaNotificationFromSnapshot();
        final String message = playbackStatus;
        handler.post(new Runnable() {
            @Override public void run() { Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show(); }
        });
    }

    public boolean isShuffleEnabled() { return shuffleEnabled; }
    public void setShuffleEnabled(boolean s) { shuffleEnabled = s; }
    public int getRepeatMode() { return repeatMode; }
    public void setRepeatMode(int mode) { repeatMode = mode % 3; }

    /* ==================== Equalizer & BassBoost Hardware DSP ==================== */

    public synchronized void applyEqualizerPreset(int presetIdx) {
        currentEqPreset = Math.max(0, Math.min(EQ_PRESET_NAMES.length - 1, presetIdx));
        if (player == null || !playerPrepared) return;
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
        playStreamUrlFull(streamUrl, title, "Live Stream", "Radio",
                "", "ca.arc7freedom.app.Arc7RadioRssActivity");
    }

    public synchronized void playStreamUrlFull(final String streamUrl,
                                               final String title,
                                               final String artist,
                                               final String sourceApp,
                                               final String thumbUrl,
                                               final String launchCls) {
        if (streamUrl == null || streamUrl.length() == 0) return;
        final int request = ++playbackGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        saveCurrentPosition(); releasePlayer(); clearMorpheResolution();
        resolvingMorpheTrack = true;
        selfTestFormat = "";
        playWhenPrepared = true; sleepVolume = 1f; lastErrorWhat = lastErrorExtra = 0;
        diagnosticState = "Resolving"; DebugLog.event("audio.stream_requested");
        playbackStatus = "Preparing audio...";
        morpheIndex = -1;
        radioFallbackUrl = LegacyMediaPolicy.radioFallback(streamUrl); radioFallbackGeneration=request;
        currentIndex = -1;
        activeStreamTitle = title == null || title.isEmpty() ? "Live Internet Stream" : title;
        activeStreamArtist = artist != null ? artist : "";
        activeSourceApp = sourceApp != null ? sourceApp : "Radio";
        activeArtworkUrl = thumbUrl != null ? thumbUrl : "";
        activeLaunchClass = launchCls != null ? launchCls : "ca.arc7freedom.app.Arc7RadioRssActivity";
        publishOwnPlaybackState(false);

        resolutionThread=new Thread(new Runnable() {
            @Override
            public void run() {
                final String effectiveUrl;
                try { effectiveUrl = MorpheEngine.prepareRadioStream(getApplicationContext(),streamUrl); }
                catch (Throwable error) { failPlayback(request,error); return; }
                playPreparedDataSource(effectiveUrl,
                        title == null || title.isEmpty() ? "Live Internet Stream" : title,
                        artist != null ? artist : "",
                        sourceApp != null ? sourceApp : "Radio",
                        thumbUrl != null ? thumbUrl : "",
                        launchCls != null ? launchCls : "ca.arc7freedom.app.Arc7RadioRssActivity",
                        false, request);
            }
        }, "Arc7-Radio-Prepare"); resolutionThread.start();
    }

    private synchronized void playPreparedDataSource(final String dataSource,
                                                     final String title, final String artist,
                                                     final String sourceApp, final String thumbUrl,
                                                     final String launchCls,
                                                     final boolean autoAdvanceQueueOnComplete,
                                                     final int request) {
        if (request != playbackGeneration) {
            MorpheStreamServer.cancel(dataSource); MorpheHlsRelay.cancel(dataSource); return;
        }
        saveCurrentPosition(); releasePlayer();
        activeDataSource = dataSource;
        activeStreamTitle = title;
        activeStreamArtist = artist != null ? artist : "";
        activeSourceApp = sourceApp != null ? sourceApp : "Music";
        activeArtworkUrl = thumbUrl != null ? thumbUrl : "";
        activeLaunchClass = launchCls != null ? launchCls : "ca.arc7freedom.app.Arc7AudioPlayerActivity";
        diagnosticState = "Preparing"; bufferingPercent = -1; DebugLog.event("audio.prepare_started");
        playbackStatus = "Preparing Android 4.2 audio...";
        resolvingMorpheTrack = true;
        FileInputStream localFis = null;
        try {
            acquireWifiLockSafe();
            final MediaPlayer preparing = new MediaPlayer();
            player = preparing;
            playerCacheLease = ca.arc7freedom.support.MediaCache.lease();
            preparing.setAudioStreamType(AudioManager.STREAM_MUSIC);
            preparing.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            preparing.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    synchronized (Arc7AudioService.this) {
                        if (request != playbackGeneration || player != mp) return;
                        if (prepareTimeout != null) { handler.removeCallbacks(prepareTimeout); prepareTimeout = null; }
                        try {
                            LegacyPlaybackAudio.enable(mp);
                            playerPrepared = true;
                            if(selfTestFormat.length()==0)applyEqualizerPreset(currentEqPreset);
                            DebugLog.event("audio.prepared");
                            startPreparedPlayback(mp);
                        } catch (Throwable error) {
                            if (!retryMorpheAudio(request, error)) failPlayback(request, error);
                        }
                    }
                }
            });
            preparing.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                public void onBufferingUpdate(MediaPlayer mp, int percent) { synchronized (Arc7AudioService.this) {
                    if (player == mp && request == playbackGeneration) bufferingPercent = Math.max(0, Math.min(100, percent));
                }}
            });
            preparing.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    synchronized (Arc7AudioService.this) {
                        if (player == mp && request == playbackGeneration) {
                            playerPrepared = false;
                            lastErrorWhat = what; lastErrorExtra = extra;
                            DebugLog.number("audio.error_what", what); DebugLog.number("audio.error_extra", extra);
                            String fallback = request==radioFallbackGeneration ? radioFallbackUrl : ""; radioFallbackUrl = "";
                            if (fallback.length()>0) {
                                playbackStatus = "Trying alternate CBC stream after " + what + "/" + extra;
                                playStreamUrlFull(fallback,title,artist,sourceApp,thumbUrl,launchCls);
                            } else {
                                Exception error = new Exception(LegacyMediaPolicy.error(what,extra));
                                if (!LegacyMediaPolicy.audioContainerFailure(what, extra) || !retryMorpheAudio(request, error)) failPlayback(request, error);
                            }
                        }
                    }
                    return true;
                }
            });
            preparing.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    synchronized (Arc7AudioService.this) {
                        if (request != playbackGeneration || player != mp) return;
                        diagnosticState = "Completed"; DebugLog.event("audio.completed");
                        flushPendingScrobble(true); // natural end counts as fully heard
                        if (autoAdvanceQueueOnComplete && !morpheQueue.isEmpty() && repeatMode == REPEAT_ONE
                                && restartCompletedPlayer(mp)) return;
                        abandonFocus(false);
                        if (autoAdvanceQueueOnComplete && !morpheQueue.isEmpty()) {
                            if (repeatMode == REPEAT_ONE) startMorpheQueueAt(morpheIndex);
                            else if (morpheIndex + 1 < morpheQueue.size()) startMorpheQueueAt(morpheIndex + 1);
                            else if (repeatMode == REPEAT_ALL) startMorpheQueueAt(0);
                            else publishOwnPlaybackState(false);
                        } else publishOwnPlaybackState(false);
                    }
                }
            });
            if (dataSource != null && !dataSource.startsWith("http://") && !dataSource.startsWith("https://")) {
                File f = new File(dataSource);
                if (f.isFile()) { localFis = new FileInputStream(f); preparing.setDataSource(localFis.getFD()); }
                else preparing.setDataSource(dataSource);
            } else preparing.setDataSource(dataSource);
            preparing.setVolume(1.0f, 1.0f);
            preparing.prepareAsync();
            prepareTimeout = new Runnable() {
                @Override public void run() {
                    synchronized (Arc7AudioService.this) {
                        if (player == preparing && !playerPrepared) failPlayback(request,
                                new Exception("Audio preparation timed out; no playable media was received"));
                    }
                }
            };
            handler.postDelayed(prepareTimeout, 125000L);
        } catch (Throwable error) {
            if (!retryMorpheAudio(request, error)) failPlayback(request, error);
        }
        finally { if (localFis != null) try { localFis.close(); } catch (Throwable ignored) {} }
    }

    /** One request/player-safe container recovery; it never advances the queue or loops HLS. */
    private synchronized boolean retryMorpheAudio(final int request, Throwable original) {
        if (request != playbackGeneration || activeMorphePlayback == null || audioCompatibilityAttempted) return false;
        audioCompatibilityAttempted = true;
        final MorpheEngine.ResolvedPlayback resolved = activeMorphePlayback;
        final String title = activeStreamTitle, artist = activeStreamArtist, source = activeSourceApp,
                artwork = activeArtworkUrl, launch = activeLaunchClass;
        final String initialError = original.getMessage() != null ? original.getMessage() : "audio decoder rejected the stream";
        releasePlayer();
        resolvingMorpheTrack = true;
        diagnosticState = "Compatibility retry"; DebugLog.event("audio.compatibility_retry");
        playbackStatus = "Retrying once with verified AAC-LC / ADTS compatibility audio…";
        publishOwnPlaybackState(false);
        resolutionThread = new Thread(new Runnable() { @Override public void run() {
            try {
                String alternate = MorpheEngine.startAudioCompatibilityFallback(getApplicationContext(), resolved);
                synchronized (Arc7AudioService.this) {
                    if (request != playbackGeneration || activeMorphePlayback != resolved) {
                        MorpheStreamServer.cancel(alternate); return;
                    }
                    if (alternate.length() == 0) throw new Exception("No verified AAC-LC compatibility track is available. " + initialError);
                }
                playPreparedDataSource(alternate, title, artist, source, artwork, launch, true, request);
            } catch (Throwable error) { failPlayback(request, error); }
        } }, "Arc7-Audio-Compatibility");
        resolutionThread.start();
        return true;
    }

    private void clearMorpheResolution() {
        MorpheEngine.cancelPlayback(activeMorphePlayback);
        activeMorphePlayback = null; audioCompatibilityAttempted = false;
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

    /** Restart the prepared decoder in place for Repeat One; do not resolve/download the same track again. */
    private synchronized boolean restartCompletedPlayer(MediaPlayer completed) {
        if (completed == null || player != completed || !playerPrepared) return false;
        try {
            completed.seekTo(0);
            completed.start();
            scrobbleOnTrackStarted(); // repeat-one replay = a fresh listen cycle
            playWhenPrepared = true;
            diagnosticState = "Playing"; playbackStatus = "";
            acquireWifiLockSafe(); applyPlayerVolume();
            publishOwnPlaybackState(true);
            return true;
        } catch (Throwable failure) {
            DebugLog.error("audio.repeat_restart_failed", failure);
            return false;
        }
    }

    public synchronized void startTrackAtIndex(int idx, boolean restoreBookmark) {
        if (idx < 0 || idx >= playlist.size()) return;
        ++playbackGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        resolvingMorpheTrack = false;
        playbackStatus = "";
        saveCurrentPosition();
        releasePlayer(); clearMorpheResolution();
        activeStreamTitle = null;
        morpheIndex = -1;
        currentIndex = idx;
        final File track = playlist.get(idx);
        playWhenPrepared = true; sleepVolume = 1f; diagnosticState = "Preparing local file";
        lastErrorWhat = lastErrorExtra = 0; bufferingPercent = 100; selfTestFormat = "";
        activeDataSource = track.getAbsolutePath();
        final int localRequest = playbackGeneration;
        DebugLog.event("audio.local_requested");
        activeSourceApp = "Music";
        activeLaunchClass = "ca.arc7freedom.app.Arc7AudioPlayerActivity";
        activeStreamArtist = track.getParentFile() != null ? track.getParentFile().getName() : "Local Library";
        activeArtworkUrl = "";

        try {
            player = new MediaPlayer();
            playerCacheLease = ca.arc7freedom.support.MediaCache.lease();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(track.getAbsolutePath());
            player.setVolume(1.0f, 1.0f);
            player.prepare();
            playerPrepared = true;
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
                    synchronized (Arc7AudioService.this) {
                    if (player != mp || playbackGeneration != localRequest) return;
                    diagnosticState = "Completed"; DebugLog.event("audio.completed");
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("pos_" + track.getAbsolutePath().hashCode(), 0).commit();
                    if (repeatMode == REPEAT_ONE && restartCompletedPlayer(mp)) return;
                    abandonFocus(false);
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
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp,int what,int extra) { synchronized (Arc7AudioService.this) {
                    if (player != mp || playbackGeneration != localRequest) return true;
                    playerPrepared=false; lastErrorWhat=what; lastErrorExtra=extra;
                    DebugLog.number("audio.error_what",what); DebugLog.number("audio.error_extra",extra);
                    failPlayback(localRequest,new Exception(LegacyMediaPolicy.error(what,extra)));return true;
                }}
            });
            startPreparedPlayback(player);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_track", track.getAbsolutePath()).commit();
        } catch (Throwable t) {
            failPlayback(playbackGeneration, t);
        }
    }

    public synchronized void togglePlayPause() {
        if (resolvingMorpheTrack) {
            playWhenPrepared = !playWhenPrepared;
            DebugLog.number("audio.pending_play_intent", playWhenPrepared ? 1 : 0);
            return;
        }
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
        if (!playerPrepared) return;
        if (player.isPlaying()) {
            pausePlayback();
        } else {
            playWhenPrepared = true;
            try { startPreparedPlayback(player); } catch (Throwable error) { failPlayback(playbackGeneration, error); }
        }
    }

    public synchronized void pausePlayback() {
        playWhenPrepared = false; abandonFocus(false);
        diagnosticState = playerPrepared ? "Paused" : "Paused before ready"; DebugLog.event("audio.user_paused");
        if (!playerPrepared) return;
        if (player != null && player.isPlaying()) {
            anchorPosition(estimatedPositionMs());
            saveCurrentPosition();
            player.pause();
            releaseWifiLockSafe();
            publishOwnPlaybackState(false);
        }
    }

    public synchronized void seekRelativeMs(int deltaMs) {
        if (player == null || !playerPrepared) return;
        try {
            int dur = getDurationMs();
            if (dur <= 1000 && activeMorphePlayback != null && activeMorphePlayback.durationSec > 0L) {
                dur = (int) Math.min(Integer.MAX_VALUE, activeMorphePlayback.durationSec * 1000L);
            }
            int current = getPositionMs();
            long upper = dur > 1000 ? (long) dur : 6L * 3600L * 1000L; // unknown length: clamp only from below
            int next = (int) Math.max(0L, Math.min(upper, current + (long) deltaMs));
            player.seekTo(next);
            anchorPosition(next);
            saveCurrentPosition();
            publishOwnPlaybackState(player.isPlaying());
        } catch (Throwable error) {
            DebugLog.error("audio.seek_relative_failed", error);
        }
    }

    public synchronized void seekAbsoluteMs(int posMs) {
        if (player == null || !playerPrepared) return;
        try {
            int dur = getDurationMs();
            if (dur <= 1000 && activeMorphePlayback != null && activeMorphePlayback.durationSec > 0L) {
                dur = (int) Math.min(Integer.MAX_VALUE, activeMorphePlayback.durationSec * 1000L);
            }
            long upper = dur > 1000 ? (long) dur : 6L * 3600L * 1000L; // unknown length: keep the user's requested offset
            int clamped = (int) Math.max(0L, Math.min(upper, Math.max(0, posMs)));
            player.seekTo(clamped);
            anchorPosition(clamped);
            saveCurrentPosition();
            publishOwnPlaybackState(player.isPlaying());
            DebugLog.number("audio.seek_to_ms", clamped);
        } catch (Throwable error) {
            // Stagefright cannot seek every container (e.g. ADTS); log the reason instead of failing silently.
            DebugLog.error("audio.seek_failed", error);
        }
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

    public synchronized boolean isPlaying() {
        try {
            return player != null && playerPrepared && player.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether the current source can seek on Android 4.2's Stagefright stack.
     * Local files and classic MP4/M4A/MP3 jobs served by the local Range-aware media server
     * can; live HLS relays and ADTS (raw AAC) compatibility streams cannot.
     */
    public synchronized boolean isSeekableSource() {
        if (activeDataSource == null || activeDataSource.length() == 0) return false;
        String lower = activeDataSource.toLowerCase(Locale.US);
        if (lower.contains("/hls/")) return false;
        String mime = MorpheStreamServer.getMimeType(activeDataSource);
        if ("audio/aac".equals(mime) || "video/mp2t".equals(mime)) return false;
        return true;
    }

    private int safePlayerPosition() {
        if (player == null || !playerPrepared) return 0;
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void anchorPosition(int posMs) {
        posAnchorMs = Math.max(0, posMs);
        posAnchorElapsedMs = SystemClock.elapsedRealtime();
    }

    private int estimatedPositionMs() {
        int pos = posAnchorMs;
        if (isPlaying()) {
            pos += (int) Math.max(0L, SystemClock.elapsedRealtime() - posAnchorElapsedMs);
        }
        return Math.max(0, pos);
    }

    public synchronized int getPositionMs() {
        if (player == null || !playerPrepared) return 0;
        int playerPos = 0;
        try {
            playerPos = Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {
            playerPos = 0;
        }
        // Android 4.2 Stagefright reports INVALID_OPERATION (caught above as 0) or a stuck
        // position for proxied/adaptive Morphe streams, so the seek bar would never move.
        // Fall back to a wall-clock estimate anchored at play/seek time, and re-anchor
        // whenever the player reports a plausible position so the estimate cannot drift.
        if (scrobbleEligibleSource()) {
            if (playerPos > 0) {
                anchorPosition(playerPos);
                return playerPos;
            }
            if (isPlaying()) {
                return estimatedPositionMs();
            }
        }
        return playerPos;
    }

    public synchronized int getDurationMs() {
        int mediaDuration = 0;
        if (player != null && playerPrepared) {
            try { mediaDuration = Math.max(0, player.getDuration()); } catch (Throwable ignored) {}
        }
        // The legacy Android Stagefright backend can report a bogus 1-second duration for
        // proxied/adaptive YouTube streams even though seek and playback work. Trust the
        // duration from the music browse metadata when it is materially more plausible.
        long metadataDuration = 0L;
        boolean morpheTrack = "YouTube Music".equals(activeSourceApp) || "YouTube".equals(activeSourceApp);
        if (morpheTrack && morpheIndex >= 0 && morpheIndex < morpheQueue.size()) {
            metadataDuration = Math.max(0L, morpheQueue.get(morpheIndex).durationSec) * 1000L;
        }
        // Resolved playback reports the provider's own lengthSeconds; it beats a bogus Stagefright 1s.
        if (metadataDuration <= 0L && activeMorphePlayback != null && activeMorphePlayback.durationSec > 0L) {
            metadataDuration = activeMorphePlayback.durationSec * 1000L;
        }
        if (metadataDuration > 1000L && (mediaDuration <= 1000
                || Math.abs((long) mediaDuration - metadataDuration) > Math.max(5000L, metadataDuration / 10L))) {
            return (int) Math.min(Integer.MAX_VALUE, metadataDuration);
        }
        return mediaDuration;
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
        if (player != null && playerPrepared && currentIndex >= 0 && currentIndex < playlist.size()) {
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
        flushPendingScrobble(false); // track skipped/switched/stopped — keep a qualifying listen, drop the rest
        playerPrepared = false;
        abandonFocus(true);
        if (prepareTimeout != null) { handler.removeCallbacks(prepareTimeout); prepareTimeout = null; }
        MorpheStreamServer.cancel(activeDataSource); MorpheHlsRelay.cancel(activeDataSource);
        activeDataSource = "";
        if (equalizer != null) {
            try { equalizer.release(); } catch (Throwable ignored) {}
            equalizer = null;
        }
        if (bassBoost != null) {
            try { bassBoost.release(); } catch (Throwable ignored) {}
            bassBoost = null;
        }
        final MediaPlayer releasing = player;
        player = null;
        if (releasing != null) {
            // release() is legal while Preparing/in Error; stop() is not and may post -38/0.
            // Detach listeners as well as guarding queued callbacks by player/request identity.
            try { releasing.setOnPreparedListener(null); } catch (Throwable ignored) {}
            try { releasing.setOnBufferingUpdateListener(null); } catch (Throwable ignored) {}
            try { releasing.setOnErrorListener(null); } catch (Throwable ignored) {}
            try { releasing.setOnCompletionListener(null); } catch (Throwable ignored) {}
            try { releasing.release(); } catch (Throwable ignored) {}
        }
        if (playerCacheLease != null) { playerCacheLease.close(); playerCacheLease = null; }
        releaseWifiLockSafe();
    }

    @Override
    public synchronized void onDestroy() {
        ++playbackGeneration;
        if (resolutionThread != null) resolutionThread.interrupt();
        resolvingMorpheTrack = false;
        handler.removeCallbacks(checkpointTask);
        try { unregisterReceiver(externalMediaReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(noisyReceiver); } catch (Throwable ignored) {}
        DebugLog.event("audio.service_destroyed");
        saveCurrentPosition();
        releasePlayer(); clearMorpheResolution();
        super.onDestroy();
    }
}
