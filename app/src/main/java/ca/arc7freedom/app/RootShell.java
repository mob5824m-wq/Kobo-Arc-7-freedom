package ca.arc7freedom.app;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Bridges Java UI workflows with our native static ARMv7 binaries (`libarc7root.so`,
 * `libarc7su.so`, `libarc7tool.so`) and system root shells.
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

    private final Context appContext;
    private File binDir;

    public RootShell(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        prepareBinaries();
    }

    public synchronized void prepareBinaries() {
        binDir = new File(appContext.getFilesDir(), "bin");
        if (!binDir.exists()) binDir.mkdirs();

        String nativeDirPath = appContext.getApplicationInfo().nativeLibraryDir;
        String[] libs = new String[]{"libarc7root.so", "libarc7su.so", "libarc7tool.so"};
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

    private File resolveBinary(String name) {
        File inNativeLib = new File(appContext.getApplicationInfo().nativeLibraryDir, name);
        if (inNativeLib.exists() && inNativeLib.canExecute()) {
            return inNativeLib;
        }
        File inBin = new File(binDir, name);
        inBin.setExecutable(true, false);
        return inBin;
    }

    public String findWorkingSuBinary() {
        String[] candidates = new String[]{
                "/system/xbin/su",
                "/system/bin/su",
                "/sbin/su",
                getSuPayloadBinary().getAbsolutePath()
        };
        for (String c : candidates) {
            File f = new File(c);
            if (!f.exists()) continue;
            ExecResult r = execCommandArray(new String[]{c, "-c", "id"}, null);
            if (r.exitCode == 0 && r.stdout.contains("uid=0")) {
                return c;
            }
        }
        return null;
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
        // Confirm real uid=0 shell after exploit run
        String working = findWorkingSuBinary();
        if (working != null) {
            if (listener != null) listener.onLine("Verified root shell via " + working + " (uid=0)!");
            return new ExecResult(0, res.stdout);
        }
        return new ExecResult(res.exitCode == 0 ? 1 : res.exitCode, res.stdout);
    }

    public ExecResult execRoot(String shellScript, LineListener listener) {
        String su = findWorkingSuBinary();
        if (su == null) {
            return new ExecResult(126, "Root access is not active yet. Run Step 1 (Root Device) first.");
        }
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(su);
            pb.redirectErrorStream(true);
            proc = pb.start();

            OutputStream os = proc.getOutputStream();
            String wrapped = shellScript + "\nexit $?\n";
            os.write(wrapped.getBytes("UTF-8"));
            os.flush();
            os.close();

            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                if (listener != null) listener.onLine(line);
            }
            br.close();
            int code = proc.waitFor();
            return new ExecResult(code, out.toString());
        } catch (Throwable t) {
            String msg = "Root shell error: " + t.getMessage();
            if (listener != null) listener.onLine(msg);
            return new ExecResult(1, msg);
        } finally {
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
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                if (listener != null) listener.onLine(line);
            }
            br.close();
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
