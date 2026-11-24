package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        // The standalone `Clock` app (ca.arc7freedom.clock) has no BootNetworkReceiver, so it
        // restores every repeating alarm / running timer itself after a reboot or clock change.
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            Arc7ClockStore.rescheduleAllOnBoot(context);
            return;
        }
        boolean isCal = Arc7CalendarStore.ACTION_CALENDAR_REMINDER.equals(action);
        boolean isTimer = Arc7ClockStore.ACTION_TIMER_TRIGGER.equals(action) || isCal;
        if (!isCal && Arc7ClockStore.isLegacySuitePackage(context)) {
            Arc7ClockStore.cancelAllAlarmsAndTimers(context);
            return;
        }
        String label = isCal
                ? ("Calendar: " + intent.getStringExtra("title")
                    + (intent.getStringExtra("location") != null && intent.getStringExtra("location").length() > 0
                        ? " @ " + intent.getStringExtra("location") : ""))
                : intent.getStringExtra("label");
        int alarmId = intent.getIntExtra("alarm_id", -1);
        int snoozeAlarmId = intent.getIntExtra("snooze_alarm_id", -1);
        String chimeId = intent.getStringExtra("chime_id");
        int volumePct = intent.getIntExtra("volume_pct", 85);

        // Arc7ClockStore takes a process-owned PARTIAL_WAKE_LOCK before its chime thread
        // starts. The alert Activity's window flags wake the display; keeping a temporary
        // WakeLock only inside this receiver would allow the CPU to suspend after onReceive.

        if (isTimer) {
            if (!isCal && !Arc7ClockStore.isTimerRunning(context)) {
                Arc7ClockStore.cancelCountdownWakeup(context);
                return;
            }
            Arc7ClockStore.saveTimerState(context, false, 0L, 0L, label);
            if (chimeId == null) chimeId = Arc7ClockStore.CHIME_APPLE_RADAR;
        } else if (alarmId > 0) {
            List<Arc7ClockStore.AlarmEntry> alarms = Arc7ClockStore.loadAlarms(context);
            Arc7ClockStore.AlarmEntry matched = null;
            for (Arc7ClockStore.AlarmEntry e : alarms) {
                if (e.id == alarmId) {
                    matched = e;
                    break;
                }
            }
            if (matched == null) {
                Arc7ClockStore.cancelAlarmPendingIntent(context, alarmId);
                Arc7ClockWidgetProvider.refreshAll(context);
                return;
            }
            if (!matched.enabled) {
                Arc7ClockStore.scheduleOrCancelAlarm(context, matched);
                return;
            }
            chimeId = matched.chimeId;
            volumePct = matched.volumePercent;
            if ((label == null || label.trim().length() == 0) && matched.label != null && matched.label.trim().length() > 0) {
                label = matched.label;
            }
            if (matched.repeatMask == Arc7ClockStore.REPEAT_ONCE) {
                matched.enabled = false;
                Arc7ClockStore.saveAlarms(context, alarms);
                Arc7ClockStore.cancelAlarmPendingIntent(context, matched.id);
            } else {
                Arc7ClockStore.scheduleOrCancelAlarm(context, matched);
            }
        } else {
            boolean isSnooze = intent.getBooleanExtra("is_snooze", true);
            if (isSnooze && !Arc7ClockStore.consumePendingSnooze(context, snoozeAlarmId)) {
                return;
            }
        }

        Arc7ClockStore.startAlarmRinging(context.getApplicationContext(), chimeId, volumePct);

        Intent alert = new Intent(context, Arc7AlarmAlertActivity.class);
        alert.putExtra("is_timer", isTimer);
        alert.putExtra("alarm_id", alarmId > 0 ? alarmId : snoozeAlarmId);
        alert.putExtra("label", label == null ? (isTimer ? "Countdown Timer Finished!" : "Alarm") : label);
        alert.putExtra("chime_id", chimeId);
        alert.putExtra("volume_pct", volumePct);
        alert.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        context.startActivity(alert);
    }
}
