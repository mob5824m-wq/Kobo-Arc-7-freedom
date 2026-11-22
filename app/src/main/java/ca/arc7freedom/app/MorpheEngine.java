package ca.arc7freedom.app;

import ca.arc7freedom.support.MediaCache;
import ca.arc7freedom.support.DebugLog;

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
import java.security.SecureRandom;
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

    // Bounded, paced attempts using distinct maintained client families, not two obsolete VR versions.
    private static final int MAX_DIRECT_CLIENT_ATTEMPTS = 3;
    private static final int QUICK_VISITOR_TIMEOUT_MS = 4000;
    private static final int QUICK_PLAYER_TIMEOUT_MS = 8000;
    private static final int QUICK_CLOUD_TIMEOUT_MS = 3000;
    private static final int QUICK_CLOUD_ENDPOINT_ATTEMPTS = 5;
    private static final long QUICK_DIRECT_BUDGET_MS = 38000L;
    private static final Object YOUTUBE_PACE_LOCK = new Object();
    private static long nextYouTubeRequestAtMs = 0L;
    private static final SecureRandom PLAYBACK_RANDOM = new SecureRandom();
    private static final String NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    // User-selectable Morphe Spoof Stream Client modes (`0 = Auto Best Stream`)
    public static final String[] SPOOF_CLIENT_LABELS = new String[]{
            "Auto (visionOS HLS/MP4 -> iOS -> TV Simply)",
            "visionOS 1.04 (H.264/AAC HLS + MP4)",
            "TV Simply 1.0 (fallback; may require sign-in)",
            "Legacy Android VR 1.64 (manual only; may be unavailable)",
            "Android Music 7.12 (YouTube Music Native M4A)",
            "Android Creator 26.10 (Studio — 720p cap)",
            "Legacy Android VR 1.61 (manual only; may be unavailable)",
            "iOS 21.26 (H.264/AAC fallback)"
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
                    "TV_SIMPLY_1_0", "75", "TVHTML5_SIMPLY", "1.0",
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
                    "IOS_21_26", "5", "IOS", "21.26.4",
                    "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
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

    public static final class AccessRequiredException extends IOException {
        public AccessRequiredException(String reason) { super(reason+". Open the official page in a supported browser to sign in or complete the check; this app cannot bypass it."); }
    }

    public interface ResolutionProgressListener {
        void onProgress(String message);
    }

    public static final class MediaItem {
        public String videoId = "";
        public String browseId = "";
        public String playlistId = "";
        public String collectionType = ""; // album / playlist / artist, never a pretend playable video
        public boolean isArtist() { return "artist".equals(collectionType) && browseId.matches("UC[A-Za-z0-9_-]{22}"); }
        public boolean isCollection() { return ("album".equals(collectionType) || "playlist".equals(collectionType)) && (browseId.length()>0 || playlistId.length()>0); }
        public boolean isBrowsable() { return isArtist() || isCollection(); }
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
        public boolean verifiedAudio;
        public String localVideoProxyUrl = "";
        public String localAudioProxyUrl = "";
        public String hlsManifestUrl = "";
        public String hlsUserAgent = WEB_UA;
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
            return hlsManifestUrl.length() > 0 || !progressiveVideos.isEmpty() || !videoOnlyStreams.isEmpty() || !audioStreams.isEmpty();
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

    private static boolean isYouTubeOrigin(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("youtube.com/") || lower.contains("youtubei.googleapis.com/")
                || lower.contains("googlevideo.com/");
    }

    private static void paceYouTubeRequest(String url) throws IOException {
        if (!isYouTubeOrigin(url)) return;
        synchronized (YOUTUBE_PACE_LOCK) {
            long waitMs;
            while ((waitMs = nextYouTubeRequestAtMs - System.currentTimeMillis()) > 0L) {
                if (waitMs > 10000L) throw new IOException("YouTube rate limited this connection; retry later");
                try {
                    YOUTUBE_PACE_LOCK.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while pacing YouTube requests", e);
                }
            }
            nextYouTubeRequestAtMs = System.currentTimeMillis() + 1200L;
        }
    }

    private static void backoffYouTubeOrigin(String url, int statusCode) {
        if (!isYouTubeOrigin(url)) return;
        long delay = statusCode == 429 || statusCode == 503 ? 60000L
                : 0L;
        if (delay == 0L) return;
        synchronized (YOUTUBE_PACE_LOCK) {
            nextYouTubeRequestAtMs = Math.max(nextYouTubeRequestAtMs,
                    System.currentTimeMillis() + delay);
        }
    }

    public static byte[] requestHttpsBytes(Context ctx,
                                           String method,
                                           String httpsUrl,
                                           String jsonBody,
                                           Map<String, String> headers,
                                           int timeoutMs,
                                           int maxBytes) throws Exception {
        checkResolutionCancelled();
        paceYouTubeRequest(httpsUrl);
        checkResolutionCancelled();
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

            checkResolutionCancelled();
            int code = conn.getResponseCode();
            if (code == 403 || code == 429 || code == 503) {
                backoffYouTubeOrigin(httpsUrl, code);
            }
            in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                throw new Exception("HTTP " + code);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                checkResolutionCancelled();
                bos.write(buf, 0, n);
                if (bos.size() >= maxBytes) break;
            }
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code + ": " + new String(bos.toByteArray(), "UTF-8"));
            }
            checkResolutionCancelled();
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
        String cacheKey = "vd_v2_" + spec[2] + "_" + spec[3].replace('.', '_');
        String cached = "";
        long cachedAt = 0L;
        if (ctx != null) {
            try {
                cached = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(cacheKey, "");
                cachedAt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(cacheKey + "_at", 0L);
            } catch (Throwable ignored) {}
        }
        if (cached != null && cached.length() > 8 && (System.currentTimeMillis() - cachedAt) < 6L * 3600L * 1000L) {
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
            client.put("userAgent", spec[4]);
            client.put("hl", "en");
            client.put("gl", "US");
            client.put("utcOffsetMinutes", 0);
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
        if (host.contains("youtube.com")) hdrs.put("Origin", host);
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
        checkResolutionCancelled();
        if(startInclusive<0 || endInclusive<startInclusive || endInclusive-startInclusive>=32L*1024L*1024L)
            throw new IOException("Invalid or oversized media range");
        final Context owner = ctx;
        final String source = httpsUrl, agent = userAgent;
        final long first = startInclusive, last = endInclusive;
        return LegacyMediaIo.read(new LegacyMediaIo.Attempt<byte[]>() {
            @Override public byte[] run() throws Exception {
                return fetchRangeOnce(owner, source, agent, first, last);
            }
        });
    }

    private static byte[] fetchRangeOnce(Context ctx, String httpsUrl, String userAgent,
                                         long startInclusive, long endInclusive) throws Exception {
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
            // Android 4 may recycle a socket closed by the native gateway. Each retry
            // must really be a fresh GET, not the same stale pooled connection.
            conn.setUseCaches(false);
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Range", "bytes=" + startInclusive + "-" + endInclusive);
            conn.setRequestProperty("User-Agent",
                    (userAgent != null && userAgent.length() > 0) ? userAgent : WEB_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            String range=conn.getHeaderField("Content-Range");
            if(code==416 && ("bytes */"+startInclusive).equals(range)) return new byte[0];
            LegacyMediaIo.requireSuccess(code);
            long declared=-1;
            try { declared=Long.parseLong(conn.getHeaderField("Content-Length")); } catch(Exception ignored) {}
            boolean whole = (code == 200);
            long skip = whole ? startInclusive : 0L;
            long expected=want;
            if(!whole) {
                Matcher match=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matcher(range==null ? "" : range);
                if(!match.matches()) throw new IOException("Missing or invalid Content-Range");
                long first=Long.parseLong(match.group(1)),last=Long.parseLong(match.group(2));
                long total="*".equals(match.group(3)) ? -1 : Long.parseLong(match.group(3));
                if(first!=startInclusive || last<first || last>endInclusive || (total>=0 && last>=total)
                        || (last<endInclusive && (total<0 || last!=total-1))) throw new IOException("Incorrect media range response");
                expected=last-first+1;
                if(declared>=0 && declared!=expected) throw new IOException("Media range length mismatch");
            } else if(declared>=0) {
                if(startInclusive>declared) throw new IOException("Media starts beyond end of file");
                expected=Math.min(want,declared-startInclusive);
            }
            in = conn.getInputStream();
            byte[] out = new byte[(int)expected];
            long skipped = 0L;
            byte[] sink = new byte[8192];
            while (skipped < skip) {
                checkResolutionCancelled();
                int n = in.read(sink, 0, (int) Math.min((long) sink.length, skip - skipped));
                if (n <= 0) break;
                skipped += n;
            }
            if(skipped!=skip) throw new java.io.EOFException("Truncated full media response");
            int off = 0;
            while (off < out.length) {
                checkResolutionCancelled();
                int n = in.read(out, off, out.length - off);
                if (n <= 0) break;
                off += n;
            }
            checkResolutionCancelled();
            if (off == out.length) return out;
            if(!whole || declared>=0) throw new java.io.EOFException("Truncated media range response");
            byte[] shorter = new byte[off];
            System.arraycopy(out, 0, shorter, 0, off);
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
        filterMode = filterMode == null ? "songs" : filterMode.toLowerCase(Locale.US);
        final boolean entitiesOnly = "artists".equals(filterMode) || "albums".equals(filterMode) || filterMode.contains("playlists");
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
        } else if ("artists".equalsIgnoreCase(filterMode)) {
            payload.put("params", "EgWKAQIgAWoMEA4QChADEAQQCRAF");
        } else if ("albums".equalsIgnoreCase(filterMode)) {
            payload.put("params", "EgWKAQIYAWoMEA4QChADEAQQCRAF");
        } else if ("playlists".equalsIgnoreCase(filterMode)) {
            payload.put("params", "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D");
        } else if ("community_playlists".equalsIgnoreCase(filterMode)) {
            payload.put("params", "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D");
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
            List<MediaItem> matching = new ArrayList<MediaItem>();
            for (MediaItem item : items) {
                if ("artists".equals(filterMode) ? item.isArtist()
                        : "albums".equals(filterMode) ? "album".equals(item.collectionType) && item.isCollection()
                        : filterMode.contains("playlists") ? "playlist".equals(item.collectionType) && item.isCollection()
                        : !item.isBrowsable() && item.videoId.matches("[A-Za-z0-9_-]{11}")) matching.add(item);
            }
            // Artist/collection searches must never silently turn into unrelated song searches.
            if (entitiesOnly || !matching.isEmpty()) return matching;
        } catch (Exception unavailable) {
            if (entitiesOnly) throw new IOException(("artists".equals(filterMode) ? "Artist search" : "Music collections")
                    + " unavailable. No unrelated song results were substituted.", unavailable);
        }

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
            MediaItem collection=musicMode ? parseMusicEntityRenderer(mrl) : null;
            if(collection!=null) { out.put(collection.browseId.length()>0 ? collection.browseId : collection.playlistId,collection); return; }
            MediaItem it = parseMusicListItemRenderer(mrl);
            if (it != null && it.videoId.length() >= 8 && !out.containsKey(it.videoId)) {
                out.put(it.videoId, it);
            }
        }
        JSONObject mtr = obj.optJSONObject("musicTwoRowItemRenderer");
        if (mtr != null) {
            MediaItem collection=musicMode ? parseMusicEntityRenderer(mtr) : null;
            if(collection!=null) { out.put(collection.browseId.length()>0 ? collection.browseId : collection.playlistId,collection); return; }
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
        it.channelId = findChannelId(vr.optJSONObject("ownerText"),0);
        if(it.channelId.length()==0) it.channelId=findChannelId(vr.optJSONObject("longBylineText"),0);
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

    private static MediaItem parseMusicEntityRenderer(JSONObject renderer) {
        JSONObject title=renderer.optJSONObject("title");
        JSONArray flex=renderer.optJSONArray("flexColumns");
        if(title==null && flex!=null && flex.length()>0 && flex.optJSONObject(0)!=null) {
            JSONObject col=flex.optJSONObject(0).optJSONObject("musicResponsiveListItemFlexColumnRenderer");
            if(col!=null) title=col.optJSONObject("text");
        }
        JSONObject nav=renderer.optJSONObject("navigationEndpoint");
        if ((nav == null || (nav.optJSONObject("browseEndpoint") == null && nav.optJSONObject("watchPlaylistEndpoint") == null))
                && title != null && title.optJSONArray("runs") != null) {
            JSONArray runs = title.optJSONArray("runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i), endpoint = run == null ? null : run.optJSONObject("navigationEndpoint");
                if (endpoint != null && endpoint.optJSONObject("browseEndpoint") != null) { nav = endpoint; break; }
            }
        }
        if(nav==null) return null;
        JSONObject browse=nav.optJSONObject("browseEndpoint"), watch=nav.optJSONObject("watchPlaylistEndpoint");
        String id=browse==null ? "" : browse.optString("browseId","");
        String playlist=watch==null ? "" : watch.optString("playlistId","");
        String pageType = "";
        if (browse != null) {
            JSONObject supported = browse.optJSONObject("browseEndpointContextSupportedConfigs");
            JSONObject config = supported == null ? null : supported.optJSONObject("browseEndpointContextMusicConfig");
            if (config != null) pageType = config.optString("pageType", "");
        }
        String type = id.matches("UC[A-Za-z0-9_-]{22}") && (pageType.length() == 0 || "MUSIC_PAGE_TYPE_ARTIST".equals(pageType))
                ? "artist" : id.startsWith("MPRE") ? "album" : id.startsWith("VL") || playlist.length()>0 ? "playlist" : "";
        if(type.length()==0) return null;
        MediaItem item=new MediaItem(); item.browseId=id; item.playlistId=playlist;
        if(item.playlistId.length()==0 && id.startsWith("VL")) item.playlistId=id.substring(2);
        item.collectionType=type; item.title=readRunsText(title); item.author=readRunsText(renderer.optJSONObject("subtitle")); item.isMusicTrack=true;
        if (item.author.length() == 0 && flex != null && flex.length() > 1 && flex.optJSONObject(1) != null) {
            JSONObject column = flex.optJSONObject(1).optJSONObject("musicResponsiveListItemFlexColumnRenderer");
            if (column != null) item.author = readRunsText(column.optJSONObject("text"));
        }
        JSONObject thumbnail=renderer.optJSONObject("thumbnailRenderer"); if(thumbnail==null) thumbnail=renderer.optJSONObject("thumbnail");
        if(thumbnail!=null && thumbnail.optJSONObject("musicThumbnailRenderer")!=null) thumbnail=thumbnail.optJSONObject("musicThumbnailRenderer").optJSONObject("thumbnail");
        item.thumbnailUrl=bestThumbFromContainer(thumbnail,"");
        return item.title.length()>0 ? item : null;
    }

    private static JSONObject musicBrowseContext() throws Exception {
        return new JSONObject().put("client",new JSONObject().put("clientName","WEB_REMIX")
                .put("clientVersion","1.20260804.16.00").put("hl","en").put("gl","CA"));
    }
    public static List<MediaItem> browseMusicCollection(Context ctx,MediaItem item) throws Exception {
        if (item == null || !item.isCollection()) throw new IOException("Choose an album or playlist");
        if(item.playlistId.startsWith("local:")) return loadSavedList(ctx,"ytm_local_"+item.playlistId.substring(6));
        String browse=item.browseId.length()>0 ? item.browseId : "VL"+item.playlistId;
        if(!browse.matches("[A-Za-z0-9_-]{5,200}")) throw new IOException("Invalid album/playlist id");
        JSONObject body=new JSONObject().put("context",musicBrowseContext()).put("browseId",browse);
        Map<String,String> headers=new LinkedHashMap<String,String>(); headers.put("User-Agent",WEB_UA);
        headers.put("Origin","https://music.youtube.com"); headers.put("X-YouTube-Client-Name","67"); headers.put("X-YouTube-Client-Version","1.20260804.16.00");
        String raw=requestHttpsString(ctx,"POST","https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",body.toString(),headers,16000);
        JSONObject contents=new JSONObject(raw).optJSONObject("contents");
        JSONObject two=contents==null ? null : contents.optJSONObject("twoColumnBrowseResultsRenderer");
        Object area=two==null ? contents : two.optJSONObject("secondaryContents");
        JSONObject shelf=findMusicShelf(area,"musicPlaylistShelfRenderer",0);
        if(shelf==null) shelf=findMusicShelf(area,"musicShelfRenderer",0);
        List<MediaItem> tracks=new ArrayList<MediaItem>();
        if(shelf!=null) for(MediaItem track:parseYouTubeItemsRecursive(shelf,true))
            if(!track.isBrowsable() && track.videoId.matches("[A-Za-z0-9_-]{11}")) tracks.add(track);
        if(tracks.isEmpty()) throw new IOException("No public tracks returned. The playlist may be private, empty, require sign-in or use an unsupported layout.");
        return tracks; // First page, bounded to 60 for the tablet's heap. The UI labels this limit.
    }
    /** Public artist songs and releases only, never the header's radio/shuffle buttons or related artists. */
    public static List<MediaItem> browseMusicArtist(Context ctx, MediaItem artist) throws Exception {
        if (artist == null || !artist.isArtist()) throw new IOException("Invalid music artist identity");
        JSONObject body = new JSONObject().put("context", musicBrowseContext()).put("browseId", artist.browseId);
        Map<String,String> headers = new LinkedHashMap<String,String>();
        headers.put("User-Agent", WEB_UA); headers.put("Origin", "https://music.youtube.com");
        headers.put("X-YouTube-Client-Name", "67"); headers.put("X-YouTube-Client-Version", "1.20260804.16.00");
        String raw = requestHttpsString(ctx, "POST", "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                body.toString(), headers, 16000);
        return parseMusicArtistPage(new JSONObject(raw));
    }

    public static List<MediaItem> parseMusicArtistPage(JSONObject page) throws IOException {
        JSONObject contents = page.optJSONObject("contents");
        JSONObject primary = contents == null ? null : contents.optJSONObject("singleColumnBrowseResultsRenderer");
        if (primary == null && contents != null) primary = contents.optJSONObject("twoColumnBrowseResultsRenderer");
        JSONArray tabs = primary == null ? null : primary.optJSONArray("tabs");
        JSONObject selected = null;
        if (tabs != null) for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i), renderer = tab == null ? null : tab.optJSONObject("tabRenderer");
            if (renderer != null && (selected == null || renderer.optBoolean("selected", false))) {
                selected = renderer;
                if (renderer.optBoolean("selected", false)) break;
            }
        }
        // Only the primary artist tab; a sidebar can contain unrelated recommendations.
        JSONObject sections = selected == null ? null : findMusicShelf(selected.optJSONObject("content"), "sectionListRenderer", 0);
        JSONArray rows = sections == null ? null : sections.optJSONArray("contents");
        LinkedHashMap<String,MediaItem> results = new LinkedHashMap<String,MediaItem>();
        if (rows != null) for (int i = 0; i < rows.length() && i < 100 && results.size() < 60; i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            JSONObject shelf = row.optJSONObject("musicShelfRenderer");
            boolean directSongs = shelf != null;
            if (shelf == null) shelf = row.optJSONObject("musicCarouselShelfRenderer");
            if (shelf == null) continue;
            String title = readRunsText(shelf.optJSONObject("title"));
            JSONObject header = shelf.optJSONObject("header");
            JSONObject basic = header == null ? null : header.optJSONObject("musicCarouselShelfBasicHeaderRenderer");
            if (title.length() == 0 && basic != null) title = readRunsText(basic.optJSONObject("title"));
            title = title.toLowerCase(Locale.US).trim();
            boolean songs = "songs".equals(title) || "top songs".equals(title) || "popular songs".equals(title)
                    || (directSongs && title.length() == 0);
            boolean releases = "albums".equals(title) || "singles".equals(title) || "singles & eps".equals(title)
                    || "singles and eps".equals(title) || "eps".equals(title);
            if (!songs && !releases) continue;
            JSONArray entries = shelf.optJSONArray("contents");
            if (entries == null) continue;
            LinkedHashMap<String,MediaItem> parsed = new LinkedHashMap<String,MediaItem>();
            walkRenderers(entries, parsed, true, 0);
            for (MediaItem item : parsed.values()) {
                if (songs && !item.isBrowsable() && item.videoId.matches("[A-Za-z0-9_-]{11}")) {
                    results.put("video:" + item.videoId, item);
                } else if (releases && "album".equals(item.collectionType) && item.isCollection()) {
                    results.put("album:" + item.browseId, item);
                }
                if (results.size() >= 60) break;
            }
        }
        if (results.isEmpty()) throw new IOException("No public artist songs or albums returned. The page may require sign-in or use an unsupported layout.");
        return new ArrayList<MediaItem>(results.values());
    }

    /** Never treat recommendations / carousels below an unavailable collection as its songs. */
    private static JSONObject findMusicShelf(Object node,String renderer,int depth) {
        if(node==null || depth>16) return null;
        if(node instanceof JSONObject) {
            JSONObject object=(JSONObject)node, shelf=object.optJSONObject(renderer); if(shelf!=null) return shelf;
            java.util.Iterator<String> keys=object.keys();
            while(keys.hasNext()) {
                String key=keys.next(); if("musicCarouselShelfRenderer".equals(key) || "header".equals(key) || "menu".equals(key)) continue;
                shelf=findMusicShelf(object.opt(key),renderer,depth+1); if(shelf!=null) return shelf;
            }
        } else if(node instanceof JSONArray) {
            JSONArray array=(JSONArray)node; for(int i=0;i<array.length();i++) { JSONObject shelf=findMusicShelf(array.opt(i),renderer,depth+1); if(shelf!=null) return shelf; }
        }
        return null;
    }

    private static String findChannelId(Object node,int depth) {
        if(node==null || depth>8) return "";
        if(node instanceof JSONObject) {
            JSONObject obj=(JSONObject)node; String id=obj.optString("browseId","");
            if(id.matches("UC[A-Za-z0-9_-]{22}")) return id;
            JSONArray names=obj.names(); if(names!=null) for(int i=0;i<names.length();i++) { String result=findChannelId(obj.opt(names.optString(i)),depth+1); if(result.length()>0) return result; }
        } else if(node instanceof JSONArray) {
            JSONArray arr=(JSONArray)node; for(int i=0;i<arr.length();i++){ String result=findChannelId(arr.opt(i),depth+1); if(result.length()>0) return result; }
        }
        return "";
    }
    public static MediaItem resolvePinnedChannel(Context ctx,String input,String title) throws Exception {
        String value=input==null ? "" : input.trim();
        java.util.regex.Matcher id=Pattern.compile("^(?:https://(?:www\\.|m\\.)?youtube\\.com/channel/)?(UC[A-Za-z0-9_-]{22})/?(?:[?#].*)?$").matcher(value);
        String channel=id.find() ? id.group(1) : "";
        if(channel.length()==0) {
            if(value.matches("@[A-Za-z0-9_.-]{3,100}")) value="https://www.youtube.com/"+value;
            if(!value.matches("https://(?:www\\.|m\\.)?youtube\\.com/@[^/?# ]{3,100}/?")) throw new IOException("Enter a channel UC id, channel URL or @handle");
            JSONObject body=new JSONObject().put("context",new JSONObject().put("client",new JSONObject().put("clientName","WEB").put("clientVersion","2.20260805.01.00"))).put("url",value);
            Map<String,String> hdrs=new LinkedHashMap<String,String>(); hdrs.put("User-Agent",WEB_UA);
            String raw=requestHttpsString(ctx,"POST","https://www.youtube.com/youtubei/v1/navigation/resolve_url?prettyPrint=false",body.toString(),hdrs,14000);
            channel=findChannelId(new JSONObject(raw),0);
        }
        if(channel.length()==0) throw new IOException("YouTube did not resolve this public channel");
        MediaItem item=new MediaItem(); item.channelId=channel; item.title=title==null || title.trim().length()==0 ? value : title.trim(); return item;
    }
    public static List<MediaItem> pinnedChannelVideos(Context ctx,String channel) throws Exception {
        if(!channel.matches("UC[A-Za-z0-9_-]{22}")) throw new IOException("Invalid channel id");
        Map<String,String> headers=new LinkedHashMap<String,String>(); headers.put("User-Agent",WEB_UA);
        String xml=new String(requestHttpsBytes(ctx,"GET","https://www.youtube.com/feeds/videos.xml?channel_id="+channel,null,headers,14000,1024*1024),"UTF-8");
        return parsePinnedChannelFeed(xml,channel);
    }
    static List<MediaItem> parsePinnedChannelFeed(String xml,String channel) throws Exception {
        if(xml==null || xml.length()>1024*1024 || !channel.matches("UC[A-Za-z0-9_-]{22}")) throw new IOException("Invalid channel feed");
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
        if(xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("Unsafe channel feed");
        org.w3c.dom.Document doc=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")));
        org.w3c.dom.NodeList nodes=doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom","entry"); List<MediaItem> result=new ArrayList<MediaItem>();
        for(int i=0;i<nodes.getLength() && result.size()<30;i++) {
            org.w3c.dom.Element entry=(org.w3c.dom.Element)nodes.item(i);
            org.w3c.dom.NodeList ids=entry.getElementsByTagNameNS("http://www.youtube.com/xml/schemas/2015","videoId");
            if(ids.getLength()==0) continue;
            MediaItem item=new MediaItem(); item.videoId=ids.item(0).getTextContent(); if(!item.videoId.matches("[A-Za-z0-9_-]{11}")) continue;
            item.channelId=channel; org.w3c.dom.NodeList titles=entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom","title"); item.title=titles.getLength()>0 ? titles.item(0).getTextContent() : "Video";
            org.w3c.dom.NodeList authors=entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom","name"); item.author=authors.getLength()>0 ? authors.item(0).getTextContent() : "";
            item.thumbnailUrl="https://i.ytimg.com/vi/"+item.videoId+"/hqdefault.jpg"; result.add(item);
        }
        return result;
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
            try { MorpheStreamServer.setCacheDir(MediaCache.directory() != null ? MediaCache.directory() : ctx.getCacheDir()); } catch (Throwable ignored) {}
        }
        int prefIdx = getPreferredSpoofClient(ctx);

        List<Integer> order = new ArrayList<Integer>();
        if (prefIdx >= 1 && prefIdx <= CLIENT_SPECS.length) {
            order.add(Integer.valueOf(prefIdx - 1));
        }
        // VisionOS serves HLS as well as MP4. Do not spend the entire automatic attempt
        // budget on obsolete ANDROID_VR variants while never reaching a different family.
        int[] defaultOrder = new int[]{0, 6, 1};
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
        int directLimit = Math.min(MAX_DIRECT_CLIENT_ATTEMPTS, order.size());
        for (int idx = 0; idx < order.size() && resolved == null; idx++) {
            checkResolutionCancelled();
            if (directAttempts >= directLimit) break;
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
                    if (Thread.currentThread().isInterrupted()) { cancelPlayback(attempt); checkResolutionCancelled(); }
                    String activeProxy = preferMusicClient ? attempt.localAudioProxyUrl : attempt.localVideoProxyUrl;
                    if (activeProxy != null && activeProxy.length() > 0) {
                        resolved = attempt;
                    } else {
                        cancelPlayback(attempt);
                        failures.append(spec[0]).append(':').append(
                                attempt.muxNote.length() > 0 ? attempt.muxNote : "unplayable").append(' ');
                    }
                } else {
                    failures.append(spec[0]).append(":no-stream ");
                }
            } catch (Throwable t) {
                if(t instanceof AccessRequiredException) throw (AccessRequiredException)t;
                failures.append(spec[0]).append(':').append(shortError(t)).append(' ');
            }
        }

        checkResolutionCancelled();
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
                if (Thread.currentThread().isInterrupted()) { cancelPlayback(cloud); checkResolutionCancelled(); }
                if (activeProxy != null && activeProxy.length() > 0) {
                    resolved = cloud;
                } else cancelPlayback(cloud);
            }
        }
        if (resolved == null) {
            throw new Exception("No playable H.264/AAC stream for " + videoId
                    + " (" + failures.toString().trim() + ")"
                    + "\nIf YouTube asks for sign-in or a bot check, open the official video page in a supported browser. This app cannot bypass account/service restrictions.");
        }

        if (quick) {
            reportResolutionProgress(listener, "Stream ready; opening player...");
        } else {
            enrichWithNextSponsorBlockAndDislikes(ctx, resolved);
        }
        if (Thread.currentThread().isInterrupted()) { cancelPlayback(resolved); checkResolutionCancelled(); }
        return resolved;
    }

    private static void checkResolutionCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Playback request cancelled");
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
        if (Thread.currentThread().isInterrupted()) return;
        // Music must not take the HLS/video path merely because that URL was listed first.
        // Probe the real esds/ASC and prepare audio-only classic M4A before trying HLS.
        if (audioOnlyPreferred) {
            for (int i = 0; i < res.audioStreams.size() && i < 3; i++) {
                StreamVariant candidate = res.audioStreams.get(i);
                if (!candidate.isDashTrack()) continue;
                String audio = spoolAudioTrack(ctx, candidate);
                if (audio.length() > 0) {
                    res.localAudioProxyUrl = audio; res.localVideoProxyUrl = audio;
                    res.verifiedAudio = true;
                    res.muxedLocally = true; res.muxNote = "Verified AAC-LC · preparing audio-only classic M4A";
                    return;
                }
                if (Thread.currentThread().isInterrupted()) return;
            }
        }
        if (res.hlsManifestUrl.length() > 0) {
            try {
                bindHlsPlayback(ctx, res, audioOnlyPreferred);
                return;
            } catch (Exception hlsError) {
                res.muxNote = "HLS: " + shortError(hlsError);
            }
        }
        if (Thread.currentThread().isInterrupted()) return;
        final StreamVariant videoTrack = pickBestVideoForMux(res);
        final StreamVariant audioTrack = res.audioStreams.isEmpty() ? null : res.audioStreams.get(0);

        // 1. Progressive muxed MP4 (`itag 18` / `22`) wins when available and verified reachable.
        if (!res.progressiveVideos.isEmpty()) {
            for (int i = 0; i < res.progressiveVideos.size(); i++) {
                StreamVariant bestV = res.progressiveVideos.get(i);
                if (bestV.isHls) continue;
                try {
                    byte[] probe = fetchRangeThroughGateway(ctx, bestV.directHttpsUrl, bestV.userAgent, 0L, 63L);
                    if (isMp4Initialization(probe)) {
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

        // No silent-video fallback: try another client if a real AAC track is unavailable.
        if (!audioOnlyPreferred && res.localVideoProxyUrl.length() == 0)
            res.muxNote = "No playable H.264 + AAC audio pair";

        // 4. Audio-only (YouTube Music) still needs a playable local classic M4A file.
        if (res.localAudioProxyUrl.length() == 0 && audioTrack != null
                && (audioOnlyPreferred || res.localVideoProxyUrl.length() == 0)) {
            if (audioTrack.isDashTrack()) {
                res.localAudioProxyUrl = spoolAudioTrack(ctx, audioTrack);
            } else {
                try {
                    byte[] probe = fetchRangeThroughGateway(ctx, audioTrack.directHttpsUrl, audioTrack.userAgent, 0L, 63L);
                    if (isMp4Initialization(probe)) {
                        res.localAudioProxyUrl = registerPlayableStreamSlot(
                                ctx, audioTrack.directHttpsUrl, audioTrack.userAgent, audioTrack.mimeType);
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (res.localAudioProxyUrl.length() == 0 && res.muxedLocally) {
            res.localAudioProxyUrl = res.localVideoProxyUrl;
        }
        if (audioOnlyPreferred && res.localVideoProxyUrl.length() == 0 && res.localAudioProxyUrl.length() > 0) {
            res.localVideoProxyUrl = res.localAudioProxyUrl;
        }
    }

    private static boolean isMp4Initialization(byte[] probe) {
        return probe != null && probe.length >= 12 && probe[4] == 'f' && probe[5] == 't'
                && probe[6] == 'y' && probe[7] == 'p';
    }

    public static MorpheHls.Transport hlsTransport(final Context ctx, final String userAgent) {
        return new MorpheHls.Transport() {
            @Override public MorpheHls.Response open(String url, String range) throws Exception {
                checkResolutionCancelled();
                ensureGatewayWarm(ctx);
                String target = url.startsWith("https://")
                        ? "http://127.0.0.1:" + gatewayPort() + "/__https/" + url.substring(8) : url;
                final HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection(Proxy.NO_PROXY);
                try {
                    conn.setConnectTimeout(8000); conn.setReadTimeout(20000);
                    conn.setUseCaches(false);
                    conn.setRequestProperty("Connection", "close");
                    conn.setRequestProperty("User-Agent", userAgent);
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    if (range != null) conn.setRequestProperty("Range", range);
                    int status = conn.getResponseCode();
                    long length = -1L;
                    try { length = Long.parseLong(conn.getHeaderField("Content-Length")); } catch (Exception ignored) {}
                    InputStream body = status == 200 || status == 206 ? conn.getInputStream() : conn.getErrorStream();
                    return new MorpheHls.Response(status, conn.getContentType(), length,
                            conn.getHeaderField("Content-Range"), body) {
                        @Override public void close() throws IOException {
                            try { super.close(); } finally { conn.disconnect(); }
                        }
                    };
                } catch (Exception error) { conn.disconnect(); throw error; }
            }
        };
    }

    public static String prepareRadioStream(Context ctx, String original) throws Exception {
        String url = LegacyMediaPolicy.canonicalRadio(original);
        if (url.toLowerCase(Locale.US).contains(".m3u8")) {
            MorpheHls.Transport transport=hlsTransport(ctx,WEB_UA);
            MorpheHls.Selection selected=MorpheHls.select(transport,url,true);
            if(selected.fragmented()) throw new IOException("Live fMP4 radio needs a newer decoder; try the MP3 station.");
            MorpheHls.probe(transport,selected.primary());
            return MorpheHlsRelay.register(selected,transport);
        }
        ensureGatewayWarm(ctx);
        return url.startsWith("https://") ? "http://127.0.0.1:"+gatewayPort()+"/__https/"+url.substring(8)
                : url.startsWith("http://") && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")
                    ? "http://127.0.0.1:"+gatewayPort()+"/__stream/"+url : url;
    }

    private static void bindHlsPlayback(final Context ctx, final ResolvedPlayback res,
                                           final boolean audioOnly) throws Exception {
        final MorpheHls.Transport transport = hlsTransport(ctx, res.hlsUserAgent);
        final MorpheHls.Selection selected = MorpheHls.selectForPlayback(transport, res.hlsManifestUrl, audioOnly);
        String local;
        if (selected.fragmented()) {
            final MorpheRemuxer.SegmentSource video = selected.video != null
                    ? MorpheHls.source(transport, selected.video) : null;
            final MorpheRemuxer.SegmentSource audio = selected.audio != null
                    ? MorpheHls.source(transport, selected.audio) : null;
            local = MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                @Override public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                    MorpheRemuxer.remuxHlsClassicMp4(video, audio, out, progress);
                }
            }, -1L, audioOnly ? "audio/mp4" : "video/mp4");
            res.muxedLocally = true;
            res.muxNote = selected.label + " (preparing classic " + (audioOnly ? "M4A" : "MP4") + ")";
        } else {
            local = MorpheHlsRelay.register(selected, transport);
            res.muxNote = selected.label + " (private TLS relay)";
        }
        if (local.length() == 0) throw new IOException("Could not start private HLS playback");
        res.localVideoProxyUrl = local;
        res.localAudioProxyUrl = local;
    }

    public static void cancelPlayback(ResolvedPlayback playback) {
        if (playback == null) return;
        MorpheStreamServer.cancel(playback.localVideoProxyUrl);
        MorpheStreamServer.cancel(playback.localAudioProxyUrl);
        MorpheHlsRelay.cancel(playback.localVideoProxyUrl);
        MorpheHlsRelay.cancel(playback.localAudioProxyUrl);
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
            // An ftyp box or an mp4a MIME label alone says nothing about AAC-LC support.
            MorpheRemuxer.validateLegacyAudio(aT);
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
        if (video.isDashTrack() || !video.hasAudio) return "";
        return registerPlayableStreamSlot(ctx, video.directHttpsUrl, video.userAgent, video.mimeType);
    }

    /** One bounded recovery when a ROM prepares video but exposes no audio track. */
    public static String startVideoWithAudioFallback(Context ctx, ResolvedPlayback res) {
        if (res == null || res.audioStreams.isEmpty() || !res.audioStreams.get(0).isDashTrack()) return "";
        StreamVariant video = pickBestVideoForMux(res);
        if (video == null || Thread.currentThread().isInterrupted()) return "";
        // Never retry the same HLS playlist or fall back to a raw/silent video-only URL.
        return startMuxForVariant(ctx, res, video);
    }

    /** One alternative container for Android-4 ROMs that reject M4A/HLS with -1010. */
    public static String startAudioCompatibilityFallback(final Context ctx, ResolvedPlayback res) {
        if (res == null || Thread.currentThread().isInterrupted()) return "";
        for (int i = 0; i < res.audioStreams.size() && i < 3; i++) {
            StreamVariant candidate = res.audioStreams.get(i);
            if (!candidate.isDashTrack()) continue;
            try {
                final MorpheRemuxer.Track track = toRemuxTrack(ctx, candidate);
                MorpheRemuxer.validateLegacyAudio(track);
                res.verifiedAudio = true;
                checkResolutionCancelled();
                return MorpheStreamServer.register(0, new MorpheStreamServer.JobBody() {
                    @Override public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                        MorpheRemuxer.remuxSingleTrackAdts(track, out, progress);
                    }
                }, -1L, "audio/aac");
            } catch (Exception unsupported) {
                if (Thread.currentThread().isInterrupted()) return "";
            }
        }
        return "";
    }

    /** Spools a single adaptive audio track (or wraps a progressive URL) for the audio player. */
    public static String startAudioForVariant(Context ctx, StreamVariant audio) {
        if (audio == null || !audio.hasAudio) return "";
        if (audio.isDashTrack()) {
            String spooled = spoolAudioTrack(ctx, audio);
            return spooled; // Never hand an unsupported raw fragmented track to MediaPlayer.
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
        String cpn = playbackNonce(16);
        payload.put("cpn", cpn);
        payload.put("contentCheckOk", true);
        payload.put("racyCheckOk", true);

        Map<String, String> hdrs = clientHeaders(spec, host, visitorData);
        // Match the maintained visionOS request, not a WEB-metadata player request:
        // mobile t/id query parameters, per-client visitorData, WATCH/MOBILE context,
        // native app User-Agent and a separate 16-character playback nonce.
        String url = host + "/youtubei/v1/player?prettyPrint=false";
        if ("VISIONOS".equals(spec[2])) url += "&t=" + playbackNonce(12) + "&id=" + videoId;
        String raw = requestHttpsString(ctx, "POST", url, payload.toString(), hdrs, Math.max(1, timeoutMs));
        JSONObject response = new JSONObject(raw);
        JSONObject status = response.optJSONObject("playabilityStatus");
        if (status != null && !"OK".equalsIgnoreCase(status.optString("status", ""))) {
            String reason=status.optString("reason", "No playable media was returned");
            String denial=(status.optString("status","")+" "+reason).toLowerCase(Locale.US);
            if(denial.contains("login_required") || denial.contains("sign in") || denial.contains("not a bot") || denial.contains("age_check"))
                throw new AccessRequiredException(reason);
            throw new IOException(status.optString("status", "UNAVAILABLE") + ": " + reason);
        }
        ResolvedPlayback parsed = parseInnerTubePlayerResponse(response, videoId, spec[0], spec[4]);
        if (parsed != null) {
            List<StreamVariant> streams = new ArrayList<StreamVariant>();
            streams.addAll(parsed.progressiveVideos);
            streams.addAll(parsed.videoOnlyStreams);
            streams.addAll(parsed.audioStreams);
            for (StreamVariant stream : streams) {
                if (!stream.directHttpsUrl.contains("cpn="))
                    stream.directHttpsUrl += (stream.directHttpsUrl.contains("?") ? "&" : "?") + "cpn=" + cpn;
            }
        }
        return parsed;
    }

    private static String playbackNonce(int length) {
        StringBuilder nonce = new StringBuilder(length);
        for (int i = 0; i < length; i++) nonce.append(NONCE_ALPHABET.charAt(PLAYBACK_RANDOM.nextInt(64)));
        return nonce.toString();
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
                String url = directUrlFromFormat(f);
                if (url.length() == 0) continue;
                String mime = f.optString("mimeType", "video/mp4");
                // MT8125 hardware decoder excels at MP4/H.264 (`avc1` + `mp4a`); avoid AV1 (`av01`) & VP9 (`vp9`)
                if (!mime.startsWith("video/mp4")
                        || !LegacyMediaPolicy.h264(mime,f.optInt("height",360),f.optInt("fps",30))
                        || !LegacyMediaPolicy.aacLc(mime,f.optInt("audioChannels",2),f.optInt("audioSampleRate",44100))) continue;
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
                String url = directUrlFromFormat(f);
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
                        && LegacyMediaPolicy.aacLc(mime,f.optInt("audioChannels",2),f.optInt("audioSampleRate",44100));
                if (isVideoMp4 && sv.height <= 720 && f.optInt("fps", 30) <= 30) {
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

        // HLS is a separate protocol, not a fake progressive MP4. The binder resolves
        // H.264/AAC renditions, probes actual media and relays EVERY URI through our TLS
        // engine; finite fMP4 renditions are remuxed to classic MP4/M4A for old Stagefright.
        out.hlsManifestUrl = sd.optString("hlsManifestUrl", "");
        out.hlsUserAgent = clientUa != null ? clientUa : WEB_UA;

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
                return Math.abs(a.bitrate-128000) - Math.abs(b.bitrate-128000); // Prefer standard AAC-LC 128k, not the largest stream.
            }
        });
        return out;
    }

    /**
     * Extract a directly usable URL from a YouTube format. Some player responses expose a
     * clear `sig`/`signature` inside `signatureCipher` instead of a top-level `url`; accept
     * those without attempting to bypass an encrypted `s` signature (which needs the site's
     * current player script and must be reported as unavailable rather than guessed).
     */
    private static String directUrlFromFormat(JSONObject format) {
        if (format == null) return "";
        String direct = format.optString("url", "");
        if (direct.length() > 0) return direct;
        String cipher = format.optString("signatureCipher", "");
        if (cipher.length() == 0) cipher = format.optString("cipher", "");
        if (cipher.length() == 0) return "";
        try {
            Uri params = Uri.parse("https://format.invalid/?" + cipher);
            String url = params.getQueryParameter("url");
            String signature = params.getQueryParameter("sig");
            if (signature == null || signature.length() == 0) {
                signature = params.getQueryParameter("signature");
            }
            if (url == null || url.length() == 0 || signature == null || signature.length() == 0) return "";
            String key = params.getQueryParameter("sp");
            if (key == null || !key.matches("[A-Za-z0-9_-]{1,32}")) key = "signature";
            return url + (url.indexOf('?') >= 0 ? "&" : "?") + key + "=" + Uri.encode(signature);
        } catch (Throwable ignored) {
            return "";
        }
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
        return fetchPlayerFromCloudInstances(ctx, videoId, 10000, 2, null);
    }

    private static ResolvedPlayback fetchPlayerFromCloudInstances(Context ctx, String videoId,
                                                                  int timeoutMs, int maxAttempts,
                                                                  ResolutionProgressListener listener) {
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        int attempts = 0;

        // Tier A: Public Piped API instances. The interactive video player has a larger
        // bounded attempt budget than background YouTube Music; then try Invidious below.
        int pipedLimit = maxAttempts > 2 ? PIPED_INSTANCES.length : Math.min(1, PIPED_INSTANCES.length);
        for (int i = 0; i < pipedLimit && attempts < maxAttempts; i++) {
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
                out.hlsManifestUrl = j.optString("hls", "");

                JSONArray vArr = j.optJSONArray("videoStreams");
                if (vArr != null) {
                    for (int k = 0; k < vArr.length(); k++) {
                        JSONObject vf = vArr.optJSONObject(k);
                        if (vf != null && !vf.optBoolean("videoOnly", true)
                                && vf.optString("mimeType", "").contains("mp4")
                                && vf.optString("url", "").length() > 0
                                && LegacyMediaPolicy.h264(vf.optString("mimeType",vf.optString("type"))+vf.optString("codec"),vf.optInt("height",360),vf.optInt("fps",30))) {
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
                                && af.optString("url", "").length() > 0
                                && LegacyMediaPolicy.aacLc(af.optString("mimeType",af.optString("type"))+af.optString("codec"),af.optInt("audioChannels",2),af.optInt("audioSampleRate",44100))) {
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
                if (out.hasAnyStream()) return out;
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
                                && vf.optString("url", "").length() > 0
                                && LegacyMediaPolicy.h264(vf.optString("mimeType",vf.optString("type"))+vf.optString("codec"),vf.optInt("height",360),vf.optInt("fps",30))) {
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
                                && af.optString("url", "").length() > 0
                                && LegacyMediaPolicy.aacLc(af.optString("mimeType",af.optString("type"))+af.optString("codec"),af.optInt("audioChannels",2),af.optInt("audioSampleRate",44100))) {
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
                if (out.hasAnyStream()) return out;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<MediaItem> searchCloudFallback(Context ctx, String query, boolean musicOnly) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("User-Agent", WEB_UA);
        String filter = musicOnly ? "music_songs" : "videos";
        // Try public mirrors sequentially only after the official web endpoint failed.
        // Keep YouTube Music on the already-tested first mirror; video search can use the
        // configured alternatives because the old single endpoint is not reliably available.
        int pipedCount = musicOnly ? 1 : PIPED_INSTANCES.length;
        for (int i = 0; i < pipedCount; i++) {
            try {
                String raw = requestHttpsString(ctx, "GET",
                        PIPED_INSTANCES[i] + "/search?q=" + Uri.encode(query) + "&filter=" + filter,
                        null, hdrs, 6000);
                JSONArray items = null;
                try { items = new JSONObject(raw).optJSONArray("items"); }
                catch (Exception arrayResponse) { items = new JSONArray(raw); }
                appendCloudSearchResults(items, out, musicOnly);
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {}
        }
        if (!musicOnly) {
            for (int i = 0; i < INVIDIOUS_INSTANCES.length; i++) {
                try {
                    String raw = requestHttpsString(ctx, "GET", INVIDIOUS_INSTANCES[i]
                            + "/api/v1/search?q=" + Uri.encode(query) + "&type=video", null, hdrs, 6000);
                    appendCloudSearchResults(new JSONArray(raw), out, false);
                    if (!out.isEmpty()) return out;
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static void appendCloudSearchResults(JSONArray items, List<MediaItem> output, boolean musicOnly) {
        if (items == null) return;
        for (int k = 0; k < items.length() && output.size() < 80; k++) {
            JSONObject it = items.optJSONObject(k);
            if (it == null) continue;
            String u = it.optString("url", "");
            String id = it.optString("videoId", "");
            String vid = id.matches("[A-Za-z0-9_-]{11}") ? id
                    : extractVideoIdFromUrlOrQuery(u.startsWith("http") ? u : "https://www.youtube.com" + u);
            if (vid == null) continue;
            MediaItem m = new MediaItem();
            m.videoId = vid;
            m.title = it.optString("title", "Video");
            m.author = it.optString("uploaderName", it.optString("uploader", it.optString("author", "")));
            m.durationSec = it.optLong("duration", it.optLong("lengthSeconds", 0L));
            m.durationText = NowPlayingManager.formatDurationMs(m.durationSec * 1000L);
            m.thumbnailUrl = it.optString("thumbnail", "");
            if (m.thumbnailUrl.length() == 0) {
                JSONArray thumbs = it.optJSONArray("videoThumbnails");
                if (thumbs != null && thumbs.length() > 0) {
                    JSONObject thumb = thumbs.optJSONObject(thumbs.length() - 1);
                    if (thumb != null) m.thumbnailUrl = thumb.optString("url", "");
                }
            }
            if (m.thumbnailUrl.length() == 0) m.thumbnailUrl = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
            m.isMusicTrack = musicOnly;
            output.add(m);
        }
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

    /** Compatibility entry: a video-only DASH track is not an offline video by itself. */
    public static File downloadStreamOffline(Context ctx,StreamVariant variant,String title,
                                             boolean audioOnly,DownloadProgressListener listener) throws Exception {
        if(variant==null) throw new IOException("No downloadable stream");
        ResolvedPlayback selection=new ResolvedPlayback(); selection.title=title;
        if(variant.hasVideo && variant.hasAudio) selection.progressiveVideos.add(variant);
        else if(variant.hasAudio) selection.audioStreams.add(variant);
        else selection.videoOnlyStreams.add(variant);
        return downloadResolvedOffline(ctx,selection,title,audioOnly,listener);
    }

    /** Real A/V merge; atomic completion; no overwriting recordings or exposing .part as playable. */
    public static File downloadResolvedOffline(final Context ctx,ResolvedPlayback resolved,String title,
                                                boolean audioOnly,final DownloadProgressListener listener) throws Exception {
        if(resolved==null) throw new IOException("Resolve a playable video first");
        if(MediaCache.directory()!=null)MorpheStreamServer.setCacheDir(MediaCache.directory());
        File dir=new File(Environment.getExternalStorageDirectory(),audioOnly ? "Music/YouTubeMusic" : "Movies/YouTube");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Download folder is unavailable");
        String name=(title==null ? "media" : title).replaceAll("[^A-Za-z0-9 _.-]","").trim();
        if(name.length()==0 || name.equals(".") || name.equals("..")) name="media";
        if(name.length()>60) name=name.substring(0,60).trim();
        final File partial=File.createTempFile(name+"-arc7-", ".part",dir);
        File target=new File(dir,partial.getName().substring(0,partial.getName().length()-5)+(audioOnly ? ".m4a" : ".mp4"));
        boolean complete=false;
        final MediaCache.Lease cacheLease = MediaCache.lease();
        DebugLog.event("media.download_started");
        final MorpheRemuxer.Progress progress=new MorpheRemuxer.Progress() {
            public void onBytes(long done,long total) { if(listener!=null) listener.onProgress(total>0 ? (int)Math.min(99,done*100/total) : -1,done,total); }
            public void onStage(String stage) {}
        };
        RandomAccessFile out=null;
        try {
            checkResolutionCancelled();
            out=new RandomAccessFile(partial,"rw");
            StreamVariant audio=resolved.audioStreams.isEmpty() ? null : resolved.audioStreams.get(0);
            StreamVariant video=pickBestVideoForMux(resolved);
            if(audioOnly && audio!=null && audio.hasAudio && !audio.hasVideo) {
                MorpheRemuxer.Track track=toRemuxTrack(ctx,audio);
                MorpheRemuxer.copyTrack(track,out,progress);
            } else if(!audioOnly && !resolved.progressiveVideos.isEmpty()) {
                StreamVariant combined=resolved.progressiveVideos.get(0);
                if(!combined.hasAudio || !combined.hasVideo || combined.isHls) throw new IOException("No combined H.264/AAC file");
                MorpheRemuxer.copyTrack(toRemuxTrack(ctx,combined),out,progress);
            } else if(!audioOnly && video!=null && audio!=null && audio.hasAudio) {
                MorpheRemuxer.remuxClassicMp4(toRemuxTrack(ctx,video),toRemuxTrack(ctx,audio),out,progress);
            } else if(resolved.hlsManifestUrl.length()>0) {
                MorpheHls.Transport transport=hlsTransport(ctx,resolved.hlsUserAgent);
                MorpheHls.Selection selected=MorpheHls.select(transport,resolved.hlsManifestUrl,audioOnly);
                if(!selected.fragmented()) throw new IOException("This live/TS HLS stream is playback-only; no offline MP4/M4A is available");
                MorpheRemuxer.remuxHlsClassicMp4(selected.video==null ? null : MorpheHls.source(transport,selected.video),
                        selected.audio==null ? null : MorpheHls.source(transport,selected.audio),out,progress);
            } else throw new IOException(audioOnly ? "No separate AAC audio track is available for M4A" : "Video download needs a real H.264 + AAC pair");
            out.getFD().sync(); out.close(); out=null;
            checkResolutionCancelled(); MorpheRemuxer.validateClassicFile(partial,audioOnly);
            checkResolutionCancelled();
            if(target.exists() || !partial.renameTo(target)) throw new IOException("Could not finish download; no existing file was replaced");
            complete=true;
            if(listener!=null) listener.onProgress(100,target.length(),target.length());
            return target;
        } finally { if(out!=null) try{out.close();}catch(Exception ignored){} if(!complete) partial.delete(); cacheLease.close(); DebugLog.event(complete ? "media.download_completed" : "media.download_cancelled_or_failed"); }
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
                m.browseId=o.optString("browse",""); m.playlistId=o.optString("playlist","");
                m.channelId=o.optString("channel",""); m.collectionType=o.optString("kind","");
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
            if (savedIdentity(cur.get(i)).equals(savedIdentity(item)) && savedIdentity(item).length()>0) {
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
        if(listKey.startsWith("ytm_local_")) cur.add(item); else cur.add(0, item);
        while (cur.size() > 80) cur.remove(cur.size() - 1);
        saveList(ctx, listKey, cur);
    }

    private static String savedIdentity(MediaItem item) {
        if (item.isArtist()) return "artist:" + item.browseId;
        if(item.isCollection()) {
            if("album".equals(item.collectionType) && item.browseId.length()>0) return "album:"+item.browseId;
            String playlist=item.playlistId.length()>0 ? item.playlistId : item.browseId.startsWith("VL") ? item.browseId.substring(2) : item.browseId;
            return "playlist:"+playlist;
        }
        return item.videoId.length()>0 ? "video:"+item.videoId : item.channelId.length()>0 ? "channel:"+item.channelId : "";
    }
    public static void saveList(Context ctx, String listKey, List<MediaItem> items) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < items.size(); i++) {
                MediaItem m = items.get(i);
                JSONObject o = new JSONObject();
                o.put("v", m.videoId);
                o.put("browse",m.browseId).put("playlist",m.playlistId).put("channel",m.channelId).put("kind",m.collectionType);
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
