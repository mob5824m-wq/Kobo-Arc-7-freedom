#!/usr/bin/env python3
"""Exercise the foreground YouTube timeline and preparation UI with recording Android fakes.

The real Activity is compiled against the Android 4.2 API, then run on the host with fake
Android/MediaPlayer/provider objects. This is not a device, decoder or live-YouTube test.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/ca/arc7freedom/app'

# Shadow only the Android and app collaborators reached by the tested Activity paths.
# Everything else comes from the real API-17 jar / freshly compiled production classes.
STUBS = {
    'android/os/SystemClock.java': '''package android.os; public class SystemClock {
 public static long now; public static long elapsedRealtime(){return now;} public static long uptimeMillis(){return now;}
}''',
    'android/os/Build.java': '''package android.os; public class Build {public static class VERSION {public static int SDK_INT=17;}}''',
    'android/os/Looper.java': '''package android.os; public class Looper {
 public static Looper getMainLooper(){return new Looper();}
}''',
    'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 public static final List<Runnable> ready=Collections.synchronizedList(new ArrayList<Runnable>());
 public static final List<Runnable> delayed=Collections.synchronizedList(new ArrayList<Runnable>());
 public Handler(){} public Handler(Looper l){} public boolean post(Runnable r){ready.add(r);return true;}
 public boolean postDelayed(Runnable r,long n){delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){while(ready.remove(r)){}while(delayed.remove(r)){}}
 public static void drain(){while(true){Runnable r;synchronized(ready){if(ready.isEmpty())return;r=ready.remove(0);}r.run();}}
}''',
    'android/content/Context.java': '''package android.content; public class Context {
 public static final String AUDIO_SERVICE="audio"; public ComponentName startService(Intent i){return null;}
}''',
    'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {}''',
    'android/content/DialogInterface.java': '''package android.content; public interface DialogInterface {
 public interface OnClickListener {void onClick(DialogInterface d,int which);}
}''',
    'android/app/AlertDialog.java': '''package android.app; import android.content.*; public class AlertDialog {
 public static class Builder {public static DialogInterface.OnClickListener itemClick;public static CharSequence[] items;
 public Builder(Context c){}public Builder setTitle(CharSequence s){return this;}
 public Builder setItems(CharSequence[] i,DialogInterface.OnClickListener l){items=i;itemClick=l;return this;}
 public Builder setNegativeButton(CharSequence s,DialogInterface.OnClickListener l){return this;}
 public AlertDialog show(){return new AlertDialog();}}
}''',
    'android/content/Intent.java': '''package android.content; import java.util.*; public class Intent {
 private final Map<String,String> extras=new HashMap<String,String>(); private String action;
 public Intent(){} public Intent(Context c,Class<?> cls){} public Intent setAction(String s){action=s;return this;}
 public String getAction(){return action;} public String getStringExtra(String k){return extras.get(k);}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
}''',
    'android/content/BroadcastReceiver.java': '''package android.content; public abstract class BroadcastReceiver {
 public abstract void onReceive(Context c,Intent i);
}''',
    'android/net/Uri.java': '''package android.net; public class Uri {
 private final String value; private Uri(String v){value=v;} public static Uri parse(String s){return new Uri(s);}
 public static Uri fromFile(java.io.File f){return new Uri("file://"+f.getPath());} public String toString(){return value;}
}''',
    'android/view/View.java': '''package android.view; import android.content.Context; public class View {
 public static final int VISIBLE=0, INVISIBLE=4, GONE=8; private int visibility=VISIBLE; private boolean enabled=true;
 public View(Context c){} public void setVisibility(int n){visibility=n;} public int getVisibility(){return visibility;}
 public void setEnabled(boolean b){enabled=b;} public boolean isEnabled(){return enabled;}
 public void setContentDescription(CharSequence s){} public boolean requestFocus(){return true;}
}''',
    'android/widget/TextView.java': '''package android.widget; import android.view.View; import android.content.Context;
 public class TextView extends View { private CharSequence text=""; public TextView(Context c){super(c);}
 public void setText(CharSequence s){text=s;} public CharSequence getText(){return text;}
}''',
    'android/widget/Button.java': '''package android.widget; public class Button extends TextView {
 public Button(android.content.Context c){super(c);}
}''',
    'android/widget/ProgressBar.java': '''package android.widget; import android.view.View; import android.content.Context;
 public class ProgressBar extends View { private int max=100,progress; private boolean indeterminate;
 public ProgressBar(Context c){super(c);} public ProgressBar(Context c,android.util.AttributeSet a,int s){super(c);}
 public void setMax(int n){max=n;} public int getMax(){return max;} public void setProgress(int n){progress=n;}
 public int getProgress(){return progress;} public void setIndeterminate(boolean b){indeterminate=b;}
 public boolean isIndeterminate(){return indeterminate;}
}''',
    'android/widget/SeekBar.java': '''package android.widget; import android.content.Context;
 public class SeekBar extends ProgressBar {
 public interface OnSeekBarChangeListener {void onProgressChanged(SeekBar b,int p,boolean user);void onStartTrackingTouch(SeekBar b);void onStopTrackingTouch(SeekBar b);}
 public OnSeekBarChangeListener listener; public SeekBar(Context c){super(c);} public void setOnSeekBarChangeListener(OnSeekBarChangeListener l){listener=l;}
 public void beginDrag(){listener.onStartTrackingTouch(this);} public void dragTo(int p){setProgress(p);listener.onProgressChanged(this,p,true);}
 public void endDrag(){listener.onStopTrackingTouch(this);} public void keyTo(int p){setProgress(p);listener.onProgressChanged(this,p,true);}
}''',
    'android/widget/Toast.java': '''package android.widget; import android.content.Context; public class Toast {
 public static final int LENGTH_SHORT=0,LENGTH_LONG=1; public static Toast makeText(Context c,CharSequence s,int n){return new Toast();}
 public void show(){}
}''',
    'android/media/AudioManager.java': '''package android.media; public class AudioManager {
 public static final int STREAM_MUSIC=3,AUDIOFOCUS_GAIN=1,AUDIOFOCUS_LOSS=-1,AUDIOFOCUS_LOSS_TRANSIENT=-2,
 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK=-3,AUDIOFOCUS_REQUEST_GRANTED=1;
 public static final String ACTION_AUDIO_BECOMING_NOISY="android.media.AUDIO_BECOMING_NOISY";
 public interface OnAudioFocusChangeListener {void onAudioFocusChange(int change);} public OnAudioFocusChangeListener listener;
 public int requestAudioFocus(OnAudioFocusChangeListener l,int s,int d){listener=l;return 1;}
 public int abandonAudioFocus(OnAudioFocusChangeListener l){if(listener==l)listener=null;return 1;}
 public int getStreamVolume(int s){return 5;}
}''',
    'android/media/MediaPlayer.java': '''package android.media; public class MediaPlayer {
 public static final int MEDIA_INFO_BUFFERING_START=701,MEDIA_INFO_BUFFERING_END=702;
 public interface OnPreparedListener {void onPrepared(MediaPlayer p);}
 public interface OnCompletionListener {void onCompletion(MediaPlayer p);}
 public interface OnErrorListener {boolean onError(MediaPlayer p,int what,int extra);}
 public interface OnInfoListener {boolean onInfo(MediaPlayer p,int what,int extra);}
 public OnInfoListener infoListener;public void setOnInfoListener(OnInfoListener l){infoListener=l;}
 public void setVolume(float l,float r){}
}''',
    'android/widget/VideoView.java': '''package android.widget; import android.content.Context;import android.media.MediaPlayer;
 import android.net.Uri; import android.view.View;
 public class VideoView extends View {
 public MediaPlayer.OnPreparedListener onPrepared;public MediaPlayer.OnErrorListener onError;
 public MediaPlayer.OnCompletionListener onCompletion;public MediaPlayer.OnInfoListener onInfo;
 public MediaPlayer player; public boolean prepared,playing;public int rawPosition,duration,queriesBeforePrepared,seekTarget=-1;
 public VideoView(Context c){super(c);} public void setOnPreparedListener(MediaPlayer.OnPreparedListener l){onPrepared=l;}
 public void setOnErrorListener(MediaPlayer.OnErrorListener l){onError=l;}
 public void setOnCompletionListener(MediaPlayer.OnCompletionListener l){onCompletion=l;}
 public void setOnInfoListener(MediaPlayer.OnInfoListener l){if(android.os.Build.VERSION.SDK_INT<17)throw new AssertionError("API 17 VideoView method on older Android");onInfo=l;}
 public void setVideoURI(Uri uri){prepared=false;playing=false;player=new MediaPlayer();}
 public void recreateSurfacePlayer(){prepared=false;playing=false;player=new MediaPlayer();}
 public void firePrepared(){prepared=true;onPrepared.onPrepared(player);} public void fireInfo(int what){
  MediaPlayer.OnInfoListener l=onInfo!=null?onInfo:player.infoListener;if(l!=null)l.onInfo(player,what,0);
 }
 public void fireError(){onError.onError(player,1,-1004);} public void fireComplete(){playing=false;onCompletion.onCompletion(player);}
 public void start(){if(!prepared)throw new AssertionError("start before prepare");playing=true;}
 public void pause(){playing=false;}public void stopPlayback(){playing=false;prepared=false;}
 public boolean isPlaying(){return prepared&&playing;} public int getCurrentPosition(){if(!prepared)queriesBeforePrepared++;return rawPosition;}
 public int getDuration(){if(!prepared)queriesBeforePrepared++;return duration;}
 public void seekTo(int n){if(!prepared)throw new AssertionError("seek before prepare");seekTarget=n;}
}''',
    'ca/arc7freedom/app/Arc7Activity.java': '''package ca.arc7freedom.app; public class Arc7Activity extends android.app.Activity {}''',
    'ca/arc7freedom/app/Arc7AudioService.java': '''package ca.arc7freedom.app; public class Arc7AudioService {}''',
    'ca/arc7freedom/app/LegacyPlaybackAudio.java': '''package ca.arc7freedom.app; import android.media.MediaPlayer;
 public class LegacyPlaybackAudio {public static final int PRESENT=1;public static void enable(MediaPlayer p)throws java.io.IOException{}
 public static int trackState(MediaPlayer p){return PRESENT;}}
''',
    'ca/arc7freedom/app/MorpheStreamServer.java': '''package ca.arc7freedom.app; public class MorpheStreamServer {
 public static String stage="";public static String getMimeType(String s){return "video/mp4";} public static String getFailure(String s){return "";}
 public static String getPreparationStatus(String s){return stage;} public static void cancel(String s){}
}''',
    'ca/arc7freedom/app/MorpheHlsRelay.java': '''package ca.arc7freedom.app; public class MorpheHlsRelay {public static void cancel(String s){}}''',
    'ca/arc7freedom/support/DebugLog.java': '''package ca.arc7freedom.support; public class DebugLog {
 public static void event(String s){} public static void number(String s,long n){} public static void error(String s,Throwable t){}
}''',
    'ca/arc7freedom/app/MorpheEngine.java': '''package ca.arc7freedom.app; import java.util.*;import android.content.Context;
 public class MorpheEngine {
 public static boolean sponsorEnabled,resolveFailure,qualityFailure; public static ResolvedPlayback response;
 public interface ResolutionProgressListener {void onProgress(String m);}
 public static class MediaItem {public String videoId="",title="",author="",localFilePath="",thumbnailUrl="",channelId="",durationText="";public long durationSec;}
 public static class StreamVariant {public String label="";}
 public static class SkipSegment {public float startSec,endSec; public String category="sponsor";}
 public static class ResolvedPlayback {
  public String videoId="",title="",author="",channelId="",description="",viewCount="",thumbnailUrl="",spoofClientUsed="",localVideoProxyUrl="",localAudioProxyUrl="",hlsManifestUrl="",muxNote="";
  public long durationSec,likesCount=-1,dislikesCount=-1;public boolean muxedLocally;
  public final List<StreamVariant> progressiveVideos=new ArrayList<StreamVariant>(),videoOnlyStreams=new ArrayList<StreamVariant>(),audioStreams=new ArrayList<StreamVariant>();
  public final List<MediaItem> relatedItems=new ArrayList<MediaItem>();public final List<SkipSegment> skipSegments=new ArrayList<SkipSegment>();
 }
 public static boolean isSponsorBlockEnabled(Context c){return sponsorEnabled;} public static void cancelPlayback(ResolvedPlayback p){}
 public static ResolvedPlayback resolveStreams(Context c,String id,boolean b,ResolutionProgressListener l)throws Exception {
  l.onProgress("Resolving video...");if(resolveFailure)throw new Exception("fixture failure");return response;
 }
 public static void addOrToggleSavedItem(Context c,String k,MediaItem i,boolean b){} public static void enrichPlaybackMetadata(Context c,ResolvedPlayback p){}
 public static String startMuxForVariant(Context c,ResolvedPlayback p,StreamVariant v){return qualityFailure?"":"http://fixture/quality";}
 public static String startAudioForVariant(Context c,StreamVariant v){return qualityFailure?"":"http://fixture/audio";}
 public static String startVideoWithAudioFallback(Context c,ResolvedPlayback p){return "";}
}''',
    'ca/arc7freedom/app/NowPlayingManager.java': '''package ca.arc7freedom.app; import android.content.Context;import android.graphics.Bitmap;
 public class NowPlayingManager {
 public static boolean active,playing; public static long position,duration;
 public static String formatDurationMs(long ms){long s=Math.max(0,ms)/1000;return s>=3600?String.format(java.util.Locale.US,"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(java.util.Locale.US,"%d:%02d",s/60,s%60);}
 public static boolean isYouTubeForegroundVideoActive(){return active;}public static void setYouTubeForegroundVideoActive(boolean b){active=b;}
 public static void clearSession(Context c){active=false;} public static void publishBuiltIn(Context c,String a,String b,String d,String e,String f,String g,Bitmap h,boolean play,long pos,long dur){playing=play;position=pos;duration=dur;}
}''',
}


def toolchain():
    tools = Path(os.environ.get('TOOLCHAIN_DIR') or ('/opt/toolchain' if Path('/opt/toolchain/jdk/bin/java').is_file() else ROOT / 'build/toolchain'))
    java = os.environ.get('JAVA') or (str(tools / 'jdk/bin/java') if (tools / 'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(tools / 'jdk/bin/javac') if (tools / 'jdk/bin/javac').is_file() else shutil.which('javac'))
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or tools / 'sdk')
    bundled_jar = tools / 'sdk/platforms/android-17/android.jar'
    android_jar = Path(os.environ.get('ANDROID_JAR') or (bundled_jar if bundled_jar.is_file() else sdk / 'platforms/android-17/android.jar'))
    aapt = os.environ.get('AAPT2') or (str(tools / 'bin/aapt2') if (tools / 'bin/aapt2').is_file() else None)
    if not aapt and (sdk / 'build-tools').is_dir():
        candidates = sorted((sdk / 'build-tools').glob('*/aapt2'))
        if candidates:
            aapt = str(candidates[-1])
    aapt = aapt or shutil.which('aapt2')
    if not java or not javac or not android_jar.is_file() or not aapt:
        raise SystemExit('Install the Android 17 toolchain (scripts/bootstrap-toolchain.sh) before running video-state tests')
    return java, javac, android_jar, aapt


def main():
    video_source = (SRC / 'YouTubeActivity.java').read_text()
    assert 'new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)' in video_source
    assert 'playerPrepareProgress.setIndeterminate(true)' in video_source
    assert 'playerStage.addView(playerPrepareProgress' in video_source
    java, javac, android_jar, aapt = toolchain()
    with tempfile.TemporaryDirectory(prefix='arc7-video-state-') as directory:
        tmp = Path(directory)
        classes = tmp / 'classes'; classes.mkdir()
        fake_classes = tmp / 'fakes'; fake_classes.mkdir()
        generated = tmp / 'gen'; generated.mkdir()
        res = tmp / 'res.zip'
        subprocess.run([aapt, 'compile', '--dir', str(ROOT / 'app/src/main/res'), '-o', str(res)], check=True)
        subprocess.run([aapt, 'link', '-I', str(android_jar), '--manifest', str(ROOT / 'split-apps/youtube/AndroidManifest.xml'),
                        '--custom-package', 'ca.arc7freedom.app', '--java', str(generated), '--min-sdk-version', '15',
                        '--target-sdk-version', '17', '--no-compile-sdk-metadata', '-o', str(tmp / 'unsigned.apk'), str(res)], check=True)
        sources = [generated / 'ca/arc7freedom/app/R.java']
        sources += sorted(SRC.glob('*.java'))
        sources += sorted((ROOT / 'app/src/main/java/com/google/zxing').rglob('*.java'))
        sources += sorted((ROOT / 'shared/java/ca/arc7freedom/glass').glob('*.java'))
        sources += sorted((ROOT / 'shared/java/ca/arc7freedom/support').glob('*.java'))
        subprocess.run([javac, '-encoding', 'UTF-8', '-source', '1.8', '-target', '1.8', '-nowarn',
                        '-bootclasspath', str(android_jar), '-d', str(classes), *map(str, sources)], check=True, timeout=120)
        fakes = []
        for name, text in STUBS.items():
            path = tmp / 'src' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            fakes.append(path)
        fakes += [ROOT / 'tests/java/VideoPositionTrackerHarness.java', ROOT / 'tests/java/YouTubeVideoStateHarness.java']
        cp = os.pathsep.join([str(classes), str(android_jar)])
        subprocess.run([javac, '-encoding', 'UTF-8', '-source', '1.8', '-target', '1.8', '-nowarn',
                        '-cp', cp, '-d', str(fake_classes), *map(str, fakes)], check=True, timeout=90)
        runtime_cp = os.pathsep.join([str(fake_classes), str(classes), str(android_jar)])
        for harness in ('VideoPositionTrackerHarness', 'YouTubeVideoStateHarness'):
            subprocess.run([java, '-ea', '-cp', runtime_cp, 'ca.arc7freedom.app.' + harness], check=True, timeout=60)
    print('ALL VIDEO STATE REGRESSIONS PASSED (recording Android/player/provider fakes; not device or live-provider validation)')


if __name__ == '__main__':
    main()
