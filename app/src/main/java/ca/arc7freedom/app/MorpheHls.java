package ca.arc7freedom.app;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HLS selection/URI handling shared by the tablet and offline JVM regression tests. */
public final class MorpheHls {
    public static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    public static final int MAX_SEGMENT_BYTES = 16 * 1024 * 1024;

    /** Implemented by the owning APK's TLS gateway, NEVER Android's obsolete HTTPS stack. */
    public interface Transport {
        Response open(String url, String range) throws Exception;
    }

    public static class Response implements Closeable {
        public final int status;
        public final String mime;
        public final long length;
        public final String contentRange;
        public final InputStream body;
        public Response(int status, String mime, long length, String contentRange, InputStream body) {
            this.status = status;
            this.mime = mime;
            this.length = length;
            this.contentRange = contentRange;
            this.body = body;
        }
        @Override public void close() throws IOException { if (body != null) body.close(); }
    }

    public static final class Part {
        public final String url;
        public final String range;
        Part(String url, String range) { this.url = url; this.range = range; }
    }

    public static final class Playlist {
        public String url;
        public String text;
        public Part init;
        public boolean live = true;
        public boolean encrypted;
        public boolean discontinuity;
        public long durationMs;
        public final List<Part> segments = new ArrayList<Part>();
    }

    public static final class Selection {
        public Playlist video;
        public Playlist audio;
        public String label = "HLS H.264/AAC";
        public String codecs = "";
        public int bandwidth;
        public Playlist primary() { return video != null ? video : audio; }
        public boolean fragmented() {
            return (video != null && video.init != null) || (audio != null && audio.init != null);
        }
    }

    private static final class Variant {
        String url;
        String codecs;
        String audioGroup;
        int height;
        int bandwidth;
        double fps;
        boolean hasVideo;
    }

    public interface UriMapper { String local(String absoluteUrl, boolean playlist) throws Exception; }
    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))(?:,|$)");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private MorpheHls() {}

    public static Map<String, String> attributes(String line) {
        int colon = line.indexOf(':');
        Matcher m = ATTR.matcher(colon < 0 ? line : line.substring(colon + 1));
        Map<String, String> out = new LinkedHashMap<String, String>();
        while (m.find()) out.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3).trim());
        return out;
    }

    static String absolute(String base, String relative) throws IOException {
        if (relative == null || relative.length() == 0 || relative.indexOf('\r') >= 0 || relative.indexOf('\n') >= 0)
            throw new IOException("Invalid HLS URI");
        URL url = new URL(new URL(base), relative);
        if (!("https".equals(url.getProtocol()) || "http".equals(url.getProtocol())) || url.getUserInfo() != null)
            throw new IOException("Unsupported HLS URI scheme");
        return url.toExternalForm();
    }

    public static byte[] read(Transport transport, Part part, int limit) throws Exception {
        checkCancelled();
        Response response = transport.open(part.url, part.range);
        try {
            if (response.status != 200 && response.status != 206) throw new IOException("HLS HTTP " + response.status);
            if (part.range != null && response.status != 206) throw new IOException("HLS byte range was ignored");
            if (response.length > limit) throw new IOException("HLS resource exceeds size limit");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while (response.body != null && (n = response.body.read(buffer)) != -1) {
                checkCancelled();
                if (out.size() + n > limit) throw new IOException("HLS resource exceeds size limit");
                out.write(buffer, 0, n);
            }
            checkCancelled();
            if (out.size() == 0) throw new IOException("Empty HLS resource");
            if (response.length >= 0 && out.size() != response.length) throw new IOException("Truncated HLS resource");
            return out.toByteArray();
        } finally { response.close(); }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("HLS request cancelled");
    }

    static String text(Transport transport, String url) throws Exception {
        String text = new String(read(transport, new Part(url, null), MAX_PLAYLIST_BYTES), "UTF-8");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.trim().startsWith("#EXTM3U")) throw new IOException("Not an HLS playlist (possibly an error page)");
        return text;
    }

    public static Selection select(Transport transport, String masterUrl, boolean audioOnly) throws Exception {
        return select(transport, masterUrl, audioOnly, 0, false);
    }

    /**
     * Android 4.2 LiveSession does not implement EXT-X-MEDIA/AUDIO rendition switching.
     * Prefer actual embedded audio, or separate fMP4 tracks that our remuxer can combine.
     * Try at most three compatible renditions, then let the caller fall back to DASH/a
     * different client. Never advertise a video-only TS playlist as successful playback.
     */
    public static Selection selectForPlayback(Transport transport, String masterUrl, boolean audioOnly) throws Exception {
        return select(transport, masterUrl, audioOnly, 0, true);
    }

    private static Selection select(Transport transport, String url, boolean audioOnly, int depth,
                                    boolean verifyLegacy) throws Exception {
        if (depth > 3) throw new IOException("Too many nested HLS playlists");
        String raw = text(transport, url);
        if (!raw.contains("#EXT-X-STREAM-INF:")) {
            Selection direct = new Selection();
            if (audioOnly) direct.audio = parseMedia(url, raw); else direct.video = parseMedia(url, raw);
            if (verifyLegacy) requireLegacyTracks(transport, direct, audioOnly);
            return direct;
        }
        final List<Variant> variants = new ArrayList<Variant>();
        final List<Map<String, String>> audio = new ArrayList<Map<String, String>>();
        Map<String, String> next = null;
        for (String source : raw.split("\n")) {
            String line = source.trim();
            if (line.startsWith("#EXT-X-SESSION-KEY:")) validateKey(line);
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attrs = attributes(line);
                if ("AUDIO".equals(attrs.get("TYPE")) && attrs.containsKey("URI")) audio.add(attrs);
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                next = attributes(line);
            } else if (!line.startsWith("#") && line.length() > 0 && next != null) {
                Variant v = new Variant();
                v.codecs = value(next, "CODECS").toLowerCase(Locale.US);
                v.audioGroup = value(next, "AUDIO");
                v.hasVideo = v.codecs.contains("avc1") || v.codecs.contains("avc3");
                String resolution = value(next, "RESOLUTION");
                try { v.height = Integer.parseInt(resolution.substring(resolution.indexOf('x') + 1)); } catch (Exception ignored) {}
                try { v.fps = Double.parseDouble(value(next, "FRAME-RATE")); } catch (Exception ignored) {}
                try { v.bandwidth = Integer.parseInt(value(next, "BANDWIDTH")); } catch (Exception ignored) {}
                v.url = absolute(url, line);
                // No codec guessing: the MT8125 has neither AV1, VP9, HEVC nor Opus decoding.
                boolean aac = LegacyMediaPolicy.aacLc(v.codecs,2,48000);
                boolean codecOk = aac || (v.hasVideo && v.audioGroup.length()>0);
                boolean unsupported = v.codecs.contains("av01") || v.codecs.contains("vp09")
                        || v.codecs.contains("vp9") || v.codecs.contains("hev1") || v.codecs.contains("hvc1")
                        || v.codecs.contains("opus") || v.codecs.contains("ac-3") || v.codecs.contains("ec-3");
                if (codecOk && !unsupported && v.height <= 720 && v.fps <= 30.1
                        && (audioOnly || v.hasVideo)) variants.add(v);
                next = null;
            }
        }
        final boolean wantAudio = audioOnly;
        Collections.sort(variants, new Comparator<Variant>() {
            @Override public int compare(Variant a, Variant b) {
                if (wantAudio) {
                    if (a.hasVideo != b.hasVideo) return a.hasVideo ? 1 : -1;
                    return a.bandwidth < b.bandwidth ? -1 : (a.bandwidth == b.bandwidth ? 0 : 1);
                }
                // Embedded audio avoids Android 4.2's unsupported alternate-audio master.
                // Keep it ahead of separate renditions within the bounded probe budget.
                if ((a.audioGroup.length() == 0) != (b.audioGroup.length() == 0))
                    return a.audioGroup.length() == 0 ? -1 : 1;
                // Prefer SD for this 2013 decoder/network; offer 720p only when SD isn't served.
                int sa = a.height > 0 && a.height <= 480 ? 1000 + a.height : a.height;
                int sb = b.height > 0 && b.height <= 480 ? 1000 + b.height : b.height;
                return sb - sa;
            }
        });
        if (variants.isEmpty()) throw new IOException("No Android 4.2 H.264/AAC HLS rendition (720p/30fps maximum)");
        Exception failure = null;
        int limit = verifyLegacy ? Math.min(3, variants.size()) : 1;
        for (int index = 0; index < limit; index++) {
            checkCancelled();
            Variant chosen = variants.get(index);
            try {
                Map<String, String> rendition = null;
                for (Map<String, String> candidate : audio) {
                    if (!chosen.audioGroup.equals(value(candidate, "GROUP-ID"))) continue;
                    if (rendition == null || "YES".equals(candidate.get("DEFAULT"))) rendition = candidate;
                }
                Selection s;
                if (audioOnly && rendition != null) {
                    s = select(transport, absolute(url, rendition.get("URI")), true, depth + 1, false);
                    s.label = "HLS AAC audio";
                } else {
                    s = select(transport, chosen.url, audioOnly, depth + 1, false);
                    s.label = audioOnly ? "HLS AAC audio" : "HLS " + chosen.height + "p H.264/AAC";
                    s.codecs = chosen.codecs; s.bandwidth = chosen.bandwidth;
                    if (!audioOnly && rendition != null) {
                        Selection a = select(transport, absolute(url, rendition.get("URI")), true, depth + 1, false);
                        s.audio = a.primary();
                    }
                }
                if (verifyLegacy) requireLegacyTracks(transport, s, audioOnly);
                return s;
            } catch (Exception unavailable) {
                checkCancelled();
                if (!verifyLegacy) throw unavailable;
                failure = unavailable;
            }
        }
        throw new IOException("No HLS rendition with playable audio: "
                + (failure != null ? failure.getMessage() : "no compatible stream"), failure);
    }

    /** Inspect actual tracks, not just CODECS declarations or the first TS sync byte. */
    static void requireLegacyTracks(Transport transport, Selection selection, boolean audioOnly) throws Exception {
        if (!selection.fragmented() && selection.video != null && selection.audio != null)
            throw new IOException("Android 4.2 cannot combine separate MPEG-TS HLS audio/video; a combined stream or DASH remux is required");
        if (!audioOnly && selection.video == null) throw new IOException("HLS has no video track");
        if (selection.video != null) requireTracks(transport, selection.video, !audioOnly,
                selection.audio == null);
        if (selection.audio != null) requireTracks(transport, selection.audio, false, true);
    }

    private static void requireTracks(Transport transport, Playlist playlist, boolean video, boolean audio) throws Exception {
        if (playlist.encrypted) throw new IOException("Cannot verify audio in encrypted HLS; trying another stream");
        if (playlist.init != null) {
            byte[] init = read(transport, playlist.init, MAX_PLAYLIST_BYTES);
            if (video && !MorpheRemuxer.hasTrack(init, true)) throw new IOException("HLS initialization has no H.264 video track");
            if (audio && !MorpheRemuxer.hasTrack(init, false)) throw new IOException("HLS initialization has no AAC audio track");
            return;
        }
        Part first = playlist.segments.get(0);
        Response response = transport.open(first.url, first.range != null ? first.range : "bytes=0-65535");
        byte[] data;
        try {
            if (response.status != 200 && response.status != 206) throw new IOException("HLS media HTTP " + response.status);
            if (first.range != null && response.status != 206) throw new IOException("HLS byte range was ignored");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while (out.size() < 65536 && response.body != null
                    && (n = response.body.read(buffer, 0, Math.min(buffer.length, 65536 - out.size()))) != -1) {
                checkCancelled(); out.write(buffer, 0, n);
            }
            checkCancelled(); data = out.toByteArray();
        } finally { response.close(); }
        if (data.length >= 376 && data[0] == 0x47 && data[188] == 0x47) {
            LegacyTsProbe.Tracks tracks = LegacyTsProbe.tracks(data);
            if (video && !tracks.video) throw new IOException("HLS transport stream has no H.264 video track");
            if (audio && !tracks.audio) throw new IOException("HLS transport stream has no AAC audio track");
        } else {
            int offset = 0;
            if (data.length >= 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
                offset = 10 + ((data[6] & 127) << 21) + ((data[7] & 127) << 14)
                        + ((data[8] & 127) << 7) + (data[9] & 127);
            }
            boolean aac = offset <= data.length - 7 && (data[offset] & 255) == 255
                    && (data[offset + 1] & 246) == 240 && (data[offset + 2] >>> 6 & 3) == 1;
            if (video || !aac) throw new IOException("HLS has no verified H.264/AAC-LC tracks");
        }
    }

    private static String value(Map<String, String> map, String name) {
        String v = map.get(name); return v == null ? "" : v;
    }

    private static void validateKey(String line) throws IOException {
        Map<String, String> attrs = attributes(line);
        String method = value(attrs, "METHOD");
        if (!("NONE".equals(method) || ("AES-128".equals(method)
                && (!attrs.containsKey("KEYFORMAT") || "identity".equals(attrs.get("KEYFORMAT"))))))
            throw new IOException("Encrypted/DRM HLS is not supported");
    }

    public static Playlist parseMedia(String url, String raw) throws Exception {
        if (!raw.trim().startsWith("#EXTM3U")) throw new IOException("Not an HLS media playlist");
        Playlist out = new Playlist(); out.url = url; out.text = raw;
        String rangeSpec = null;
        long nextOffset = 0L;
        String previousUrl = "";
        for (String source : raw.split("\n")) {
            String line = source.trim();
            if (line.startsWith("#EXT-X-KEY:")) {
                validateKey(line);
                if (!"NONE".equals(attributes(line).get("METHOD"))) out.encrypted = true;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attrs = attributes(line);
                Part init = new Part(absolute(url, attrs.get("URI")), range(attrs.get("BYTERANGE"), 0L));
                if (out.init != null && (!out.init.url.equals(init.url) || !same(out.init.range, init.range)))
                    throw new IOException("Changing HLS initialization maps is unsupported");
                out.init = init;
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                rangeSpec = line.substring(line.indexOf(':') + 1);
            } else if (line.startsWith("#EXTINF:")) {
                try { out.durationMs += (long) (1000.0 * Double.parseDouble(line.substring(8).split(",")[0])); }
                catch (Exception badDuration) { throw new IOException("Invalid HLS segment duration"); }
            } else if (line.equals("#EXT-X-ENDLIST")) out.live = false;
            else if (line.equals("#EXT-X-DISCONTINUITY")) out.discontinuity = true;
            else if (!line.startsWith("#") && line.length() > 0) {
                String segmentUrl = absolute(url, line);
                if (rangeSpec != null && !rangeSpec.contains("@") && !segmentUrl.equals(previousUrl))
                    throw new IOException("Implicit HLS byte range has no preceding resource");
                String r = range(rangeSpec, nextOffset);
                out.segments.add(new Part(segmentUrl, r));
                previousUrl = segmentUrl;
                if (r != null) nextOffset = Long.parseLong(r.substring(r.indexOf('-') + 1)) + 1L;
                rangeSpec = null;
                if (out.segments.size() > 20000) throw new IOException("HLS playlist is too large");
            }
        }
        if (out.segments.isEmpty()) throw new IOException("HLS playlist has no media segments");
        if (out.init != null && (out.live || out.encrypted || out.discontinuity))
            throw new IOException("Live, encrypted or discontinuous fMP4 HLS needs a newer Android decoder");
        return out;
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String range(String spec, long implicitOffset) throws IOException {
        if (spec == null) return null;
        try {
            String[] parts = spec.split("@");
            long length = Long.parseLong(parts[0]);
            long start = parts.length > 1 ? Long.parseLong(parts[1]) : implicitOffset;
            if (length <= 0L || start < 0L || start > Long.MAX_VALUE - length) throw new NumberFormatException();
            return "bytes=" + start + "-" + (start + length - 1L);
        } catch (Exception e) { throw new IOException("Invalid HLS byte range"); }
    }

    /** Rewrite segments AND quoted URI attributes (AES keys, maps, child playlists). */
    public static String rewrite(Playlist playlist, UriMapper mapper) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String source : playlist.text.split("\n")) {
            String line = source.trim();
            if (line.length() == 0) continue;
            if (!line.startsWith("#")) line = mapper.local(absolute(playlist.url, line), false);
            else {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer changed = new StringBuffer();
                while (m.find()) {
                    String local = mapper.local(absolute(playlist.url, m.group(1)), line.startsWith("#EXT-X-MEDIA:"));
                    m.appendReplacement(changed, Matcher.quoteReplacement("URI=\"" + local + "\""));
                }
                m.appendTail(changed);
                line = changed.toString();
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Validate actual media bytes, not just a successful metadata/player response. */
    public static void probe(Transport transport, Playlist playlist) throws Exception {
        checkCancelled();
        Part first = playlist.init != null ? playlist.init : playlist.segments.get(0);
        Response r = transport.open(first.url, first.range != null ? first.range : "bytes=0-1023");
        try {
            if (r.status != 200 && r.status != 206) throw new IOException("HLS media HTTP " + r.status);
            byte[] data = new byte[564]; int n = 0;
            while (r.body != null && n < data.length) {
                checkCancelled();
                int count = r.body.read(data, n, data.length - n); if (count < 0) break; n += count;
            }
            if (n < 8) throw new IOException("Empty HLS media");
            if (playlist.init != null) {
                String box = new String(data, 4, 4, "ISO-8859-1");
                if (!("ftyp".equals(box) || "moov".equals(box))) throw new IOException("Invalid HLS MP4 initialization");
            } else if (!playlist.encrypted) {
                boolean ts = n > 188 && data[0] == 0x47 && data[188] == 0x47;
                boolean aac = (data[0] & 255) == 255 && (data[1] & 246) == 240;
                boolean id3 = data[0] == 'I' && data[1] == 'D' && data[2] == '3';
                if (!ts && !aac && !id3) throw new IOException("HLS is not legacy MPEG-TS/AAC (unmapped fMP4 is unsupported)");
            }
        } finally { r.close(); }
    }

    public static MorpheRemuxer.SegmentSource source(final Transport transport, final Playlist playlist) throws Exception {
        if (playlist.init == null) throw new IOException("HLS MP4 initialization map is missing");
        final byte[] init = read(transport, playlist.init, MAX_PLAYLIST_BYTES);
        return new MorpheRemuxer.SegmentSource() {
            private int index;
            @Override public byte[] init() { return init; }
            @Override public long durationMs() { return playlist.durationMs; }
            @Override public byte[] next() throws Exception {
                if (Thread.currentThread().isInterrupted()) throw new IOException("HLS preparation cancelled");
                return index < playlist.segments.size()
                        ? read(transport, playlist.segments.get(index++), MAX_SEGMENT_BYTES) : null;
            }
        };
    }
}
