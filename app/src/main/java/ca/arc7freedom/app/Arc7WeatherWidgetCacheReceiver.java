package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Cache-change notifications for pre-split suite widgets; signature-protected in the manifest. */
public final class Arc7WeatherWidgetCacheReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (Arc7WeatherService.owner(c) || intent == null
                || !Arc7WeatherWidgetBridge.CHANGED.equals(intent.getAction())) return;
        Arc7WeatherWidgetProvider.refreshAll(c);
        // Re-register after a Weather reinstall/update without starting suite-owned network work.
        Arc7WeatherWidgetBridge.registerLegacy(c, Arc7WeatherWidgetBridge.localWidgets(c));
    }
}
