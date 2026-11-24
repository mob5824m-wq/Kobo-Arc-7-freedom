package ca.arc7freedom.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * Deterministic tests of alarm scheduling, cancellation, snooze cleanup,
 * receiver enabled-state guards, and legacy suite isolation.
 */
public final class AlarmStateHarness {
    private static void check(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }

    private static Context newClockContext() {
        return new Context("ca.arc7freedom.clock");
    }

    private static Context newSuiteContext() {
        return new Context("ca.arc7freedom.app");
    }

    private static Intent alarmTriggerIntent(Context ctx, int alarmId) {
        Intent i = new Intent(ctx, Arc7AlarmReceiver.class);
        i.setAction(Arc7ClockStore.ACTION_ALARM_TRIGGER);
        i.putExtra("alarm_id", alarmId);
        i.putExtra("label", "Test Alarm");
        i.putExtra("chime_id", Arc7ClockStore.CHIME_APPLE_MARIMBA);
        i.putExtra("volume_pct", 85);
        return i;
    }

    private static void disabledAndDeletedAlarmsNeverRing() {
        Context ctx = newClockContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Arc7AlarmReceiver receiver = new Arc7AlarmReceiver();

        List<Arc7ClockStore.AlarmEntry> alarms = Arc7ClockStore.loadAlarms(ctx);
        Arc7ClockStore.AlarmEntry first = alarms.get(0);
        first.enabled = true;
        first.repeatMask = Arc7ClockStore.REPEAT_EVERY_DAY;
        Arc7ClockStore.saveAlarms(ctx, alarms);
        Arc7ClockStore.scheduleOrCancelAlarm(ctx, first);
        check(am.activeCount() == 1, "Enabled alarm was not scheduled");

        // Turn the alarm OFF: AlarmManager and PendingIntent must both be cancelled.
        first.enabled = false;
        Arc7ClockStore.saveAlarms(ctx, alarms);
        Arc7ClockStore.scheduleOrCancelAlarm(ctx, first);
        check(am.activeCount() == 0, "Disabled alarm remained scheduled");
        PendingIntent pi = am.lastCancelledPendingIntent;
        check(pi != null && pi.cancelled, "Disabled alarm PendingIntent was not cancelled");

        // Even if a stale broadcast arrives for the disabled alarm, it must not ring or open alert.
        ctx.startedActivities.clear();
        receiver.onReceive(ctx, alarmTriggerIntent(ctx, first.id));
        check(ctx.startedActivities.isEmpty(), "Disabled alarm still launched alert activity!");
        check(!Arc7ClockStore.isRingingActive(), "Disabled alarm still started ringing!");

        // Even if a stale broadcast arrives for a deleted alarm id, it must not ring or open alert.
        ctx.startedActivities.clear();
        receiver.onReceive(ctx, alarmTriggerIntent(ctx, 9999));
        check(ctx.startedActivities.isEmpty(), "Deleted alarm still launched alert activity!");
        check(!Arc7ClockStore.isRingingActive(), "Deleted alarm still started ringing!");
        System.out.println("PASS: disabled and deleted alarms cancel PendingIntents and never ring on stale broadcasts");
    }

    private static void oneShotAndRepeatingTransitions() {
        Context ctx = newClockContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Arc7AlarmReceiver receiver = new Arc7AlarmReceiver();

        Arc7ClockStore.AlarmEntry once = new Arc7ClockStore.AlarmEntry();
        once.id = Arc7ClockStore.nextAlarmId(ctx);
        once.hour = 7;
        once.minute = 15;
        once.repeatMask = Arc7ClockStore.REPEAT_ONCE;
        once.enabled = true;
        List<Arc7ClockStore.AlarmEntry> list = Arc7ClockStore.loadAlarms(ctx);
        list.add(once);
        Arc7ClockStore.saveAlarms(ctx, list);
        Arc7ClockStore.scheduleOrCancelAlarm(ctx, once);
        check(am.activeCount() == 1, "One-time alarm was not scheduled");

        // First trigger fires once and disables the one-time alarm.
        receiver.onReceive(ctx, alarmTriggerIntent(ctx, once.id));
        check(ctx.startedActivities.size() == 1, "Enabled one-time alarm did not launch alert");
        Arc7ClockStore.stopAlarmRinging();
        List<Arc7ClockStore.AlarmEntry> after = Arc7ClockStore.loadAlarms(ctx);
        Arc7ClockStore.AlarmEntry saved = null;
        for (Arc7ClockStore.AlarmEntry e : after) {
            if (e.id == once.id) saved = e;
        }
        check(saved != null && !saved.enabled, "One-time alarm stayed enabled after firing");
        check(am.activeCount() == 0, "One-time alarm stayed scheduled after firing");

        // Second stale trigger for the now-disabled one-time alarm must be ignored.
        ctx.startedActivities.clear();
        receiver.onReceive(ctx, alarmTriggerIntent(ctx, once.id));
        check(ctx.startedActivities.isEmpty(), "Already-fired one-time alarm fired a second time");
        check(!Arc7ClockStore.isRingingActive(), "Already-fired one-time alarm rang a second time");
        System.out.println("PASS: one-time alarm disables cleanly and rejects duplicate triggers");
    }

    private static void snoozeRespectsAlarmOffAndOneShotSnoozeStillWorks() {
        Context ctx = newClockContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Arc7AlarmReceiver receiver = new Arc7AlarmReceiver();

        // Case A: repeating alarm snoozed, then turned OFF before snooze expires.
        List<Arc7ClockStore.AlarmEntry> alarms = Arc7ClockStore.loadAlarms(ctx);
        Arc7ClockStore.AlarmEntry rep = alarms.get(0);
        rep.enabled = true;
        rep.repeatMask = Arc7ClockStore.REPEAT_EVERY_DAY;
        Arc7ClockStore.saveAlarms(ctx, alarms);
        Arc7ClockStore.scheduleSnooze(ctx, rep.label, 5, rep.chimeId, rep.volumePercent, rep.id);
        check(am.activeCount() == 1, "Snooze was not scheduled");

        rep.enabled = false;
        Arc7ClockStore.saveAlarms(ctx, alarms);
        Arc7ClockStore.scheduleOrCancelAlarm(ctx, rep);
        check(am.activeCount() == 0, "Disabling alarm did not cancel its pending snooze");

        Intent staleSnooze = new Intent();
        staleSnooze.setAction(Arc7ClockStore.ACTION_ALARM_TRIGGER);
        staleSnooze.putExtra("alarm_id", -1);
        staleSnooze.putExtra("is_snooze", true);
        staleSnooze.putExtra("snooze_alarm_id", rep.id);
        receiver.onReceive(ctx, staleSnooze);
        check(ctx.startedActivities.isEmpty(), "Cancelled snooze still rang after alarm was turned off");

        // Case B: one-time alarm triggers (auto-disables), user snoozes -> snooze MUST still ring once.
        Arc7ClockStore.AlarmEntry once = new Arc7ClockStore.AlarmEntry();
        once.id = Arc7ClockStore.nextAlarmId(ctx);
        once.hour = 8;
        once.minute = 0;
        once.repeatMask = Arc7ClockStore.REPEAT_ONCE;
        once.enabled = true;
        alarms = Arc7ClockStore.loadAlarms(ctx);
        alarms.add(once);
        Arc7ClockStore.saveAlarms(ctx, alarms);
        receiver.onReceive(ctx, alarmTriggerIntent(ctx, once.id));
        Arc7ClockStore.stopAlarmRinging();
        ctx.startedActivities.clear();

        Arc7ClockStore.scheduleSnooze(ctx, once.label, 5, once.chimeId, once.volumePercent, once.id);
        Intent onceSnooze = new Intent();
        onceSnooze.setAction(Arc7ClockStore.ACTION_ALARM_TRIGGER);
        onceSnooze.putExtra("alarm_id", -1);
        onceSnooze.putExtra("is_snooze", true);
        onceSnooze.putExtra("snooze_alarm_id", once.id);
        receiver.onReceive(ctx, onceSnooze);
        check(ctx.startedActivities.size() == 1, "Legitimate one-time alarm snooze failed to ring");
        Arc7ClockStore.stopAlarmRinging();
        ctx.startedActivities.clear();

        // Replay of the already-consumed snooze must not ring again.
        receiver.onReceive(ctx, onceSnooze);
        check(ctx.startedActivities.isEmpty(), "Consumed snooze rang twice");
        System.out.println("PASS: snooze cancels when alarm is turned off, works for one-time alarms, and never replays");
    }

    private static void cancelledTimerAndLegacySuiteIsolation() {
        Context ctx = newClockContext();
        Arc7AlarmReceiver receiver = new Arc7AlarmReceiver();
        Arc7ClockStore.saveTimerState(ctx, true, System.currentTimeMillis() + 60000L, 0L, "Tea");
        Arc7ClockStore.saveTimerState(ctx, false, 0L, 0L, "");
        Intent timerIntent = new Intent().setAction(Arc7ClockStore.ACTION_TIMER_TRIGGER);
        receiver.onReceive(ctx, timerIntent);
        check(ctx.startedActivities.isEmpty(), "Cancelled timer still rang on stale broadcast");

        // Legacy suite package (`ca.arc7freedom.app`) must never reschedule or ring Clock alarms.
        Context suiteCtx = newSuiteContext();
        AlarmManager suiteAm = (AlarmManager) suiteCtx.getSystemService(Context.ALARM_SERVICE);
        List<Arc7ClockStore.AlarmEntry> legacy = Arc7ClockStore.loadAlarms(suiteCtx);
        legacy.get(0).enabled = true;
        Arc7ClockStore.saveAlarms(suiteCtx, legacy);
        // Simulate an old PendingIntent left over from before split-app separation.
        PendingIntent staleSuitePi = PendingIntent.getBroadcast(
                suiteCtx, 50000 + legacy.get(0).id,
                alarmTriggerIntent(suiteCtx, legacy.get(0).id), PendingIntent.FLAG_UPDATE_CURRENT);
        suiteAm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000L, staleSuitePi);
        check(suiteAm.activeCount() == 1, "Test setup failed to stage legacy suite alarm");

        Arc7ClockStore.rescheduleAllOnBoot(suiteCtx);
        check(suiteAm.activeCount() == 0, "Suite rescheduleAllOnBoot kept or rescheduled legacy Clock alarms!");
        receiver.onReceive(suiteCtx, alarmTriggerIntent(suiteCtx, legacy.get(0).id));
        check(suiteCtx.startedActivities.isEmpty(), "Suite Arc7AlarmReceiver still rang legacy Clock alarm!");

        // Unique ID guard even if KEY_NEXT_ID lags behind stored alarm IDs.
        suiteCtx.getSharedPreferences("arc7_clock_store", 0).edit().putInt("next_alarm_id", 1).commit();
        int nextId = Arc7ClockStore.nextAlarmId(suiteCtx);
        for (Arc7ClockStore.AlarmEntry e : legacy) {
            check(nextId > e.id, "nextAlarmId collided with existing alarm id " + e.id);
        }
        System.out.println("PASS: cancelled timers stay silent, legacy suite alarms are purged/blocked, and alarm IDs stay unique");
    }

    public static void main(String[] args) {
        disabledAndDeletedAlarmsNeverRing();
        oneShotAndRepeatingTransitions();
        snoozeRespectsAlarmOffAndOneShotSnoozeStillWorks();
        cancelledTimerAndLegacySuiteIsolation();
    }
}
