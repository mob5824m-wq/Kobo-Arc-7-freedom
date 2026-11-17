package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Selected-city weather, real freshness, today's range and a separate manual refresh target. */
public class Arc7WeatherWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        if (ids != null) for (int id : ids) updateOne(c,m,id);
        Arc7WeatherService.schedule(c);
        if (Arc7WeatherService.owner(c) && Arc7WeatherStore.refreshMinutes(c)>0) Arc7WeatherService.request(c,false);
    }
    @Override public void onEnabled(Context c) { Arc7WeatherService.schedule(c); }
    @Override public void onDisabled(Context c) { Arc7WeatherService.schedule(c); }

    /** Render only: never triggers a fetch/recursive widget update loop. */
    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c,Arc7WeatherWidgetProvider.class));
        if (ids != null) for (int id : ids) updateOne(c,m,id);
    }
    private static void updateOne(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(),R.layout.widget_arc7_weather);
        Arc7WeatherData.Place p = Arc7WeatherService.owner(c) ? Arc7WeatherStore.selected(c) : null;
        Arc7WeatherData.Forecast f = p == null ? null : Arc7WeatherStore.forecast(c,p);
        Arc7WeatherData.Units u = Arc7WeatherStore.units(c);
        long now = System.currentTimeMillis();
        v.setTextViewText(R.id.weather_widget_city,p == null ? "WEATHER" : p.name);
        v.setTextViewText(R.id.weather_widget_temp,f == null ? Arc7WeatherData.MISSING : u.temp(f.current.temp));
        v.setTextViewText(R.id.weather_widget_unit,f == null ? "NO DATA" : "FEELS " + u.temp(f.current.feels));
        String summary;
        if (!Arc7WeatherService.owner(c)) summary = "Use the widget supplied by the separate Weather app. Tap to open/install it.";
        else if (p == null) summary = "Tap to add a city or coordinates. Device location is optional.";
        else if (f == null) summary = "No saved forecast for this city. Tap Refresh or open Weather.";
        else {
            Arc7WeatherData.Day today = f.today(now);
            summary = Arc7WeatherData.description(f.current.code);
            if (today != null) summary += " \u00b7 H " + u.temp(today.high) + " / L " + u.temp(today.low)
                    + " \u00b7 Rain " + Arc7WeatherData.pct(today.probability);
        }
        v.setTextViewText(R.id.weather_widget_summary,summary);
        String error = p == null ? "" : Arc7WeatherStore.error(c,p,Arc7WeatherStore.FORECAST);
        String status = f == null ? "Not updated" : (f.stale(now) ? "STALE \u00b7 " : "Saved ") + Arc7WeatherData.formatTime(f.fetchedAt,f.zone,u.clock12 ? "MMM d h:mm a" : "MMM d HH:mm");
        if (error.length()>0) status += " \u00b7 Refresh failed";
        v.setTextViewText(R.id.weather_widget_status,status);
        Intent open = new Intent(c,Arc7WeatherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent click = PendingIntent.getActivity(c,7709,SplitAppRouter.route(c,open,-1),PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.weather_widget_root,click);
        v.setOnClickPendingIntent(R.id.weather_widget_refresh,Arc7WeatherService.owner(c) ? Arc7WeatherService.refreshIntent(c) : click);
        m.updateAppWidget(id,v);
    }
}
