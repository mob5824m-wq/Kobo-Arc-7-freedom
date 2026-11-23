package ca.arc7freedom.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in {@code ListenBrainz} scrobbling for Morphe playback (`https://listenbrainz.org`).
 *
 * Implements the public ListenBrainz submit-listens API:
 *   POST {apiRoot}/1/submit-listens
 *   Authorization: Token <user token>
 *   { "listen_type": "playing_now" | "single", "payload": [ { ... } ] }
 *
 * Rules implemented here (per the ListenBrainz submission spec):
 *   • A track is scrobbled once it has been listened to for at least half its duration
 *     or 4 minutes — whichever comes first (and never before it is > 30 seconds long).
 *   • `playing_now` is sent when playback actually starts; it is not stored by the server.
 *   • `single` carries `listened_at` = unix seconds when the track started playing.
 *   • Scrobbles are fire-and-forget on a daemon thread; failures are logged as counters,
 *     never with track titles/URLs/tokens (privacy: logs stay metadata-free).
 *
 * Pure Java + `org.json` (built into Android); all HTTPS goes through the app's existing
 * TLS 1.3 gateway transport (`MorpheEngine.requestHttpsString`) so TLS 1.3/cipher policy,
 * cancellation and pacing rules are shared with the rest of Morphe.
 *
 * Android 4.0.3+ compatible (API 15): no lambdas, no try-with-resources requirements beyond
 * Java 7, no `java.time`.
 */
public final class ListenBrainzEngine {

    private ListenBrainzEngine() {}

    /** Default public server; overridable for self-hosted ListenBrainz-compatible endpoints. */
    public static final String DEFAULT_API_ROOT = "https://api.listenbrainz.org";
    /** Page that lists the user's ListenBrainz user token (used by the "Get token" button). */
    public static final String TOKEN_INFO_URL = "https://listenbrainz.org/profile/";
    public static final String PREFS = "listenbrainz_scrobble";

    /** Tracks at/below this length are never scrobbled (ListenBrainz discards them anyway). */
    public static final int MIN_TRACK_SEC = 30;
    /** Listen for at most this long before a listen counts (the "4 minutes" rule). */
    public static final long MAX_SCROBBLE_THRESHOLD_MS = 4L * 60L * 1000L;
    private static final long MIN_LISTENED_MS = 15000L;
    private static final int HTTP_TIMEOUT_MS = 15000;

    /* ==================== Settings ==================== */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return ctx != null && prefs(ctx).getBoolean("enabled", false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean("enabled", on).commit();
    }

    public static String getToken(Context ctx) {
        if (ctx == null) return "";
        String t = prefs(ctx).getString("token", "");
        return t == null ? "" : t.trim();
    }

    public static void setToken(Context ctx, String token) {
        if (ctx == null) return;
        String t = token == null ? "" : token.trim();
        SharedPreferences.Editor ed = prefs(ctx).edit().putString("token", t);
        if (t.length() == 0) ed.remove("user_name");
        ed.commit();
    }

    /** Base URL of the API (no trailing slash), e.g. `https://api.listenbrainz.org`. */
    public static String getApiRoot(Context ctx) {
        if (ctx == null) return DEFAULT_API_ROOT;
        String raw = prefs(ctx).getString("api_root", DEFAULT_API_ROOT);
        if (raw == null) raw = DEFAULT_API_ROOT;
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return DEFAULT_API_ROOT;
        }
        return trimmed;
    }

    public static void setApiRoot(Context ctx, String apiRoot) {
        if (ctx == null) return;
        String raw = apiRoot == null ? "" : apiRoot.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.length() == 0 || (!raw.startsWith("https://") && !raw.startsWith("http://"))) {
            raw = DEFAULT_API_ROOT;
        }
        prefs(ctx).edit().putString("api_root", raw).commit();
    }

    public static boolean isCustomApiRoot(Context ctx) {
        return !DEFAULT_API_ROOT.equals(getApiRoot(ctx));
    }

    /** Last validated ListenBrainz user name ("" when unknown / never validated). */
    public static String getCachedUserName(Context ctx) {
        if (ctx == null) return "";
        String u = prefs(ctx).getString("user_name", "");
        return u == null ? "" : u;
    }

    /** Enabled and has a token — the scrobbler may fire. */
    public static boolean isConfigured(Context ctx) {
        return isEnabled(ctx) && getToken(ctx).length() > 0;
    }

    /* ==================== Token validation ==================== */

    public static final class TokenCheck {
        public final boolean ok;
        public final String userName;
        public final String error;

        private TokenCheck(boolean ok, String userName, String error) {
            this.ok = ok;
            this.userName = userName == null ? "" : userName;
            this.error = error == null ? "" : error;
        }

        public static TokenCheck success(String userName) {
            return new TokenCheck(true, userName, "");
        }

        public static TokenCheck failure(String error) {
            return new TokenCheck(false, "", error);
        }
    }

    /**
     * Blocking token validation against `GET {apiRoot}/1/validate-token`.
     * Never call on the UI thread. Updates the cached user name on success.
     */
    public static TokenCheck validateToken(Context ctx) {
        if (ctx == null) return TokenCheck.failure("No context");
        String token = getToken(ctx);
        if (token.length() == 0) return TokenCheck.failure("Token is empty — paste your ListenBrainz user token first");
        try {
            Map<String, String> headers = authHeaders(token);
            String body = MorpheEngine.requestHttpsString(ctx, "GET",
                    getApiRoot(ctx) + "/1/validate-token", null, headers, HTTP_TIMEOUT_MS);
            JSONObject json = new JSONObject(body);
            boolean valid = json.optBoolean("valid", false);
            if (valid) {
                String userName = json.optString("user_name", "");
                prefs(ctx).edit().putString("user_name", userName).commit();
                ca.arc7freedom.support.DebugLog.event("listenbrainz.token_valid");
                return TokenCheck.success(userName);
            }
            return TokenCheck.failure("ListenBrainz rejected the token (not valid for any user)");
        } catch (Throwable error) {
            ca.arc7freedom.support.DebugLog.error("listenbrainz.token_validate_failed", error);
            String msg = error.getMessage();
            return TokenCheck.failure(msg != null && msg.length() > 0
                    ? msg : "Could not reach ListenBrainz — check Wi-Fi and the API host");
        }
    }

    /* ==================== Eligibility & thresholds ==================== */

    /** A track is scrobblable when it has real names and is longer than 30 seconds (or unknown). */
    public static boolean isScrobblable(String title, String artist, long durationSec) {
        if (title == null || title.trim().length() == 0) return false;
        if (artist == null || artist.trim().length() == 0) return false;
        return durationSec <= 0L || durationSec > MIN_TRACK_SEC;
    }

    /** Milliseconds of listening required before a listen counts (half the track, capped at 4 min). */
    public static long thresholdMs(long durationSec) {
        long half = durationSec > 0L ? (durationSec * 1000L) / 2L : MAX_SCROBBLE_THRESHOLD_MS;
        long threshold = Math.min(half, MAX_SCROBBLE_THRESHOLD_MS);
        return Math.max(MIN_LISTENED_MS, threshold);
    }

    /* ==================== Submission ==================== */

    /** `playing_now` — fire-and-forget, not persisted server-side. */
    public static void submitNowPlaying(final Context ctx, final String title, final String artist,
                                        final String album, final long durationSec) {
        submitAsync(ctx, "playing_now", title, artist, album, durationSec, 0L);
    }

    /** `single` — a completed listen; `listenedAtSec` is the unix time the track started playing. */
    public static void submitScrobble(final Context ctx, final String title, final String artist,
                                      final String album, final long durationSec, final long listenedAtSec) {
        if (listenedAtSec <= 0L) return;
        submitAsync(ctx, "single", title, artist, album, durationSec, listenedAtSec);
    }

    private static Map<String, String> authHeaders(String token) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Token " + token);
        return headers;
    }

    private static void submitAsync(final Context ctx, final String listenType,
                                    final String title, final String artist, final String album,
                                    final long durationSec, final long listenedAtSec) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (!isConfigured(app)) return;
        if (!isScrobblable(title, artist, durationSec)) return;

        final String t = title == null ? "" : title.trim();
        final String a = artist == null ? "" : artist.trim();
        final String al = album == null ? "" : album.trim();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject track = new JSONObject();
                    track.put("artist_name", a);
                    track.put("track_name", t);
                    if (al.length() > 0) track.put("release_name", al);
                    JSONObject additional = new JSONObject();
                    if (durationSec > 0L) additional.put("duration_ms", durationSec * 1000L);
                    additional.put("submission_client", "KoboArc7Freedom/2.7 (Android 4.2)");
                    track.put("additional_info", additional);

                    JSONObject listen = new JSONObject();
                    if ("single".equals(listenType)) listen.put("listened_at", listenedAtSec);
                    listen.put("track_metadata", track);

                    JSONObject root = new JSONObject();
                    root.put("listen_type", listenType);
                    root.put("payload", new JSONArray().put(listen));

                    String body = MorpheEngine.requestHttpsString(app, "POST",
                            getApiRoot(app) + "/1/submit-listens",
                            root.toString(), authHeaders(getToken(app)), HTTP_TIMEOUT_MS);
                    if (body != null && body.contains("\"ok\"")) {
                        ca.arc7freedom.support.DebugLog.event("playing_now".equals(listenType)
                                ? "listenbrainz.playing_now_sent" : "listenbrainz.scrobble_sent");
                    } else {
                        ca.arc7freedom.support.DebugLog.event("listenbrainz.listen_rejected");
                    }
                } catch (Throwable error) {
                    // Deliberately no title/URL/token in the log; counters only.
                    ca.arc7freedom.support.DebugLog.error("listenbrainz.submit_failed", error);
                }
            }
        }, "ListenBrainz-Submit");
        worker.setDaemon(true);
        worker.start();
    }
}
