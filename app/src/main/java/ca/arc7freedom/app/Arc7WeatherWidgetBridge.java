package ca.arc7freedom.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/** Compatibility for widgets pinned before Weather was split into its own APK.
 * Only a signed Arc7 suite may read the selected city's already-cached display strings.
 * No location coordinates, network credentials, write interface or suite network dependency.
 */
public final class Arc7WeatherWidgetBridge extends ContentProvider {
    static final String PERMISSION="ca.arc7freedom.permission.WEATHER_WIDGET";
    static final String CHANGED="ca.arc7freedom.weather.WIDGET_CACHE_CHANGED";
    static final String REQUEST="ca.arc7freedom.weather.WIDGET_REQUEST";
    static final Uri URI=Uri.parse("content://ca.arc7freedom.weather.widget/snapshot");
    static final String[] COLUMNS={"city","temperature","feels","summary","range","status"};
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.arc7.weather-widget"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if(!URI.equals(uri) || !Arc7WeatherService.owner(getContext())) throw new IllegalArgumentException("Unknown widget snapshot");
        int uid=Binder.getCallingUid();
        if(getContext().getPackageManager().checkSignatures(uid,Process.myUid())!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Weather widget signature mismatch");
        MatrixCursor cursor=new MatrixCursor(COLUMNS); cursor.addRow(snapshot(getContext())); return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("Read-only weather snapshot"); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException("Read-only weather snapshot"); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException("Read-only weather snapshot"); }

    static String[] snapshot(Context c) {
        Arc7WeatherData.Place place=Arc7WeatherStore.selected(c);
        Arc7WeatherData.Forecast forecast=place==null ? null : Arc7WeatherStore.forecast(c,place);
        Arc7WeatherData.Units units=Arc7WeatherStore.units(c); long now=System.currentTimeMillis();
        String summary=place==null ? "Tap to choose a city in Weather. Location access is optional."
                : forecast==null ? "No saved forecast. Tap Refresh or open Weather." : Arc7WeatherData.description(forecast.current.code);
        String range="High —   Low —";
        if(forecast!=null) {
            Arc7WeatherData.Day today=forecast.today(now);
            if(today!=null) range="High "+units.temp(today.high)+"   Low "+units.temp(today.low)+"   ·   Rain "+Arc7WeatherData.pct(today.probability);
        }
        String status=forecast==null ? "Not updated" : (forecast.stale(now) ? "STALE · " : "Updated ")
                +Arc7WeatherData.formatTime(forecast.fetchedAt,forecast.zone,units.clock12 ? "MMM d h:mm a" : "MMM d HH:mm");
        if(place!=null && Arc7WeatherStore.error(c,place,Arc7WeatherStore.FORECAST).length()>0) status+=" · Refresh failed";
        return new String[]{place==null ? "WEATHER" : place.name,forecast==null ? Arc7WeatherData.MISSING : units.temp(forecast.current.temp),
                forecast==null ? "NO DATA" : "FEELS "+units.temp(forecast.current.feels),summary,range,status};
    }
    static String[] remoteSnapshot(Context c) {
        Cursor cursor=null;
        try {
            if(c.getPackageManager().checkSignatures(c.getPackageName(),"ca.arc7freedom.weather")!=PackageManager.SIGNATURE_MATCH) return null;
            cursor=c.getContentResolver().query(URI,COLUMNS,null,null,null);
            if(cursor!=null && cursor.moveToFirst()) {
                String[] result=new String[COLUMNS.length]; for(int i=0;i<result.length;i++) result[i]=cursor.getString(i); return result;
            }
        } catch(Exception unavailable) { /* Missing/outdated Weather: retain an honest setup message. */ }
        finally { if(cursor!=null) cursor.close(); }
        return null;
    }
    private static Intent requestIntent(boolean active,boolean refresh) {
        return new Intent(REQUEST).setComponent(new ComponentName("ca.arc7freedom.weather","ca.arc7freedom.app.Arc7WeatherWidgetRequestReceiver"))
                .putExtra("legacy_active",active).putExtra("refresh",refresh);
    }
    static void registerLegacy(Context c,boolean active) {
        try { c.sendBroadcast(requestIntent(active,false)); } catch(Exception ignored) {}
    }
    static PendingIntent refreshIntent(Context c) {
        return PendingIntent.getBroadcast(c,7713,requestIntent(true,true),PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static void notifyLegacy(Context c) {
        c.sendBroadcast(new Intent(CHANGED).setComponent(new ComponentName("ca.arc7freedom.app","ca.arc7freedom.app.Arc7WeatherWidgetCacheReceiver")),PERMISSION);
    }
    static boolean localWidgets(Context c) {
        int[] ids=android.appwidget.AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c,Arc7WeatherWidgetProvider.class));
        return ids!=null && ids.length>0;
    }
    static boolean hasLegacyWidgets(Context c) {
        if(!c.getSharedPreferences("weather_widget_bridge",0).getBoolean("legacy_active",false)) return false;
        // An uninstalled / differently signed suite must not keep a background alarm alive.
        return c.getPackageManager().checkSignatures(c.getPackageName(),"ca.arc7freedom.app")==PackageManager.SIGNATURE_MATCH;
    }
}
