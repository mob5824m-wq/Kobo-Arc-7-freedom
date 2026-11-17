package ca.arc7freedom.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/** One data-preserving installer for ALL companion/default apps, independent of install method. */
public final class BundledAppInstaller {
    private static final Object INSTALL_LOCK = new Object();
    private final Context context;
    private final RootShell shell;
    private final BundledApk.Assets assets;
    public static final class State {
        public final String code, detail;
        State(String code, String detail) { this.code = code; this.detail = detail; }
        public boolean done() { return "CURRENT".equals(code) || "NEWER_KEPT".equals(code) || "UPDATED".equals(code); }
    }

    public BundledAppInstaller(Context context, RootShell shell) {
        this.context = context.getApplicationContext(); this.shell = shell;
        assets = new BundledApk.Assets() {
            @Override public java.io.InputStream open(String name) throws IOException {
                return BundledAppInstaller.this.context.getAssets().open(name);
            }
        };
    }
    public List<BundledApk.Entry> catalog() throws IOException { return BundledApk.catalog(assets); }
    public BundledApk.Entry find(String pkg) throws IOException {
        for (BundledApk.Entry entry : catalog()) if (pkg.equals(entry.pkg)) return entry;
        throw new IOException("No bundled patch for " + pkg + ". Open Arc7 Freedom's Install / Repair All Patches screen.");
    }

    public State inspect(BundledApk.Entry entry) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo installed = pm.getPackageInfo(entry.pkg, 0);
            if (installed.versionCode > entry.version) return new State("NEWER_KEPT", "newer installed version retained");
            String digest = BundledApk.digest(new FileInputStream(pm.getApplicationInfo(entry.pkg, 0).sourceDir));
            String decision = BundledApk.decision(installed.versionCode, entry.version, true, entry.sha256.equals(digest));
            if ("CURRENT".equals(decision)) return new State("CURRENT", "signed APK bytes verified");
            // All bundled APKs use the permanent release key. A key mismatch needs a user-led
            // migration/backup, not the old destructive uninstall-and-retry fallback.
            if (pm.checkSignatures(context.getPackageName(), entry.pkg) != PackageManager.SIGNATURE_MATCH) {
                return new State("BLOCKED", "signing key differs; installed app/data retained. Back up before an explicit migration.");
            }
            return new State("INSTALL", "older or same-version patched APK needs an in-place update");
        } catch (PackageManager.NameNotFoundException missing) {
            return new State("INSTALL", "not installed");
        } catch (Exception unreadable) {
            return new State("BLOCKED", "could not inspect installed APK: " + unreadable.getMessage());
        }
    }

    public File stage(BundledApk.Entry entry) throws IOException {
        File dir = new File(context.getCacheDir(), "install-maintenance");
        File apk = BundledApk.extract(assets, entry, dir);
        PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (parsed == null || !entry.pkg.equals(parsed.packageName) || parsed.versionCode != entry.version) {
            apk.delete(); throw new IOException("Bundled APK identity mismatch: " + entry.label);
        }
        // Android 4.x's system installer needs directory traversal to read the 0644 APK.
        // No app data or preference file permissions are changed.
        new File(context.getApplicationInfo().dataDir).setExecutable(true, false);
        context.getCacheDir().setExecutable(true, false);
        dir.setExecutable(true, false);
        return apk;
    }

    public State apply(BundledApk.Entry entry, boolean rooted, TabletOptimizerManager.LogSink log) {
        synchronized (INSTALL_LOCK) {
            State state = inspect(entry);
            if (!"INSTALL".equals(state.code)) return state;
            if (!rooted) return new State("PENDING", "Android confirmation needed; open Install / Repair All Patches > Install pending apps");
            File apk = null;
            try {
                apk = stage(entry);
                String target = "/data/local/tmp/arc7-maintenance-" + entry.pkg + ".apk";
                RootShell.ExecResult result = shell.execRoot(BundledApk.installScript(apk.getAbsolutePath(), target, entry.pkg), null);
                if (log != null && result.stdout.trim().length() > 0) log.onLog(result.stdout.trim());
                State after = inspect(entry);
                // pm's output alone is not proof: read back the installed package and hash.
                if (result.exitCode == 0 && "CURRENT".equals(after.code)) return new State("UPDATED", "in-place update and installed checksum verified");
                return new State("BLOCKED", "in-place update not verified; existing data retained. " + result.stdout.trim());
            } catch (Exception error) {
                return new State("BLOCKED", "patch not installed; data retained: " + error.getMessage());
            } finally { if (apk != null) apk.delete(); }
        }
    }

    /** Explicit legacy install buttons also use the catalog/verification path, never different rules. */
    public boolean installRequested(String pkg, TabletOptimizerManager.LogSink log) {
        try {
            BundledApk.Entry entry = find(pkg);
            boolean rooted = shell.isRootAvailable();
            State state = apply(entry, rooted, log);
            if (log != null) log.onLog(state.code + " " + entry.label + ": " + state.detail);
            if ("PENDING".equals(state.code)) {
                Intent intent = new Intent(context, InstallMaintenanceActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
            return state.done();
        } catch (Exception error) {
            if (log != null) log.onLog("BLOCKED " + pkg + ": " + error.getMessage());
            return false;
        }
    }

    public static Intent confirmationIntent(File apk) {
        // ACTION_INSTALL_PACKAGE goes to Android's installer, not our ACTION_VIEW APK handler.
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        return intent;
    }
}
