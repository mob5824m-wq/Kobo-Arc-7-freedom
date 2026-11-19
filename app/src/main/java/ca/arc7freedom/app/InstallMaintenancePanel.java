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
public final class InstallMaintenancePanel {
    private final Activity host;
    public final LinearLayout view;
    private final Handler ui = new Handler();
    private TextView report, summary;
    private Button next;
    private boolean choosing, resumed, closed;
    private int choiceRequest;
    private String installingPackage;
    private File staged;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            summary.setText(host.getSharedPreferences(InstallMaintenanceService.PREFS, Activity.MODE_PRIVATE)
                    .getString("summary", "Waiting for maintenance"));
            report.setText(InstallMaintenanceService.report(host));
            if (resumed) ui.postDelayed(this, 1500);
        }
    };

    public InstallMaintenancePanel(Activity host, Bundle saved) {
        this.host = host;
        if (saved != null) {
            installingPackage = saved.getString("installing");
            String path = saved.getString("staged");
            if (path != null) staged = new File(path);
        }
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16, 12, 16, 12);
        TextView help = new TextView(host);
        help.setText("Install / Repair All Patches\n\nEvery full install method uses this same offline patch bundle: Home, stores, YouTube and all default apps. Root installs updates automatically. Without root, confirm each APK below; system-only steps stay pending. Key mismatches never trigger an uninstall. Flashing, wiping and optional conversion are NOT maintenance steps.");
        root.addView(help);
        summary = new TextView(host); root.addView(summary);
        Button retry = Arc7Skin.secondaryButton(host, Arc7Skin.current(host), ""); retry.setText("Apply / Retry All Patches");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { InstallMaintenanceService.request(host, "manual retry"); }
        });
        root.addView(retry);
        next = Arc7Skin.secondaryButton(host, Arc7Skin.current(host), ""); next.setText("Install pending apps (Android confirmations)");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { installNext(); }
        });
        root.addView(next);
        report = new TextView(host); report.setTextIsSelectable(true);
        root.addView(report);
        view = root;
    }
    public void resume() { if (closed) return; resumed = true; ui.removeCallbacks(refresh); refresh.run(); }
    public void pause() { resumed = false; ++choiceRequest; choosing = false; next.setEnabled(true); ui.removeCallbacks(refresh); }
    public void close() { closed = true; pause(); }
    public void save(Bundle out) {
        out.putString("installing", installingPackage);
        if (staged != null) out.putString("staged", staged.getAbsolutePath());
    }

    private void installNext() {
        if (closed || !resumed || host.isFinishing() || choosing || installingPackage != null) return;
        final int request=++choiceRequest;
        choosing = true; next.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final BundledAppInstaller installer = new BundledAppInstaller(host, new RootShell(host));
                    for (final BundledApk.Entry entry : installer.catalog()) {
                        // Blocks are visible in the report; don't hide every other update behind one bad key.
                        if (!"INSTALL".equals(installer.inspect(entry).code)) continue;
                        final File apk = installer.stage(entry);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (closed || !resumed || request!=choiceRequest || host.isFinishing()) { apk.delete(); return; }
                                choosing = false; next.setEnabled(true);
                                installingPackage = entry.pkg; staged = apk;
                                try { host.startActivityForResult(BundledAppInstaller.confirmationIntent(apk), 71); }
                                catch (RuntimeException error) {
                                    installingPackage = null; apk.delete(); staged = null;
                                    Toast.makeText(host, "Could not open Android installer: " + error.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                        return;
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (closed || !resumed || request!=choiceRequest || host.isFinishing()) return;
                            choosing = false; next.setEnabled(true);
                            InstallMaintenanceService.request(host, "confirmation queue checked");
                            Toast.makeText(host, "No more confirmable APKs. Check report for signing/storage/root restrictions.", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception error) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (closed || !resumed || request!=choiceRequest || host.isFinishing()) return;
                            choosing = false; next.setEnabled(true);
                            Toast.makeText(host, "Patch staging failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "Arc7ConfirmNextPatch").start();
    }

    public void activityResult(int requestCode, int result, Intent data) {
        if (requestCode != 71 || installingPackage == null) return;
        final String pkg = installingPackage;
        final int request=++choiceRequest;
        installingPackage = null; choosing = true; next.setEnabled(false);
        if (staged != null) { staged.delete(); staged = null; }
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    BundledAppInstaller installer = new BundledAppInstaller(host, new RootShell(host));
                    ok = "CURRENT".equals(installer.inspect(installer.find(pkg)).code);
                } catch (Exception ignored) {}
                final boolean verified = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (closed || request!=choiceRequest || host.isFinishing()) return;
                        choosing = false; next.setEnabled(true);
                        if (!resumed) return; // Never pop a new installer while another tab/app is in front.
                        if (verified) installNext();
                        else Toast.makeText(host, "Update cancelled or not verified. Queue paused; data retained. Retry when ready.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "Arc7VerifyConfirmedPatch").start();
    }
}
