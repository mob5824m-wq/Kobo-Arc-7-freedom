#!/usr/bin/env python3
"""Run the production audio service against an Android-4-style MediaPlayer state machine.

Android 4.2's JNI getDuration/getCurrentPosition/stop paths report INVALID_OPERATION
through OnError(-38, 0), not a Java exception. Catching Throwable does not make polling
an unprepared native player safe. All Android/media/HTTP objects here are recording
fakes: this is a lifecycle regression, not a device, decoder or live-provider test.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/ca/arc7freedom/app'

STUBS = {
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/Binder.java': 'package android.os; public class Binder implements IBinder {}',
    'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=17;}}',
    'android/os/PowerManager.java': 'package android.os; public class PowerManager {public static final int PARTIAL_WAKE_LOCK=1;}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 private static final List<Runnable> ready=new ArrayList<Runnable>();
 private static final List<Runnable> delayed=new ArrayList<Runnable>();
 public Handler(){} public Handler(Looper l){}
 public boolean post(Runnable r){synchronized(ready){ready.add(r);}return true;}
 public boolean postDelayed(Runnable r,long ms){synchronized(delayed){delayed.add(r);}return true;}
 public void removeCallbacks(Runnable r){synchronized(ready){while(ready.remove(r)){} }synchronized(delayed){while(delayed.remove(r)){} }}
 public static void drain(){int count=0;while(true){Runnable r;synchronized(ready){if(ready.isEmpty())return;r=ready.remove(0);}if(++count>1000)throw new AssertionError("Callback loop");r.run();}}
 public static List<Runnable> timers(){synchronized(delayed){return new ArrayList<Runnable>(delayed);}}
 public static void fireTimer(Runnable r){new Handler().removeCallbacks(r);r.run();}
 public static void reset(){synchronized(ready){ready.clear();}synchronized(delayed){delayed.clear();}}
}''',
    'android/content/Intent.java': '''package android.content; import java.util.*;
public class Intent {private String action;private Map<String,Object> extras=new HashMap<String,Object>();
 public Intent(){}public Intent(Context c,Class<?> t){}public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}
 public static final String ACTION_MEDIA_BUTTON="android.intent.action.MEDIA_BUTTON",EXTRA_KEY_EVENT="event";
 public Intent putExtra(String key,int n){extras.put(key,n);return this;}public Intent putExtra(String key,Object v){extras.put(key,v);return this;}public Object getParcelableExtra(String k){return extras.get(k);}
 public String getStringExtra(String key){Object o=extras.get(key);return o instanceof String?(String)o:null;}public int getIntExtra(String k,int d){Object o=extras.get(k);return o instanceof Integer?(Integer)o:d;}}
''',
    'android/content/IntentFilter.java': 'package android.content;public class IntentFilter {public IntentFilter(){}public IntentFilter(String s){}public void addAction(String s){}}',
    'android/content/ComponentName.java': 'package android.content;public class ComponentName {public ComponentName(Context c,Class<?> t){}}',
    'android/content/BroadcastReceiver.java': 'package android.content;public abstract class BroadcastReceiver {public boolean isOrderedBroadcast(){return true;}public void abortBroadcast(){}public abstract void onReceive(Context c,Intent i);}',
    'android/content/SharedPreferences.java': '''package android.content;import java.util.*;
public class SharedPreferences {private Map<String,Object> values=new HashMap<String,Object>();public int getInt(String k,int d){Object v=values.get(k);return v instanceof Integer?(Integer)v:d;}public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
 public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}public long getLong(String k,long d){Object v=values.get(k);return v instanceof Long?(Long)v:d;}
 public Editor edit(){return new Editor();}public class Editor {public Editor putInt(String k,int v){values.put(k,v);return this;}public Editor putString(String k,String v){values.put(k,v);return this;}public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}public Editor putLong(String k,long v){values.put(k,v);return this;}public Editor remove(String k){values.remove(k);return this;}public boolean commit(){return true;}}}''',
    'android/content/Context.java': '''package android.content;import java.util.*;
public class Context {public static final String WIFI_SERVICE="wifi",NOTIFICATION_SERVICE="notification",AUDIO_SERVICE="audio";public static final int MODE_PRIVATE=0;
 private Map<String,SharedPreferences> prefs=new HashMap<String,SharedPreferences>();private android.net.wifi.WifiManager wifi=new android.net.wifi.WifiManager();private android.app.NotificationManager notices=new android.app.NotificationManager();public android.media.AudioManager audio=new android.media.AudioManager();
 public Context getApplicationContext(){return this;}public String getPackageName(){return "ca.arc7freedom.ytmusic";}public Object getSystemService(String s){return WIFI_SERVICE.equals(s)?wifi:AUDIO_SERVICE.equals(s)?audio:notices;}
 public Intent lastService;public void startService(Intent i){lastService=i;}public void registerReceiver(BroadcastReceiver r,IntentFilter f){}public void unregisterReceiver(BroadcastReceiver r){}
 public SharedPreferences getSharedPreferences(String n,int m){if(!prefs.containsKey(n))prefs.put(n,new SharedPreferences());return prefs.get(n);}}
''',
    'android/app/Service.java': '''package android.app;import android.content.*;import android.os.*;
public class Service extends Context {public static final int START_STICKY=1;public void onCreate(){}public IBinder onBind(Intent i){return null;}public int onStartCommand(Intent i,int flags,int id){return 0;}public void onDestroy(){}public void startForeground(int id,Notification n){}public void stopForeground(boolean remove){}}''',
    'android/app/PendingIntent.java': '''package android.app;import android.content.*;public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1;public static PendingIntent getActivity(Context c,int code,Intent i,int f){return new PendingIntent();}public static PendingIntent getService(Context c,int code,Intent i,int f){return new PendingIntent();}}''',
    'android/app/NotificationManager.java': 'package android.app;public class NotificationManager {public void notify(int id,Notification n){}public void cancel(int id){}}',
    'android/app/Notification.java': '''package android.app;import android.content.*;public class Notification {public static final int PRIORITY_HIGH=1;public android.widget.RemoteViews contentView,bigContentView;
 public static class Builder {public Builder(Context c){}public Builder setSmallIcon(int n){return this;}public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setContentIntent(PendingIntent p){return this;}public Builder setDeleteIntent(PendingIntent p){return this;}public Builder setOngoing(boolean b){return this;}public Builder setPriority(int n){return this;}public Notification build(){return new Notification();}public Notification getNotification(){return build();}}}''',
    'android/widget/RemoteViews.java': '''package android.widget;public class RemoteViews {public RemoteViews(String p,int id){}public void setTextViewText(int id,String s){}public void setImageViewBitmap(int id,Object b){}public void setImageViewResource(int id,int n){}public void setOnClickPendingIntent(int id,android.app.PendingIntent p){}public void setProgressBar(int id,int max,int p,boolean i){}}''',
    'android/widget/Toast.java': '''package android.widget;import java.util.*;public class Toast {public static final int LENGTH_LONG=1;public static final List<String> messages=new ArrayList<String>();private String text;public static Toast makeText(android.content.Context c,String s,int d){Toast t=new Toast();t.text=s;return t;}public void show(){messages.add(text);}}''',
    'android/net/wifi/WifiManager.java': '''package android.net.wifi;public class WifiManager {public static final int WIFI_MODE_FULL_HIGH_PERF=3;public WifiLock createWifiLock(int m,String s){return new WifiLock();}public static class WifiLock {private boolean held;public void setReferenceCounted(boolean b){}public boolean isHeld(){return held;}public void acquire(){held=true;}public void release(){held=false;}}}''',
    'android/view/KeyEvent.java': '''package android.view;public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_HEADSETHOOK=79,KEYCODE_MEDIA_PLAY_PAUSE=85,KEYCODE_MEDIA_PLAY=126,KEYCODE_MEDIA_PAUSE=127,KEYCODE_MEDIA_NEXT=87,KEYCODE_MEDIA_PREVIOUS=88,KEYCODE_MEDIA_REWIND=89,KEYCODE_MEDIA_FAST_FORWARD=90,KEYCODE_MEDIA_STOP=86;
 private int action,code,repeat;public KeyEvent(int a,int k,int r){action=a;code=k;repeat=r;}public int getAction(){return action;}public int getKeyCode(){return code;}public int getRepeatCount(){return repeat;}
}''',
    'android/media/AudioManager.java': '''package android.media;public class AudioManager {
 public static final int STREAM_MUSIC=3,AUDIOFOCUS_GAIN=1,AUDIOFOCUS_LOSS=-1,AUDIOFOCUS_LOSS_TRANSIENT=-2,AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK=-3,AUDIOFOCUS_REQUEST_GRANTED=1;
 public static final String ACTION_AUDIO_BECOMING_NOISY="android.media.AUDIO_BECOMING_NOISY";
 public interface OnAudioFocusChangeListener {void onAudioFocusChange(int change);}
 public OnAudioFocusChangeListener listener;public int result=1,requests,abandons,registrations;
 public int requestAudioFocus(OnAudioFocusChangeListener l,int stream,int duration){listener=l;requests++;return result;}
 public int abandonAudioFocus(OnAudioFocusChangeListener l){if(listener==l)listener=null;abandons++;return 1;}
 public void registerMediaButtonEventReceiver(android.content.ComponentName c){registrations++;}public void unregisterMediaButtonEventReceiver(android.content.ComponentName c){registrations--;}
 public void change(int c){OnAudioFocusChangeListener l=listener;if(l!=null)l.onAudioFocusChange(c);}
}''',
    'android/media/audiofx/Equalizer.java': '''package android.media.audiofx;public class Equalizer {public static int created;public Equalizer(int p,int session){created++;}public void setEnabled(boolean b){}public short getNumberOfBands(){return 5;}public short[] getBandLevelRange(){return new short[]{-1200,1200};}public void setBandLevel(short b,short l){}public void release(){}}''',
    'android/media/audiofx/BassBoost.java': 'package android.media.audiofx;public class BassBoost {public BassBoost(int p,int s){}public void setEnabled(boolean b){}public void setStrength(short s){}public void release(){}}',
    'ca/arc7freedom/app/Arc7AudioWidgetProvider.java': 'package ca.arc7freedom.app;public class Arc7AudioWidgetProvider {public static void refreshAll(android.content.Context c,String s,boolean p){}}',
    'ca/arc7freedom/app/MorpheHlsRelay.java': 'package ca.arc7freedom.app;public class MorpheHlsRelay {public static void cancel(String s){}}',
    'ca/arc7freedom/app/MorpheStreamServer.java': '''package ca.arc7freedom.app;import java.util.*;public class MorpheStreamServer {public static final Set<String> cancelled=Collections.synchronizedSet(new HashSet<String>());public static String getMimeType(String s){return "audio/mp4";}public static String getFailure(String s){return "";}public static String getPreparationStatus(String s){return "";}public static void cancel(String s){if(s!=null)cancelled.add(s);}}''',
    'ca/arc7freedom/app/MorpheEngine.java': '''package ca.arc7freedom.app;import java.util.*;import java.util.concurrent.*;import android.content.*;
public class MorpheEngine {
 public static volatile int resolves,compatibilityCalls;public static volatile boolean sponsorEnabled;public static volatile CountDownLatch fallbackEntered,fallbackRelease;
 public static class MediaItem {public String title="Fixture song",author="Fixture artist",videoId="fixture0001",localFilePath="",album="";public long durationSec;public String bestThumb(){return "";}}
 public static class SkipSegment {public float startSec,endSec;}
 public static class ResolvedPlayback {public boolean verifiedAudio;public long durationSec;public String title="Fixture song",author="Fixture artist",thumbnailUrl="",localAudioProxyUrl="",localVideoProxyUrl="";public List<SkipSegment> skipSegments=new ArrayList<SkipSegment>();public List<MediaItem> relatedItems=new ArrayList<MediaItem>();}
 public interface ResolutionProgressListener {void onProgress(String message);}
 public static ResolvedPlayback resolveStreams(Context c,String id,boolean audio,ResolutionProgressListener progress){ResolvedPlayback r=new ResolvedPlayback();r.localAudioProxyUrl="http://fixture/audio-"+(++resolves)+".m4a";return r;}
 public static String startAudioCompatibilityFallback(Context c,ResolvedPlayback r){int call=++compatibilityCalls;if(fallbackEntered!=null){fallbackEntered.countDown();boolean interrupted=false;while(true){try{fallbackRelease.await();break;}catch(InterruptedException e){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();}return "http://fixture/compat-"+call+".aac";}
 public static void enrichPlaybackMetadata(Context c,ResolvedPlayback r){}public static void cancelPlayback(ResolvedPlayback r){}public static boolean isSponsorBlockEnabled(Context c){return sponsorEnabled;}public static String prepareRadioStream(Context c,String s){return s;}
 public static final List<String> listensSent = Collections.synchronizedList(new ArrayList<String>());
 public static String requestHttpsString(Context c,String method,String url,String body,Map<String,String> headers,int timeoutMs) throws Exception {
     StringBuilder sb = new StringBuilder();
     sb.append(method).append(' ').append(url).append('\\n').append(headers == null ? "" : headers.toString()).append('\\n').append(body == null ? "" : body);
     listensSent.add(sb.toString());
     return "{\\"status\\":\\"ok\\"}";
 }
}''',
    'ca/arc7freedom/app/NowPlayingManager.java': '''package ca.arc7freedom.app;import android.content.*;
public class NowPlayingManager {
 public static int playingPublications;private static Snapshot snapshot=new Snapshot();
 public static class Snapshot {public boolean active,playing;public String sourcePkg="",sourceApp="Music",title="",artist="";public Object artwork;public long durationMs;public long livePositionMs(){return 0;}}
 public static void onExternalMediaBroadcast(Context c,Intent i){}public static void syncExternalAudioIfNeeded(Context c,boolean b){}public static boolean isYouTubeForegroundVideoActive(){return false;}public static void setYouTubeForegroundVideoActive(boolean b){}public static void dispatchTransportAction(Context c,String s){}
 public static Snapshot getSnapshot(Context c){return snapshot;}public static Intent buildOpenPlayerIntent(Context c,Snapshot s){return new Intent();}public static String formatDurationMs(long n){return "0:00";}
 public static void publishBuiltIn(Context c,String source,String launch,String title,String artist,String album,String url,Object art,boolean playing,long position,long duration){if(playing)playingPublications++;snapshot=new Snapshot();snapshot.active=true;snapshot.playing=playing;snapshot.sourcePkg=c.getPackageName();snapshot.sourceApp=source;snapshot.title=title;snapshot.artist=artist;snapshot.durationMs=duration;}
 public static void clearSession(Context c){snapshot=new Snapshot();}
}''',
}

MEDIA_PLAYER = r'''package android.media;
import java.io.FileDescriptor;
import java.util.*;
import java.util.concurrent.*;
import android.os.*;
/** Recording fake of Android 4.2 JNI errors: invalid calls post OnError, they do NOT throw. */
public class MediaPlayer {
 public interface OnBufferingUpdateListener {void onBufferingUpdate(MediaPlayer p,int percent);}
 public OnBufferingUpdateListener bufferingListener;public void setOnBufferingUpdateListener(OnBufferingUpdateListener l){bufferingListener=l;}
 public interface OnPreparedListener {void onPrepared(MediaPlayer p);}public interface OnErrorListener {boolean onError(MediaPlayer p,int what,int extra);}public interface OnCompletionListener {void onCompletion(MediaPlayer p);}
 public static final int IDLE=0,INITIALIZED=1,PREPARING=2,PREPARED=3,STARTED=4,PAUSED=5,COMPLETED=6,ERROR=7,RELEASED=8;
 public static volatile MediaPlayer latest;public static final List<String> invalidCalls=Collections.synchronizedList(new ArrayList<String>());
 public static final List<String> unlockedCalls=Collections.synchronizedList(new ArrayList<String>());
 public int state=IDLE,starts,stops,queries,volumeWrites,position=3000;public float volume=1f;public String dataSource="";public Object ownerMonitor;
 public volatile CountDownLatch durationEntered,durationRelease;
 public OnPreparedListener preparedListener;public OnErrorListener errorListener;public OnCompletionListener completionListener;
 public MediaPlayer(){latest=this;}
 public void setOnPreparedListener(OnPreparedListener l){preparedListener=l;}public void setOnErrorListener(OnErrorListener l){errorListener=l;}public void setOnCompletionListener(OnCompletionListener l){completionListener=l;}
 private boolean ready(){return state==PREPARED||state==STARTED||state==PAUSED||state==COMPLETED;}
 private void checkMonitor(String op){if(ownerMonitor!=null&&!Thread.holdsLock(ownerMonitor))unlockedCalls.add(op);}
 private boolean requireReady(String op){checkMonitor(op);if(ready())return true;invalidCalls.add(op+" in state "+state);emitError(-38,0);return false;}
 public void setAudioStreamType(int n){}public void setWakeMode(android.content.Context c,int n){}
 public void setDataSource(String s){if(state!=IDLE)throw new AssertionError("setDataSource before idle");dataSource=s;state=INITIALIZED;}
 public void setDataSource(FileDescriptor fd){setDataSource("file-descriptor");}
 public void prepareAsync(){if(state!=INITIALIZED)throw new AssertionError("prepareAsync before data source");state=PREPARING;}
 public void prepare(){if(state!=INITIALIZED)throw new AssertionError("prepare before data source");state=PREPARED;}
 public void emitPrepared(){if(state!=PREPARING)throw new AssertionError("prepare callback after error");state=PREPARED;final OnPreparedListener l=preparedListener;new Handler().post(new Runnable(){public void run(){if(l!=null)l.onPrepared(MediaPlayer.this);}});}
 public void emitError(final int what,final int extra){state=ERROR;final OnErrorListener l=errorListener;new Handler().post(new Runnable(){public void run(){if(l!=null)l.onError(MediaPlayer.this,what,extra);}});}
 public int getDuration(){queries++;checkMonitor("getDuration");CountDownLatch entered=durationEntered,finish=durationRelease;if(entered!=null){entered.countDown();try{if(!finish.await(5,TimeUnit.SECONDS))throw new AssertionError("Duration latch timed out");}catch(InterruptedException e){throw new AssertionError(e);}}return requireReady("getDuration")?42000:0;}
 public int getCurrentPosition(){queries++;return requireReady("getCurrentPosition")?position:0;}
 public boolean isPlaying(){checkMonitor("isPlaying");queries++;return state==STARTED;}
 public int getAudioSessionId(){checkMonitor("getAudioSessionId");return 1;}
 public void start(){if(requireReady("start")){starts++;state=STARTED;}}
 public void pause(){if(requireReady("pause"))state=PAUSED;}
 public void seekTo(int n){if(requireReady("seekTo"))position=n;}
 public void stop(){stops++;if(requireReady("stop"))state=INITIALIZED;}
 public void release(){checkMonitor("release");state=RELEASED;}
 public void setVolume(float left,float right){checkMonitor("setVolume");volumeWrites++;volume=left;}
 public void emitCompletion(){state=COMPLETED;final OnCompletionListener l=completionListener;new Handler().post(new Runnable(){public void run(){if(l!=null)l.onCompletion(MediaPlayer.this);}});}
 public static class TrackInfo {public static final int MEDIA_TRACK_TYPE_UNKNOWN=0,MEDIA_TRACK_TYPE_AUDIO=2;public int getTrackType(){return 2;}}
 public TrackInfo[] getTrackInfo(){if(!requireReady("getTrackInfo"))return null;return new TrackInfo[]{new TrackInfo()};}
}'''


def main():
    tools = Path(os.environ.get('TOOLCHAIN_DIR', ROOT/'build/toolchain'))
    java = os.environ.get('JAVA') or (str(tools/'jdk/bin/java') if (tools/'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(tools/'jdk/bin/javac') if (tools/'jdk/bin/javac').is_file() else shutil.which('javac'))
    if not java or not javac:
        raise SystemExit('Install a JDK or run scripts/bootstrap-toolchain.sh')
    service = (SRC/'Arc7AudioService.java').read_text()
    resources = {}
    for group, name in re.findall(r'R\.(\w+)\.(\w+)', service):
        resources.setdefault(group, set()).add(name)
    r = 'package ca.arc7freedom.app;public class R {'
    for group, names in sorted(resources.items()):
        r += 'public static class '+group+' {'
        for index, name in enumerate(sorted(names), 1):
            r += 'public static final int '+name+'='+str(index)+';'
        r += '}'
    r += '}'
    stubs = dict(STUBS, **{'android/media/MediaPlayer.java': MEDIA_PLAYER, 'ca/arc7freedom/app/R.java': r})
    with tempfile.TemporaryDirectory(prefix='arc7-audio-state-') as directory:
        tmp = Path(directory)
        sources = []
        for name, text in stubs.items():
            path = tmp/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            sources.append(path)
        sources += [SRC/name for name in ('Arc7AudioService.java', 'Arc7MediaButtonReceiver.java', 'AudioFocusPolicy.java', 'LegacyMediaPolicy.java', 'LegacyPlaybackAudio.java', 'MediaNotificationPolicy.java', 'ListenBrainzEngine.java')]
        orgjson = ROOT/'tests/support/org/json'
        sources += [orgjson/name for name in ('JSONObject.java', 'JSONArray.java', 'MiniJson.java')]
        sources += [ROOT/'shared/java/ca/arc7freedom/support'/name for name in ('DebugLog.java','MediaCache.java')]
        sources.append(ROOT/'tests/java/AudioServiceStateHarness.java')
        subprocess.run(list(map(str, [javac, '-nowarn', '-d', tmp/'classes', *sources])), check=True, timeout=90)
        subprocess.run(list(map(str, [java, '-ea', '-cp', tmp/'classes', 'ca.arc7freedom.app.AudioServiceStateHarness'])), check=True, timeout=45)
    print('ALL AUDIO STATE REGRESSIONS PASSED (production service + recording Android-4 MediaPlayer, not device/decoder/live-provider validation)')


if __name__ == '__main__':
    import traceback
    try:
        main()
    except Exception:
        message = traceback.format_exc()
        print(message, file=sys.stderr, flush=True)
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            encoded = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::error title=Audio player lifecycle regression::'+encoded, flush=True)
        sys.exit(1)
