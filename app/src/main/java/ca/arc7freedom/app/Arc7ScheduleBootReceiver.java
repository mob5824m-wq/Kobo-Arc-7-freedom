package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restore only this APK's schedules, without starting the suite or its network services. */
public class Arc7ScheduleBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        if (intent == null || !(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if ("ca.arc7freedom.clock".equals(context.getPackageName())) {
                        Arc7ClockStore.rescheduleAllOnBoot(context);
                    } else if ("ca.arc7freedom.calendar".equals(context.getPackageName())) {
                        Arc7CalendarStore.scheduleReminders(context, Arc7CalendarStore.loadSavedEvents(context));
                    }
                } finally { pending.finish(); }
            }
        }, "AppScheduleRestore").start();
    }
}
