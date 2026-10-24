package ca.arc7freedom.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared Morphe Streaming, InnerTube (`WEB` & `WEB_REMIX`), Multi-Client Stream Spoofer,
 * Local `206 Partial Content` Range TLS 1.3 Media Proxy, SponsorBlock, ReturnYouTubeDislike,
 * Synced LRC Lyrics & Offline MP4/M4A Download Engine for Kobo Arc 7 (`Android 4.2.2`, `v2.4.0`).
 */
public final class MorpheEngine {

    public static final String PREFS = "morphe_settings_v250";
    public static final int STREAM_PROXY_PORT = 18977;

    private static final int QUICK_DIRECT_CLIENT_ATTEMPTS = 3;
    private static final int QUICK_VISITOR_TIMEOUT_MS = 1600;
    private static final int QUICK_PLAYER_TIMEOUT_MS = 6500;
    private static final int QUICK_CLOUD_TIMEOUT_MS = 3000;
    private static final int QUICK_CLOUD_ENDPOINT_ATTEMPTS = 4;
    private static final long QUICK_DIRECT_BUDGET_MS = 21000L;

    // User-selectable Morphe Spoof Stream Client modes (`0 = Auto Best Stream`)
    public static final String[] SPOOF_CLIENT_LABELS = new String[]{
            "Auto (Android VR Quest 3 -> visionOS 1.04 -> TV Simply -> iOS)",
            "visionOS 1.04 (Apple Vision Pro — 2026 Anonymous, Best H.264)",
            "TV Simply 1.1 (TVHTML5_SIMPLY — Zero PoToken)",
            "Android VR 1.64 (Pico 4 — DASH H.264)",
            "Android Music 7.12 (YouTube Music Native M4A)",
            "Android Creator 26.10 (Studio — 720p cap)",
            "Android VR 1.61 (Oculus Quest 3 — Progressive + DASH H.264/AAC)",
            "iOS 20.10 (iPhone 15 Pro — Progressive + DASH H.264/AAC)"
    };

    /**
     * 2026 InnerTube client table.
     *
     * Columns: key | clientId | clientName | clientVersion | userAgent | deviceMake |
     *          deviceModel | osName | osVersion | androidSdk | buildId | clientScreen |
     *          platform | host
     */
    private static final String[][] CLIENT_SPECS = new String[][]{
            {
                    "VISIONOS_1_04", "101", "VISIONOS", "1.04",
                    "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)",
                    "Apple", "RealityDevice17,1", "visionOS", "26.6.0.23O770", "", "",
                    "WATCH", "MOBILE", "https://youtubei.googleapis.com"
            },
            {
                    "TV_SIMPLY_1_1", "75", "TVHTML5_SIMPLY", "1.1",
                    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
                    "", "", "", "", "", "",
                    "", "", "https://youtubei.googleapis.com"
            },
            {
                    "ANDROID_VR_1_64", "28", "ANDROID_VR", "1.64.34",
                    "com.google.android.apps.youtube.vr.pico/1.64.34 (Linux; U; Android 10; Build/5.13.7) gzip",
                    "Pico", "A8110", "Android", "10", "29", "5.13.7",
                    "", "", "https://www.youtube.com"
            },
            {
                    "ANDROID_MUSIC_7_12", "21", "ANDROID_MUSIC", "7.12.52",
                    "com.google.android.apps.youtube.music/7.12.52 (Linux; U; Android 14) gzip",
                    "Google", "Pixel 8", "Android", "14", "34", "AP2A.240905.003",
                    "", "", "https://music.youtube.com"
            },
            {
                    "ANDROID_CREATOR_26_10", "14", "ANDROID_CREATOR", "26.10.000",
                    "com.google.android.apps.youtube.creator/26.10.000 (Linux; U; Android 16; Pixel 10 Pro XL Build/BD3A.251005.003.W3) gzip",
                    "Google", "Pixel 10 Pro XL", "Android", "16", "36", "BD3A.251005.003.W3",
                    "", "", "https://www.youtube.com"
            },
            {
                    "ANDROID_VR_QUEST3", "28", "ANDROID_VR", "1.61.48",
                    "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                    "Oculus", "Quest 3", "Android", "12L", "32", "SQ3A.220605.009.A1",
                    "", "", "https://www.youtube.com"
            },
            {
                    "IOS_20_10", "5", "IOS", "20.10.4",
                    "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                    "Apple", "iPhone16,2", "iPhone", "18.3.2.22D82", "", "",
                    "", "MOBILE", "https://youtubei.googleapis.com"
            }
    };

    private static final String[] PIPED_INSTANCES = new String[]{
            "https://api.piped.private.coffee",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de"
    };

    private static final String[] INVIDIOUS_INSTANCES = new String[]{
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be"
    };

    private static final String WEB_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

    private static volatile String cachedVisitorData = "";
    private static volatile long visitorFetchedMs = 0L;

    public interface ResolutionProgressListener {
        void onProgress(String message);
    }

    public static final class MediaItem {
        public String videoId = "";
        public String browseId = "";
        public String playlistId = "";
        public String title = "";
        public String author = "";
        public String channelId = "";
        public String album = "";
        public String durationText = "";
        public long durationSec = 0L;
        public String viewCountText = "";
        public String publishedText = "";
        public String thumbnailUrl = "";
        public boolean isLive = false;
        public boolean isMusicTrack = false;
        public String localFilePath = "";

        public String bestThumb() {
            if (thumbnailUrl != null && thumbnailUrl.startsWith("http")) return thumbnailUrl;
            if (videoId != null && videoId.length() >= 8) {
                return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            }
            return "";
        }
    }

    public static final class StreamVariant {
        public int itag;
        public String label = "";
        public String mimeType = "";
        public String codec = "";
        public String directHttpsUrl = "";
        public String userAgent = WEB_UA;
        public int height = 0;
        public int bitrate = 0;
        public boolean hasVideo = false;
        public boolean hasAudio = false;
        public boolean isHls = false;
        public long contentLength = -1L;
        /** Last byte (inclusive) of the DASH `initRange`; `-1` when this is a progressive file. */
        public long initEnd = -1L;
        /** Last byte (inclusive) of the DASH `indexRange`; `-1` when unknown. */
        public long indexEnd = -1L;
        public long approxDurationMs = 0L;

        public boolean isDashTrack() {
            return initEnd >= 0L && directHttpsUrl.length() > 0;
        }
    }

    public static final class SkipSegment {
        public float startSec;
        public float endSec;
        public String category = "sponsor";
    }

    public static final class CommentItem {
        public String author = "";
        public String text = "";
        public String likes = "";
        public String timeAgo = "";
    }

    public static final class LyricLine {
        public long timeMs = -1L;
        public String text = "";
    }

    public static final class ResolvedPlayback {
        public String videoId = "";
        public String title = "";
        public String author = "";
        public String channelId = "";
        public String description = "";
        public String viewCount = "";
        public long durationSec = 0L;
        public String thumbnailUrl = "";
        public String spoofClientUsed = "";
        public String localVideoProxyUrl = "";
        public String localAudioProxyUrl = "";
        /** True when `localVideoProxyUrl` is a locally muxed H.264+AAC MP4 (DASH remux). */
        public boolean muxedLocally = false;
        public String muxNote = "";
        public final List<StreamVariant> progressiveVideos = new ArrayList<StreamVariant>();
        public final List<StreamVariant> videoOnlyStreams = new ArrayList<StreamVariant>();
        public final List<StreamVariant> audioStreams = new ArrayList<StreamVariant>();
        public final List<MediaItem> relatedItems = new ArrayList<MediaItem>();
        public final List<SkipSegment> skipSegments = new ArrayList<SkipSegment>();
        public long likesCount = -1L;
        public long dislikesCount = -1L;

        public boolean hasAnyStream() {
            return !progressiveVideos.isEmpty() || !videoOnlyStreams.isEmpty() || !audioStreams.isEmpty();
        }

        public int bestHeight() {
            int h = 0;
            for (int i = 0; i < progressiveVideos.size(); i++) h = Math.max(h, progressiveVideos.get(i).height);
            for (int i = 0; i < videoOnlyStreams.size(); i++) h = Math.max(h, videoOnlyStreams.get(i).height);
            return h;
        }
    }

    private static final class StreamSlot {
        final String httpsUrl;
        final String userAgent;
        final String mimeType;

        StreamSlot(String httpsUrl, String userAgent, String mimeType) {
            this.httpsUrl = httpsUrl;
            this.userAgent = userAgent;
            this.mimeType = mimeType;
        }
    }

    private static final Map<String, StreamSlot> STREAM_SLOTS =
            Collections.synchronizedMap(new LinkedHashMap<String, StreamSlot>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StreamSlot> eldest) {
                    return size() > 96;
                }
            });

    private static volatile ServerSocket proxyServer;
    private static volatile int activeProxyPort = STREAM_PROXY_PORT;
    private static volatile int customGatewayPortOverride = -1;

    private MorpheEngine() {}

    public static void setGatewayPortForTest(int port) {
        customGatewayPortOverride = port;
    }

    private static int gatewayPort() {
        return customGatewayPortOverride > 0 ? customGatewayPortOverride : NetworkCertManager.DEFAULT_PROXY_PORT;
    }

    /* ==================== 1. Settings Helpers (`Morphe Patches`) ==================== */

    public static boolean isSponsorBlockEnabled(Context ctx) {
        return ctx == null || ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sponsorblock", true);
    }

    public static void setSponsorBlockEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("sponsorblock", on).commit();
    }

    public static boolean isReturnDislikeEnabled(Context ctx) {
        return ctx == null || ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ryd", true);
    }

    public static void setReturnDislikeEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ryd", on).commit();
    }

    public static boolean isBackgroundPlayEnabled(Context ctx) {
        return ctx == null || ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("bg_play", true);
    }

    public static void setBackgroundPlayEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("bg_play", on).commit();
    }

    public static int getPreferredSpoofClient(Context ctx) {
        return ctx == null ? 0 : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("spoof_idx", 0);
    }

    public static void setPreferredSpoofClient(Context ctx, int idx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("spoof_idx", idx).commit();
    }

    /* ==================== 2. Deep-Link Video / Playlist ID Parser ==================== */

    public static String extractVideoIdFromUrlOrQuery(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.matches("^[A-Za-z0-9_-]{11}$")) return s;
        try {
            Uri u = Uri.parse(s);
            String host = u.getHost() != null ? u.getHost().toLowerCase(Locale.US) : "";
            if (host.contains("youtu.be")) {
                List<String> segs = u.getPathSegments();
                if (segs != null && !segs.isEmpty() && segs.get(0).length() == 11) {
                    return segs.get(0);
                }
            }
            if (host.contains("youtube.com") || host.contains("invidious") || host.contains("piped")) {
                String v = u.getQueryParameter("v");
                if (v != null && v.length() == 11) return v;
                List<String> segs = u.getPathSegments();
                if (segs != null && segs.size() >= 2
                        && ("shorts".equals(segs.get(0)) || "live".equals(segs.get(0))
                        || "embed".equals(segs.get(0)) || "v".equals(segs.get(0)))
                        && segs.get(1).length() == 11) {
                    return segs.get(1);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ==================== 3. Local `206 Partial Content` Stream Proxy ==================== */

    public static synchronized int ensureStreamProxyStarted(final Context ctx) {
        if (proxyServer != null && !proxyServer.isClosed()) {
            return activeProxyPort;
        }
        try {
            try {
                proxyServer = new ServerSocket(STREAM_PROXY_PORT, 32, InetAddress.getByName("127.0.0.1"));
                activeProxyPort = STREAM_PROXY_PORT;
            } catch (Throwable bindBusy) {
                proxyServer = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
                activeProxyPort = proxyServer.getLocalPort();
            }
            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (proxyServer != null && !proxyServer.isClosed()) {
                        try {
                            final Socket client = proxyServer.accept();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    handleMediaClientSocket(ctx, client);
                                }
                            }).start();
                        } catch (Throwable ignored) {
                            break;
                        }
                    }
                }
            }, "MorpheRangeProxy");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (Throwable ignored) {}
        return activeProxyPort;
    }

    public static String registerPlayableStreamSlot(Context ctx, String httpsUrl, String userAgent, String mimeType) {
        int port = ensureStreamProxyStarted(ctx);
        String id = UUID.randomUUID().toString().replace("-", "");
        STREAM_SLOTS.put(id, new StreamSlot(
                httpsUrl,
                (userAgent != null && userAgent.length() > 0) ? userAgent : WEB_UA,
                (mimeType != null && mimeType.length() > 0) ? mimeType : "video/mp4"));
        return "http://127.0.0.1:" + port + "/play/" + id;
    }

    private static void handleMediaClientSocket(Context ctx, Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            ByteArrayOutputStream reqBuf = new ByteArrayOutputStream();
            int b;
            int tail = 0;
            while (reqBuf.size() < 8192 && (b = cin.read()) != -1) {
                reqBuf.write(b);
                tail = ((tail << 8) | (b & 0xFF));
                if (tail == 0x0D0A0D0A) break;
            }
            String reqText = new String(reqBuf.toByteArray(), "ISO-8859-1");
            String[] lines = reqText.split("\r\n");
            if (lines.length == 0) return;

            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0].trim();
            String path = first[1].trim();
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) path = path.substring(0, qIdx);

            String slotId = path.startsWith("/play/") ? path.substring(6).trim() : "";
            StreamSlot slot = STREAM_SLOTS.get(slotId);
            if (slot == null) {
                cout.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes("ISO-8859-1"));
                cout.flush();
                return;
            }

            String rangeHeader = null;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].regionMatches(true, 0, "Range:", 0, 6)) {
                    rangeHeader = lines[i].substring(6).trim();
                    break;
                }
            }
            // Enforce Range header so `googlevideo.com` skips 45 KB/s non-range throttling!
            if (rangeHeader == null || rangeHeader.length() == 0) {
                rangeHeader = "bytes=0-";
            }

            if (ctx != null) {
                Arc7TlsGatewayService.ensureStarted(ctx);
            }

            String upHost;
            int upPort;
            String bridgePath;
            String hostHeader;
            if (slot.httpsUrl.startsWith("https://")) {
                upHost = "127.0.0.1";
                upPort = gatewayPort();
                bridgePath = "/__https/" + slot.httpsUrl.substring(8);
                hostHeader = upHost + ":" + upPort;
            } else {
                URL plainUrl = new URL(slot.httpsUrl);
                upHost = plainUrl.getHost();
                upPort = plainUrl.getPort() > 0 ? plainUrl.getPort() : 80;
                String f = plainUrl.getFile();
                bridgePath = (f == null || f.isEmpty()) ? "/" : f;
                hostHeader = plainUrl.getPort() > 0 ? (upHost + ":" + upPort) : upHost;
            }

            upstream = new Socket(upHost, upPort);
            upstream.setSoTimeout(30000);
            upstream.setTcpNoDelay(true);
            client.setTcpNoDelay(true);

            String wireReq = ("HEAD".equalsIgnoreCase(method) ? "HEAD " : "GET ")
                    + bridgePath + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: " + slot.userAgent + "\r\n"
                    + "Accept: */*\r\n"
                    + "Range: " + rangeHeader + "\r\n"
                    + "Connection: close\r\n\r\n";

            OutputStream uout = upstream.getOutputStream();
            uout.write(wireReq.getBytes("ISO-8859-1"));
            uout.flush();

            InputStream uin = new BufferedInputStream(upstream.getInputStream(), 32768);
            byte[] chunk = new byte[32768];
            int n;
            while ((n = uin.read(chunk)) > 0) {
                cout.write(chunk, 0, n);
            }
            cout.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (upstream != null) upstream.close(); } catch (Throwable ignored) {}
            try { client.close(); } catch (Throwable ignored) {}
        }
    }

    /* ==================== 4. Native C TLS 1.3 HTTP/JSON Transport ==================== */

    public static String requestHttpsString(Context ctx,
                                            String method,
                                            String httpsUrl,
                                            String jsonBody,
                                            Map<String, String> headers,
                                            int timeoutMs) throws Exception {
        byte[] raw = requestHttpsBytes(ctx, method, httpsUrl, jsonBody, headers, timeoutMs, 6 * 1024 * 1024);
        return new String(raw, "UTF-8");
    }


    private static volatile long lastGatewayKickMs = 0L;

    /**
     * Makes sure the persistent `127.0.0.1:8118` TLS 1.3 daemon is actually bound before the
     * first InnerTube / `googlevideo` request. `Arc7TlsGatewayService` starts it on its own
     * watchdog thread, so a cold launch could otherwise race the very first request.
     */
    private static void ensureGatewayWarm(Context ctx) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (now - lastGatewayKickMs < 10000L) return;
        lastGatewayKickMs = now;
        try {
            if (Arc7TlsGatewayService.isGatewayAlive()) return;
            Arc7TlsGatewayService.ensureStarted(ctx);
            new NetworkCertManager(ctx, new RootShell(ctx)).startBrowserGateway();
        } catch (Throwable ignored) {}
    }

    public static byte[] requestHttpsBytes(Context ctx,
                                           String method,
                                           String httpsUrl,
                                           String jsonBody,
                                           Map<String, String> headers,
                                           int timeoutMs,
                                           int maxBytes) throws Exception {
        ensureGatewayWarm(ctx);
        String bridgeUrl = httpsUrl.startsWith("https://")
                ? ("http://127.0.0.1:" + gatewayPort() + "/__https/" + httpsUrl.substring(8))
                : httpsUrl;

        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(bridgeUrl).openConnection(Proxy.NO_PROXY);
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", WEB_UA);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (jsonBody != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                byte[] bodyBytes = jsonBody.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bodyBytes.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();
            }

            int code = conn.getResponseCode();
            in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                throw new Exception("HTTP " + code);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() >= maxBytes) break;
            }
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code + ": " + new String(bos.toByteArray(), "UTF-8"));
            }
            return bos.toByteArray();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static String ensureVisitorData(Context ctx) {
        long now = System.currentTimeMillis();
        if (cachedVisitorData.length() > 0 && (now - visitorFetchedMs) < 1800000L) {
            return cachedVisitorData;
        }
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("User-Agent", WEB_UA);
            String html = requestHttpsString(ctx, "GET", "https://www.youtube.com/?hl=en", null, h, 8000);
            Matcher m = Pattern.compile("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
            if (m.find()) {
                cachedVisitorData = m.group(1);
                visitorFetchedMs = now;
            }
        } catch (Throwable ignored) {}
        return cachedVisitorData;
    }

    /**
     * Fetches a per-client `visitorData` from the official `/youtubei/v1/visitor_id` endpoint.
     *
     * YouTube raises the bot score (and finally rejects the player request) when a single
     * visitor id is shared across clients — the old code always sent the WEB visitor id with
     * the Android VR / visionOS / Music clients, which is exactly why 2026 playback 403'd.
     * Each client now gets (and caches for 30 days) its own visitor id, keyed by
     * `clientName/clientVersion`.
     */
    private static String ensureClientVisitorData(Context ctx, String[] spec) {
        return ensureClientVisitorData(ctx, spec, 9000);
    }

    private static String ensureClientVisitorData(Context ctx, String[] spec, int timeoutMs) {
        String cacheKey = "vd_" + spec[2] + "_" + spec[3].replace('.', '_');
        String cached = "";
        long cachedAt = 0L;
        if (ctx != null) {
            try {
                cached = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(cacheKey, "");
                cachedAt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(cacheKey + "_at", 0L);
            } catch (Throwable ignored) {}
        }
        if (cached != null && cached.length() > 8 && (System.currentTimeMillis() - cachedAt) < 30L * 24L * 3600L * 1000L) {
            return cached;
        }

        String host = spec[13].length() > 0 ? spec[13] : "https://www.youtube.com";
        String endpoint = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false";
        if ("TVHTML5_SIMPLY".equals(spec[2]) || "TVHTML5".equals(spec[2])) {
            endpoint = "https://www.youtube.com/youtubei/v1/guide?prettyPrint=false";
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("context", buildClientContext(spec, null, false));
            Map<String, String> hdrs = clientHeaders(spec, host, null);
            String raw = requestHttpsString(ctx, "POST", endpoint, payload.toString(), hdrs, Math.max(1, timeoutMs));
            JSONObject resp = new JSONObject(raw);
            JSONObject rc = resp.optJSONObject("responseContext");
            String vd = rc != null ? rc.optString("visitorData", "") : "";
            if (vd.length() > 8 && ctx != null) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(cacheKey, vd).putLong(cacheKey + "_at", System.currentTimeMillis()).commit();
            }
            return vd;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static JSONObject buildClientContext(String[] spec, String visitorData, boolean withPlaybackContext) {
        JSONObject context = new JSONObject();
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", spec[2]);
            client.put("clientVersion", spec[3]);
            client.put("hl", "en");
            client.put("gl", "US");
            if (spec[5].length() > 0) client.put("deviceMake", spec[5]);
            if (spec[6].length() > 0) client.put("deviceModel", spec[6]);
            if (spec[7].length() > 0) client.put("osName", spec[7]);
            if (spec[8].length() > 0) client.put("osVersion", spec[8]);
            if (spec[9].length() > 0) {
                try { client.put("androidSdkVersion", Integer.parseInt(spec[9])); } catch (Throwable ignored) {}
            }
            if (spec[10].length() > 0) client.put("osBuildId", spec[10]);
            if (spec[11].length() > 0) client.put("clientScreen", spec[11]);
            if (spec[12].length() > 0) client.put("platform", spec[12]);
            if (visitorData != null && visitorData.length() > 0) client.put("visitorData", visitorData);
            context.put("client", client);

            JSONObject request = new JSONObject();
            request.put("useSsl", true);
            context.put("request", request);

            JSONObject user = new JSONObject();
            user.put("lockedSafetyMode", false);
            context.put("user", user);

            // NOTE: no `playbackContext` here — 2026 clients reject a nested playbackContext
            // inside `context`; it is only valid at the top level of the player request.
            if (withPlaybackContext) {
                JSONObject requestFlags = new JSONObject();
                requestFlags.put("internalExperimentFlags", new org.json.JSONArray());
                requestFlags.put("useSsl", true);
                context.put("request", requestFlags);
            }
        } catch (Throwable ignored) {}
        return context;
    }

    private static Map<String, String> clientHeaders(String[] spec, String host, String visitorData) {
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", spec[4]);
        hdrs.put("X-YouTube-Client-Name", spec[1]);
        hdrs.put("X-YouTube-Client-Version", spec[3]);
        hdrs.put("Origin", host);
        hdrs.put("Accept-Language", "en-US,en;q=0.9");
        hdrs.put("X-Goog-Api-Format-Version", "2");
        if (visitorData != null && visitorData.length() > 0) {
            hdrs.put("X-Goog-Visitor-Id", visitorData);
        }
        return hdrs;
    }

    /**
     * Fetches one inclusive byte range of a `googlevideo.com` DASH track, through the local
     * TLS 1.3 gateway on `127.0.0.1:8118` (Stagefright & `HttpURLConnection` on Android 4.2
     * cannot negotiate the TLS 1.3 / ML-KEM endpoints `googlevideo` now requires).
     */
    public static byte[] fetchRangeThroughGateway(Context ctx,
                                                  String httpsUrl,
                                                  String userAgent,
                                                  long startInclusive,
                                                  long endInclusive) throws Exception {
        if (startInclusive < 0L) startInclusive = 0L;
        if (endInclusive < startInclusive) endInclusive = startInclusive;
        long want = endInclusive - startInclusive + 1L;
        ensureGatewayWarm(ctx);
        int gwPort = gatewayPort();

        String target = httpsUrl;
        if (target.startsWith("https://")) {
            target = "http://127.0.0.1:" + gwPort + "/__https/" + target.substring(8);
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Range", "bytes=" + startInclusive + "-" + endInclusive);
            conn.setRequestProperty("User-Agent",
                    (userAgent != null && userAgent.length() > 0) ? userAgent : WEB_UA);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code + " for range " + startInclusive + "-" + endInclusive);
            }
            in = conn.getInputStream();
            boolean whole = (code == 200);
            long skip = whole ? startInclusive : 0L;
            byte[] out = new byte[(int) Math.min(want, 32L * 1024L * 1024L)];
            long skipped = 0L;
            byte[] sink = new byte[8192];
            while (skipped < skip) {
                int n = in.read(sink, 0, (int) Math.min((long) sink.length, skip - skipped));
                if (n <= 0) break;
                skipped += n;
            }
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n <= 0) break;
                off += n;
            }
            if (off == out.length) return out;
            byte[] shorter = new byte[off];
            System.arraycopy(out, 0, shorter, 0, off);
            if (shorter.length == 0) throw new IOException("Empty range response");
            return shorter;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /* ==================== 5. YouTube & YouTube Music Search / Home Feeds ==================== */

    public static List<MediaItem> searchYouTubeVideos(Context ctx, String query) throws Exception {
        JSONObject payload = new JSONObject();
        JSONObject context = new JSONObject();
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", "2.20260708.00.00");
        client.put("hl", "en");
        client.put("gl", "US");
        String vd = ensureVisitorData(ctx);
        if (vd.length() > 0) client.put("visitorData", vd);
        context.put("client", client);
        payload.put("context", context);
        payload.put("query", query);

        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        hdrs.put("Origin", "https://www.youtube.com");
        hdrs.put("Referer", "https://www.youtube.com/");
        hdrs.put("X-YouTube-Client-Name", "1");
        hdrs.put("X-YouTube-Client-Version", "2.20260708.00.00");
        if (vd.length() > 0) hdrs.put("X-Goog-Visitor-Id", vd);

        try {
            String raw = requestHttpsString(ctx, "POST",
                    "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
                    payload.toString(), hdrs, 14000);
            List<MediaItem> items = parseYouTubeItemsRecursive(new JSONObject(raw), false);
            if (!items.isEmpty()) return items;
        } catch (Throwable ignored) {}

        return searchCloudFallback(ctx, query, false);
    }

    public static List<MediaItem> fetchYouTubeFeedTopic(Context ctx, String categoryQuery) throws Exception {
        if (categoryQuery == null || categoryQuery.trim().isEmpty()) {
            // Try official `FEwhat_to_watch` / `FEtrending` browse first, then rich curated query
            try {
                JSONObject payload = new JSONObject();
                JSONObject context = new JSONObject();
                JSONObject client = new JSONObject();
                client.put("clientName", "WEB");
                client.put("clientVersion", "2.20260708.00.00");
                client.put("hl", "en");
                client.put("gl", "US");
                String vd = ensureVisitorData(ctx);
                if (vd.length() > 0) client.put("visitorData", vd);
                context.put("client", client);
                payload.put("context", context);
                payload.put("browseId", "FEtrending");

                Map<String, String> hdrs = new LinkedHashMap<String, String>();
                hdrs.put("User-Agent", WEB_UA);
                hdrs.put("Origin", "https://www.youtube.com");
                hdrs.put("X-YouTube-Client-Name", "1");
                hdrs.put("X-YouTube-Client-Version", "2.20260708.00.00");
                String raw = requestHttpsString(ctx, "POST",
                        "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                        payload.toString(), hdrs, 12000);
                List<MediaItem> items = parseYouTubeItemsRecursive(new JSONObject(raw), false);
                if (!items.isEmpty()) return items;
            } catch (Throwable ignored) {}
            return searchYouTubeVideos(ctx, "popular videos today HD");
        }
        return searchYouTubeVideos(ctx, categoryQuery);
    }

    /**
     * Native `music.youtube.com` `WEB_REMIX` (`clientId = 67`) search & quick-picks engine!
     * Supports songs, music videos, albums, artists, community playlists, moods & radio mixes.
     */
    public static List<MediaItem> searchYouTubeMusic(Context ctx, String query, String filterMode) throws Exception {
        JSONObject payload = new JSONObject();
        JSONObject context = new JSONObject();
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB_REMIX");
        client.put("clientVersion", "1.20260707.12.00");
        client.put("hl", "en");
        client.put("gl", "US");
        String vd = ensureVisitorData(ctx);
        if (vd.length() > 0) client.put("visitorData", vd);
        context.put("client", client);
        payload.put("context", context);
        payload.put("query", query);
        // Official WEB_REMIX songs filter param `EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D` when mode == "songs"
        if ("songs".equalsIgnoreCase(filterMode)) {
            payload.put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D");
        } else if ("videos".equalsIgnoreCase(filterMode)) {
            payload.put("params", "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D");
        }

        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        hdrs.put("Origin", "https://music.youtube.com");
        hdrs.put("Referer", "https://music.youtube.com/");
        hdrs.put("X-YouTube-Client-Name", "67");
        hdrs.put("X-YouTube-Client-Version", "1.20260707.12.00");
        if (vd.length() > 0) hdrs.put("X-Goog-Visitor-Id", vd);

        try {
            String raw = requestHttpsString(ctx, "POST",
                    "https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
                    payload.toString(), hdrs, 14000);
            List<MediaItem> items = parseYouTubeItemsRecursive(new JSONObject(raw), true);
            if (!items.isEmpty()) return items;
        } catch (Throwable ignored) {}

        // Fallback to standard InnerTube + Cloud music search
        List<MediaItem> fb = searchYouTubeVideos(ctx, query + " official audio");
        for (int i = 0; i < fb.size(); i++) fb.get(i).isMusicTrack = true;
        return fb;
    }

    public static List<MediaItem> parseYouTubeItemsRecursive(JSONObject root, boolean musicMode) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        LinkedHashMap<String, MediaItem> dedup = new LinkedHashMap<String, MediaItem>();
        walkRenderers(root, dedup, musicMode, 0);
        out.addAll(dedup.values());
        return out;
    }

    private static void walkRenderers(Object node,
                                      LinkedHashMap<String, MediaItem> out,
                                      boolean musicMode,
                                      int depth) {
        if (node == null || depth > 28 || out.size() >= 60) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                walkRenderers(arr.opt(i), out, musicMode, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject obj = (JSONObject) node;

        // Skip ad slots completely (`Morphe Hide Ads Patch`)
        if (obj.has("adSlotRenderer") || obj.has("promotedSparklesWebRenderer")
                || obj.has("searchPyvRenderer") || obj.has("displayAdRenderer")) {
            return;
        }

        // 1. Standard YouTube video renderers (`videoRenderer`, `compactVideoRenderer`, `gridVideoRenderer`, `playlistVideoRenderer`)
        String[] videoKeys = new String[]{
                "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer"
        };
        for (int k = 0; k < videoKeys.length; k++) {
            JSONObject vr = obj.optJSONObject(videoKeys[k]);
            if (vr != null) {
                MediaItem it = parseStandardVideoRenderer(vr, musicMode);
                if (it != null && it.videoId.length() >= 8 && !out.containsKey(it.videoId)) {
                    out.put(it.videoId, it);
                }
            }
        }

        // 2. YouTube Music `musicResponsiveListItemRenderer` & `musicTwoRowItemRenderer`
        JSONObject mrl = obj.optJSONObject("musicResponsiveListItemRenderer");
        if (mrl != null) {
            MediaItem it = parseMusicListItemRenderer(mrl);
            if (it != null && it.videoId.length() >= 8 && !out.containsKey(it.videoId)) {
                out.put(it.videoId, it);
            }
        }
        JSONObject mtr = obj.optJSONObject("musicTwoRowItemRenderer");
        if (mtr != null) {
            MediaItem it = parseMusicTwoRowRenderer(mtr);
            if (it != null && it.videoId.length() >= 8 && !out.containsKey(it.videoId)) {
                out.put(it.videoId, it);
            }
        }

        JSONArray names = obj.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                walkRenderers(obj.opt(key), out, musicMode, depth + 1);
            }
        }
    }

    private static MediaItem parseStandardVideoRenderer(JSONObject vr, boolean musicMode) {
        String vid = vr.optString("videoId", "");
        if (vid.length() < 8) return null;
        MediaItem it = new MediaItem();
        it.videoId = vid;
        it.title = readRunsText(vr.optJSONObject("title"));
        if (it.title.length() == 0) return null;
        it.author = readRunsText(vr.optJSONObject("ownerText"));
        if (it.author.length() == 0) it.author = readRunsText(vr.optJSONObject("longBylineText"));
        if (it.author.length() == 0) it.author = readRunsText(vr.optJSONObject("shortBylineText"));
        it.durationText = readRunsText(vr.optJSONObject("lengthText"));
        it.durationSec = parseDurationTextSec(it.durationText);
        it.viewCountText = readRunsText(vr.optJSONObject("shortViewCountText"));
        if (it.viewCountText.length() == 0) it.viewCountText = readRunsText(vr.optJSONObject("viewCountText"));
        it.publishedText = readRunsText(vr.optJSONObject("publishedTimeText"));
        it.thumbnailUrl = bestThumbFromContainer(vr.optJSONObject("thumbnail"), vid);
        it.isMusicTrack = musicMode;
        if (it.durationText.length() == 0) {
            it.isLive = true;
            it.durationText = "LIVE";
        }
        return it;
    }

    private static MediaItem parseMusicListItemRenderer(JSONObject mrl) {
        String vid = findNestedVideoId(mrl);
        if (vid == null || vid.length() < 8) return null;
        MediaItem it = new MediaItem();
        it.videoId = vid;
        it.isMusicTrack = true;
        JSONArray cols = mrl.optJSONArray("flexColumns");
        if (cols != null && cols.length() > 0) {
            JSONObject c0 = cols.optJSONObject(0);
            if (c0 != null) {
                JSONObject txtObj = c0.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
                if (txtObj != null) it.title = readRunsText(txtObj.optJSONObject("text"));
            }
            if (cols.length() > 1) {
                JSONObject c1 = cols.optJSONObject(1);
                if (c1 != null) {
                    JSONObject txtObj = c1.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
                    if (txtObj != null) {
                        String meta = readRunsText(txtObj.optJSONObject("text"));
                        String[] parts = meta.split("\u2022");
                        it.author = parts.length > 0 ? parts[0].trim() : meta;
                        if (parts.length > 1) it.album = parts[1].trim();
                        if (parts.length > 0) {
                            String last = parts[parts.length - 1].trim();
                            if (last.matches("^\\d+:\\d{2}(:\\d{2})?$")) {
                                it.durationText = last;
                                it.durationSec = parseDurationTextSec(last);
                            }
                        }
                    }
                }
            }
        }
        if (it.title.length() == 0) return null;
        JSONObject fixed = mrl.optJSONArray("fixedColumns") != null && mrl.optJSONArray("fixedColumns").length() > 0
                ? mrl.optJSONArray("fixedColumns").optJSONObject(0) : null;
        if (fixed != null && it.durationText.length() == 0) {
            JSONObject fc = fixed.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
            if (fc != null) {
                it.durationText = readRunsText(fc.optJSONObject("text"));
                it.durationSec = parseDurationTextSec(it.durationText);
            }
        }
        JSONObject thumb = mrl.optJSONObject("thumbnail");
        if (thumb != null && thumb.optJSONObject("musicThumbnailRenderer") != null) {
            it.thumbnailUrl = bestThumbFromContainer(
                    thumb.optJSONObject("musicThumbnailRenderer").optJSONObject("thumbnail"), vid);
        } else {
            it.thumbnailUrl = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
        }
        return it;
    }

    private static MediaItem parseMusicTwoRowRenderer(JSONObject mtr) {
        String vid = findNestedVideoId(mtr);
        if (vid == null || vid.length() < 8) return null;
        MediaItem it = new MediaItem();
        it.videoId = vid;
        it.isMusicTrack = true;
        it.title = readRunsText(mtr.optJSONObject("title"));
        it.author = readRunsText(mtr.optJSONObject("subtitle"));
        if (it.title.length() == 0) return null;
        it.thumbnailUrl = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
        return it;
    }

    private static String findNestedVideoId(JSONObject obj) {
        if (obj == null) return null;
        JSONObject pd = obj.optJSONObject("playlistItemData");
        if (pd != null && pd.optString("videoId", "").length() >= 8) {
            return pd.optString("videoId");
        }
        JSONObject nav = obj.optJSONObject("navigationEndpoint");
        if (nav != null && nav.optJSONObject("watchEndpoint") != null) {
            String v = nav.optJSONObject("watchEndpoint").optString("videoId", "");
            if (v.length() >= 8) return v;
        }
        String raw = obj.toString();
        Matcher m = Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"").matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /* ==================== 6. Multi-Client Stream Spoofer + Cloud Failover ==================== */

    public static ResolvedPlayback resolveStreams(Context ctx, String videoId, boolean preferMusicClient) throws Exception {
        return resolveStreamsInternal(ctx, videoId, preferMusicClient, null, false);
    }

    /** Faster, progress-reporting resolver for foreground playback. Metadata is fetched separately
     * so SponsorBlock/recommendation servers cannot hold the video behind the resolving overlay. */
    public static ResolvedPlayback resolveStreams(Context ctx, String videoId, boolean preferMusicClient,
                                                   ResolutionProgressListener listener) throws Exception {
        return resolveStreamsInternal(ctx, videoId, preferMusicClient, listener, true);
    }

    private static ResolvedPlayback resolveStreamsInternal(Context ctx, String videoId,
                                                            boolean preferMusicClient,
                                                            ResolutionProgressListener listener,
                                                            boolean quick) throws Exception {
        ensureStreamProxyStarted(ctx);
        if (ctx != null) {
            try { MorpheStreamServer.setCacheDir(ctx.getCacheDir()); } catch (Throwable ignored) {}
        }
        int prefIdx = getPreferredSpoofClient(ctx);

        List<Integer> order = new ArrayList<Integer>();
        if (prefIdx >= 1 && prefIdx <= CLIENT_SPECS.length) {
            order.add(Integer.valueOf(prefIdx - 1));
        }
        // Try ANDROID_VR (Quest 3 `1.61.48` & Pico `1.64.34`) first: they return real MP4 progressive
        // `formats` (`itag 18`) AND H.264/AAC `adaptiveFormats` without PoToken or HLS-only manifests!
        int[] defaultOrder = preferMusicClient
                ? new int[]{5, 2, 0, 1, 3, 6, 4}
                : new int[]{5, 2, 0, 1, 6, 3, 4};
        for (int i = 0; i < defaultOrder.length; i++) {
            int idx = defaultOrder[i];
            if (idx < CLIENT_SPECS.length && !order.contains(Integer.valueOf(idx))) {
                order.add(Integer.valueOf(idx));
            }
        }
        for (int i = 0; i < CLIENT_SPECS.length; i++) {
            if (!order.contains(Integer.valueOf(i))) order.add(Integer.valueOf(i));
        }

        ResolvedPlayback resolved = null;
        StringBuilder failures = new StringBuilder();
        long directDeadline = quick ? System.currentTimeMillis() + QUICK_DIRECT_BUDGET_MS : Long.MAX_VALUE;
        int directAttempts = 0;
        int directLimit = quick ? Math.max(QUICK_DIRECT_CLIENT_ATTEMPTS, order.size()) : order.size();
        for (int idx = 0; idx < order.size() && resolved == null; idx++) {
            if (quick && directAttempts >= directLimit) break;
            long remaining = quick ? directDeadline - System.currentTimeMillis() : Long.MAX_VALUE;
            if (quick && remaining <= 0L) break;

            String[] spec = CLIENT_SPECS[order.get(idx).intValue()];
            directAttempts++;
            if (quick) {
                reportResolutionProgress(listener, "Resolving video: YouTube client " + directAttempts
                        + "/" + directLimit + " (" + spec[0] + ")...");
            }
            try {
                int visitorTimeout = quick
                        ? (int) Math.max(1L, Math.min((long) QUICK_VISITOR_TIMEOUT_MS, remaining))
                        : 9000;
                String vd = quick
                        ? ensureClientVisitorData(ctx, spec, visitorTimeout)
                        : ensureClientVisitorData(ctx, spec);
                remaining = quick ? directDeadline - System.currentTimeMillis() : Long.MAX_VALUE;
                if (quick && remaining <= 0L) {
                    failures.append(spec[0]).append(":time-budget ");
                    break;
                }
                int playerTimeout = quick
                        ? (int) Math.max(1L, Math.min((long) QUICK_PLAYER_TIMEOUT_MS, remaining))
                        : 15000;
                ResolvedPlayback attempt = fetchPlayerWithClientSpec(ctx, videoId, spec, vd, playerTimeout);
                if (attempt != null && attempt.hasAnyStream()) {
                    reportResolutionProgress(listener, "Stream found (" + spec[0] + "); preparing Android 4.2 playback...");
                    bindLocalPlayback(ctx, attempt, preferMusicClient);
                    String activeProxy = preferMusicClient ? attempt.localAudioProxyUrl : attempt.localVideoProxyUrl;
                    if (activeProxy != null && activeProxy.length() > 0) {
                        resolved = attempt;
                    } else {
                        failures.append(spec[0]).append(':').append(
                                attempt.muxNote.length() > 0 ? attempt.muxNote : "unplayable").append(' ');
                    }
                } else {
                    failures.append(spec[0]).append(":no-stream ");
                }
            } catch (Throwable t) {
                failures.append(spec[0]).append(':').append(shortError(t)).append(' ');
            }
        }

        if (resolved == null) {
            if (quick) reportResolutionProgress(listener, "Direct clients unavailable; trying video mirrors...");
            ResolvedPlayback cloud = quick
                    ? fetchPlayerFromCloudInstances(ctx, videoId, QUICK_CLOUD_TIMEOUT_MS,
                            QUICK_CLOUD_ENDPOINT_ATTEMPTS, listener)
                    : fetchPlayerFromCloudInstances(ctx, videoId);
            if (cloud != null) {
                reportResolutionProgress(listener, "Stream found; preparing Android playback...");
                bindLocalPlayback(ctx, cloud, preferMusicClient);
                String activeProxy = preferMusicClient ? cloud.localAudioProxyUrl : cloud.localVideoProxyUrl;
                if (activeProxy != null && activeProxy.length() > 0) {
                    resolved = cloud;
                }
            }
        }
        if (resolved == null) {
            throw new Exception("No playable H.264/AAC stream for " + videoId
                    + " (" + failures.toString().trim() + ")");
        }

        if (quick) {
            reportResolutionProgress(listener, "Stream ready; opening player...");
        } else {
            enrichWithNextSponsorBlockAndDislikes(ctx, resolved);
        }
        return resolved;
    }

    private static void reportResolutionProgress(ResolutionProgressListener listener, String message) {
        Log.i("MorpheEngine", message);
        if (listener != null) {
            try { listener.onProgress(message); } catch (Throwable ignored) {}
        }
    }

    public static void enrichPlaybackMetadata(Context ctx, ResolvedPlayback playback) {
        if (playback != null) enrichWithNextSponsorBlockAndDislikes(ctx, playback);
    }

    /**
     * Turns the resolved DASH / progressive formats into `127.0.0.1` URLs that Android 4.2's
     * Stagefright (`MPEG4Extractor.cpp`) can actually open:
     *
     *   • Progressive muxed MP4 (`itag 18` / `22`) -> verified via live range probe, then served
     *     through our local Range proxy.
     *   • Video-only H.264 + audio-only AAC DASH -> `MorpheStreamServer` +
     *     `MorpheRemuxer.remuxClassicMp4` defragments both tracks into a single classic
     *     non-fragmented ISO-BMFF MP4 (`ftyp` + `moov` with full `stsd`/`stts`/`stss`/`ctts`/
     *     `stsc`/`stsz`/`stco` sample tables + `mdat`).
     *   • Audio-only (YouTube Music / background play) -> `MorpheRemuxer.remuxSingleTrackClassicMp4`
     *     defragments the DASH audio track into a classic non-fragmented `.m4a` file.
     */
    private static void bindLocalPlayback(final Context ctx, final ResolvedPlayback res, boolean audioOnlyPreferred) {
        final StreamVariant videoTrack = pickBestVideoForMux(res);
        final StreamVariant audioTrack = res.audioStreams.isEmpty() ? null : res.audioStreams.get(0);

        // 1. Progressive muxed MP4 (`itag 18` / `22`) wins when available and verified reachable.
        if (!res.progressiveVideos.isEmpty()) {
            for (int i = 0; i < res.progressiveVideos.size(); i++) {
                StreamVariant bestV = res.progressiveVideos.get(i);
                if (bestV.isHls) continue;
                try {
                    byte[] probe = fetchRangeThroughGateway(ctx, bestV.directHttpsUrl, bestV.userAgent, 0L, 63L);
                    if (probe != null && probe.length >= 8) {
                        res.localVideoProxyUrl = registerPlayableStreamSlot(
                                ctx, bestV.directHttpsUrl, bestV.userAgent, bestV.mimeType);
                        if (audioOnlyPreferred && audioTrack != null && audioTrack.isDashTrack()) {
                            String spooledAudio = spoolAudioTrack(ctx, audioTrack);
                            res.localAudioProxyUrl = spooledAudio.length() > 0 ? spooledAudio : res.localVideoProxyUrl;
                        } else {
                            res.localAudioProxyUrl = res.localVideoProxyUrl;
                        }
                        res.muxNote = bestV.label + " (progressive)";
                        return;
                    }
                } catch (Throwable t) {
                    res.muxNote = "progressive probe failed: " + shortError(t);
                }
            }
        }

        // 2a. YouTube Music / audio-first: never download the video track at all.
        if (audioOnlyPreferred && audioTrack != null && audioTrack.isDashTrack()) {
            res.localAudioProxyUrl = spoolAudioTrack(ctx, audioTrack);
            if (res.localAudioProxyUrl.length() == 0 && videoTrack != null) {
                res.localVideoProxyUrl = startMuxForVariant(ctx, res, videoTrack);
                res.localAudioProxyUrl = res.localVideoProxyUrl;
            } else {
                res.localVideoProxyUrl = res.localAudioProxyUrl;
            }
            return;
        }

        // 2b. DASH: defragment video-only + audio-only into ONE classic non-fragmented MP4.
        if (videoTrack != null && audioTrack != null) {
            try {
                final MorpheRemuxer.Track vT = toRemuxTrack(ctx, videoTrack);
                final MorpheRemuxer.Track aT = toRemuxTrack(ctx, audioTrack);
                // Probe init segments synchronously so HTTP 403 / broken tracks fail fast to next client.
                MorpheRemuxer.plan(vT, aT);
                String url = MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                    @Override
                    public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                        MorpheRemuxer.remuxClassicMp4(vT, aT, out, progress);
                    }
                }, -1L, "video/mp4");
                res.localVideoProxyUrl = url;
                res.muxedLocally = true;
                res.muxNote = videoTrack.label + " + " + audioTrack.label;
            } catch (Throwable t) {
                res.muxNote = "mux failed: " + shortError(t);
            }
            if (audioOnlyPreferred) {
                res.localAudioProxyUrl = spoolAudioTrack(ctx, audioTrack);
            }
        }

        // 3. Video-only without a usable audio track: defragment into classic MP4.
        if (res.localVideoProxyUrl.length() == 0 && videoTrack != null) {
            try {
                final MorpheRemuxer.Track vT = toRemuxTrack(ctx, videoTrack);
                byte[] vInit = vT.fetcher.fetch(0L, vT.initEnd);
                if (vInit == null || vInit.length < 8) throw new Exception("Empty video init");
                String url = MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                    @Override
                    public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                        MorpheRemuxer.remuxSingleTrackClassicMp4(vT, out, progress);
                    }
                }, -1L, "video/mp4");
                res.localVideoProxyUrl = url;
                res.muxNote = videoTrack.label + " (video only)";
            } catch (Throwable t) {
                res.muxNote = "video spool failed: " + shortError(t);
            }
        }

        // 4. Audio-only (YouTube Music) still needs a playable local classic M4A file.
        if (res.localAudioProxyUrl.length() == 0 && audioTrack != null) {
            if (audioTrack.isDashTrack()) {
                res.localAudioProxyUrl = spoolAudioTrack(ctx, audioTrack);
            } else {
                try {
                    byte[] probe = fetchRangeThroughGateway(ctx, audioTrack.directHttpsUrl, audioTrack.userAgent, 0L, 63L);
                    if (probe != null && probe.length >= 8) {
                        res.localAudioProxyUrl = registerPlayableStreamSlot(
                                ctx, audioTrack.directHttpsUrl, audioTrack.userAgent, audioTrack.mimeType);
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (res.localAudioProxyUrl.length() == 0) {
            res.localAudioProxyUrl = res.localVideoProxyUrl;
        }
        if (res.localVideoProxyUrl.length() == 0 && res.localAudioProxyUrl.length() > 0) {
            res.localVideoProxyUrl = res.localAudioProxyUrl;
        }
    }

    private static MorpheRemuxer.Track toRemuxTrack(final Context ctx, final StreamVariant v) {
        final String url = v.directHttpsUrl;
        final String ua = v.userAgent;
        final long uaLong = v.initEnd;
        final long len = v.contentLength;
        final long dur = v.approxDurationMs;
        return new MorpheRemuxer.Track(
                v.mimeType,
                len,
                uaLong,
                dur,
                new MorpheRemuxer.RangeFetcher() {
                    @Override
                    public byte[] fetch(long startInclusive, long endInclusive) throws Exception {
                        return fetchRangeThroughGateway(ctx, url, ua, startInclusive, endInclusive);
                    }
                });
    }

    private static String spoolAudioTrack(final Context ctx, final StreamVariant audioTrack) {
        try {
            final MorpheRemuxer.Track aT = toRemuxTrack(ctx, audioTrack);
            // Verify init segment immediately so HTTP 403 URLs never get registered as valid streams.
            byte[] aInit = aT.fetcher.fetch(0L, aT.initEnd);
            if (aInit == null || aInit.length < 8) return "";
            return MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                @Override
                public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                    MorpheRemuxer.remuxSingleTrackClassicMp4(aT, out, progress);
                }
            }, -1L, "audio/mp4");
        } catch (Throwable t) {
            return "";
        }
    }


    /**
     * Called when the user taps "Background Audio": produces an audio-only MP4 for the current
     * video (the muxed file already contains the AAC track, but re-serving just the audio track
     * saves ~90% of the bandwidth on a 1 GB tablet).
     */
    public static String ensureAudioOnlyUrl(Context ctx, ResolvedPlayback res) {
        if (res == null) return "";
        if (res.audioStreams.isEmpty()) return res.localAudioProxyUrl;
        if (res.localAudioProxyUrl.length() > 0
                && !res.localAudioProxyUrl.equals(res.localVideoProxyUrl)) {
            return res.localAudioProxyUrl;
        }
        StreamVariant audio = res.audioStreams.get(0);
        if (!audio.isDashTrack()) {
            return registerPlayableStreamSlot(ctx, audio.directHttpsUrl, audio.userAgent, audio.mimeType);
        }
        String spooled = spoolAudioTrack(ctx, audio);
        if (spooled.length() > 0) res.localAudioProxyUrl = spooled;
        return res.localAudioProxyUrl;
    }

    /** Re-muxes the current video at a different quality (used by the quality picker). */
    public static String startMuxForVariant(Context ctx, ResolvedPlayback res, StreamVariant video) {
        if (res == null || video == null) return "";
        if (video.isDashTrack() && !res.audioStreams.isEmpty()
                && res.audioStreams.get(0).isDashTrack()) {
            try {
                final MorpheRemuxer.Track vT = toRemuxTrack(ctx, video);
                final MorpheRemuxer.Track aT = toRemuxTrack(ctx, res.audioStreams.get(0));
                MorpheRemuxer.plan(vT, aT);
                return MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                    @Override
                    public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                        MorpheRemuxer.remuxClassicMp4(vT, aT, out, progress);
                    }
                }, -1L, "video/mp4");
            } catch (Throwable ignored) {
            }
        }
        if (video.isDashTrack()) {
            try {
                final MorpheRemuxer.Track vT = toRemuxTrack(ctx, video);
                return MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                    @Override
                    public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                        MorpheRemuxer.remuxSingleTrackClassicMp4(vT, out, progress);
                    }
                }, -1L, "video/mp4");
            } catch (Throwable ignored) {
            }
        }
        return registerPlayableStreamSlot(ctx, video.directHttpsUrl, video.userAgent, video.mimeType);
    }

    /** Spools a single adaptive audio track (or wraps a progressive URL) for the audio player. */
    public static String startAudioForVariant(Context ctx, StreamVariant audio) {
        if (audio == null) return "";
        if (audio.isDashTrack()) {
            String spooled = spoolAudioTrack(ctx, audio);
            if (spooled.length() > 0) return spooled;
        }
        return registerPlayableStreamSlot(ctx, audio.directHttpsUrl, audio.userAgent, audio.mimeType);
    }

    private static StreamVariant pickBestVideoForMux(ResolvedPlayback res) {
        StreamVariant best = null;
        for (int i = 0; i < res.videoOnlyStreams.size(); i++) {
            StreamVariant v = res.videoOnlyStreams.get(i);
            if (!v.isDashTrack()) continue;
            if (best == null || scoreHeightForMux(v.height) > scoreHeightForMux(best.height)) best = v;
        }
        return best;
    }

    private static int scoreHeightForMux(int height) {
        if (height <= 0) return 0;
        // Prefer 360p / 480p for fast classic MP4 muxing on Kobo Arc 7 (720p remains selectable in Quality menu).
        if (height == 360) return 1000;
        if (height == 480) return 950;
        if (height == 240) return 800;
        if (height == 720) return 750;
        return height <= 720 ? height : 720 - (height - 720) / 2;
    }

    private static int scoreHeight(int height) {
        if (height <= 0) return 0;
        return height <= 720 ? height : 720 - (height - 720) / 2;
    }

    private static String shortError(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 70 ? m.substring(0, 70) : m;
    }

    private static ResolvedPlayback fetchPlayerWithClientSpec(Context ctx,
                                                              String videoId,
                                                              String[] spec,
                                                              String visitorData) throws Exception {
        return fetchPlayerWithClientSpec(ctx, videoId, spec, visitorData, 15000);
    }

    private static ResolvedPlayback fetchPlayerWithClientSpec(Context ctx,
                                                              String videoId,
                                                              String[] spec,
                                                              String visitorData,
                                                              int timeoutMs) throws Exception {
        String host = spec[13].length() > 0 ? spec[13] : "https://www.youtube.com";
        JSONObject payload = new JSONObject();
        payload.put("context", buildClientContext(spec, visitorData, true));
        payload.put("videoId", videoId);
        payload.put("cpn", randomCpn());
        payload.put("contentCheckOk", true);
        payload.put("racyCheckOk", true);

        Map<String, String> hdrs = clientHeaders(spec, host, visitorData);

        // Newer clients expose the full response on `/youtubei/v1/player` with `?id=` +
        // a rotating `t=` token; `www.youtube.com` only answers that shape for TV clients.
        String url = host + "/youtubei/v1/player?prettyPrint=false&t=" + randomToken(11)
                + "&id=" + Uri.encode(videoId);
        String raw = requestHttpsString(ctx, "POST", url, payload.toString(), hdrs, Math.max(1, timeoutMs));
        return parseInnerTubePlayerResponse(new JSONObject(raw), videoId, spec[0], spec[4]);
    }

    private static String randomToken(int len) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String randomCpn() {
        return randomToken(16);
    }

    public static ResolvedPlayback parseInnerTubePlayerResponse(JSONObject pr,
                                                                String expectedVideoId,
                                                                String clientLabel,
                                                                String clientUa) {
        JSONObject playability = pr.optJSONObject("playabilityStatus");
        if (playability != null && !"OK".equalsIgnoreCase(playability.optString("status", ""))) {
            return null;
        }
        JSONObject details = pr.optJSONObject("videoDetails");
        if (details == null) return null;
        String returnedId = details.optString("videoId", "");
        // Guard against YouTube's placeholder "Update app" video substitution!
        if (expectedVideoId != null && expectedVideoId.length() > 0 && !expectedVideoId.equals(returnedId)) {
            return null;
        }

        JSONObject sd = pr.optJSONObject("streamingData");
        if (sd == null) return null;

        ResolvedPlayback out = new ResolvedPlayback();
        out.videoId = returnedId;
        out.title = details.optString("title", "Video");
        out.author = details.optString("author", "");
        out.channelId = details.optString("channelId", "");
        out.description = details.optString("shortDescription", "");
        out.viewCount = details.optString("viewCount", "");
        try { out.durationSec = Long.parseLong(details.optString("lengthSeconds", "0")); } catch (Throwable ignored) {}
        out.thumbnailUrl = bestThumbFromContainer(details.optJSONObject("thumbnail"), returnedId);
        out.spoofClientUsed = clientLabel;

        // 1. Combined progressive Video + Audio formats (`formats`, e.g. H.264 + AAC MP4 `itag 18` / `22`).
        JSONArray formats = sd.optJSONArray("formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null) continue;
                String url = f.optString("url", "");
                if (url.length() == 0) continue;
                String mime = f.optString("mimeType", "video/mp4");
                // MT8125 hardware decoder excels at MP4/H.264 (`avc1` + `mp4a`); avoid AV1 (`av01`) & VP9 (`vp9`)
                if (mime.contains("av01") || mime.contains("vp9") || mime.contains("webm")) continue;
                StreamVariant sv = new StreamVariant();
                sv.itag = f.optInt("itag", 18);
                sv.directHttpsUrl = url;
                sv.userAgent = clientUa;
                sv.mimeType = mime.split(";")[0].trim();
                sv.codec = extractCodec(mime);
                sv.height = f.optInt("height", 360);
                sv.bitrate = f.optInt("bitrate", 500000);
                sv.hasVideo = true;
                sv.hasAudio = true;
                sv.label = (sv.height > 0 ? (sv.height + "p MP4") : ("MP4 #" + sv.itag));
                try { sv.contentLength = Long.parseLong(f.optString("contentLength", "-1")); } catch (Throwable ignored) {}
                out.progressiveVideos.add(sv);
            }
        }

        // 2. Adaptive formats: video-only H.264 and audio-only AAC DASH tracks.
        JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                JSONObject f = adaptive.optJSONObject(i);
                if (f == null) continue;
                String url = f.optString("url", "");
                if (url.length() == 0) continue;
                String mime = f.optString("mimeType", "");
                StreamVariant sv = new StreamVariant();
                sv.itag = f.optInt("itag", 140);
                sv.directHttpsUrl = url;
                sv.userAgent = clientUa;
                sv.mimeType = mime.split(";")[0].trim();
                sv.codec = extractCodec(mime);
                sv.bitrate = f.optInt("bitrate", 0);
                sv.height = f.optInt("height", 0);
                try { sv.contentLength = Long.parseLong(f.optString("contentLength", "-1")); } catch (Throwable ignored) {}
                try { sv.approxDurationMs = Long.parseLong(f.optString("approxDurationMs", "0")); } catch (Throwable ignored) {}
                sv.initEnd = readRangeEnd(f.optJSONObject("initRange"));
                sv.indexEnd = readRangeEnd(f.optJSONObject("indexRange"));
                if (sv.approxDurationMs <= 0L) sv.approxDurationMs = out.durationSec * 1000L;

                boolean isVideoMp4 = (mime.startsWith("video/mp4") || mime.startsWith("video/quicktime"))
                        && (mime.contains("avc1") || mime.contains("avc3"));
                boolean isAudioMp4 = mime.startsWith("audio/mp4")
                        && (mime.contains("mp4a") || mime.contains("aac"));
                if (isVideoMp4) {
                    sv.hasVideo = true;
                    sv.label = (sv.height > 0 ? (sv.height + "p") : "Video") + " H.264"
                            + (sv.bitrate > 0 ? (" " + (sv.bitrate / 1000) + "k") : "");
                    out.videoOnlyStreams.add(sv);
                } else if (isAudioMp4) {
                    sv.hasAudio = true;
                    if (sv.bitrate <= 0) {
                        if (sv.itag == 141) sv.bitrate = 256000;
                        else if (sv.itag == 140) sv.bitrate = 128000;
                        else if (sv.itag == 139) sv.bitrate = 48000;
                        else sv.bitrate = 128000;
                    }
                    sv.label = (sv.bitrate / 1000) + " kbps M4A";
                    out.audioStreams.add(sv);
                }
            }
        }

        // 3. Do NOT treat `hlsManifestUrl` as a progressive MP4: Apple `VISIONOS` often returns
        // an HLS-only manifest whose internal segment URLs are direct TLS 1.3 fMP4 ranges that
        // Android 4.2 Stagefright cannot open. Leaving `progressiveVideos` empty when only HLS
        // is present ensures `hasAnyStream()` stays false and our client cascade falls through
        // to `ANDROID_VR` / `TVHTML5_SIMPLY` / `IOS` which provide real MP4/M4A streams.

        // Sort progressive video highest-720p-compatible first (`<= 720p` ideal for 1024x600 / 1280x800 IPS panel!)
        Collections.sort(out.progressiveVideos, new Comparator<StreamVariant>() {
            @Override
            public int compare(StreamVariant a, StreamVariant b) {
                int sa = scoreHeight(a.height);
                int sb = scoreHeight(b.height);
                return sb - sa;
            }
        });
        Collections.sort(out.videoOnlyStreams, new Comparator<StreamVariant>() {
            @Override
            public int compare(StreamVariant a, StreamVariant b) {
                return scoreHeight(b.height) - scoreHeight(a.height);
            }
        });
        Collections.sort(out.audioStreams, new Comparator<StreamVariant>() {
            @Override
            public int compare(StreamVariant a, StreamVariant b) {
                return b.bitrate - a.bitrate;
            }
        });
        return out;
    }

    private static String extractCodec(String mimeType) {
        int idx = mimeType.indexOf("codecs=\"");
        if (idx < 0) return "";
        int end = mimeType.indexOf('"', idx + 8);
        return end > idx ? mimeType.substring(idx + 8, end) : "";
    }

    private static long readRangeEnd(JSONObject range) {
        if (range == null) return -1L;
        try {
            return Long.parseLong(range.optString("end", "-1"));
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static ResolvedPlayback fetchPlayerFromCloudInstances(Context ctx, String videoId) {
        return fetchPlayerFromCloudInstances(ctx, videoId, 10000, PIPED_INSTANCES.length + INVIDIOUS_INSTANCES.length, null);
    }

    private static ResolvedPlayback fetchPlayerFromCloudInstances(Context ctx, String videoId,
                                                                  int timeoutMs, int maxAttempts,
                                                                  ResolutionProgressListener listener) {
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        int attempts = 0;

        // Tier A: Public Piped API instances
        for (int i = 0; i < PIPED_INSTANCES.length && attempts < maxAttempts; i++) {
            attempts++;
            reportResolutionProgress(listener, "Trying video mirror " + attempts + "/" + maxAttempts + " (Piped)...");
            try {
                String raw = requestHttpsString(ctx, "GET",
                        PIPED_INSTANCES[i] + "/streams/" + Uri.encode(videoId), null, hdrs, timeoutMs);
                JSONObject j = new JSONObject(raw);
                ResolvedPlayback out = new ResolvedPlayback();
                out.videoId = videoId;
                out.title = j.optString("title", "Video");
                out.author = j.optString("uploader", "");
                out.description = j.optString("description", "");
                out.durationSec = j.optLong("duration", 0L);
                out.likesCount = j.optLong("likes", -1L);
                out.dislikesCount = j.optLong("dislikes", -1L);
                out.thumbnailUrl = j.optString("thumbnailUrl", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
                out.spoofClientUsed = "PIPED_CLOUD";

                JSONArray vArr = j.optJSONArray("videoStreams");
                if (vArr != null) {
                    for (int k = 0; k < vArr.length(); k++) {
                        JSONObject vf = vArr.optJSONObject(k);
                        if (vf != null && !vf.optBoolean("videoOnly", true)
                                && vf.optString("mimeType", "").contains("mp4")
                                && vf.optString("url", "").length() > 0) {
                            StreamVariant sv = new StreamVariant();
                            sv.directHttpsUrl = vf.optString("url");
                            sv.mimeType = "video/mp4";
                            sv.height = vf.optInt("height", 360);
                            sv.hasVideo = true;
                            sv.hasAudio = true;
                            sv.label = sv.height + "p MP4";
                            out.progressiveVideos.add(sv);
                        }
                    }
                }
                JSONArray aArr = j.optJSONArray("audioStreams");
                if (aArr != null) {
                    for (int k = 0; k < aArr.length(); k++) {
                        JSONObject af = aArr.optJSONObject(k);
                        if (af != null && af.optString("mimeType", "").contains("mp4")
                                && af.optString("url", "").length() > 0) {
                            StreamVariant sv = new StreamVariant();
                            sv.directHttpsUrl = af.optString("url");
                            sv.mimeType = "audio/mp4";
                            sv.bitrate = af.optInt("bitrate", 128000);
                            sv.hasAudio = true;
                            sv.label = (sv.bitrate / 1000) + " kbps M4A";
                            out.audioStreams.add(sv);
                        }
                    }
                }
                if (!out.progressiveVideos.isEmpty() || !out.audioStreams.isEmpty()) return out;
            } catch (Throwable ignored) {}
        }

        // Tier B: Public Invidious API instances
        for (int i = 0; i < INVIDIOUS_INSTANCES.length && attempts < maxAttempts; i++) {
            attempts++;
            reportResolutionProgress(listener, "Trying video mirror " + attempts + "/" + maxAttempts + " (Invidious)...");
            try {
                String raw = requestHttpsString(ctx, "GET",
                        INVIDIOUS_INSTANCES[i] + "/api/v1/videos/" + Uri.encode(videoId), null, hdrs, timeoutMs);
                JSONObject j = new JSONObject(raw);
                ResolvedPlayback out = new ResolvedPlayback();
                out.videoId = videoId;
                out.title = j.optString("title", "Video");
                out.author = j.optString("author", "");
                out.description = j.optString("description", "");
                out.durationSec = j.optLong("lengthSeconds", 0L);
                out.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                out.spoofClientUsed = "INVIDIOUS_CLOUD";

                JSONArray fmtStreams = j.optJSONArray("formatStreams");
                if (fmtStreams != null) {
                    for (int k = 0; k < fmtStreams.length(); k++) {
                        JSONObject vf = fmtStreams.optJSONObject(k);
                        if (vf != null && vf.optString("type", "").contains("mp4")
                                && vf.optString("url", "").length() > 0) {
                            StreamVariant sv = new StreamVariant();
                            sv.directHttpsUrl = vf.optString("url");
                            sv.mimeType = "video/mp4";
                            sv.hasVideo = true;
                            sv.hasAudio = true;
                            sv.label = vf.optString("qualityLabel", "360p MP4");
                            out.progressiveVideos.add(sv);
                        }
                    }
                }
                JSONArray adaptive = j.optJSONArray("adaptiveFormats");
                if (adaptive != null) {
                    for (int k = 0; k < adaptive.length(); k++) {
                        JSONObject af = adaptive.optJSONObject(k);
                        if (af != null && af.optString("type", "").startsWith("audio/mp4")
                                && af.optString("url", "").length() > 0) {
                            StreamVariant sv = new StreamVariant();
                            sv.directHttpsUrl = af.optString("url");
                            sv.mimeType = "audio/mp4";
                            sv.bitrate = af.optInt("bitrate", 128000);
                            sv.hasAudio = true;
                            sv.label = "M4A Audio";
                            out.audioStreams.add(sv);
                        }
                    }
                }
                if (!out.progressiveVideos.isEmpty() || !out.audioStreams.isEmpty()) return out;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<MediaItem> searchCloudFallback(Context ctx, String query, boolean musicOnly) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        String filter = musicOnly ? "music_songs" : "videos";
        for (int i = 0; i < PIPED_INSTANCES.length; i++) {
            try {
                String raw = requestHttpsString(ctx, "GET",
                        PIPED_INSTANCES[i] + "/search?q=" + Uri.encode(query) + "&filter=" + filter,
                        null, hdrs, 10000);
                JSONArray items = new JSONObject(raw).optJSONArray("items");
                if (items != null) {
                    for (int k = 0; k < items.length(); k++) {
                        JSONObject it = items.optJSONObject(k);
                        if (it == null) continue;
                        String u = it.optString("url", "");
                        String vid = extractVideoIdFromUrlOrQuery("https://www.youtube.com" + u);
                        if (vid == null) continue;
                        MediaItem m = new MediaItem();
                        m.videoId = vid;
                        m.title = it.optString("title", "Video");
                        m.author = it.optString("uploaderName", "");
                        m.durationSec = it.optLong("duration", 0L);
                        m.durationText = NowPlayingManager.formatDurationMs(m.durationSec * 1000L);
                        m.thumbnailUrl = it.optString("thumbnail", "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg");
                        m.isMusicTrack = musicOnly;
                        out.add(m);
                    }
                }
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /* ==================== 7. SponsorBlock, ReturnYouTubeDislike, Comments & Synced Lyrics ==================== */

    private static void enrichWithNextSponsorBlockAndDislikes(Context ctx, ResolvedPlayback out) {
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);

        // Related / Radio Queue (`youtubei/v1/next` automix `RDAMVM<id>`)
        try {
            JSONObject nextReq = new JSONObject();
            JSONObject c = new JSONObject();
            JSONObject cl = new JSONObject();
            cl.put("clientName", "WEB");
            cl.put("clientVersion", "2.20260708.00.00");
            cl.put("hl", "en");
            c.put("client", cl);
            nextReq.put("context", c);
            nextReq.put("videoId", out.videoId);
            String nextRaw = requestHttpsString(ctx, "POST",
                    "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
                    nextReq.toString(), hdrs, 7000);
            List<MediaItem> rel = parseYouTubeItemsRecursive(new JSONObject(nextRaw), false);
            for (int i = 0; i < rel.size() && out.relatedItems.size() < 20; i++) {
                if (!out.videoId.equals(rel.get(i).videoId)) {
                    out.relatedItems.add(rel.get(i));
                }
            }
        } catch (Throwable ignored) {}

        // SponsorBlock segments (`sponsor`, `selfpromo`, `interaction`, `music_offtopic`)
        if (isSponsorBlockEnabled(ctx)) {
            try {
                String sbUrl = "https://sponsor.ajay.app/api/skipSegments?videoID=" + Uri.encode(out.videoId)
                        + "&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22music_offtopic%22%5D";
                String sbRaw = requestHttpsString(ctx, "GET", sbUrl, null, hdrs, 4500);
                synchronized (out.skipSegments) {
                    out.skipSegments.addAll(parseSponsorBlockSegments(sbRaw));
                }
            } catch (Throwable ignored) {}
        }

        // Return YouTube Dislike counts
        if (isReturnDislikeEnabled(ctx) && out.dislikesCount < 0L) {
            try {
                String rydRaw = requestHttpsString(ctx, "GET",
                        "https://returnyoutubedislikeapi.com/votes?videoId=" + Uri.encode(out.videoId),
                        null, hdrs, 4500);
                JSONObject ryd = new JSONObject(rydRaw);
                out.likesCount = ryd.optLong("likes", out.likesCount);
                out.dislikesCount = ryd.optLong("dislikes", out.dislikesCount);
            } catch (Throwable ignored) {}
        }
    }

    public static List<SkipSegment> parseSponsorBlockSegments(String jsonArrayText) {
        List<SkipSegment> list = new ArrayList<SkipSegment>();
        try {
            JSONArray arr = new JSONArray(jsonArrayText);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONArray seg = o.optJSONArray("segment");
                if (seg != null && seg.length() >= 2) {
                    SkipSegment s = new SkipSegment();
                    s.startSec = (float) seg.optDouble(0, 0.0);
                    s.endSec = (float) seg.optDouble(1, 0.0);
                    s.category = o.optString("category", "sponsor");
                    if (s.endSec - s.startSec >= 1.5f) list.add(s);
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static List<CommentItem> fetchVideoComments(Context ctx, String videoId) {
        List<CommentItem> out = new ArrayList<CommentItem>();
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        for (int i = 0; i < PIPED_INSTANCES.length; i++) {
            try {
                String raw = requestHttpsString(ctx, "GET",
                        PIPED_INSTANCES[i] + "/comments/" + Uri.encode(videoId), null, hdrs, 7000);
                JSONArray arr = new JSONObject(raw).optJSONArray("comments");
                if (arr != null) {
                    for (int k = 0; k < arr.length() && out.size() < 25; k++) {
                        JSONObject c = arr.optJSONObject(k);
                        if (c == null) continue;
                        CommentItem ci = new CommentItem();
                        ci.author = c.optString("author", "Viewer");
                        ci.text = c.optString("commentText", "").replace("<br>", "\n").replace("&quot;", "\"");
                        ci.likes = String.valueOf(c.optInt("likeCount", 0));
                        ci.timeAgo = c.optString("commentedTime", "");
                        out.add(ci);
                    }
                }
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * Fetches millisecond-synced LRC karaoke lyrics (`lrclib.net`) with automatic fallback to plain lyrics!
     */
    public static List<LyricLine> fetchSyncedLyrics(Context ctx, String rawTitle, String rawArtist) {
        String cleanTitle = cleanTrackTitleForLyrics(rawTitle);
        String cleanArtist = rawArtist != null ? rawArtist.replace(" - Topic", "").trim() : "";
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", "YouTubeMusicMorphe/2.4.0");
        try {
            String url = "https://lrclib.net/api/search?track_name=" + Uri.encode(cleanTitle)
                    + (cleanArtist.length() > 0 ? ("&artist_name=" + Uri.encode(cleanArtist)) : "");
            String raw = requestHttpsString(ctx, "GET", url, null, hdrs, 7000);
            JSONArray arr = new JSONArray(raw);
            if (arr.length() > 0) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null && item.optString("syncedLyrics", "").length() > 10) {
                        return parseLrcText(item.optString("syncedLyrics"));
                    }
                }
                JSONObject first = arr.optJSONObject(0);
                if (first != null && first.optString("plainLyrics", "").length() > 0) {
                    return parseLrcText(first.optString("plainLyrics"));
                }
            }
        } catch (Throwable ignored) {}
        return new ArrayList<LyricLine>();
    }

    public static List<LyricLine> parseLrcText(String rawLrc) {
        List<LyricLine> lines = new ArrayList<LyricLine>();
        if (rawLrc == null) return lines;
        Pattern p = Pattern.compile("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)$");
        String[] rows = rawLrc.split("\n");
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i].trim();
            if (r.length() == 0) continue;
            Matcher m = p.matcher(r);
            LyricLine ll = new LyricLine();
            if (m.find()) {
                long mins = Long.parseLong(m.group(1));
                long secs = Long.parseLong(m.group(2));
                String frac = m.group(3) != null ? m.group(3) : "0";
                while (frac.length() < 3) frac = frac + "0";
                ll.timeMs = mins * 60000L + secs * 1000L + Long.parseLong(frac.substring(0, 3));
                ll.text = m.group(4).trim();
            } else {
                ll.timeMs = -1L;
                ll.text = r;
            }
            if (ll.text.length() > 0) lines.add(ll);
        }
        return lines;
    }

    private static String cleanTrackTitleForLyrics(String t) {
        if (t == null) return "";
        return t.replaceAll("(?i)\\((official\\s+video|official\\s+music\\s+video|official\\s+audio|lyrics?|visualizer|hd|4k)\\)", "")
                .replaceAll("(?i)\\[(official\\s+video|official\\s+audio|lyrics?|4k)\\]", "")
                .trim();
    }

    /* ==================== 8. 1-Tap Offline MP4 Video & M4A Audio Downloader ==================== */

    public static File downloadStreamOffline(Context ctx,
                                             StreamVariant variant,
                                             String title,
                                             boolean audioOnly,
                                             DownloadProgressListener listener) throws Exception {
        File dir = new File(Environment.getExternalStorageDirectory(),
                audioOnly ? "Music/YouTubeMusic" : "Movies/YouTube");
        if (!dir.exists()) dir.mkdirs();

        String safeName = (title != null ? title : "media")
                .replaceAll("[^A-Za-z0-9 _.-]", "")
                .trim();
        if (safeName.length() == 0) safeName = "download_" + System.currentTimeMillis();
        if (safeName.length() > 64) safeName = safeName.substring(0, 64).trim();
        File outFile = new File(dir, safeName + (audioOnly ? ".m4a" : ".mp4"));

        // DASH tracks (`initRange`/`indexRange`) cannot be proxied 1:1 — the raw file starts with
        // a bare `moov` and is only playable once it is spooled/merged, so download it that way.
        final boolean dash = variant.isDashTrack();
        if (dash) {
            final MorpheRemuxer.Track t = toRemuxTrack(ctx, variant);
            final long total = variant.contentLength;
            final DownloadProgressListener dlListener = listener;
            RandomAccessFile rafOut = new RandomAccessFile(outFile, "rw");
            try {
                MorpheRemuxer.copyTrack(t, rafOut, new MorpheRemuxer.Progress() {
                    @Override
                    public void onBytes(long absoluteOffset, long totalBytes) {
                        long denom = totalBytes > 0L ? totalBytes : total;
                        if (dlListener != null && denom > 0L) {
                            dlListener.onProgress(
                                    (int) Math.min(100L, (absoluteOffset * 100L) / denom),
                                    absoluteOffset,
                                    denom);
                        }
                    }

                    @Override
                    public void onStage(String stage) {}
                });
            } finally {
                try { rafOut.close(); } catch (Throwable ignored) {}
            }
            return outFile;
        }

        String localProxyUrl = registerPlayableStreamSlot(
                ctx, variant.directHttpsUrl, variant.userAgent, variant.mimeType);

        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            conn = (HttpURLConnection) new URL(localProxyUrl).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Range", "bytes=0-");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                throw new Exception("Download HTTP " + code);
            }
            long total = conn.getContentLength();
            if (total <= 0L && variant.contentLength > 0L) total = variant.contentLength;

            in = conn.getInputStream();
            fos = new FileOutputStream(outFile);
            byte[] buf = new byte[32768];
            long done = 0L;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                if (listener != null && total > 0L) {
                    int pct = (int) ((done * 100L) / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        listener.onProgress(pct, done, total);
                    }
                }
            }
            fos.flush();
            return outFile;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    public interface DownloadProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
    }

    /* ==================== 9. Shared JSON Extractors ==================== */

    private static String readRunsText(JSONObject textObj) {
        if (textObj == null) return "";
        if (textObj.has("simpleText")) return textObj.optString("simpleText", "");
        JSONArray runs = textObj.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString().trim();
    }

    private static String bestThumbFromContainer(JSONObject thumbObj, String fallbackVideoId) {
        if (thumbObj != null) {
            JSONArray arr = thumbObj.optJSONArray("thumbnails");
            if (arr != null && arr.length() > 0) {
                JSONObject best = arr.optJSONObject(arr.length() - 1);
                if (best != null) {
                    String u = best.optString("url", "");
                    if (u.startsWith("//")) u = "https:" + u;
                    if (u.startsWith("http")) return u;
                }
            }
        }
        return "https://i.ytimg.com/vi/" + fallbackVideoId + "/hqdefault.jpg";
    }

    private static long parseDurationTextSec(String s) {
        if (s == null || s.length() == 0) return 0L;
        String[] parts = s.trim().split(":");
        try {
            if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2]);
            }
            if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1]);
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    /* ==================== 10. Persistent History, Liked Songs & Subscriptions ==================== */

    public static List<MediaItem> loadSavedList(Context ctx, String listKey) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString(listKey, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                MediaItem m = new MediaItem();
                m.videoId = o.optString("v", "");
                m.title = o.optString("t", "");
                m.author = o.optString("a", "");
                m.durationText = o.optString("d", "");
                m.thumbnailUrl = o.optString("th", "");
                m.localFilePath = o.optString("fp", "");
                m.isMusicTrack = o.optBoolean("m", false);
                out.add(m);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void addOrToggleSavedItem(Context ctx, String listKey, MediaItem item, boolean toggleIfPresent) {
        List<MediaItem> cur = loadSavedList(ctx, listKey);
        int existing = -1;
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).videoId.equals(item.videoId) && item.videoId.length() > 0) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            cur.remove(existing);
            if (toggleIfPresent) {
                saveList(ctx, listKey, cur);
                return;
            }
        }
        cur.add(0, item);
        while (cur.size() > 80) cur.remove(cur.size() - 1);
        saveList(ctx, listKey, cur);
    }

    private static void saveList(Context ctx, String listKey, List<MediaItem> items) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < items.size(); i++) {
                MediaItem m = items.get(i);
                JSONObject o = new JSONObject();
                o.put("v", m.videoId);
                o.put("t", m.title);
                o.put("a", m.author);
                o.put("d", m.durationText);
                o.put("th", m.bestThumb());
                o.put("fp", m.localFilePath);
                o.put("m", m.isMusicTrack);
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(listKey, arr.toString()).commit();
    }
}
