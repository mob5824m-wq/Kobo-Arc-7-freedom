#!/usr/bin/env python3
"""Run production Arc7ClockStore + Arc7AlarmReceiver against recording Android AlarmManager/PendingIntent fakes."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/ca/arc7freedom/app'

STUBS = {
    'android/os/Environment.java': '''package android.os;import java.io.File;
public class Environment {public static File getExternalStorageDirectory(){return new File("/tmp/arc7-alarm-test-sd");}}''',
    'android/os/PowerManager.java': '''package android.os;
public class PowerManager {public static final int PARTIAL_WAKE_LOCK=1;
 public WakeLock newWakeLock(int flags,String tag){return new WakeLock();}
 public static class WakeLock {private boolean held;public void setReferenceCounted(boolean b){}public boolean isHeld(){return held;}public void acquire(){}public void acquire(long ms){held=true;}public void release(){held=false;}}}''',
    'android/net/Uri.java': 'package android.net;public class Uri {public static String encode(String s){return s;}}',
    'android/content/ContentResolver.java': 'package android.content;public class ContentResolver {}',
    'android/provider/Settings.java': '''package android.provider;import android.content.*;public class Settings {public static class System {
 public static final String TIME_12_24="time_12_24";
 public static boolean is24HourFormat(Context c){return false;}
 public static String getString(ContentResolver r,String k){return "12";}
 public static boolean putString(ContentResolver r,String k,String v){return true;}}}''',
    'android/text/format/DateFormat.java': 'package android.text.format;public class DateFormat {public static boolean is24HourFormat(android.content.Context c){return false;}}',
    'android/media/AudioFormat.java': 'package android.media;public class AudioFormat {public static final int CHANNEL_OUT_MONO=4,ENCODING_PCM_16BIT=2;}',
    'android/media/AudioManager.java': '''package android.media;public class AudioManager {public static final int STREAM_ALARM=4,MODE_STREAM=1;
 public int getStreamMaxVolume(int s){return 15;}public void setStreamVolume(int s,int v,int f){}}''',
    'android/media/AudioTrack.java': '''package android.media;public class AudioTrack {public static final int MODE_STREAM=1;
 public AudioTrack(int s,int r,int c,int f,int b,int m){}public static int getMinBufferSize(int r,int c,int f){return 4096;}
 public void setStereoVolume(float l,float r){}public void play(){}public int write(short[] d,int o,int l){return l;}public void stop(){}public void release(){}}''',
    'android/media/MediaPlayer.java': '''package android.media;public class MediaPlayer {
 public void setAudioStreamType(int s){}public void setDataSource(String p){}public void setLooping(boolean b){}
 public void setVolume(float l,float r){}public void prepare(){}public void start(){}public void stop(){}public void release(){}}''',
    'android/content/Intent.java': '''package android.content;import java.util.*;
public class Intent {
 public static final String ACTION_BOOT_COMPLETED="android.intent.action.BOOT_COMPLETED",
  ACTION_TIME_CHANGED="android.intent.action.TIME_SET",
  ACTION_TIMEZONE_CHANGED="android.intent.action.TIMEZONE_CHANGED";
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2,FLAG_ACTIVITY_NO_USER_ACTION=4;
 private String action;private Class<?> target;private final Map<String,Object> extras=new HashMap<String,Object>();
 public Intent(){}public Intent(String a){action=a;}public Intent(Context c,Class<?> t){target=t;}
 public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}
 public Class<?> getTargetClass(){return target;}
 public Intent addFlags(int f){return this;}
 public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public int getIntExtra(String k,int d){Object o=extras.get(k);return o instanceof Integer?(Integer)o:d;}
 public boolean getBooleanExtra(String k,boolean d){Object o=extras.get(k);return o instanceof Boolean?(Boolean)o:d;}
 public String getStringExtra(String k){Object o=extras.get(k);return o instanceof String?(String)o:null;}}''',
    'android/content/SharedPreferences.java': '''package android.content;import java.util.*;
public class SharedPreferences {
 private final Map<String,Object> values=new HashMap<String,Object>();
 public boolean contains(String k){return values.containsKey(k);}
 public int getInt(String k,int d){Object v=values.get(k);return v instanceof Integer?(Integer)v:d;}
 public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
 public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
 public long getLong(String k,long d){Object v=values.get(k);return v instanceof Long?(Long)v:d;}
 public Editor edit(){return new Editor();}
 public class Editor {
  public Editor putInt(String k,int v){values.put(k,v);return this;}
  public Editor putString(String k,String v){values.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
  public Editor putLong(String k,long v){values.put(k,v);return this;}
  public Editor remove(String k){values.remove(k);return this;}
  public boolean commit(){return true;}}}''',
    'android/content/BroadcastReceiver.java': 'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
    'android/app/PendingIntent.java': '''package android.app;import android.content.*;import java.util.*;
public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1<<27,FLAG_NO_CREATE=1<<29;
 private static final Map<String,PendingIntent> REGISTRY=new HashMap<String,PendingIntent>();
 public final String key;public Intent intent;public boolean cancelled=false;
 private PendingIntent(String k,Intent i){key=k;intent=i;}
 public static PendingIntent getBroadcast(Context c,int req,Intent i,int flags){
  String k=c.getPackageName()+"|"+req+"|"+(i!=null?i.getAction():"")+"|"+(i!=null&&i.getTargetClass()!=null?i.getTargetClass().getName():"");
  PendingIntent existing=REGISTRY.get(k);
  if((flags&FLAG_NO_CREATE)!=0)return existing;
  if(existing==null){existing=new PendingIntent(k,i);REGISTRY.put(k,existing);}
  else{existing.intent=i;existing.cancelled=false;}
  return existing;}
 public void cancel(){cancelled=true;REGISTRY.remove(key);}}''',
    'android/app/AlarmManager.java': '''package android.app;import java.util.*;
public class AlarmManager {
 public static final int RTC_WAKEUP=0;
 private final Map<String,Long> scheduled=new LinkedHashMap<String,Long>();
 public PendingIntent lastCancelledPendingIntent;
 public void set(int type,long triggerAt,PendingIntent pi){if(pi!=null&&!pi.cancelled)scheduled.put(pi.key,triggerAt);}
 public void cancel(PendingIntent pi){lastCancelledPendingIntent=pi;if(pi!=null)scheduled.remove(pi.key);}
 public int activeCount(){return scheduled.size();}}''',
    'android/content/Context.java': '''package android.content;import android.app.*;import android.media.*;import android.os.*;import java.util.*;
public class Context {
 public static final String ALARM_SERVICE="alarm",POWER_SERVICE="power",AUDIO_SERVICE="audio";public static final int MODE_PRIVATE=0;
 private final String pkg;
 private final AlarmManager am=new AlarmManager();
 private final PowerManager pm=new PowerManager();
 private final AudioManager audio=new AudioManager();
 private final ContentResolver cr=new ContentResolver();
 private final Map<String,SharedPreferences> prefs=new HashMap<String,SharedPreferences>();
 public final List<Intent> startedActivities=new ArrayList<Intent>();
 public Context(String pkg){this.pkg=pkg;}
 public Context getApplicationContext(){return this;}
 public String getPackageName(){return pkg;}
 public ContentResolver getContentResolver(){return cr;}
 public Object getSystemService(String name){return ALARM_SERVICE.equals(name)?am:POWER_SERVICE.equals(name)?pm:AUDIO_SERVICE.equals(name)?audio:null;}
 public SharedPreferences getSharedPreferences(String n,int m){if(!prefs.containsKey(n))prefs.put(n,new SharedPreferences());return prefs.get(n);}
 public void startActivity(Intent i){startedActivities.add(i);}
 public void sendBroadcast(Intent i){}}''',
    'ca/arc7freedom/app/RootShell.java': 'package ca.arc7freedom.app;import android.content.*;public class RootShell {public RootShell(Context c){}public void execRoot(String cmd,Object cb){}}',
    'ca/arc7freedom/app/Arc7ClockWidgetProvider.java': 'package ca.arc7freedom.app;import android.content.*;public class Arc7ClockWidgetProvider {public static void refreshAll(Context c){}}',
    'ca/arc7freedom/app/Arc7AlarmAlertActivity.java': 'package ca.arc7freedom.app;public class Arc7AlarmAlertActivity {}',
    'ca/arc7freedom/app/Arc7TlsGatewayService.java': 'package ca.arc7freedom.app;import android.content.*;public class Arc7TlsGatewayService {public static void ensureStarted(Context c){}}',
    'ca/arc7freedom/app/NetworkCertManager.java': 'package ca.arc7freedom.app;public class NetworkCertManager {public static final int DEFAULT_PROXY_PORT=8118;}',
    'ca/arc7freedom/app/Arc7CalendarStore.java': 'package ca.arc7freedom.app;public class Arc7CalendarStore {public static final String ACTION_CALENDAR_REMINDER="ca.arc7freedom.app.ACTION_CALENDAR_REMINDER";}',
}


def main():
    tools = Path(os.environ.get('TOOLCHAIN_DIR', ROOT/'build/toolchain'))
    java = os.environ.get('JAVA') or (str(tools/'jdk/bin/java') if (tools/'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(tools/'jdk/bin/javac') if (tools/'jdk/bin/javac').is_file() else shutil.which('javac'))
    if not java or not javac:
        raise SystemExit('Install a JDK or run scripts/bootstrap-toolchain.sh')
    with tempfile.TemporaryDirectory(prefix='arc7-alarm-state-') as directory:
        tmp = Path(directory)
        sources = []
        for name, text in STUBS.items():
            path = tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            sources.append(path)
        sources += [SRC / name for name in ('Arc7ClockStore.java', 'Arc7AlarmReceiver.java')]
        orgjson = ROOT / 'tests/support/org/json'
        sources += [orgjson / name for name in ('JSONObject.java', 'JSONArray.java', 'MiniJson.java')]
        sources.append(ROOT / 'tests/java/AlarmStateHarness.java')
        subprocess.run(list(map(str, [javac, '-nowarn', '-d', tmp / 'classes', *sources])), check=True, timeout=90)
        subprocess.run(list(map(str, [java, '-ea', '-cp', tmp / 'classes', 'ca.arc7freedom.app.AlarmStateHarness'])), check=True, timeout=45)
    print('ALL ALARM STATE REGRESSIONS PASSED (production store + receiver + recording AlarmManager/PendingIntent fakes)')


if __name__ == '__main__':
    main()
