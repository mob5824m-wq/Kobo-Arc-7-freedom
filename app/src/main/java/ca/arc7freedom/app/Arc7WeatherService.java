package ca.arc7freedom.app;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Widget/optional official-alert updates. No tracking, root, suite networking, wake-up alarm or sticky service. */
public final class Arc7WeatherService extends IntentService {
    public static final String UPDATED = "ca.arc7freedom.weather.UPDATED";
    private static final String PERIODIC = "ca.arc7freedom.weather.PERIODIC";
    private static final String MANUAL = "ca.arc7freedom.weather.REFRESH";
    private static final int NOTIFICATION = 7710;
    private volatile Arc7WeatherTransport transport;

    public Arc7WeatherService() { super("WeatherUpdate"); }
    static boolean owner(Context c) { return "ca.arc7freedom.weather".equals(c.getPackageName()); }
    public static void request(Context c, boolean manual) {
        if (owner(c)) c.startService(new Intent(c, Arc7WeatherService.class).setAction(manual ? MANUAL : PERIODIC));
    }
    public static PendingIntent refreshIntent(Context c) {
        return PendingIntent.getService(c, 7711, new Intent(c, Arc7WeatherService.class).setAction(MANUAL), PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static PendingIntent alarmIntent(Context c) {
        return PendingIntent.getService(c, 7712, new Intent(c, Arc7WeatherService.class).setAction(PERIODIC), PendingIntent.FLAG_UPDATE_CURRENT);
    }
    public static boolean hasWidgets(Context c) {
        int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, Arc7WeatherWidgetProvider.class));
        return (ids != null && ids.length > 0) || Arc7WeatherWidgetBridge.hasLegacyWidgets(c);
    }
    public static void schedule(Context c) {
        if (!owner(c)) return;
        AlarmManager a = (AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if (a == null) return;
        PendingIntent pending = alarmIntent(c); a.cancel(pending);
        int minutes = Arc7WeatherStore.refreshMinutes(c);
        if (minutes > 0 && Arc7WeatherStore.selected(c) != null && (hasWidgets(c) || Arc7WeatherStore.notifications(c))) {
            long interval = minutes * 60000L;
            // Does not wake a sleeping tablet or switch Wi-Fi on. Android can batch/delay updates.
            a.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime()+interval, interval, pending);
        }
        if (!Arc7WeatherStore.notifications(c)) cancelNotification(c);
    }
    public static void changed(Context c, String kind) {
        Arc7WeatherWidgetProvider.refreshAll(c);
        if(owner(c)) Arc7WeatherWidgetBridge.notifyLegacy(c);
        c.sendBroadcast(new Intent(UPDATED).setPackage(c.getPackageName()).putExtra("kind", kind));
    }
    public static boolean connected(Context c, boolean background) {
        ConnectivityManager cm = (ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected()
                && (!background || !Arc7WeatherStore.wifiOnly(c) || n.getType() == ConnectivityManager.TYPE_WIFI
                    || n.getType() == ConnectivityManager.TYPE_ETHERNET);
    }
    @Override protected void onHandleIntent(Intent intent) {
        if (!owner(this) || intent == null) return;
        final boolean manual = MANUAL.equals(intent.getAction());
        if (!manual && !PERIODIC.equals(intent.getAction())) return;
        final Arc7WeatherData.Place p = Arc7WeatherStore.selected(this);
        if (p == null) { changed(this, "empty"); return; }
        long fetched = Arc7WeatherStore.fetchedAt(this,p,Arc7WeatherStore.FORECAST), now = System.currentTimeMillis();
        if (manual && fetched<=now && fetched>now-30000L) { changed(this,"cached"); return; }
        if (!manual && !hasWidgets(this) && !Arc7WeatherStore.notifications(this)) return;
        if (!manual && !Arc7WeatherStore.needsRefresh(this,p,System.currentTimeMillis())) { changed(this,"cached"); return; }
        if (!connected(this,!manual)) { changed(this,"offline"); return; }
        PowerManager.WakeLock wake = null;
        try {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Arc7Weather:refresh");
                wake.acquire(75000L); // bounded, released below even on errors; only while doing work
            }
            transport = new Arc7WeatherTransport(this);
            Arc7WeatherStore.refresh(this,p,transport,new Arc7WeatherStore.Listener() {
                @Override public void stage(String kind, String error) {
                    changed(Arc7WeatherService.this,kind);
                    if (Arc7WeatherStore.ALERTS.equals(kind) && error.length()==0) notifyAlerts(Arc7WeatherService.this,p);
                }
            });
        } catch (Exception unavailable) {
            // Forecast/air/alert failures themselves are persisted separately by the store.
            changed(this,"unavailable");
        } finally {
            Arc7WeatherTransport t = transport; transport = null; if (t != null) t.cancel();
            if (wake != null && wake.isHeld()) wake.release();
        }
    }
    @Override public void onDestroy() {
        Arc7WeatherTransport t = transport; if (t != null) t.cancel();
        super.onDestroy();
    }
    public static synchronized void cancelNotification(Context c) {
        NotificationManager n = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (n != null) n.cancel(NOTIFICATION);
    }
    @SuppressWarnings("deprecation")
    public static synchronized void notifyAlerts(Context c, Arc7WeatherData.Place p) {
        if (p==null || !owner(c) || !Arc7WeatherStore.notifications(c)) return;
        Arc7WeatherData.Place selected = Arc7WeatherStore.selected(c);
        if (selected == null || !selected.id.equals(p.id)) return;
        Arc7WeatherData.Alerts data = Arc7WeatherStore.alerts(c,p);
        long now = System.currentTimeMillis();
        if (data == null || Arc7WeatherStore.error(c,p,Arc7WeatherStore.ALERTS).length()>0 || data.fetchedAt > now + 300000L || now-data.fetchedAt > 30 * 60000L) return;
        List<Arc7WeatherData.Alert> active = data.active(now);
        if (active.isEmpty()) { if (data.complete) cancelNotification(c); return; }
        Set<String> previous = new HashSet<String>();
        for (String key : Arc7WeatherStore.notified(c,p).split("\\n")) previous.add(key);
        boolean unseen = false; StringBuilder next = new StringBuilder();
        for (Arc7WeatherData.Alert a : active) {
            // New publications of an existing warning are meaningful updates. Hash to bound private preferences.
            String fingerprint = fingerprint(a.id + "|" + a.sent);
            if (!previous.contains(fingerprint)) unseen = true;
            next.append(fingerprint).append('\n');
        }
        if (!unseen) return;
        NotificationManager manager = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Intent open = new Intent(c,Arc7WeatherActivity.class).putExtra("weather_tab",4);
        PendingIntent click = PendingIntent.getActivity(c,7710,open,PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c).setSmallIcon(R.drawable.ic_weather)
                .setContentTitle(p.name + ": " + active.get(0).title)
                .setContentText(active.size()>1 ? active.size()+" official alerts. Tap for instructions." : "Official weather alert. Tap for instructions.")
                .setContentIntent(click).setAutoCancel(true).setWhen(now);
        if (Arc7WeatherStore.alertSound(c)) b.setDefaults(Notification.DEFAULT_SOUND);
        manager.notify(NOTIFICATION,b.getNotification());
        Arc7WeatherStore.notified(c,p,next.toString());
    }
    private static String fingerprint(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(); for (byte v : d) b.append(String.format(java.util.Locale.US,"%02x",v & 255));
            return b.toString();
        } catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
}
