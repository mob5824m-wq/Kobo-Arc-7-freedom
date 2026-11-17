package ca.arc7freedom.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Streaming offline payload reader. Pure Java so host tests exercise the tablet's exact code. */
public final class BundledApk {
    public interface Assets { InputStream open(String name) throws IOException; }
    public static final class Entry {
        public final String pkg, label, filename, sha256, source;
        public final int version;
        Entry(String[] fields) throws IOException {
            if (fields.length != 6 || !fields[0].matches("[a-zA-Z0-9_.]+")
                    || !fields[2].matches("[a-zA-Z0-9_.-]+\\.apk")
                    || !fields[4].matches("[a-f0-9]{64}")) throw new IOException("Invalid APK catalog row");
            pkg = fields[0]; label = fields[1]; filename = fields[2]; sha256 = fields[4]; source = fields[5];
            try { version = Integer.parseInt(fields[3]); }
            catch (NumberFormatException e) { throw new IOException("Invalid bundled APK version"); }
            if (version <= 0 || !(source.startsWith("asset:") || source.startsWith("parts:"))
                    || source.contains("..") || source.contains("\\") || source.substring(6).startsWith("/")) {
                throw new IOException("Invalid APK payload source");
            }
        }
    }
    private BundledApk() {}

    public static List<Entry> catalog(Assets assets) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(assets.open("maintenance/catalog.tsv"), "UTF-8"));
        try {
            List<Entry> result = new ArrayList<Entry>();
            java.util.HashSet<String> packages = new java.util.HashSet<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0 || line.startsWith("#")) continue;
                Entry entry = new Entry(line.split("\t", -1));
                if (!packages.add(entry.pkg)) throw new IOException("Duplicate APK catalog package");
                result.add(entry);
            }
            if (result.isEmpty()) throw new IOException("Empty offline APK catalog");
            return result;
        } finally { reader.close(); }
    }

    /** Only exact bytes count as current; a version match alone must not hide maintenance patches. */
    public static String decision(int installedVersion, int bundledVersion, boolean readable, boolean sameBytes) {
        if (installedVersion > bundledVersion) return "NEWER_KEPT";
        if (installedVersion < 0) return "INSTALL";
        if (!readable) return "BLOCKED";
        return sameBytes ? "CURRENT" : "INSTALL";
    }

    public static File extract(Assets assets, Entry entry, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("APK staging storage unavailable");
        File partial = new File(directory, entry.filename + ".part");
        File ready = new File(directory, entry.filename);
        boolean complete = false;
        try {
            FileOutputStream output = new FileOutputStream(partial);
            try {
                if (entry.source.startsWith("asset:")) {
                    copy(assets.open(entry.source.substring(6)), output);
                } else {
                    BufferedReader recipe = new BufferedReader(new InputStreamReader(assets.open(entry.source.substring(6)), "UTF-8"));
                    try {
                        String hash;
                        int count = 0;
                        while ((hash = recipe.readLine()) != null) {
                            if (!hash.matches("[a-f0-9]{64}") || ++count > 20000) throw new IOException("Invalid APK recipe");
                            copy(assets.open("maintenance/chunks/" + hash + ".bin"), output);
                        }
                        if (count == 0) throw new IOException("Empty APK recipe");
                    } finally { recipe.close(); }
                }
                output.getFD().sync();
            } finally { output.close(); }
            if (!entry.sha256.equals(digest(new FileInputStream(partial)))) throw new IOException("APK checksum mismatch: " + entry.label);
            if (ready.exists() && !ready.delete()) throw new IOException("Cannot replace staged APK");
            if (!partial.renameTo(ready)) throw new IOException("Cannot finish staged APK");
            ready.setReadable(true, false);
            complete = true;
            return ready;
        } finally { if (!complete) partial.delete(); }
    }

    private static void copy(InputStream input, FileOutputStream output) throws IOException {
        try {
            byte[] buffer = new byte[32768]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        } finally { input.close(); }
    }

    /** Takes ownership of and closes the stream, also on a failed read. */
    public static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable"); }
        finally { input.close(); }
    }

    /** Shared by automatic and button-triggered root installs. Never uninstall/clear or mask pm failure. */
    public static String installScript(String source, String target, String pkg) {
        return "trap " + quote("rm -f " + quote(target)) + " 0\n"
                + "mkdir -p /data/local/tmp && cat " + quote(source) + " > " + quote(target)
                + " && chmod 0644 " + quote(target) + " || { echo PAYLOAD_STAGE_FAILED; exit 31; }\n"
                + "_pm=$(pm install -r " + quote(target) + " 2>&1)\n_rc=$?\necho \"$_pm\"\n"
                + "if [ \"$_rc\" -eq 0 ]; then case \"$_pm\" in *Success*) echo ARC7_APK_INSTALL_OK; exit 0 ;; esac; fi\n"
                + "echo " + quote("UPDATE_FAILED_DATA_PRESERVED " + pkg) + "\nexit 32\n";
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
