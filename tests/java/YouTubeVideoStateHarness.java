package ca.arc7freedom.app;

import android.app.AlertDialog;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Calls the actual YouTubeActivity with fake Android views, player, clock and provider. */
public final class YouTubeVideoStateHarness {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void eq(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private static Object get(YouTubeActivity a, String name) throws Exception {
        Field f = YouTubeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(a);
    }
    private static void set(YouTubeActivity a, String name, Object value) throws Exception {
        Field f = YouTubeActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(a, value);
    }
    private static Object call(YouTubeActivity a, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = YouTubeActivity.class.getDeclaredMethod(method, types); m.setAccessible(true); return m.invoke(a, args);
    }
    private static void tick(YouTubeActivity a) throws Exception { ((Runnable) get(a, "progressTicker")).run(); }
    private static void prepare(YouTubeActivity a, MorpheEngine.ResolvedPlayback p, int request, int position) throws Exception {
        set(a, "currentPlayback", p); set(a, "videoRequestGeneration", request);
        call(a, "prepareForegroundVideo", new Class<?>[]{MorpheEngine.ResolvedPlayback.class, Uri.class, int.class, int.class},
                p, Uri.parse("http://fixture/video.mp4"), request, position);
    }
    private static String text(TextView view) { return view.getText().toString(); }
    private static MorpheEngine.ResolvedPlayback video(int durationSec) {
        MorpheEngine.ResolvedPlayback p = new MorpheEngine.ResolvedPlayback();
        p.videoId = "fixture0001"; p.title = "Fixture"; p.author = "Creator";
        p.durationSec = durationSec; p.localVideoProxyUrl = "http://fixture/video.mp4";
        return p;
    }

    public static void main(String[] args) throws Exception {
        Handler.ready.clear(); Handler.delayed.clear(); SystemClock.now = 1000L;
        YouTubeActivity a = new YouTubeActivity();
        VideoView videoView = new VideoView(a); SeekBar bar = new SeekBar(a);
        ProgressBar loading = new ProgressBar(a); TextView time = new TextView(a), status = new TextView(a);
        Button play = new Button(a), quality = new Button(a);
        set(a, "videoView", videoView); set(a, "seekBar", bar); set(a, "playerPrepareProgress", loading);
        set(a, "timeLabelTv", time); set(a, "playerOverlayStatusTv", status); set(a, "playPauseBtn", play);
        set(a, "qualityBtn", quality); set(a, "videoTitleTv", new TextView(a)); set(a, "videoChannelTv", new TextView(a));
        set(a, "rydStatsTv", new TextView(a)); set(a, "audioManager", new AudioManager());
        call(a, "wireVideoSeekBar", new Class<?>[]{});

        MorpheEngine.ResolvedPlayback first = video(60);
        // Simulate an Android 4 stream reporting unknown native duration and a permanently stuck zero position.
        videoView.duration = 0; videoView.rawPosition = 0;
        prepare(a, first, 1, 0);
        eq(View.VISIBLE, loading.getVisibility(), "loading shown while VideoView prepares");
        check(!bar.isEnabled(), "cannot seek an unprepared player");
        MorpheStreamServer.stage = "Preparing classic MP4 (7 MB)";
        tick(a);
        check(text(status).contains("7 MB"), "real preparation stage should be visible");
        MorpheStreamServer.stage = "";
        eq(0, videoView.queriesBeforePrepared, "no native time getters before onPrepared");
        videoView.firePrepared();
        eq(View.GONE, loading.getVisibility(), "loading hidden after actual onPrepared");
        check(bar.isEnabled(), "metadata duration enables seeking");
        check(text(time).equals("0:00 / 1:00\n1:00 left"), "initial total and remaining time: " + text(time));
        SystemClock.now += 5000; tick(a);
        eq(5000, bar.getProgress(), "stuck-zero player still moves seek bar");
        check(text(time).equals("0:05 / 1:00\n0:55 left"), "remaining time follows fallback: " + text(time));
        eq(5000, NowPlayingManager.position, "notification/widget receives effective position");
        eq(60000, NowPlayingManager.duration, "notification/widget receives metadata duration");

        // During a drag the one-second ticker must not replace the preview or time-left label.
        bar.beginDrag(); bar.dragTo(40000);
        check(text(time).equals("0:40 / 1:00\n0:20 left"), "scrubbing previews target time left");
        SystemClock.now += 1000; tick(a);
        check(text(time).equals("0:40 / 1:00\n0:20 left"), "ticker overwrote drag preview");
        bar.endDrag();
        eq(40000, videoView.seekTarget, "drag seeks the real VideoView");
        SystemClock.now += 2000; tick(a);
        eq(42000, bar.getProgress(), "stale native zero cannot undo drag seek");
        check(text(time).equals("0:42 / 1:00\n0:18 left"), "time left after seeking");

        call(a, "toggleForegroundVideo", new Class<?>[]{});
        eq(42000, bar.getProgress(), "pause retains position");
        SystemClock.now += 10000; tick(a);
        eq(42000, bar.getProgress(), "paused time does not advance");
        call(a, "toggleForegroundVideo", new Class<?>[]{});
        SystemClock.now += 1000; tick(a);
        eq(43000, bar.getProgress(), "resume excludes paused time");

        videoView.fireInfo(MediaPlayer.MEDIA_INFO_BUFFERING_START);
        SystemClock.now += 12000; tick(a);
        eq(43000, bar.getProgress(), "buffering does not advance the fallback");
        videoView.fireInfo(MediaPlayer.MEDIA_INFO_BUFFERING_END);
        SystemClock.now += 1000; tick(a);
        eq(44000, bar.getProgress(), "timeline resumes after buffering");

        call(a, "seekVideoRelative", new Class<?>[]{int.class}, -30000);
        eq(14000, videoView.seekTarget, "relative rewind uses effective position");
        SystemClock.now += 1000; tick(a);
        eq(15000, bar.getProgress(), "relative rewind updates fallback anchor");
        call(a, "seekVideoRelative", new Class<?>[]{int.class}, 30000);
        eq(45000, videoView.seekTarget, "relative forward uses effective position");
        bar.keyTo(46000);
        eq(46000, videoView.seekTarget, "D-pad/accessibility seek works without a touch-stop callback");
        MorpheEngine.SkipSegment segment = new MorpheEngine.SkipSegment();
        segment.startSec = 44f; segment.endSec = 50f; first.skipSegments.add(segment);
        MorpheEngine.sponsorEnabled = true;
        SystemClock.now += 1000; tick(a);
        eq(50000, videoView.seekTarget, "SponsorBlock uses the same seek path");
        eq(50000, NowPlayingManager.position, "SponsorBlock immediately publishes the new position");
        MorpheEngine.sponsorEnabled = false;

        // Exercise the real quality picker, including the asynchronous preparation callback.
        MorpheEngine.StreamVariant variant = new MorpheEngine.StreamVariant(); variant.label = "720p";
        first.progressiveVideos.add(variant);
        MediaPlayer.OnPreparedListener beforeQuality = videoView.onPrepared;
        MediaPlayer oldQualityPlayer = videoView.player;
        call(a, "showQualityPickerDialog", new Class<?>[]{});
        check(AlertDialog.Builder.items.length == 1, "quality picker exposes the available variant");
        AlertDialog.Builder.itemClick.onClick(null, 0);
        eq(View.VISIBLE, loading.getVisibility(), "quality rebuild shows preparation bar");
        check(!bar.isEnabled(), "quality rebuild disables seeking");
        Thread qualityWorker = (Thread) get(a, "resolutionThread"); qualityWorker.join(3000);
        check(!qualityWorker.isAlive(), "quality worker failed to finish"); Handler.drain();
        beforeQuality.onPrepared(oldQualityPlayer);
        eq(View.VISIBLE, loading.getVisibility(), "old quality callback cannot hide progress");
        videoView.firePrepared();
        eq(50000, videoView.seekTarget, "quality picker restores the effective, not stuck native, position");
        eq(View.GONE, loading.getVisibility(), "quality rebuild completes after player preparation");
        SystemClock.now += 1000; tick(a);
        eq(51000, bar.getProgress(), "restored quality continues from the anchor");
        videoView.recreateSurfacePlayer();
        SystemClock.now += 1000; videoView.firePrepared();
        eq(52000, videoView.seekTarget, "surface recreation restores current position, not the old quality target");
        SystemClock.now += 1000; tick(a);
        eq(53000, bar.getProgress(), "surface recreation preserves the running timeline");

        // A replacement request must also ignore old prepared callbacks.
        MediaPlayer.OnPreparedListener stalePrepared = videoView.onPrepared;
        MediaPlayer stalePlayer = videoView.player;
        MorpheEngine.ResolvedPlayback next = video(90);
        videoView.duration = 0; videoView.rawPosition = 0;
        prepare(a, next, 3, 45000);
        eq(View.VISIBLE, loading.getVisibility(), "replacement shows preparation bar");
        check(!bar.isEnabled(), "quality preparation disables seeking");
        stalePrepared.onPrepared(stalePlayer);
        eq(View.VISIBLE, loading.getVisibility(), "stale prepared callback cannot hide replacement progress");
        videoView.firePrepared();
        eq(45000, videoView.seekTarget, "quality change seeks after preparation");
        eq(View.GONE, loading.getVisibility(), "replacement prepared hides progress");
        SystemClock.now += 1000; tick(a);
        eq(46000, bar.getProgress(), "quality restoration retains the estimated position");
        next.progressiveVideos.add(variant); MorpheEngine.qualityFailure = true;
        call(a, "showQualityPickerDialog", new Class<?>[]{});
        AlertDialog.Builder.itemClick.onClick(null, 0);
        qualityWorker = (Thread) get(a, "resolutionThread"); qualityWorker.join(3000); Handler.drain();
        eq(View.GONE, loading.getVisibility(), "unavailable quality hides the preparation indicator");
        check(text(status).contains("Could not prepare the selected quality"), "unavailable quality shows an error");
        MorpheEngine.qualityFailure = false;

        MorpheEngine.ResolvedPlayback unknown = video(0);
        videoView.rawPosition = 0;
        prepare(a, unknown, 5, 0);
        videoView.firePrepared();
        check(!bar.isEnabled(), "unknown duration disables misleading seek bar");
        SystemClock.now += 2000; tick(a);
        check(text(time).equals("0:02 / --:--\n--:-- left"), "unknown remaining time must not claim zero: " + text(time));

        prepare(a, video(60), 6, 0);
        videoView.fireInfo(MediaPlayer.MEDIA_INFO_BUFFERING_START);
        eq(0, videoView.queriesBeforePrepared, "preparation buffering event must not poll player");
        videoView.firePrepared();
        SystemClock.now += 10000; tick(a);
        eq(0, bar.getProgress(), "buffering that starts before prepare freezes position");
        videoView.fireInfo(MediaPlayer.MEDIA_INFO_BUFFERING_END);
        SystemClock.now += 1000; tick(a);
        eq(1000, bar.getProgress(), "early buffering end resumes position");

        MorpheEngine.ResolvedPlayback failing = video(60);
        prepare(a, failing, 7, 0);
        MediaPlayer.OnPreparedListener failedPrepared = videoView.onPrepared;
        MediaPlayer failedPlayer = videoView.player;
        videoView.fireError();
        eq(View.GONE, loading.getVisibility(), "playback error hides progress");
        check(!bar.isEnabled() && text(status).contains("Playback failed"), "error disables controls and is visible");
        String failureText = text(status);
        failedPrepared.onPrepared(failedPlayer);
        check(!bar.isEnabled() && !NowPlayingManager.active && text(status).equals(failureText),
                "late prepared callback must not revive a failed player");

        prepare(a, video(60), 9, 0);
        Runnable timeout = (Runnable) get(a, "videoPrepareTimeout"); timeout.run();
        eq(View.GONE, loading.getVisibility(), "preparation timeout hides progress");
        check(text(status).contains("timed out"), "timeout reported");
        prepare(a, video(60), 11, 0);
        call(a, "stopForegroundVideo", new Class<?>[]{});
        eq(View.GONE, loading.getVisibility(), "stop hides progress");
        check(!bar.isEnabled(), "stop disables the seek bar");

        MorpheEngine.resolveFailure = false; MorpheEngine.response = video(10);
        call(a, "playVideoById", new Class<?>[]{String.class, String.class, String.class}, "oldfixture1", "Old", "Creator");
        eq(View.VISIBLE, loading.getVisibility(), "resolution begins with visible preparation bar");
        Thread worker = (Thread) get(a, "resolutionThread"); worker.join(3000);
        check(!worker.isAlive(), "old resolution worker failed to finish");
        // The first worker's callbacks are queued, but a new video is requested before delivery.
        MorpheEngine.ResolvedPlayback latest = video(75); MorpheEngine.response = latest;
        call(a, "playVideoById", new Class<?>[]{String.class, String.class, String.class}, "fixture0001", "Fixture", "Creator");
        worker = (Thread) get(a, "resolutionThread"); worker.join(3000);
        check(!worker.isAlive(), "new resolution worker failed to finish"); Handler.drain();
        check(get(a, "currentPlayback") == latest, "stale resolver replaced the new video");
        eq(View.VISIBLE, loading.getVisibility(), "resolved stream still shows progress until player prepares");
        check(text(status).contains("Preparing Android 4.2 video"), "prepare stage is explained");
        videoView.firePrepared();
        eq(View.GONE, loading.getVisibility(), "successful resolution hides progress only after player prepares");
        check(text(time).contains("1:15 left"), "new video uses its own duration");
        videoView.fireComplete();
        check(text(time).equals("1:15 / 1:15\n0:00 left"), "completion shows zero time left");

        MorpheEngine.resolveFailure = true;
        call(a, "playVideoById", new Class<?>[]{String.class, String.class, String.class}, "fixture0001", "Fixture", "Creator");
        eq(View.VISIBLE, loading.getVisibility(), "failed resolution starts with visible preparation bar");
        worker = (Thread) get(a, "resolutionThread"); worker.join(3000);
        check(!worker.isAlive(), "resolution worker failed to finish"); Handler.drain();
        eq(View.GONE, loading.getVisibility(), "resolution failure hides preparation bar");
        check(text(status).contains("Could not load stream"), "resolution failure remains visible");

        MorpheEngine.MediaItem offline = new MorpheEngine.MediaItem();
        offline.title = "Offline"; offline.videoId = "offline"; offline.localFilePath = "/fixture/video.mp4";
        videoView.duration = 60000; videoView.rawPosition = 12000;
        call(a, "playLocalVideoFile", new Class<?>[]{MorpheEngine.MediaItem.class}, offline);
        eq(View.VISIBLE, loading.getVisibility(), "offline video preparation uses the same indicator");
        videoView.firePrepared();
        eq(12000, bar.getProgress(), "working native position is authoritative for a local video");
        videoView.rawPosition = 13000; SystemClock.now += 1000; tick(a);
        eq(13000, bar.getProgress(), "changing native readings remain authoritative");
        check(text(time).equals("0:13 / 1:00\n0:47 left"), "native duration and remaining time");

        // VideoView.setOnInfoListener was added in API 17; API 15/16 attach to MediaPlayer instead.
        Build.VERSION.SDK_INT = 15; videoView.onInfo = null; videoView.rawPosition = 0;
        prepare(a, video(60), 20, 0); videoView.firePrepared();
        check(videoView.player.infoListener != null, "older Android uses MediaPlayer buffering listener");
        videoView.fireInfo(MediaPlayer.MEDIA_INFO_BUFFERING_START);
        SystemClock.now += 5000; tick(a);
        eq(0, bar.getProgress(), "API 15 buffering freezes the timeline");
        videoView.fireInfo(MediaPlayer.MEDIA_INFO_BUFFERING_END);
        SystemClock.now += 1000; tick(a);
        eq(1000, bar.getProgress(), "API 15 timeline resumes after buffering");
        Build.VERSION.SDK_INT = 17;
        System.out.println("PASS real YouTubeActivity: resolution/prepare, stale callbacks/errors/timeouts/stop, time-left, drag/transport seeks, pause, API 15/17 buffering, quality restore and offline/unknown duration");
    }
}
