package ca.arc7freedom.support;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** Visible owner-UID tools. Launching this exported screen never exports or clears anything. */
public final class SupportActivity extends Activity {
    private TextView status, log;
    private volatile boolean alive = true;
    private boolean inspecting, restoringPreferences;
    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Holo);
        super.onCreate(state); SupportUi.window(this);
        setTitle("App tools · " + getPackageName());
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(16, 12, 16, 24);
        scroll.addView(layout); setContentView(scroll);
        TextView intro = new TextView(this);
        intro.setText("These settings and logs belong only to this installed app. Logs stay private until you explicitly copy or save them. No upload or logcat collection.\n\nOnly event codes, component names, numeric counters and exception classes/Java frames are recorded. Never passwords, TOTP seeds/codes, URLs/tokens, queries, commands, file paths or document/media contents.");
        SupportUi.panel(this,intro,false); layout.addView(intro);
        CheckBox enabled = new CheckBox(this); enabled.setText("Keep bounded local debug log (up to 384 KiB)");
        enabled.setChecked(AppSupport.preferences(this).getBoolean("logging", true)); enabled.setTextColor(0xffe6f5ff); layout.addView(enabled);
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean on) {
            if(restoringPreferences)return;
            if (!AppSupport.preferences(SupportActivity.this).edit().putBoolean("logging", on).commit()) { restoringPreferences=true;b.setChecked(!on);restoringPreferences=false; message("Could not save logging setting"); return; }
            DebugLog.setEnabled(on); refreshLog();
        }});
        button(layout, "Refresh debug log", new Runnable() { public void run() { refreshLog(); }});
        button(layout, "Write a test event", new Runnable() { public void run() { DebugLog.event("user.test_event"); refreshLog(); }});
        button(layout, "Copy sanitized log…", new Runnable() { public void run() {
            new AlertDialog.Builder(SupportActivity.this).setTitle("Copy to Android clipboard?")
                .setMessage("On Android 4, other apps may read the clipboard. Only a bounded structured log is copied; review it before sharing.")
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    try { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("App debug log", report())); message("Log copied"); }
                    catch (Exception e) { message("Could not copy log"); }
                }}).setNegativeButton("Cancel", null).show();
        }});
        button(layout, "Save sanitized log…", new Runnable() { public void run() {
            new AlertDialog.Builder(SupportActivity.this).setTitle("Save a public log copy?")
                .setMessage("Save up to 64 KiB in Download/Arc7 Debug. This copy becomes readable by other apps and is not removed by Clear private logs.")
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { export(); }}).setNegativeButton("Cancel", null).show();
        }});
        button(layout, "Clear private debug logs…", new Runnable() { public void run() {
            new AlertDialog.Builder(SupportActivity.this).setMessage("Delete only this app's private rotated debug logs? Public copies are unaffected.")
                .setPositiveButton("Clear logs", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    try { DebugLog.clear(); refreshLog(); } catch (Exception e) { message("Could not clear logs"); }
                }}).setNegativeButton("Cancel", null).show();
        }});
        log = new TextView(this); log.setTextSize(11); log.setTypeface(android.graphics.Typeface.MONOSPACE); log.setTextIsSelectable(true); SupportUi.panel(this,log,false); layout.addView(log);
        CheckBox lite = new CheckBox(this); lite.setText("Low-RAM profile for this app (transparent glass retained)"); lite.setChecked(RuntimePolicy.lowRam()); lite.setTextColor(0xffe6f5ff); layout.addView(lite);
        lite.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean on) {
            if(restoringPreferences)return;
            if (!AppSupport.preferences(SupportActivity.this).edit().putBoolean("low_ram", on).commit()) { restoringPreferences=true;b.setChecked(!on);restoringPreferences=false; message("Could not save profile"); return; }
            AppSupport.configure(SupportActivity.this); message("Profile saved. Reopen the main screen for all artwork/layout changes."); refreshStorage();
        }});
        TextView profile = new TextView(this); profile.setText("Low-RAM: 128 px artwork, 8 thumbnail entries, 1 bounded artwork worker, 1 media preparation job, 96 px Home wallpaper sample. Normal: 256 px / 32 / 3 / 3 / 160 px. Active playback is not killed when changing profiles. Lower media-cache limits take full effect after current playback/download preparation ends."); SupportUi.panel(this,profile,false); layout.addView(profile);
        status = new TextView(this); SupportUi.panel(this,status,false); layout.addView(status);
        button(layout, "Set temporary media cache limit…", new Runnable() { public void run() {
            final long[] limits = {32, 64, 128, 256, 512, 1024};
            new AlertDialog.Builder(SupportActivity.this).setTitle("Temporary media limit · MiB")
                .setItems(new String[]{"32 MiB", "64 MiB", "128 MiB", "256 MiB", "512 MiB", "1024 MiB"}, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int which) {
                    if (AppSupport.preferences(SupportActivity.this).edit().putLong("media_cache_mb", limits[which]).commit()) { MediaCache.setLimit(limits[which]*1024L*1024L); refreshStorage(); }
                    else message("Could not save cache limit");
                }}).setNegativeButton("Cancel", null).show();
        }});
        button(layout, "Refresh storage usage", new Runnable() { public void run() { refreshStorage(); }});
        button(layout, "Clear temporary media cache…", new Runnable() { public void run() {
            new AlertDialog.Builder(SupportActivity.this).setTitle("Clear only temporary media?")
                .setMessage("Only this app's managed media cache is removed. Saved downloads, music libraries, recordings, vaults, optical/LAN resume data and other app data are never deleted. Clearing is blocked during active preparation/playback/downloads.")
                .setPositiveButton("Clear temporary cache", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() { public void run() { try { final long freed=MediaCache.trim(0); uiMessage("Freed " + mib(freed)); } catch (Exception e) { uiMessage("Cache not cleared: " + e.getMessage()); } refreshStorage(); }}, "Arc7-Cache-Clear").start();
                }}).setNegativeButton("Cancel", null).show();
        }});
        final ComponentName diagnostics = new ComponentName(getPackageName(), "ca.arc7freedom.app.Arc7PlaybackDiagnosticsActivity");
        try { getPackageManager().getActivityInfo(diagnostics, 0);
            button(layout, "Playback diagnostics / offline sound tests", new Runnable() { public void run() { startActivity(new Intent().setComponent(diagnostics)); }});
        } catch (Exception notMediaApp) { /* do not launch another APK's service or request new permissions */ }
        refreshLog(); refreshStorage();
    }
    private void button(LinearLayout parent, String title, final Runnable action) { Button b=new Button(this); b.setText(title); SupportUi.panel(this,b,true); parent.addView(b); b.setOnClickListener(new android.view.View.OnClickListener(){ public void onClick(android.view.View v){action.run();}}); }
    private String report() throws Exception {
        String header = "Package: " + getPackageName() + "\nStructured private log; no provider URL or user content.\n";
        String body = DebugLog.snapshot();
        int max=DebugLog.EXPORT_BYTES-header.length();
        if(body.length()>max){body=body.substring(body.length()-max);int newline=body.indexOf('\n');if(newline>=0)body=body.substring(newline+1);}
        return header+body;
    }
    private void refreshLog() { if (log != null) try { log.setText(report()); } catch (Exception e) { log.setText("Log unavailable"); } }
    private static String mib(long n) { return String.format(Locale.US, "%.1f MiB", n/(1024.0*1024.0)); }
    private synchronized void refreshStorage() {
        if(inspecting || !alive)return; inspecting=true;
        new Thread(new Runnable() { public void run() {
            String text;
            try { text="Managed temporary media: " + mib(MediaCache.size()) + " / " + mib(MediaCache.limit()) + (MediaCache.busy()?" · active (cleanup blocked; current jobs may use "+mib(MediaCache.activeLimit())+")":" · idle") + "\nOther cache (read-only): " + otherCacheUsage() + "\n" + savedMediaUsage() + "\nSaved downloads/recordings/vaults/resume data are never managed or cleared here. Other app caches are excluded from this limit and cleanup."; }
            catch (Exception e) { text="Temporary media usage unavailable; nothing was removed."; }
            final String result=text; runOnUiThread(new Runnable(){public void run(){synchronized(SupportActivity.this){inspecting=false;}if(alive)status.setText(result);}});
        }}, "Arc7-Cache-Inspect").start();
    }
    private String otherCacheUsage() throws java.io.IOException {
        StorageUsage.Result total=StorageUsage.measure(getCacheDir().getCanonicalFile());
        return mib(Math.max(0,total.bytes-MediaCache.size()))+(total.partial?" (partial/unavailable scan)":"");
    }
    private String savedMediaUsage() throws java.io.IOException {
        String pkg=getPackageName(); java.util.ArrayList<String> paths=new java.util.ArrayList<String>();
        boolean suite="ca.arc7freedom.app".equals(pkg);
        if(suite||pkg.endsWith(".ytmusic"))paths.add("Music/YouTubeMusic");
        if(suite||pkg.endsWith(".youtube"))paths.add("Movies/YouTube");
        if(suite||pkg.endsWith(".podcasts")||pkg.endsWith(".radiorss"))paths.add("Podcasts");
        if(suite||pkg.endsWith(".recorder"))paths.add("Music/Recordings");
        if(pkg.endsWith(".audioplayer"))paths.add("Music");
        if("com.aurora.store".equals(pkg))paths.add("Download/AuroraStore");
        if(paths.isEmpty())return "No app-managed public media folder to count for this app. Private app data and arbitrary SD-card folders are not scanned.";
        StringBuilder text=new StringBuilder("Saved media (read-only; shared folders may also be used by other apps):");
        for(String path:paths){StorageUsage.Result result=StorageUsage.measure(new File(Environment.getExternalStorageDirectory().getCanonicalFile(),path));
            text.append("\n").append(path).append(": ").append(mib(result.bytes)).append(" · ").append(result.files).append(" files").append(result.partial?" (partial/unavailable scan)":"");}
        return text.toString();
    }
    private void export() {
        File file=null; boolean created=false;
        try {
            File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getCanonicalFile(),"Arc7 Debug");
            if(!dir.getCanonicalFile().equals(dir.getAbsoluteFile()))throw new Exception();
            if (!dir.isDirectory()&&!dir.mkdirs()) throw new Exception();
            file=new File(dir,getPackageName()+"-"+System.currentTimeMillis()+".txt");
            if(!file.createNewFile())throw new Exception(); created=true;
            FileOutputStream out=new FileOutputStream(file);try{out.write(report().getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
            message("Saved " + file.getAbsolutePath());
        } catch(Exception unavailable){if(created&&file!=null)file.delete();message("Could not save public log; use View/Copy or check storage permission/space.");}
    }
    private void message(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void uiMessage(final String text){runOnUiThread(new Runnable(){public void run(){if(alive)message(text);}});}
    @Override public void onDestroy(){alive=false;super.onDestroy();}
}
