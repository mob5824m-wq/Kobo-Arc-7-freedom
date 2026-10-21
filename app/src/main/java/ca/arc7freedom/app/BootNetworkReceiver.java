package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.util.Calendar;

/**
 * Self-heals Kobo Arc 7 network connectivity automatically after reboots or dead-battery
 * clock resets (e.g., when a depleted battery resets the RTC back to Jan 1, 2013):
 *   1. Re-enforces Android setup provisioning flags so Home & Recents stay unlocked.
 *   2. As soon as Wi-Fi connects, syncs atomic 2026 UTC time via raw-IP NTP (`162.159.200.1`
 *      / `216.239.35.0`) + plain HTTP `Date:` header fallback if year < 2026.
 *   3. Keeps the local `127.0.0.1:8118` modern TLS 1.3 gateway warm in the background.
 */
public class BootNetworkReceiver extends BroadcastReceiver {

    private static volatile long lastCheckMs = 0L;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null) return;
        long now = System.currentTimeMillis();
        if (Math.abs(now - lastCheckMs) < 15000L) return;
        lastCheckMs = now;

        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RootShell shell = new RootShell(context);
                    boolean rooted = shell.isRootAvailable();

                    // Ensure Home & Recents provisioning stays intact across reboots
                    if (rooted) {
                        shell.execRoot(
                                "settings put global device_provisioned 1 >/dev/null 2>&1 || true; "
                                + "settings put secure user_setup_complete 1 >/dev/null 2>&1 || true",
                                null
                        );
                    }

                    // Re-apply MicroSD heavy-folder bind mounts automatically on boot if user enabled Storage Expander
                    if (rooted && context.getSharedPreferences(TabletOptimizerManager.PREFS, Context.MODE_PRIVATE)
                            .getBoolean(TabletOptimizerManager.KEY_BIND_SD_BOOT, false)) {
                        TabletOptimizerManager.applyMicroSdBindMounts(context, shell, null, false);
                    }

                    // Restore Warm Night / Amber Reading Filter overlay across reboots if active
                    if (NightFilterService.currentMode(context) != NightFilterService.MODE_OFF) {
                        NightFilterService.applyMode(context, NightFilterService.currentMode(context));
                    }

                    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo active = cm != null ? cm.getActiveNetworkInfo() : null;
                    if (active != null && active.isConnected()) {
                        NetworkCertManager certMgr = new NetworkCertManager(context, shell);
                        certMgr.ensureCaBundleFile();
                        if (Calendar.getInstance().get(Calendar.YEAR) < 2026 && rooted) {
                            String tlsPath = shell.getTlsEngineBinary().getAbsolutePath();
                            RootShell.ExecResult r = shell.execRoot("'" + tlsPath + "' ntp --set", null);
                            if (r.exitCode == 0 && r.stdout.contains("ANDROID_DATE=")) {
                                for (String ln : r.stdout.split("\n")) {
                                    if (ln.trim().startsWith("ANDROID_DATE=")) {
                                        String d = ln.trim().substring("ANDROID_DATE=".length()).trim();
                                        shell.execRoot("date -u -s " + d + " >/dev/null 2>&1 || date -s " + d + " >/dev/null 2>&1 || true", null);
                                    }
                                }
                            }
                        }
                        certMgr.startBrowserGateway();
                    }
                } catch (Throwable ignored) {
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }
}
