package ca.arc7freedom.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Loopback HLS relay. Every upstream request (playlists, segments and AES-128 keys) is
 * made by this APK's own TLS transport. Never hand a modern HTTPS URI to Stagefright.
 * No fixed shared port, global proxy, service in the suite or platform HTTPS is used.
 */
public final class MorpheHlsRelay {
    private static ServerSocket server;
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<String, Session>();
    private static final Semaphore CLIENTS = new Semaphore(4);
    private MorpheHlsRelay() {}

    private static final class Resource {
        final String url;
        final MorpheHls.Playlist playlist;
        final String fixed;
        Resource(String url, MorpheHls.Playlist playlist, String fixed) {
            this.url = url; this.playlist = playlist; this.fixed = fixed;
        }
    }

    private static final class Session implements MorpheHls.UriMapper {
        final String token = UUID.randomUUID().toString().replace("-", "");
        final MorpheHls.Transport transport;
        final Map<String, Resource> resources = new LinkedHashMap<String, Resource>();
        final Map<String, String> byUrl = new LinkedHashMap<String, String>();
        final Map<Thread, Socket> clients = new LinkedHashMap<Thread, Socket>();
        final Map<Thread, MorpheHls.Response> responses = new LinkedHashMap<Thread, MorpheHls.Response>();
        boolean cancelled;
        Session(final MorpheHls.Transport upstream) {
            this.transport = new MorpheHls.Transport() {
                @Override public MorpheHls.Response open(String url, String range) throws Exception {
                    checkActive();
                    final Thread owner = Thread.currentThread();
                    final MorpheHls.Response response = upstream.open(url, range);
                    boolean reject;
                    synchronized (Session.this) {
                        reject = cancelled || owner.isInterrupted();
                        if (!reject) responses.put(owner, response);
                    }
                    if (reject) {
                        response.close();
                        throw new IOException("HLS session cancelled");
                    }
                    return new MorpheHls.Response(response.status, response.mime, response.length,
                            response.contentRange, response.body) {
                        @Override public void close() throws IOException {
                            synchronized (Session.this) { responses.remove(owner); }
                            response.close();
                        }
                    };
                }
            };
        }
        synchronized void checkActive() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("HLS session cancelled");
        }
        synchronized void attach(Socket client) throws IOException {
            checkActive();
            clients.put(Thread.currentThread(), client);
        }
        synchronized void detach() { clients.remove(Thread.currentThread()); }
        void cancel() {
            List<Socket> sockets;
            List<Thread> workers;
            final List<MorpheHls.Response> openResponses;
            synchronized (this) {
                cancelled = true;
                sockets = new ArrayList<Socket>(clients.values());
                workers = new ArrayList<Thread>(clients.keySet());
                openResponses = new ArrayList<MorpheHls.Response>(responses.values());
                responses.clear();
            }
            for (Thread worker : workers) worker.interrupt();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
            // Some old ROMs block close()/disconnect() while a read is pending. Do not
            // freeze the activity's UI thread; close upstreams on a bounded-I/O worker.
            if (!openResponses.isEmpty()) {
                Thread closer = new Thread(new Runnable() {
                    @Override public void run() {
                        for (MorpheHls.Response response : openResponses)
                            try { response.close(); } catch (IOException ignored) {}
                    }
                }, "Morphe-HLS-Cancel");
                closer.setDaemon(true); closer.start();
            }
        }
        synchronized String add(Resource resource) throws IOException {
            checkActive();
            String key = resource.url + (resource.playlist != null ? ":playlist" : ":data");
            String id = byUrl.get(key);
            if (id == null) {
                if (resources.size() >= 20010) throw new IOException("HLS session resource limit");
                id = Integer.toString(resources.size());
                resources.put(id, resource); byUrl.put(key, id);
            }
            return "http://127.0.0.1:" + server.getLocalPort() + "/hls/" + token + "/" + id
                    + (resource.playlist != null || resource.fixed != null ? ".m3u8" : "");
        }
        synchronized Resource get(String id) { return resources.get(id); }
        @Override public String local(String url, boolean playlist) throws Exception {
            return add(new Resource(url, playlist ? MorpheHls.parseMedia(url, MorpheHls.text(transport, url)) : null, null));
        }
    }

    public static synchronized String register(MorpheHls.Selection selection, MorpheHls.Transport transport) throws Exception {
        if (selection.fragmented()) throw new IOException("fMP4 must be remuxed for Android 4.2, not relayed");
        ensureStarted();
        Session session = new Session(transport);
        String primary = session.add(new Resource(selection.primary().url, selection.primary(), null));
        if (selection.video != null && selection.audio != null) {
            String audio = session.add(new Resource(selection.audio.url, selection.audio, null));
            String master = "#EXTM3U\n#EXT-X-VERSION:4\n"
                    + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"" + audio + "\"\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=" + Math.max(1, selection.bandwidth)
                    + ",CODECS=\"" + selection.codecs + "\",AUDIO=\"audio\"\n" + primary + "\n";
            primary = session.add(new Resource("selected-master", null, master));
        }
        SESSIONS.put(session.token, session);
        while (SESSIONS.size() > 4) SESSIONS.remove(SESSIONS.keySet().iterator().next()).cancel();
        return primary;
    }

    public static synchronized void cancel(String url) {
        if (url == null) return;
        int start = url.indexOf("/hls/");
        int end = start >= 0 ? url.indexOf('/', start + 5) : -1;
        if (end > start) {
            Session session = SESSIONS.remove(url.substring(start + 5, end));
            if (session != null) session.cancel();
        }
    }

    private static void ensureStarted() throws IOException {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(new Runnable() {
            @Override public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket client = server.accept();
                        if (!CLIENTS.tryAcquire()) { client.close(); continue; }
                        Thread worker = new Thread(new Runnable() {
                            @Override public void run() {
                                try { serve(client); } finally { CLIENTS.release(); }
                            }
                        }, "Morphe-HLS-Client");
                        worker.setDaemon(true); worker.start();
                    } catch (IOException closed) { break; }
                }
            }
        }, "Morphe-HLS-Loopback");
        accept.setDaemon(true); accept.start();
    }

    private static void serve(Socket client) {
        MorpheHls.Response upstream = null;
        Session attachedSession = null;
        boolean headersSent = false;
        try {
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int b, tail = 0;
            while (header.size() < 8192 && (b = in.read()) >= 0) {
                header.write(b); tail = (tail << 8) | b;
                if (tail == 0x0d0a0d0a) break;
            }
            String[] lines = new String(header.toByteArray(), "ISO-8859-1").split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2 || !("GET".equals(request[0]) || "HEAD".equals(request[0]))) {
                reply(out, 405, "text/plain", "GET or HEAD required".getBytes("UTF-8"), false); return;
            }
            boolean head = "HEAD".equals(request[0]);
            String[] path = request[1].split("/");
            Session session;
            synchronized (MorpheHlsRelay.class) { session = path.length == 4 && "hls".equals(path[1]) ? SESSIONS.get(path[2]) : null; }
            Resource resource = session != null ? session.get(path[3].replace(".m3u8", "")) : null;
            if (resource == null) { reply(out, 404, "text/plain", "Expired HLS session".getBytes("UTF-8"), head); return; }
            session.attach(client);
            attachedSession = session;
            if (resource.fixed != null || resource.playlist != null) {
                String body = resource.fixed;
                if (body == null) {
                    MorpheHls.Playlist playlist = resource.playlist.live
                            ? MorpheHls.parseMedia(resource.url, MorpheHls.text(session.transport, resource.url)) : resource.playlist;
                    body = MorpheHls.rewrite(playlist, session);
                }
                session.checkActive();
                reply(out, 200, "application/vnd.apple.mpegurl", body.getBytes("UTF-8"), head); return;
            }
            String range = null;
            for (String line : lines) if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                range = line.substring(6).trim();
                if (!range.matches("bytes=[0-9]*-[0-9]*")) {
                    reply(out, 416, "text/plain", "Invalid range".getBytes("UTF-8"), head); return;
                }
            }
            upstream = session.transport.open(resource.url, range);
            if (upstream.status != 200 && upstream.status != 206) throw new IOException("HLS upstream HTTP " + upstream.status);
            StringBuilder response = new StringBuilder("HTTP/1.1 " + upstream.status + (upstream.status == 206 ? " Partial Content\r\n" : " OK\r\n"));
            response.append("Content-Type: ").append(safe(upstream.mime, "video/mp2t")).append("\r\n");
            if (upstream.length >= 0) response.append("Content-Length: ").append(upstream.length).append("\r\n");
            if (upstream.status == 206 && upstream.contentRange != null)
                response.append("Content-Range: ").append(safe(upstream.contentRange, "")).append("\r\n");
            response.append("Connection: close\r\nCache-Control: no-store\r\n\r\n");
            out.write(response.toString().getBytes("ISO-8859-1")); headersSent = true;
            if (!head && upstream.body != null) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = upstream.body.read(buffer)) >= 0) {
                    session.checkActive();
                    out.write(buffer, 0, n);
                }
            }
            out.flush();
        } catch (Exception error) {
            if (!headersSent) try {
                reply(client.getOutputStream(), 502, "text/plain", ("HLS transport error: " + error.getMessage()).getBytes("UTF-8"), false);
            } catch (Exception ignored) {}
        } finally {
            if (attachedSession != null) attachedSession.detach();
            try { if (upstream != null) upstream.close(); } catch (IOException ignored) {}
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static String safe(String header, String fallback) {
        return header == null || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0 ? fallback : header;
    }
    private static void reply(OutputStream out, int code, String mime, byte[] data, boolean head) throws IOException {
        String header = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + data.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes("ISO-8859-1")); if (!head) out.write(data); out.flush();
    }
}
