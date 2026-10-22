package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Skinned Home Screen Month + Upcoming Agenda AppWidget (`Arc7 Calendar & Agenda`).
 */
public class Arc7CalendarWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null) return;
        for (int i = 0; i < appWidgetIds.length; i++) {
            updateOne(context, appWidgetManager, appWidgetIds[i]);
        }
    }

    private static void updateOne(Context ctx, AppWidgetManager mgr, int appWidgetId) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_arc7_calendar);
        Calendar now = Calendar.getInstance();
        Date d = now.getTime();
        rv.setTextViewText(R.id.cal_widget_day_num, String.valueOf(now.get(Calendar.DAY_OF_MONTH)));
        rv.setTextViewText(R.id.cal_widget_month,
                new SimpleDateFormat("EEEE • MMMM yyyy", Locale.US).format(d).toUpperCase(Locale.US));

        StringBuilder agenda = new StringBuilder();
        int shown = 0;
        Calendar cursor = (Calendar) now.clone();
        for (int dayOff = 0; dayOff < 14 && shown < 4; dayOff++) {
            List<Arc7CalendarStore.CalEvent> dayEvents = Arc7CalendarStore.eventsForDay(
                    ctx, cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH), cursor.get(Calendar.DAY_OF_MONTH));
            for (int j = 0; j < dayEvents.size() && shown < 4; j++) {
                Arc7CalendarStore.CalEvent e = dayEvents.get(j);
                String prefix = dayOff == 0 ? "Today"
                        : (dayOff == 1 ? "Tomorrow"
                        : new SimpleDateFormat("MMM d", Locale.US).format(cursor.getTime()));
                if (agenda.length() > 0) agenda.append('\n');
                agenda.append("• ").append(prefix).append(" (")
                      .append(Arc7CalendarStore.formatTimeRange(ctx, e)).append(") — ").append(e.title);
                shown++;
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (agenda.length() == 0) {
            agenda.append("No events in the next 14 days — tap to add an event or reminder!");
        }
        rv.setTextViewText(R.id.cal_widget_agenda, agenda.toString());

        Intent openCal = new Intent(ctx, Arc7CalendarActivity.class);
        openCal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 7701, openCal, PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.cal_widget_root, pi);
        mgr.updateAppWidget(appWidgetId, rv);
    }
}
