package ca.arc7freedom.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Local M4A sidecar metadata and deterministic artist/album ordered offline library. */
final class OfflineMusicLibrary {
    private OfflineMusicLibrary() {}
    private static final String ROOT = "Music/YouTubeMusic";

    static File root() { return new File(Environment.getExternalStorageDirectory(), ROOT); }

    static File organizeAndTag(File downloaded, MorpheEngine.MediaItem item) throws Exception {
        if (downloaded == null || item == null) return downloaded;
        String artist = clean(item.author, "Unknown Artist");
        String album = clean(item.album, "Unknown Album");
        File folder = new File(new File(root(), artist), album);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Could not create offline artist/album folders");
        File target = new File(folder, downloaded.getName());
        if (!downloaded.equals(target) && !downloaded.renameTo(target)) {
            // Keep a complete download usable even if an external-storage rename fails.
            target = downloaded;
        }
        item.localFilePath = target.getAbsolutePath();
        writeSidecar(target, item);
        return target;
    }

    static List<MorpheEngine.MediaItem> scan(Context context) {
        List<File> files = new ArrayList<File>();
        collect(root(), files, 0);
        List<MorpheEngine.MediaItem> items = new ArrayList<MorpheEngine.MediaItem>();
        for (File file : files) {
            MorpheEngine.MediaItem item = readMetadata(file);
            item.localFilePath = file.getAbsolutePath();
            item.videoId = "offline_" + Integer.toHexString(file.getAbsolutePath().hashCode());
            item.isMusicTrack = true;
            items.add(item);
        }
        Collections.sort(items, new Comparator<MorpheEngine.MediaItem>() {
            @Override public int compare(MorpheEngine.MediaItem a, MorpheEngine.MediaItem b) {
                int order = compareText(a.author, b.author);
                if (order == 0) order = compareText(a.album, b.album);
                if (order == 0) order = compareText(a.title, b.title);
                return order;
            }
        });
        return items;
    }

    private static int compareText(String a, String b) {
        return (a == null ? "" : a).compareToIgnoreCase(b == null ? "" : b);
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 8 || dir == null || !dir.isDirectory() || out.size() >= 3000) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, out, depth + 1);
            else if (child.isFile() && child.getName().toLowerCase(Locale.US).endsWith(".m4a")) out.add(child);
        }
    }

    private static MorpheEngine.MediaItem readMetadata(File file) {
        MorpheEngine.MediaItem item = new MorpheEngine.MediaItem();
        item.title = stripExtension(file.getName());
        File albumFolder = file.getParentFile();
        File artistFolder = albumFolder == null ? null : albumFolder.getParentFile();
        item.author = artistFolder != null && artistFolder.getParentFile() != null
                && artistFolder.getParentFile().equals(root()) ? artistFolder.getName() : "Unknown Artist";
        item.album = albumFolder != null && artistFolder != null && artistFolder.getParentFile() != null
                && artistFolder.getParentFile().equals(root()) ? albumFolder.getName() : "Unknown Album";
        JSONObject sidecar = readSidecar(file);
        if (sidecar != null) {
            item.title = sidecar.optString("title", item.title);
            item.author = sidecar.optString("artist", item.author);
            item.album = sidecar.optString("album", item.album);
            item.videoId = sidecar.optString("videoId", "");
            item.durationSec = sidecar.optLong("durationSec", 0L);
            item.durationText = item.durationSec > 0 ? NowPlayingManager.formatDurationMs(item.durationSec * 1000L) : "";
            item.thumbnailUrl = sidecar.optString("thumbnailUrl", "");
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if ((item.title == null || item.title.length() == 0 || item.title.equals(stripExtension(file.getName()))) && present(title)) item.title = title;
            if (unknown(item.author) && present(artist)) item.author = artist;
            if (unknown(item.album) && present(album)) item.album = album;
            if (item.durationSec <= 0 && present(duration)) item.durationSec = Long.parseLong(duration) / 1000L;
        } catch (Throwable ignored) {
        } finally { try { retriever.release(); } catch (Throwable ignored) {} }
        item.durationText = item.durationSec > 0 ? NowPlayingManager.formatDurationMs(item.durationSec * 1000L) : "";
        return item;
    }

    private static boolean unknown(String value) { return !present(value) || value.startsWith("Unknown "); }
    private static boolean present(String value) { return value != null && value.trim().length() > 0; }
    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static String clean(String value, String fallback) {
        String s = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        s = s.replaceAll("\\s+", " ");
        if (s.length() == 0 || s.equals(".") || s.equals("..")) return fallback;
        return s.length() > 80 ? s.substring(0, 80).trim() : s;
    }
    private static File sidecar(File file) { return new File(file.getAbsolutePath() + ".arc7meta"); }
    private static JSONObject readSidecar(File file) {
        File sidecar = sidecar(file);
        if (!sidecar.isFile() || sidecar.length() > 16384) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(sidecar); byte[] data = new byte[(int) sidecar.length()];
            int off = 0; while (off < data.length) { int n = in.read(data, off, data.length - off); if (n < 0) break; off += n; }
            return off == data.length ? new JSONObject(new String(data, "UTF-8")) : null;
        } catch (Throwable ignored) { return null; }
        finally { if (in != null) try { in.close(); } catch (Throwable ignored) {} }
    }
    private static void writeSidecar(File file, MorpheEngine.MediaItem item) throws Exception {
        JSONObject data = new JSONObject();
        data.put("title", item.title == null ? "" : item.title);
        data.put("artist", item.author == null ? "" : item.author);
        data.put("album", item.album == null ? "" : item.album);
        data.put("videoId", item.videoId == null ? "" : item.videoId);
        data.put("durationSec", item.durationSec);
        data.put("thumbnailUrl", item.bestThumb());
        File target = sidecar(file), temp = new File(target.getAbsolutePath() + ".part");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temp); out.write(data.toString().getBytes("UTF-8")); out.getFD().sync();
        } finally { if (out != null) out.close(); }
        if (target.exists() && !target.delete()) { temp.delete(); throw new java.io.IOException("Could not replace offline metadata"); }
        if (!temp.renameTo(target)) { temp.delete(); throw new java.io.IOException("Could not save offline metadata"); }
    }
}
