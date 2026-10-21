package ca.arc7freedom.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;

/**
 * Stores pinned desktop shortcuts across 3 workspace pages (`0`, `1` center default, `2`)
 * and resolves default stock Android / Google apps cleanly on first launch.
 */
public class PinnedShortcutsStore {
    private static final String PREFS = "vanilla_jb_workspace_v1";
    private static final String KEY_INIT = "initialized";
    public static final int PAGE_COUNT = 3;
    public static final int DEFAULT_PAGE = 1;

    public static class AppEntry {
        public final String label;
        public final String packageName;
        public final String className;
        public final boolean isSystemApp;

        public AppEntry(String label, String packageName, String className, boolean isSystemApp) {
            this.label = label;
            this.packageName = packageName;
            this.className = className;
            this.isSystemApp = isSystemApp;
        }

        public String flatten() {
            return packageName + "/" + className;
        }
    }

    public static List<AppEntry> loadAllLaunchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> raw = pm.queryIntentActivities(main, 0);
        List<AppEntry> out = new ArrayList<AppEntry>();
        String myPkg = ctx.getPackageName();

        if (raw != null) {
            for (ResolveInfo ri : raw) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String cls = ri.activityInfo.name;
                if (myPkg.equals(pkg)) continue;
                CharSequence cs = ri.loadLabel(pm);
                String lbl = cs != null ? cs.toString().trim() : pkg;
                boolean sys = (ri.activityInfo.applicationInfo.flags
                        & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                out.add(new AppEntry(lbl, pkg, cls, sys));
            }
        }

        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    public static void ensureInitialized(Context ctx, List<AppEntry> allApps) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_INIT, false)) return;

        String[][] preferred = new String[][]{
                {"com.android.vending"},
                {"com.android.browser", "com.android.chrome", "org.mozilla.firefox"},
                {"com.android.gallery3d", "com.google.android.gallery3d"},
                {"com.android.camera", "com.mediatek.camera", "com.google.android.camera"},
                {"com.android.providers.downloads.ui"},
                {"com.android.calculator2"},
                {"com.android.deskclock", "com.google.android.deskclock"},
                {"com.android.settings"},
                {"ca.arc7freedom.app"}
        };

        JSONArray center = new JSONArray();
        for (String[] group : preferred) {
            AppEntry match = findFirstByPackages(allApps, group);
            if (match != null) {
                center.put(match.flatten());
            }
        }
        // Fill up to 6 items if fewer matched
        for (AppEntry e : allApps) {
            if (center.length() >= 8) break;
            if (!contains(center, e.flatten()) && !e.packageName.startsWith("com.kobo.")) {
                center.put(e.flatten());
            }
        }

        sp.edit()
                .putString("page_0", new JSONArray().toString())
                .putString("page_1", center.toString())
                .putString("page_2", new JSONArray().toString())
                .putBoolean(KEY_INIT, true)
                .apply();
    }

    private static AppEntry findFirstByPackages(List<AppEntry> apps, String[] candidates) {
        for (String pkg : candidates) {
            for (AppEntry e : apps) {
                if (pkg.equals(e.packageName)) return e;
            }
        }
        return null;
    }

    private static boolean contains(JSONArray arr, String val) {
        for (int i = 0; i < arr.length(); i++) {
            if (val.equals(arr.optString(i))) return true;
        }
        return false;
    }

    public static List<ComponentName> getPageShortcuts(Context ctx, int page) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<ComponentName> list = new ArrayList<ComponentName>();
        try {
            JSONArray arr = new JSONArray(sp.getString("page_" + page, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                ComponentName cn = ComponentName.unflattenFromString(arr.optString(i));
                if (cn != null) list.add(cn);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    public static boolean addShortcut(Context ctx, int page, String flattened) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("page_" + page, "[]"));
            if (!contains(arr, flattened)) {
                arr.put(flattened);
                sp.edit().putString("page_" + page, arr.toString()).apply();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void removeShortcut(Context ctx, int page, String flattened) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("page_" + page, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i);
                if (!flattened.equals(v)) next.put(v);
            }
            sp.edit().putString("page_" + page, next.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
