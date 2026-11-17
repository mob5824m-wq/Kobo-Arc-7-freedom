package ca.arc7freedom.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Local `127.0.0.1` HTTP media server that exposes finished, playable MP4 files for
 * Android 4.2's Stagefright `MediaPlayer` (`VideoView`) and `Arc7AudioService`.
 *
 * Stagefright cannot negotiate modern HTTPS or combine separate DASH tracks. Jobs
 * download/remux through this APK's TLS transport and expose a classic MP4/M4A. If
 * the exact OUTPUT length is supplied at registration, readers may stream committed
 * bytes as they arrive. Otherwise readers must wait for successful completion:
 * input-download progress and a partial cache file are not a final Content-Length.
 * Finite fMP4 HLS and classic DASH remux therefore need preparation time/cache space.
 * Range/HEAD requests use the final output length, never the upstream track length.
 *
 * Pure `java.io` / `java.net` — no `android.*` imports so the host JVM suite can test it.
 */
public final class MorpheStreamServer {

    public static final int DEFAULT_PORT = 18978;

    public interface JobBody {
        void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception;
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Job> JOBS = new LinkedHashMap<String, Job>(16, 0.75f, true);
    private static final int MAX_JOBS = 3;
    private static volatile ServerSocket server;
    private static volatile int port = DEFAULT_PORT;
    private static volatile File cacheDir;
    // Package-private so the offline HTTP regression can exercise the deadline quickly.
    static volatile long preparationWaitMs = 120000L;

    private MorpheStreamServer() {}

    /** @param dir directory used for partial downloads (`Context.getCacheDir()`). */
    public static void setCacheDir(File dir) {
        cacheDir = dir;
        MorpheRemuxer.setCacheDir(dir);
    }

    public static synchronized int ensureStarted() {
        if (server != null && !server.isClosed()) return port;
        try {
            try {
                server = new ServerSocket(DEFAULT_PORT, 24, InetAddress.getByName("127.0.0.1"));
                port = DEFAULT_PORT;
            } catch (Throwable busy) {
                server = new ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"));
                port = server.getLocalPort();
            }
            final ServerSocket srv = server;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (srv != null && !srv.isClosed()) {
                        try {
                            final Socket client = srv.accept();
                            Thread w = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    serve(client);
                                }
                            }, "MorpheMediaClient");
                            w.setDaemon(true);
                            w.start();
                        } catch (Throwable t2) {
                            break;
                        }
                    }
                }
            }, "MorpheMediaServer");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            return 0;
        }
        return port;
    }

    public static int port() {
        return ensureStarted();
    }

    /**
     * Starts a background job and returns the local URL to hand to `MediaPlayer`.
     *
     * @param knownTotal exact final size when known, otherwise `-1`
     */
    public static String register(int portOverride, JobBody body, long knownTotal, String mimeType) {
        int p = portOverride > 0 ? portOverride : ensureStarted();
        if (p <= 0) return "";
        final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        File dir = cacheDir;
        if (dir == null) {
            dir = new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "arc7morphe");
        }
        if (!dir.exists()) dir.mkdirs();
        final File file = new File(dir, "morphe_" + id + ".mp4");
        final Job job = new Job(file, knownTotal, mimeType);
        synchronized (LOCK) {
            JOBS.put(id, job);
            trimLocked();
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                RandomAccessFile raf = null;
                try {
                    if (job.cancelled) return;
                    raf = new RandomAccessFile(file, "rw");
                    raf.setLength(0L);
                    if (job.cancelled) return;
                    body.run(raf, new MorpheRemuxer.Progress() {
                        @Override
                        public void onBytes(long absoluteOffset, long totalBytes) {
                            job.publish(absoluteOffset, totalBytes);
                        }

                        @Override
                        public void onStage(String stage) {
                            job.setStage(stage);
                        }
                    });
                    job.finish(null);
                } catch (Throwable e) {
                    job.finish(e);
                } finally {
                    if (raf != null) {
                        try { raf.close(); } catch (Throwable ignored) {}
                    }
                    // Cancellation can race worker startup, before the file even exists.
                    if (job.cancelled) try { file.delete(); } catch (Throwable ignored) {}
                }
            }
        }, "MorpheMediaJob");
        job.worker = t;
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + p + "/play/" + id;
    }

    private static Job jobFor(String url) {
        if (url == null) return null;
        int index = url.lastIndexOf("/play/");
        synchronized (LOCK) { return index < 0 ? null : JOBS.get(url.substring(index + 6)); }
    }
    public static String getPreparationStatus(String url) {
        Job job = jobFor(url);
        return job == null || job.done ? "" : job.stage;
    }
    public static String getFailure(String url) {
        Job job = jobFor(url);
        return job != null && job.failed && job.error != null ? job.error : "";
    }

    public static String urlFor(String playUrl) {
        return playUrl;
    }

    public static void cancel(String playUrl) {
        if (playUrl == null) return;
        int idx = playUrl.lastIndexOf("/play/");
        if (idx < 0) return;
        String id = playUrl.substring(idx + 6);
        Job j;
        synchronized (LOCK) {
            j = JOBS.remove(id);
        }
        if (j != null) {
            j.cancel();
            try { j.file.delete(); } catch (Throwable ignored) {}
        }
    }

    public static void cancelAll() {
        List<Job> all;
        synchronized (LOCK) {
            all = new ArrayList<Job>(JOBS.values());
            JOBS.clear();
        }
        for (Job j : all) {
            j.cancel();
            try { j.file.delete(); } catch (Throwable ignored) {}
        }
    }

    private static void trimLocked() {
        while (JOBS.size() > MAX_JOBS) {
            Iterator<Map.Entry<String, Job>> it = JOBS.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<String, Job> eldest = it.next();
            it.remove();
            eldest.getValue().cancel();
            try { eldest.getValue().file.delete(); } catch (Throwable ignored) {}
        }
    }

    /* ======================================================================
     * Job state
     * ====================================================================== */

    private static final class Job {
        final File file;
        final String mimeType;
        final boolean needsCompletedFile;
        final List<Socket> clients = new ArrayList<Socket>();
        volatile long total;
        volatile boolean done;
        volatile boolean failed;
        volatile boolean cancelled;
        volatile String stage = "queued";
        volatile String error;
        volatile Thread worker;

        Job(File file, long total, String mimeType) {
            this.file = file;
            this.total = total;
            this.needsCompletedFile = total <= 0L;
            this.mimeType = (mimeType == null || mimeType.length() == 0) ? "video/mp4" : mimeType;
        }

        synchronized void publish(long newWritten, long reportedTotal) {
            if (cancelled) throw new IllegalStateException("Media preparation cancelled");
            // Unknown-output-length jobs can report INPUT/spool progress here. Do not
            // promote that to a promise to Stagefright or count unwritten output bytes.
            if (!needsCompletedFile && reportedTotal > 0L && reportedTotal != total)
                throw new IllegalStateException("Media output length changed during streaming");
            notifyAll();
        }

        synchronized void setStage(String s) {
            if (cancelled) throw new IllegalStateException("Media preparation cancelled");
            stage = s == null ? "" : s;
            notifyAll();
        }

        synchronized void finish(Throwable e) {
            if (cancelled) { notifyAll(); return; }
            long length = file.length();
            if (e == null && !needsCompletedFile && length != total)
                e = new IOException("Incomplete media output: " + length + " of " + total + " bytes");
            if (e != null) {
                failed = true;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            } else {
                total = length;
            }
            done = true;
            notifyAll();
        }

        synchronized boolean attach(Socket client) {
            if (cancelled) return false;
            clients.add(client);
            return true;
        }

        synchronized void detach(Socket client) { clients.remove(client); }

        void cancel() {
            List<Socket> toClose;
            Thread toInterrupt;
            synchronized (this) {
                cancelled = true;
                done = true;
                toInterrupt = worker;
                toClose = new ArrayList<Socket>(clients);
                notifyAll();
            }
            if (toInterrupt != null) toInterrupt.interrupt();
            for (Socket client : toClose) try { client.close(); } catch (IOException ignored) {}
        }

        synchronized long available() {
            return file.length();
        }

        synchronized boolean awaitMore(long pos, long timeoutMs) {
            if (done) return false;
            try {
                wait(timeoutMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return !done || available() > pos;
        }

        synchronized boolean awaitDone(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!done) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0L) return done;
                try {
                    wait(Math.min(remain, 250L));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return done;
                }
            }
            return true;
        }

        synchronized long totalOrLength() {
            return total > 0L ? total : 0L;
        }
    }

    /* ======================================================================
     * HTTP/1.1 handling
     * ====================================================================== */

    private static void serve(Socket client) {
        RandomAccessFile raf = null;
        Job attachedJob = null;
        try {
            client.setSoTimeout(60000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String request = readRequest(in);
            if (request == null) return;

            String[] lines = request.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0].toUpperCase(Locale.US);
            String path = first[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if ("/__ping".equals(path)) {
                writeAscii(out, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 12\r\nConnection: close\r\n\r\nMORPHE_READY");
                return;
            }
            if (!path.startsWith("/play/")) {
                writeAscii(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nConnection: close\r\n\r\nNot Found");
                return;
            }
            String id = path.substring(6);
            Job job;
            synchronized (LOCK) {
                job = JOBS.get(id);
            }
            if (job == null) {
                writeAscii(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 11\r\nConnection: close\r\n\r\nNo Such Job");
                return;
            }
            if (!job.attach(client)) return;
            attachedJob = job;
            if (job.needsCompletedFile && !job.awaitDone(preparationWaitMs)) {
                // Never fall through to the size of an unfinished file on timeout.
                writeAscii(out, "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            if (job.failed || job.cancelled) {
                writeAscii(out, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long total = job.totalOrLength();
            if (total <= 0L) {
                writeAscii(out, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 13\r\nConnection: close\r\n\r\nNot Ready Yet");
                return;
            }

            long start = 0L;
            long end = total - 1L;
            boolean ranged = false;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].regionMatches(true, 0, "Range:", 0, 6)) {
                    String spec = lines[i].substring(6).trim();
                    if (spec.startsWith("bytes=")) spec = spec.substring(6);
                    int comma = spec.indexOf(',');
                    if (comma >= 0) spec = spec.substring(0, comma);
                    int dash = spec.indexOf('-');
                    if (dash >= 0) {
                        String a = spec.substring(0, dash).trim();
                        String b = spec.substring(dash + 1).trim();
                        try {
                            if (a.length() == 0 && b.length() > 0) {
                                long n = Long.parseLong(b);
                                start = Math.max(0L, total - n);
                                end = total - 1L;
                            } else if (a.length() > 0) {
                                start = Long.parseLong(a);
                                end = b.length() > 0 ? Long.parseLong(b) : total - 1L;
                            }
                            ranged = true;
                        } catch (NumberFormatException ignored) {
                            ranged = false;
                        }
                    }
                    break;
                }
            }
            if (start < 0L) start = 0L;
            if (end >= total) end = total - 1L;
            if (start > end || start >= total) {
                writeAscii(out, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"
                        + total + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long length = end - start + 1L;

            StringBuilder head = new StringBuilder();
            head.append(ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(job.mimeType).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            if (ranged) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            writeAscii(out, head.toString());
            if ("HEAD".equals(method)) return;

            raf = new RandomAccessFile(job.file, "r");
            byte[] buf = new byte[65536];
            long pos = start;
            while (pos <= end && !job.cancelled && !job.failed) {
                long avail = job.available();
                if (avail <= pos) {
                    if (job.done) break;
                    job.awaitMore(pos, 500L);
                    continue;
                }
                int want = (int) Math.min((long) buf.length, Math.min(end - pos + 1L, avail - pos));
                raf.seek(pos);
                int n = raf.read(buf, 0, want);
                if (n <= 0) {
                    if (job.done) break;
                    job.awaitMore(pos, 250L);
                    continue;
                }
                out.write(buf, 0, n);
                out.flush();
                pos += n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (attachedJob != null) attachedJob.detach(client);
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
            try { client.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readRequest(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        int tail = 0;
        while (sb.length() < 8192 && (b = in.read()) != -1) {
            sb.append((char) b);
            tail = ((tail << 8) | (b & 0xFF));
            if (tail == 0x0D0A0D0A) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        out.write(text.getBytes("ISO-8859-1"));
        out.flush();
    }
}
