package ca.arc7freedom.app;

/**
 * A monotonic-clock fallback for VideoView implementations that stop reporting their
 * position. All times are supplied by the caller so pauses, buffering and seeks can be
 * recorded at the instant they happen. A changing native position is authoritative;
 * an unchanged or unavailable reading is extrapolated only while video is advancing.
 */
final class VideoPositionTracker {
    private static final long SEEK_TOLERANCE_MS = 1500L;
    private static final long SEEK_GRACE_MS = 5000L;

    private int positionMs;
    private long updatedAtMs;
    private boolean advancing;
    private int lastNativeMs = -1;
    private boolean seekPending;
    private int seekTargetMs;
    private long seekStartedAtMs;

    void reset(int initialPositionMs, long nowMs) {
        positionMs = Math.max(0, initialPositionMs);
        updatedAtMs = nowMs;
        advancing = false;
        lastNativeMs = -1;
        seekPending = false;
    }

    /** Record the requested position immediately, without trusting an old async seek reading. */
    void seekTo(int targetMs, long nowMs) {
        positionMs = Math.max(0, targetMs);
        updatedAtMs = nowMs;
        seekPending = true;
        seekTargetMs = positionMs;
        seekStartedAtMs = nowMs;
    }

    /**
     * @param nativeMs position from the prepared player, or -1 when unavailable
     * @param isAdvancing true only while playing and not buffering
     * @param durationMs known duration, or 0 when unknown
     */
    int update(int nativeMs, boolean isAdvancing, long nowMs, int durationMs) {
        long estimated = positionMs;
        if (advancing) {
            long elapsed = Math.max(0L, nowMs - updatedAtMs);
            estimated = Math.min((long) Integer.MAX_VALUE,
                    estimated + Math.min((long) Integer.MAX_VALUE, elapsed));
        }

        if (nativeMs >= 0) {
            boolean changed = nativeMs != lastNativeMs;
            if (seekPending) {
                long low = Math.max(0L, (long) seekTargetMs - SEEK_TOLERANCE_MS);
                long high = Math.min((long) Integer.MAX_VALUE,
                        Math.max((long) seekTargetMs, estimated) + SEEK_TOLERANCE_MS);
                // A stale zero must not undo a nonzero seek on the affected ROMs.
                boolean nearTarget = nativeMs >= low && nativeMs <= high
                        && (nativeMs != 0 || seekTargetMs == 0);
                if (nearTarget && (changed || nativeMs == seekTargetMs)) {
                    estimated = nativeMs;
                    seekPending = false;
                } else if (lastNativeMs >= 0 && changed
                        && nowMs - seekStartedAtMs >= SEEK_GRACE_MS && nativeMs > 0) {
                    // A player that continues reporting a different position may have
                    // rejected the seek. Eventually show reality rather than a false jump.
                    estimated = nativeMs;
                    seekPending = false;
                }
                lastNativeMs = nativeMs;
            } else if (!(nativeMs == 0 && lastNativeMs > 0)) {
                // Some broken players intermittently return zero after valid samples.
                // Do not let those zeroes drag the timeline back to the beginning.
                if (changed) estimated = nativeMs;
                lastNativeMs = nativeMs;
            }
        }

        if (durationMs > 0) estimated = Math.min(estimated, (long) durationMs);
        positionMs = (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE, estimated));
        updatedAtMs = nowMs;
        advancing = isAdvancing;
        return positionMs;
    }
}
