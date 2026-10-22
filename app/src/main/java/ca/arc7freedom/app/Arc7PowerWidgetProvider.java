package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Built-in Home Screen Power Dock Widget (Live TLS 1.3 Status + Browser + Reader + Apps + Terminal).
 */
public class Arc7PowerWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context, appWidgetManager, appWidgetIds);
    }

    public static void refreshAll(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, Arc7PowerWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                updateAll(context, mgr, ids);
            }
        } catch (Throwable ignored) {}
    }

    private static PendingIntent actPi(Context ctx, int code, Class<?> cls) {
        Intent i = new Intent(ctx, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        boolean running = Arc7TlsGatewayService.isGatewayAlive();
        Intent startSvc = new Intent(context, Arc7TlsGatewayService.class);
        PendingIntent piTls = PendingIntent.getService(
                context, 7301, startSvc, PendingIntent.FLAG_UPDATE_CURRENT);

        for (int i = 0; i < ids.length; i++) {
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_arc7_power);
            rv.setTextViewText(R.id.widget_power_tls, running ? "\u2713 TLS 1.3 ON" : "Start TLS 1.3");
            rv.setOnClickPendingIntent(R.id.widget_power_tls, piTls);
            rv.setOnClickPendingIntent(R.id.widget_power_browser, actPi(context, 7302, Arc7BrowserActivity.class));
            rv.setOnClickPendingIntent(R.id.widget_power_reader, actPi(context, 7303, Arc7EbookReaderActivity.class));
            rv.setOnClickPendingIntent(R.id.widget_power_apps, actPi(context, 7304, Arc7AppManagerActivity.class));
            rv.setOnClickPendingIntent(R.id.widget_power_term, actPi(context, 7305, Arc7TerminalActivity.class));
            mgr.updateAppWidget(ids[i], rv);
        }
    }
}
