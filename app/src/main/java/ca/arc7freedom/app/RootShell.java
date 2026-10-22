package ca.arc7freedom.app;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges Java UI workflows with our native static ARMv7 binaries (`libarc7root.so`,
 * `libarc7su.so`, `libarc7tool.so`, `libarc7tls.so`) and system root shells across ALL
 * Android 4.2 `su` calling conventions:
 *   Mode 1: `su -c "/system/bin/sh <script.sh>"` (Arc7 su, SuperSU, Superuser, KingoRoot, Magisk)
 *   Mode 2: `su 0 /system/bin/sh <script.sh>`   (AOSP `system/extras/su/su.c` engineering builds)
 *   Mode 3: `su` with commands piped on `stdin`
 */
public class RootShell {

    public interface LineListener {
        void onLine(String line);
    }

    public static class ExecResult {
        public final int exitCode;
        public final String stdout;

        public ExecResult(int exitCode, String stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
        }
    }

    private static final int MODE_DASH_C = 1;
    private static final int MODE_AOSP_UID0 = 2;
    private static final int MODE_STDIN_PIPE = 3;
    private static final AtomicLong SCRIPT_SEQ = new AtomicLong(1L);

    private final Context appContext;
    private File binDir;
    private volatile String cachedSuPath = null;
    private volatile int cachedSuMode = MODE_DASH_C;

    public RootShell(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        prepareBinaries();
    }

    public synchronized void prepareBinaries() {
        binDir = new File(appContext.getFilesDir(), "bin");
        if (!binDir.exists()) {
            binDir.mkdirs();
        }
        binDir.setReadable(true, false);
        binDir.setExecutable(true, false);

        String nativeDirPath = appContext.getApplicationInfo().nativeLibraryDir;
        String[] libs = new String[]{
                "libarc7root.so", "libarc7su.so", "libarc7tool.so",
                "libarc7tls.so", "libarc7py3.so", "libarc7apt.so"
        };
        for (String name : libs) {
            File src = new File(nativeDirPath, name);
            File dst = new File(binDir, name);
            if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
                copyFile(src, dst);
            }
            if (dst.exists()) {
                dst.setReadable(true, false);
                dst.setExecutable(true, false);
            }
        }
        // Provide direct CLI aliases (`python3`, `python`, `apt`, `apt-get`, `apt-cache`, `arc7tls`)
        // right inside `binDir` so even unrooted shells in Arc7 Terminal have instant `python3` & `apt`!
        linkAliasQuietly(new File(binDir, "libarc7py3.so"), new File(binDir, "python3"));
        linkAliasQuietly(new File(binDir, "libarc7py3.so"), new File(binDir, "python"));
        linkAliasQuietly(new File(binDir, "libarc7apt.so"), new File(binDir, "apt"));
        linkAliasQuietly(new File(binDir, "libarc7apt.so"), new File(binDir, "apt-get"));
        linkAliasQuietly(new File(binDir, "libarc7apt.so"), new File(binDir, "apt-cache"));
        linkAliasQuietly(new File(binDir, "libarc7tls.so"), new File(binDir, "arc7tls"));
    }

    private static void linkAliasQuietly(File target, File link) {
        if (!target.exists() || link.exists()) return;
        try {
            Runtime.getRuntime().exec(new String[]{"ln", "-s", target.getAbsolutePath(), link.getAbsolutePath()}).waitFor();
        } catch (Throwable ignored) {}
        if (!link.exists()) {
            copyFile(target, link);
            link.setReadable(true, false);
            link.setExecutable(true, false);
        }
    }

    public File getPython3Binary() {
        return resolveBinary("libarc7py3.so");
    }

    public File getAptBinary() {
        return resolveBinary("libarc7apt.so");
    }

    public File getRootEngineBinary() {
        return resolveBinary("libarc7root.so");
    }

    public File getSuPayloadBinary() {
        return resolveBinary("libarc7su.so");
    }

    public File getPartitionToolBinary() {
        return resolveBinary("libarc7tool.so");
    }

    public File getTlsEngineBinary() {
        return resolveBinary("libarc7tls.so");
    }

    private File resolveBinary(String name) {
        File inNativeLib = new File(appContext.getApplicationInfo().nativeLibraryDir, name);
        if (inNativeLib.exists() && inNativeLib.canExecute()) {
            return inNativeLib;
        }
        File inBin = new File(binDir, name);
        inBin.setExecutable(true, false);
        return inBin;
    }

    /**
     * Probes every installed `su` candidate across all 3 Android invocation styles and returns
     * only a binary proven able to execute arbitrary `/system/bin/sh` scripts with `uid=0`.
     */
    public String findWorkingSuBinary() {
        if (cachedSuPath != null && new File(cachedSuPath).exists()) {
            if (probeSuWithMode(cachedSuPath, cachedSuMode)) {
                return cachedSuPath;
            }
            cachedSuPath = null;
        }

        String[] candidates = new String[]{
                "/sbin/su",
                "/system/xbin/su",
                "/system/bin/su",
                "/system/xbin/daemonsu",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                getSuPayloadBinary().getAbsolutePath()
        };

        int[] modes = new int[]{MODE_DASH_C, MODE_AOSP_UID0, MODE_STDIN_PIPE};
        for (String c : candidates) {
            File f = new File(c);
            if (!f.exists()) continue;
            for (int mode : modes) {
                if (probeSuWithMode(c, mode)) {
                    cachedSuPath = c;
                    cachedSuMode = mode;
                    return c;
                }
            }
        }
        return null;
    }

    private boolean probeSuWithMode(String suPath, int mode) {
        ExecResult r = executeWithMode(suPath, mode, "id", null);
        return r.stdout != null && r.stdout.contains("uid=0");
    }

    public boolean isRootAvailable() {
        return findWorkingSuBinary() != null;
    }

    public String runExploitCheck() {
        File bin = getRootEngineBinary();
        if (!bin.exists()) return "Native binary missing";
        ExecResult r = execCommandArray(new String[]{bin.getAbsolutePath(), "--check"}, null);
        return r.stdout.trim();
    }

    public ExecResult runRootExploit(LineListener listener) {
        cachedSuPath = null;
        prepareBinaries();
        File rootBin = getRootEngineBinary();
        File suBin = getSuPayloadBinary();
        if (listener != null) {
            listener.onLine("Binary: " + rootBin.getAbsolutePath());
            listener.onLine("Payload: " + suBin.getAbsolutePath());
        }
        ExecResult res = execCommandArray(
                new String[]{rootBin.getAbsolutePath(), "--root", suBin.getAbsolutePath()},
                listener);

        String working = findWorkingSuBinary();
        if (working != null) {
            ExecResult shellVerify = execRoot("id", null);
            if (shellVerify.stdout.contains("uid=0")) {
                if (listener != null) {
                    listener.onLine("Verified persistent root shell via " + working + ": " + shellVerify.stdout.trim());
                }
                return new ExecResult(0, res.stdout);
            }
        }
        return new ExecResult(res.exitCode == 0 ? 1 : res.exitCode, res.stdout);
    }

    private static volatile boolean sAppletsSynced = false;

    public ExecResult execRoot(String shellScript, LineListener listener) {
        String su = findWorkingSuBinary();
        if (su == null) {
            // Self-heal once in case `/system` has `nosuid` and root daemon exited after idle
            ExecResult repair = execCommandArray(
                    new String[]{getRootEngineBinary().getAbsolutePath(), "--root", getSuPayloadBinary().getAbsolutePath()},
                    null);
            if (repair.exitCode == 0) {
                su = findWorkingSuBinary();
            }
        }
        if (su == null) {
            return new ExecResult(126, "Root access is not active yet. Tap 'Root Kobo Arc 7 Now' on Tab 1 first.");
        }

        // One-time silent self-heal per process: ensure `/sbin/{su,uname,cp}` & `/system/xbin/{uname,cp}`
        // exist even on tablets originally rooted by earlier builds lacking `uname`/`cp` applets!
        if (!sAppletsSynced) {
            sAppletsSynced = true;
            try {
                String suBinSrc = getSuPayloadBinary().getAbsolutePath();
                String syncSh =
                        "(mount -o remount,rw / 2>/dev/null || mount -o remount,rw rootfs / 2>/dev/null) && "
                        + "mkdir -p /sbin && cat '" + suBinSrc + "' > /sbin/su.new && "
                        + "chown 0:0 /sbin/su.new 2>/dev/null; chmod 06755 /sbin/su.new && "
                        + "mv /sbin/su.new /sbin/su && "
                        + "ln -s /sbin/su /sbin/uname 2>/dev/null || true; "
                        + "ln -s /sbin/su /sbin/cp 2>/dev/null || true; "
                        + "mount -o remount,ro / 2>/dev/null || true";
                executeWithMode(su, cachedSuMode, syncSh, null);
            } catch (Throwable ignored) {}
        }

        ExecResult primary = executeWithMode(su, cachedSuMode, shellScript, listener);
        if (primary.exitCode == 0 || !primary.stdout.isEmpty()) {
            return primary;
        }
        // Automatic fallback across alternate `su` modes if primary exited silent non-zero
        int[] fallbacks = new int[]{MODE_DASH_C, MODE_AOSP_UID0, MODE_STDIN_PIPE};
        for (int m : fallbacks) {
            if (m == cachedSuMode) continue;
            ExecResult alt = executeWithMode(su, m, shellScript, listener);
            if (alt.exitCode == 0 || !alt.stdout.isEmpty()) {
                cachedSuMode = m;
                return alt;
            }
        }
        return primary;
    }

    private ExecResult executeWithMode(String su, int mode, String shellScript, LineListener listener) {
        File tmpScript = null;
        Process proc = null;
        try {
            if (mode == MODE_STDIN_PIPE) {
                ProcessBuilder pb = new ProcessBuilder(su);
                pb.redirectErrorStream(true);
                proc = pb.start();
                OutputStream os = proc.getOutputStream();
                String wrapped = shellScript + "\nexit $?\n";
                os.write(wrapped.getBytes("UTF-8"));
                os.flush();
                os.close();
            } else {
                // Write a clean world-readable script in app cache dir (`0755`) so zero quoting,
                // whitespace, `$VAR`, or length issues can ever happen inside `su -c` or `su 0`!
                File scriptDir = new File(appContext.getCacheDir(), "su_scripts");
                if (!scriptDir.exists()) {
                    scriptDir.mkdirs();
                }
                scriptDir.setReadable(true, false);
                scriptDir.setExecutable(true, false);
                // Ensure parent app dirs allow uid=0 traversal even under DAC_READ_SEARCH restrictions
                File p1 = appContext.getCacheDir();
                if (p1 != null) p1.setExecutable(true, false);
                File p2 = p1 != null ? p1.getParentFile() : null;
                if (p2 != null) p2.setExecutable(true, false);

                tmpScript = new File(scriptDir, "cmd_" + SCRIPT_SEQ.incrementAndGet() + ".sh");
                FileOutputStream fos = new FileOutputStream(tmpScript);
                try {
                    fos.write(("#!/system/bin/sh\n" + shellScript + "\n").getBytes("UTF-8"));
                    fos.flush();
                } finally {
                    fos.close();
                }
                tmpScript.setReadable(true, false);
                tmpScript.setExecutable(true, false);

                String[] cmdArr = (mode == MODE_AOSP_UID0)
                        ? new String[]{su, "0", "/system/bin/sh", tmpScript.getAbsolutePath()}
                        : new String[]{su, "-c", "/system/bin/sh " + tmpScript.getAbsolutePath()};
                ProcessBuilder pb = new ProcessBuilder(cmdArr);
                pb.redirectErrorStream(true);
                proc = pb.start();
                proc.getOutputStream().close();
            }

            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                    if (listener != null) listener.onLine(line);
                }
            } finally {
                br.close();
            }
            int code = proc.waitFor();
            return new ExecResult(code, out.toString());
        } catch (Throwable t) {
            String msg = "Root shell error: " + t.getMessage();
            if (listener != null) listener.onLine(msg);
            return new ExecResult(1, msg);
        } finally {
            if (tmpScript != null) {
                try { tmpScript.delete(); } catch (Throwable ignored) {}
            }
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    public ExecResult execCommandArray(String[] cmd, LineListener listener) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            proc.getOutputStream().close();

            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                    if (listener != null) listener.onLine(line);
                }
            } finally {
                br.close();
            }
            int code = proc.waitFor();
            return new ExecResult(code, out.toString());
        } catch (Throwable t) {
            String msg = "Exec error: " + t.getMessage();
            if (listener != null) listener.onLine(msg);
            return new ExecResult(1, msg);
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    public static String remountSystemRwCmd() {
        return "(mount -o remount,rw /system 2>/dev/null || mount -o remount,rw /emmc@android /system 2>/dev/null || true)";
    }

    public static String remountSystemRoCmd() {
        return "(mount -o remount,ro /system 2>/dev/null || true)";
    }

    public ExecResult execSh(String shellScript, LineListener listener) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh");
            pb.redirectErrorStream(true);
            proc = pb.start();
            OutputStream os = proc.getOutputStream();
            os.write((shellScript + "\nexit $?\n").getBytes("UTF-8"));
            os.flush();
            os.close();

            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                    if (listener != null) listener.onLine(line);
                }
            } finally {
                br.close();
            }
            return new ExecResult(proc.waitFor(), out.toString());
        } catch (Throwable t) {
            String msg = "Shell error: " + t.getMessage();
            if (listener != null) listener.onLine(msg);
            return new ExecResult(1, msg);
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    public static boolean copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) {
                dst.getParentFile().mkdirs();
            }
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }
}
