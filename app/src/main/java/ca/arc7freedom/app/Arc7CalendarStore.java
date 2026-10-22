package ca.arc7freedom.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent storage, recurrence calculator, `.ics` (RFC 5545 iCalendar) importer/exporter,
 * built-in holiday generator, and `AlarmManager.RTC_WAKEUP` reminder scheduler for `Arc7 Calendar`.
 */
public final class Arc7CalendarStore {

    private Arc7CalendarStore() {}

    public static final String PREFS = "arc7_calendar_store";
    public static final String KEY_EVENTS_JSON = "events_json_v2";
    public static final String ACTION_CALENDAR_REMINDER = "ca.arc7freedom.action.CALENDAR_REMINDER";

    public static final int REPEAT_NONE = 0;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_WEEKLY = 2;
    public static final int REPEAT_MONTHLY = 3;
    public static final int REPEAT_YEARLY = 4;

    public static final String[] REPEAT_LABELS = new String[]{
            "Does not repeat", "Every day", "Every week", "Every month", "Every year"
    };

    public static final int[] REMINDER_MINS = new int[]{-1, 0, 10, 30, 60, 1440};
    public static final String[] REMINDER_LABELS = new String[]{
            "No reminder", "At time of event", "10 minutes before",
            "30 minutes before", "1 hour before", "1 day before"
    };

    public static final int[] CATEGORY_COLORS = new int[]{
            0xFF0EA5E9, // Personal / Sky
            0xFF10B981, // Work / Emerald
            0xFFF59E0B, // Reading / Amber
            0xFFEC4899, // Family / Rose
            0xFFA855F7, // Study / Violet
            0xFFEF4444  // Important / Coral Red
    };

    public static final String[] CATEGORY_NAMES = new String[]{
            "Personal", "Work", "Reading Goal", "Family & Friends", "Study", "Important"
    };

    public static final class CalEvent {
        public long id;
        public String title = "";
        public int year;
        public int month; // 0-based (0 = Jan .. 11 = Dec)
        public int day;   // 1..31
        public int startHour = 9;
        public int startMin = 0;
        public int durationMins = 60;
        public boolean allDay = false;
        public String location = "";
        public String notes = "";
        public int categoryIdx = 0;
        public int repeatMode = REPEAT_NONE;
        public int reminderMinutesBefore = 10; // -1 = none
        public boolean holidayBuiltIn = false;

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("year", year);
            o.put("month", month);
            o.put("day", day);
            o.put("startHour", startHour);
            o.put("startMin", startMin);
            o.put("durationMins", durationMins);
            o.put("allDay", allDay);
            o.put("location", location);
            o.put("notes", notes);
            o.put("categoryIdx", categoryIdx);
            o.put("repeatMode", repeatMode);
            o.put("reminderMinutesBefore", reminderMinutesBefore);
            return o;
        }

        public static CalEvent fromJson(JSONObject o) {
            CalEvent e = new CalEvent();
            e.id = o.optLong("id", System.currentTimeMillis());
            e.title = o.optString("title", "Untitled Event");
            e.year = o.optInt("year", 2026);
            e.month = o.optInt("month", 0);
            e.day = o.optInt("day", 1);
            e.startHour = o.optInt("startHour", 9);
            e.startMin = o.optInt("startMin", 0);
            e.durationMins = o.optInt("durationMins", 60);
            e.allDay = o.optBoolean("allDay", false);
            e.location = o.optString("location", "");
            e.notes = o.optString("notes", "");
            e.categoryIdx = o.optInt("categoryIdx", 0);
            e.repeatMode = o.optInt("repeatMode", REPEAT_NONE);
            e.reminderMinutesBefore = o.optInt("reminderMinutesBefore", 10);
            return e;
        }

        public int accentColor() {
            if (holidayBuiltIn) return 0xFFEF4444;
            int idx = Math.max(0, Math.min(CATEGORY_COLORS.length - 1, categoryIdx));
            return CATEGORY_COLORS[idx];
        }
    }

    public static List<CalEvent> loadSavedEvents(Context ctx) {
        List<CalEvent> out = new ArrayList<CalEvent>();
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_EVENTS_JSON, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(CalEvent.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void saveEvents(Context ctx, List<CalEvent> events) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < events.size(); i++) {
                if (!events.get(i).holidayBuiltIn) {
                    arr.put(events.get(i).toJson());
                }
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_EVENTS_JSON, arr.toString()).commit();
            scheduleReminders(ctx, events);
            refreshWidgets(ctx);
        } catch (Throwable ignored) {}
    }

    public static void upsertEvent(Context ctx, CalEvent ev) {
        List<CalEvent> all = loadSavedEvents(ctx);
        if (ev.id <= 0L) ev.id = System.currentTimeMillis();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == ev.id) {
                all.set(i, ev);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(ev);
        saveEvents(ctx, all);
    }

    public static void deleteEvent(Context ctx, long id) {
        List<CalEvent> all = loadSavedEvents(ctx);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id == id) {
                cancelReminder(ctx, all.get(i));
                all.remove(i);
            }
        }
        saveEvents(ctx, all);
    }

    public static boolean occursOnDate(CalEvent e, int year, int month0, int day) {
        Calendar target = Calendar.getInstance();
        target.set(year, month0, day, 12, 0, 0);
        target.set(Calendar.MILLISECOND, 0);

        Calendar origin = Calendar.getInstance();
        origin.set(e.year, e.month, e.day, 12, 0, 0);
        origin.set(Calendar.MILLISECOND, 0);

        if (target.getTimeInMillis() < origin.getTimeInMillis()) return false;
        if (e.year == year && e.month == month0 && e.day == day) return true;
        if (e.repeatMode == REPEAT_DAILY) return true;
        if (e.repeatMode == REPEAT_WEEKLY) {
            return target.get(Calendar.DAY_OF_WEEK) == origin.get(Calendar.DAY_OF_WEEK);
        }
        if (e.repeatMode == REPEAT_MONTHLY) {
            return day == e.day;
        }
        if (e.repeatMode == REPEAT_YEARLY) {
            return month0 == e.month && day == e.day;
        }
        return false;
    }

    public static List<CalEvent> builtInHolidaysForYear(int year) {
        List<CalEvent> h = new ArrayList<CalEvent>();
        addHoliday(h, year, 0, 1, "New Year's Day");
        addHoliday(h, year, 1, 14, "Valentine's Day");
        addHoliday(h, year, 2, 17, "St. Patrick's Day");
        addHoliday(h, year, 6, 1, "Canada Day");
        addHoliday(h, year, 6, 4, "Independence Day");
        addHoliday(h, year, 9, 31, "Halloween");
        addHoliday(h, year, 10, 11, "Remembrance / Veterans Day");
        addHoliday(h, year, 11, 24, "Christmas Eve");
        addHoliday(h, year, 11, 25, "Christmas Day");
        addHoliday(h, year, 11, 26, "Boxing Day");
        addHoliday(h, year, 11, 31, "New Year's Eve");
        return h;
    }

    private static void addHoliday(List<CalEvent> list, int y, int m0, int d, String name) {
        CalEvent e = new CalEvent();
        e.id = -(y * 10000L + m0 * 100L + d);
        e.year = y;
        e.month = m0;
        e.day = d;
        e.title = name;
        e.allDay = true;
        e.holidayBuiltIn = true;
        e.reminderMinutesBefore = -1;
        list.add(e);
    }

    public static List<CalEvent> eventsForDay(Context ctx, int year, int month0, int day) {
        List<CalEvent> matched = new ArrayList<CalEvent>();
        matched.addAll(builtInHolidaysForYear(year));
        matched.addAll(loadSavedEvents(ctx));
        List<CalEvent> res = new ArrayList<CalEvent>();
        for (int i = 0; i < matched.size(); i++) {
            CalEvent e = matched.get(i);
            if (occursOnDate(e, year, month0, day)) {
                res.add(e);
            }
        }
        Collections.sort(res, new Comparator<CalEvent>() {
            @Override
            public int compare(CalEvent a, CalEvent b) {
                if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
                return (a.startHour * 60 + a.startMin) - (b.startHour * 60 + b.startMin);
            }
        });
        return res;
    }

    public static void scheduleReminders(Context ctx, List<CalEvent> events) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < events.size(); i++) {
            CalEvent e = events.get(i);
            if (e.holidayBuiltIn || e.reminderMinutesBefore < 0) continue;
            Calendar c = Calendar.getInstance();
            c.set(e.year, e.month, e.day, e.allDay ? 9 : e.startHour, e.allDay ? 0 : e.startMin, 0);
            c.set(Calendar.MILLISECOND, 0);
            long trigger = c.getTimeInMillis() - (e.reminderMinutesBefore * 60000L);
            if (trigger > now - 30000L) {
                Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
                intent.setAction(ACTION_CALENDAR_REMINDER);
                intent.putExtra("title", e.title);
                intent.putExtra("location", e.location);
                PendingIntent pi = PendingIntent.getBroadcast(
                        ctx, (int) (e.id & 0x7FFFFFFF), intent, PendingIntent.FLAG_UPDATE_CURRENT);
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        }
    }

    private static void cancelReminder(Context ctx, CalEvent e) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
        intent.setAction(ACTION_CALENDAR_REMINDER);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, (int) (e.id & 0x7FFFFFFF), intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
    }

    public static void refreshWidgets(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, Arc7CalendarWidgetProvider.class));
            if (ids != null && ids.length > 0) {
                Intent i = new Intent(ctx, Arc7CalendarWidgetProvider.class);
                i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                ctx.sendBroadcast(i);
            }
        } catch (Throwable ignored) {}
    }

    /* ==================== RFC 5545 `.ics` Export & Import ==================== */

    public static String serializeToIcs(List<CalEvent> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Kobo Arc 7 Freedom//Arc7 Calendar 2.0//EN\r\n");
        for (int i = 0; i < all.size(); i++) {
            CalEvent e = all.get(i);
            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:arc7-").append(e.id).append("@arc7freedom.local\r\n");
            sb.append(String.format(Locale.US, "DTSTART:%04d%02d%02dT%02d%02d00\r\n",
                    e.year, e.month + 1, e.day, e.startHour, e.startMin));
            sb.append("SUMMARY:").append(e.title.replace("\n", " ")).append("\r\n");
            if (e.location.length() > 0) {
                sb.append("LOCATION:").append(e.location.replace("\n", " ")).append("\r\n");
            }
            if (e.notes.length() > 0) {
                sb.append("DESCRIPTION:").append(e.notes.replace("\n", "\\n")).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    public static File exportToIcs(Context ctx) throws Exception {
        File dir = new File(Environment.getExternalStorageDirectory(), "Arc7Freedom/Calendar");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "Arc7-Calendar-Backup.ics");
        String ics = serializeToIcs(loadSavedEvents(ctx));
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(ics.getBytes("UTF-8"));
        fos.close();
        return out;
    }

    public static List<CalEvent> parseIcsText(String icsContent) {
        List<CalEvent> parsed = new ArrayList<CalEvent>();
        String[] lines = icsContent.split("\r?\n");
        CalEvent cur = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if ("BEGIN:VEVENT".equalsIgnoreCase(line)) {
                cur = new CalEvent();
                cur.id = System.currentTimeMillis() + i;
            } else if ("END:VEVENT".equalsIgnoreCase(line)) {
                if (cur != null && cur.title.length() > 0) parsed.add(cur);
                cur = null;
            } else if (cur != null) {
                if (line.startsWith("SUMMARY:")) {
                    cur.title = line.substring(8).trim();
                } else if (line.startsWith("LOCATION:")) {
                    cur.location = line.substring(9).trim();
                } else if (line.startsWith("DESCRIPTION:")) {
                    cur.notes = line.substring(12).replace("\\n", "\n").trim();
                } else if (line.startsWith("DTSTART")) {
                    int col = line.indexOf(':');
                    if (col > 0) {
                        String dt = line.substring(col + 1).trim();
                        if (dt.length() >= 8) {
                            try {
                                cur.year = Integer.parseInt(dt.substring(0, 4));
                                cur.month = Math.max(0, Math.min(11, Integer.parseInt(dt.substring(4, 6)) - 1));
                                cur.day = Integer.parseInt(dt.substring(6, 8));
                                if (dt.length() >= 13 && dt.charAt(8) == 'T') {
                                    cur.allDay = false;
                                    cur.startHour = Integer.parseInt(dt.substring(9, 11));
                                    cur.startMin = Integer.parseInt(dt.substring(11, 13));
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        return parsed;
    }

    public static int importFromIcs(Context ctx, File icsFile) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(icsFile), "UTF-8"));
        List<CalEvent> all = loadSavedEvents(ctx);
        int count = 0;
        CalEvent cur = null;
        String line;
        try {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if ("BEGIN:VEVENT".equalsIgnoreCase(line)) {
                    cur = new CalEvent();
                    cur.id = System.currentTimeMillis() + count;
                } else if ("END:VEVENT".equalsIgnoreCase(line)) {
                    if (cur != null && cur.title.length() > 0) {
                        all.add(cur);
                        count++;
                    }
                    cur = null;
                } else if (cur != null) {
                    if (line.startsWith("SUMMARY:")) {
                        cur.title = line.substring(8).trim();
                    } else if (line.startsWith("LOCATION:")) {
                        cur.location = line.substring(9).trim();
                    } else if (line.startsWith("DESCRIPTION:")) {
                        cur.notes = line.substring(12).replace("\\n", "\n").trim();
                    } else if (line.startsWith("DTSTART")) {
                        int col = line.indexOf(':');
                        if (col > 0) {
                            String dt = line.substring(col + 1).trim();
                            if (dt.length() >= 8) {
                                cur.year = Integer.parseInt(dt.substring(0, 4));
                                cur.month = Integer.parseInt(dt.substring(4, 6)) - 1;
                                cur.day = Integer.parseInt(dt.substring(6, 8));
                            }
                            if (dt.length() >= 13 && dt.charAt(8) == 'T') {
                                cur.startHour = Integer.parseInt(dt.substring(9, 11));
                                cur.startMin = Integer.parseInt(dt.substring(11, 13));
                            } else {
                                cur.allDay = true;
                            }
                        }
                    }
                }
            }
        } finally {
            br.close();
        }
        saveEvents(ctx, all);
        return count;
    }

    public static String formatTimeRange(Context ctx, CalEvent e) {
        if (e.allDay) return "All-day";
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, e.startHour);
        c.set(Calendar.MINUTE, e.startMin);
        SimpleDateFormat fmt = new SimpleDateFormat(
                Arc7ClockStore.is12HourMode(ctx) ? "h:mm a" : "HH:mm", Locale.US);
        Date start = c.getTime();
        c.add(Calendar.MINUTE, Math.max(15, e.durationMins));
        return fmt.format(start) + " – " + fmt.format(c.getTime());
    }
}
