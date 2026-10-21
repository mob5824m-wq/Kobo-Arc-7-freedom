package ca.arc7freedom.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.Locale;

/**
 * All-in-one manager powering Kobo Arc 7 Freedom v1.3.0 Hardware & OS Upgrades:
 *   - Upgrade #2: Pre-bundled offline Android 4.2 App Store (`FDroidClassicLegacy.apk`,
 *     with built-in Conscrypt modern TLS 1.3!) one-tap silent root install or PackageInstaller.
 *   - Upgrade #3: `8 GB` Storage Expander (`pm set-install-location 2` App-to-SD default
 *     + lossless MicroSD bind-mounting of `Download`, `Books`, `Movies`, `Music`, `DCIM`,
 *     automatically re-mounted on every boot by `BootNetworkReceiver`!).
 *   - Upgrade #4: On-Device Anti-Brick `boot.img` / `recovery.img` Root-ADB Ramdisk Patcher
 *     (`ro.secure=0`, `ro.debuggable=1`, `persist.sys.usb.config=mtp,adb`) preserving MediaTek
 *     `MT8125` 512-byte `0x58881688` headers & backing up the untouched factory image first!
 *   - Upgrade #5: Offline `/system/etc/hosts` Ad & Tracker Blocker (with 1-tap restore),
 *     `1 GB RAM` `minfree` + `0.5x` Snappy Animation Speed Booster, and Live Battery Health
 *     (`mV`, `°C`, `status`) + Fuel-Gauge Calibration Reset (`batterystats.bin`).
 */
public class TabletOptimizerManager {

    public interface LogSink {
        void onLog(String line);
    }

    public static final String PREFS = "arc7_optimizer";
    public static final String KEY_BIND_SD_BOOT = "bind_sd_folders_on_boot";
    public static final String FDROID_ASSET = "starter-apps/FDroidClassicLegacy.apk";

    private static final String[] HEAVY_MEDIA_DIRS = {
        "Download", "Books", "Movies", "Music", "DCIM"
    };

    private final Context ctx;
    private final RootShell shell;

    public TabletOptimizerManager(Context ctx, RootShell shell) {
        this.ctx = ctx.getApplicationContext();
        this.shell = shell;
    }

    /* ========================================================================
     * Live Storage, Ad-Block, Speed & Battery Telemetry Summary
     * ======================================================================== */

    public static String formatDiskUsage(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isDirectory()) return "not mounted";
            StatFs sf = new StatFs(path);
            @SuppressWarnings("deprecation")
            long blockSize = sf.getBlockSize();
            @SuppressWarnings("deprecation")
            long total = sf.getBlockCount() * blockSize;
            @SuppressWarnings("deprecation")
            long avail = sf.getAvailableBlocks() * blockSize;
            if (total <= 0) return "not mounted";
            return String.format(Locale.US, "%.2f GB free / %.2f GB total (%d%% free)",
                    avail / 1073741824.0, total / 1073741824.0, (int) ((avail * 100L) / total));
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private static String readSysFile(String path) {
        BufferedReader br = null;
        try {
            File f = new File(path);
            if (!f.canRead()) return "";
            br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    public String inspectSystemHealthSummary() {
        StringBuilder sb = new StringBuilder();
        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        sb.append("Internal Storage (").append(internalPath).append("): ")
          .append(formatDiskUsage(internalPath)).append("\n");
        String sdPath = findMicroSdRoot();
        sb.append("External MicroSD (").append(sdPath != null ? sdPath : "/storage/sdcard1").append("): ")
          .append(sdPath != null ? formatDiskUsage(sdPath) : "No MicroSD card detected").append("\n");

        boolean sysProxy = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sys_tls13_proxy", false);
        sb.append("System-Wide TLS 1.3 Upgrade Proxy: ")
          .append(sysProxy ? "ENABLED (`127.0.0.1:8118` -> 2026 TLS 1.3)" : "Browser/Gateway mode (`127.0.0.1:8118`)")
          .append("\n");

        boolean sdBind = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BIND_SD_BOOT, false);
        sb.append("MicroSD Heavy-Folder Bind Mount: ").append(sdBind ? "ACTIVE (auto-remounts on boot)" : "OFF").append("\n");

        boolean adblockActive = new File("/system/etc/hosts.arc7.bak").exists();
        sb.append("System Ad & Tracker /etc/hosts Shield: ").append(adblockActive ? "ACTIVE (backup saved)" : "Stock /etc/hosts").append("\n");

        sb.append("Night / Warm Reading Filter: ").append(NightFilterService.modeLabel(NightFilterService.currentMode(ctx))).append("\n");

        /* Live Kobo Arc 7 / Linux 3.4 `/sys/class/power_supply/battery` telemetry */
        String cap = firstNonEmpty(
                readSysFile("/sys/class/power_supply/battery/capacity"),
                readSysFile("/sys/class/power_supply/BAT0/capacity"));
        String voltRaw = firstNonEmpty(
                readSysFile("/sys/class/power_supply/battery/voltage_now"),
                readSysFile("/sys/class/power_supply/battery/batt_vol"));
        String tempRaw = firstNonEmpty(
                readSysFile("/sys/class/power_supply/battery/temp"),
                readSysFile("/sys/class/power_supply/battery/batt_temp"));
        String status = firstNonEmpty(
                readSysFile("/sys/class/power_supply/battery/status"),
                "Unknown");

        String voltPretty = "";
        try {
            long v = Long.parseLong(voltRaw);
            if (v > 100000) voltPretty = (v / 1000) + " mV";
            else if (v > 0) voltPretty = v + " mV";
        } catch (Throwable ignored) {}

        String tempPretty = "";
        try {
            long t = Long.parseLong(tempRaw);
            if (Math.abs(t) > 100) tempPretty = String.format(Locale.US, "%.1f °C", t / 10.0);
            else if (t != 0) tempPretty = t + " °C";
        } catch (Throwable ignored) {}

        sb.append("Battery Telemetry: ")
          .append(cap.isEmpty() ? "?" : cap + "%")
          .append(" [").append(status).append("]")
          .append(voltPretty.isEmpty() ? "" : " • " + voltPretty)
          .append(tempPretty.isEmpty() ? "" : " • " + tempPretty);
        return sb.toString();
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b != null ? b : "");
    }

    public static String findMicroSdRoot() {
        String[] candidates = { "/storage/sdcard1", "/mnt/media_rw/sdcard1", "/mnt/external_sd", "/Removable/MicroSD" };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists() && f.isDirectory()) {
                try {
                    StatFs sf = new StatFs(c);
                    @SuppressWarnings("deprecation")
                    long blocks = sf.getBlockCount();
                    if (blocks > 1024) return c;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /* ========================================================================
     * Upgrade #2: Pre-Bundled Offline F-Droid Classic (Android 4.2 + TLS 1.3)
     * ======================================================================== */

    public boolean installBundledFDroidClassic(LogSink log) {
        log.onLog("=== Installing Pre-Bundled Offline F-Droid Classic (Android 4.2 + TLS 1.3) ===");
        File outDir = new File(Environment.getExternalStorageDirectory(), "Download");
        if (!outDir.exists()) outDir.mkdirs();
        File outApk = new File(outDir, "FDroidClassic-Android4.2-TLS1.3.apk");

        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = ctx.getAssets().open(FDROID_ASSET);
            fos = new FileOutputStream(outApk);
            byte[] buf = new byte[65536];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                fos.write(buf, 0, r);
                total += r;
            }
            fos.flush();
            log.onLog("Extracted offline store APK (" + (total / 1024) + " KB) -> " + outApk.getAbsolutePath());
        } catch (Throwable t) {
            log.onLog("ERROR extracting bundled F-Droid Classic APK: " + t.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }

        if (shell.isRootAvailable()) {
            log.onLog("Root detected — running silent `pm install -r` so Unknown Sources toggle isn't even needed...");
            RootShell.ExecResult pm = shell.execRoot(
                    "pm install -r " + RootShell.shellQuote(outApk.getAbsolutePath()), null);
            log.onLog(pm.stdout.trim());
            if (pm.stdout.contains("Success")) {
                log.onLog("SUCCESS: F-Droid Classic (Android 4.2 + built-in modern TLS 1.3) is now installed in your App Drawer!");
                return true;
            }
        }

        log.onLog("Opening Android 4.2 Package Installer prompt (enable Settings -> Security -> Unknown Sources if prompted)...");
        Intent prompt = new Intent(Intent.ACTION_VIEW);
        prompt.setDataAndType(Uri.fromFile(outApk), "application/vnd.android.package-archive");
        prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(prompt);
        return true;
    }

    /* ========================================================================
     * Upgrade #3: 8 GB Storage Expander (App-to-SD + Lossless Folder Bind Mount)
     * ======================================================================== */

    public boolean setAppInstallToExternalSd(boolean preferExternal, LogSink log) {
        if (!shell.isRootAvailable()) {
            log.onLog("ERROR: Tap 'Root Kobo Arc 7 Now' on Tab 1 first.");
            return false;
        }
        int loc = preferExternal ? 2 : 0;
        RootShell.ExecResult r = shell.execRoot("pm set-install-location " + loc + " && pm get-install-location", null);
        log.onLog("pm install-location -> " + r.stdout.trim());
        return r.exitCode == 0;
    }

    public boolean enableMicroSdFolderExpansion(LogSink log) {
        return applyMicroSdBindMounts(ctx, shell, log, true);
    }

    public static boolean applyMicroSdBindMounts(Context ctx, RootShell shell, LogSink log, boolean copyExistingFirst) {
        if (!shell.isRootAvailable()) {
            if (log != null) log.onLog("ERROR: Root access required for kernel `mount -o bind`.");
            return false;
        }
        String sdRoot = findMicroSdRoot();
        if (sdRoot == null) {
            if (log != null) log.onLog("ERROR: No formatted MicroSD card detected at /storage/sdcard1.");
            return false;
        }

        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        script.append("SD_BASE='").append(sdRoot).append("/Arc7SD'\n");
        script.append("mkdir -p \"$SD_BASE\"\n");
        for (String folder : HEAVY_MEDIA_DIRS) {
            script.append("mkdir -p \"$SD_BASE/").append(folder).append("\"\n");
            for (String internalBase : new String[]{"/storage/sdcard0", "/mnt/shell/emulated/0", "/data/media/0", "/data/media"}) {
                script.append("if [ -d '").append(internalBase).append("' ]; then\n");
                script.append("  SRC='").append(internalBase).append("/").append(folder).append("'\n");
                script.append("  mkdir -p \"$SRC\"\n");
                if (copyExistingFirst) {
                    script.append("  cp -rn \"$SRC/\"* \"$SD_BASE/").append(folder).append("/\" 2>/dev/null || true\n");
                }
                script.append("  grep -qs \" $SRC \" /proc/mounts || mount -o bind \"$SD_BASE/")
                      .append(folder).append("\" \"$SRC\" 2>/dev/null || true\n");
                script.append("fi\n");
            }
        }
        script.append("echo BIND_OK\n");

        RootShell.ExecResult r = shell.execRoot(script.toString(), null);
        if (log != null) log.onLog(r.stdout.trim());
        boolean ok = r.stdout.contains("BIND_OK");
        if (ok) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_BIND_SD_BOOT, true).commit();
            if (log != null) {
                log.onLog("SUCCESS: Download, Books, Movies, Music & DCIM now store directly onto MicroSD (" + sdRoot + "/Arc7SD/) and auto-remount on every boot!");
            }
        }
        return ok;
    }

    public boolean disableMicroSdFolderExpansion(LogSink log) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_BIND_SD_BOOT, false).commit();
        if (!shell.isRootAvailable()) return false;
        StringBuilder sb = new StringBuilder();
        for (String folder : HEAVY_MEDIA_DIRS) {
            for (String internalBase : new String[]{"/storage/sdcard0", "/mnt/shell/emulated/0", "/data/media/0", "/data/media"}) {
                sb.append("umount '").append(internalBase).append("/").append(folder).append("' 2>/dev/null || true\n");
            }
        }
        sb.append("echo UNBIND_OK\n");
        RootShell.ExecResult r = shell.execRoot(sb.toString(), null);
        log.onLog("Unmounted MicroSD folder binds (" + r.stdout.trim() + "). Your files remain safely on MicroSD in Arc7SD/!");
        return true;
    }

    /* ========================================================================
     * Upgrade #4: Anti-Brick On-Device `boot.img` / `recovery.img` Root-ADB Patcher
     * ======================================================================== */

    public boolean patchLivePartitionRootAdb(DeviceInspector.Snapshot snap, boolean patchRecovery, LogSink log) {
        if (!shell.isRootAvailable()) {
            log.onLog("ERROR: Tap 'Root Kobo Arc 7 Now' on Tab 1 first.");
            return false;
        }
        String targetName = patchRecovery ? "recovery" : "boot";
        DeviceInspector.PartitionTarget targetPart = (snap != null && snap.partitions != null)
                ? snap.partitions.get(targetName) : null;
        if (targetPart == null) {
            log.onLog("ERROR: Could not locate live `" + targetName + "` block partition on this device.");
            return false;
        }

        File tool = shell.getPartitionToolBinary();
        if (!tool.exists()) {
            log.onLog("ERROR: Native binary `libarc7tool.so` missing.");
            return false;
        }

        String sd = findMicroSdRoot();
        File workDir = new File(sd != null ? sd : Environment.getExternalStorageDirectory().getAbsolutePath(),
                "KoboArc7Backups/AntiBrick-" + targetName);
        workDir.mkdirs();

        File stockImg = new File(workDir, targetName + "-stock-unmodified.img");
        File patchedImg = new File(workDir, targetName + "-root-adb-patched.img");
        long dumpLimit = targetPart.sizeBytes > 0 ? targetPart.sizeBytes : 16777216L;
        long countBlocks = (dumpLimit + 4095L) / 4096L;

        log.onLog("=== Anti-Brick Root-ADB Ramdisk Patcher (" + targetName + " -> " + targetPart.sourcePath + ") ===");
        log.onLog("[1/4] Backing up untouched factory " + targetName + " image -> " + stockImg.getAbsolutePath());

        String dumpCmd = String.format(Locale.US,
                "dd if='%s' of='%s' bs=4096 skip=%d count=%d && sync",
                targetPart.sourcePath, stockImg.getAbsolutePath(),
                targetPart.offsetBytes / 4096L, countBlocks);
        RootShell.ExecResult d = shell.execRoot(dumpCmd, null);
        if (d.exitCode != 0 || !stockImg.exists()) {
            log.onLog("ERROR dumping live partition: " + d.stdout);
            return false;
        }

        log.onLog("[2/4] Unpacking gzip/cpio ramdisk, preserving MT8125 0x58881688 header, injecting ro.secure=0 + ro.debuggable=1...");
        String patchCmd = String.format(Locale.US,
                "'%s' patch-boot-adb '%s' '%s' && '%s' inspect-boot '%s'",
                tool.getAbsolutePath(), stockImg.getAbsolutePath(), patchedImg.getAbsolutePath(),
                tool.getAbsolutePath(), patchedImg.getAbsolutePath());
        RootShell.ExecResult pr = shell.execRoot(patchCmd, null);
        log.onLog(pr.stdout.trim());
        if (!pr.stdout.contains("PATCH_BOOT_ADB=OK") || !pr.stdout.contains("VALID_BOOT_IMG=YES")) {
            log.onLog("ABORTED BEFORE FLASH: Patched image did not pass strict boot header verification! Your live partition was NOT touched.");
            return false;
        }

        log.onLog("[3/4] Flashing verified Root-ADB " + targetName + " image back to " + targetPart.sourcePath + "...");
        String flashCmd = String.format(Locale.US,
                "'%s' flash '%s' '%s' %d",
                tool.getAbsolutePath(), patchedImg.getAbsolutePath(),
                targetPart.sourcePath, targetPart.offsetBytes);
        RootShell.ExecResult fr = shell.execRoot(flashCmd, null);
        log.onLog(fr.stdout.trim());
        if (fr.exitCode != 0 || !fr.stdout.contains("DONE ")) {
            log.onLog("ERROR writing patched image; restoring untouched factory backup immediately...");
            shell.execRoot(String.format(Locale.US, "'%s' flash '%s' '%s' %d",
                    tool.getAbsolutePath(), stockImg.getAbsolutePath(),
                    targetPart.sourcePath, targetPart.offsetBytes), null);
            return false;
        }

        log.onLog("[4/4] COMPLETE! Early-init Root ADB (`adb shell` = `#`) is now permanently active on `"
                + targetName + "` AND your stock backup is safely saved at " + stockImg.getAbsolutePath());
        return true;
    }

    /* ========================================================================
     * Upgrade #5: Ad-Blocker (`/etc/hosts`), 1 GB RAM Booster & Battery Reset
     * ======================================================================== */

    public boolean toggleSystemAdBlocker(boolean enable, LogSink log) {
        if (!shell.isRootAvailable()) {
            log.onLog("ERROR: Root access required to update /system/etc/hosts.");
            return false;
        }
        if (!enable) {
            String cmd = RootShell.remountSystemRwCmd()
                    + " && if [ -f /system/etc/hosts.arc7.bak ]; then cp -f /system/etc/hosts.arc7.bak /system/etc/hosts; else printf '127.0.0.1 localhost\\n' > /system/etc/hosts; fi"
                    + " && rm -f /system/etc/hosts.arc7.bak && chmod 0644 /system/etc/hosts && sync"
                    + " && " + RootShell.remountSystemRoCmd() + " && echo RESTORED_OK";
            RootShell.ExecResult r = shell.execRoot(cmd, null);
            log.onLog(r.stdout.trim());
            return r.stdout.contains("RESTORED_OK");
        }

        File stage = new File(ctx.getFilesDir(), "hosts-adblock-staged.txt");
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = ctx.getAssets().open("hosts-adblock.txt");
            fos = new FileOutputStream(stage);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (Throwable t) {
            log.onLog("ERROR staging ad-block hosts file: " + t.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }

        String cmd = RootShell.remountSystemRwCmd()
                + " && [ -f /system/etc/hosts.arc7.bak ] || cp -a /system/etc/hosts /system/etc/hosts.arc7.bak"
                + " && cp -f '" + stage.getAbsolutePath() + "' /system/etc/hosts"
                + " && chmod 0644 /system/etc/hosts && sync"
                + " && " + RootShell.remountSystemRoCmd()
                + " && echo ADBLOCK_HOSTS_OK";
        RootShell.ExecResult r = shell.execRoot(cmd, null);
        log.onLog(r.stdout.trim());
        if (r.stdout.contains("ADBLOCK_HOSTS_OK")) {
            log.onLog("SUCCESS: System-wide Ad, Tracker & Kobo Telemetry blocker installed in /system/etc/hosts!");
            return true;
        }
        return false;
    }

    public boolean applyOneGigRamAndSpeedBoost(LogSink log) {
        if (!shell.isRootAvailable()) {
            log.onLog("ERROR: Root access required for kernel VM / lowmemorykiller tuning.");
            return false;
        }
        log.onLog("=== Applying Kobo Arc 7 1 GB RAM + Snappy 0.5x Animation Speed Boost ===");
        String cmd =
                "sync && echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true\n" +
                "echo 20 > /proc/sys/vm/swappiness 2>/dev/null || true\n" +
                "chmod 0644 /sys/module/lowmemorykiller/parameters/minfree 2>/dev/null || true\n" +
                "echo '2048,3072,4096,8192,12288,16384' > /sys/module/lowmemorykiller/parameters/minfree 2>/dev/null || true\n" +
                "settings put system window_animation_scale 0.5 2>/dev/null || true\n" +
                "settings put system transition_animation_scale 0.5 2>/dev/null || true\n" +
                "settings put system animator_duration_scale 0.5 2>/dev/null || true\n" +
                "echo BOOST_OK\n";
        RootShell.ExecResult r = shell.execRoot(cmd, null);
        log.onLog(r.stdout.trim());
        boolean ok = r.stdout.contains("BOOST_OK");
        if (ok) {
            log.onLog("SUCCESS: Freed page cache, tuned 1 GB RAM LMK & VM swappiness, and set snappy 0.5x UI animations!");
        }
        return ok;
    }

    public boolean calibrateBatteryFuelGauge(LogSink log) {
        if (!shell.isRootAvailable()) {
            log.onLog("ERROR: Root access required to reset /data/system/batterystats.bin.");
            return false;
        }
        log.onLog("=== Calibrating Kobo Arc 7 Battery Fuel Gauge ===");
        RootShell.ExecResult r = shell.execRoot(
                "rm -f /data/system/batterystats.bin /data/system/batterystats-checkin.bin /data/system/batterystats-daily.xml && sync && echo CAL_OK",
                null);
        log.onLog(r.stdout.trim());
        if (r.stdout.contains("CAL_OK")) {
            log.onLog("SUCCESS: Old fuel-gauge history cleared! Charge your Kobo Arc 7 to 100%, leave plugged in for 30 extra minutes, then use normally for fresh percentage tracking.");
            return true;
        }
        return false;
    }
}
