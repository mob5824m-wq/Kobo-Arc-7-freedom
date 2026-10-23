package ca.arc7freedom.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Keeps exactly ONE launcher icon per built-in app.
 *
 * Since `v2.5.2` every default app (Browser, Clock, Calculator, …) also ships as its own
 * installable APK (`releases/apps/<Name>.apk`, package `ca.arc7freedom.<key>`,
 * see {@link SplitApps}).  The suite keeps an `<activity-alias>` for each of them so nothing
 * disappears from the launcher when only the suite is installed — but as soon as the real
 * standalone app is present the alias is disabled, otherwise the tablet would show two
 * identical icons (and two `Open with` entries for `http://` links).
 *
 * Runs on every suite start and on boot; cheap (one `getPackageInfo` per app, all in-memory
 * after the first `PackageManager` query on Android 4.2).
 */
final class SplitAppIconSync {

    private static final String SUITE_PACKAGE = "ca.arc7freedom.app";

    private SplitAppIconSync() {}

    /** Re-evaluates every split-app alias. Safe to call from any thread. */
    static void sync(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            PackageManager pm = app.getPackageManager();
            if (pm == null) return;
            for (String[] row : SplitApps.ALL) {
                String alias = row[0];
                String standalonePackage = row[1];
                boolean installed = isInstalled(pm, standalonePackage);
                setAliasEnabled(pm, alias, !installed);
            }
        } catch (Throwable ignored) {
            // Never let icon bookkeeping break app startup.
        }
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable notInstalled) {
            return false;
        }
    }

    private static void setAliasEnabled(PackageManager pm, String alias, boolean enabled) {
        try {
            ComponentName component = new ComponentName(SUITE_PACKAGE, SUITE_PACKAGE + alias);
            int wanted = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            int current = pm.getComponentEnabledSetting(component);
            if (current != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP);
            }
        } catch (Throwable ignored) {
        }
    }
}
