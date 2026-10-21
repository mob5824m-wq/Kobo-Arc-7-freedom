package ca.arc7freedom.app;

import android.content.Context;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Full OS Partition Backup & Restore manager for Kobo Arc 7 (MT8125) and Arc 7HD (Tegra).
 * Streams raw partitions through native ARMv7 `libarc7tool.so` (zlib gzip + single-pass SHA-256),
 * generates MediaTek SP Flash Tool scatter files (`MT8125_Android_scatter.txt`), and verifies
 * cryptographic SHA-256 hashes both on write and prior to any restore operation.
 */
public class BackupManager {

    public interface ProgressCallback {
        void onProgress(int percent, String status);
        void onLog(String line);
    }

    public static class Options {
        public boolean includeBootAndRecovery = true;
        public boolean includeNvramAndScatter = true;
        public boolean includeSystemPartition = true;
        public boolean includeUserAppsData = false;
    }

    public static class SavedBackup {
        public final File directory;
        public final String name;
        public final String createdAt;
        public final String deviceModel;
        public final String variant;
        public final long totalCompressedBytes;
        public final List<String> imageFiles;

        public SavedBackup(File directory, String name, String createdAt, String deviceModel,
                           String variant, long totalCompressedBytes, List<String> imageFiles) {
            this.directory = directory;
            this.name = name;
            this.createdAt = createdAt;
            this.deviceModel = deviceModel;
            this.variant = variant;
            this.totalCompressedBytes = totalCompressedBytes;
            this.imageFiles = imageFiles;
        }
    }

    private final Context appContext;
    private final RootShell shell;

    public BackupManager(Context ctx, RootShell shell) {
        this.appContext = ctx.getApplicationContext();
        this.shell = shell;
    }

    public File createBackup(DeviceInspector.Snapshot snap, File baseVolumeRoot, Options opts,
                             final ProgressCallback cb) throws Exception {
        if (!shell.isRootAvailable()) {
            throw new IllegalStateException("Root access is required before backing up raw OS partitions. Run Step 1 (Root) first.");
        }

        long estimatedNeeded = estimateRequiredBytes(opts);
        long freeBytes = getUsableSpaceSafe(baseVolumeRoot);
        if (freeBytes > 0 && freeBytes < estimatedNeeded) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Insufficient free space on %s (%d MB free, ~%d MB recommended). Insert a microSD card or uncheck large partitions.",
                    baseVolumeRoot.getAbsolutePath(), freeBytes / 1048576L, estimatedNeeded / 1048576L));
        }

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arc7Freedom:BackupWakeLock");
        wl.setReferenceCounted(false);
        wl.acquire(45 * 60 * 1000L);

        try {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String code = (snap.deviceCodename == null || snap.deviceCodename.isEmpty()) ? "arc7" : snap.deviceCodename;
            File outDir = new File(baseVolumeRoot, "Arc7Freedom/backups/backup-" + ts + "-" + code);
            if (!outDir.mkdirs() && !outDir.exists()) {
                throw new IllegalStateException("Cannot create backup folder at " + outDir.getAbsolutePath());
            }

            cb.onLog("=== Creating OS Backup in " + outDir.getAbsolutePath() + " ===");
            cb.onProgress(2, "Saving partition table metadata & SP Flash Tool scatter...");

            // 1. Save human + tool readable partition layout metadata & build.prop
            StringBuilder meta = new StringBuilder();
            meta.append("=== KOBO ARC 7 FREEDOM PARTITION METADATA ===\n");
            meta.append("Model: ").append(snap.model).append("\n");
            meta.append("Variant: ").append(snap.variantTitle).append("\n");
            meta.append("Build: ").append(snap.buildDisplay).append("\n");
            meta.append("Kernel: ").append(snap.kernelLine).append("\n\n");
            meta.append("--- /proc/dumchar_info ---\n").append(snap.rawDumcharText).append("\n");
            meta.append("--- /proc/emmc ---\n").append(snap.rawEmmcText).append("\n");
            meta.append("--- /proc/partitions ---\n").append(snap.rawPartitionsText).append("\n");
            meta.append("--- /proc/mounts ---\n").append(snap.rawMountsText).append("\n");
            writeTextFile(new File(outDir, "partition_layout.txt"), meta.toString());

            String scatter = DeviceInspector.buildScatterFile(snap.rawDumcharText);
            if (!scatter.isEmpty()) {
                writeTextFile(new File(outDir, "MT8125_Android_scatter.txt"), scatter);
                cb.onLog("Generated MediaTek unbrick scatter file: MT8125_Android_scatter.txt");
            }
            RootShell.copyFile(new File("/system/build.prop"), new File(outDir, "build.prop"));

            // Collect partition dump plan
            List<PartitionJob> jobs = new ArrayList<PartitionJob>();
            if (opts.includeNvramAndScatter) {
                if (new File("/dev/block/mmcblk0").exists()) {
                    // First 16 MB captures MBR + EBR1 + Preloader header + NVRAM region on MT8125
                    jobs.add(new PartitionJob("mbr_header",
                            new DeviceInspector.PartitionTarget("mbr_header", "/dev/block/mmcblk0", 0L, 16L * 1048576L),
                            "mbr_gpt_header.img.gz"));
                }
                addIfPresent(snap, jobs, "nvram", "nvram.img.gz");
                addIfPresent(snap, jobs, "protect_f", "protect_f.img.gz");
                addIfPresent(snap, jobs, "protect_s", "protect_s.img.gz");
                addIfPresent(snap, jobs, "sec_ro", "sec_ro.img.gz");
            }
            if (opts.includeBootAndRecovery) {
                addIfPresent(snap, jobs, "uboot", "uboot.img.gz");
                addIfPresent(snap, jobs, "logo", "logo.img.gz");
                addIfPresent(snap, jobs, "boot", "boot.img.gz");
                addIfPresent(snap, jobs, "recovery", "recovery.img.gz");
            }
            if (opts.includeSystemPartition) {
                addIfPresent(snap, jobs, "system", "system.ext4.img.gz");
            }

            if (jobs.isEmpty() && !opts.includeUserAppsData) {
                throw new IllegalStateException("No matching block partitions discovered on this device.");
            }

            Map<String, String> shaSums = new LinkedHashMap<String, String>();
            JSONArray manifestItems = new JSONArray();
            File toolBin = shell.getPartitionToolBinary();

            int totalSteps = jobs.size() + (opts.includeUserAppsData ? 1 : 0);
            int stepIndex = 0;

            for (final PartitionJob job : jobs) {
                final int basePct = 5 + (stepIndex * 88 / Math.max(1, totalSteps));
                final int nextPct = 5 + ((stepIndex + 1) * 88 / Math.max(1, totalSteps));
                File outFile = new File(outDir, job.fileName);

                cb.onLog("Dumping [" + job.role + "] from " + job.target.describe() + " -> " + job.fileName);
                cb.onProgress(basePct, "Backing up " + job.role + "...");

                String cmd = String.format(Locale.US, "%s dump-gz %s %s %d %d",
                        RootShell.shellQuote(toolBin.getAbsolutePath()),
                        RootShell.shellQuote(job.target.sourcePath),
                        RootShell.shellQuote(outFile.getAbsolutePath()),
                        job.target.offsetBytes,
                        job.target.sizeBytes);

                final String[] doneTokens = new String[1];
                RootShell.ExecResult res = shell.execRoot(cmd, new RootShell.LineListener() {
                    @Override
                    public void onLine(String line) {
                        if (line.startsWith("PROGRESS ")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 4) {
                                long rawDone = parseLongSafe(parts[1]);
                                long rawTotal = parseLongSafe(parts[2]);
                                long gzDone = parseLongSafe(parts[3]);
                                int subPct = rawTotal > 0
                                        ? (int) Math.min(100L, (rawDone * 100L) / rawTotal) : 50;
                                int overall = basePct + ((nextPct - basePct) * subPct / 100);
                                cb.onProgress(overall, String.format(Locale.US,
                                        "Backing up %s: %d MB read (%d MB compressed)",
                                        job.role, rawDone / 1048576L, gzDone / 1048576L));
                            }
                        } else if (line.startsWith("DONE ")) {
                            doneTokens[0] = line;
                        } else {
                            cb.onLog(line);
                        }
                    }
                });

                if (res.exitCode != 0 || doneTokens[0] == null || !outFile.exists()) {
                    throw new IllegalStateException("Failed while dumping partition '" + job.role + "': " + res.stdout);
                }

                // DONE <raw_bytes> <gz_bytes> <gz_sha256> <raw_sha256>
                String[] dt = doneTokens[0].split("\\s+");
                long rawBytes = dt.length > 1 ? parseLongSafe(dt[1]) : 0L;
                long gzBytes = dt.length > 2 ? parseLongSafe(dt[2]) : outFile.length();
                String gzSha = dt.length > 3 ? dt[3] : "";
                String rawSha = dt.length > 4 ? dt[4] : "";

                shaSums.put(job.fileName, gzSha);
                JSONObject item = new JSONObject();
                item.put("role", job.role);
                item.put("file", job.fileName);
                item.put("sourcePath", job.target.sourcePath);
                item.put("offsetBytes", job.target.offsetBytes);
                item.put("rawBytes", rawBytes);
                item.put("compressedBytes", gzBytes);
                item.put("gzSha256", gzSha);
                item.put("rawSha256", rawSha);
                manifestItems.put(item);

                cb.onLog(String.format(Locale.US, "  OK: %s (%d MB -> %d MB, sha256=%s...)",
                        job.fileName, rawBytes / 1048576L, gzBytes / 1048576L,
                        gzSha.length() >= 12 ? gzSha.substring(0, 12) : gzSha));
                stepIndex++;
            }

            if (opts.includeUserAppsData) {
                cb.onProgress(92, "Archiving user apps & settings (excluding /data/media)...");
                File tarGz = new File(outDir, "userdata_apps.tar.gz");
                String tarCmd = "tar -czf " + RootShell.shellQuote(tarGz.getAbsolutePath())
                        + " --exclude='/data/media' --exclude='media' /data/app /data/data 2>/dev/null || "
                        + "busybox tar -czf " + RootShell.shellQuote(tarGz.getAbsolutePath())
                        + " /data/app /data/system/packages.xml 2>/dev/null";
                shell.execRoot(tarCmd, new RootShell.LineListener() {
                    @Override public void onLine(String line) { cb.onLog(line); }
                });
                if (tarGz.exists() && tarGz.length() > 0) {
                    String h = computeSha256ViaTool(tarGz, 0L);
                    if (!h.isEmpty()) shaSums.put(tarGz.getName(), h);
                }
            }

            // Write standard SHA256SUMS + JSON manifest
            StringBuilder sumsText = new StringBuilder();
            for (Map.Entry<String, String> e : shaSums.entrySet()) {
                sumsText.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
            }
            writeTextFile(new File(outDir, "SHA256SUMS"), sumsText.toString());

            JSONObject manifest = new JSONObject();
            manifest.put("formatVersion", 1);
            manifest.put("createdAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            manifest.put("model", snap.model);
            manifest.put("device", snap.deviceCodename);
            manifest.put("variantTitle", snap.variantTitle);
            manifest.put("androidRelease", snap.androidRelease);
            manifest.put("buildDisplay", snap.buildDisplay);
            manifest.put("partitions", manifestItems);
            writeTextFile(new File(outDir, "backup-manifest.json"), manifest.toString(2));

            cb.onProgress(100, "OS Backup completed & SHA-256 checksums saved!");
            cb.onLog("=== OS Backup Complete: " + outDir.getAbsolutePath() + " ===");
            return outDir;
        } finally {
            if (wl.isHeld()) wl.release();
        }
    }

    public boolean verifyBackupIntegrity(SavedBackup backup, ProgressCallback cb) {
        File sumsFile = new File(backup.directory, "SHA256SUMS");
        if (!sumsFile.exists()) {
            cb.onLog("SHA256SUMS missing in " + backup.directory.getAbsolutePath());
            return false;
        }
        String content = DeviceInspector.readWholeFile(sumsFile.getAbsolutePath());
        String[] lines = content.split("\n");
        int total = 0, passed = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            total++;
            String expected = parts[0].toLowerCase(Locale.US);
            String fname = parts[1];
            File target = new File(backup.directory, fname);
            cb.onProgress((i * 100) / Math.max(1, lines.length), "Verifying SHA-256 of " + fname + "...");
            if (!target.exists()) {
                cb.onLog("FAIL: Missing file " + fname);
                return false;
            }
            String actual = computeSha256ViaTool(target, 0L);
            if (!expected.equalsIgnoreCase(actual)) {
                cb.onLog("FAIL: Checksum mismatch for " + fname + " (expected " + expected + ", got " + actual + ")");
                return false;
            }
            cb.onLog("VERIFIED OK: " + fname + " (" + expected.substring(0, 12) + "...)");
            passed++;
        }
        cb.onProgress(100, "All " + passed + " partition image checksums verified OK!");
        return total > 0 && passed == total;
    }

    public void restoreBackup(SavedBackup backup, DeviceInspector.Snapshot snap,
                              boolean restoreBoot, boolean restoreRecovery,
                              boolean restoreNvram, boolean restoreSystem,
                              final ProgressCallback cb) throws Exception {
        if (!shell.isRootAvailable()) {
            throw new IllegalStateException("Root access is required to restore OS partitions.");
        }

        cb.onLog("=== Pre-Restore Integrity Verification ===");
        if (!verifyBackupIntegrity(backup, cb)) {
            throw new IllegalStateException("Backup failed SHA-256 integrity verification! Aborting before touching flash.");
        }

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arc7Freedom:RestoreWakeLock");
        wl.setReferenceCounted(false);
        wl.acquire(45 * 60 * 1000L);

        try {
            File manifestFile = new File(backup.directory, "backup-manifest.json");
            JSONObject manifest = new JSONObject(readUnlimitedTextFile(manifestFile));
            JSONArray parts = manifest.getJSONArray("partitions");
            File toolBin = shell.getPartitionToolBinary();

            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                final String role = p.getString("role");
                String fname = p.getString("file");
                long savedOffset = p.optLong("offsetBytes", 0L);
                String savedDest = p.getString("sourcePath");

                boolean want = ("boot".equals(role) && restoreBoot)
                        || ("recovery".equals(role) && restoreRecovery)
                        || (("nvram".equals(role) || "protect_f".equals(role) || "protect_s".equals(role)) && restoreNvram)
                        || ("system".equals(role) && restoreSystem);
                if (!want) continue;

                DeviceInspector.PartitionTarget live = snap.partitions.get(role);
                String dstDev = live != null ? live.sourcePath : savedDest;
                long dstOff = live != null ? live.offsetBytes : savedOffset;
                long maxSz = live != null ? live.sizeBytes : p.optLong("rawBytes", 0L);
                File srcImg = new File(backup.directory, fname);

                cb.onLog("Restoring [" + role + "] from " + fname + " -> " + dstDev + "...");
                String cmd = String.format(Locale.US, "%s flash %s %s %d %d",
                        RootShell.shellQuote(toolBin.getAbsolutePath()),
                        RootShell.shellQuote(srcImg.getAbsolutePath()),
                        RootShell.shellQuote(dstDev),
                        dstOff,
                        maxSz);

                RootShell.ExecResult res = shell.execRoot(cmd, new RootShell.LineListener() {
                    @Override
                    public void onLine(String line) {
                        if (line.startsWith("PROGRESS ")) {
                            String[] tok = line.split("\\s+");
                            if (tok.length >= 4) {
                                long cPos = parseLongSafe(tok[1]);
                                long cTot = parseLongSafe(tok[2]);
                                long written = parseLongSafe(tok[3]);
                                int pct = cTot > 0 ? (int) Math.min(99L, (cPos * 100L) / cTot) : 50;
                                cb.onProgress(pct, String.format(Locale.US,
                                        "Restoring %s: %d MB written...", role, written / 1048576L));
                            }
                        } else {
                            cb.onLog(line);
                        }
                    }
                });
                if (res.exitCode != 0) {
                    throw new IllegalStateException("Restore failed on partition '" + role + "': " + res.stdout);
                }
            }
            shell.execRoot("sync", null);
            cb.onProgress(100, "OS Backup restored successfully! Reboot your tablet now to finish.");
        } finally {
            if (wl.isHeld()) wl.release();
        }
    }

    public List<SavedBackup> scanSavedBackups(List<DeviceInspector.StorageVolumeInfo> volumes) {
        List<SavedBackup> result = new ArrayList<SavedBackup>();
        List<String> seen = new ArrayList<String>();
        for (DeviceInspector.StorageVolumeInfo vol : volumes) {
            File backupsRoot = new File(vol.rootDir, "Arc7Freedom/backups");
            File[] dirs = backupsRoot.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                try {
                    if (!d.isDirectory()) continue;
                    String canon = d.getCanonicalPath();
                    if (seen.contains(canon)) continue;
                    File mf = new File(d, "backup-manifest.json");
                    if (!mf.exists()) continue;
                    seen.add(canon);

                    JSONObject j = new JSONObject(readUnlimitedTextFile(mf));
                    List<String> files = new ArrayList<String>();
                    long totalSz = 0L;
                    JSONArray arr = j.optJSONArray("partitions");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            files.add(item.optString("role") + " (" + item.optString("file") + ")");
                            totalSz += item.optLong("compressedBytes", 0L);
                        }
                    }
                    result.add(new SavedBackup(
                            d,
                            d.getName(),
                            j.optString("createdAt", d.getName()),
                            j.optString("model", "Kobo Arc 7"),
                            j.optString("variantTitle", ""),
                            totalSz,
                            files));
                } catch (Throwable ignored) {
                }
            }
        }
        Collections.sort(result, new Comparator<SavedBackup>() {
            @Override
            public int compare(SavedBackup a, SavedBackup b) {
                return b.name.compareTo(a.name);
            }
        });
        return result;
    }

    public String computeSha256ViaTool(File file, long limitBytes) {
        File tool = shell.getPartitionToolBinary();
        String cmd = String.format(Locale.US, "%s sha256 %s %d",
                RootShell.shellQuote(tool.getAbsolutePath()),
                RootShell.shellQuote(file.getAbsolutePath()),
                limitBytes);
        RootShell.ExecResult r = shell.execRoot(cmd, null);
        if (r.exitCode != 0) {
            r = shell.execCommandArray(new String[]{
                    tool.getAbsolutePath(), "sha256", file.getAbsolutePath(), String.valueOf(limitBytes)
            }, null);
        }
        for (String line : r.stdout.split("\n")) {
            if (line.startsWith("SHA256 ")) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 2) return p[1].trim();
            }
        }
        return "";
    }

    public static long estimateRequiredBytes(Options o) {
        long mb = 24L;
        if (o.includeNvramAndScatter) mb += 28L;
        if (o.includeBootAndRecovery) mb += 24L;
        if (o.includeSystemPartition) mb += 420L;
        if (o.includeUserAppsData) mb += 180L;
        return mb * 1048576L;
    }

    private static void addIfPresent(DeviceInspector.Snapshot snap, List<PartitionJob> out,
                                     String role, String fileName) {
        DeviceInspector.PartitionTarget t = snap.partitions.get(role);
        if (t != null) {
            out.add(new PartitionJob(role, t, fileName));
        }
    }

    private static long getUsableSpaceSafe(File dir) {
        try {
            File probe = dir;
            while (probe != null && !probe.exists()) probe = probe.getParentFile();
            if (probe == null) return 0L;
            android.os.StatFs sf = new android.os.StatFs(probe.getAbsolutePath());
            return (long) sf.getAvailableBlocks() * (long) sf.getBlockSize();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void writeTextFile(File file, String content) throws Exception {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        FileWriter fw = new FileWriter(file, false);
        try {
            fw.write(content);
        } finally {
            fw.close();
        }
    }

    public static String readUnlimitedTextFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            br.close();
        }
        return sb.toString();
    }

    private static class PartitionJob {
        final String role;
        final DeviceInspector.PartitionTarget target;
        final String fileName;

        PartitionJob(String role, DeviceInspector.PartitionTarget target, String fileName) {
            this.role = role;
            this.target = target;
            this.fileName = fileName;
        }
    }
}
