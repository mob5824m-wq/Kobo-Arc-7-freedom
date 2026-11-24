package ca.arc7freedom.app;

import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real service; Android, media decoding and provider resolution are recording fakes. */
public final class AudioServiceStateHarness {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object get(Arc7AudioService service, String name) throws Exception {
        Field field = Arc7AudioService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private static void set(Arc7AudioService service, String name, Object value) throws Exception {
        Field field = Arc7AudioService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static void join(Thread thread) throws Exception {
        if (thread == null) return;
        thread.join(4000L);
        check(!thread.isAlive(), "Resolution worker did not finish: " + thread.getName());
    }

    private static MediaPlayer resolvedPlayer(Arc7AudioService service) throws Exception {
        join((Thread) get(service, "resolutionThread"));
        MediaPlayer player = (MediaPlayer) get(service, "player");
        check(player != null && player.state == MediaPlayer.PREPARING, "Expected an asynchronously preparing player: " + service.getPlaybackStatus());
        player.ownerMonitor = service;
        return player;
    }

    private static MediaPlayer startMusic(Arc7AudioService service) throws Exception {
        service.playMorpheQueue(Collections.singletonList(new MorpheEngine.MediaItem()), 0,
                "YouTube Music", "ca.arc7freedom.app.YouTubeMusicActivity");
        return resolvedPlayer(service);
    }

    private static Arc7AudioService create() {
        Handler.reset();
        MediaPlayer.invalidCalls.clear();
        MediaPlayer.unlockedCalls.clear();
        Toast.messages.clear();
        Equalizer.created = 0;
        MorpheEngine.compatibilityCalls = 0;
        MorpheEngine.fallbackEntered = null;
        MorpheEngine.fallbackRelease = null;
        MorpheEngine.sponsorEnabled = false;
        MorpheStreamServer.cancelled.clear();
        NowPlayingManager.clearSession(null);
        NowPlayingManager.playingPublications = 0;
        Arc7AudioService service = new Arc7AudioService();
        service.onCreate();
        return service;
    }

    private static void noMisuse(String context) {
        check(MediaPlayer.invalidCalls.isEmpty(), context + ": asynchronous INVALID_OPERATION(-38,0): " + MediaPlayer.invalidCalls);
        check(MediaPlayer.unlockedCalls.isEmpty(), context + ": player operations raced replacement/release: " + MediaPlayer.unlockedCalls);
    }

    private static void close(Arc7AudioService service) {
        service.onDestroy();
        Handler.drain();
        noMisuse("Service cleanup");
        check(Handler.timers().isEmpty(), "Service left checkpoint/preparation callbacks behind");
        check(!ca.arc7freedom.support.MediaCache.busy(), "Service leaked a media cache lease");
    }

    @SuppressWarnings("unchecked")
    private static void pollPending(Arc7AudioService service, MediaPlayer player) throws Exception {
        boolean wasSponsorEnabled = MorpheEngine.sponsorEnabled;
        MorpheEngine.sponsorEnabled = true;
        synchronized (service) {
            MorpheEngine.SkipSegment segment = new MorpheEngine.SkipSegment();
            segment.startSec = 1f; segment.endSec = 5f;
            ((MorpheEngine.ResolvedPlayback) get(service, "activeMorphePlayback")).skipSegments.add(segment);
            ((List<MorpheEngine.SkipSegment>) get(service, "activeSkipSegments")).add(segment);
        }
        int before = player.queries, volumes = player.volumeWrites, effects = Equalizer.created;
        for (int i = 0; i < 12; i++) {
            // The real Music screen reads all three on every UI tick, even during preparation.
            check(!service.isPlaying(), "Published playing before OnPrepared");
            check(service.getDurationMs() == 0 && service.getPositionMs() == 0, "Pending time must be unknown/zero");
            service.seekRelativeMs(30000);
            service.seekAbsoluteMs(12000);
            service.pausePlayback();
            service.togglePlayPause();
            service.applyEqualizerPreset(2);
            service.setSleepTimerMinutes(0);
            Handler.fireTimer((Runnable) get(service, "checkpointTask"));
        }
        Handler.drain(); // Deliver JNI-style errors, if any; try/catch in the service cannot catch these.
        noMisuse("UI/checkpoint/transport polling during prepare: " + service.getPlaybackStatus());
        check(player.queries == before, "Pending UI/checkpoint touched native player getters");
        check(player.volumeWrites == volumes && Equalizer.created == effects, "Sleep/DSP touched a preparing player");
        check(player.starts == 0 && service.isResolvingMorpheTrack(), "Pending polling cancelled or started playback");
        check(NowPlayingManager.playingPublications == 0, "Preparing session published Playing");
        check(Toast.messages.isEmpty(), "Preparing session displayed an error: " + Toast.messages);
        MorpheEngine.sponsorEnabled = wasSponsorEnabled;
    }

    private static void prepare(Arc7AudioService service, MediaPlayer player) {
        player.emitPrepared();
        Handler.drain();
        check(player.state == MediaPlayer.STARTED && service.isPlaying(), "OnPrepared did not start the track");
        check(!service.isResolvingMorpheTrack() && service.getPlaybackStatus().length() == 0, "Successful preparation retained loading/error state");
        check(player.starts == 1 && NowPlayingManager.playingPublications > 0, "Playing must follow successful prepare/start");
    }

    private static void repeatOneReusesPreparedDecoder() throws Exception {
        Arc7AudioService service = create();
        MediaPlayer player = startMusic(service);
        prepare(service, player);
        service.setRepeatMode(Arc7AudioService.REPEAT_ONE);
        int resolves = MorpheEngine.resolves;
        player.emitCompletion();
        Handler.drain();
        check(get(service, "player") == player && player.state == MediaPlayer.STARTED,
                "Repeat One replaced the current player instead of restarting its decoder");
        check(player.starts == 2 && player.position == 0 && player.stops == 0,
                "Repeat One did not seek/restart the prepared decoder in place");
        check(MorpheEngine.resolves == resolves, "Repeat One re-resolved the same online track");
        close(service);
        System.out.println("PASS: Repeat One seeks/restarts the prepared player without resolving the same track again");
    }

    private static void asynchronousErrorModel() {
        final int[] received = new int[3];
        MediaPlayer player = new MediaPlayer();
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                received[0]++; received[1] = what; received[2] = extra; return true;
            }
        });
        player.setDataSource("http://fixture/model.m4a");
        player.prepareAsync();
        check(player.getDuration() == 0, "Fake duration must return without throwing");
        check(received[0] == 0, "Native error incorrectly delivered synchronously");
        Handler.drain();
        check(received[0] == 1 && received[1] == -38 && received[2] == 0, "Fake did not deliver JNI MEDIA_ERROR(-38,0)");
        player.release();
        MediaPlayer.invalidCalls.clear();
        System.out.println("PASS: JNI-style invalid duration operation returns normally, then delivers asynchronous -38/0");
    }

    @SuppressWarnings("unchecked")
    private static void preparingAndNormalTransport() throws Exception {
        Arc7AudioService service = create();
        MediaPlayer player = startMusic(service);
        pollPending(service, player);
        check(Equalizer.created == 0, "Effect session opened during prepare");
        prepare(service, player);
        check(Equalizer.created == 1 && service.getCurrentEqPreset() == 2, "Deferred EQ selection was not applied after prepare");
        check(service.getDurationMs() == 42000 && service.getPositionMs() == 3000, "Ready time getters are broken");
        service.seekAbsoluteMs(-10);
        check(service.getPositionMs() == 0, "Absolute seek lower clamp");
        service.seekRelativeMs(30000);
        check(service.getPositionMs() == 30000, "Relative seek after prepare");
        service.seekAbsoluteMs(99999);
        check(service.getPositionMs() == 42000, "Absolute seek upper clamp");
        service.pausePlayback();
        check(!service.isPlaying() && service.getDurationMs() == 42000 && service.getPositionMs() == 42000, "Paused player must remain readable");
        service.seekRelativeMs(-15000);
        check(service.getPositionMs() == 27000, "Paused seeking was disabled");
        service.togglePlayPause();
        check(service.isPlaying() && player.starts == 2, "Paused playback cannot resume");
        synchronized (service) {
            MorpheEngine.SkipSegment segment = new MorpheEngine.SkipSegment();
            segment.startSec = 1f; segment.endSec = 5f;
            ((MorpheEngine.ResolvedPlayback) get(service, "activeMorphePlayback")).skipSegments.add(segment);
            ((List<MorpheEngine.SkipSegment>) get(service, "activeSkipSegments")).add(segment);
        }
        MorpheEngine.sponsorEnabled = true;
        player.position = 3000;
        Handler.fireTimer((Runnable) get(service, "checkpointTask"));
        check(service.getPositionMs() == 5000, "Ready SponsorBlock skip regressed");
        service.setSleepTimerMinutes(1);
        set(service, "sleepDeadlineMs", System.currentTimeMillis() + 20000L);
        Handler.fireTimer((Runnable) get(service, "checkpointTask"));
        check(player.volume > 0f && player.volume < 1f, "Ready sleep fade regressed");
        set(service, "sleepDeadlineMs", System.currentTimeMillis() - 1L);
        Handler.fireTimer((Runnable) get(service, "checkpointTask"));
        check(!service.isPlaying(), "Sleep expiry did not pause");
        service.togglePlayPause();
        player.emitCompletion();
        Handler.drain();
        check(!service.isPlaying() && service.getDurationMs() == 42000, "Completion getters are not usable");
        service.togglePlayPause();
        check(service.isPlaying(), "Completed track cannot restart");
        close(service);
        check(player.stops == 0 && player.state == MediaPlayer.RELEASED, "Cleanup must release directly, not stop a possibly errored player");
        System.out.println("PASS: repeated preparing UI/checkpoint/seek/DSP polling stays quiet; prepared/paused/completed time, seek, EQ, SponsorBlock, sleep and resume remain functional");
    }

    private static void localBookmarks() throws Exception {
        Arc7AudioService service = create();
        File directory = File.createTempFile("arc7-bookmark-", ".fixture");
        check(directory.delete() && directory.mkdir(), "Cannot create local bookmark fixture directory");
        File track = new File(directory, "owned-audio.mp3");
        try {
            // Empty file: the player is a recording fake, not an actual MP3 decoder.
            check(track.createNewFile(), "Cannot create local bookmark fixture");
            String key = "pos_" + track.getAbsolutePath().hashCode();
            service.getSharedPreferences("arc7_audio_bookmarks", 0).edit().putInt(key, 12000).commit();
            service.loadTrackAndFolderQueue(track);
            MediaPlayer first = (MediaPlayer) get(service, "player");
            first.ownerMonitor = service;
            check(service.isPlaying() && service.getPositionMs() == 12000, "Local bookmark was not restored after synchronous prepare");
            service.seekAbsoluteMs(23000);
            Handler.fireTimer((Runnable) get(service, "checkpointTask"));
            check(service.getSharedPreferences("arc7_audio_bookmarks", 0).getInt(key, 0) == 23000, "Prepared local checkpoint was not saved");
            service.loadTrackAndFolderQueue(track);
            MediaPlayer second = (MediaPlayer) get(service, "player");
            second.ownerMonitor = service;
            check(first.state == MediaPlayer.RELEASED && service.getPositionMs() == 23000, "In-place local restart lost its bookmark");
            check(service.getSharedPreferences("arc7_audio_bookmarks", 0).getString("last_track", "").equals(track.getAbsolutePath()), "Last local track was not retained");
            close(service);
        } finally {
            track.delete(); directory.delete();
        }
        System.out.println("PASS: synchronously prepared local playback still restores/saves bookmarks and the last track");
    }

    private static void replacementAndStaleCallbacks() throws Exception {
        Arc7AudioService service = create();
        MediaPlayer old = startMusic(service);
        MediaPlayer.OnPreparedListener prepared = old.preparedListener;
        MediaPlayer.OnErrorListener error = old.errorListener;
        MediaPlayer.OnCompletionListener complete = old.completionListener;
        Runnable timeout = (Runnable) get(service, "prepareTimeout");
        MediaPlayer current = startMusic(service);
        check(old.state == MediaPlayer.RELEASED && old.stops == 0, "Replacing a preparing player issued invalid stop");
        check(old.preparedListener == null && old.errorListener == null && old.completionListener == null, "Released player listeners remain attached");
        // Model callbacks already queued before listener detachment/release.
        prepared.onPrepared(old);
        error.onError(old, -38, 0);
        complete.onCompletion(old);
        timeout.run();
        Handler.drain();
        check(get(service, "player") == current && current.starts == 0 && service.isResolvingMorpheTrack(), "Stale callback replaced/started/failed the current request");
        pollPending(service, current);
        prepare(service, current);
        service.stopAndDismissNotification();
        check(!service.isPlaying() && service.getDurationMs() == 0 && service.getPositionMs() == 0, "Released getters did not return safe defaults");
        check(!NowPlayingManager.getSnapshot(service).active, "Dismiss left an active session");
        MediaPlayer destroyed = startMusic(service);
        close(service);
        check(destroyed.state == MediaPlayer.RELEASED && destroyed.stops == 0, "Destroying during preparation issued invalid stop");
        System.out.println("PASS: prepare cancellation/destroy releases without stop; detached and already-queued stale callbacks cannot affect a replacement request");
    }

    private static void compatibilityRetryAndFailure() throws Exception {
        Arc7AudioService service = create();
        MediaPlayer original = startMusic(service);
        original.emitError(1, -1010);
        Handler.drain();
        MediaPlayer alternate = resolvedPlayer(service);
        check(MorpheEngine.compatibilityCalls == 1 && alternate.dataSource.endsWith(".aac"), "Genuine unsupported container did not keep the one-shot AAC/ADTS fallback");
        check(original.state == MediaPlayer.RELEASED && original.stops == 0, "Error cleanup stopped a failed player");
        pollPending(service, alternate);
        prepare(service, alternate);
        alternate.emitError(1, -1007);
        Handler.drain();
        check(MorpheEngine.compatibilityCalls == 1 && get(service, "player") == null, "Compatibility fallback looped");
        check(service.getPlaybackStatus().contains("MediaPlayer 1/-1007") && !service.isResolvingMorpheTrack(), "Terminal decoder failure was hidden");
        check(!NowPlayingManager.getSnapshot(service).active, "Failure retained Playing notification state");
        close(service);

        service = create();
        MediaPlayer invalid = startMusic(service);
        invalid.emitError(-38, 0);
        Handler.drain();
        check(!LegacyMediaPolicy.audioContainerFailure(-38, 0) && MorpheEngine.compatibilityCalls == 0, "Invalid operation was misclassified/retried as a stream-container failure");
        check(service.getPlaybackStatus().contains("MediaPlayer -38/0") && service.getPlaybackStatus().contains("player state"), "Invalid-operation diagnostic still blames the stream: " + service.getPlaybackStatus());
        close(service);
        System.out.println("PASS: genuine container errors retain one retry, second failure remains visible, and -38/0 is not disguised as a container problem");
    }

    private static void cancellationDuringCompatibility() throws Exception {
        Arc7AudioService service = create();
        MediaPlayer original = startMusic(service);
        MorpheEngine.fallbackEntered = new CountDownLatch(1);
        MorpheEngine.fallbackRelease = new CountDownLatch(1);
        original.emitError(1, -1010);
        Handler.drain();
        Thread fallback = (Thread) get(service, "resolutionThread");
        check(MorpheEngine.fallbackEntered.await(3, TimeUnit.SECONDS), "Fallback did not enter the controlled provider fixture");
        MediaPlayer replacement;
        try {
            replacement = startMusic(service);
        } finally {
            MorpheEngine.fallbackRelease.countDown();
            join(fallback);
        }
        check(MorpheStreamServer.cancelled.contains("http://fixture/compat-1.aac"), "Late compatibility stream was not cancelled");
        check(get(service, "player") == replacement && replacement.state == MediaPlayer.PREPARING, "Cancelled fallback overwrote the new track");
        pollPending(service, replacement);
        prepare(service, replacement);
        close(service);
        System.out.println("PASS: even a provider fixture ignoring interruption cannot resurrect a cancelled compatibility request");
    }

    private static void timeoutAndConcurrentRelease() throws Exception {
        Arc7AudioService service = create();
        MediaPlayer pending = startMusic(service);
        Handler.fireTimer((Runnable) get(service, "prepareTimeout"));
        Handler.drain();
        check(pending.state == MediaPlayer.RELEASED && pending.stops == 0, "Preparation timeout stopped an unprepared player");
        check(service.getPlaybackStatus().contains("timed out") && service.getDurationMs() == 0, "Timeout diagnosis/cleanup regressed");
        close(service);

        final Arc7AudioService concurrent = create();
        final MediaPlayer player = startMusic(concurrent);
        prepare(concurrent, player);
        player.durationEntered = new CountDownLatch(1);
        player.durationRelease = new CountDownLatch(1);
        final int[] duration = new int[1];
        final CountDownLatch stopping = new CountDownLatch(1), stopped = new CountDownLatch(1);
        Thread getter = new Thread(new Runnable() {
            public void run() { duration[0] = concurrent.getDurationMs(); }
        }, "Fixture-UI-Poll");
        Thread stopper = new Thread(new Runnable() {
            public void run() { stopping.countDown(); try { concurrent.stopAndDismissNotification(); } finally { stopped.countDown(); } }
        }, "Fixture-Service-Stop");
        getter.start();
        try {
            check(player.durationEntered.await(3, TimeUnit.SECONDS), "Poll did not enter native getter fixture");
            stopper.start();
            check(stopping.await(3, TimeUnit.SECONDS), "Stop thread did not start");
            check(!stopped.await(150, TimeUnit.MILLISECONDS), "Release raced a player getter");
        } finally {
            player.durationRelease.countDown();
            join(getter); join(stopper);
        }
        check(duration[0] == 42000 && player.state == MediaPlayer.RELEASED, "Serialized duration/stop returned the wrong result");
        close(concurrent);
        System.out.println("PASS: preparation timeout is clean; player getters and concurrent release share the same lifecycle lock");
    }

    private static void focusAndHeadphones() throws Exception {
        Arc7AudioService service=create();MediaPlayer p=startMusic(service);
        check(service.audio.requests==0&&!Arc7AudioService.acceptsMediaButtons(),"Preparing/silent watchers must not steal focus/buttons");
        p.emitPrepared();Handler.drain();
        check(service.isPlaying()&&service.audio.requests==1&&service.audio.registrations==1&&Arc7AudioService.acceptsMediaButtons(),"Prepared playback must acquire focus + media buttons");
        service.audio.change(-3);Handler.drain();check(service.isPlaying()&&Math.abs(p.volume-.2f)<.001,"Duck must preserve playback at 20%");
        service.audio.change(1);Handler.drain();check(Math.abs(p.volume-1f)<.001,"Gain must restore app volume, not force system volume");
        service.audio.change(-2);Handler.drain();check(!service.isPlaying(),"Transient loss must pause");
        service.audio.change(-2);Handler.drain();service.audio.change(1);Handler.drain();check(service.isPlaying(),"Repeated transient loss must resume the same player on gain");
        service.audio.change(-2);Handler.drain();android.media.AudioManager.OnAudioFocusChangeListener stale=service.audio.listener;
        service.pausePlayback();stale.onAudioFocusChange(1);Handler.drain();check(!service.isPlaying(),"User pause during transient loss must veto auto-resume");
        service.togglePlayPause();check(service.isPlaying(),"Explicit play should request focus again");
        android.content.BroadcastReceiver noisy=(android.content.BroadcastReceiver)get(service,"noisyReceiver");
        noisy.onReceive(service,new android.content.Intent().setAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        check(!service.isPlaying(),"Headphone unplug must pause");
        if(service.audio.listener!=null)service.audio.change(1);Handler.drain();check(!service.isPlaying(),"Reconnect/gain must not auto-resume an unplugged player");
        service.togglePlayPause();service.audio.change(-1);Handler.drain();check(!service.isPlaying()&&!Arc7AudioService.acceptsMediaButtons(),"Permanent loss must pause and unregister media buttons");
        service.togglePlayPause();stale=service.audio.listener;
        MediaPlayer replacement=startMusic(service);prepare(service,replacement);int starts=replacement.starts;
        stale.onAudioFocusChange(-1);Handler.drain();check(replacement.starts==starts&&service.isPlaying(),"Queued focus callback from old player must not pause replacement");
        replacement.bufferingListener.onBufferingUpdate(replacement,57);service.recordDiagnosticSnapshot();
        check(service.getDiagnosticReport().contains("57%")&&!service.getDiagnosticReport().contains("http://fixture"),"Diagnostic report must expose buffering but not source URLs");
        close(service);check(!Arc7AudioService.acceptsMediaButtons()&&service.audio.registrations==0,"Destroy must drop button ownership");

        service=create();p=startMusic(service);service.audio.result=0;p.emitPrepared();Handler.drain();
        check(!service.isPlaying()&&p.starts==0&&service.getDiagnosticReport().contains("Waiting for audio focus"),"Denied focus must never start audible playback");
        service.audio.result=1;service.togglePlayPause();check(service.isPlaying(),"Manual retry after focus denial should work");close(service);

        service=create();p=startMusic(service);noisy=(android.content.BroadcastReceiver)get(service,"noisyReceiver");
        noisy.onReceive(service,new android.content.Intent().setAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        p.emitPrepared();Handler.drain();check(p.starts==0&&!service.isPlaying()&&service.audio.requests==0,"Unplug during prepare must veto late auto-start without invalid native calls");
        service.togglePlayPause();check(service.isPlaying(),"Explicit play after pending unplug should work");close(service);
        System.out.println("PASS: prepared-only focus/buttons, duck/restore, repeated transient recovery, user-pause/unplug/permanent-loss veto, stale callbacks, denied-focus retry and sanitized diagnostics (recording Android fakes)");
    }

    private static void headsetButtons() throws Exception {
        android.content.Context context=new android.content.Context();Arc7MediaButtonReceiver receiver=new Arc7MediaButtonReceiver();
        android.content.Intent intent=new android.content.Intent().setAction(android.content.Intent.ACTION_MEDIA_BUTTON)
            .putExtra(android.content.Intent.EXTRA_KEY_EVENT,new android.view.KeyEvent(0,85,0));
        receiver.onReceive(context,intent);check(context.lastService==null,"Cold/silent APK must not react to media buttons");
        Arc7AudioService service=create();MediaPlayer p=startMusic(service);prepare(service,p);
        int[] keys={79,85,126,127,87,88,89,90,86};
        String[] actions={Arc7AudioService.ACTION_TOGGLE,Arc7AudioService.ACTION_TOGGLE,Arc7AudioService.ACTION_PLAY,Arc7AudioService.ACTION_PAUSE,Arc7AudioService.ACTION_NEXT,Arc7AudioService.ACTION_PREV,Arc7AudioService.ACTION_REWIND_30,Arc7AudioService.ACTION_FFWD_30,Arc7AudioService.ACTION_STOP_DISMISS};
        for(int i=0;i<keys.length;i++){
            context.lastService=null;intent.putExtra(android.content.Intent.EXTRA_KEY_EVENT,new android.view.KeyEvent(0,keys[i],0));receiver.onReceive(context,intent);
            check(context.lastService!=null&&actions[i].equals(context.lastService.getAction()),"Wrong headset mapping: "+keys[i]);
        }
        for(int[] key:new int[][]{{1,85,0},{0,85,1},{0,1,0}}){context.lastService=null;intent.putExtra(android.content.Intent.EXTRA_KEY_EVENT,new android.view.KeyEvent(key[0],key[1],key[2]));receiver.onReceive(context,intent);check(context.lastService==null,"Key-up/repeat/unknown event must not double-toggle");}
        service.onStartCommand(new android.content.Intent().setAction(Arc7AudioService.ACTION_PAUSE),0,1);check(!service.isPlaying(),"Explicit PAUSE must not toggle to playing");
        service.onStartCommand(new android.content.Intent().setAction(Arc7AudioService.ACTION_PAUSE),0,1);check(!service.isPlaying(),"Repeated explicit PAUSE must stay paused");
        service.onStartCommand(new android.content.Intent().setAction(Arc7AudioService.ACTION_PLAY),0,1);check(service.isPlaying(),"Explicit PLAY should resume");
        int starts=p.starts;service.onStartCommand(new android.content.Intent().setAction(Arc7AudioService.ACTION_PLAY),0,1);check(p.starts==starts,"Repeated PLAY must not restart a playing track");
        close(service);System.out.println("PASS: real headset receiver ownership, play/pause/next/previous/seek/stop mapping, single key-down handling and idempotent explicit play/pause commands");
    }

    private static boolean payloadContains(String needle) {
        synchronized (MorpheEngine.listensSent) {
            for (String s : MorpheEngine.listensSent) if (s.contains(needle)) return true;
        }
        return false;
    }

    private static int countPayloads(String needle) {
        int count = 0;
        synchronized (MorpheEngine.listensSent) {
            for (String s : MorpheEngine.listensSent) if (s.contains(needle)) count++;
        }
        return count;
    }

    private static void waitForSubmissions(int needleCount, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (countPayloads(needle) >= needleCount) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for ListenBrainz submission '" + needle + "': " + MorpheEngine.listensSent);
    }

    private static MorpheEngine.MediaItem fixtureItem(String title, long durationSec) {
        MorpheEngine.MediaItem item = new MorpheEngine.MediaItem();
        item.title = title;
        item.author = "Fixture artist";
        item.durationSec = durationSec;
        return item;
    }

    private static void listenBrainzScrobbleFlow() throws Exception {
        Arc7AudioService service = create();
        try {
            ListenBrainzEngine.setEnabled(service, true);
            ListenBrainzEngine.setToken(service, "fixture-token");
            MorpheEngine.listensSent.clear();

            service.playMorpheQueue(Collections.singletonList(fixtureItem("Fixture song", 200L)), 0,
                    "YouTube Music", "ca.arc7freedom.app.YouTubeMusicActivity");
            MediaPlayer player = resolvedPlayer(service);
            player.emitPrepared();
            Handler.drain();
            check(player.state == MediaPlayer.STARTED, "Fixture track did not start under ListenBrainz");

            waitForSubmissions(1, "playing_now");
            check(payloadContains("playing_now"), "playing_now was not submitted: " + MorpheEngine.listensSent);
            check(payloadContains("fixture-token"), "Authorization token was not sent with the submission");

            // 110 s of a 200 s track clears the half-track threshold (first tick only arms the position baseline).
            for (int i = 0; i < 110; i++) {
                player.position += 1000;
                Handler.fireTimer((Runnable) get(service, "checkpointTask"));
            }
            waitForSubmissions(1, "\"single\"");
            check(countPayloads("\"single\"") == 1, "Half-track scrobble missing or duplicated: " + MorpheEngine.listensSent);

            // A second track: a bare forward jump is a seek, never listened time — no scrobble.
            service.playMorpheQueue(Collections.singletonList(fixtureItem("Second fixture", 200L)), 0,
                    "YouTube Music", "ca.arc7freedom.app.YouTubeMusicActivity");
            MediaPlayer jumped = resolvedPlayer(service);
            jumped.emitPrepared();
            Handler.drain();
            jumped.position = 198000;
            Handler.fireTimer((Runnable) get(service, "checkpointTask"));
            check(countPayloads("\"single\"") == 1, "Forward seek counted as listening: " + MorpheEngine.listensSent);

            // Playing to the end counts as a fully heard track even below the threshold.
            jumped.emitCompletion();
            Handler.drain();
            waitForSubmissions(2, "\"single\"");
            check(countPayloads("\"single\"") == 2, "Natural completion did not submit the listen: " + MorpheEngine.listensSent);
            System.out.println("PASS: ListenBrainz playing-now, half-track threshold, seek suppression and natural-end scrobbling (recording HTTP fakes)");
        } finally {
            close(service);
        }
    }

    private static void ytMusicStuckPositionAndSeek() throws Exception {
        Arc7AudioService service = create();
        try {
            MorpheEngine.MediaItem song = fixtureItem("Stuck Pos Song", 240L);
            service.playMorpheQueue(Collections.singletonList(song), 0,
                    "YouTube Music", "ca.arc7freedom.app.YouTubeMusicActivity");
            MediaPlayer player = resolvedPlayer(service);
            // Simulate Stagefright returning a small stuck positive buffer sample (23 ms)
            // even while the song is playing and total duration is 240000 ms.
            player.position = 23;
            player.emitPrepared();
            Handler.drain();
            check(service.isPlaying(), "YT Music track failed to start");
            check(service.getDurationMs() == 240000, "YT Music duration mismatch: " + service.getDurationMs());
            check(service.getPositionMs() == 23, "Initial position mismatch: " + service.getPositionMs());

            android.os.SystemClock.advanceBy(5000L);
            int p1 = service.getPositionMs();
            check(p1 >= 5000 && p1 <= 5200, "Stuck 23ms Stagefright position did not advance: " + p1);

            // Pausing freezes position; paused time must not be counted upon resume.
            service.pausePlayback();
            android.os.SystemClock.advanceBy(8000L);
            int pausedPos = service.getPositionMs();
            check(Math.abs(pausedPos - p1) <= 200, "Paused position drifted: " + pausedPos + " vs " + p1);

            service.togglePlayPause();
            int resumedPos = service.getPositionMs();
            check(Math.abs(resumedPos - pausedPos) <= 200, "Resume lost or jumped position: " + resumedPos + " vs " + pausedPos);
            android.os.SystemClock.advanceBy(3000L);
            int p2 = service.getPositionMs();
            check(p2 >= resumedPos + 2900 && p2 <= resumedPos + 3200, "Resumed position did not advance: " + p2);

            // Buffering pauses elapsed clock; BUFFERING_END resumes it cleanly.
            player.emitInfo(MediaPlayer.MEDIA_INFO_BUFFERING_START, 0);
            Handler.drain();
            int bufPos = service.getPositionMs();
            android.os.SystemClock.advanceBy(4000L);
            check(Math.abs(service.getPositionMs() - bufPos) <= 200, "Buffering advanced position");
            player.emitInfo(MediaPlayer.MEDIA_INFO_BUFFERING_END, 0);
            Handler.drain();
            android.os.SystemClock.advanceBy(2000L);
            int afterBuf = service.getPositionMs();
            check(afterBuf >= bufPos + 1900 && afterBuf <= bufPos + 2200, "Position did not resume after buffering: " + afterBuf);

            // Seeking while Stagefright stays stuck at 23ms must jump to target and continue advancing,
            // even past the 5s seek-grace window.
            player.freezePosition = true;
            service.seekAbsoluteMs(90000);
            int sought = service.getPositionMs();
            check(sought >= 89900 && sought <= 90200, "Stuck Stagefright sample overwrote seek: " + sought);
            android.os.SystemClock.advanceBy(6000L);
            int afterSeek = service.getPositionMs();
            check(afterSeek >= 95900 && afterSeek <= 96200, "Position did not advance past seek grace from target: " + afterSeek);

            // Stuck zero on a new track must also advance.
            MorpheEngine.MediaItem song2 = fixtureItem("Stuck Zero Song", 180L);
            service.playMorpheQueue(Collections.singletonList(song2), 0,
                    "YouTube Music", "ca.arc7freedom.app.YouTubeMusicActivity");
            MediaPlayer player2 = resolvedPlayer(service);
            player2.position = 0;
            player2.emitPrepared();
            Handler.drain();
            check(service.getDurationMs() == 180000, "Second track duration mismatch");
            android.os.SystemClock.advanceBy(4000L);
            int zeroAdvanced = service.getPositionMs();
            check(zeroAdvanced >= 3900 && zeroAdvanced <= 4200, "Stuck zero position did not advance: " + zeroAdvanced);

            // Repeat One completion restart resets position back to zero.
            service.setRepeatMode(Arc7AudioService.REPEAT_ONE);
            player2.emitCompletion();
            Handler.drain();
            int restarted = service.getPositionMs();
            check(restarted >= 0 && restarted <= 200, "Repeat-one restart did not reset position: " + restarted);
            android.os.SystemClock.advanceBy(3000L);
            int afterRestart = service.getPositionMs();
            check(afterRestart >= 2900 && afterRestart <= 3200, "Repeat-one restarted track did not advance: " + afterRestart);

            System.out.println("PASS: YT Music stuck-zero/stuck-positive elapsed time, pause/resume, buffering, seek-grace and repeat-one reset");
        } finally {
            close(service);
        }
    }

    public static void main(String[] args) throws Exception {
        asynchronousErrorModel();
        preparingAndNormalTransport();
        repeatOneReusesPreparedDecoder();
        localBookmarks();
        replacementAndStaleCallbacks();
        compatibilityRetryAndFailure();
        cancellationDuringCompatibility();
        timeoutAndConcurrentRelease();
        focusAndHeadphones();
        headsetButtons();
        listenBrainzScrobbleFlow();
        ytMusicStuckPositionAndSeek();
    }
}
