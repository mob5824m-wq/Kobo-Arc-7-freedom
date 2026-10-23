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
 * Stagefright cannot speak TLS 1.3, cannot play DASH manifests, and cannot mux separate
 * video / audio tracks. This server therefore:
 *   1. Runs a background job that downloads YouTube's DASH tracks through the local
 *      TLS 1.3 gateway and merges them with {@link MorpheRemuxer} into one fMP4.
 *   2. Announces the *exact* final `Content-Length` up front (the remuxer computes it
 *      before the first media byte is fetched), so `MediaPlayer` can seek freely.
 *   3. Serves `Range: bytes=a-b` / `bytes=a-` / `bytes=-n` with `206 Partial Content`
 *      while the file is still downloading, blocking the reader until the requested
 *      bytes exist. Playback therefore starts within a second or two.
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

    private MorpheStreamServer() {}

    /** @param dir directory used for partial downloads (`Context.getCacheDir()`). */
    public static void setCacheDir(File dir) {
        cacheDir = dir;
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
                    raf = new RandomAccessFile(file, "rw");
                    raf.setLength(0L);
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
                }
            }
        }, "MorpheMediaJob");
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + p + "/play/" + id;
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
        volatile long total;
        volatile boolean done;
        volatile boolean failed;
        volatile boolean cancelled;
        volatile String stage = "queued";
        volatile String error;
        volatile long written;

        Job(File file, long total, String mimeType) {
            this.file = file;
            this.total = total;
            this.mimeType = (mimeType == null || mimeType.length() == 0) ? "video/mp4" : mimeType;
        }

        synchronized void publish(long newWritten, long reportedTotal) {
            if (newWritten > written) written = newWritten;
            if (reportedTotal > 0L) total = reportedTotal;
            notifyAll();
        }

        synchronized void setStage(String s) {
            stage = s == null ? "" : s;
            notifyAll();
        }

        synchronized void finish(Throwable e) {
            if (e != null) {
                failed = true;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            done = true;
            if (total <= 0L) {
                try { total = file.length(); } catch (Throwable ignored) {}
            }
            notifyAll();
        }

        synchronized void cancel() {
            cancelled = true;
            done = true;
            notifyAll();
        }

        synchronized long available() {
            try {
                long len = file.length();
                return Math.max(len, written);
            } catch (Throwable t) {
                return written;
            }
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
            if (total > 0L) return total;
            try { return file.length(); } catch (Throwable t) { return 0L; }
        }
    }

    /* ======================================================================
     * HTTP/1.1 handling
     * ====================================================================== */

    private static void serve(Socket client) {
        RandomAccessFile raf = null;
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
            if (job.total <= 0L) {
                // Length unknown: wait for the muxer to finish so Content-Length is exact.
                job.awaitDone(120000L);
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
            while (pos <= end) {
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
