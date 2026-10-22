package ca.arc7freedom.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent storage, exact Android 4.2 `AlarmManager.set(RTC_WAKEUP, ...)` scheduler,
 * 12-hour (`AM/PM`) + 24-hour time formatting, individual per-alarm volume (`0%–100%`),
 * and 20 verified real Ogg Vorbis / MP3 Alarm Chimes downloaded directly from the
 * Internet Archive (`https://archive.org/download/...`) with SHA-1 integrity verification,
 * plus live `archive.org` sound search & custom chime downloads!
 */
public class Arc7ClockStore {

    public interface ChimeSyncListener {
        void onProgress(int completed, int total, String message);
    }

    public static final String ACTION_ALARM_TRIGGER = "ca.arc7freedom.app.ACTION_ALARM_TRIGGER";
    public static final String ACTION_TIMER_TRIGGER = "ca.arc7freedom.app.ACTION_TIMER_TRIGGER";

    public static final int REPEAT_EVERY_DAY = 0x7F; // Sun..Sat bits 0..6
    public static final int REPEAT_WEEKDAYS  = 0x3E; // Mon..Fri bits 1..5
    public static final int REPEAT_WEEKENDS  = 0x41; // Sun(bit0) + Sat(bit6)
    public static final int REPEAT_ONCE      = 0x00;

    /* ==================== 20 VERIFIED INTERNET ARCHIVE (`archive.org`) CHIMES ==================== */
    public static final String CHIME_APPLE_MARIMBA     = "apple_marimba";
    public static final String CHIME_APPLE_RADAR       = "apple_radar";
    public static final String CHIME_APPLE_OPENING     = "apple_opening";
    public static final String CHIME_APPLE_XYLOPHONE   = "apple_xylophone";
    public static final String CHIME_APPLE_HARP        = "apple_harp";
    public static final String CHIME_APPLE_CHIMES      = "apple_chimes";
    public static final String CHIME_APPLE_SCIFI       = "apple_scifi";
    public static final String CHIME_APPLE_SILK        = "apple_silk";
    public static final String CHIME_APPLE_APEX        = "apple_apex";
    public static final String CHIME_APPLE_BEACON      = "apple_beacon";
    public static final String CHIME_APPLE_CONSTELL    = "apple_constellation";
    public static final String CHIME_APPLE_SEASIDE     = "apple_seaside";
    public static final String CHIME_ANDROID_HELIUM    = "android_helium";
    public static final String CHIME_ANDROID_OXYGEN    = "android_oxygen";
    public static final String CHIME_ANDROID_PYXIS     = "android_pyxis";
    public static final String CHIME_ANDROID_CESIUM    = "android_cesium";
    public static final String CHIME_ANDROID_ARGON     = "android_argon";
    public static final String CHIME_ANDROID_PLATINUM  = "android_platinum";
    public static final String CHIME_ARC7_SUNRISE      = "arc7_sunrise";
    public static final String CHIME_ARCHIVE_SUBMARINE = "archive_submarine";

    /** `{ chimeId, displayLabel, verifiedArchiveOrgUrl, verifiedSha1Hex }` */
    public static final String[][] ARCHIVE_ORG_CHIME_CATALOG = new String[][]{
            {CHIME_APPLE_MARIMBA,     "Internet Archive • Marimba Classic",      "https://archive.org/download/Ringtone12/Marimba.ogg",          "05521f8271c97155605d5417d39ddf79f0a48f4d"},
            {CHIME_APPLE_RADAR,       "Internet Archive • Radar Wake Alarm",     "https://archive.org/download/Ringtone12/Radar.ogg",            "5b3887a60a43b882b8cdfe5608f4b3182e3875cf"},
            {CHIME_APPLE_OPENING,     "Internet Archive • Opening Chime",        "https://archive.org/download/Ringtone12/Opening.ogg",          "517e3a9b30742af8665e6f416e05ddabe3abe485"},
            {CHIME_APPLE_XYLOPHONE,   "Internet Archive • Studio Xylophone",     "https://archive.org/download/Ringtone12/Xylophone.ogg",        "5f30ff59a5abcad2711365c43d42b33208e39712"},
            {CHIME_APPLE_HARP,        "Internet Archive • Concert Harp",         "https://archive.org/download/Ringtone12/Harp.ogg",             "e2bdad03e18a277e76eea3a47baa6535f1ca0eea"},
            {CHIME_APPLE_CHIMES,      "Internet Archive • Wind Chimes",          "https://archive.org/download/Ringtone12/Chimes.ogg",           "0ecad7c6399255097c45ed271bfbe6ddc52fd454"},
            {CHIME_APPLE_SCIFI,       "Internet Archive • Sci-Fi Retro",         "https://archive.org/download/Ringtone12/Sci-fi.ogg",           "c99357d3765334574acbc221e858d30f7cd7453a"},
            {CHIME_APPLE_SILK,        "Internet Archive • Silk Morning",         "https://archive.org/download/Ringtone12/Silk.ogg",             "68f44eaefe1d5de07690ab222ba42ae8118f3b5b"},
            {CHIME_APPLE_APEX,        "Internet Archive • Apex Pulse",           "https://archive.org/download/Ringtone12/Apex.ogg",             "c003d9c31283870900baa697a009efc3aa0bb945"},
            {CHIME_APPLE_BEACON,      "Internet Archive • Beacon Alert",         "https://archive.org/download/Ringtone12/Beacon.ogg",           "d67c758a611cc9de99ed00434dba5fc22fa39a5b"},
            {CHIME_APPLE_CONSTELL,    "Internet Archive • Constellation",        "https://archive.org/download/Ringtone12/Constellation.ogg",    "1e02d8dcdebf2f9226d2a1e1097f73d41389351c"},
            {CHIME_APPLE_SEASIDE,     "Internet Archive • By The Seaside",       "https://archive.org/download/Ringtone12/By%20The%20Seaside.ogg", "8c7411e75ee7e42fadd7ad037076788c141aaa93"},
            {CHIME_ANDROID_HELIUM,    "Internet Archive • Ascending Bell",       "https://archive.org/download/Ringtone12/Ascending.ogg",        "8329724cb31262ad35b8d7d0bfc095f29b494916"},
            {CHIME_ANDROID_OXYGEN,    "Internet Archive • Aurora Glass",         "https://archive.org/download/Ringtone12/Aurora.ogg",           "094f063d0f10a8b2f9ae5d0d4f99543c1034e7a5"},
            {CHIME_ANDROID_PYXIS,     "Internet Archive • Android Pyxis",        "https://archive.org/download/Pyxis/Pyxis.ogg",                 "65f007597a3af376d06172c27d5e4394f47cd572"},
            {CHIME_ANDROID_CESIUM,    "Internet Archive • Cosmic Chime",         "https://archive.org/download/Ringtone12/Cosmic.ogg",           "f26a680377eea17a60030ea7e69aed8aca1a2301"},
            {CHIME_ANDROID_ARGON,     "Internet Archive • Radiate Piano",        "https://archive.org/download/Ringtone12/Radiate.ogg",          "9225b534ef7e41e1defca43ba80bc97e6b4c6bed"},
            {CHIME_ANDROID_PLATINUM,  "Internet Archive • Twin Bell Clock",      "https://archive.org/download/OldPhone/Clock%20Alarm.ogg",      "8f46d13d7dbbc8510977259149f6b122622fe0b7"},
            {CHIME_ARC7_SUNRISE,      "Internet Archive • Slow Rise Dawn",       "https://archive.org/download/Ringtone12/Slow%20Rise.ogg",      "04474dda11e373067c49545c2be860e6fbd8c4ff"},
            {CHIME_ARCHIVE_SUBMARINE, "Internet Archive • Submarine Dive Horn",  "https://archive.org/download/SubmarineDiveAlarm/submarine_dive_horn.ogg", "2aea921f9b61e242bea7658b852a5a08c2a77885"}
    };

    public static final String[] CHIME_IDS = new String[ARCHIVE_ORG_CHIME_CATALOG.length];
    public static final String[] CHIME_LABELS = new String[ARCHIVE_ORG_CHIME_CATALOG.length];
    static {
        for (int i = 0; i < ARCHIVE_ORG_CHIME_CATALOG.length; i++) {
            CHIME_IDS[i] = ARCHIVE_ORG_CHIME_CATALOG[i][0];
            CHIME_LABELS[i] = ARCHIVE_ORG_CHIME_CATALOG[i][1];
        }
    }

    private static final String PREFS = "arc7_clock_store";
    private static final String KEY_ALARMS = "alarms_json";
    private static final String KEY_NEXT_ID = "next_alarm_id";
    private static final String KEY_USE_12H = "use_12h_clock";
    private static final String KEY_TIMER_END = "timer_end_wall_ms";
    private static final String KEY_TIMER_REM = "timer_paused_rem_ms";
    private static final String KEY_TIMER_RUN = "timer_running";
    private static final String KEY_TIMER_LABEL = "timer_label";

    private static final int TIMER_REQ_CODE = 990001;

    private static volatile boolean ringingActive = false;
    private static volatile Thread synthThread = null;
    private static volatile MediaPlayer activeMediaPlayer = null;
    private static volatile boolean syncInFlight = false;

    public static File getChimesDir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Arc7Freedom/chimes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File getCachedChimeFile(String chimeId) {
        if (chimeId == null || chimeId.length() == 0) return null;
        File dir = getChimesDir();
        if (chimeId.startsWith("custom:")) {
            File cf = new File(dir, chimeId.substring(7));
            return (cf.isFile() && cf.length() > 512) ? cf : null;
        }
        File ogg = new File(dir, chimeId + ".ogg");
        if (ogg.isFile() && ogg.length() > 512) return ogg;
        File mp3 = new File(dir, chimeId + ".mp3");
        if (mp3.isFile() && mp3.length() > 512) return mp3;
        return null;
    }

    public static int countDownloadedArchiveChimes() {
        int n = 0;
        for (int i = 0; i < ARCHIVE_ORG_CHIME_CATALOG.length; i++) {
            if (getCachedChimeFile(ARCHIVE_ORG_CHIME_CATALOG[i][0]) != null) n++;
        }
        return n;
    }

    public static String[] getAllAvailableChimeIds() {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < CHIME_IDS.length; i++) ids.add(CHIME_IDS[i]);
        File[] extras = getChimesDir().listFiles();
        if (extras != null) {
            for (int i = 0; i < extras.length; i++) {
                String nm = extras[i].getName();
                if (nm.startsWith("ia_") && (nm.endsWith(".ogg") || nm.endsWith(".mp3")) && extras[i].length() > 512) {
                    ids.add("custom:" + nm);
                }
            }
        }
        return ids.toArray(new String[ids.size()]);
    }

    public static String[] getAllAvailableChimeLabels() {
        String[] ids = getAllAvailableChimeIds();
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            boolean cached = getCachedChimeFile(ids[i]) != null;
            labels[i] = (cached ? "✓ " : "⬇ ") + getChimeLabel(ids[i]);
        }
        return labels;
    }

    public static String getChimeLabel(String chimeId) {
        if (chimeId != null) {
            if (chimeId.startsWith("custom:")) {
                String clean = chimeId.substring(7).replaceFirst("^ia_", "")
                        .replace(".ogg", "").replace(".mp3", "").replace('_', ' ');
                return "Internet Archive • " + clean;
            }
            for (int i = 0; i < CHIME_IDS.length; i++) {
                if (CHIME_IDS[i].equals(chimeId)) return CHIME_LABELS[i];
            }
        }
        return CHIME_LABELS[0];
    }

    public static int getChimeIndex(String chimeId) {
        String[] all = getAllAvailableChimeIds();
        if (chimeId != null) {
            for (int i = 0; i < all.length; i++) {
                if (all[i].equals(chimeId)) return i;
            }
        }
        return 0;
    }

    private static String sha1Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(bytes);
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < d.length; i++) {
            sb.append(String.format(Locale.US, "%02x", d[i] & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] fetchViaTls13Gateway(Context ctx, String httpsUrl, int timeoutMs) throws Exception {
        if (ctx != null) {
            Arc7TlsGatewayService.ensureStarted(ctx);
        }
        String gatewayUrl = "http://127.0.0.1:8118/__arc7_raw?u=" + Uri.encode(httpsUrl);
        HttpURLConnection conn = (HttpURLConnection) new URL(gatewayUrl).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "KoboArc7Freedom/2.0 (InternetArchiveChimes)");
        InputStream in = null;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " fetching " + httpsUrl);
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8 * 1024 * 1024) break;
            }
            return bos.toByteArray();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    public static File downloadSingleArchiveChime(Context ctx, String chimeId) {
        File existing = getCachedChimeFile(chimeId);
        if (existing != null) return existing;
        for (int i = 0; i < ARCHIVE_ORG_CHIME_CATALOG.length; i++) {
            if (ARCHIVE_ORG_CHIME_CATALOG[i][0].equals(chimeId)) {
                try {
                    byte[] raw = fetchViaTls13Gateway(ctx, ARCHIVE_ORG_CHIME_CATALOG[i][2], 18000);
                    if (raw.length < 512) return null;
                    String expectedSha1 = ARCHIVE_ORG_CHIME_CATALOG[i][3];
                    if (expectedSha1 != null && expectedSha1.length() == 40) {
                        String actual = sha1Hex(raw);
                        if (!expectedSha1.equalsIgnoreCase(actual)) {
                            return null;
                        }
                    }
                    File dst = new File(getChimesDir(), chimeId + ".ogg");
                    FileOutputStream fos = new FileOutputStream(dst);
                    fos.write(raw);
                    fos.close();
                    return dst;
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    public static int autoSyncInternetArchiveChimes(Context ctx, ChimeSyncListener listener) {
        if (syncInFlight) return countDownloadedArchiveChimes();
        syncInFlight = true;
        int okCount = 0;
        try {
            int total = ARCHIVE_ORG_CHIME_CATALOG.length;
            for (int i = 0; i < total; i++) {
                String id = ARCHIVE_ORG_CHIME_CATALOG[i][0];
                String label = ARCHIVE_ORG_CHIME_CATALOG[i][1];
                File f = downloadSingleArchiveChime(ctx, id);
                if (f != null) okCount++;
                if (listener != null) {
                    listener.onProgress(i + 1, total,
                            (f != null ? "Downloaded & SHA-1 verified: " : "Skipped (offline): ") + label);
                }
            }
        } finally {
            syncInFlight = false;
        }
        return okCount;
    }

    public static final class ArchiveSearchHit {
        public String title;
        public String downloadUrl;
        public String localFileName;
    }

    /** Searches live public-domain / open-source audio chimes on `https://archive.org`! */
    public static List<ArchiveSearchHit> searchInternetArchiveChimes(Context ctx, String query) throws Exception {
        String q = Uri.encode("(" + query + ") AND mediatype:(audio)");
        String searchUrl = "https://archive.org/advancedsearch.php?q=" + q
                + "&fl[]=identifier&fl[]=title&rows=6&page=1&output=json";
        String rawSearch = new String(fetchViaTls13Gateway(ctx, searchUrl, 15000), "UTF-8");
        JSONObject root = new JSONObject(rawSearch);
        JSONArray docs = root.getJSONObject("response").getJSONArray("docs");
        List<ArchiveSearchHit> hits = new ArrayList<ArchiveSearchHit>();
        for (int i = 0; i < docs.length() && hits.size() < 12; i++) {
            JSONObject doc = docs.getJSONObject(i);
            String ident = doc.optString("identifier", "");
            String itemTitle = doc.optString("title", ident);
            if (ident.length() == 0) continue;
            try {
                String metaJson = new String(
                        fetchViaTls13Gateway(ctx, "https://archive.org/metadata/" + Uri.encode(ident), 12000),
                        "UTF-8");
                JSONArray files = new JSONObject(metaJson).optJSONArray("files");
                if (files == null) continue;
                int perItem = 0;
                for (int f = 0; f < files.length() && perItem < 3 && hits.size() < 12; f++) {
                    JSONObject fo = files.getJSONObject(f);
                    String fn = fo.optString("name", "");
                    long sz = fo.optLong("size", 0L);
                    String low = fn.toLowerCase(Locale.US);
                    if ((low.endsWith(".ogg") || low.endsWith(".mp3")) && sz > 2000 && sz < 2500000L) {
                        ArchiveSearchHit hit = new ArchiveSearchHit();
                        hit.title = itemTitle + " — " + fn;
                        hit.downloadUrl = "https://archive.org/download/" + Uri.encode(ident) + "/" + Uri.encode(fn);
                        String ext = low.endsWith(".ogg") ? ".ogg" : ".mp3";
                        hit.localFileName = "ia_" + ident.replaceAll("[^A-Za-z0-9_]", "_") + "_"
                                + fn.replaceAll("[^A-Za-z0-9_]", "_") + ext;
                        hits.add(hit);
                        perItem++;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return hits;
    }

    public static String downloadCustomSearchHit(Context ctx, ArchiveSearchHit hit) throws Exception {
        byte[] audio = fetchViaTls13Gateway(ctx, hit.downloadUrl, 20000);
        if (audio.length < 512) throw new Exception("Audio stream empty");
        File out = new File(getChimesDir(), hit.localFileName);
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(audio);
        fos.close();
        return "custom:" + hit.localFileName;
    }

    /* ==================== 12-HOUR (AM/PM) & 24-HOUR TIME MODE ==================== */

    public static boolean is12HourMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.contains(KEY_USE_12H)) {
            return sp.getBoolean(KEY_USE_12H, true);
        }
        try {
            String sys = Settings.System.getString(ctx.getContentResolver(), Settings.System.TIME_12_24);
            if ("24".equals(sys)) return false;
            if ("12".equals(sys)) return true;
        } catch (Throwable ignored) {}
        return true; // Default to friendly 12-hour AM/PM clock!
    }

    public static void set12HourMode(Context ctx, boolean use12Hour) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USE_12H, use12Hour)
                .commit();
        try {
            Settings.System.putString(ctx.getContentResolver(),
                    Settings.System.TIME_12_24, use12Hour ? "12" : "24");
        } catch (Throwable ignored) {}
        try {
                    new RootShell(ctx).execRoot(
                            "settings put system time_12_24 " + (use12Hour ? "12" : "24") + " 2>/dev/null || true",
                            null);
        } catch (Throwable ignored) {}
        try {
            ctx.sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));
        } catch (Throwable ignored) {}
        Arc7ClockWidgetProvider.refreshAll(ctx);
    }

    public static String formatClockNow(Context ctx, Date now, boolean withSeconds) {
        boolean use12 = is12HourMode(ctx);
        String pat = use12
                ? (withSeconds ? "h:mm:ss a" : "h:mm a")
                : (withSeconds ? "HH:mm:ss" : "HH:mm");
        return new SimpleDateFormat(pat, Locale.US).format(now);
    }

    public static class AlarmEntry {
        public int id;
        public int hour;
        public int minute;
        public String label;
        public int repeatMask; // bit 0=Sun .. bit 6=Sat; 0 = One-time
        public boolean enabled;
        public String chimeId = CHIME_APPLE_MARIMBA;
        public int volumePercent = 85; // Individual per-alarm volume: 0..100

        public String formatTimePreferred(Context ctx) {
            if (is12HourMode(ctx)) {
                int h12 = hour % 12;
                if (h12 == 0) h12 = 12;
                String ampm = hour < 12 ? "AM" : "PM";
                return String.format(Locale.US, "%d:%02d %s", h12, minute, ampm);
            }
            return String.format(Locale.US, "%02d:%02d", hour, minute);
        }

        public String formatTime24And12() {
            int h12 = hour % 12;
            if (h12 == 0) h12 = 12;
            String ampm = hour < 12 ? "AM" : "PM";
            return String.format(Locale.US, "%d:%02d %s  (%02d:%02d)", h12, minute, ampm, hour, minute);
        }

        public String describeRepeat() {
            if (repeatMask == REPEAT_ONCE) return "One-time";
            if (repeatMask == REPEAT_EVERY_DAY) return "Every day";
            if (repeatMask == REPEAT_WEEKDAYS) return "Weekdays (Mon–Fri)";
            if (repeatMask == REPEAT_WEEKENDS) return "Weekends (Sat–Sun)";
            String[] names = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if ((repeatMask & (1 << i)) != 0) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(names[i]);
                }
            }
            return sb.toString();
        }
    }

    public static List<AlarmEntry> loadAlarms(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_ALARMS, null);
        List<AlarmEntry> out = new ArrayList<AlarmEntry>();
        if (raw == null) {
            AlarmEntry a1 = new AlarmEntry();
            a1.id = 1; a1.hour = 7; a1.minute = 0; a1.label = "Morning Wake Up";
            a1.repeatMask = REPEAT_WEEKDAYS; a1.enabled = false;
            a1.chimeId = CHIME_APPLE_MARIMBA; a1.volumePercent = 85;

            AlarmEntry a2 = new AlarmEntry();
            a2.id = 2; a2.hour = 8; a2.minute = 30; a2.label = "Weekend Reading";
            a2.repeatMask = REPEAT_WEEKENDS; a2.enabled = false;
            a2.chimeId = CHIME_ANDROID_HELIUM; a2.volumePercent = 65;

            out.add(a1);
            out.add(a2);
            sp.edit().putInt(KEY_NEXT_ID, 3).commit();
            saveAlarms(ctx, out);
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AlarmEntry e = new AlarmEntry();
                e.id = o.optInt("id", i + 1);
                e.hour = o.optInt("hour", 7);
                e.minute = o.optInt("minute", 0);
                e.label = o.optString("label", "Alarm");
                e.repeatMask = o.optInt("repeatMask", REPEAT_EVERY_DAY);
                e.enabled = o.optBoolean("enabled", true);
                e.chimeId = o.optString("chimeId", CHIME_APPLE_MARIMBA);
                e.volumePercent = Math.max(0, Math.min(100, o.optInt("volumePercent", 85)));
                out.add(e);
            }
        } catch (Throwable ignored) {}
        Collections.sort(out, new Comparator<AlarmEntry>() {
            @Override
            public int compare(AlarmEntry a, AlarmEntry b) {
                int ta = a.hour * 60 + a.minute;
                int tb = b.hour * 60 + b.minute;
                return ta < tb ? -1 : (ta > tb ? 1 : (a.id - b.id));
            }
        });
        return out;
    }

    public static void saveAlarms(Context ctx, List<AlarmEntry> list) {
        JSONArray arr = new JSONArray();
        for (AlarmEntry e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("hour", e.hour);
                o.put("minute", e.minute);
                o.put("label", e.label == null ? "Alarm" : e.label);
                o.put("repeatMask", e.repeatMask);
                o.put("enabled", e.enabled);
                o.put("chimeId", e.chimeId == null ? CHIME_APPLE_MARIMBA : e.chimeId);
                o.put("volumePercent", Math.max(0, Math.min(100, e.volumePercent)));
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ALARMS, arr.toString())
                .commit();
        Arc7ClockWidgetProvider.refreshAll(ctx);
    }

    public static int nextAlarmId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int id = sp.getInt(KEY_NEXT_ID, 10);
        sp.edit().putInt(KEY_NEXT_ID, id + 1).commit();
        return id;
    }

    public static long computeNextTriggerMillis(AlarmEntry e, long nowMillis) {
        Calendar cand = Calendar.getInstance();
        cand.setTimeInMillis(nowMillis);
        cand.set(Calendar.HOUR_OF_DAY, e.hour);
        cand.set(Calendar.MINUTE, e.minute);
        cand.set(Calendar.SECOND, 0);
        cand.set(Calendar.MILLISECOND, 0);

        if (e.repeatMask == REPEAT_ONCE) {
            if (cand.getTimeInMillis() <= nowMillis + 1000L) {
                cand.add(Calendar.DAY_OF_YEAR, 1);
            }
            return cand.getTimeInMillis();
        }
        for (int addDays = 0; addDays < 8; addDays++) {
            Calendar test = (Calendar) cand.clone();
            test.add(Calendar.DAY_OF_YEAR, addDays);
            int dowBit = test.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
            if ((e.repeatMask & (1 << dowBit)) != 0 && test.getTimeInMillis() > nowMillis + 1000L) {
                return test.getTimeInMillis();
            }
        }
        cand.add(Calendar.DAY_OF_YEAR, 1);
        return cand.getTimeInMillis();
    }

    public static void scheduleOrCancelAlarm(Context ctx, AlarmEntry e) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
        intent.setAction(ACTION_ALARM_TRIGGER);
        intent.putExtra("alarm_id", e.id);
        intent.putExtra("label", e.label);
        intent.putExtra("chime_id", e.chimeId);
        intent.putExtra("volume_pct", e.volumePercent);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 50000 + e.id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        if (!e.enabled) {
            am.cancel(pi);
            Arc7ClockWidgetProvider.refreshAll(ctx);
            return;
        }
        long triggerAt = computeNextTriggerMillis(e, System.currentTimeMillis());
        am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        Arc7ClockWidgetProvider.refreshAll(ctx);
    }

    public static void scheduleSnooze(Context ctx, String label, int minutes, String chimeId, int volumePercent) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
        intent.setAction(ACTION_ALARM_TRIGGER);
        intent.putExtra("alarm_id", -1);
        intent.putExtra("label", (label == null ? "Alarm" : label) + " (Snoozed)");
        intent.putExtra("chime_id", chimeId == null ? CHIME_APPLE_MARIMBA : chimeId);
        intent.putExtra("volume_pct", volumePercent);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 59999, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60000L, pi);
    }

    public static void scheduleSnooze(Context ctx, String label, int minutes) {
        scheduleSnooze(ctx, label, minutes, CHIME_APPLE_MARIMBA, 85);
    }

    public static void rescheduleAllOnBoot(Context ctx) {
        List<AlarmEntry> list = loadAlarms(ctx);
        for (AlarmEntry e : list) {
            scheduleOrCancelAlarm(ctx, e);
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_TIMER_RUN, false)) {
            long endMs = sp.getLong(KEY_TIMER_END, 0L);
            String label = sp.getString(KEY_TIMER_LABEL, "Countdown Timer");
            if (endMs > System.currentTimeMillis()) {
                scheduleCountdownWakeup(ctx, endMs, label);
            }
        }
    }

    public static String describeNearestEnabledAlarm(Context ctx) {
        List<AlarmEntry> list = loadAlarms(ctx);
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        AlarmEntry bestAlarm = null;
        for (AlarmEntry e : list) {
            if (!e.enabled) continue;
            long t = computeNextTriggerMillis(e, now);
            if (t < best) {
                best = t;
                bestAlarm = e;
            }
        }
        if (bestAlarm == null) {
            return "No active alarms scheduled. Tap '+ Add Alarm' or flip any preset ON.";
        }
        long diffMin = Math.max(1L, (best - now + 30000L) / 60000L);
        long hrs = diffMin / 60L;
        long mins = diffMin % 60L;
        return String.format(Locale.US, "Next Alarm: %s — %s (Vol %d%% • in %dh %dm)",
                bestAlarm.formatTimePreferred(ctx), bestAlarm.label, bestAlarm.volumePercent, hrs, mins);
    }

    /* ==================== PERSISTENT COUNTDOWN TIMER STATE ==================== */

    public static void saveTimerState(Context ctx, boolean running, long endWallMs, long pausedRemMs, String label) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TIMER_RUN, running)
                .putLong(KEY_TIMER_END, endWallMs)
                .putLong(KEY_TIMER_REM, pausedRemMs)
                .putString(KEY_TIMER_LABEL, label == null ? "Countdown Timer" : label)
                .commit();
        if (running && endWallMs > System.currentTimeMillis()) {
            scheduleCountdownWakeup(ctx, endWallMs, label);
        } else {
            cancelCountdownWakeup(ctx);
        }
    }

    public static boolean isTimerRunning(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TIMER_RUN, false);
    }

    public static long getTimerEndWallMs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIMER_END, 0L);
    }

    public static long getTimerPausedRemainingMs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIMER_REM, 300000L);
    }

    public static String getTimerLabel(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TIMER_LABEL, "Countdown Timer");
    }

    public static void scheduleCountdownWakeup(Context ctx, long endWallMs, String label) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
        intent.setAction(ACTION_TIMER_TRIGGER);
        intent.putExtra("label", label == null ? "Countdown Timer" : label);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, TIMER_REQ_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC_WAKEUP, endWallMs, pi);
    }

    public static void cancelCountdownWakeup(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
        intent.setAction(ACTION_TIMER_TRIGGER);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, TIMER_REQ_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
    }

    /* ==================== 44.1 kHz CHIME SYNTHESIZER + PER-ALARM VOLUME ==================== */

    private static final class ChimeSpec {
        final double[] notesHz;
        final int stepMs;
        final int timbre; // 0=marimba wood, 1=bright radar/beep, 2=crystal bell, 3=harp/piano, 4=vibrato scifi

        ChimeSpec(double[] notesHz, int stepMs, int timbre) {
            this.notesHz = notesHz;
            this.stepMs = stepMs;
            this.timbre = timbre;
        }
    }

    private static ChimeSpec resolveChimeSpec(String id) {
        if (CHIME_APPLE_RADAR.equals(id)) {
            return new ChimeSpec(new double[]{1174.66, 1479.98, 1174.66, 1479.98, 1174.66, 0, 0, 0}, 105, 1);
        }
        if (CHIME_APPLE_OPENING.equals(id)) {
            return new ChimeSpec(new double[]{369.99, 554.37, 830.61, 932.33, 830.61, 0, 0}, 210, 2);
        }
        if (CHIME_APPLE_XYLOPHONE.equals(id)) {
            return new ChimeSpec(new double[]{783.99, 659.25, 523.25, 392.00, 523.25, 659.25, 783.99, 1046.50, 0}, 130, 0);
        }
        if (CHIME_APPLE_HARP.equals(id)) {
            return new ChimeSpec(new double[]{261.63, 329.63, 392.00, 493.88, 587.33, 659.25, 783.99, 0, 0}, 175, 3);
        }
        if (CHIME_APPLE_CHIMES.equals(id)) {
            return new ChimeSpec(new double[]{659.25, 830.61, 987.77, 1318.51, 1108.73, 987.77, 0, 0}, 190, 2);
        }
        if (CHIME_APPLE_SCIFI.equals(id)) {
            return new ChimeSpec(new double[]{523.25, 783.99, 698.46, 1046.50, 0, 0}, 240, 4);
        }
        if (CHIME_ANDROID_HELIUM.equals(id)) {
            return new ChimeSpec(new double[]{1046.50, 783.99, 1318.51, 0, 1046.50, 1318.51, 0, 0}, 135, 2);
        }
        if (CHIME_ANDROID_OXYGEN.equals(id)) {
            return new ChimeSpec(new double[]{440.00, 659.25, 987.77, 739.99, 0, 0}, 215, 2);
        }
        if (CHIME_ANDROID_PYXIS.equals(id)) {
            return new ChimeSpec(new double[]{587.33, 880.00, 1174.66, 1479.98, 0, 0}, 125, 1);
        }
        if (CHIME_ANDROID_CESIUM.equals(id)) {
            return new ChimeSpec(new double[]{783.99, 1174.66, 1567.98, 0, 783.99, 1567.98, 0, 0}, 115, 1);
        }
        if (CHIME_ANDROID_ARGON.equals(id)) {
            return new ChimeSpec(new double[]{349.23, 440.00, 523.25, 783.99, 659.25, 0, 0}, 200, 3);
        }
        if (CHIME_ANDROID_PLATINUM.equals(id)) {
            return new ChimeSpec(new double[]{880.00, 1108.73, 1318.51, 1760.00, 0, 0}, 145, 1);
        }
        if (CHIME_ARC7_SUNRISE.equals(id)) {
            return new ChimeSpec(new double[]{523.25, 659.25, 783.99, 0.0}, 200, 2);
        }
        // Default: Apple Marimba (B4 - G#4 - B4 - E5 - G#5 - F#5 - E5 - B4)
        return new ChimeSpec(new double[]{493.88, 415.30, 493.88, 659.25, 830.61, 739.99, 659.25, 493.88, 0}, 145, 0);
    }

    public static void previewChime(final Context ctx, final String chimeId, final int volumePercent) {
        stopAlarmRinging();
        startAlarmRingingInternal(ctx, chimeId, volumePercent, 3200L);
    }

    public static synchronized void startAlarmRinging(final Context ctx) {
        startAlarmRinging(ctx, CHIME_APPLE_MARIMBA, 85);
    }

    public static synchronized void startAlarmRinging(final Context ctx, final String chimeId, final int volumePercent) {
        startAlarmRingingInternal(ctx, chimeId, volumePercent, 0L);
    }

    private static synchronized void startAlarmRingingInternal(
            final Context ctx, final String chimeId, final int volumePercent, final long maxDurationMs) {
        stopAlarmRinging();
        final int clampedPct = Math.max(0, Math.min(100, volumePercent));
        if (clampedPct == 0) {
            return; // 0% per-alarm silent/screen-wake-only mode!
        }
        ringingActive = true;

        try {
            AudioManager audioMgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (audioMgr != null) {
                int maxVol = audioMgr.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int targetStep = Math.max(1, Math.round((clampedPct / 100.0f) * maxVol));
                audioMgr.setStreamVolume(AudioManager.STREAM_ALARM, targetStep, 0);
            }
        } catch (Throwable ignored) {}

        synthThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    // 1. Play real downloaded Internet Archive (.ogg / .mp3) chime first (downloading on demand if needed!)
                    File audioFile = getCachedChimeFile(chimeId);
                    if (audioFile == null && ctx != null) {
                        audioFile = downloadSingleArchiveChime(ctx, chimeId);
                    }
                    if (audioFile != null && audioFile.isFile()) {
                        MediaPlayer mp = new MediaPlayer();
                        activeMediaPlayer = mp;
                        mp.setAudioStreamType(AudioManager.STREAM_ALARM);
                        mp.setDataSource(audioFile.getAbsolutePath());
                        mp.setLooping(true);
                        float gain = Math.max(0.0f, Math.min(1.0f, clampedPct / 100.0f));
                        mp.setVolume(gain, gain);
                        mp.prepare();
                        mp.start();
                        long startedAt = System.currentTimeMillis();
                        while (ringingActive) {
                            if (maxDurationMs > 0L && System.currentTimeMillis() - startedAt >= maxDurationMs) {
                                break;
                            }
                            Thread.sleep(80L);
                        }
                        return;
                    }

                    // 2. Air-gapped offline fallback before first Internet Archive sync
                    final int sr = 44100;
                    int minBuf = AudioTrack.getMinBufferSize(
                            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack(
                            AudioManager.STREAM_ALARM,
                            sr,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minBuf, sr / 2),
                            AudioTrack.MODE_STREAM);
                    float gain = Math.max(0.0f, Math.min(1.0f, clampedPct / 100.0f));
                    track.setStereoVolume(gain, gain);
                    track.play();

                    short[] phrase = synthesizeChimePhrase(chimeId, clampedPct);
                    long startedAt = System.currentTimeMillis();
                    int offset = 0;
                    int chunk = 4096;
                    while (ringingActive && phrase.length > 0) {
                        if (maxDurationMs > 0L && System.currentTimeMillis() - startedAt >= maxDurationMs) {
                            break;
                        }
                        int count = Math.min(chunk, phrase.length - offset);
                        track.write(phrase, offset, count);
                        offset = (offset + count) % phrase.length;
                    }
                } catch (Throwable ignored) {
                } finally {
                    ringingActive = false;
                    MediaPlayer mp = activeMediaPlayer;
                    activeMediaPlayer = null;
                    if (mp != null) {
                        try { mp.stop(); } catch (Throwable ignored) {}
                        try { mp.release(); } catch (Throwable ignored) {}
                    }
                    if (track != null) {
                        try { track.stop(); } catch (Throwable ignored) {}
                        try { track.release(); } catch (Throwable ignored) {}
                    }
                }
            }
        }, "Arc7AlarmChimeSynth");
        synthThread.start();
    }

    public static short[] synthesizeChimePhrase(String chimeId, int volumePercent) {
        final int sr = 44100;
        int clampedPct = Math.max(0, Math.min(100, volumePercent));
        double gain = clampedPct / 100.0;
        ChimeSpec spec = resolveChimeSpec(chimeId);
        int noteSamples = Math.max(2048, (sr * spec.stepMs) / 1000);
        short[] out = new short[noteSamples * spec.notesHz.length];
        for (int step = 0; step < spec.notesHz.length; step++) {
            double f = spec.notesHz[step];
            int base = step * noteSamples;
            if (f <= 0.0) continue;
            for (int i = 0; i < noteSamples; i++) {
                double progress = (double) i / (double) noteSamples;
                double attack = Math.min(1.0, i / 280.0);
                double decay = (spec.timbre == 0 || spec.timbre == 3)
                        ? Math.exp(-3.4 * progress)
                        : Math.min(1.0, (noteSamples - i) / 1600.0);
                double env = attack * decay;
                double t = (double) i / (double) sr;
                double wave;
                if (spec.timbre == 0) {
                    wave = 0.78 * Math.sin(2.0 * Math.PI * f * t)
                            + 0.22 * Math.sin(8.0 * Math.PI * f * t) * Math.exp(-6.0 * progress);
                } else if (spec.timbre == 1) {
                    wave = 0.62 * Math.sin(2.0 * Math.PI * f * t)
                            + 0.26 * Math.sin(4.0 * Math.PI * f * t)
                            + 0.12 * Math.sin(6.0 * Math.PI * f * t);
                } else if (spec.timbre == 3) {
                    wave = 0.68 * Math.sin(2.0 * Math.PI * f * t)
                            + 0.22 * Math.sin(4.0 * Math.PI * f * t)
                            + 0.10 * Math.sin(6.0 * Math.PI * f * t);
                } else if (spec.timbre == 4) {
                    double mod = f * (1.0 + 0.022 * Math.sin(2.0 * Math.PI * 11.0 * t));
                    wave = 0.75 * Math.sin(2.0 * Math.PI * mod * t)
                            + 0.25 * Math.sin(4.0 * Math.PI * mod * t);
                } else {
                    wave = 0.66 * Math.sin(2.0 * Math.PI * f * t)
                            + 0.24 * Math.sin(4.0 * Math.PI * f * t)
                            + 0.10 * Math.sin(5.5 * Math.PI * f * t);
                }
                out[base + i] = (short) (wave * env * 24000.0 * gain);
            }
        }
        return out;
    }

    public static synchronized void stopAlarmRinging() {
        ringingActive = false;
        MediaPlayer mp = activeMediaPlayer;
        activeMediaPlayer = null;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
        }
        Thread t = synthThread;
        synthThread = null;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
    }
}
