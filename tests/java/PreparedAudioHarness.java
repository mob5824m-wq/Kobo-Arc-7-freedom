package ca.arc7freedom.app;

import android.media.MediaPlayer;
import java.io.IOException;

/** Host-only fake MediaPlayer; exercises the production pre-start audio guard. */
public final class PreparedAudioHarness {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        MediaPlayer player = new MediaPlayer();
        player.tracks = new MediaPlayer.TrackInfo[]{new MediaPlayer.TrackInfo(1)};
        check(LegacyPlaybackAudio.trackState(player) == LegacyPlaybackAudio.MISSING, "Video-only is not audio-ready");
        boolean failed = false;
        try { LegacyPlaybackAudio.enable(player); } catch (IOException expected) { failed = true; }
        check(failed && player.volumeCalls == 0, "Video-only player was enabled before recovery");
        player.tracks = new MediaPlayer.TrackInfo[]{new MediaPlayer.TrackInfo(1), null, new MediaPlayer.TrackInfo(2)};
        check(LegacyPlaybackAudio.trackState(player) == LegacyPlaybackAudio.PRESENT, "Audio need not be the first track");
        LegacyPlaybackAudio.enable(player);
        check(player.volumeCalls == 1 && player.left == 1.0f && player.right == 1.0f, "Audio volume was not enabled");
        player.tracks = new MediaPlayer.TrackInfo[]{new MediaPlayer.TrackInfo(2)};
        LegacyPlaybackAudio.enable(player); // audio-only quality / background service
        check(player.volumeCalls == 2, "Audio-only playback regressed");
        for (MediaPlayer.TrackInfo[] tracks : new MediaPlayer.TrackInfo[][]{
                null, new MediaPlayer.TrackInfo[0], {null}, {new MediaPlayer.TrackInfo(0)}}) {
            player.tracks = tracks;
            check(LegacyPlaybackAudio.trackState(player) == LegacyPlaybackAudio.UNKNOWN, "Unavailable diagnostics are not proof of silence");
            LegacyPlaybackAudio.enable(player);
        }
        for (int unavailable : new int[]{1, 2}) {
            player.unavailable = unavailable;
            check(LegacyPlaybackAudio.trackState(player) == LegacyPlaybackAudio.UNKNOWN, "Vendor/Android-4.0 missing API handling");
            LegacyPlaybackAudio.enable(player);
        }
        System.out.println("PASS: production prepared-player audio guard rejects video-only before start, enables audio-only/combined tracks, tolerates missing Android-4/vendor track APIs, never changes system volume");
    }
}
