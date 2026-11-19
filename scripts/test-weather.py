#!/usr/bin/env python3
"""Offline regressions executing the production Weather models, store, transport, service,
receiver, widget and radar math on a host JVM. Android services are small fakes; provider
responses are synthetic schema fixtures, NOT live access or physical Kobo validation.
Requires a JDK (or scripts/bootstrap-toolchain.sh); no third-party Python dependencies.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/ca/arc7freedom/app'
NS = '{http://schemas.android.com/apk/res/android}'

STUBS = {
'android/content/SharedPreferences.java': '''package android.content; import java.util.*;
public class SharedPreferences {
 public Map<String,Object> data=new HashMap<String,Object>(); public boolean fail;
 public boolean contains(String k){return data.containsKey(k);} public String getString(String k,String d){Object v=data.get(k);return v instanceof String?(String)v:d;}
 public long getLong(String k,long d){Object v=data.get(k);return v instanceof Number?((Number)v).longValue():d;}
 public int getInt(String k,int d){return (int)getLong(k,d);} public boolean getBoolean(String k,boolean d){Object v=data.get(k);return v instanceof Boolean?(Boolean)v:d;}
 public Editor edit(){return new Editor();} public class Editor {
 Map<String,Object> next=new HashMap<String,Object>(); Set<String> removed=new HashSet<String>();
 public Editor putString(String k,String v){next.put(k,v);return this;} public Editor putLong(String k,long v){next.put(k,v);return this;}
 public Editor putInt(String k,int v){return putLong(k,v);} public Editor putBoolean(String k,boolean v){next.put(k,v);return this;}
 public Editor remove(String k){removed.add(k);return this;} public boolean commit(){if(fail)return false;for(String k:removed)data.remove(k);data.putAll(next);return true;}
 }}''',
'android/content/Context.java': '''package android.content; import java.util.*; import java.io.*;
public class Context {
 public static final int MODE_PRIVATE=0; public static final String ALARM_SERVICE="alarm",NOTIFICATION_SERVICE="notification",CONNECTIVITY_SERVICE="connectivity",POWER_SERVICE="power";
 public android.content.pm.PackageManager pm=new android.content.pm.PackageManager(); public ContentResolver resolver=new ContentResolver(); public String permission;
 public String owner="ca.arc7freedom.weather"; public Map<String,SharedPreferences> preferences=new HashMap<String,SharedPreferences>();
 public Map<String,Object> services=new HashMap<String,Object>(); public List<Intent> started=new ArrayList<Intent>(),broadcasts=new ArrayList<Intent>();
 public File cache; public void copy(Context c){owner=c.owner;preferences=c.preferences;services=c.services;started=c.started;broadcasts=c.broadcasts;cache=c.cache;pm=c.pm;resolver=c.resolver;}
 public Context getApplicationContext(){return this;} public String getPackageName(){return owner;} public File getCacheDir(){return cache;}
 public SharedPreferences getSharedPreferences(String n,int m){if(!preferences.containsKey(n))preferences.put(n,new SharedPreferences());return preferences.get(n);}
 public Object getSystemService(String n){return services.get(n);} public ComponentName startService(Intent i){started.add(i);return null;}
 public android.content.pm.PackageManager getPackageManager(){return pm;} public ContentResolver getContentResolver(){return resolver;}
 public void sendBroadcast(Intent i){broadcasts.add(i);} public void sendBroadcast(Intent i,String p){permission=p;broadcasts.add(i);}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 public static final String ACTION_BOOT_COMPLETED="boot",ACTION_MY_PACKAGE_REPLACED="replaced",ACTION_TIME_CHANGED="time",ACTION_TIMEZONE_CHANGED="zone";
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000; public String action,pkg; public Class<?> target; public int flags; public ComponentName component; public java.util.Map<String,Object> extras=new java.util.HashMap<String,Object>();
 public Intent(String a){action=a;} public Intent(Context c,Class<?> t){target=t;pkg=c.getPackageName();}
 public Intent setAction(String a){action=a;return this;} public String getAction(){return action;}
 public Intent setPackage(String p){pkg=p;return this;} public Intent addFlags(int f){flags|=f;return this;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;} public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;} public boolean getBooleanExtra(String k,boolean d){Object v=extras.get(k);return v instanceof Boolean?(Boolean)v:d;} public Intent setComponent(ComponentName c){component=c;return this;}
}''',
'android/content/ComponentName.java': 'package android.content; public class ComponentName { public String pkg,cls; public ComponentName(Context c,Class<?> t){this(c.getPackageName(),t.getName());} public ComponentName(String p,String n){pkg=p;cls=n;} }',
'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }',
'android/app/IntentService.java': 'package android.app; public abstract class IntentService extends android.content.Context { public IntentService(String n){} protected abstract void onHandleIntent(android.content.Intent i); public void onDestroy(){} }',
'android/app/PendingIntent.java': '''package android.app; import android.content.*; public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1; public String type; public Intent intent;
 private PendingIntent(String t,Intent i){type=t;intent=i;} public static PendingIntent getActivity(Context c,int n,Intent i,int f){return new PendingIntent("activity",i);}
 public static PendingIntent getService(Context c,int n,Intent i,int f){return new PendingIntent("service",i);}
 public static PendingIntent getBroadcast(Context c,int n,Intent i,int f){return new PendingIntent("broadcast",i);}
}''',
'android/app/AlarmManager.java': '''package android.app; public class AlarmManager { public static final int ELAPSED_REALTIME=3; public int scheduled,cancelled,type; public long interval;
 public void cancel(PendingIntent p){cancelled++;} public void setInexactRepeating(int t,long start,long i,PendingIntent p){scheduled++;type=t;interval=i;}
}''',
'android/app/Notification.java': '''package android.app; public class Notification { public static final int DEFAULT_SOUND=1;
 public static class Builder { public Builder(android.content.Context c){} public Builder setSmallIcon(int i){return this;} public Builder setContentTitle(String s){return this;}
 public Builder setContentText(String s){return this;} public Builder setContentIntent(PendingIntent i){return this;} public Builder setAutoCancel(boolean b){return this;}
 public Builder setWhen(long t){return this;} public Builder setDefaults(int d){return this;} public Notification getNotification(){return new Notification();} }
}''',
'android/app/NotificationManager.java': 'package android.app; public class NotificationManager { public int count,cancelled; public void notify(int i,Notification n){count++;} public void cancel(int i){cancelled++;} }',
'android/net/ConnectivityManager.java': '''package android.net; public class ConnectivityManager { public static final int TYPE_WIFI=1,TYPE_ETHERNET=9; public NetworkInfo active=new NetworkInfo(); public NetworkInfo getActiveNetworkInfo(){return active;} }''',
'android/net/NetworkInfo.java': 'package android.net; public class NetworkInfo { public boolean connected=true; public int type=1; public boolean isConnected(){return connected;} public int getType(){return type;} }',
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }',
'android/os/PowerManager.java': '''package android.os; public class PowerManager { public static final int PARTIAL_WAKE_LOCK=1; public WakeLock last;
 public WakeLock newWakeLock(int n,String s){return last=new WakeLock();} public static class WakeLock { public int acquired,released; public boolean held;
 public void acquire(long t){acquired++;held=true;} public boolean isHeld(){return held;} public void release(){released++;held=false;} }
}''',
'android/appwidget/AppWidgetProvider.java': '''package android.appwidget; public class AppWidgetProvider extends android.content.BroadcastReceiver {
 public void onReceive(android.content.Context c,android.content.Intent i){} public void onUpdate(android.content.Context c,AppWidgetManager m,int[] ids){}
 public void onEnabled(android.content.Context c){} public void onDisabled(android.content.Context c){} }''',
'android/appwidget/AppWidgetManager.java': '''package android.appwidget; public class AppWidgetManager {
 public static AppWidgetManager instance=new AppWidgetManager(); public int[] ids=new int[0]; public android.widget.RemoteViews view; public int updates;
 public static AppWidgetManager getInstance(android.content.Context c){return instance;} public int[] getAppWidgetIds(android.content.ComponentName n){return ids;}
 public void updateAppWidget(int id,android.widget.RemoteViews v){view=v;updates++;} }''',
'android/widget/RemoteViews.java': '''package android.widget; import java.util.*; public class RemoteViews {
 public Map<Integer,String> texts=new HashMap<Integer,String>(); public Map<Integer,android.app.PendingIntent> clicks=new HashMap<Integer,android.app.PendingIntent>();
 public RemoteViews(String p,int id){} public void setTextViewText(int id,String s){texts.put(id,s);} public void setOnClickPendingIntent(int id,android.app.PendingIntent p){clicks.put(id,p);}
}''',
'android/content/ContentProvider.java': 'package android.content; import android.database.Cursor; import android.net.Uri;\npublic abstract class ContentProvider { public Context context; public Context getContext(){return context;} public abstract boolean onCreate();\npublic abstract Cursor query(Uri u,String[] p,String s,String[] a,String sort);public abstract String getType(Uri u);\npublic abstract Uri insert(Uri u,ContentValues v);public abstract int update(Uri u,ContentValues v,String s,String[] a);public abstract int delete(Uri u,String s,String[] a); }',
'android/content/ContentValues.java': 'package android.content; public class ContentValues {}',
'android/content/ContentResolver.java': 'package android.content; import android.database.Cursor; import android.net.Uri;\npublic class ContentResolver { public ContentProvider provider; public int queries; public Cursor last;\n public Cursor query(Uri u,String[] p,String s,String[] a,String o){queries++;if(provider==null)throw new IllegalStateException("No provider");return last=provider.query(u,p,s,a,o);} }',
'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int SIGNATURE_MATCH=0; public boolean signature=true; public int checkSignatures(int a,int b){return signature?0:-1;} public int checkSignatures(String a,String b){return signature?0:-1;} }',
'android/database/Cursor.java': 'package android.database; public interface Cursor { boolean moveToFirst(); String getString(int i); void close(); }',
'android/database/MatrixCursor.java': 'package android.database; public class MatrixCursor implements Cursor { public Object[] row;public boolean closed;\n public MatrixCursor(String[] c){}public void addRow(Object[] r){row=r;} public boolean moveToFirst(){return row!=null;}public String getString(int i){return (String)row[i];}public void close(){closed=true;} }',
'android/net/Uri.java': 'package android.net; public class Uri { final String s;private Uri(String v){s=v;}public static Uri parse(String v){return new Uri(v);}public boolean equals(Object v){return v instanceof Uri&&s.equals(((Uri)v).s);}public int hashCode(){return s.hashCode();} }',
'android/os/Binder.java': 'package android.os; public class Binder { public static int getCallingUid(){return 1234;} }',
'android/os/Process.java': 'package android.os; public class Process { public static int myUid(){return 5678;} }',
'ca/arc7freedom/app/RootShell.java': 'package ca.arc7freedom.app; public class RootShell { public RootShell(android.content.Context c){} }',
'ca/arc7freedom/app/NetworkCertManager.java': 'package ca.arc7freedom.app; public class NetworkCertManager { public NetworkCertManager(android.content.Context c,RootShell r){} public boolean startBrowserGateway(){return true;} }',
'ca/arc7freedom/app/Arc7WeatherActivity.java': 'package ca.arc7freedom.app; public class Arc7WeatherActivity {}',
'ca/arc7freedom/app/SplitAppRouter.java': 'package ca.arc7freedom.app; public class SplitAppRouter { public static android.content.Intent route(android.content.Context c,android.content.Intent i,int n){return i;} }',
'ca/arc7freedom/app/R.java': '''package ca.arc7freedom.app; public final class R {
 public static final class drawable { public static final int ic_weather=1; } public static final class layout { public static final int widget_arc7_weather=2; }
 public static final class id { public static final int weather_widget_city=3,weather_widget_temp=4,weather_widget_unit=5,weather_widget_summary=6,weather_widget_status=7,weather_widget_root=8,weather_widget_refresh=9,weather_widget_range=10; } }''',
}

HARNESS = r'''package ca.arc7freedom.app;
import java.io.*; import java.net.*; import java.nio.file.*; import java.util.*; import java.util.concurrent.*;
import org.json.*; import android.content.*; import android.app.*; import android.appwidget.*; import android.net.*; import android.os.*;
import com.sun.net.httpserver.*;
public class WeatherHarness {
 static String forecast,air,ca,us; static File fixtures; static long now=1793514600000L; // 2026-11-01 06:30 UTC, DST fallback
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static void equal(double a,double b,String m){check(Math.abs(a-b)<0.015,m+": "+a+" != "+b);}
 static String read(String n)throws Exception{return new String(Files.readAllBytes(new File(fixtures,n).toPath()),"UTF-8");}
 static Arc7WeatherData.Place p=new Arc7WeatherData.Place("Mississauga, Ontario (CA)",43.589,-79.644,"CA");
 static Arc7WeatherData.Place usa=new Arc7WeatherData.Place("New York (US)",40.71,-74,"US");
 static Context context(){Context c=new Context(); c.cache=fixtures; c.services.put(Context.ALARM_SERVICE,new AlarmManager());c.services.put(Context.NOTIFICATION_SERVICE,new NotificationManager());c.services.put(Context.CONNECTIVITY_SERVICE,new ConnectivityManager());c.services.put(Context.POWER_SERVICE,new PowerManager());return c;}
 static class Net implements Arc7WeatherStore.Transport {
   boolean cancelled; String weather=forecast,aq=air,alert=ca; int calls; Runnable hook; String fail="";
   public String get(String url)throws Exception{calls++; if(hook!=null){Runnable r=hook;hook=null;r.run();} if(url.contains(fail)&&fail.length()>0)throw new IOException("fixture failure"); return url.contains("air-quality")?aq:url.contains("weather-alerts")||url.contains("weather.gov")?alert:weather;}
   public boolean cancelled(){return cancelled;}public void cancel(){cancelled=true;}
 }
 static void models()throws Exception {
   Arc7WeatherData.Forecast f=Arc7WeatherData.parseForecast(p,forecast,now);
   check(f.hasData && f.days.size()==14 && f.hours.size()==336,"full 14-day schema");
   List<Arc7WeatherData.Hour> next=f.upcoming(now,48);check(next.size()==48,"48 upcoming hours");
   check(next.get(0).time==1793512800000L,"exclude first midnight and elapsed DST hour");
   check(Arc7WeatherData.formatTime(next.get(0).time,f.zone,"HH:mm z").contains("01:00 EST"),"use selected city's DST time zone");
   check(f.upcoming(now+10*Arc7WeatherData.HOUR,48).get(0).time==next.get(10).time,"re-filter cache on every render");
   check(f.today(now).date.equals("2026-11-01"),"daily local date from UTC unix instant");
   equal(f.current.temp,7.5,"current measurement");check(!f.stale(now),"freshness");check(f.stale(now+2*Arc7WeatherData.HOUR),"age stale");check(f.stale(now-600000),"future fetched timestamp stale");
   equal(f.days.get(0).rain,2.4,"daily rain"); check(f.days.get(0).sunrise>0,"sunrise epoch");
   JSONObject root=new JSONObject(forecast); root.put("current",new JSONObject().put("time",now/1000).put("temperature_2m",(Object)null));
   root.put("hourly",new JSONObject().put("time",new JSONArray().put(now/1000)).put("temperature_2m",new JSONArray().put((Object)null)));
   Arc7WeatherData.Forecast missing=Arc7WeatherData.parseForecast(p,root.toString(),now);
   check(Double.isNaN(missing.current.temp) && missing.current.code==-1,"null is not 0 degrees or clear sky");
   check(Double.isNaN(missing.hours.get(0).temp) && Double.isNaN(missing.hours.get(0).probability),"null / short arrays remain unavailable");
   for(String bad:new String[]{"{}","<html>blocked</html>","{\"error\":true,\"reason\":\"rate limit\"}","{\"current\":{\"temperature_2m\":14}}"}){
      try{Arc7WeatherData.parseForecast(p,bad,now);throw new AssertionError("bad forecast accepted");}catch(IllegalArgumentException expected){}
   }
   check(Arc7WeatherStore.formatTemp(Double.NaN,false).equals("\u2014"),"missing temperature never zero");
   check(Arc7WeatherStore.wmoDescription(-1).contains("unavailable") && Arc7WeatherStore.wmoDescription(8).contains("unavailable"),"unknown WMO never fair");
   check(Arc7WeatherStore.wmoDescription(66).contains("Freezing"),"freezing rain");
   check(Arc7WeatherStore.formatTemp(20,true).equals("68\u00b0F"),"C/F conversion");
   Arc7WeatherData.Units u=new Arc7WeatherData.Units();u.wind=1;check(u.speed(100).startsWith("62"),"mph");u.wind=2;check(u.speed(36).startsWith("10.0"),"m/s");u.wind=3;check(u.speed(18.52).startsWith("10"),"knots");
   u.inches=true;check(u.rain(25.4).equals("1.00 in")&&u.snow(2.54).equals("1.0 in"),"mm and cm conversions distinct");
   u.inHg=true;check(u.pressure(1013.25).startsWith("29.92"),"inHg");u.miles=true;check(u.visibility(1609.344).equals("1.0 mi"),"visibility miles");
   check(Arc7WeatherData.direction(360).equals("N")&&Arc7WeatherData.direction(Double.NaN).equals("\u2014"),"compass");
   check(Arc7WeatherData.zone("",3600).getRawOffset()==3600000,"legacy offset-only response");
   check(Arc7WeatherData.time("2026-11-01T01:30:00-05:00",TimeZone.getTimeZone("UTC"))==now,"ISO offset parse");
   check(Arc7WeatherData.time("2026-11-01T06:30:00.000Z",TimeZone.getTimeZone("UTC"))==now,"UTC fractional CAP parse");
   check(Arc7WeatherData.time("2026-02-30T10:00",TimeZone.getTimeZone("UTC"))==0,"invalid calendar date");
   check(Arc7WeatherData.time("2026-11-01T06:30:00.1Z",TimeZone.getTimeZone("UTC"))==now+100,"ISO fractional tenths are not milliseconds");
   check(Arc7WeatherData.time("2026-11-01T06:30:00.123456Z",TimeZone.getTimeZone("UTC"))==now+123,"CAP fractional microseconds");
   check(Arc7WeatherData.validFix(43.5,-79.6,now-1000,100,now),"fresh accurate device fix");
   check(!Arc7WeatherData.validFix(43.5,-79.6,now-900000,100,now),"stale device fix rejected");
   check(!Arc7WeatherData.validFix(43.5,-79.6,now,50000,now),"very imprecise device fix rejected");
   check(!Arc7WeatherData.validFix(200,-79.6,now,100,now),"invalid device coordinates rejected");
   List<Arc7WeatherData.Place> hits=Arc7WeatherData.parseSearch(read("search.json"));check(hits.size()==2 && hits.get(0).country.equals("CA") && hits.get(1).country.equals("GB"),"disambiguated, de-duplicated geocoding; invalid coordinates dropped");
   check(Arc7WeatherStore.searchUrl("New York").contains("count=10")&&Arc7WeatherStore.searchUrl("New York").contains("New+York"),"multi-result encoded search");
   try{new Arc7WeatherData.Place("x",Double.NaN,0,"");throw new AssertionError();}catch(IllegalArgumentException expected){}
   try{new Arc7WeatherData.Place("x",91,0,"");throw new AssertionError();}catch(IllegalArgumentException expected){}
   Arc7WeatherData.Air a=Arc7WeatherData.parseAir(air,now);check(a.hours.size()==72 && a.zone.getID().equals("America/Toronto"),"AQ forecast schema and timezone independent of weather success");
   equal(a.at(now).us,42,"current AQ hour");check(Double.isNaN(a.at(now).pollen[0]),"unsupported pollen not zero");
   check(Double.isNaN(a.at(now+7*24*Arc7WeatherData.HOUR).us),"old AQ hour never presented as current");
   check(Arc7WeatherData.aqiLabel(151,false).equals("Unhealthy")&&Arc7WeatherData.aqiLabel(81,true).equals("Very poor"),"AQ scales");
   check(Arc7WeatherData.uvLabel(11).equals("Extreme")&&Arc7WeatherData.moon(now).contains("approx."),"UV and labelled approximate moon");
   System.out.println("PASS weather models: real/missing measurements, 48h/14d, cache ageing, DST/local dates, units, search, AQ/pollen, UV/sun/moon");
 }
 static void alerts()throws Exception {
   Arc7WeatherData.Alerts c=Arc7WeatherData.parseAlerts(ca,p,now);check(c.complete&&c.items.size()==1&&c.active(now).size()==1,"ECCC actual schema; exact polygon filtering; future event warning already published");
   check(c.items.get(0).title.equals("Wind warning"),"ECCC names");
   Arc7WeatherData.Alerts n=Arc7WeatherData.parseAlerts(us,usa,now);check(n.items.size()==2 && n.active(now).size()==1,"NWS Actual only; expires filtered, future onset watch visible");
   JSONObject paged=new JSONObject(us).put("pagination",new JSONObject().put("next","https://api.weather.gov/next"));
   check(!Arc7WeatherData.parseAlerts(paged.toString(),usa,now).complete,"paginated alerts never complete");
   JSONObject geo=new JSONObject(read("polygon-hole.json"));check(!Arc7WeatherData.contains(geo,-79.65,43.58),"polygon hole exclusion");check(Arc7WeatherData.contains(geo,-79.9,43.2),"outer polygon");
   JSONObject noGeo=new JSONObject(ca);noGeo.put("features",new JSONArray().put(new JSONObject().put("properties",new JSONObject().put("alert_name_en","Wind warning"))));
   check(!Arc7WeatherData.parseAlerts(noGeo.toString(),p,now).complete,"missing Canadian geometry not safe no-alert status");
   noGeo.optJSONArray("features").getJSONObject(0).put("geometry",new JSONObject().put("type","Polygon").put("coordinates",new JSONArray()));
   check(!Arc7WeatherData.parseAlerts(noGeo.toString(),p,now).complete,"malformed polygon not safe no-alert status");
   check(Arc7WeatherStore.alertUrl(p).contains("/collections/weather-alerts/items") && Arc7WeatherStore.alertUrl(usa).contains("point=40.7100,-74.0000"),"official endpoint and lat/lon order");
   check(Arc7WeatherStore.alertUrl(new Arc7WeatherData.Place("Other",10,10,"GB")).isEmpty(),"unsupported never fetch fake alerts");
   System.out.println("PASS official alerts: ECCC weather-alerts fields/polygons/holes, NWS watch/expiry/test/cancel, incomplete pagination and unsupported regions");
 }
 static void stores()throws Exception {
   final Context c=context();check(Arc7WeatherStore.places(c).isEmpty()&&Arc7WeatherStore.selected(c)==null,"fresh install has no default fake city");
   check(Arc7WeatherStore.forecast(c,p)==null,"no fake cached forecast");
   final Context legacy=context();SharedPreferences old=legacy.getSharedPreferences(Arc7WeatherStore.PREFS,0);old.edit().putString("cached_json",forecast).putString("city_label","Legacy city").putString("lat","43.58").putString("lon","-79.64").commit();
   Arc7WeatherData.Place imported=Arc7WeatherStore.selected(legacy);check(imported!=null&&Arc7WeatherStore.forecast(legacy,imported).fetchedAt==0,"legacy cache import freshness unknown");
   Arc7WeatherStore.savePlace(c,p,true);Arc7WeatherStore.savePlace(c,usa,false);
   Net success=new Net();Arc7WeatherStore.refresh(c,p,success,null);check(success.calls==3&&Arc7WeatherStore.forecast(c,p)!=null&&Arc7WeatherStore.air(c,p)!=null&&Arc7WeatherStore.alerts(c,p)!=null,"all providers independently cached");
   String good=Arc7WeatherStore.raw(c,p,"forecast");Net failure=new Net();failure.weather="{\"error\":true,\"reason\":\"bad fixture\"}";
   List<String> stages=new ArrayList<String>();Arc7WeatherStore.refresh(c,p,failure,(kind,error)->stages.add(kind+":"+error));
   check(good.equals(Arc7WeatherStore.raw(c,p,"forecast"))&&!Arc7WeatherStore.error(c,p,"forecast").isEmpty(),"failed JSON never replaces last good forecast");check(stages.size()==3&&stages.get(1).equals("air:"),"forecast failure does not block air/alerts");
   Net wrongCity=new Net();wrongCity.hook=()->Arc7WeatherStore.select(c,usa);Arc7WeatherStore.refresh(c,p,wrongCity,null);
   check(Arc7WeatherStore.selected(c).id.equals(usa.id)&&Arc7WeatherStore.forecast(c,usa)==null,"worker never selects or contaminates another city");
   long at=System.currentTimeMillis()+10;Net t=new Net();Arc7WeatherStore.store(c,p,"forecast",good,at,Arc7WeatherStore.epoch(c),t);
   check(!Arc7WeatherStore.store(c,p,"forecast","{}",at-1,Arc7WeatherStore.epoch(c),t),"older completion cannot overwrite new cache");
   final Context cancelled=context();Arc7WeatherStore.savePlace(cancelled,p,true);Net cancel=new Net();cancel.hook=cancel::cancel;Arc7WeatherStore.refresh(cancelled,p,cancel,null);check(Arc7WeatherStore.forecast(cancelled,p)==null,"cancelled fetch has no cache side effects");
   final Context cleared=context();Arc7WeatherStore.savePlace(cleared,p,true);Net race=new Net();race.hook=()->Arc7WeatherStore.clearCaches(cleared);Arc7WeatherStore.refresh(cleared,p,race,null);check(race.calls==1&&Arc7WeatherStore.forecast(cleared,p)==null,"in-flight service cannot repopulate cleared caches");
   final Context removed=context();Arc7WeatherStore.savePlace(removed,p,true);Net delete=new Net();delete.hook=()->{Arc7WeatherStore.remove(removed,p);Arc7WeatherStore.savePlace(removed,p,true);};Arc7WeatherStore.refresh(removed,p,delete,null);check(Arc7WeatherStore.forecast(removed,p)==null,"remove/re-add cannot resurrect old request");
   Arc7WeatherStore.savePlace(c,new Arc7WeatherData.Place("Renamed",p.lat,p.lon,"US"),false);check(Arc7WeatherStore.alerts(c,new Arc7WeatherData.Place("Renamed",p.lat,p.lon,"US"))==null,"region changes invalidate old official alert response");check(Arc7WeatherStore.forecast(c,p)!=null,"rename/region edit preserves weather");
   Arc7WeatherData.Units u=new Arc7WeatherData.Units();u.fahrenheit=true;u.wind=3;u.clock12=true;Arc7WeatherStore.saveUnits(c,u);Arc7WeatherStore.saveOptions(c,180,true,true,false);
   Arc7WeatherStore.clearCaches(c);check(Arc7WeatherStore.places(c).size()==2&&Arc7WeatherStore.units(c).fahrenheit&&Arc7WeatherStore.units(c).wind==3&&Arc7WeatherStore.refreshMinutes(c)==180,"clear retains cities, units and options");
   for(int i=0;i<10;i++)Arc7WeatherStore.savePlace(c,new Arc7WeatherData.Place("Extra"+i,10+i,1,""),false);
   try{Arc7WeatherStore.savePlace(c,new Arc7WeatherData.Place("Too many",30,1,""),false);throw new AssertionError("unbounded places");}catch(IllegalArgumentException expected){}
   SharedPreferences sp=c.getSharedPreferences(Arc7WeatherStore.PREFS,0);sp.fail=true;try{Arc7WeatherStore.setFahrenheit(c,false);throw new AssertionError("disk failure hidden");}catch(IllegalStateException expected){}sp.fail=false;
   Arc7WeatherStore.remove(c,usa);check(!Arc7WeatherStore.selected(c).id.equals(usa.id),"remove selected falls back safely");
   System.out.println("PASS weather storage: migration, bounded private city caches, failures, independent provider stages, races/cancel/remove/clear epochs, selection, settings and disk-full honesty");
 }
 static volatile String routeMode="normal";static volatile int hits;static String lastAgent;static CountDownLatch slowStarted;
 static HttpServer server()throws Exception {
   HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",AppNetworkScope.gatewayPort()),0);
   s.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
   s.createContext("/",e->{try{hits++;lastAgent=e.getRequestHeaders().getFirst("User-Agent");String path=e.getRequestURI().toString();
      if(path.contains("slow")){slowStarted.countDown();try{Thread.sleep(12000);}catch(InterruptedException ignored){}}
      if(path.contains("redirect")){e.getResponseHeaders().add("Location","https://evil.invalid/weather");e.sendResponseHeaders(302,-1);return;}
      if(path.contains("limited")){e.sendResponseHeaders(429,-1);return;}
      String text=path.contains("air-quality")?air:path.contains("weather-alerts")?ca:forecast;
      if(path.contains("large"))text=new String(new char[3000]).replace('\0','x');
      byte[] body=text.getBytes("UTF-8");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
   }finally{e.close();}});s.start();return s;
 }
 static void network()throws Exception {
   AppNetworkScope.initialize("ca.arc7freedom.weather");HttpServer s=server();
   try{
     Context c=context();Arc7WeatherTransport t=new Arc7WeatherTransport(c);check(t.get("https://api.open-meteo.com/fixture").equals(forecast),"actual transport through own gateway");
     check(lastAgent.contains("Arc7Weather/")&&lastAgent.contains("github.com"),"provider identification / OSM user-agent");
     for(String bad:new String[]{"http://api.open-meteo.com/","https://api.open-meteo.com.evil.invalid/","https://x@api.open-meteo.com/","https://api.open-meteo.com:8443/"})check(!Arc7WeatherTransport.allowed(bad),"strict provider TLS allowlist");
     for(String path:new String[]{"redirect","limited","large"})try{t.bytes("https://api.open-meteo.com/"+path,1024);throw new AssertionError("redirect/status/size accepted");}catch(IOException expected){}
     final Arc7WeatherTransport slow=new Arc7WeatherTransport(c);slowStarted=new CountDownLatch(1);final Throwable[] error={null};
     Thread thread=new Thread(()->{try{slow.get("https://api.open-meteo.com/slow");}catch(Exception e){error[0]=e;}});thread.start();check(slowStarted.await(3,TimeUnit.SECONDS),"slow request started");
     long start=System.currentTimeMillis();slow.cancel();thread.join(2500);check(!thread.isAlive()&&error[0]!=null,"active network cancellation unblocks request");check(System.currentTimeMillis()-start<3000,"cancellation does not block UI");
     Context suite=context();suite.owner="ca.arc7freedom.app";try{new Arc7WeatherTransport(suite);throw new AssertionError("suite-owned weather traffic");}catch(IllegalArgumentException expected){}
     background(c);
   } finally {s.stop(0);}
   System.out.println("PASS owning-UID transport: real loopback HTTP, UA, HTTPS/provider allowlist, redirects/status/size limits and active cancellation");
 }
 static void background(Context c)throws Exception {
   Arc7WeatherStore.savePlace(c,p,true);AppWidgetManager manager=AppWidgetManager.instance;AlarmManager alarm=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
   Arc7WeatherService.schedule(c);check(alarm.scheduled==0,"no background work without widget/notification opt-in");
   manager.ids=new int[]{1};Arc7WeatherStore.saveOptions(c,180,true,false,false);Arc7WeatherService.schedule(c);
   check(alarm.interval==10800000&&alarm.type==AlarmManager.ELAPSED_REALTIME,"3h non-waking widget schedule");
   int scheduled=alarm.scheduled;Arc7WeatherStore.saveOptions(c,0,true,false,false);Arc7WeatherService.schedule(c);check(alarm.scheduled==scheduled,"manual stops automatic schedule");
   ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);cm.active.type=7;
   check(!Arc7WeatherService.connected(c,true)&&Arc7WeatherService.connected(c,false),"Wi-Fi-only background still allows manual PAN request");cm.active.type=ConnectivityManager.TYPE_ETHERNET;check(Arc7WeatherService.connected(c,true),"Ethernet allowed");
   cm.active.type=1;Arc7WeatherStore.saveOptions(c,30,false,false,false);Arc7WeatherService svc=new Arc7WeatherService();svc.copy(c);int before=hits;
   svc.onHandleIntent(new Intent(c,Arc7WeatherService.class).setAction("ca.arc7freedom.weather.REFRESH"));
   check(hits==before+3&&Arc7WeatherStore.forecast(c,p)!=null,"real widget service refreshes forecast, AQ and alerts");
   PowerManager.WakeLock wake=((PowerManager)c.getSystemService(Context.POWER_SERVICE)).last;check(wake.acquired==1&&wake.released==1&&!wake.held,"bounded wake lock released");
   before=hits;svc.onHandleIntent(new Intent(c,Arc7WeatherService.class).setAction("ca.arc7freedom.weather.REFRESH"));check(hits==before,"coalesce rapid widget refresh taps");
   Arc7WeatherWidgetProvider.refreshAll(c);check(hits==before,"render-only widget never recurses into fetch");
   check(manager.view.texts.get(R.id.weather_widget_status).contains("STALE")&&manager.view.clicks.get(R.id.weather_widget_refresh).type.equals("service"),"widget timestamp/stale and explicit refresh click");
   Arc7WeatherStore.clearCaches(c);Arc7WeatherWidgetProvider.refreshAll(c);check(manager.view.texts.get(R.id.weather_widget_temp).equals("\u2014"),"widget no demo temperature");
   Context suite=context();suite.owner="ca.arc7freedom.app";Arc7WeatherService.schedule(suite);Arc7WeatherService.request(suite,true);check(suite.started.isEmpty(),"suite never starts independent weather jobs");
   Arc7WeatherStore.savePlace(c,usa,true);Arc7WeatherStore.saveOptions(c,30,false,true,false);NotificationManager notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
   long realNow=System.currentTimeMillis();JSONObject active=new JSONObject(us);JSONArray list=new JSONArray();
   list.put(new JSONObject().put("id","new-warning").put("properties",new JSONObject().put("status","Actual").put("headline","Wind warning").put("id","new-warning").put("severity","Severe").put("sent","publication-one").put("effective",realNow/1000-60).put("expires",realNow/1000+3600)));
   active.put("features",list);Net t=new Net();Arc7WeatherStore.store(c,usa,"alerts",active.toString(),realNow,Arc7WeatherStore.epoch(c),t);
   Arc7WeatherService.notifyAlerts(c,null);Arc7WeatherService.notifyAlerts(c,usa);Arc7WeatherService.notifyAlerts(c,usa);check(notifications.count==1,"opt-in selected-city alert notification deduplicates");
   list.getJSONObject(0).optJSONObject("properties").put("sent","publication-two");
   Arc7WeatherStore.store(c,usa,"alerts",active.toString(),realNow+1,Arc7WeatherStore.epoch(c),t);Arc7WeatherService.notifyAlerts(c,usa);check(notifications.count==2,"new official publication notifies once");
   Arc7WeatherStore.select(c,p);Arc7WeatherService.notifyAlerts(c,usa);check(notifications.count==2,"old city's response never notifies for new city");
   int started=c.started.size();new Arc7WeatherBootReceiver().onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));check(c.started.size()==started+1,"boot restores own configured updates");
   new Arc7WeatherBootReceiver().onReceive(suite,new Intent(Intent.ACTION_BOOT_COMPLETED));check(suite.started.isEmpty(),"suite boot does not borrow weather networking");
   manager.ids=new int[0];Arc7WeatherStore.saveOptions(c,30,false,false,false);Arc7WeatherService.schedule(c);int cancelled=alarm.cancelled;check(cancelled>0,"removing widget and notifications cancels schedule");
   System.out.println("PASS Weather background/widget: non-waking/manual schedules, network policy, actual refresh, rapid-tap coalescing, no fake readings, alert opt-in/dedup/ownership and boot restore");
 }
 static void bridge()throws Exception {
   Context c=context(),suite=context();suite.owner="ca.arc7freedom.app";AppWidgetManager manager=AppWidgetManager.instance;manager.ids=new int[]{44};
   Arc7WeatherWidgetBridge provider=new Arc7WeatherWidgetBridge();provider.context=c;check(provider.onCreate(),"cache provider starts");suite.resolver.provider=provider;
   Arc7WeatherStore.savePlace(c,p,true);long realNow=System.currentTimeMillis();JSONObject current=new JSONObject(forecast);
   current.optJSONObject("daily").put("time",new JSONArray().put(realNow/1000));
   Arc7WeatherStore.store(c,p,"forecast",current.toString(),realNow,Arc7WeatherStore.epoch(c),new Net());
   String[] data=Arc7WeatherWidgetBridge.remoteSnapshot(suite);check(data.length==6&&data[0].equals(p.name)&&data[4].contains("High 12")&&data[4].contains("Low 4"),"signed cached high/low snapshot, no coordinates");
   check(((android.database.MatrixCursor)suite.resolver.last).closed,"remote cursor always closed");
   new Arc7WeatherWidgetProvider().onUpdate(suite,manager,new int[]{44});
   check(manager.view.texts.get(R.id.weather_widget_range).equals(data[4])&&manager.view.clicks.get(R.id.weather_widget_refresh).type.equals("broadcast"),"old suite widget uses separate Weather high/low and forwarded refresh");
   Intent request=manager.view.clicks.get(R.id.weather_widget_refresh).intent;check(request.component.pkg.equals("ca.arc7freedom.weather"),"refresh explicitly targets owning package");
   int started=c.started.size();new Arc7WeatherWidgetRequestReceiver().onReceive(c,request);
   check(c.started.size()==started+1&&Arc7WeatherWidgetBridge.hasLegacyWidgets(c),"legacy refresh starts only owning Weather service");check(suite.started.isEmpty(),"suite does not start its own weather jobs");
   manager.ids=new int[0];Arc7WeatherStore.saveOptions(c,30,false,false,false);Arc7WeatherService.schedule(c);
   check(((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).scheduled>0&&Arc7WeatherService.hasWidgets(c),"legacy-only widget retains owning Weather schedule");
   manager.ids=new int[]{44};Arc7WeatherWidgetBridge.notifyLegacy(c);Intent changed=c.broadcasts.get(c.broadcasts.size()-1);
   check(c.permission.equals(Arc7WeatherWidgetBridge.PERMISSION)&&changed.component.cls.endsWith("CacheReceiver"),"signed explicit cache change receiver");
   new Arc7WeatherWidgetCacheReceiver().onReceive(suite,changed);check(suite.broadcasts.get(suite.broadcasts.size()-1).getBooleanExtra("legacy_active",false),"Weather reinstall re-registers pinned suite widgets");
   c.pm.signature=false;try{provider.query(Arc7WeatherWidgetBridge.URI,null,null,null,null);throw new AssertionError("untrusted UID");}catch(SecurityException expected){}
   check(!Arc7WeatherWidgetBridge.hasLegacyWidgets(c),"missing/differently signed suite cannot keep alarm alive");c.pm.signature=true;
   suite.pm.signature=false;int queries=suite.resolver.queries;check(Arc7WeatherWidgetBridge.remoteSnapshot(suite)==null&&suite.resolver.queries==queries,"untrusted Weather never queried");suite.pm.signature=true;
   try{provider.query(Uri.parse("content://ca.arc7freedom.weather.widget/private"),null,null,null,null);throw new AssertionError();}catch(IllegalArgumentException expected){}
   try{provider.insert(Arc7WeatherWidgetBridge.URI,new ContentValues());throw new AssertionError();}catch(UnsupportedOperationException expected){}
   try{provider.update(Arc7WeatherWidgetBridge.URI,new ContentValues(),null,null);throw new AssertionError();}catch(UnsupportedOperationException expected){}
   try{provider.delete(Arc7WeatherWidgetBridge.URI,null,null);throw new AssertionError();}catch(UnsupportedOperationException expected){}
   suite.resolver.provider=null;Arc7WeatherWidgetProvider.refreshAll(suite);check(manager.view.texts.get(R.id.weather_widget_status).contains("Waiting"),"missing Weather shown honestly");
   new Arc7WeatherWidgetProvider().onDisabled(suite);Intent disabled=suite.broadcasts.get(suite.broadcasts.size()-1);new Arc7WeatherWidgetRequestReceiver().onReceive(c,disabled);
   check(!Arc7WeatherWidgetBridge.hasLegacyWidgets(c),"last legacy widget removal clears registration");
   System.out.println("PASS signed Weather widget bridge: actual provider/read-only/UID guards, high-low rendering, cursor close, own-UID manual and legacy scheduling, missing app, re-registration and disable cleanup");
 }
 static void radar()throws Exception {
   for(double lat:new double[]{-85,-43,0,43.589,85})equal(Arc7WeatherRadarMath.latitude(Arc7WeatherRadarMath.y(lat,5),5),lat,"Mercator round trip");
   equal(Arc7WeatherRadarMath.longitude(Arc7WeatherRadarMath.x(-79.644,5),5),-79.644,"longitude round trip");check(Arc7WeatherRadarMath.wrap(-1,5)==31,"dateline wrap");
   check(Double.isFinite(Arc7WeatherRadarMath.y(90,7)),"polar clamp");
   List<Arc7WeatherRadarMath.Frame> frames=Arc7WeatherRadarMath.frames(read("radar.json"),now);check(frames.size()==6,"last six PAST frames only");
   check(frames.get(5).tile(7,-1,50).contains("/256/7/127/50/2/1_1.png"),"public PNG/universal-blue/max7 tile contract");
   try{frames.get(0).tile(8,1,1);throw new AssertionError();}catch(IllegalArgumentException expected){}
   try{Arc7WeatherRadarMath.frames("{\"host\":\"https://evil.invalid\"}",now);throw new AssertionError();}catch(IllegalArgumentException expected){}
   System.out.println("PASS radar: past-only six frames, provider/path validation, public PNG/blue/zoom limits, Mercator/polar/dateline math");
 }
 public static void main(String[] args)throws Exception {
   fixtures=new File(args[0]);forecast=read("forecast.json");air=read("air.json");ca=read("ca.json");us=read("us.json");
   models();alerts();stores();radar();network();bridge();
 }
}'''


def write_fixtures(directory):
    z = ZoneInfo('America/Toronto')
    start = datetime(2026, 11, 1, tzinfo=z)
    now = datetime(2026, 11, 1, 6, 30, tzinfo=timezone.utc)
    timestamp = int(now.timestamp())
    hours = [int(start.timestamp()) + i * 3600 for i in range(14 * 24)]
    hourly = {'time': hours}
    values = {'temperature_2m': 7.5, 'apparent_temperature': 5.4, 'relative_humidity_2m': 71,
              'dew_point_2m': 2.1, 'precipitation_probability': 35, 'precipitation': 0.2,
              'snowfall': None, 'weather_code': 3, 'cloud_cover': 90, 'visibility': 16000,
              'pressure_msl': 1013.25, 'wind_speed_10m': 18.52, 'wind_direction_10m': 270,
              'wind_gusts_10m': 36, 'uv_index': 1.4, 'is_day': 0}
    for k, v in values.items():
        hourly[k] = [v] * len(hours)
    days = [start + timedelta(days=i) for i in range(14)]
    daily = {'time': [int(d.timestamp()) for d in days]}
    for k, v in {'weather_code': 3, 'temperature_2m_max': 12, 'temperature_2m_min': 4,
                 'apparent_temperature_max': 10, 'apparent_temperature_min': 1,
                 'precipitation_probability_max': 40, 'precipitation_sum': 2.4,
                 'snowfall_sum': None, 'precipitation_hours': 2, 'wind_speed_10m_max': 30,
                 'wind_gusts_10m_max': 45, 'wind_direction_10m_dominant': 270,
                 'uv_index_max': 2.4, 'daylight_duration': 36000, 'sunshine_duration': 18000}.items():
        daily[k] = [v] * 14
    daily['sunrise'] = [int((d + timedelta(hours=7)).timestamp()) for d in days]
    daily['sunset'] = [int((d + timedelta(hours=17)).timestamp()) for d in days]
    forecast = {'timezone': 'America/Toronto', 'utc_offset_seconds': -18000,
                'current': dict(values, time=timestamp), 'hourly': hourly, 'daily': daily}
    air = {'timezone': 'America/Toronto', 'hourly': {'time': hours[:72],
           'us_aqi': [42] * 72, 'european_aqi': [23] * 72, 'pm2_5': [8.5] * 72,
           'pm10': [12] * 72, 'ozone': [52] * 72, 'alder_pollen': [None] * 72}}
    def polygon(west=-80, south=43, east=-79, north=44):
        return {'type': 'Polygon', 'coordinates': [[[west, south], [east, south], [east, north], [west, north], [west, south]]]}
    ca_properties = {'id': 'ec-warning-1', 'alert_name_en': 'Wind warning',
                     'alert_text_en': 'Strong winds are expected. Secure loose objects.',
                     'feature_name_en': 'Mississauga', 'risk_colour_en': 'Yellow',
                     'publication_datetime': '2026-11-01T06:00:00.000Z',
                     'validity_datetime': '2026-11-01T10:00:00.000Z',
                     'expiration_datetime': '2026-11-01T18:00:00.000Z', 'status_en': 'Active'}
    ca = {'type': 'FeatureCollection', 'features': [
        {'type': 'Feature', 'properties': ca_properties, 'geometry': polygon()},
        {'type': 'Feature', 'properties': dict(ca_properties, id='elsewhere'), 'geometry': polygon(-90, 40, -89, 41)}]}
    us = {'type': 'FeatureCollection', 'features': []}
    for ident, status, msg, expires in [('watch', 'Actual', 'Alert', timestamp + 3600),
                                       ('expired', 'Actual', 'Alert', timestamp - 1),
                                       ('test', 'Test', 'Alert', timestamp + 3600),
                                       ('cancel', 'Actual', 'Cancel', timestamp + 3600)]:
        us['features'].append({'id': ident, 'properties': {'id': ident, 'headline': 'Wind watch',
            'description': 'Synthetic test response', 'instruction': 'Check official updates.',
            'severity': 'Severe', 'areaDesc': 'New York', 'status': status, 'messageType': msg,
            'sent': '2026-11-01T06:00:00Z', 'effective': timestamp - 60,
            'onset': timestamp + 1800, 'expires': expires}})
    hole = polygon()
    hole['coordinates'].append([[-79.7,43.5],[-79.6,43.5],[-79.6,43.7],[-79.7,43.7],[-79.7,43.5]])
    search = {'results': [{'name': 'London', 'admin1': 'Ontario', 'country_code': 'CA', 'latitude': 42.98, 'longitude': -81.25},
                          {'name': 'London', 'admin1': 'England', 'country_code': 'GB', 'latitude': 51.51, 'longitude': -0.12},
                          {'name': 'Duplicate', 'latitude': 42.98, 'longitude': -81.25},
                          {'name': 'Missing coordinates'}, {'name': 'Invalid', 'latitude': 200, 'longitude': 200}]}
    radar = {'host': 'https://tilecache.rainviewer.com', 'radar': {
        'past': [{'time': timestamp - 600 * i, 'path': f'/v2/radar/{timestamp - 600 * i}'} for i in range(12)],
        'nowcast': [{'time': timestamp + 600, 'path': f'/v2/radar/{timestamp + 600}'}]}}
    for name, data in {'forecast': forecast, 'air': air, 'ca': ca, 'us': us,
                       'polygon-hole': hole, 'search': search, 'radar': radar}.items():
        (directory / f'{name}.json').write_text(json.dumps(data))


def static_checks():
    manifest = ET.parse(ROOT / 'split-apps/weather/AndroidManifest.xml').getroot()
    assert manifest.get('package') == 'ca.arc7freedom.weather'
    features = {x.get(NS+'name'): x.get(NS+'required') for x in manifest.findall('uses-feature')}
    assert all(features.get('android.hardware.'+f) == 'false' for f in ('location', 'location.gps', 'location.network'))
    for tag, name in [('service','Arc7WeatherService'), ('receiver','Arc7WeatherBootReceiver')]:
        node = next(x for x in manifest.findall(f'application/{tag}') if x.get(NS+'name').endswith('.'+name))
        assert node.get(NS+'exported') == 'false'
    for other in (ROOT / 'split-apps').glob('*/AndroidManifest.xml'):
        if other.parent.name == 'weather':
            continue
        text = other.read_text()
        assert 'Arc7WeatherService' not in text and 'Arc7WeatherBootReceiver' not in text, other
        assert 'android.permission.ACCESS_FINE_LOCATION' not in text, other
    provider = manifest.find("application/provider")
    assert provider.get(NS+'authorities') == 'ca.arc7freedom.weather.widget'
    assert provider.get(NS+'readPermission') == 'ca.arc7freedom.permission.WEATHER_WIDGET' and provider.get(NS+'exported') == 'true'
    permission = manifest.find('permission')
    assert permission.get(NS+'protectionLevel') == 'signature'
    request = next(x for x in manifest.findall('application/receiver') if x.get(NS+'name').endswith('WidgetRequestReceiver'))
    assert request.get(NS+'permission') == 'ca.arc7freedom.permission.WEATHER_WIDGET'
    suite = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
    cache = next(x for x in suite.findall('application/receiver') if x.get(NS+'name').endswith('WidgetCacheReceiver'))
    assert cache.get(NS+'permission') == 'ca.arc7freedom.permission.WEATHER_WIDGET'
    assert 'weather_widget_range' in (ROOT/'app/src/main/res/layout/widget_arc7_weather.xml').read_text()
    store = (SRC / 'Arc7WeatherStore.java').read_text()
    assert 'Environment.getExternalStorageDirectory' not in store and 'new File("/sdcard' not in store
    assert '"forecast_days=14' in store or '&forecast_days=14' in store
    activity = (SRC / 'Arc7WeatherActivity.java').read_text()
    assert 'token==weatherGeneration' in activity and 'place.id.equals(target.id)' in activity
    assert 'token!=searchGeneration' in activity and 'searchDialog.isShowing()' in activity
    assert 'active=false; cancelWeather(); cancelSearch(); stopLocation(); closeRadar();' in activity
    assert 'removeUpdates(locationListener)' in activity and '20000L' in activity
    assert 'requestLocationUpdates' not in (SRC / 'Arc7WeatherService.java').read_text()
    widget = ET.parse(ROOT / 'app/src/main/res/xml/widget_arc7_weather_info.xml').getroot()
    assert widget.get(NS+'updatePeriodMillis') == '0', 'one owner for configurable update intervals; do not keep resetting >30m alarms'
    radar = (SRC / 'Arc7WeatherRadar.java').read_text()
    assert 'worker.shutdownNow()' in radar and 'token!=sequence' in radar and '12 MB' in radar
    assert 'outWidth!=256' in radar and 'outHeight!=256' in radar
    assert 'diskEpoch!=cacheEpoch' in radar and 'cacheEpoch++' in radar
    print('PASS Weather integration: isolated manifest/optional location, lifecycle/search generation guards, private caches, widget scheduling and bounded/cancellable radar')


def main():
    static_checks()
    toolchain = Path(os.environ.get('TOOLCHAIN_DIR', ROOT / 'build/toolchain'))
    java = os.environ.get('JAVA') or (str(toolchain/'jdk/bin/java') if (toolchain/'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(toolchain/'jdk/bin/javac') if (toolchain/'jdk/bin/javac').is_file() else shutil.which('javac'))
    assert java and javac, 'Run scripts/bootstrap-toolchain.sh or install a JDK'
    with tempfile.TemporaryDirectory(prefix='arc7-weather-') as tmp:
        tmp = Path(tmp)
        write_fixtures(tmp)
        sources = []
        for name, text in STUBS.items():
            file = tmp / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text)
            sources.append(file)
        harness = tmp/'WeatherHarness.java'
        harness.write_text(HARNESS)
        sources += [harness, *(ROOT/'tests/support/org/json').glob('*.java')]
        sources += [SRC/name for name in ('Arc7WeatherData.java','Arc7WeatherStore.java', 'Arc7WeatherRadarMath.java',
                    'Arc7WeatherTransport.java','Arc7WeatherService.java','Arc7WeatherWidgetProvider.java',
                    'Arc7WeatherBootReceiver.java','Arc7WeatherWidgetBridge.java','Arc7WeatherWidgetRequestReceiver.java',
                    'Arc7WeatherWidgetCacheReceiver.java','AppNetworkScope.java')]
        classes = tmp/'classes'
        classes.mkdir()
        subprocess.run([str(javac), '-encoding','UTF-8','-d',str(classes), *map(str,sources)], check=True)
        subprocess.run([str(java), '-ea','-cp',str(classes),'ca.arc7freedom.app.WeatherHarness',str(tmp)], check=True, timeout=65)
    print('ALL OFFLINE WEATHER REGRESSIONS PASSED (synthetic providers/fake Android, no device or live-service claim)')


if __name__ == '__main__':
    main()
