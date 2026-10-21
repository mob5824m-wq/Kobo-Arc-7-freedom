package ca.arc7freedom.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inspects real hardware, kernel, storage volumes, Kobo bloatware packages, and eMMC partition layout
 * across Kobo Arc 7 (MT8125 / apollojr / LBP8 / T647), Kobo Arc 7HD (Tegra 3 / cardhu / T416),
 * Kobo Arc 10HD (macallan), and generic Android devices.
 */
public class DeviceInspector {

    public enum Variant {
        KOBO_ARC_7_MT8125,
        KOBO_ARC_7HD_TEGRA,
        KOBO_ARC_OTHER,
        GENERIC_ANDROID
    }

    public static class PartitionTarget {
        public final String role;        // e.g. "boot", "recovery", "system", "cache", "userdata", "nvram", "protect_f", "protect_s", "uboot", "logo"
        public final String sourcePath;  // e.g. "/dev/recovery" or "/dev/block/mmcblk0" or "/emmc@android"
        public final long offsetBytes;   // non-zero only when reading raw slice of /dev/block/mmcblk0
        public final long sizeBytes;     // known exact size in bytes (0 = full block device)

        public PartitionTarget(String role, String sourcePath, long offsetBytes, long sizeBytes) {
            this.role = role;
            this.sourcePath = sourcePath;
            this.offsetBytes = offsetBytes;
            this.sizeBytes = sizeBytes;
        }

        public String describe() {
            String sz = sizeBytes > 0 ? String.format(Locale.US, " (%d MB)", sizeBytes / 1048576L) : "";
            if (offsetBytes > 0) {
                return String.format(Locale.US, "%s @ 0x%x%s", sourcePath, offsetBytes, sz);
            }
            return sourcePath + sz;
        }
    }

    public static class StorageVolumeInfo {
        public final String label;
        public final File rootDir;
        public final boolean removableSd;
        public final long freeBytes;
        public final long totalBytes;

        public StorageVolumeInfo(String label, File rootDir, boolean removableSd, long freeBytes, long totalBytes) {
            this.label = label;
            this.rootDir = rootDir;
            this.removableSd = removableSd;
            this.freeBytes = freeBytes;
            this.totalBytes = totalBytes;
        }

        public String formatSummary() {
            return String.format(Locale.US, "%s (%s) — %d MB free / %d MB total",
                    label, rootDir.getAbsolutePath(), freeBytes / 1048576L, totalBytes / 1048576L);
        }
    }

    public static class Snapshot {
        public Variant variant;
        public String variantTitle;
        public String model;
        public String deviceCodename;
        public String hardware;
        public String androidRelease;
        public int sdkInt;
        public String buildDisplay;
        public String kernelLine;
        public String cpuAbi;
        public boolean rooted;
        public String suPath;
        public String exploitProbeSummary;
        public Map<String, PartitionTarget> partitions = new LinkedHashMap<String, PartitionTarget>();
        public String rawDumcharText = "";
        public String rawEmmcText = "";
        public String rawPartitionsText = "";
        public String rawMountsText = "";
        public List<StorageVolumeInfo> volumes = new ArrayList<StorageVolumeInfo>();
        public List<String> detectedKoboSystemApks = new ArrayList<String>();
        public List<String> detectedKoboPackages = new ArrayList<String>();
        public boolean vanillaHomeInstalled;
    }

    public static Snapshot collect(Context ctx, RootShell shell) {
        Snapshot s = new Snapshot();
        s.model = safeStr(Build.MODEL);
        s.deviceCodename = safeStr(Build.DEVICE);
        s.hardware = safeStr(Build.HARDWARE);
        s.androidRelease = safeStr(Build.VERSION.RELEASE);
        s.sdkInt = Build.VERSION.SDK_INT;
        s.buildDisplay = safeStr(Build.DISPLAY);
        s.cpuAbi = safeStr(Build.CPU_ABI);
        s.kernelLine = readFirstLine("/proc/version");

        s.rawMountsText = readWholeFile("/proc/mounts");
        s.rawDumcharText = readWholeFile("/proc/dumchar_info");
        s.rawEmmcText = readWholeFile("/proc/emmc");
        s.rawPartitionsText = readWholeFile("/proc/partitions");

        s.rooted = shell.isRootAvailable();
        s.suPath = shell.findWorkingSuBinary();
        if (s.rooted && s.rawDumcharText.isEmpty()) {
            // Try reading restricted /proc entries with root if needed
            RootShell.ExecResult r = shell.execRoot("cat /proc/dumchar_info 2>/dev/null", null);
            if (r.exitCode == 0 && !r.stdout.trim().isEmpty()) {
                s.rawDumcharText = r.stdout;
            }
        }

        s.exploitProbeSummary = shell.runExploitCheck();
        detectVariant(s);
        discoverPartitions(s, shell);
        discoverStorageVolumes(s);
        discoverKoboComponents(ctx, s);
        return s;
    }

    private static void detectVariant(Snapshot s) {
        String fingerprint = (s.model + " " + s.deviceCodename + " " + s.hardware + " "
                + Build.BOARD + " " + Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);

        boolean hasMtk = !s.rawDumcharText.isEmpty()
                || fingerprint.contains("mt8125") || fingerprint.contains("mt6589")
                || fingerprint.contains("apollojr") || fingerprint.contains("lbp8")
                || fingerprint.contains("t647") || s.rawMountsText.contains("/emmc@android");
        boolean hasTegra = fingerprint.contains("tegra") || fingerprint.contains("cardhu")
                || fingerprint.contains("macallan") || fingerprint.contains("t416")
                || new File("/dev/block/platform/sdhci-tegra.3/by-name").exists();

        if (hasMtk) {
            s.variant = Variant.KOBO_ARC_7_MT8125;
            s.variantTitle = "Kobo Arc 7 (MT8125 / apollojr / T647)";
        } else if (hasTegra) {
            s.variant = Variant.KOBO_ARC_7HD_TEGRA;
            s.variantTitle = "Kobo Arc 7HD / 10HD (NVIDIA Tegra)";
        } else if (fingerprint.contains("kobo") || fingerprint.contains("arc") || fingerprint.contains("zeus")) {
            s.variant = Variant.KOBO_ARC_OTHER;
            s.variantTitle = "Kobo Arc Tablet (" + s.deviceCodename + ")";
        } else {
            s.variant = Variant.GENERIC_ANDROID;
            s.variantTitle = "Generic Android Device (" + s.model + ")";
        }
    }

    private static void discoverPartitions(Snapshot s, RootShell shell) {
        // 1. Parse MediaTek /proc/dumchar_info (gold-standard exact offsets & sizes on Kobo Arc 7 MT8125!)
        if (!s.rawDumcharText.isEmpty()) {
            String[] lines = s.rawDumcharText.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("Part_Name")) continue;
                String[] tok = trimmed.split("\\s+");
                if (tok.length >= 5) {
                    String partName = tok[0].toLowerCase(Locale.US);
                    long size = parseHex(tok[1]);
                    long startAddr = parseHex(tok[2]);
                    String mapTo = tok[4]; // e.g. /dev/block/mmcblk0
                    String role = mapMtkNameToRole(partName);
                    if (role != null && size > 0) {
                        String directDev = mapMtkDirectDevice(partName, mapTo, startAddr);
                        long useOffset = directDev.equals(mapTo) ? startAddr : 0L;
                        s.partitions.put(role, new PartitionTarget(role, directDev, useOffset, size));
                    }
                }
            }
        }

        // 2. Parse MediaTek /proc/emmc fallback (exact verified layout from Kobo Arc 7!)
        if (!s.rawEmmcText.isEmpty()) {
            String[] lines = s.rawEmmcText.split("\n");
            for (String line : lines) {
                // Format: emmc_p5: 00020c00 00180000 "android"
                if (!line.startsWith("emmc_p")) continue;
                int colon = line.indexOf(':');
                int q1 = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                if (colon > 0 && q1 > colon && q2 > q1) {
                    String pNum = line.substring("emmc_p".length(), colon).trim();
                    String[] hexes = line.substring(colon + 1, q1).trim().split("\\s+");
                    String name = line.substring(q1 + 1, q2).toLowerCase(Locale.US);
                    long nrSects = hexes.length >= 2 ? parseHex(hexes[1]) : 0L;
                    long bytes = nrSects * 512L;
                    String role = mapMtkNameToRole(name);
                    if (role != null && !s.partitions.containsKey(role)) {
                        String dev = "/dev/block/mmcblk0p" + pNum;
                        if (new File("/emmc@" + name).exists()) dev = "/emmc@" + name;
                        s.partitions.put(role, new PartitionTarget(role, dev, 0L, bytes));
                    }
                }
            }
        }

        // 3. Parse NVIDIA Tegra /dev/block/platform/sdhci-tegra.3/by-name (Kobo Arc 7HD / 10HD)
        String[][] tegraMap = new String[][]{
                {"boot", "/dev/block/platform/sdhci-tegra.3/by-name/LNX"},
                {"recovery", "/dev/block/platform/sdhci-tegra.3/by-name/SOS"},
                {"system", "/dev/block/platform/sdhci-tegra.3/by-name/APP"},
                {"cache", "/dev/block/platform/sdhci-tegra.3/by-name/CAC"},
                {"userdata", "/dev/block/platform/sdhci-tegra.3/by-name/UDA"},
                {"misc", "/dev/block/platform/sdhci-tegra.3/by-name/MSC"}
        };
        for (String[] pair : tegraMap) {
            if (!s.partitions.containsKey(pair[0]) && new File(pair[1]).exists()) {
                s.partitions.put(pair[0], new PartitionTarget(pair[0], pair[1], 0L, 0L));
            }
        }

        // 4. Parse /proc/mounts for /system, /cache, /data, /protect_f, /protect_s if not yet populated
        for (String line : s.rawMountsText.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2 && p[0].startsWith("/")) {
                if ("/system".equals(p[1]) && !s.partitions.containsKey("system")) {
                    s.partitions.put("system", new PartitionTarget("system", p[0], 0L, 0L));
                } else if ("/cache".equals(p[1]) && !s.partitions.containsKey("cache")) {
                    s.partitions.put("cache", new PartitionTarget("cache", p[0], 0L, 0L));
                } else if ("/data".equals(p[1]) && !s.partitions.containsKey("userdata")) {
                    s.partitions.put("userdata", new PartitionTarget("userdata", p[0], 0L, 0L));
                } else if ("/protect_f".equals(p[1]) && !s.partitions.containsKey("protect_f")) {
                    s.partitions.put("protect_f", new PartitionTarget("protect_f", p[0], 0L, 0L));
                } else if ("/protect_s".equals(p[1]) && !s.partitions.containsKey("protect_s")) {
                    s.partitions.put("protect_s", new PartitionTarget("protect_s", p[0], 0L, 0L));
                }
            }
        }

        // 5. Generic Android by-name scan (root shell ls if available)
        if (!s.partitions.containsKey("boot") || !s.partitions.containsKey("recovery")) {
            String[] commonPaths = new String[]{
                    "/dev/bootimg", "/dev/block/by-name/boot",
                    "/dev/block/platform/omap/omap_hsmmc.1/by-name/boot"
            };
            for (String cp : commonPaths) {
                if (!s.partitions.containsKey("boot") && new File(cp).exists()) {
                    s.partitions.put("boot", new PartitionTarget("boot", cp, 0L, 0L));
                }
            }
            String[] recPaths = new String[]{
                    "/dev/recovery", "/dev/block/by-name/recovery",
                    "/dev/block/platform/omap/omap_hsmmc.1/by-name/recovery"
            };
            for (String rp : recPaths) {
                if (!s.partitions.containsKey("recovery") && new File(rp).exists()) {
                    s.partitions.put("recovery", new PartitionTarget("recovery", rp, 0L, 0L));
                }
            }
        }
    }

    private static String mapMtkNameToRole(String mtkName) {
        if ("bootimg".equals(mtkName) || "boot".equals(mtkName)) return "boot";
        if ("recovery".equals(mtkName)) return "recovery";
        if ("android".equals(mtkName) || "system".equals(mtkName)) return "system";
        if ("cache".equals(mtkName)) return "cache";
        if ("usrdata".equals(mtkName) || "userdata".equals(mtkName)) return "userdata";
        if ("nvram".equals(mtkName)) return "nvram";
        if ("protect_f".equals(mtkName)) return "protect_f";
        if ("protect_s".equals(mtkName)) return "protect_s";
        if ("sec_ro".equals(mtkName)) return "sec_ro";
        if ("uboot".equals(mtkName) || "lk".equals(mtkName)) return "uboot";
        if ("logo".equals(mtkName)) return "logo";
        return null;
    }

    private static String mapMtkDirectDevice(String partName, String mapTo, long startAddr) {
        String emmcSym = "/emmc@" + partName;
        if (new File(emmcSym).exists()) return emmcSym;
        String charDev = "/dev/" + partName;
        if (new File(charDev).exists()) return charDev;
        return mapTo;
    }

    private static void discoverStorageVolumes(Snapshot s) {
        // Check External microSD candidates FIRST so microSD is offered prominently on Kobo Arc 7!
        String[] extCandidates = new String[]{
                "/storage/sdcard1",
                "/mnt/sdcard2",
                "/storage/extSdCard",
                "/mnt/external_sd",
                "/storage/external_SD"
        };
        List<String> seenCanonical = new ArrayList<String>();
        for (String path : extCandidates) {
            addVolumeIfUsable(s.volumes, seenCanonical, "External microSD Card", new File(path), true);
        }

        // Also scan /proc/mounts for any mounted vfat/exfat sdcard volume on mmcblk1
        for (String line : s.rawMountsText.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 3 && (p[0].contains("179:97") || p[0].contains("mmcblk1") || p[1].contains("sdcard1"))) {
                addVolumeIfUsable(s.volumes, seenCanonical, "External microSD Card", new File(p[1]), true);
            }
        }

        File internal = Environment.getExternalStorageDirectory();
        if (internal != null) {
            addVolumeIfUsable(s.volumes, seenCanonical, "Internal Storage", internal, false);
        }
        addVolumeIfUsable(s.volumes, seenCanonical, "Internal /sdcard", new File("/sdcard"), false);
    }

    private static void addVolumeIfUsable(List<StorageVolumeInfo> out, List<String> seen,
                                          String label, File dir, boolean removable) {
        try {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return;
            String canon = dir.getCanonicalPath();
            if (seen.contains(canon)) return;
            StatFs sf = new StatFs(dir.getAbsolutePath());
            long blockSize = sf.getBlockSize();
            long total = (long) sf.getBlockCount() * blockSize;
            long avail = (long) sf.getAvailableBlocks() * blockSize;
            if (total <= 0) return;
            seen.add(canon);
            out.add(new StorageVolumeInfo(label, dir, removable, avail, total));
        } catch (Throwable ignored) {
        }
    }

    private static void discoverKoboComponents(Context ctx, Snapshot s) {
        String[] sysDirs = new String[]{"/system/app", "/system/priv-app"};
        for (String d : sysDirs) {
            File dir = new File(d);
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".apk")) continue;
                String low = n.toLowerCase(Locale.US);
                if (low.contains("kobo") || low.contains("tapestry")
                        || low.contains("arcsetup") || low.contains("kobohome")) {
                    s.detectedKoboSystemApks.add(f.getAbsolutePath());
                }
            }
        }

        PackageManager pm = ctx.getPackageManager();
        s.vanillaHomeInstalled = false;
        try {
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            for (PackageInfo pi : pkgs) {
                if ("ca.arc7freedom.launcher".equals(pi.packageName)) {
                    s.vanillaHomeInstalled = true;
                }
                String low = pi.packageName.toLowerCase(Locale.US);
                if (low.contains("kobo") || low.contains("tapestry")) {
                    ApplicationInfo ai = pi.applicationInfo;
                    String state = (ai != null && !ai.enabled) ? " (disabled)" : "";
                    s.detectedKoboPackages.add(pi.packageName + state);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String buildScatterFile(String dumcharInfo) {
        if (dumcharInfo == null || dumcharInfo.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("############################################################################################################\n");
        sb.append("#\n");
        sb.append("#  General Setting - Auto-generated by Kobo Arc 7 Freedom from live device /proc/dumchar_info\n");
        sb.append("#  Compatible with MediaTek SP Flash Tool for full low-level unbrick recovery\n");
        sb.append("#\n");
        sb.append("############################################################################################################\n");
        sb.append("- general: MTK_PLATFORM_CFG\n");
        sb.append("  info:\n");
        sb.append("    - config_version: V1.1.1\n");
        sb.append("      platform: MT8125\n");
        sb.append("      project: apollojr\n");
        sb.append("      storage: EMMC\n");
        sb.append("      boot_channel: MSDC_0\n");
        sb.append("      block_size: 0x20000\n\n");

        int idx = 0;
        for (String line : dumcharInfo.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("Part_Name")) continue;
            String[] tok = t.split("\\s+");
            if (tok.length < 4) continue;
            String name = tok[0];
            long size = parseHex(tok[1]);
            long addr = parseHex(tok[2]);
            String upper = name.toUpperCase(Locale.US);
            String fname = fileNameForScatterPartition(name);
            sb.append(String.format(Locale.US, "- partition_index: SYS%d\n", idx++));
            sb.append(String.format(Locale.US, "  partition_name: %s\n", upper));
            sb.append(String.format(Locale.US, "  file_name: %s\n", fname));
            sb.append(String.format(Locale.US, "  is_download: %s\n", "NONE".equals(fname) ? "false" : "true"));
            sb.append(String.format(Locale.US, "  type: %s\n",
                    ("ANDROID".equals(upper) || "CACHE".equals(upper) || "USRDATA".equals(upper))
                            ? "YAFFS_IMG" : "NORMAL_ROM"));
            sb.append(String.format(Locale.US, "  linear_start_addr: 0x%x\n", addr));
            sb.append(String.format(Locale.US, "  physical_start_addr: 0x%x\n", addr));
            sb.append(String.format(Locale.US, "  partition_size: 0x%x\n\n", size));
        }
        return sb.toString();
    }

    private static String fileNameForScatterPartition(String name) {
        String low = name.toLowerCase(Locale.US);
        if ("bootimg".equals(low)) return "boot.img";
        if ("recovery".equals(low)) return "recovery.img";
        if ("uboot".equals(low)) return "lk.bin";
        if ("logo".equals(low)) return "logo.bin";
        if ("android".equals(low)) return "system.img";
        if ("sec_ro".equals(low)) return "secro.img";
        return "NONE";
    }

    private static long parseHex(String s) {
        try {
            String clean = s.trim();
            if (clean.startsWith("0x") || clean.startsWith("0X")) clean = clean.substring(2);
            return Long.parseLong(clean, 16);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static String readFirstLine(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Throwable t) {
            return "";
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
    }

    public static String readWholeFile(String path) {
        BufferedReader br = null;
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return "";
            StringBuilder sb = new StringBuilder();
            br = new BufferedReader(new FileReader(f));
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < 400) {
                sb.append(line).append('\n');
                count++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
    }
}
