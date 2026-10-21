package ca.arc7freedom.launcher;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * Ensures Android 4.2 Jelly Bean marks the tablet as provisioned (`device_provisioned = 1`
 * and `user_setup_complete = 1`) on every boot — including right after a Factory Data Reset
 * once VanillaAndroid4Home.apk lives in /system/app/ after KoboSetupWizard is removed.
 * Without these flags, Android 4.2 silently disables the Home & Recents navigation buttons!
 */
public class BootProvisionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ensureProvisioned(context);
    }

    @SuppressWarnings("deprecation")
    public static void ensureProvisioned(final Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            int prov = (Build.VERSION.SDK_INT >= 17)
                    ? Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0)
                    : Settings.Secure.getInt(cr, Settings.Secure.DEVICE_PROVISIONED, 0);
            int setup = Settings.Secure.getInt(cr, "user_setup_complete", 0);
            if (prov == 1 && setup == 1) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 17) {
                Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1);
            } else {
                Settings.Secure.putInt(cr, Settings.Secure.DEVICE_PROVISIONED, 1);
            }
            Settings.Secure.putInt(cr, "user_setup_complete", 1);
        } catch (Throwable ignored) {
            // If running as user app rather than /system/app, apply via root su asynchronously
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{
                                "su", "-c",
                                "settings put global device_provisioned 1; settings put secure user_setup_complete 1"
                        });
                        p.waitFor();
                    } catch (Throwable ignored2) {
                    }
                }
            }).start();
        }
    }
}
