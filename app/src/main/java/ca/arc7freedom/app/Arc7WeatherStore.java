package ca.arc7freedom.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Private, bounded per-city caches and settings; workers never change the selected city. */
public final class Arc7WeatherStore {
    private Arc7WeatherStore() {}
    public static final String PREFS = "arc7_weather_store";
    private static final Object LOCK = new Object();
    private static final String PLACES = "places_v2", SELECTED = "selected_v2";
    public static final String FORECAST = "forecast", AIR = "air", ALERTS = "alerts";
    private static final String[] KINDS = {FORECAST, AIR, ALERTS};

    public interface Transport {
        String get(String url) throws Exception;
        boolean cancelled();
        void cancel();
    }
    public interface Listener { void stage(String kind, String error); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String key(Arc7WeatherData.Place p, String kind) { return "data_" + p.id + "_" + kind; }
    private static void commit(SharedPreferences.Editor e) {
        if (!e.commit()) throw new IllegalStateException("Could not save Weather data. Check available storage.");
    }
    public static List<Arc7WeatherData.Place> places(Context c) {
        synchronized (LOCK) {
            migrate(c);
            return readPlaces(prefs(c));
        }
    }
    private static List<Arc7WeatherData.Place> readPlaces(SharedPreferences sp) {
        List<Arc7WeatherData.Place> result = new ArrayList<Arc7WeatherData.Place>();
        try {
            JSONArray a = new JSONArray(sp.getString(PLACES, "[]"));
            for (int i = 0; i < a.length() && result.size() < Arc7WeatherData.MAX_PLACES; i++) {
                try {
                    Arc7WeatherData.Place p = Arc7WeatherData.Place.fromJson(a.optJSONObject(i));
                    boolean duplicate = false;
                    for (Arc7WeatherData.Place existing : result) if (existing.id.equals(p.id)) duplicate = true;
                    if (!duplicate) result.add(p);
                } catch (Exception malformedPlace) { /* discard only the corrupt row */ }
            }
        } catch (Exception malformedList) { /* recovery UI offers city search/manual coordinates */ }
        return result;
    }
    private static String serialise(List<Arc7WeatherData.Place> places) {
        try {
            JSONArray a = new JSONArray(); for (Arc7WeatherData.Place p : places) a.put(p.json());
            return a.toString();
        } catch (Exception impossible) { throw new IllegalArgumentException("Invalid saved location", impossible); }
    }
    /** Import only the old owning app's PRIVATE preferences. Never read a shared /sdcard cache. */
    private static void migrate(Context c) {
        SharedPreferences sp = prefs(c);
        if (sp.contains(PLACES)) return;
        List<Arc7WeatherData.Place> places = new ArrayList<Arc7WeatherData.Place>();
        SharedPreferences.Editor e = sp.edit();
        String raw = sp.getString("cached_json", "");
        boolean oldCoords = sp.contains("lat") && sp.contains("lon");
        if (oldCoords || raw.length() > 0) try {
            Arc7WeatherData.Place p = new Arc7WeatherData.Place(sp.getString("city_label", "Kitchener, Ontario (CA)"),
                    Double.parseDouble(sp.getString("lat", "43.4516")),
                    Double.parseDouble(sp.getString("lon", "-80.4925")), "");
            places.add(p); e.putString(SELECTED, p.id);
            if (raw.length() > 0) {
                Arc7WeatherData.parseForecast(p, raw, 0L); // Validate before import; freshness unknown, not "just updated".
                e.putString(key(p, FORECAST), raw).putLong(key(p, FORECAST) + "_at", 0L);
            }
        } catch (Exception invalidLegacy) { /* new UI stays empty, with no fabricated conditions */ }
        e.putString(PLACES, serialise(places));
        e.remove("cached_json"); // stop retaining an unbounded duplicate of the legacy cache
        commit(e);
    }
    public static Arc7WeatherData.Place selected(Context c) {
        synchronized (LOCK) {
            List<Arc7WeatherData.Place> places = places(c);
            String selected = prefs(c).getString(SELECTED, "");
            for (Arc7WeatherData.Place p : places) if (p.id.equals(selected)) return p;
            return places.isEmpty() ? null : places.get(0);
        }
    }
    public static boolean saved(Context c, Arc7WeatherData.Place p) {
        if (p == null) return false;
        for (Arc7WeatherData.Place item : places(c)) if (p.id.equals(item.id)) return true;
        return false;
    }
    public static void select(Context c, Arc7WeatherData.Place p) {
        synchronized (LOCK) {
            if (!saved(c, p)) throw new IllegalArgumentException("Location is no longer saved.");
            commit(prefs(c).edit().putString(SELECTED, p.id));
        }
    }
    public static void savePlace(Context c, Arc7WeatherData.Place p, boolean select) {
        synchronized (LOCK) {
            List<Arc7WeatherData.Place> all = places(c); int found = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(p.id)) found = i;
            boolean regionChanged = found >= 0 && !all.get(found).country.equals(p.country);
            if (found >= 0) all.set(found, p);
            else {
                if (all.size() >= Arc7WeatherData.MAX_PLACES)
                    throw new IllegalArgumentException("Up to 12 locations can be saved. Remove one before adding another.");
                all.add(p);
            }
            SharedPreferences.Editor e = prefs(c).edit().putString(PLACES, serialise(all));
            if (select) e.putString(SELECTED, p.id);
            if (regionChanged) {
                String k=key(p,ALERTS);
                e.remove(k).remove(k+"_at").remove(k+"_error").remove("notified_"+p.id).remove("attempt_"+p.id);
                e.putLong("cache_epoch",epoch(c)+1);
            }
            commit(e);
        }
    }
    public static void moveFirst(Context c, Arc7WeatherData.Place p) {
        synchronized (LOCK) {
            List<Arc7WeatherData.Place> all = places(c);
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(p.id)) { all.add(0, all.remove(i)); break; }
            commit(prefs(c).edit().putString(PLACES, serialise(all)));
        }
    }
    public static void remove(Context c, Arc7WeatherData.Place p) {
        synchronized (LOCK) {
            List<Arc7WeatherData.Place> all = places(c);
            for (int i = all.size()-1; i >= 0; i--) if (all.get(i).id.equals(p.id)) all.remove(i);
            SharedPreferences sp = prefs(c);
            SharedPreferences.Editor e = sp.edit().putString(PLACES, serialise(all));
            if (sp.getString(SELECTED, "").equals(p.id)) e.putString(SELECTED, all.isEmpty() ? "" : all.get(0).id);
            clear(e, p); e.remove("notified_" + p.id).putLong("cache_epoch",epoch(c)+1); commit(e);
        }
    }
    private static void clear(SharedPreferences.Editor e, Arc7WeatherData.Place p) {
        for (String kind : KINDS) {
            String k = key(p, kind); e.remove(k).remove(k + "_at").remove(k + "_error");
        }
        e.remove("attempt_" + p.id);
    }
    public static void clearCaches(Context c) {
        synchronized (LOCK) {
            SharedPreferences.Editor e = prefs(c).edit();
            for (Arc7WeatherData.Place p : places(c)) clear(e, p);
            e.putLong("cache_epoch",epoch(c)+1);
            commit(e); // Saved places, settings and notification deduplication remain intact.
        }
    }
    public static String raw(Context c, Arc7WeatherData.Place p, String kind) {
        return p == null ? "" : prefs(c).getString(key(p, kind), "");
    }
    public static long fetchedAt(Context c, Arc7WeatherData.Place p, String kind) {
        return p == null ? 0L : prefs(c).getLong(key(p, kind) + "_at", 0L);
    }
    public static String error(Context c, Arc7WeatherData.Place p, String kind) {
        return p == null ? "" : prefs(c).getString(key(p, kind) + "_error", "");
    }
    public static Arc7WeatherData.Forecast forecast(Context c, Arc7WeatherData.Place p) {
        if (p == null) return null;
        synchronized (LOCK) {
            try { return Arc7WeatherData.parseForecast(p, raw(c, p, FORECAST), fetchedAt(c, p, FORECAST)); }
            catch (Exception unavailable) { return null; }
        }
    }
    public static Arc7WeatherData.Air air(Context c, Arc7WeatherData.Place p) {
        if (p == null) return null;
        synchronized (LOCK) {
            try { return Arc7WeatherData.parseAir(raw(c, p, AIR), fetchedAt(c, p, AIR)); }
            catch (Exception unavailable) { return null; }
        }
    }
    public static Arc7WeatherData.Alerts alerts(Context c, Arc7WeatherData.Place p) {
        if (p == null || !("CA".equals(p.country) || "US".equals(p.country))) return null;
        synchronized (LOCK) {
            try { return Arc7WeatherData.parseAlerts(raw(c, p, ALERTS), p, fetchedAt(c, p, ALERTS)); }
            catch (Exception unavailable) { return null; }
        }
    }
    /** Atomic data + timestamp commit, no city/selection side effects; older workers cannot overwrite newer results. */
    static boolean store(Context c, Arc7WeatherData.Place p, String kind, String raw, long at, long epoch, Transport t) {
        synchronized (LOCK) {
            if (t.cancelled() || epoch!=epoch(c) || !saved(c, p) || at < fetchedAt(c, p, kind)) return false;
            commit(prefs(c).edit().putString(key(p, kind), raw).putLong(key(p, kind) + "_at", at)
                    .remove(key(p, kind) + "_error"));
            return true;
        }
    }
    private static void failure(Context c, Arc7WeatherData.Place p, String kind, String message, long at, long epoch, Transport t) {
        synchronized (LOCK) {
            if (!t.cancelled() && epoch==epoch(c) && saved(c, p) && at >= fetchedAt(c, p, kind))
                commit(prefs(c).edit().putString(key(p, kind) + "_error", message));
        }
    }
    public static String message(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().length() == 0) m = e.getClass().getSimpleName();
        return m.substring(0, Math.min(220, m.length()));
    }
    public static boolean needsRefresh(Context c, Arc7WeatherData.Place p, long now) {
        if (p == null) return false;
        long attempt = prefs(c).getLong("attempt_" + p.id, 0L);
        if (attempt <= now && now - attempt < 120000L) return false; // no offline retry storm
        long at = fetchedAt(c, p, FORECAST);
        int minutes = refreshMinutes(c);
        return at <= 0 || at > now + 300000L || now-at >= (minutes <= 0 ? 90 : minutes) * 60000L;
    }
    static long epoch(Context c) { return prefs(c).getLong("cache_epoch",0L); }
    public static void refresh(Context c, Arc7WeatherData.Place p, Transport t, Listener listener) {
        final long epoch;
        synchronized (LOCK) {
            if (p == null || !saved(c, p) || t.cancelled()) return;
            epoch=epoch(c);
            commit(prefs(c).edit().putLong("attempt_" + p.id, System.currentTimeMillis()));
        }
        for (String kind : KINDS) {
            if (t.cancelled() || epoch!=epoch(c)) return;
            if (ALERTS.equals(kind) && alertUrl(p).length() == 0) continue;
            long at = System.currentTimeMillis(); String error = "";
            try {
                String json = t.get(FORECAST.equals(kind) ? forecastUrl(p) : AIR.equals(kind) ? airUrl(p) : alertUrl(p));
                // Every provider response is validated BEFORE replacing its own last good cache.
                if (FORECAST.equals(kind)) Arc7WeatherData.parseForecast(p, json, at);
                else if (AIR.equals(kind)) Arc7WeatherData.parseAir(json, at);
                else Arc7WeatherData.parseAlerts(json, p, at);
                store(c, p, kind, json, at, epoch, t);
            } catch (Exception unavailable) {
                if (t.cancelled()) return;
                error = message(unavailable);
                try { failure(c, p, kind, error, at, epoch, t); } catch (Exception diskFull) { error = message(diskFull); }
            }
            if (epoch!=epoch(c)) return;
            if (!t.cancelled() && listener != null) listener.stage(kind, error);
            // AQ/alert failures are independent: a failed forecast must not prevent official alert checks.
        }
    }
    public static String searchUrl(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || q.length() > 120) throw new IllegalArgumentException("Enter 2 to 120 characters for city search.");
        return "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(q, "UTF-8")
                + "&count=10&language=en&format=json";
    }
    private static String coordinateQuery(Arc7WeatherData.Place p) {
        return String.format(Locale.US, "latitude=%.4f&longitude=%.4f", p.lat, p.lon);
    }
    public static String forecastUrl(Arc7WeatherData.Place p) {
        return "https://api.open-meteo.com/v1/forecast?" + coordinateQuery(p)
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,precipitation,snowfall,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                + "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation_probability,precipitation,snowfall,weather_code,cloud_cover,visibility,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,sunrise,sunset,daylight_duration,sunshine_duration,uv_index_max,precipitation_sum,snowfall_sum,precipitation_hours,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant"
                + "&forecast_days=14&timezone=auto&timeformat=unixtime&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm";
    }
    public static String airUrl(Arc7WeatherData.Place p) {
        return "https://air-quality-api.open-meteo.com/v1/air-quality?" + coordinateQuery(p)
                + "&hourly=us_aqi,european_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,dust,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen"
                + "&forecast_days=3&timezone=auto&timeformat=unixtime";
    }
    public static String alertUrl(Arc7WeatherData.Place p) {
        if ("US".equals(p.country)) return String.format(Locale.US,
                "https://api.weather.gov/alerts/active?point=%.4f,%.4f&status=actual&message_type=alert,update", p.lat, p.lon);
        if ("CA".equals(p.country)) return String.format(Locale.US,
                "https://api.weather.gc.ca/collections/weather-alerts/items?f=json&lang=en&limit=100&bbox=%.4f,%.4f,%.4f,%.4f",
                Math.max(-180,p.lon-0.01), Math.max(-90,p.lat-0.01), Math.min(180,p.lon+0.01), Math.min(90,p.lat+0.01));
        return "";
    }
    public static Arc7WeatherData.Units units(Context c) {
        SharedPreferences p = prefs(c); Arc7WeatherData.Units u = new Arc7WeatherData.Units();
        u.fahrenheit = p.getBoolean("use_fahrenheit", false); u.wind = Math.max(0, Math.min(3,p.getInt("wind_unit",0)));
        u.inches = p.getBoolean("inches", false); u.miles = p.getBoolean("miles",false); u.inHg = p.getBoolean("inhg",false);
        u.clock12 = p.getBoolean("clock12",false); u.europeanAqi = p.getBoolean("eu_aqi",false);
        return u;
    }
    public static void saveUnits(Context c, Arc7WeatherData.Units u) {
        commit(prefs(c).edit().putBoolean("use_fahrenheit",u.fahrenheit).putInt("wind_unit",u.wind)
                .putBoolean("inches",u.inches).putBoolean("miles",u.miles).putBoolean("inhg",u.inHg)
                .putBoolean("clock12",u.clock12).putBoolean("eu_aqi",u.europeanAqi));
    }
    public static boolean isFahrenheit(Context c) { return c != null && units(c).fahrenheit; }
    public static void setFahrenheit(Context c, boolean f) { Arc7WeatherData.Units u = units(c); u.fahrenheit=f; saveUnits(c,u); }
    public static String formatTemp(double c, boolean f) { return Arc7WeatherData.temperature(c,f); }
    public static String wmoDescription(int code) { return Arc7WeatherData.description(code); }
    public static int refreshMinutes(Context c) {
        int n = prefs(c).getInt("refresh_minutes",30); return n == 0 || n == 30 || n == 60 || n == 180 ? n : 30;
    }
    public static boolean wifiOnly(Context c) { return prefs(c).getBoolean("wifi_only",false); }
    public static boolean notifications(Context c) { return prefs(c).getBoolean("alert_notifications",false); }
    public static boolean alertSound(Context c) { return prefs(c).getBoolean("alert_sound",false); }
    public static void saveOptions(Context c, int minutes, boolean wifi, boolean notifications, boolean sound) {
        if (minutes != 0 && minutes != 30 && minutes != 60 && minutes != 180) throw new IllegalArgumentException("Invalid update interval");
        commit(prefs(c).edit().putInt("refresh_minutes",minutes).putBoolean("wifi_only",wifi)
                .putBoolean("alert_notifications",notifications).putBoolean("alert_sound",sound));
    }
    public static String notified(Context c, Arc7WeatherData.Place p) { return prefs(c).getString("notified_" + p.id, ""); }
    public static void notified(Context c, Arc7WeatherData.Place p, String value) {
        synchronized (LOCK) { if (saved(c,p)) commit(prefs(c).edit().putString("notified_" + p.id,value)); }
    }
}
