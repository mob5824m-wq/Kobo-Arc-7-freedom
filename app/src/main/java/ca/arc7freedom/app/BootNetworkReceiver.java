package ca.arc7freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;

/**
 * Self-heals Kobo Arc 7 network connectivity automatically after reboots or dead-battery
 * clock resets (e.g., when a depleted battery resets the RTC back to Jan 1, 2013):
 *   1. Re-enforces Android setup provisioning flags so Home & Recents stay unlocked.
 *   2. As soon as Wi-Fi connects, syncs atomic 2026 UTC time via raw-IP NTP (`162.159.200.1`
 *      / `216.239.35.0`) + plain HTTP `Date:` header fallback if year < 2026.
 *   3. Keeps the local `127.0.0.1:8118` modern TLS 1.3 gateway warm in the background.
 */
public class BootNetworkReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_CLOCK_AUTO_DOCK = "ca.arc7freedom.action.SET_CLOCK_AUTO_DOCK";
    private static volatile long lastCheckMs = 0L;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null) return;
        final String action = intent.getAction() != null ? intent.getAction() : "";
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            SplitAppIconSync.sync(context);
        }
        if (action.startsWith("com.android.music.") || action.startsWith("org.videolan.vlc.")) {
            Arc7AudioService.ensureWatcherRunning(context);
            NowPlayingManager.onExternalMediaBroadcast(context, intent);
            return;
        }
        if (ACTION_SET_CLOCK_AUTO_DOCK.equals(action)) {
            context.getSharedPreferences("arc7_desk_clock", Context.MODE_PRIVATE).edit()
                    .putBoolean("auto_dock_on_charge", intent.getBooleanExtra("enabled", false)).commit();
            return;
        }
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (context.getSharedPreferences("arc7_desk_clock", Context.MODE_PRIVATE)
                    .getBoolean("auto_dock_on_charge", false)) {
                Intent dock = new Intent(context, Arc7DeskClockActivity.class);
                dock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try { context.startActivity(SplitAppRouter.route(context, dock, -1)); } catch (Throwable ignored) {}
            }
            return;
        }
        final boolean isPkgUpgrade = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!isPkgUpgrade && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !ConnectivityManager.CONNECTIVITY_ACTION.equals(action) && !Intent.ACTION_TIME_CHANGED.equals(action)) return;
        long now = System.currentTimeMillis();
        if (!isPkgUpgrade && Math.abs(now - lastCheckMs) < 15000L) return;
        lastCheckMs = now;

        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Heavy patches run in the same restartable service as every installer,
                    // not inside a broadcast's short execution window.
                    InstallMaintenanceService.request(context, action);
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

                    // Keep Local TLS 1.3 Gateway (:8118), Audio Notification Watcher & Chromebook USB Tunnel (:18880) warm at boot!
                    Arc7TlsGatewayService.ensureStarted(context);
                    Arc7AudioService.ensureWatcherRunning(context);
                    Arc7ChromebookUsbService.ensureStarted(context);
                    Arc7CalendarStore.scheduleReminders(context, Arc7CalendarStore.loadSavedEvents(context));
                } catch (Throwable ignored) {
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }
}
