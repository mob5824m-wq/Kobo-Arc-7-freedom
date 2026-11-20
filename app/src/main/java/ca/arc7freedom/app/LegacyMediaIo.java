package ca.arc7freedom.app;

import ca.arc7freedom.support.DebugLog;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/** Bounded retries for finite, idempotent media GETs; never retries a decoder or player operation. */
public final class LegacyMediaIo {
    public static final int MAX_ATTEMPTS = 3;

    public interface Attempt<T> { T run() throws Exception; }

    /** Only gateway failures, not provider denials/rate limits, are immediately retryable. */
    public static final class GatewayException extends IOException {
        GatewayException(int status) { super("Media gateway HTTP " + status); }
    }

    private LegacyMediaIo() {}

    public static void requireSuccess(int status) throws IOException {
        if (status == 200 || status == 206) return;
        if (status == 502 || status == 504) throw new GatewayException(status);
        throw new IOException("Media HTTP " + status);
    }

    public static boolean retryable(IOException error) {
        return error instanceof EOFException || error instanceof SocketException
                || error instanceof SocketTimeoutException || error instanceof GatewayException
                // Android 4's HttpURLConnection throws plain IOException for this case.
                || (error.getMessage() != null && (error.getMessage().equals("unexpected end of stream")
                    || error.getMessage().equals("Premature EOF")
                    || error.getMessage().startsWith("unexpected end of stream on ")));
    }

    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Media request cancelled");
    }

    /** Each attempt must close its connection and discard its partial buffer before throwing. */
    public static <T> T read(Attempt<T> attempt) throws Exception {
        for (int number = 1; ; number++) {
            checkCancelled();
            try {
                T result = attempt.run();
                checkCancelled();
                return result;
            } catch (IOException failure) {
                checkCancelled();
                if (number >= MAX_ATTEMPTS || !retryable(failure)) throw failure;
                DebugLog.number("media.io_retry", number);
                DebugLog.error("media.io_retry_cause", failure);
                try { Thread.sleep(number == 1 ? 150L : 350L); }
                catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Media retry cancelled");
                }
            }
        }
    }
}
