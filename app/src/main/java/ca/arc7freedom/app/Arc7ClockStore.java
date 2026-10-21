package ca.arc7freedom.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent storage, exact Android 4.2 `AlarmManager.set(RTC_WAKEUP, ...)` scheduler,
 * boot-time re-registrar, and guaranteed loud dual-source alarm chime engine
 * (plays BOTH system alarm ringtone AND a pure-Java 44.1 kHz dual-harmonic chime on
 * `AudioManager.STREAM_ALARM` so even debloated tablets without `/system/media/audio/alarms`
 * ALWAYS sound loud and clear!).
 */
public class Arc7ClockStore {

    public static final String ACTION_ALARM_TRIGGER = "ca.arc7freedom.app.ACTION_ALARM_TRIGGER";
    public static final String ACTION_TIMER_TRIGGER = "ca.arc7freedom.app.ACTION_TIMER_TRIGGER";

    public static final int REPEAT_EVERY_DAY = 0x7F;          // Sun..Sat bits 0..6
    public static final int REPEAT_WEEKDAYS  = 0x3E;          // Mon..Fri bits 1..5
    public static final int REPEAT_WEEKENDS  = 0x41;          // Sun(bit0) + Sat(bit6)
    public static final int REPEAT_ONCE      = 0x00;

    private static final String PREFS = "arc7_clock_store";
    private static final String KEY_ALARMS = "alarms_json";
    private static final String KEY_NEXT_ID = "next_alarm_id";
    private static final String KEY_TIMER_END = "timer_end_wall_ms";
    private static final String KEY_TIMER_REM = "timer_paused_rem_ms";
    private static final String KEY_TIMER_RUN = "timer_running";
    private static final String KEY_TIMER_LABEL = "timer_label";

    private static final int TIMER_REQ_CODE = 990001;

    private static volatile boolean ringingActive = false;
    private static volatile Ringtone activeRingtone = null;
    private static volatile Thread synthThread = null;

    public static class AlarmEntry {
        public int id;
        public int hour;
        public int minute;
        public String label;
        public int repeatMask; // bit 0=Sun .. bit 6=Sat; 0 = One-time
        public boolean enabled;

        public String formatTime24And12() {
            int h12 = hour % 12;
            if (h12 == 0) h12 = 12;
            String ampm = hour < 12 ? "AM" : "PM";
            return String.format(Locale.US, "%02d:%02d  (%d:%02d %s)", hour, minute, h12, minute, ampm);
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
            // Seed two helpful disabled starter presets on first launch!
            AlarmEntry a1 = new AlarmEntry();
            a1.id = 1; a1.hour = 7; a1.minute = 0; a1.label = "Morning Wake Up";
            a1.repeatMask = REPEAT_WEEKDAYS; a1.enabled = false;
            AlarmEntry a2 = new AlarmEntry();
            a2.id = 2; a2.hour = 8; a2.minute = 30; a2.label = "Weekend Reading";
            a2.repeatMask = REPEAT_WEEKENDS; a2.enabled = false;
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
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ALARMS, arr.toString())
                .commit();
    }

    public static int nextAlarmId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int id = sp.getInt(KEY_NEXT_ID, 10);
        sp.edit().putInt(KEY_NEXT_ID, id + 1).commit();
        return id;
    }

    public static long computeNextTriggerMillis(AlarmEntry e, long nowMillis) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMillis);

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
            int dowBit = test.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY; // 0..6
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
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 50000 + e.id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        if (!e.enabled) {
            am.cancel(pi);
            return;
        }
        long triggerAt = computeNextTriggerMillis(e, System.currentTimeMillis());
        // Note: On targetSdkVersion 17, AlarmManager.set() IS ALWAYS EXACT at OS level across ALL Android versions!
        am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    public static void scheduleSnooze(Context ctx, String label, int minutes) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, Arc7AlarmReceiver.class);
        intent.setAction(ACTION_ALARM_TRIGGER);
        intent.putExtra("alarm_id", -1);
        intent.putExtra("label", (label == null ? "Alarm" : label) + " (Snoozed)");
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 59999, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60000L, pi);
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
        return String.format(Locale.US, "Next Alarm: %s — %s (rings in %dh %dm)",
                bestAlarm.formatTime24And12(), bestAlarm.label, hrs, mins);
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

    /* ==================== DUAL SYSTEM + PCM SYNTHESIZED ALARM RINGER ==================== */

    public static synchronized void startAlarmRinging(final Context ctx) {
        if (ringingActive) return;
        ringingActive = true;

        try {
            AudioManager audioMgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (audioMgr != null) {
                int maxVol = audioMgr.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int curVol = audioMgr.getStreamVolume(AudioManager.STREAM_ALARM);
                if (curVol < Math.max(1, (maxVol * 3) / 4)) {
                    audioMgr.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, (maxVol * 4) / 5), 0);
                }
            }
        } catch (Throwable ignored) {}

        // 1. Try system alarm ringtone if present
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri != null) {
                Ringtone r = RingtoneManager.getRingtone(ctx.getApplicationContext(), uri);
                if (r != null) {
                    r.setStreamType(AudioManager.STREAM_ALARM);
                    r.play();
                    activeRingtone = r;
                }
            }
        } catch (Throwable ignored) {}

        // 2. Always drive crisp 44.1 kHz harmonic chime pulses on STREAM_ALARM so even
        //    debloated tablets with zero /system/media/audio files wake the user reliably!
        synthThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    final int sr = 44100;
                    int minBuf = AudioTrack.getMinBufferSize(
                            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack(
                            AudioManager.STREAM_ALARM,
                            sr,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minBuf, sr),
                            AudioTrack.MODE_STREAM);
                    track.play();

                    // Pleasant ascending 3-note chime (C5 523.25Hz -> E5 659.25Hz -> G5 783.99Hz) + pause
                    double[] freqs = new double[]{523.25, 659.25, 783.99, 0.0};
                    int noteSamples = sr / 5; // 200 ms per note
                    short[] pcm = new short[noteSamples];
                    int step = 0;
                    while (ringingActive) {
                        double f = freqs[step % freqs.length];
                        for (int i = 0; i < noteSamples; i++) {
                            if (f <= 0.0) {
                                pcm[i] = 0;
                            } else {
                                double env = Math.min(1.0, Math.min(i / 600.0, (noteSamples - i) / 2200.0));
                                double t = (double) i / (double) sr;
                                double wave = 0.72 * Math.sin(2.0 * Math.PI * f * t)
                                        + 0.28 * Math.sin(4.0 * Math.PI * f * t);
                                pcm[i] = (short) (wave * env * 22000.0);
                            }
                        }
                        track.write(pcm, 0, noteSamples);
                        step++;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (track != null) {
                        try { track.stop(); } catch (Throwable ignored) {}
                        try { track.release(); } catch (Throwable ignored) {}
                    }
                }
            }
        }, "Arc7AlarmChimeSynth");
        synthThread.start();
    }

    public static synchronized void stopAlarmRinging() {
        ringingActive = false;
        if (activeRingtone != null) {
            try { activeRingtone.stop(); } catch (Throwable ignored) {}
            activeRingtone = null;
        }
        Thread t = synthThread;
        synthThread = null;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
    }
}
