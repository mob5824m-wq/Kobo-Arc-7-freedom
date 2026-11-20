package ca.arc7freedom.app;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic I/O/transport faults. Does not claim live YouTube or Kobo audio testing. */
public final class MediaIoHarness {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static final class Hls implements MorpheHls.Transport {
        int hits, closes;
        String fault;
        Hls(String fault) { this.fault = fault; }
        public MorpheHls.Response open(String url, String range) throws Exception {
            int hit = ++hits;
            if (fault.equals("headers") && hit == 1) throw new IOException("unexpected end of stream");
            int status = fault.equals("denied") ? 403 : fault.equals("limited") ? 429 : fault.equals("unavailable") ? 503 : 206;
            byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            if (fault.equals("truncated") || (fault.equals("once") && hit == 1)) data = new byte[]{99, 98};
            final byte[] body = data;
            String contentRange = fault.startsWith("wrong") ? "bytes 2-9/10" : "bytes 0-7/8";
            // With no Content-Length, the validated byte range must still catch truncation.
            return new MorpheHls.Response(status, "video/mp4", -1, contentRange, new ByteArrayInputStream(body)) {
                public void close() throws IOException {
                    closes++; super.close();
                    if (fault.endsWith("close")) throw new IOException("unexpected end of stream");
                }
            };
        }
    }
    private static void rejected(Hls source, int attempts) throws Exception {
        try { MorpheHls.read(source, new MorpheHls.Part("https://fixture.invalid/segment", "bytes=0-7"), 32); }
        catch (IOException expected) {
            check(source.hits == attempts && source.closes == attempts, "Wrong retry/close count for " + source.fault + ": " + source.hits);
            return;
        }
        throw new AssertionError("Accepted " + source.fault);
    }
    public static void main(String[] args) throws Exception {
        byte[] expected = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        for (String fault : new String[]{"once", "headers"}) {
            Hls source = new Hls(fault);
            check(Arrays.equals(expected, MorpheHls.read(source,
                    new MorpheHls.Part("https://fixture.invalid/segment", "bytes=0-7"), 32)), "Retry reused corrupt partial bytes");
            check(source.hits == 2 && source.closes == (fault.equals("headers") ? 1 : 2), "Retry did not reopen/close the transport");
        }
        rejected(new Hls("truncated"), 3);
        for (String fault : new String[]{"wrong", "wrong-close", "denied", "limited", "unavailable"}) rejected(new Hls(fault), 1);
        Hls closeFailure = new Hls("good-close");
        check(Arrays.equals(expected, MorpheHls.read(closeFailure,
                new MorpheHls.Part("https://fixture.invalid/segment", "bytes=0-7"), 32))
                && closeFailure.hits == 1 && closeFailure.closes == 1, "Close-only failure replayed valid media");
        final int[] hits = new int[1];
        check(LegacyMediaIo.read(new LegacyMediaIo.Attempt<Integer>() {
            public Integer run() throws Exception { if (++hits[0] < 3) throw new SocketTimeoutException(); return 7; }
        }) == 7 && hits[0] == 3, "Read timeouts did not get the bounded budget");
        final CountDownLatch entered = new CountDownLatch(1);
        final int[] cancelledHits = new int[1];
        Thread cancelled = new Thread(new Runnable() { public void run() {
            try {
                LegacyMediaIo.read(new LegacyMediaIo.Attempt<byte[]>() {
                    public byte[] run() throws Exception { cancelledHits[0]++; entered.countDown(); throw new EOFException(); }
                });
                throw new AssertionError("Cancelled retry returned data");
            } catch (InterruptedIOException expectedCancellation) { check(Thread.currentThread().isInterrupted(), "Lost interrupt flag"); }
            catch (Exception wrong) { throw new AssertionError(wrong); }
        }});
        final Throwable[] threadError = new Throwable[1];
        cancelled.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable error) { threadError[0] = error; }
        });
        cancelled.start(); check(entered.await(2, TimeUnit.SECONDS), "Retry never entered");
        cancelled.interrupt(); cancelled.join(2000);
        check(!cancelled.isAlive() && cancelledHits[0] == 1 && threadError[0] == null, "Cancellation retried or failed: " + threadError[0]);
        check(!LegacyMediaIo.retryable(new InterruptedIOException("cancelled"))
                && !LegacyMediaIo.retryable(new IOException("Incorrect media range response")), "Permanent/cancelled input classified transient");
        check(!LegacyMediaPolicy.audioContainerFailure(1, -1004) && !LegacyMediaPolicy.error(1, -1004).contains("station"), "YouTube I/O mislabeled as a station/container fault");
        System.out.println("PASS finite media I/O: fresh whole-resource retries on EOF/Android unexpected-end/timeout, exact HLS range/size, 3-attempt cap, no corrupt prefix reuse, no denial/rate-limit/malformed retries, prompt cancellation and sanitized logging");
    }
}
