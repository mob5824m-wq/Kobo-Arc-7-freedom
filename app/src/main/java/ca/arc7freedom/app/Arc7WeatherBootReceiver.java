package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restore only Weather's own opted-in/widget schedule after reboot, update, clock or network changes. */
public final class Arc7WeatherBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Arc7WeatherService.owner(context) || intent == null) return;
        String a = intent.getAction();
        if (!(Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || "android.net.conn.CONNECTIVITY_CHANGE".equals(a))) return;
        Arc7WeatherService.schedule(context);
        if (Arc7WeatherStore.refreshMinutes(context) > 0) Arc7WeatherService.request(context,false);
    }
}
