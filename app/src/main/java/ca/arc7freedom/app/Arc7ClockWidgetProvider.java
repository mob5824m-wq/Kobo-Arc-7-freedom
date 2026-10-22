package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;

/**
 * Built-in Home Screen Digital Clock + Next Scheduled Alarm + 1-Tap Nightstand Flip Widget.
 */
public class Arc7ClockWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context, appWidgetManager, appWidgetIds);
    }

    public static void refreshAll(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, Arc7ClockWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                updateAll(context, mgr, ids);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        String alarmSummary = "Tap for Alarms & Timers";
        try {
            List<Arc7ClockStore.AlarmEntry> alarms = Arc7ClockStore.loadAlarms(context);
            int enabledCount = 0;
            Arc7ClockStore.AlarmEntry earliest = null;
            long earliestMs = Long.MAX_VALUE;
            long now = System.currentTimeMillis();
            for (int i = 0; i < alarms.size(); i++) {
                Arc7ClockStore.AlarmEntry a = alarms.get(i);
                if (a.enabled) {
                    enabledCount++;
                    long t = Arc7ClockStore.computeNextTriggerMillis(a, now);
                    if (t < earliestMs) {
                        earliestMs = t;
                        earliest = a;
                    }
                }
            }
            if (earliest != null) {
                alarmSummary = String.format(Locale.US, "\u23F0 Next %s (%d on)",
                        earliest.formatTimePreferred(context), enabledCount);
            }
        } catch (Throwable ignored) {}

        Intent openClock = new Intent(context, Arc7ClockActivity.class);
        openClock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piClock = PendingIntent.getActivity(
                context, 7101, openClock, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openFlip = new Intent(context, Arc7DeskClockActivity.class);
        openFlip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piFlip = PendingIntent.getActivity(
                context, 7102, openFlip, PendingIntent.FLAG_UPDATE_CURRENT);

        for (int i = 0; i < ids.length; i++) {
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_arc7_clock);
            rv.setTextViewText(R.id.widget_clock_alarm_status, alarmSummary);
            rv.setOnClickPendingIntent(R.id.widget_clock_root, piClock);
            rv.setOnClickPendingIntent(R.id.widget_clock_time, piClock);
            rv.setOnClickPendingIntent(R.id.widget_clock_date, piClock);
            rv.setOnClickPendingIntent(R.id.widget_clock_alarm_status, piClock);
            rv.setOnClickPendingIntent(R.id.widget_clock_flip_btn, piFlip);
            mgr.updateAppWidget(ids[i], rv);
        }
    }
}
