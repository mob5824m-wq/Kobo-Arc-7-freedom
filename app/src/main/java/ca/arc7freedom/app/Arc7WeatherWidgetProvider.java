package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Skinned Home Screen Weather & 7-Day Forecast AppWidget (`Arc7 Weather`).
 */
public class Arc7WeatherWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null) return;
        for (int i = 0; i < appWidgetIds.length; i++) {
            updateOne(context, appWidgetManager, appWidgetIds[i]);
        }
    }

    public static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, Arc7WeatherWidgetProvider.class));
            if (ids != null) {
                for (int i = 0; i < ids.length; i++) updateOne(ctx, mgr, ids[i]);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateOne(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_arc7_weather);
        Arc7WeatherStore.ForecastSnapshot snap = Arc7WeatherStore.loadCached(ctx);
        boolean fahr = Arc7WeatherStore.isFahrenheit(ctx);
        rv.setTextViewText(R.id.weather_widget_temp, Arc7WeatherStore.formatTemp(snap.tempC, fahr));
        rv.setTextViewText(R.id.weather_widget_unit,
                "FEELS " + Arc7WeatherStore.formatTemp(snap.feelsLikeC, fahr));
        rv.setTextViewText(R.id.weather_widget_city, snap.cityLabel.toUpperCase());
        rv.setTextViewText(R.id.weather_widget_summary, snap.summaryLine(fahr));

        Intent open = new Intent(ctx, Arc7WeatherActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 7709, open, PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.weather_widget_root, pi);
        mgr.updateAppWidget(id, rv);
    }
}
