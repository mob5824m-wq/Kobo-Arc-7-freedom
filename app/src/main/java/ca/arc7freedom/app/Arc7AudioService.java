package ca.arc7freedom.app;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Background Screen-Off Music, Audiobook, Live Internet Radio & Podcast Playback Service (`v2.2.0`):
 *   - `PARTIAL_WAKE_LOCK` screen-off playback
 *   - Automatic audiobook/podcast position bookmarks
 *   - Live Internet Radio & HTTPS stream URL playback routed automatically through local TLS 1.3 gateway
 *   - Smooth 30-second exponential volume fade-out Sleep Timer!
 */
public class Arc7AudioService extends Service {

    public static final String ACTION_TOGGLE      = "ca.arc7freedom.app.AUDIO_TOGGLE";
    public static final String ACTION_REWIND_30   = "ca.arc7freedom.app.AUDIO_REWIND_30";
    public static final String ACTION_FFWD_30     = "ca.arc7freedom.app.AUDIO_FFWD_30";
    public static final String ACTION_PLAY_STREAM = "ca.arc7freedom.app.AUDIO_PLAY_STREAM";
    public static final String ACTION_SLEEP_TIMER = "ca.arc7freedom.app.AUDIO_SLEEP_TIMER";
    public static final String EXTRA_STREAM_URL   = "stream_url";
    public static final String EXTRA_STREAM_TITLE = "stream_title";
    public static final String EXTRA_SLEEP_MINS   = "sleep_minutes";

    private static final String PREFS = "arc7_audio_bookmarks";

    public class LocalBinder extends Binder {
        public Arc7AudioService getService() {
            return Arc7AudioService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private final List<File> playlist = new ArrayList<File>();
    private int currentIndex = -1;
    private String activeStreamTitle = null;
    private long sleepDeadlineMs = 0L;

    private final Runnable checkpointTask = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            if (sleepDeadlineMs > 0L) {
                long remMs = sleepDeadlineMs - System.currentTimeMillis();
                if (remMs <= 0L) {
                    sleepDeadlineMs = 0L;
                    setPlayerVolumeSafe(1.0f);
                    pausePlayback();
                } else if (remMs <= 30000L) {
                    // Smooth bedside fade-out over the final 30 seconds!
                    float vol = Math.max(0.03f, remMs / 30000f);
                    setPlayerVolumeSafe(vol * vol);
                }
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private synchronized void setPlayerVolumeSafe(float v) {
        if (player != null) {
            try { player.setVolume(v, v); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
            if (ACTION_TOGGLE.equals(act)) {
                togglePlayPause();
            } else if (ACTION_REWIND_30.equals(act)) {
                seekRelativeMs(-30000);
            } else if (ACTION_FFWD_30.equals(act)) {
                seekRelativeMs(30000);
            } else if (ACTION_PLAY_STREAM.equals(act)) {
                final String url = intent.getStringExtra(EXTRA_STREAM_URL);
                final String title = intent.getStringExtra(EXTRA_STREAM_TITLE);
                if (url != null && !url.isEmpty()) {
                    new Thread(new Runnable() {
                        @Override public void run() { playStreamUrl(url, title); }
                    }).start();
                }
            } else if (ACTION_SLEEP_TIMER.equals(act)) {
                setSleepTimerMinutes(intent.getIntExtra(EXTRA_SLEEP_MINS, 30));
            }
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), getCurrentTitle(), isPlaying());
        }
        return START_STICKY;
    }

    public synchronized void playStreamUrl(String streamUrl, final String title) {
        saveCurrentPosition();
        releasePlayer();
        currentIndex = -1;
        activeStreamTitle = title == null || title.isEmpty() ? "Live Internet Stream" : title;
        try {
            String effectiveUrl = streamUrl;
            if (streamUrl.startsWith("https://")) {
                RootShell sh = new RootShell(getApplicationContext());
                NetworkCertManager cm = new NetworkCertManager(getApplicationContext(), sh);
                cm.ensureCaBundleFile();
                cm.startBrowserGateway();
                int port = NetworkCertManager.BROWSER_GATEWAY_PORT;
                effectiveUrl = "http://127.0.0.1:" + port + "/__arc7_raw?url="
                        + URLEncoder.encode(streamUrl, "UTF-8");
            }
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(effectiveUrl);
            player.setVolume(1.0f, 1.0f);
            player.prepare();
            player.start();
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), activeStreamTitle, true);
        } catch (Throwable t) {
            releasePlayer();
        }
    }

    public synchronized void loadTrackAndFolderQueue(File pickedAudioFile) {
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

    public static void startSleepTimer(android.content.Context ctx, int minutes) {
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
        currentIndex = idx;
        final File track = playlist.get(idx);

        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(track.getAbsolutePath());
            player.setVolume(1.0f, 1.0f);
            player.prepare();

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
                    if (currentIndex + 1 < playlist.size()) {
                        startTrackAtIndex(currentIndex + 1, true);
                    }
                }
            });
            player.start();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_track", track.getAbsolutePath()).commit();
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), track.getName(), true);
        } catch (Throwable t) {
            releasePlayer();
        }
    }

    public synchronized void togglePlayPause() {
        if (player == null) {
            String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_track", "");
            if (!last.isEmpty() && new File(last).exists()) {
                loadTrackAndFolderQueue(new File(last));
            }
            return;
        }
        if (player.isPlaying()) {
            pausePlayback();
        } else {
            player.setVolume(1.0f, 1.0f);
            player.start();
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), getCurrentTitle(), true);
        }
    }

    public synchronized void pausePlayback() {
        if (player != null && player.isPlaying()) {
            saveCurrentPosition();
            player.pause();
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), getCurrentTitle(), false);
        }
    }

    public synchronized void seekRelativeMs(int deltaMs) {
        if (player == null || activeStreamTitle != null) return;
        int next = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + deltaMs));
        player.seekTo(next);
        saveCurrentPosition();
    }

    public synchronized void seekAbsoluteMs(int posMs) {
        if (player == null || activeStreamTitle != null) return;
        player.seekTo(Math.max(0, Math.min(player.getDuration(), posMs)));
    }

    public synchronized void stepTrack(int delta) {
        int next = currentIndex + delta;
        if (next >= 0 && next < playlist.size()) {
            startTrackAtIndex(next, false);
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
    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public int getPositionMs() { return player != null && activeStreamTitle == null ? player.getCurrentPosition() : 0; }
    public int getDurationMs() { return player != null && activeStreamTitle == null ? player.getDuration() : 0; }

    public String getCurrentTitle() {
        if (activeStreamTitle != null) return "📻 " + activeStreamTitle;
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

    private synchronized void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkpointTask);
        saveCurrentPosition();
        releasePlayer();
        super.onDestroy();
    }
}
