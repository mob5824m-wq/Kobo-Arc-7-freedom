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
        final String action = intent.getAction() != null ? intent.getAction() : "";
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (context.getSharedPreferences("arc7_desk_clock", Context.MODE_PRIVATE)
                    .getBoolean("auto_dock_on_charge", false)) {
                Intent dock = new Intent(context, Arc7DeskClockActivity.class);
                dock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try { context.startActivity(dock); } catch (Throwable ignored) {}
            }
            return;
        }
        final boolean isPkgUpgrade = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        long now = System.currentTimeMillis();
        if (!isPkgUpgrade && Math.abs(now - lastCheckMs) < 15000L) return;
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
                                "settings put global device_provisioned 1 </dev/null >/dev/null 2>&1 || true; "
                                + "settings put secure user_setup_complete 1 </dev/null >/dev/null 2>&1 || true",
                                null
                        );
                    }

                    // Re-apply MicroSD heavy-folder bind mounts automatically on boot if user enabled Storage Expander
                    if (rooted && context.getSharedPreferences(TabletOptimizerManager.PREFS, Context.MODE_PRIVATE)
                            .getBoolean(TabletOptimizerManager.KEY_BIND_SD_BOOT, false)) {
                        TabletOptimizerManager.applyMicroSdBindMounts(context, shell, null, false);
                    }

                    // Re-arm all scheduled Arc7 Clock Alarms & running Countdown Timers after reboot!
                    Arc7ClockStore.rescheduleAllOnBoot(context);

                    // Restore Warm Night / Amber Reading Filter overlay across reboots if active
                    if (NightFilterService.currentMode(context) != NightFilterService.MODE_OFF) {
                        NightFilterService.applyMode(context, NightFilterService.currentMode(context));
                    }

                    // Restore Wi-Fi File Drive (:8080) HTTP server automatically if previously enabled
                    if (context.getSharedPreferences(Arc7WifiDriveService.PREFS, Context.MODE_PRIVATE)
                            .getBoolean(Arc7WifiDriveService.KEY_ACTIVE, false)
                            && !Arc7WifiDriveService.isRunning()) {
                        context.startService(new Intent(context, Arc7WifiDriveService.class));
                    }

                    // Keep both Local TLS 1.3 Gateway (:8118) AND Chromebook Wired USB Tunnel (:18880) warm at boot!
                    Arc7TlsGatewayService.ensureStarted(context);
                    Arc7ChromebookUsbService.ensureStarted(context);
                    Arc7CalendarStore.scheduleReminders(context, Arc7CalendarStore.loadSavedEvents(context));
                    NetworkCertManager certMgr = new NetworkCertManager(context, shell);
                    certMgr.ensureCaBundleFile();
                    certMgr.startBrowserGateway();

                    // Always ensure Aurora Store, VLC Media Player, Launcher v50, Python 3, `apt`, & Default Launcher / Kobo Care removal stay applied!
                    TabletOptimizerManager opt = new TabletOptimizerManager(context, shell);
                    if (rooted) {
                        opt.ensureCompanionAppsUpgradedSilently();
                    } else {
                        opt.provisionPython3AndAptSystemWide(null, false);
                    }

                    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo active = cm != null ? cm.getActiveNetworkInfo() : null;
                    if (active != null && active.isConnected()) {
                        // Automatically sync local IANA timezone, atomic time, AND Internet Archive alarm chimes!
                        certMgr.syncTimeAndTimezoneAutomatically(rooted, null);
                        Arc7ClockStore.autoSyncInternetArchiveChimes(context, null);
                    }
                } catch (Throwable ignored) {
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }
}
