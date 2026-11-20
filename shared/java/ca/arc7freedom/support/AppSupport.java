package ca.arc7freedom.support;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;

/** Installed independently in each owned APK. No cross-UID log reader or shared settings file. */
public final class AppSupport {
    public static final String PREFS = "arc7_support_v1";
    public static final int MENU_TOOLS = 0xA701;
    private static boolean installed;
    private AppSupport() {}
    public static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static void configure(Context context) {
        SharedPreferences prefs = preferences(context);
        RuntimePolicy.setLowRam(prefs.getBoolean("low_ram", false));
        long defaultMb = RuntimePolicy.lowRam() ? 64L : 256L;
        try { MediaCache.initialize(context.getCacheDir(), prefs.getLong("media_cache_mb", defaultMb) * 1024L * 1024L); }
        catch (Exception unavailable) { DebugLog.error("cache.initialize_failed", unavailable); }
    }
    public static synchronized void install(final Application app) {
        if (installed) return;
        installed = true;
        DebugLog.initialize(app.getFilesDir(), app.getPackageName(), preferences(app).getBoolean("logging", true));
        DebugLog.installCrashHandler();
        try { DebugLog.number("app.version_code", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode); }
        catch (Exception unavailable) { DebugLog.error("app.version_unavailable", unavailable); }
        DebugLog.number("android.sdk", android.os.Build.VERSION.SDK_INT);
        configure(app);
        app.registerComponentCallbacks(new android.content.ComponentCallbacks2() {
            public void onConfigurationChanged(android.content.res.Configuration configuration) {}
            public void onLowMemory() { RuntimePolicy.trimMemory(); DebugLog.event("memory.low"); }
            public void onTrimMemory(int level) { if(level>=10)RuntimePolicy.trimMemory(); DebugLog.number("memory.trim_level",level); }
        });
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) { DebugLog.lifecycle("activity.create", a.getClass().getName()); }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { DebugLog.lifecycle("activity.resume", a.getClass().getName()); }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) { DebugLog.lifecycle("activity.stop", a.getClass().getName()); }
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) {}
        });
    }
    public static void open(Activity activity) { activity.startActivity(new Intent(activity, SupportActivity.class)); }
    public static boolean addMenu(Menu menu) { menu.add(Menu.NONE, MENU_TOOLS, Menu.NONE, "App tools · logs & storage"); return true; }
}
