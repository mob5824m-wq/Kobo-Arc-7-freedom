package ca.arc7freedom.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android-independent weather models/parsers. Missing measurements are NaN, never demo weather. */
public final class Arc7WeatherData {
    private Arc7WeatherData() {}
    public static final String MISSING = "\u2014";
    public static final long HOUR = 3600000L;
    public static final int MAX_PLACES = 12;

    public static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }
    public static double number(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        double d = v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
        return finite(d) ? d : Double.NaN;
    }
    public static String text(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof String ? ((String) v).trim() : "";
    }
    private static double value(JSONObject o, String key, int i) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        double v = a != null && i < a.length() ? a.optDouble(i, Double.NaN) : Double.NaN;
        return finite(v) ? v : Double.NaN;
    }
    private static double percent(double n) { return n >= 0 && n <= 100 ? n : Double.NaN; }
    private static double positive(double n) { return n >= 0 ? n : Double.NaN; }
    private static int code(double n) { return finite(n) && n >= 0 && n <= 99 && n == (int)n ? (int)n : -1; }

    public static final class Place {
        public final String id, name, country;
        public final double lat, lon;
        public Place(String name, double lat, double lon, String country) {
            if (!finite(lat) || !finite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
                throw new IllegalArgumentException("Latitude must be -90 to 90; longitude -180 to 180.");
            this.lat = Math.round(lat * 10000.0) / 10000.0;
            this.lon = Math.round(lon * 10000.0) / 10000.0;
            this.id = Math.round(this.lat * 10000.0) + "_" + Math.round(this.lon * 10000.0);
            this.name = name == null || name.trim().length() == 0 ? coordinates(this.lat, this.lon)
                    : name.trim().substring(0, Math.min(120, name.trim().length()));
            String cc = country == null ? "" : country.trim().toUpperCase(Locale.US);
            this.country = cc.matches("[A-Z]{2}") ? cc : "";
        }
        public JSONObject json() throws Exception {
            return new JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("country", country);
        }
        public static Place fromJson(JSONObject j) {
            if (j == null) throw new IllegalArgumentException("Missing location");
            return new Place(text(j, "name"), number(j, "lat"), number(j, "lon"), text(j, "country"));
        }
    }

    /** All stored measurements use SI/provider defaults; changing display units never refetches. */
    public static final class Units {
        public boolean fahrenheit, clock12, europeanAqi;
        public int wind; // 0 km/h, 1 mph, 2 m/s, 3 knots
        public boolean inches, miles, inHg;
        public String temp(double c) { return temperature(c, fahrenheit); }
        public String speed(double kmh) {
            double[] factors = {1.0, 0.621371192, 1.0/3.6, 0.539956803};
            String[] suffix = {" km/h", " mph", " m/s", " kn"};
            int n = Math.max(0, Math.min(3, wind));
            return measure(kmh * factors[n], n == 2 ? 1 : 0, suffix[n]);
        }
        public String rain(double mm) { return measure(inches ? mm / 25.4 : mm, inches ? 2 : 1, inches ? " in" : " mm"); }
        public String snow(double cm) { return measure(inches ? cm / 2.54 : cm, 1, inches ? " in" : " cm"); }
        public String visibility(double metres) { return measure(metres / (miles ? 1609.344 : 1000), 1, miles ? " mi" : " km"); }
        public String pressure(double hpa) { return measure(inHg ? hpa / 33.8638867 : hpa, inHg ? 2 : 0, inHg ? " inHg" : " hPa"); }
        public String time(long ms, TimeZone zone) { return formatTime(ms, zone, clock12 ? "h:mm a" : "HH:mm"); }
    }

    public static class Hour {
        public long time;
        public double temp = Double.NaN, feels = Double.NaN, humidity = Double.NaN, dew = Double.NaN;
        public double probability = Double.NaN, rain = Double.NaN, snow = Double.NaN;
        public double wind = Double.NaN, gust = Double.NaN, direction = Double.NaN;
        public double pressure = Double.NaN, cloud = Double.NaN, visibility = Double.NaN, uv = Double.NaN;
        public int code = -1, isDay = -1;
    }
    public static final class Day {
        public long time, sunrise, sunset;
        public String date = "";
        public int code = -1;
        public double high = Double.NaN, low = Double.NaN, feelsHigh = Double.NaN, feelsLow = Double.NaN;
        public double probability = Double.NaN, rain = Double.NaN, snow = Double.NaN, wetHours = Double.NaN;
        public double wind = Double.NaN, gust = Double.NaN, direction = Double.NaN, uv = Double.NaN;
        public double daylight = Double.NaN, sunshine = Double.NaN;
    }
    public static final class Forecast {
        public Place place;
        public long fetchedAt, currentTime;
        public TimeZone zone = TimeZone.getTimeZone("UTC");
        public String zoneLabel = "UTC";
        public Hour current = new Hour();
        public final List<Hour> hours = new ArrayList<Hour>();
        public final List<Day> days = new ArrayList<Day>();
        public boolean hasData;
        public boolean stale(long now) {
            return fetchedAt <= 0 || fetchedAt > now + 300000L || now - fetchedAt > 90 * 60000L
                    || (currentTime > 0 && Math.abs(now - currentTime) > 2 * HOUR);
        }
        public Day today(long now) {
            String key = formatTime(now, zone, "yyyy-MM-dd");
            for (Day d : days) if (d.date.equals(key)) return d;
            return null;
        }
        /** Retains the ongoing forecast hour, excludes elapsed hours even when reading an old cache. */
        public List<Hour> upcoming(long now, int limit) {
            List<Hour> result = new ArrayList<Hour>();
            for (Hour h : hours) if (h.time <= now + 16 * 24 * HOUR && h.time + HOUR > now) {
                result.add(h); if (result.size() >= limit) break;
            }
            return result;
        }
        public Hour nearCurrent() {
            for (Hour h : hours) if (currentTime >= h.time && currentTime < h.time + HOUR) return h;
            return new Hour();
        }
    }

    public static List<Place> parseSearch(String raw) throws Exception {
        JSONObject root = checkedObject(raw);
        JSONArray a = root.optJSONArray("results");
        List<Place> result = new ArrayList<Place>();
        Set<String> ids = new HashSet<String>();
        if (a != null) for (int i = 0; i < a.length() && i < 20; i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            String name = text(j, "name"), admin = text(j, "admin1"), country = text(j, "country_code");
            if (name.length() == 0) continue;
            if (admin.length() > 0 && !admin.equalsIgnoreCase(name)) name += ", " + admin;
            if (country.length() > 0) name += " (" + country + ")";
            try {
                Place p = new Place(name, number(j, "latitude"), number(j, "longitude"), country);
                if (ids.add(p.id)) result.add(p);
            } catch (IllegalArgumentException badCoordinate) { /* do not turn missing coords into 0,0 */ }
        }
        return result;
    }

    public static Forecast parseForecast(Place p, String raw, long fetchedAt) throws Exception {
        JSONObject root = checkedObject(raw);
        Forecast s = new Forecast(); s.place = p; s.fetchedAt = fetchedAt;
        String tz = text(root, "timezone");
        s.zone = zone(tz, number(root, "utc_offset_seconds")); s.zoneLabel = s.zone.getID();
        JSONObject cur = root.optJSONObject("current");
        if (cur != null) {
            s.current = hour(cur, -1); s.currentTime = time(cur.opt("time"), s.zone);
            s.current.time = s.currentTime;
        }
        JSONObject hourly = root.optJSONObject("hourly");
        JSONArray times = hourly == null ? null : hourly.optJSONArray("time");
        Set<Long> seen = new HashSet<Long>();
        if (times != null) for (int i = 0; i < times.length() && i < 400; i++) {
            long at = time(arrayItem(times, i), s.zone);
            if (at <= 0 || !seen.add(at)) continue;
            Hour h = hour(hourly, i); h.time = at; s.hours.add(h);
        }
        Collections.sort(s.hours, new Comparator<Hour>() {
            @Override public int compare(Hour a, Hour b) { return a.time < b.time ? -1 : a.time == b.time ? 0 : 1; }
        });
        JSONObject daily = root.optJSONObject("daily");
        JSONArray dates = daily == null ? null : daily.optJSONArray("time");
        Set<String> dayKeys = new HashSet<String>();
        if (dates != null) for (int i = 0; i < dates.length() && i < 16; i++) {
            Day d = new Day(); d.time = time(arrayItem(dates, i), s.zone);
            if (d.time <= 0) continue;
            d.date = formatTime(d.time, s.zone, "yyyy-MM-dd");
            if (!dayKeys.add(d.date)) continue;
            d.code = code(value(daily, "weather_code", i));
            d.high = value(daily, "temperature_2m_max", i); d.low = value(daily, "temperature_2m_min", i);
            d.feelsHigh = value(daily, "apparent_temperature_max", i); d.feelsLow = value(daily, "apparent_temperature_min", i);
            d.probability = percent(value(daily, "precipitation_probability_max", i));
            d.rain = positive(value(daily, "precipitation_sum", i)); d.snow = positive(value(daily, "snowfall_sum", i));
            d.wetHours = positive(value(daily, "precipitation_hours", i));
            d.wind = positive(value(daily, "wind_speed_10m_max", i)); d.gust = positive(value(daily, "wind_gusts_10m_max", i));
            d.direction = value(daily, "wind_direction_10m_dominant", i); d.uv = positive(value(daily, "uv_index_max", i));
            d.daylight = positive(value(daily, "daylight_duration", i)); d.sunshine = positive(value(daily, "sunshine_duration", i));
            d.sunrise = arrayTime(daily, "sunrise", i, s.zone); d.sunset = arrayTime(daily, "sunset", i, s.zone);
            s.days.add(d);
        }
        Collections.sort(s.days, new Comparator<Day>() {
            @Override public int compare(Day a, Day b) { return a.date.compareTo(b.date); }
        });
        s.hasData = s.currentTime > 0 && finite(s.current.temp);
        for (Hour h : s.hours) if (finite(h.temp)) s.hasData = true;
        for (Day d : s.days) if (finite(d.high) || finite(d.low)) s.hasData = true;
        if (!s.hasData) throw new IllegalArgumentException("The weather provider returned no usable forecast measurements.");
        return s;
    }
    private static double field(JSONObject o, String key, int i) { return i < 0 ? number(o, key) : value(o, key, i); }
    private static Hour hour(JSONObject o, int i) {
        Hour h = new Hour();
        h.temp = field(o, "temperature_2m", i); h.feels = field(o, "apparent_temperature", i);
        h.humidity = percent(field(o, "relative_humidity_2m", i)); h.dew = field(o, "dew_point_2m", i);
        h.probability = percent(field(o, "precipitation_probability", i)); h.rain = positive(field(o, "precipitation", i));
        h.snow = positive(field(o, "snowfall", i)); h.code = code(field(o, "weather_code", i));
        h.wind = positive(field(o, "wind_speed_10m", i)); h.gust = positive(field(o, "wind_gusts_10m", i));
        h.direction = field(o, "wind_direction_10m", i); h.pressure = positive(field(o, "pressure_msl", i));
        h.cloud = percent(field(o, "cloud_cover", i)); h.visibility = positive(field(o, "visibility", i));
        h.uv = positive(field(o, "uv_index", i));
        double day = field(o, "is_day", i); h.isDay = day == 1 ? 1 : day == 0 ? 0 : -1;
        return h;
    }

    public static final class AirHour {
        public long time;
        public double us = Double.NaN, eu = Double.NaN, pm25 = Double.NaN, pm10 = Double.NaN;
        public double ozone = Double.NaN, no2 = Double.NaN, so2 = Double.NaN, co = Double.NaN, dust = Double.NaN;
        public final double[] pollen = new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        public double index(Units u) { return u.europeanAqi ? eu : us; }
    }
    public static final String[] POLLEN_KEYS = {"alder_pollen", "birch_pollen", "grass_pollen", "mugwort_pollen", "olive_pollen", "ragweed_pollen"};
    public static final String[] POLLEN_NAMES = {"Alder", "Birch", "Grass", "Mugwort", "Olive", "Ragweed"};
    public static final class Air {
        public long fetchedAt;
        public TimeZone zone = TimeZone.getTimeZone("UTC");
        public final List<AirHour> hours = new ArrayList<AirHour>();
        public AirHour at(long now) {
            for (AirHour h : hours) if (h.time <= now && now < h.time + HOUR) return h;
            return new AirHour(); // Never present yesterday's AQI as current.
        }
    }
    public static Air parseAir(String raw, long fetchedAt) throws Exception {
        JSONObject root = checkedObject(raw), hourly = root.optJSONObject("hourly");
        JSONArray times = hourly == null ? null : hourly.optJSONArray("time");
        if (times == null || times.length() == 0) throw new IllegalArgumentException("Air quality data unavailable for this location.");
        TimeZone z = zone(text(root, "timezone"), number(root, "utc_offset_seconds"));
        Air air = new Air(); air.fetchedAt = fetchedAt; air.zone = z;
        for (int i = 0; i < times.length() && i < 168; i++) {
            AirHour h = new AirHour(); h.time = time(arrayItem(times, i), z);
            if (h.time <= 0) continue;
            h.us = positive(value(hourly, "us_aqi", i)); h.eu = positive(value(hourly, "european_aqi", i));
            h.pm25 = positive(value(hourly, "pm2_5", i)); h.pm10 = positive(value(hourly, "pm10", i));
            h.ozone = positive(value(hourly, "ozone", i)); h.no2 = positive(value(hourly, "nitrogen_dioxide", i));
            h.so2 = positive(value(hourly, "sulphur_dioxide", i)); h.co = positive(value(hourly, "carbon_monoxide", i));
            h.dust = positive(value(hourly, "dust", i));
            for (int n = 0; n < POLLEN_KEYS.length; n++) h.pollen[n] = positive(value(hourly, POLLEN_KEYS[n], i));
            air.hours.add(h);
        }
        if (air.hours.isEmpty()) throw new IllegalArgumentException("Air quality timestamps unavailable.");
        return air;
    }

    public static final class Alert {
        public String id = "", title = "", detail = "", instruction = "", area = "", severity = "", sent = "";
        public long expires, starts;
        public boolean active(long now) { return expires > now && (starts <= 0 || starts <= now); }
    }
    public static final class Alerts {
        public long fetchedAt;
        public String provider = "";
        public boolean complete = true;
        public final List<Alert> items = new ArrayList<Alert>();
        public List<Alert> active(long now) {
            List<Alert> result = new ArrayList<Alert>();
            for (Alert a : items) if (a.active(now)) result.add(a);
            return result;
        }
    }
    public static Alerts parseAlerts(String raw, Place place, long fetchedAt) throws Exception {
        JSONObject root = checkedObject(raw);
        JSONArray features = root.optJSONArray("features");
        if (features == null || !"FeatureCollection".equals(text(root, "type")))
            throw new IllegalArgumentException("Official alert service returned an unrecognised response.");
        boolean canada = "CA".equals(place.country);
        Alerts result = new Alerts(); result.fetchedAt = fetchedAt;
        result.provider = canada ? "Environment and Climate Change Canada" : "US National Weather Service";
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < features.length() && i < 200; i++) {
            JSONObject f = features.optJSONObject(i), p = f == null ? null : f.optJSONObject("properties");
            if (p == null) { result.complete = false; continue; }
            if (canada) {
                JSONObject geometry = f.optJSONObject("geometry");
                if (!validGeometry(geometry)) { result.complete = false; continue; }
                if (!contains(geometry, place.lon, place.lat)) continue;
            }
            Alert a = new Alert();
            if (canada) {
                a.id = text(p, "id"); if (a.id.length() == 0) a.id = text(f, "id");
                a.title = text(p, "alert_name_en"); a.detail = text(p, "alert_text_en");
                a.area = text(p, "feature_name_en"); a.severity = text(p, "risk_colour_en");
                a.sent = text(p, "publication_datetime");
                a.expires = time(p.opt("expiration_datetime"), TimeZone.getTimeZone("UTC"));
                a.starts = time(p.opt("publication_datetime"), TimeZone.getTimeZone("UTC"));
                String status = text(p, "status_en").toLowerCase(Locale.US);
                if (status.contains("ended") || status.contains("cancel") || status.contains("test")) continue;
            } else {
                String status = text(p, "status"), message = text(p, "messageType");
                if (!"Actual".equals(status) || "Cancel".equals(message)) continue;
                a.id = text(p, "id"); if (a.id.length() == 0) a.id = text(f, "id");
                a.title = text(p, "headline"); if (a.title.length() == 0) a.title = text(p, "event");
                a.detail = text(p, "description"); a.instruction = text(p, "instruction");
                a.area = text(p, "areaDesc"); a.severity = text(p, "severity"); a.sent = text(p, "sent");
                a.expires = time(p.opt("expires"), TimeZone.getTimeZone("UTC"));
                // Future onset watches are already active when effective. Do not hide them until onset.
                a.starts = time(p.opt("effective"), TimeZone.getTimeZone("UTC"));
            }
            if (a.title.length() == 0 || a.expires <= 0) { result.complete = false; continue; }
            if (a.id.length() == 0) a.id = a.title + a.area;
            if (seen.add(a.id + a.sent)) result.items.add(a);
        }
        JSONArray links = root.optJSONArray("links");
        if (links != null) for (int i = 0; i < links.length(); i++)
            if ("next".equals(text(links.optJSONObject(i), "rel"))) result.complete = false;
        JSONObject pagination = root.optJSONObject("pagination");
        if (pagination != null && text(pagination, "next").length() > 0) result.complete = false;
        if (number(root, "numberMatched") > features.length() || features.length() > 200) result.complete = false;
        return result;
    }

    private static boolean validGeometry(JSONObject geometry) {
        if (geometry==null) return false;
        JSONArray a=geometry.optJSONArray("coordinates"); String type=text(geometry,"type");
        if (a==null || a.length()==0) return false;
        try {
            if ("Polygon".equals(type)) return validPolygon(a);
            if ("MultiPolygon".equals(type)) {
                for (int i=0; i<a.length(); i++) if (!validPolygon(a.getJSONArray(i))) return false;
                return true;
            }
        } catch (Exception malformed) { return false; }
        return false;
    }
    private static boolean validPolygon(JSONArray rings) throws Exception {
        if (rings.length()==0) return false;
        for (int i=0; i<rings.length(); i++) {
            JSONArray ring=rings.getJSONArray(i);
            if (ring.length()<4 || ring.length()>100000) return false;
            double firstX=Double.NaN, firstY=Double.NaN, x=Double.NaN, y=Double.NaN;
            for (int j=0; j<ring.length(); j++) {
                JSONArray point=ring.getJSONArray(j); x=point.optDouble(0,Double.NaN); y=point.optDouble(1,Double.NaN);
                if (!finite(x) || !finite(y) || x< -180 || x>180 || y< -90 || y>90) return false;
                if (j==0) { firstX=x; firstY=y; }
            }
            if (Math.abs(firstX-x)>1e-7 || Math.abs(firstY-y)>1e-7) return false;
        }
        return true;
    }

    /** Exact point filtering for ECCC's bbox query, including holes and MultiPolygon regions. */
    public static boolean contains(JSONObject geometry, double lon, double lat) {
        String type = text(geometry, "type"); JSONArray c = geometry == null ? null : geometry.optJSONArray("coordinates");
        if (c == null) return false;
        if ("Polygon".equals(type)) return polygon(c, lon, lat);
        if ("MultiPolygon".equals(type)) for (int i = 0; i < c.length(); i++) {
            try { if (polygon(c.getJSONArray(i), lon, lat)) return true; } catch (Exception malformed) { return false; }
        }
        return false;
    }
    private static boolean polygon(JSONArray rings, double x, double y) {
        try {
            if (rings.length() == 0 || !ring(rings.getJSONArray(0), x, y)) return false;
            for (int i = 1; i < rings.length(); i++) if (ring(rings.getJSONArray(i), x, y)) return false;
            return true;
        } catch (Exception malformed) { return false; }
    }
    private static boolean ring(JSONArray points, double x, double y) throws Exception {
        boolean inside = false;
        if (points.length() < 4 || points.length() > 100000) return false;
        for (int i = 0, j = points.length()-1; i < points.length(); j = i++) {
            JSONArray a = points.getJSONArray(i), b = points.getJSONArray(j);
            double ax = a.optDouble(0, Double.NaN), ay = a.optDouble(1, Double.NaN);
            double bx = b.optDouble(0, Double.NaN), by = b.optDouble(1, Double.NaN);
            if (!finite(ax) || !finite(ay) || !finite(bx) || !finite(by)) return false;
            double cross = (x-ax)*(by-ay)-(y-ay)*(bx-ax);
            if (Math.abs(cross) < 1e-9 && x >= Math.min(ax,bx) && x <= Math.max(ax,bx)
                    && y >= Math.min(ay,by) && y <= Math.max(ay,by)) return true;
            if ((ay > y) != (by > y) && x < (bx-ax)*(y-ay)/(by-ay)+ax) inside = !inside;
        }
        return inside;
    }

    public static JSONObject checkedObject(String raw) throws Exception {
        if (raw == null || raw.length() > 2 * 1024 * 1024 || !raw.trim().startsWith("{"))
            throw new IllegalArgumentException("Weather service response was empty, too large or not JSON.");
        JSONObject root = new JSONObject(raw);
        if (Boolean.TRUE.equals(root.opt("error"))) throw new IllegalArgumentException("Provider: " + text(root, "reason"));
        return root;
    }
    private static Object arrayItem(JSONArray a, int i) {
        try { return a == null || i >= a.length() ? null : a.get(i); } catch (Exception absent) { return null; }
    }
    private static long arrayTime(JSONObject o, String key, int i, TimeZone z) {
        return time(arrayItem(o.optJSONArray(key), i), z);
    }
    public static TimeZone zone(String name, double offset) {
        TimeZone z = TimeZone.getTimeZone(name.length() == 0 ? "GMT" : name);
        if ((!"GMT".equals(z.getID()) && name.length()>0) || "GMT".equals(name) || "UTC".equals(name)) return z;
        int seconds = finite(offset) && Math.abs(offset) <= 86400 ? (int)offset : 0;
        return new SimpleTimeZone(seconds * 1000, "UTC" + (seconds >= 0 ? "+" : "") + seconds / 3600.0);
    }
    public static long time(Object value, TimeZone zone) {
        if (value instanceof Number) {
            double seconds = ((Number)value).doubleValue();
            return finite(seconds) && seconds > 0 && seconds < 4102444800L ? (long)(seconds * 1000) : 0L;
        }
        if (!(value instanceof String)) return 0L;
        String t = ((String)value).trim();
        // ISO timestamps with explicit UTC/offset (CAP/NWS) and local ISO legacy weather caches.
        if (t.endsWith("Z")) t = t.substring(0, t.length()-1) + "+0000";
        t = t.replaceFirst("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        Matcher fraction = Pattern.compile("\\.(\\d+)(?=[+-]\\d{4}$|$)").matcher(t);
        if (fraction.find()) {
            String ms = (fraction.group(1)+"000").substring(0,3);
            t = t.substring(0,fraction.start())+"."+ms+t.substring(fraction.end());
        }
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mmZ", "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US); f.setLenient(false); f.setTimeZone(zone);
            ParsePosition pos = new ParsePosition(0); Date d = f.parse(t, pos);
            if (d != null && pos.getIndex() == t.length()) return d.getTime();
        }
        return 0L;
    }
    public static String formatTime(long ms, TimeZone zone, String pattern) {
        if (ms <= 0) return MISSING;
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US); f.setTimeZone(zone);
        return f.format(new Date(ms));
    }
    public static String temperature(double c, boolean f) {
        return finite(c) ? Math.round(f ? c * 1.8 + 32 : c) + (f ? "\u00b0F" : "\u00b0C") : MISSING;
    }
    public static String measure(double v, int decimals, String suffix) {
        return finite(v) ? String.format(Locale.US, "%." + decimals + "f", v) + suffix : MISSING;
    }
    public static String pct(double v) { return measure(v, 0, "%"); }
    public static String coordinates(double lat, double lon) { return String.format(Locale.US, "%.4f, %.4f", lat, lon); }
    public static boolean validFix(double lat, double lon, long at, double accuracyMetres, long now) {
        return finite(lat) && finite(lon) && lat>=-90 && lat<=90 && lon>=-180 && lon<=180
                && now-at>=-60000L && now-at<=10*60000L
                && (Double.isNaN(accuracyMetres) || (accuracyMetres>=0 && accuracyMetres<=20000));
    }
    public static String direction(double degrees) {
        if (!finite(degrees) || degrees < 0 || degrees > 360) return MISSING;
        String[] names = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return names[((int)Math.round(degrees / 22.5)) % 16];
    }
    public static String description(int c) {
        switch (c) {
            case 0: return "Clear sky"; case 1: return "Mainly clear"; case 2: return "Partly cloudy"; case 3: return "Overcast";
            case 45: return "Fog"; case 48: return "Freezing fog";
            case 51: return "Light drizzle"; case 53: return "Drizzle"; case 55: return "Heavy drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain"; case 63: return "Rain"; case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow"; case 73: return "Snow"; case 75: return "Heavy snow"; case 77: return "Snow grains";
            case 80: return "Light showers"; case 81: return "Showers"; case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm"; case 96: case 99: return "Thunderstorm with hail";
            default: return "Conditions unavailable";
        }
    }
    public static String aqiLabel(double aqi, boolean eu) {
        if (!finite(aqi)) return "Unavailable";
        double[] limits = eu ? new double[]{20,40,60,80,100} : new double[]{50,100,150,200,300};
        String[] names = eu ? new String[]{"Good", "Fair", "Moderate", "Poor", "Very poor", "Extremely poor"}
                : new String[]{"Good", "Moderate", "Unhealthy for sensitive groups", "Unhealthy", "Very unhealthy", "Hazardous"};
        for (int i = 0; i < limits.length; i++) if (aqi <= limits[i]) return names[i];
        return names[5];
    }
    public static String uvLabel(double uv) {
        if (!finite(uv)) return "Unavailable";
        return uv < 3 ? "Low" : uv < 6 ? "Moderate" : uv < 8 ? "High" : uv < 11 ? "Very high" : "Extreme";
    }
    public static String age(long at, long now) {
        if (at <= 0) return "time unknown";
        long minutes = (now-at)/60000L;
        if (minutes < -5) return "check tablet clock";
        if (minutes <= 0) return "just now";
        if (minutes < 60) return minutes + " min ago";
        if (minutes < 1440) return minutes / 60 + " h " + minutes % 60 + " min ago";
        return minutes / 1440 + " days ago";
    }
    public static String duration(double seconds) {
        if (!finite(seconds)) return MISSING;
        long minutes = Math.round(seconds / 60);
        return minutes / 60 + " h " + minutes % 60 + " min";
    }
    public static String moon(long now) {
        double phase = ((now - 947182440000L) / 86400000.0 / 29.530588853) % 1;
        if (phase < 0) phase += 1;
        String[] names = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous", "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};
        return names[((int)Math.floor(phase * 8 + 0.5)) % 8] + " \u00b7 "
                + Math.round((1 - Math.cos(2 * Math.PI * phase)) * 50) + "% illuminated (approx.)";
    }

    /** Hourly model guidance, explicitly NOT government warnings or a minute-by-minute nowcast. */
    public static List<String> guidance(Forecast s, long now, Units u) {
        List<String> tips = new ArrayList<String>();
        List<Hour> hours = s.upcoming(now, 12);
        if (hours.isEmpty()) { tips.add("No upcoming hourly forecast in the saved data. Refresh for a new outlook."); return tips; }
        for (Hour h : hours) if (h.code == 95 || h.code == 96 || h.code == 99) {
            tips.add("Thunderstorms appear in the hourly forecast. Check official alerts before outdoor plans."); break;
        }
        for (Hour h : hours) if (h.code == 56 || h.code == 57 || h.code == 66 || h.code == 67) {
            tips.add("Freezing precipitation is forecast; surfaces may be icy."); break;
        }
        for (Hour h : hours) if (h.probability >= 40 || h.rain >= 0.2 || h.snow > 0) {
            tips.add("Precipitation in the " + u.time(h.time, s.zone) + " forecast hour: " + pct(h.probability)
                    + " chance, " + u.rain(h.rain) + " total. Consider an umbrella or suitable layers."); break;
        }
        for (Hour h : hours) if (h.gust >= 50) { tips.add("Gusts of " + u.speed(h.gust) + " are forecast. Secure loose outdoor items."); break; }
        Day d = s.today(now);
        if (d != null && d.uv >= 3) tips.add("Today's maximum UV is " + measure(d.uv, 1, "") + " (" + uvLabel(d.uv) + "). Use sun protection outdoors.");
        double feels = s.current.feels;
        if (feels >= 30) tips.add("It feels hot. Stay hydrated and take shade breaks.");
        if (feels <= 0) tips.add("It feels below freezing. Dress in warm layers.");
        if (tips.isEmpty()) tips.add("Tap an hour or day for details. Conditions can change; check official alerts before travel.");
        return tips;
    }
}
