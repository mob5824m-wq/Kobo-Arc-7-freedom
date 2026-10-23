package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Keeps the suite's fallback launcher aliases in sync as standalone apps are installed or
 * removed. Without this package-change receiver, the same app can temporarily show twice
 * until the user opens Arc7 Freedom or reboots (for example, two Camera icons after install).
 */
public final class SplitAppPackageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action)
                && !Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.PACKAGE_FULLY_REMOVED".equals(action)) {
            return;
        }

        Uri data = intent.getData();
        String changedPackage = data != null ? data.getSchemeSpecificPart() : null;
        if (changedPackage == null || changedPackage.length() == 0) return;
        for (String[] row : SplitApps.ALL) {
            if (changedPackage.equals(row[1])) {
                // PACKAGE_REMOVED is also sent for the remove phase of an APK update. The
                // following PACKAGE_ADDED/REPLACED broadcast will do the authoritative sync.
                if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                        && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    return;
                }
                SplitAppIconSync.sync(context);
                return;
            }
        }
    }
}
