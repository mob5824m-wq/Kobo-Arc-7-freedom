package ca.arc7freedom.app;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Shared post-install pipeline for APK/SD/OTA, ADB, backup, upgrader, conversion and boot.
 * No firmware flashing, data wipes, app uninstalls or compulsory proxy/VPN opt-ins here.
 * Failed steps remain visible/retryable; no background PackageInstaller dialogs are launched.
 */
public class InstallMaintenanceService extends IntentService {
    public static final String PREFS = "arc7_install_maintenance";
    private static boolean requested;
    private static boolean rerunAfterRoot;
    private static volatile String liveReport = "";

    public InstallMaintenanceService() { super("Arc7InstallMaintenance"); setIntentRedelivery(true); }

    public static synchronized void request(Context context, String reason) {
        if (!"ca.arc7freedom.app".equals(context.getPackageName())) return;
        if (requested) {
            if ("root granted".equals(reason) || "conversion completed".equals(reason)) rerunAfterRoot = true;
            return;
        }
        requested = true;
        try { context.startService(new Intent(context, InstallMaintenanceService.class).putExtra("reason", reason)); }
        catch (RuntimeException error) {
            requested = false;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("summary", "Patch service could not start. Open Install / Repair All Patches to retry: " + error.getMessage()).commit();
        }
    }

    public static String report(Context context) {
        String current = liveReport;
        if (current.length() > 0) return current;
        try {
            InputStream in = new FileInputStream(new File(context.getFilesDir(), "install-maintenance.log"));
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1 && out.size() < 512 * 1024) out.write(b, 0, n);
                return new String(out.toByteArray(), "UTF-8");
            } finally { in.close(); }
        } catch (Exception missing) { return "No completed patch report yet. Tap Apply / Retry All Patches."; }
    }

    @Override protected void onHandleIntent(Intent intent) {
        if (!"ca.arc7freedom.app".equals(getPackageName())) return;
        final StringBuilder lines = new StringBuilder();
        final File logFile = new File(getFilesDir(), "install-maintenance.log");
        final TabletOptimizerManager.LogSink log = new TabletOptimizerManager.LogSink() {
            @Override public synchronized void onLog(String line) {
                lines.append(line).append('\n'); liveReport = lines.toString();
                try {
                    FileOutputStream out = new FileOutputStream(logFile);
                    try { out.write(liveReport.getBytes("UTF-8")); } finally { out.close(); }
                } catch (Exception ignored) { /* SharedPreferences summary is an independent fallback. */ }
            }
        };
        int pending = 0, updated = 0, current = 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("summary", "Applying patches — open report for progress").commit();
        log.onLog("Arc7 install maintenance — " + new java.util.Date() + " — "
                + (intent == null ? "retry" : intent.getStringExtra("reason")));
        try {
            RootShell shell = new RootShell(this);
            final boolean rooted = shell.isRootAvailable();
            final NetworkCertManager certs = new NetworkCertManager(this, shell);
            final TabletOptimizerManager optimizer = new TabletOptimizerManager(this, shell);
            log.onLog(rooted ? "Root verified. Applying in-place updates; no app data will be erased."
                    : "No root: APK patches are bundled offline. Android confirmations and system/root steps remain PENDING.");

            // Local repairs must never wait for an internet request or another package's update.
            try { certs.ensureCaBundleFile(); log.onLog("CURRENT local CA bundle / owning-app TLS assets"); }
            catch (Exception error) { pending++; log.onLog("BLOCKED local CA bundle: " + error.getMessage()); }
            try {
                certs.migrateLegacySuiteProxy();
                boolean blocked = getSharedPreferences(TabletOptimizerManager.PREFS, MODE_PRIVATE)
                        .getBoolean("legacy_proxy_cleanup_pending", true);
                if (blocked) pending++;
                log.onLog((blocked ? "PENDING " : "CURRENT ") + getSharedPreferences(TabletOptimizerManager.PREFS, MODE_PRIVATE)
                        .getString("legacy_proxy_cleanup_message", "Legacy Arc7 proxy cleanup requires review in Network settings."));
            } catch (Exception error) { pending++; log.onLog("BLOCKED old Arc7 proxy cleanup: " + error.getMessage()); }

            BundledAppInstaller installer = new BundledAppInstaller(this, shell);
            List<BundledApk.Entry> entries = installer.catalog();
            for (BundledApk.Entry entry : entries) {
                BundledAppInstaller.State result = installer.apply(entry, rooted, log);
                if ("UPDATED".equals(result.code)) updated++;
                else if (result.done()) current++;
                else pending++;
                log.onLog(result.code + " " + entry.label + " (" + entry.pkg + "): " + result.detail);
            }
            SplitAppIconSync.sync(this);

            if (rooted) {
                try {
                    if (!certs.installBundledSystemCasOffline(log)) pending++;
                } catch (Exception error) { pending++; log.onLog("BLOCKED system CA patch: " + error.getMessage()); }
                try {
                    if (!optimizer.provisionPython3AndAptSystemWide(log, false)) pending++;
                } catch (Exception error) { pending++; log.onLog("BLOCKED Python / apt / TLS tools: " + error.getMessage()); }
                // Settings writes are read back: unsupported ROMs must not be labelled patched.
                RootShell.ExecResult setup = shell.execRoot(
                        "settings put global device_provisioned 1 >/dev/null 2>&1; "
                        + "settings put secure device_provisioned 1 >/dev/null 2>&1; "
                        + "settings put secure user_setup_complete 1 >/dev/null 2>&1; "
                        + "_g=$(settings get global device_provisioned 2>/dev/null); "
                        + "_s=$(settings get secure device_provisioned 2>/dev/null); "
                        + "_u=$(settings get secure user_setup_complete 2>/dev/null); "
                        + "case \"$_g:$_s:$_u\" in 1:*:1|*:1:1) echo ARC7_SETUP_VERIFIED ;; *) echo SETUP_PENDING; exit 1 ;; esac", null);
                if (setup.exitCode == 0 && setup.stdout.contains("ARC7_SETUP_VERIFIED")) log.onLog("CURRENT Home / Recents setup provisioning verified");
                else { pending++; log.onLog("PENDING Home / Recents setup provisioning: ROM permission or settings command unavailable"); }
                shell.execRoot("appwidget grantbind --package ca.arc7freedom.launcher --user 0 2>/dev/null"
                        + " || appwidget grantbind --package ca.arc7freedom.launcher 2>/dev/null || true", null);
            } else {
                pending += 2;
                optimizer.provisionPython3AndAptSystemWide(log, false);
                log.onLog("PENDING root/system CA + CLI-tool + Home provisioning patches. Root explicitly, then retry; embedded per-app TLS remains usable.");
            }

            // Re-establish enabled features, not new opt-ins. No universal proxy or tethering.
            if (rooted && getSharedPreferences(TabletOptimizerManager.PREFS, MODE_PRIVATE)
                    .getBoolean(TabletOptimizerManager.KEY_BIND_SD_BOOT, false)) {
                TabletOptimizerManager.applyMicroSdBindMounts(this, shell, log, false);
            }
            Arc7ClockStore.rescheduleAllOnBoot(this);
            Arc7CalendarStore.scheduleReminders(this, Arc7CalendarStore.loadSavedEvents(this));

            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
            if (active != null && active.isConnected()) {
                log.onLog("Online follow-up: attempting time/timezone + missing chimes (network-dependent; APK patches above are already checked).");
                certs.startBrowserGateway();
                certs.syncTimeAndTimezoneAutomatically(rooted, new NetworkCertManager.ProgressListener() {
                    @Override public void onProgress(int pct, String message) {}
                    @Override public void onLog(String message) { log.onLog(message); }
                });
                int chimes = Arc7ClockStore.autoSyncInternetArchiveChimes(this, new Arc7ClockStore.ChimeSyncListener() {
                    @Override public void onProgress(int done, int total, String message) { log.onLog(message); }
                });
                log.onLog("Online chime follow-up: " + chimes + " available; unavailable downloads retry later, not counted as installed APK patches.");
            } else {
                log.onLog("DEFERRED online time/timezone and optional chime downloads: offline. Reconnect and retry; no download is required for bundled APK patches.");
            }
            log.onLog("Optional conversion, stock/Kobo app removal, ad blocking, boot/recovery flashing, storage changes, VPN and tethering were NOT enabled by maintenance.");
        } catch (Exception error) {
            pending++; log.onLog("BLOCKED maintenance step: " + error.toString() + ". Retry is safe; unfinished steps are not marked complete.");
        } finally {
            String summary = updated + " APKs updated; " + current + " current/newer; " + pending + " pending/blocked step(s).";
            log.onLog("SUMMARY: " + summary + " Review DEFERRED online work separately.");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("summary", summary)
                    .putInt("pending", pending).putLong("last_run", System.currentTimeMillis()).commit();
            synchronized (InstallMaintenanceService.class) {
                requested = false;
                if (rerunAfterRoot) { rerunAfterRoot = false; request(this, "root/conversion follow-up"); }
            }
        }
    }
}
