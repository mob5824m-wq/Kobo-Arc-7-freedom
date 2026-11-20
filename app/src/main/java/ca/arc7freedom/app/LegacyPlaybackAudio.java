package ca.arc7freedom.app;

import android.media.MediaPlayer;
import java.io.IOException;

/** Guard prepared-player readiness; metadata and a moving picture are not proof of audio. */
public final class LegacyPlaybackAudio {
    private LegacyPlaybackAudio() {}

    public static final int UNKNOWN = -1;
    public static final int MISSING = 0;
    public static final int PRESENT = 1;

    public static int trackState(MediaPlayer player) {
        try {
            MediaPlayer.TrackInfo[] tracks = player.getTrackInfo();
            // Some Android 4.0/vendor players don't implement the API. Do not mistake
            // missing diagnostics for a definite absence of audio on those ROMs.
            if (tracks == null || tracks.length == 0) return UNKNOWN;
            boolean knownTrack = false;
            for (int i = 0; i < tracks.length; i++) {
                if (tracks[i] == null) continue;
                int type = tracks[i].getTrackType();
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) return PRESENT;
                if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_UNKNOWN) knownTrack = true;
            }
            return knownTrack ? MISSING : UNKNOWN;
        } catch (RuntimeException unavailable) { return UNKNOWN; }
        catch (LinkageError unavailable) { return UNKNOWN; }
    }

    /** Called after prepare and before start/publishing Playing. Never alters system volume. */
    public static void enable(MediaPlayer player) throws IOException {
        if (trackState(player) == MISSING)
            throw new IOException("The device opened this stream without an audio track");
        player.setVolume(1.0f, 1.0f);
    }
}
