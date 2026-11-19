package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exported only behind our signature permission; never accepts an arbitrary URL/location. */
public final class Arc7WeatherWidgetRequestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        if(!Arc7WeatherService.owner(c) || intent==null || !Arc7WeatherWidgetBridge.REQUEST.equals(intent.getAction())) return;
        c.getSharedPreferences("weather_widget_bridge",0).edit().putBoolean("legacy_active",intent.getBooleanExtra("legacy_active",false)).commit();
        Arc7WeatherService.schedule(c);
        if(intent.getBooleanExtra("refresh",false)) Arc7WeatherService.request(c,true);
    }
}
