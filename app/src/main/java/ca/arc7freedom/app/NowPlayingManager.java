package ca.arc7freedom.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Universal System-Wide Audio Now-Playing State & Transport Coordinator (`v2.4.0`).
 *
 * Bridges EVERY audio source on Kobo Arc 7 (`Android 4.2.2`):
 *   - Built-in apps: `YouTube Music`, `YouTube`, `Music`, `Radio & Podcasts`, `Voice Recorder`, `Browser`
 *   - External apps: `VLC`, `NewPipe`, browsers, games & third-party players via standard Android
 *     `com.android.music.*` metadata broadcasts AND live `AudioManager.isMusicActive()` polling!
 *
 * Automatically feeds BOTH:
 *   1. Rich Android 4.2 Foreground/Ongoing Media Notification (`notif_media_compact` + `notif_media_expanded`)
 *   2. Live Clock Standby Now-Playing Display (`Arc7DeskClockActivity` & `Arc7ClockActivity`)
 *   3. Home Screen `Now Playing Media` AppWidget (`Arc7AudioWidgetProvider`)
 */
public final class NowPlayingManager {

    public static final String ACTION_NOW_PLAYING_CHANGED = "ca.arc7freedom.app.NOW_PLAYING_CHANGED";
    public static final String ACTION_YOUTUBE_FG_CONTROL  = "ca.arc7freedom.app.YOUTUBE_FG_CONTROL";
    private static final String PREFS = "arc7_now_playing_v240";

    public static final class Snapshot {
        public boolean active;
        public boolean playing;
        public String title = "";
        public String artist = "";
        public String album = "";
        public String sourceApp = "Music";
        public String sourcePkg = "ca.arc7freedom.app";
        public String launchClass = "ca.arc7freedom.app.Arc7AudioPlayerActivity";
        public String artworkUrl = "";
        public long positionMs;
        public long durationMs;
        public long updatedRealtimeMs;
        public Bitmap artwork;

        public long livePositionMs() {
            if (!playing) return Math.max(0L, positionMs);
            long delta = Math.max(0L, SystemClock.elapsedRealtime() - updatedRealtimeMs);
            long pos = Math.max(0L, positionMs) + delta;
            if (durationMs > 0L && pos > durationMs) pos = durationMs;
            return pos;
        }
    }

    private static final Object LOCK = new Object();
    private static Snapshot current = new Snapshot();
    private static String cachedArtUrl = "";
    private static Bitmap cachedArtBitmap = null;

    // External metadata captured from `com.android.music.*` broadcasts (e.g. VLC, stock Music, etc.)
    private static volatile String extTitle = "";
    private static volatile String extArtist = "";
    private static volatile String extAlbum = "";
    private static volatile String extPkg = "";
    private static volatile long extDurationMs = 0L;
    private static volatile long extPositionMs = 0L;
    private static volatile long extUpdatedRealtimeMs = 0L;
    private static volatile boolean extBroadcastPlaying = false;

    // Foreground VideoView session inside `YouTubeActivity` (when playing video in-app rather than background service)
    private static volatile boolean ytVideoOwned = false;

    private NowPlayingManager() {}

    public static String formatDurationMs(long ms) {
        if (ms <= 0L) return "0:00";
        long totalSec = ms / 1000L;
        long hrs = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hrs > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs);
        }
        return String.format(Locale.US, "%d:%02d", mins, secs);
    }

    public static boolean isYouTubeForegroundVideoActive() {
        return ytVideoOwned;
    }

    public static void setYouTubeForegroundVideoActive(boolean active) {
        ytVideoOwned = active;
    }

    public static void publishBuiltIn(final Context ctx,
                                      String sourceApp,
                                      String launchClass,
                                      String title,
                                      String artist,
                                      String album,
                                      String artworkUrl,
                                      Bitmap explicitArt,
                                      boolean playing,
                                      long positionMs,
                                      long durationMs) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        synchronized (LOCK) {
            current.active = true;
            current.playing = playing;
            current.sourceApp = nonEmpty(sourceApp, "Music");
            current.sourcePkg = appCtx.getPackageName();
            current.launchClass = nonEmpty(launchClass, "ca.arc7freedom.app.Arc7AudioPlayerActivity");
            current.title = nonEmpty(title, "Playing Audio");
            current.artist = artist != null ? artist : "";
            current.album = album != null ? album : "";
            current.positionMs = Math.max(0L, positionMs);
            current.durationMs = Math.max(0L, durationMs);
            current.updatedRealtimeMs = SystemClock.elapsedRealtime();

            if (explicitArt != null) {
                current.artwork = explicitArt;
                current.artworkUrl = artworkUrl != null ? artworkUrl : "";
            } else if (artworkUrl != null && artworkUrl.length() > 0) {
                current.artworkUrl = artworkUrl;
                if (artworkUrl.equals(cachedArtUrl) && cachedArtBitmap != null) {
                    current.artwork = cachedArtBitmap;
                } else {
                    current.artwork = null;
                    fetchArtworkAsync(appCtx, artworkUrl);
                }
            } else {
                current.artworkUrl = "";
                current.artwork = null;
            }
            persistSnapshotLocked(appCtx);
        }
        notifyEverywhere(appCtx);
    }

    public static void clearSession(Context ctx) {
        if (ctx == null) return;
        Context appCtx = ctx.getApplicationContext();
        ytVideoOwned = false;
        extBroadcastPlaying = false;
        synchronized (LOCK) {
            current = new Snapshot();
            persistSnapshotLocked(appCtx);
        }
        appCtx.sendBroadcast(new Intent(ACTION_NOW_PLAYING_CHANGED));
        Arc7AudioWidgetProvider.refreshAll(appCtx, "Not playing", false);
    }

    public static void onExternalMediaBroadcast(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        String action = intent.getAction() != null ? intent.getAction() : "";
        String track = firstStringExtra(intent, "track", "title", "name");
        String artist = firstStringExtra(intent, "artist", "author", "channel");
        String album = firstStringExtra(intent, "album", "albumName");
        if (track.length() > 0) extTitle = track;
        if (artist.length() > 0) extArtist = artist;
        if (album.length() > 0) extAlbum = album;

        long dur = firstLongExtra(intent, "duration", "trackLength");
        long pos = firstLongExtra(intent, "position", "seek");
        if (dur > 0L) extDurationMs = dur;
        if (pos >= 0L) extPositionMs = pos;
        extUpdatedRealtimeMs = SystemClock.elapsedRealtime();

        if (action.endsWith("playbackcomplete") || action.endsWith("playstatechanged")) {
            if (intent.hasExtra("playing")) {
                extBroadcastPlaying = intent.getBooleanExtra("playing", false);
            } else if (intent.hasExtra("playstate")) {
                extBroadcastPlaying = intent.getBooleanExtra("playstate", false);
            } else if (action.endsWith("playbackcomplete")) {
                extBroadcastPlaying = false;
            }
        } else {
            extBroadcastPlaying = true;
        }

        String senderPkg = firstStringExtra(intent, "package", "scrobbling_source", "player");
        if (senderPkg.length() > 0) extPkg = senderPkg;

        syncExternalAudioIfNeeded(ctx, true);
    }

    /**
     * Polls `AudioManager.isMusicActive()` so ANY playing audio on the tablet (including VLC,
     * third-party apps, games, or web streams) immediately surfaces in BOTH the Android
     * Notification shade AND the Clock Standby screen!
     */
    public static boolean syncExternalAudioIfNeeded(Context ctx, boolean triggerServiceNotification) {
        if (ctx == null) return false;
        Context appCtx = ctx.getApplicationContext();

        // Built-in foreground YouTube VideoView owns its own rich metadata
        if (ytVideoOwned) return true;

        boolean systemMusicActive = false;
        try {
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            systemMusicActive = am != null && am.isMusicActive();
        } catch (Throwable ignored) {}

        synchronized (LOCK) {
            // Do not overwrite an actively playing built-in `ca.arc7freedom.app` session
            if (current.active && current.playing && appCtx.getPackageName().equals(current.sourcePkg)) {
                return true;
            }
        }

        if (systemMusicActive || extBroadcastPlaying) {
            String detectedPkg = detectExternalAudioPackage(appCtx);
            String appLabel = resolvePackageLabel(appCtx, detectedPkg);
            String title = extTitle.length() > 0 ? extTitle : (appLabel + " Audio Playing");
            String artist = extArtist.length() > 0 ? extArtist : "Playing on " + appLabel;
            long pos = extPositionMs;
            if (extBroadcastPlaying && extUpdatedRealtimeMs > 0L) {
                pos += Math.max(0L, SystemClock.elapsedRealtime() - extUpdatedRealtimeMs);
            }
            synchronized (LOCK) {
                current.active = true;
                current.playing = true;
                current.sourceApp = appLabel;
                current.sourcePkg = detectedPkg.length() > 0 ? detectedPkg : "org.videolan.vlc";
                current.launchClass = "";
                current.title = title;
                current.artist = artist;
                current.album = extAlbum;
                current.positionMs = Math.max(0L, pos);
                current.durationMs = Math.max(0L, extDurationMs);
                current.updatedRealtimeMs = SystemClock.elapsedRealtime();
                persistSnapshotLocked(appCtx);
            }
            if (triggerServiceNotification) {
                notifyEverywhere(appCtx);
            } else {
                appCtx.sendBroadcast(new Intent(ACTION_NOW_PLAYING_CHANGED));
            }
            return true;
        } else {
            boolean clearedExternal = false;
            synchronized (LOCK) {
                if (current.active && !appCtx.getPackageName().equals(current.sourcePkg)) {
                    current.playing = false;
                    current.active = false;
                    persistSnapshotLocked(appCtx);
                    clearedExternal = true;
                }
            }
            if (clearedExternal) {
                appCtx.sendBroadcast(new Intent(ACTION_NOW_PLAYING_CHANGED));
                Arc7AudioWidgetProvider.refreshAll(appCtx, "Paused", false);
            }
            return false;
        }
    }

    public static Snapshot getSnapshot(Context ctx) {
        if (ctx != null) {
            syncExternalAudioIfNeeded(ctx, false);
        }
        synchronized (LOCK) {
            if (!current.active && ctx != null) {
                loadPersistedIntoCurrentLocked(ctx.getApplicationContext());
            }
            Snapshot copy = new Snapshot();
            copy.active = current.active;
            copy.playing = current.playing;
            copy.title = current.title;
            copy.artist = current.artist;
            copy.album = current.album;
            copy.sourceApp = current.sourceApp;
            copy.sourcePkg = current.sourcePkg;
            copy.launchClass = current.launchClass;
            copy.artworkUrl = current.artworkUrl;
            copy.positionMs = current.positionMs;
            copy.durationMs = current.durationMs;
            copy.updatedRealtimeMs = current.updatedRealtimeMs;
            copy.artwork = current.artwork;
            return copy;
        }
    }

    public static Intent buildOpenPlayerIntent(Context ctx, Snapshot snap) {
        Intent open;
        if (snap != null && snap.sourcePkg != null && !ctx.getPackageName().equals(snap.sourcePkg)) {
            PackageManager pm = ctx.getPackageManager();
            open = pm.getLaunchIntentForPackage(snap.sourcePkg);
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return open;
            }
        }
        open = new Intent();
        String cls = (snap != null && snap.launchClass != null && snap.launchClass.length() > 0)
                ? snap.launchClass
                : "ca.arc7freedom.app.Arc7AudioPlayerActivity";
        open.setClassName(ctx.getPackageName(), cls);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return open;
    }

    public static void dispatchTransportAction(Context ctx, String action) {
        if (ctx == null || action == null) return;
        Context appCtx = ctx.getApplicationContext();

        if (ytVideoOwned) {
            Intent i = new Intent(ACTION_YOUTUBE_FG_CONTROL);
            i.putExtra("cmd", action);
            appCtx.sendBroadcast(i);
            return;
        }

        Snapshot snap = getSnapshot(appCtx);
        if (snap.active && !appCtx.getPackageName().equals(snap.sourcePkg)) {
            int keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            if (Arc7AudioService.ACTION_PREV.equals(action)) keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            else if (Arc7AudioService.ACTION_NEXT.equals(action)) keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
            else if (Arc7AudioService.ACTION_REWIND_30.equals(action)) keyCode = KeyEvent.KEYCODE_MEDIA_REWIND;
            else if (Arc7AudioService.ACTION_FFWD_30.equals(action)) keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            else if (Arc7AudioService.ACTION_STOP_DISMISS.equals(action)) keyCode = KeyEvent.KEYCODE_MEDIA_STOP;
            sendSystemMediaKey(appCtx, keyCode);
            if (Arc7AudioService.ACTION_STOP_DISMISS.equals(action)) {
                clearSession(appCtx);
                Intent svc = new Intent(appCtx, Arc7AudioService.class);
                svc.setAction(Arc7AudioService.ACTION_STOP_DISMISS);
                appCtx.startService(svc);
            }
            return;
        }

        Intent svc = new Intent(appCtx, Arc7AudioService.class);
        svc.setAction(action);
        appCtx.startService(svc);
    }

    public static void sendSystemMediaKey(final Context ctx, final int keyCode) {
        try {
            long now = SystemClock.uptimeMillis();
            Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
            down.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            ctx.sendOrderedBroadcast(down, null);

            Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
            up.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now + 20L, KeyEvent.ACTION_UP, keyCode, 0));
            ctx.sendOrderedBroadcast(up, null);
        } catch (Throwable ignored) {}
    }

    private static void notifyEverywhere(Context appCtx) {
        appCtx.sendBroadcast(new Intent(ACTION_NOW_PLAYING_CHANGED));
        Snapshot s = getSnapshot(null);
        Arc7AudioWidgetProvider.refreshAll(appCtx,
                s.artist.length() > 0 ? (s.title + " • " + s.artist) : s.title,
                s.playing);
        Intent svc = new Intent(appCtx, Arc7AudioService.class);
        svc.setAction(Arc7AudioService.ACTION_REFRESH_NOTIFICATION);
        try {
            appCtx.startService(svc);
        } catch (Throwable ignored) {}
    }

    private static void fetchArtworkAsync(final Context appCtx, final String artUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    Arc7TlsGatewayService.ensureStarted(appCtx);
                    String gw = "http://127.0.0.1:" + NetworkCertManager.DEFAULT_PROXY_PORT
                            + "/__arc7_raw?u=" + Uri.encode(artUrl);
                    conn = (HttpURLConnection) new URL(gw).openConnection(Proxy.NO_PROXY);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(8000);
                    if (conn.getResponseCode() == 200) {
                        in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0 && bos.size() < 2 * 1024 * 1024) {
                            bos.write(buf, 0, n);
                        }
                        byte[] data = bos.toByteArray();
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                        int sample = 1;
                        while (opts.outWidth / sample > 256 || opts.outHeight / sample > 256) {
                            sample *= 2;
                        }
                        BitmapFactory.Options dec = new BitmapFactory.Options();
                        dec.inSampleSize = sample;
                        dec.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, dec);
                        if (bmp != null) {
                            synchronized (LOCK) {
                                cachedArtUrl = artUrl;
                                cachedArtBitmap = bmp;
                                if (artUrl.equals(current.artworkUrl)) {
                                    current.artwork = bmp;
                                }
                            }
                            notifyEverywhere(appCtx);
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

    private static String detectExternalAudioPackage(Context ctx) {
        if (extPkg != null && extPkg.length() > 0 && !ctx.getPackageName().equals(extPkg)) {
            return extPkg;
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = am != null ? am.getRunningAppProcesses() : null;
            if (procs != null) {
                String fallback = "";
                for (int i = 0; i < procs.size(); i++) {
                    ActivityManager.RunningAppProcessInfo p = procs.get(i);
                    if (p.pkgList == null || p.pkgList.length == 0) continue;
                    String pkg = p.pkgList[0];
                    if (ctx.getPackageName().equals(pkg) || "ca.arc7freedom.launcher".equals(pkg)
                            || "system".equals(pkg) || pkg.startsWith("com.android.systemui")
                            || pkg.startsWith("com.android.phone")) {
                        continue;
                    }
                    if (pkg.contains("vlc") || pkg.contains("music") || pkg.contains("audio")
                            || pkg.contains("pipe") || pkg.contains("spotify") || pkg.contains("radio")) {
                        return pkg;
                    }
                    if (fallback.length() == 0 && p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                        fallback = pkg;
                    }
                }
                if (fallback.length() > 0) return fallback;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String resolvePackageLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.length() == 0) return "System Audio";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {}
        return pkg;
    }

    private static void persistSnapshotLocked(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("active", current.active)
                .putBoolean("playing", current.playing)
                .putString("title", current.title)
                .putString("artist", current.artist)
                .putString("album", current.album)
                .putString("sourceApp", current.sourceApp)
                .putString("sourcePkg", current.sourcePkg)
                .putString("launchClass", current.launchClass)
                .putString("artworkUrl", current.artworkUrl)
                .putLong("positionMs", current.positionMs)
                .putLong("durationMs", current.durationMs)
                .commit();
    }

    private static void loadPersistedIntoCurrentLocked(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        current.active = sp.getBoolean("active", false);
        current.playing = false;
        current.title = sp.getString("title", "");
        current.artist = sp.getString("artist", "");
        current.album = sp.getString("album", "");
        current.sourceApp = sp.getString("sourceApp", "Music");
        current.sourcePkg = sp.getString("sourcePkg", ctx.getPackageName());
        current.launchClass = sp.getString("launchClass", "ca.arc7freedom.app.Arc7AudioPlayerActivity");
        current.artworkUrl = sp.getString("artworkUrl", "");
        current.positionMs = sp.getLong("positionMs", 0L);
        current.durationMs = sp.getLong("durationMs", 0L);
        current.updatedRealtimeMs = SystemClock.elapsedRealtime();
    }

    private static String nonEmpty(String s, String def) {
        return (s != null && s.trim().length() > 0) ? s.trim() : def;
    }

    private static String firstStringExtra(Intent intent, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            try {
                Object o = intent.getExtras() != null ? intent.getExtras().get(keys[i]) : null;
                if (o instanceof CharSequence && ((CharSequence) o).length() > 0) {
                    return o.toString().trim();
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static long firstLongExtra(Intent intent, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            try {
                Object o = intent.getExtras() != null ? intent.getExtras().get(keys[i]) : null;
                if (o instanceof Number) return ((Number) o).longValue();
            } catch (Throwable ignored) {}
        }
        return -1L;
    }
}
