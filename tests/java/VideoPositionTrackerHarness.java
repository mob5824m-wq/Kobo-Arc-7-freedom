package ca.arc7freedom.app;

/** Deterministic tests of the production foreground-video clock, with a fake player. */
public final class VideoPositionTrackerHarness {
    private static void eq(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        VideoPositionTracker clock = new VideoPositionTracker();
        clock.reset(0, 1000);
        eq(0, clock.update(0, true, 1000, 60000), "play starts at zero");
        eq(1500, clock.update(0, true, 2500, 60000), "stuck zero advances");
        eq(4000, clock.update(0, false, 5000, 60000), "pause records the current position");
        eq(4000, clock.update(0, false, 20000, 60000), "pause remains frozen");
        eq(4000, clock.update(0, true, 20000, 60000), "resume does not include paused time");
        eq(5000, clock.update(0, true, 21000, 60000), "resumed clock advances");

        // A buffering-start event records the point reached; the end event restarts the clock.
        eq(5500, clock.update(0, false, 21500, 60000), "buffer start freezes at current position");
        eq(5500, clock.update(0, false, 31500, 60000), "buffered time is not counted");
        eq(5500, clock.update(0, true, 31500, 60000), "buffer end resumes at the same position");
        eq(6500, clock.update(0, true, 32500, 60000), "clock resumes after buffering");

        clock.seekTo(30000, 32500);
        eq(30000, clock.update(0, true, 32500, 60000), "stale zero cannot undo forward seek");
        eq(32000, clock.update(0, true, 34500, 60000), "stuck player advances from seek target");
        eq(32500, clock.update(32500, true, 35000, 60000), "native player can resynchronise after seek");
        eq(33500, clock.update(32500, true, 36000, 60000), "stale nonzero reading is extrapolated");
        eq(34500, clock.update(0, true, 37000, 60000), "intermittent zero does not reset a valid timeline");
        eq(35500, clock.update(32500, true, 38000, 60000), "return to old stale value does not reset clock");
        eq(35500, clock.update(35500, true, 38000, 60000), "changing native position is authoritative");

        clock.seekTo(0, 38000);
        eq(0, clock.update(35500, false, 38000, 60000), "old native value cannot undo backward seek");
        eq(0, clock.update(0, false, 39000, 60000), "seek to zero is accepted while paused");
        eq(0, clock.update(0, true, 49000, 60000), "resume from zero ignores paused time");
        eq(1000, clock.update(0, true, 50000, 60000), "resume after zero seek");

        clock.seekTo(59000, 50000);
        eq(60000, clock.update(-1, true, 55000, 60000), "clamp fallback to duration");
        eq(60000, clock.update(-1, true, 60000, 60000), "stay at the end");
        clock.reset(0, 60000);
        eq(0, clock.update(-1, true, 60000, 0), "new video resets the position");
        eq(3000, clock.update(-1, true, 63000, 0), "unknown duration still advances");
        eq(Integer.MAX_VALUE, clock.update(-1, true, Long.MAX_VALUE / 2, 0), "huge elapsed time saturates instead of overflowing");

        // If native playback keeps moving far from a requested target, the seek failed;
        // eventually trust the real position instead of showing a permanent fake jump.
        clock.reset(5000, 0);
        eq(5000, clock.update(5000, true, 0, 120000), "seed native sample");
        clock.seekTo(90000, 0);
        eq(92000, clock.update(7000, true, 2000, 120000), "ignore pre-seek movement during grace period");
        eq(11000, clock.update(11000, true, 6000, 120000), "recover when a seek was rejected");

        System.out.println("PASS foreground-video timeline: stuck zero/nonzero, pause/resume, buffering, seeks, stale samples, rejected seeks, reset, unknown duration, end and overflow");
    }
}
