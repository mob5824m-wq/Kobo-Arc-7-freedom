package ca.arc7freedom.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zero-API-Key Live `Open-Meteo` Weather & Geocoding Engine (`v2.2.0`):
 *   - Uses `https://geocoding-api.open-meteo.com/v1/search` + `https://api.open-meteo.com/v1/forecast`
 *     over `arc7tls` TLS 1.3 with zero API key or account required!
 *   - Persists full offline JSON snapshot to `/sdcard/Arc7Freedom/weather_cache.json` + SharedPreferences
 *     so both `Arc7WeatherActivity`, `Arc7WeatherWidgetProvider`, and `Arc7DeskClockActivity` always work
 *     instantly even when Wi-Fi is turned off!
 */
public final class Arc7WeatherStore {

    private Arc7WeatherStore() {}

    public static final String PREFS = "arc7_weather_store";
    public static final String KEY_CITY_LABEL = "city_label";
    public static final String KEY_LAT        = "lat";
    public static final String KEY_LON        = "lon";
    public static final String KEY_FAHRENHEIT = "use_fahrenheit";
    public static final String KEY_CACHED_RAW = "cached_json";

    public static final class DailyEntry {
        public String date = "";
        public int weatherCode = 0;
        public double maxC = 0;
        public double minC = 0;
        public int precipProb = 0;
    }

    public static final class HourlyEntry {
        public String hourLabel = "";
        public double tempC = 0;
        public int weatherCode = 0;
    }

    public static final class ForecastSnapshot {
        public String cityLabel = "Kitchener, Ontario";
        public double tempC = 14.0;
        public double feelsLikeC = 12.5;
        public double windKmh = 14.0;
        public int humidityPct = 62;
        public double uvMax = 4.2;
        public int weatherCode = 1;
        public final List<HourlyEntry> hourly = new ArrayList<HourlyEntry>();
        public final List<DailyEntry> daily = new ArrayList<DailyEntry>();

        public String summaryLine(boolean fahrenheit) {
            return formatTemp(tempC, fahrenheit) + " • " + wmoDescription(weatherCode)
                    + " (Feels " + formatTemp(feelsLikeC, fahrenheit) + ", Wind "
                    + Math.round(windKmh) + " km/h, Humidity " + humidityPct + "%)";
        }
    }

    public static boolean isFahrenheit(Context ctx) {
        return ctx != null && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FAHRENHEIT, false);
    }

    public static void setFahrenheit(Context ctx, boolean f) {
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FAHRENHEIT, f).commit();
        }
    }

    public static String formatTemp(double c, boolean fahrenheit) {
        if (fahrenheit) {
            return Math.round(c * 9.0 / 5.0 + 32.0) + "°F";
        }
        return Math.round(c) + "°C";
    }

    public static String wmoDescription(int code) {
        if (code == 0) return "Clear Sky ☀";
        if (code == 1 || code == 2) return "Partly Cloudy ⛅";
        if (code == 3) return "Overcast ☁";
        if (code == 45 || code == 48) return "Foggy 🌫";
        if (code >= 51 && code <= 57) return "Drizzle 🌦";
        if (code >= 61 && code <= 67) return "Rain 🌧";
        if (code >= 71 && code <= 77) return "Snow ❄";
        if (code >= 80 && code <= 82) return "Rain Showers 🌦";
        if (code == 85 || code == 86) return "Snow Showers 🌨";
        if (code >= 95) return "Thunderstorm ⛈";
        return "Fair ⛅";
    }

    public static ForecastSnapshot refreshCityForecast(Context ctx, RootShell shell,
                                                       NetworkCertManager certMgr, String newCityQuery) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cityLabel = sp.getString(KEY_CITY_LABEL, "Kitchener, Ontario");
        double lat = Double.parseDouble(sp.getString(KEY_LAT, "43.4516"));
        double lon = Double.parseDouble(sp.getString(KEY_LON, "-80.4925"));

        if (newCityQuery != null && newCityQuery.trim().length() > 0) {
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(newCityQuery.trim(), "UTF-8") + "&count=1&language=en&format=json";
            String geoRaw = Arc7BookSources.fetchStringViaTls13(ctx, shell, certMgr, geoUrl);
            JSONObject geoObj = new JSONObject(geoRaw);
            JSONArray res = geoObj.optJSONArray("results");
            if (res == null || res.length() == 0) {
                throw new IllegalArgumentException("City not found: " + newCityQuery);
            }
            JSONObject g = res.getJSONObject(0);
            lat = g.getDouble("latitude");
            lon = g.getDouble("longitude");
            String admin = g.optString("admin1", "");
            String country = g.optString("country_code", "");
            cityLabel = g.optString("name", newCityQuery.trim())
                    + (admin.isEmpty() ? "" : (", " + admin))
                    + (country.isEmpty() ? "" : (" (" + country + ")"));
            sp.edit()
                    .putString(KEY_CITY_LABEL, cityLabel)
                    .putString(KEY_LAT, String.valueOf(lat))
                    .putString(KEY_LON, String.valueOf(lon))
                    .commit();
        }

        String fcUrl = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max,precipitation_probability_max"
                + "&timezone=auto", lat, lon);
        String fcJson = Arc7BookSources.fetchStringViaTls13(ctx, shell, certMgr, fcUrl);
        sp.edit().putString(KEY_CACHED_RAW, fcJson).commit();
        saveOfflineJson(cityLabel, fcJson);

        ForecastSnapshot snap = parseOpenMeteoJson(cityLabel, fcJson);
        Arc7WeatherWidgetProvider.refreshAll(ctx);
        return snap;
    }

    public static ForecastSnapshot loadCached(Context ctx) {
        String cityLabel = "Kitchener, Ontario";
        String raw = "";
        if (ctx != null) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            cityLabel = sp.getString(KEY_CITY_LABEL, cityLabel);
            raw = sp.getString(KEY_CACHED_RAW, "");
        }
        if (raw.isEmpty()) {
            try {
                File f = offlineFile();
                if (f.isFile()) {
                    byte[] b = new byte[(int) Math.min(262144L, f.length())];
                    FileInputStream fis = new FileInputStream(f);
                    int n = fis.read(b);
                    fis.close();
                    JSONObject wrap = new JSONObject(new String(b, 0, n, "UTF-8"));
                    cityLabel = wrap.optString("city", cityLabel);
                    raw = wrap.optString("json", "");
                }
            } catch (Throwable ignored) {}
        }
        if (!raw.isEmpty()) {
            try { return parseOpenMeteoJson(cityLabel, raw); } catch (Throwable ignored) {}
        }
        ForecastSnapshot fallback = new ForecastSnapshot();
        fallback.cityLabel = cityLabel;
        return fallback;
    }

    public static ForecastSnapshot parseOpenMeteoJson(String cityLabel, String json) throws Exception {
        ForecastSnapshot s = new ForecastSnapshot();
        s.cityLabel = cityLabel;
        JSONObject root = new JSONObject(json);
        JSONObject cur = root.optJSONObject("current");
        if (cur != null) {
            s.tempC = cur.optDouble("temperature_2m", 14.0);
            s.feelsLikeC = cur.optDouble("apparent_temperature", s.tempC);
            s.humidityPct = cur.optInt("relative_humidity_2m", 55);
            s.weatherCode = cur.optInt("weather_code", 1);
            s.windKmh = cur.optDouble("wind_speed_10m", 10.0);
        }
        JSONObject hourly = root.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            JSONArray codes = hourly.optJSONArray("weather_code");
            if (times != null && temps != null) {
                for (int i = 0; i < Math.min(24, times.length()); i++) {
                    HourlyEntry h = new HourlyEntry();
                    String t = times.optString(i, "");
                    int idxT = t.indexOf('T');
                    h.hourLabel = idxT >= 0 ? t.substring(idxT + 1) : t;
                    h.tempC = temps.optDouble(i, s.tempC);
                    h.weatherCode = codes != null ? codes.optInt(i, 1) : 1;
                    s.hourly.add(h);
                }
            }
        }
        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            JSONArray dates = daily.optJSONArray("time");
            JSONArray maxes = daily.optJSONArray("temperature_2m_max");
            JSONArray mins  = daily.optJSONArray("temperature_2m_min");
            JSONArray codes = daily.optJSONArray("weather_code");
            JSONArray uvs   = daily.optJSONArray("uv_index_max");
            JSONArray probs = daily.optJSONArray("precipitation_probability_max");
            if (uvs != null && uvs.length() > 0) s.uvMax = uvs.optDouble(0, 0.0);
            if (dates != null && maxes != null && mins != null) {
                for (int i = 0; i < Math.min(7, dates.length()); i++) {
                    DailyEntry d = new DailyEntry();
                    d.date = dates.optString(i, "");
                    d.maxC = maxes.optDouble(i, 15.0);
                    d.minC = mins.optDouble(i, 5.0);
                    d.weatherCode = codes != null ? codes.optInt(i, 1) : 1;
                    d.precipProb = probs != null ? probs.optInt(i, 0) : 0;
                    s.daily.add(d);
                }
            }
        }
        return s;
    }

    private static File offlineFile() {
        File ext = null;
        try { ext = Environment.getExternalStorageDirectory(); } catch (Throwable ignored) {}
        return ext != null
                ? new File(ext, "Arc7Freedom/weather_cache.json")
                : new File("/sdcard/Arc7Freedom/weather_cache.json");
    }

    private static void saveOfflineJson(String city, String json) {
        try {
            File f = offlineFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            JSONObject w = new JSONObject();
            w.put("city", city);
            w.put("json", json);
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(w.toString().getBytes("UTF-8")); } finally { fos.close(); }
        } catch (Throwable ignored) {}
    }
}
