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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Upgrade #9 (Backend): Background Screen-Off Music & Audiobook Playback Service
 * (`PARTIAL_WAKE_LOCK` + automatic audiobook bookmark resume + bedtime sleep timer!).
 */
public class Arc7AudioService extends Service {

    public static final String ACTION_TOGGLE    = "ca.arc7freedom.app.AUDIO_TOGGLE";
    public static final String ACTION_REWIND_30 = "ca.arc7freedom.app.AUDIO_REWIND_30";
    public static final String ACTION_FFWD_30   = "ca.arc7freedom.app.AUDIO_FFWD_30";

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
    private long sleepDeadlineMs = 0L;

    private final Runnable checkpointTask = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            if (sleepDeadlineMs > 0L && System.currentTimeMillis() >= sleepDeadlineMs) {
                sleepDeadlineMs = 0L;
                pausePlayback();
            }
            handler.postDelayed(this, 4000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler.postDelayed(checkpointTask, 4000L);
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
            }
            Arc7AudioWidgetProvider.refreshAll(getApplicationContext(), getCurrentTitle(), isPlaying());
        }
        return START_STICKY;
    }

    public synchronized void loadTrackAndFolderQueue(File pickedAudioFile) {
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

    public static boolean isAudioName(String n) {
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".aac") ||
               l.endsWith(".ogg") || l.endsWith(".flac") || l.endsWith(".wav") || l.endsWith(".opus");
    }

    public synchronized void startTrackAtIndex(int idx, boolean restoreBookmark) {
        if (idx < 0 || idx >= playlist.size()) return;
        saveCurrentPosition();
        releasePlayer();
        currentIndex = idx;
        final File track = playlist.get(idx);

        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(track.getAbsolutePath());
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
        if (player == null) return;
        int next = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + deltaMs));
        player.seekTo(next);
        saveCurrentPosition();
    }

    public synchronized void seekAbsoluteMs(int posMs) {
        if (player == null) return;
        player.seekTo(Math.max(0, Math.min(player.getDuration(), posMs)));
    }

    public synchronized void stepTrack(int delta) {
        int next = currentIndex + delta;
        if (next >= 0 && next < playlist.size()) {
            startTrackAtIndex(next, false);
        }
    }

    public void setSleepTimerMinutes(int minutes) {
        sleepDeadlineMs = minutes <= 0 ? 0L : (System.currentTimeMillis() + minutes * 60000L);
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
    public int getPositionMs() { return player != null ? player.getCurrentPosition() : 0; }
    public int getDurationMs() { return player != null ? player.getDuration() : 0; }

    public String getCurrentTitle() {
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
