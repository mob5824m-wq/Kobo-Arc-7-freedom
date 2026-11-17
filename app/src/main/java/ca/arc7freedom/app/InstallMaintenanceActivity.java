package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import java.io.File;

/** Visible, resumable fallback on unrooted tablets. Android still confirms each separate APK. */
public class InstallMaintenanceActivity extends Activity {
    private final Handler ui = new Handler();
    private TextView report, summary;
    private Button next;
    private boolean choosing, resumed;
    private String installingPackage;
    private File staged;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            summary.setText(getSharedPreferences(InstallMaintenanceService.PREFS, MODE_PRIVATE)
                    .getString("summary", "Waiting for maintenance"));
            report.setText(InstallMaintenanceService.report(InstallMaintenanceActivity.this));
            if (resumed) ui.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            installingPackage = saved.getString("installing");
            String path = saved.getString("staged");
            if (path != null) staged = new File(path);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16, 12, 16, 12);
        TextView help = new TextView(this);
        help.setText("Install / Repair All Patches\n\nEvery full install method uses this same offline patch bundle: Home, stores, YouTube and all 28 default apps. Root installs updates automatically. Without root, confirm each APK below; system-only steps stay pending. Key mismatches never trigger an uninstall. Flashing, wiping and optional conversion are NOT maintenance steps.");
        root.addView(help);
        summary = new TextView(this); root.addView(summary);
        Button retry = new Button(this); retry.setText("Apply / Retry All Patches");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { InstallMaintenanceService.request(InstallMaintenanceActivity.this, "manual retry"); }
        });
        root.addView(retry);
        next = new Button(this); next.setText("Install pending apps (Android confirmations)");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { installNext(); }
        });
        root.addView(next);
        ScrollView scroll = new ScrollView(this);
        report = new TextView(this); report.setTextIsSelectable(true); scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        InstallMaintenanceService.request(this, "patch screen opened");
    }
    @Override protected void onResume() { super.onResume(); resumed = true; ui.removeCallbacks(refresh); refresh.run(); }
    @Override protected void onPause() { resumed = false; ui.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("installing", installingPackage);
        if (staged != null) out.putString("staged", staged.getAbsolutePath());
    }

    private void installNext() {
        if (choosing || installingPackage != null) return;
        choosing = true; next.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final BundledAppInstaller installer = new BundledAppInstaller(InstallMaintenanceActivity.this, new RootShell(InstallMaintenanceActivity.this));
                    for (final BundledApk.Entry entry : installer.catalog()) {
                        // Blocks are visible in the report; don't hide every other update behind one bad key.
                        if (!"INSTALL".equals(installer.inspect(entry).code)) continue;
                        final File apk = installer.stage(entry);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                choosing = false; next.setEnabled(true);
                                if (isFinishing()) { apk.delete(); return; }
                                installingPackage = entry.pkg; staged = apk;
                                try { startActivityForResult(BundledAppInstaller.confirmationIntent(apk), 71); }
                                catch (RuntimeException error) {
                                    installingPackage = null; apk.delete(); staged = null;
                                    Toast.makeText(InstallMaintenanceActivity.this, "Could not open Android installer: " + error.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                        return;
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            choosing = false; next.setEnabled(true);
                            InstallMaintenanceService.request(InstallMaintenanceActivity.this, "confirmation queue checked");
                            Toast.makeText(InstallMaintenanceActivity.this, "No more confirmable APKs. Check report for signing/storage/root restrictions.", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception error) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            choosing = false; next.setEnabled(true);
                            Toast.makeText(InstallMaintenanceActivity.this, "Patch staging failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "Arc7ConfirmNextPatch").start();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 71 || installingPackage == null) return;
        final String pkg = installingPackage;
        installingPackage = null; choosing = true; next.setEnabled(false);
        if (staged != null) { staged.delete(); staged = null; }
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    BundledAppInstaller installer = new BundledAppInstaller(InstallMaintenanceActivity.this, new RootShell(InstallMaintenanceActivity.this));
                    ok = "CURRENT".equals(installer.inspect(installer.find(pkg)).code);
                } catch (Exception ignored) {}
                final boolean verified = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        choosing = false; next.setEnabled(true);
                        if (verified) installNext();
                        else Toast.makeText(InstallMaintenanceActivity.this, "Update cancelled or not verified. Queue paused; data retained. Retry when ready.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "Arc7VerifyConfirmedPatch").start();
    }
}
