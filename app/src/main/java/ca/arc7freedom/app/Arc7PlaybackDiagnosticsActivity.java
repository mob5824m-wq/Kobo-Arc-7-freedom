package ca.arc7freedom.app;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import ca.arc7freedom.support.DebugLog;
import ca.arc7freedom.support.MediaCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Real on-device test controls, not an automatic claim that speaker/headphones emitted sound. */
public final class Arc7PlaybackDiagnosticsActivity extends Arc7Activity {
    private static final String[] FILES={"pcm.wav","mp3.mp3","aac-lc.m4a"};
    private static final String[] HASHES={"8e531512564e86f0bce7d218aee9f44787a560876afeefbfbbd49605da2333a8","eb352b82bc808a3d9494a0afce4bd93cdeaabffd2205292a4fb784abcf2d5eec","cbfbbb2535accea5ba15c8a60748ba747e376f712e942aaee6c53e26869201c3"};
    private Arc7AudioService service;
    private boolean bound;
    private volatile boolean alive=true;
    private volatile int request;
    private int lastClip=-1;
    private TextView report;
    private final Handler ui=new Handler();
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){service=((Arc7AudioService.LocalBinder)b).getService();refresh();}
        public void onServiceDisconnected(ComponentName n){service=null;}
    };
    private final Runnable tick=new Runnable(){public void run(){if(!alive)return;refresh();ui.postDelayed(this,750L);}};
    @Override public void onCreate(Bundle state){
        setTheme(android.R.style.Theme_Holo);super.onCreate(state);ca.arc7freedom.support.SupportUi.window(this);setTitle("Playback diagnostics");setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        ScrollView scroll=new ScrollView(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(16,12,16,24);scroll.addView(layout);setContentView(scroll);
        TextView intro=new TextView(this);intro.setText("This screen observes only this app's background audio player. YouTube foreground video has its own player. No URL, title, token, filename or search text is included.\n\nOffline clips are 1.5-second, quiet generated 440 Hz tones. Start at low media volume. They need no internet, root or microphone. Playing a clip stops current playback, cancels this app's sleep timer and bypasses app EQ. Prepared/Playing does not prove sound reached the speaker: confirm what you hear.");ca.arc7freedom.support.SupportUi.panel(this,intro,false);layout.addView(intro);
        report=new TextView(this);report.setTextIsSelectable(true);ca.arc7freedom.support.SupportUi.panel(this,report,false);layout.addView(report);
        String[] labels={"Test PCM / WAV","Test MP3","Test AAC-LC / M4A"};
        for(int i=0;i<labels.length;i++){final int codec=i;button(layout,labels[i],new Runnable(){public void run(){
            new AlertDialog.Builder(Arc7PlaybackDiagnosticsActivity.this).setTitle("Play offline sound test?")
                .setMessage("Stop current playback and play a quiet test tone? Set a low media volume first. No volume is forced and nothing resumes automatically afterward.")
                .setPositiveButton("Play test",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){startClip(codec);}}).setNegativeButton("Cancel",null).show();
        }});}
        button(layout,"I heard the last clip",new Runnable(){public void run(){confirm(true);}});
        button(layout,"I did not hear it",new Runnable(){public void run(){confirm(false);}});
        button(layout,"Stop playback / cancel pending test",new Runnable(){public void run(){++request;if(service!=null)service.stopAndDismissNotification();refresh();}});
        button(layout,"Record current playback counters in debug log",new Runnable(){public void run(){if(service!=null){service.recordDiagnosticSnapshot();Toast.makeText(Arc7PlaybackDiagnosticsActivity.this,"Playback counters recorded without URLs, titles or paths.",Toast.LENGTH_LONG).show();}}});
        button(layout,"View / copy / save this app's debug log",new Runnable(){public void run(){ca.arc7freedom.support.AppSupport.open(Arc7PlaybackDiagnosticsActivity.this);}});
        bound=bindService(new Intent(this,Arc7AudioService.class),connection,Context.BIND_AUTO_CREATE);
        ui.post(tick);
    }
    private void button(LinearLayout parent,String text,final Runnable run){Button b=new Button(this);b.setText(text);ca.arc7freedom.support.SupportUi.panel(this,b,true);parent.addView(b);b.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){run.run();}});}
    private void refresh(){if(report!=null)report.setText(service==null?"Connecting to this app's audio service…":service.getDiagnosticReport());}
    private void confirm(boolean heard){if(lastClip<0){Toast.makeText(this,"Play a test clip first",Toast.LENGTH_LONG).show();return;}DebugLog.number(heard?"audio.self_test_user_heard":"audio.self_test_user_not_heard",lastClip);Toast.makeText(this,heard?"Your audible-output confirmation was logged.":"Logged. Check media volume/output route; compare WAV, MP3 and AAC results. No decoder/provider success is assumed.",Toast.LENGTH_LONG).show();}
    private void startClip(final int codec){
        if(service==null)return;
        service.stopAndDismissNotification();
        final int generation=++request;
        final Context app=getApplicationContext();
        new Thread(new Runnable(){public void run(){
            File temporary=null;
            MediaCache.Lease copying=null;
            try{
                File directory=MediaCache.directory();if(directory==null)throw new Exception("Temporary media cache unavailable");
                MediaCache.reserve(100000L);
                copying=MediaCache.lease();
                temporary=File.createTempFile("sound-test-",".part",directory);
                InputStream in=app.getAssets().open("audio-self-test/"+FILES[codec]);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");FileOutputStream out=new FileOutputStream(temporary);long size=0;
                try{byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){size+=n;if(size>100000L)throw new Exception("Invalid test asset size");digest.update(bytes,0,n);out.write(bytes,0,n);}out.getFD().sync();}finally{try{out.close();}finally{in.close();}}
                StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.US,"%02x",b&255));
                if(!HASHES[codec].equals(hex.toString()))throw new Exception("Offline test asset checksum mismatch");
                final File clip=new File(directory,"sound-test-"+FILES[codec]);
                synchronized(Arc7PlaybackDiagnosticsActivity.this){if(!alive||generation!=request){temporary.delete();return;}if(clip.exists()&&!clip.delete())throw new Exception("Previous test clip is in use");if(!temporary.renameTo(clip))throw new Exception("Could not prepare test clip");}
                final MediaCache.Lease hold=copying;
                if(ui.post(new Runnable(){public void run(){try{if(alive&&generation==request&&service!=null){lastClip=codec;service.playDiagnosticClip(clip,codec);refresh();}}finally{hold.close();}}})) copying=null;
            }catch(final Exception error){if(temporary!=null)temporary.delete();DebugLog.error("audio.self_test_asset_failed",error);ui.post(new Runnable(){public void run(){if(alive&&generation==request)Toast.makeText(Arc7PlaybackDiagnosticsActivity.this,"Could not prepare the verified offline test clip. See this app's log; no provider was contacted.",Toast.LENGTH_LONG).show();}});}
            finally{if(copying!=null)copying.close();}
        }},"Arc7-Offline-Sound-Test").start();
    }
    @Override public void onDestroy(){alive=false;++request;ui.removeCallbacks(tick);if(bound)try{unbindService(connection);}catch(Exception ignored){}service=null;super.onDestroy();}
}
