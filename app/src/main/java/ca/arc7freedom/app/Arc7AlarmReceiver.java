package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import java.util.List;

/**
 * Wakes the Kobo Arc 7 / 7HD CPU + screen on scheduled `RTC_WAKEUP` Alarm or Countdown Timer
 * completion, advances repeating alarms (or disables one-shot alarms), starts that individual
 * alarm's chosen chime + custom `0%–100%` volume, and opens `Arc7AlarmAlertActivity`!
 */
public class Arc7AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean isCal = Arc7CalendarStore.ACTION_CALENDAR_REMINDER.equals(action);
        boolean isTimer = Arc7ClockStore.ACTION_TIMER_TRIGGER.equals(action) || isCal;
        String label = isCal
                ? ("Calendar: " + intent.getStringExtra("title")
                    + (intent.getStringExtra("location") != null && intent.getStringExtra("location").length() > 0
                        ? " @ " + intent.getStringExtra("location") : ""))
                : intent.getStringExtra("label");
        int alarmId = intent.getIntExtra("alarm_id", -1);
        String chimeId = intent.getStringExtra("chime_id");
        int volumePct = intent.getIntExtra("volume_pct", 85);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "Arc7Freedom:AlarmTriggerWake");
            wl.setReferenceCounted(false);
            wl.acquire(45000L);
        }

        if (isTimer) {
            Arc7ClockStore.saveTimerState(context, false, 0L, 0L, label);
            if (chimeId == null) chimeId = Arc7ClockStore.CHIME_APPLE_RADAR;
        } else if (alarmId > 0) {
            List<Arc7ClockStore.AlarmEntry> alarms = Arc7ClockStore.loadAlarms(context);
            for (Arc7ClockStore.AlarmEntry e : alarms) {
                if (e.id == alarmId) {
                    chimeId = e.chimeId;
                    volumePct = e.volumePercent;
                    if (e.repeatMask == Arc7ClockStore.REPEAT_ONCE) {
                        e.enabled = false;
                        Arc7ClockStore.saveAlarms(context, alarms);
                    } else {
                        Arc7ClockStore.scheduleOrCancelAlarm(context, e);
                    }
                    break;
                }
            }
        }

        Arc7ClockStore.startAlarmRinging(context.getApplicationContext(), chimeId, volumePct);

        Intent alert = new Intent(context, Arc7AlarmAlertActivity.class);
        alert.putExtra("is_timer", isTimer);
        alert.putExtra("label", label == null ? (isTimer ? "Countdown Timer Finished!" : "Alarm") : label);
        alert.putExtra("chime_id", chimeId);
        alert.putExtra("volume_pct", volumePct);
        alert.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        context.startActivity(alert);
    }
}
