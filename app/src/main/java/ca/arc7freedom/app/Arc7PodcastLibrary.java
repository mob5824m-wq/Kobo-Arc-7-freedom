package ca.arc7freedom.app;

import android.content.Context;
import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local show favourites. Filtering never fetches a directory or a feed. */
public final class Arc7PodcastLibrary {
    private static final String PREFS = "arc7_radio_rss", KEY = "favorite_podcasts_v1";
    public static final int MAX_SHOWS = 200;
    private Arc7PodcastLibrary() {}

    public static final class Show {
        public final String title, publisher, feedUrl;
        public Show(String title, String publisher, String feedUrl) throws IOException {
            this.title = text(title, 300);
            this.publisher = text(publisher, 200);
            this.feedUrl = normalizeFeedUrl(feedUrl);
            if (this.title.length() == 0) throw new IOException("The podcast needs a title");
        }
        public String label() { return title + (publisher.length() == 0 ? "" : " — " + publisher); }
    }

    public static String normalizeFeedUrl(String value) throws IOException {
        if (value == null || value.length() > 2048 || value.matches("(?s).*[\\p{Cntrl}].*"))
            throw new IOException("Invalid podcast feed URL");
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || host == null || host.length() == 0 || uri.getRawUserInfo() != null
                    || uri.getPort() < -1 || uri.getPort() > 65535) throw new Exception();
            scheme = scheme.toLowerCase(Locale.US); host = host.toLowerCase(Locale.US);
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            // Preserve escaped path/query bytes: re-encoding them can change a signed feed URL.
            String path = uri.getRawPath();
            return scheme + "://" + host + (port == -1 ? "" : ":" + port)
                    + (path == null || path.length() == 0 ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception invalid) { throw new IOException("Use an HTTP or HTTPS podcast feed URL without a login"); }
    }

    public static synchronized List<Show> load(Context context) throws IOException {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        if (raw.length() > 600000) throw new IOException("Saved podcast list is too large; it was not changed");
        try {
            JSONArray array = new JSONArray(raw);
            if (array.length() > MAX_SHOWS) throw new IOException("Saved podcast list exceeds its limit");
            List<Show> shows = new ArrayList<Show>(); Set<String> seen = new HashSet<String>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Show show = new Show(item.getString("title"), item.optString("publisher", ""), item.getString("feed"));
                if (seen.add(show.feedUrl)) shows.add(show);
            }
            return shows;
        } catch (Exception corrupt) { throw new IOException("Could not read podcast favorites; saved data was not changed", corrupt); }
    }

    /** A failed preference write is an error, not a claimed favourite. Other app keys are untouched. */
    public static synchronized void setFavorite(Context context, Show show, boolean favorite) throws IOException {
        if (show == null) throw new IOException("Choose a podcast first");
        List<Show> shows = load(context); int existing = -1;
        for (int i = 0; i < shows.size(); i++) if (shows.get(i).feedUrl.equals(show.feedUrl)) { existing = i; break; }
        if (existing >= 0) {
            shows.remove(existing);
            if (favorite) shows.add(existing, show);
        } else if (favorite) {
            if (shows.size() >= MAX_SHOWS) throw new IOException("Up to 200 favorite podcasts are supported; remove one first");
            shows.add(0, show);
        } else return;
        try {
            JSONArray array = new JSONArray();
            for (Show saved : shows) array.put(new JSONObject().put("title", saved.title)
                    .put("publisher", saved.publisher).put("feed", saved.feedUrl));
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit())
                throw new IOException("Podcast favorites could not be saved; please retry");
        } catch (IOException failed) { throw failed; }
        catch (Exception invalid) { throw new IOException("Podcast favorites could not be saved", invalid); }
    }

    public static boolean contains(List<Show> shows, Show show) {
        for (Show saved : shows) if (saved.feedUrl.equals(show.feedUrl)) return true;
        return false;
    }

    public static List<Show> filter(List<Show> shows, String query) {
        String q = searchable(query); String[] tokens = q.length() == 0 ? new String[0] : q.split("\\s+");
        List<Show> matches = new ArrayList<Show>();
        for (Show show : shows) {
            String haystack = searchable(show.title + " " + show.publisher); boolean match = true;
            for (String token : tokens) if (!haystack.contains(token)) { match = false; break; }
            if (match) matches.add(show);
        }
        return matches;
    }

    public static List<Show> parseDirectory(String raw) throws Exception {
        JSONArray results = new JSONObject(raw).optJSONArray("results");
        List<Show> shows = new ArrayList<Show>(); Set<String> seen = new HashSet<String>();
        if (results != null) for (int i = 0; i < results.length() && shows.size() < 50; i++) {
            JSONObject item = results.optJSONObject(i); if (item == null) continue;
            try {
                String title = item.optString("collectionName", "Podcast");
                Show show = new Show(title, item.optString("artistName", ""), item.optString("feedUrl", ""));
                if (seen.add(show.feedUrl)) shows.add(show);
            } catch (IOException invalid) { /* An invalid directory entry cannot become a saved feed. */ }
        }
        return shows;
    }

    private static String searchable(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.US).trim();
    }
    private static String text(String value, int limit) throws IOException {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > limit || clean.matches("(?s).*[\\p{Cntrl}&&[^\\t]].*"))
            throw new IOException("Podcast name is invalid or too long");
        return clean;
    }
}
