package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Real selected-city data and dedicated High/Low; old suite widgets use a signed cache bridge. */
public class Arc7WeatherWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids) {
        if(ids!=null) for(int id:ids) updateOne(c,m,id);
        if(!Arc7WeatherService.owner(c)) Arc7WeatherWidgetBridge.registerLegacy(c,Arc7WeatherWidgetBridge.localWidgets(c));
        Arc7WeatherService.schedule(c);
        if(Arc7WeatherService.owner(c) && Arc7WeatherStore.refreshMinutes(c)>0) Arc7WeatherService.request(c,false);
    }
    @Override public void onEnabled(Context c) {
        if(!Arc7WeatherService.owner(c)) Arc7WeatherWidgetBridge.registerLegacy(c,true);
        Arc7WeatherService.schedule(c);
    }
    @Override public void onDisabled(Context c) {
        if(!Arc7WeatherService.owner(c)) Arc7WeatherWidgetBridge.registerLegacy(c,false);
        Arc7WeatherService.schedule(c);
    }
    /** Render only: never fetches or recursively requests another update. */
    public static void refreshAll(Context c) {
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        int[] ids=manager.getAppWidgetIds(new ComponentName(c,Arc7WeatherWidgetProvider.class));
        if(ids!=null) for(int id:ids) updateOne(c,manager,id);
    }
    private static void updateOne(Context c,AppWidgetManager manager,int id) {
        boolean owner=Arc7WeatherService.owner(c);
        String[] data=owner ? Arc7WeatherWidgetBridge.snapshot(c) : Arc7WeatherWidgetBridge.remoteSnapshot(c);
        boolean available=data!=null;
        if(data==null) data=new String[]{"WEATHER",Arc7WeatherData.MISSING,"SETUP","Open or update the separate Weather app, choose a city, then Refresh.","High —   Low —","Waiting for Weather"};
        RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.widget_arc7_weather);
        int[] fields={R.id.weather_widget_city,R.id.weather_widget_temp,R.id.weather_widget_unit,R.id.weather_widget_summary,R.id.weather_widget_range,R.id.weather_widget_status};
        for(int i=0;i<fields.length;i++) views.setTextViewText(fields[i],data[i]);
        Intent open=new Intent(c,Arc7WeatherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent click=PendingIntent.getActivity(c,7709,SplitAppRouter.route(c,open,-1),PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.weather_widget_root,click);
        views.setOnClickPendingIntent(R.id.weather_widget_refresh,owner ? Arc7WeatherService.refreshIntent(c)
                : available ? Arc7WeatherWidgetBridge.refreshIntent(c) : click);
        manager.updateAppWidget(id,views);
    }
}
