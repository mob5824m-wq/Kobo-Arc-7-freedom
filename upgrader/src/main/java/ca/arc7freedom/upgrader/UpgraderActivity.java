package ca.arc7freedom.upgrader;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import ca.arc7freedom.app.BundledApk;
import java.io.FileInputStream;

/**
 * Separately installable, data-preserving in-place updater (ca.arc7freedom.upgrader).
 * Uses the existing root shell when available, or opens Android's confirmed installer.
 * Signature/storage failures are reported; they must not silently uninstall the suite,
 * Home, media apps or stores. This updater can itself be removed independently.
 */
public class UpgraderActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusTitle;
    private TextView statusSub;
    private ProgressBar progressBar;
    private TextView logView;
    private ScrollView logScroll;
    private Button actionBtn;
    private Button launchBtn;
    private volatile boolean running = false;
    private String expectedMainHash;
    private boolean awaitingInstall;

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            expectedMainHash = savedInstanceState.getString("expected_main_hash");
            awaitingInstall = savedInstanceState.getBoolean("awaiting_install", false);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#080C12"));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundDrawable(box(Color.parseColor("#111927"), Color.parseColor("#1E3A5F"), 14));

        statusTitle = new TextView(this);
        statusTitle.setText("Kobo Arc 7 Freedom -> v2.6.5 Upgrader");
        statusTitle.setTextColor(Color.parseColor("#F8FAFC"));
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        statusTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        card.addView(statusTitle);

        statusSub = new TextView(this);
        statusSub.setText("Installs Arc7 Freedom in place, then opens the SAME offline patch pipeline as USB, SD and OTA installs: Home, F-Droid, stores, YouTube and all 28 default apps. No app uninstall, stock-app removal or wipe. Without root, confirm each pending APK in the patch screen.");
        statusSub.setTextColor(Color.parseColor("#94A3B8"));
        statusSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusSub.setPadding(0, dp(6), 0, dp(12));
        card.addView(statusSub);

        Button ownAppInfo = new Button(this);
        ownAppInfo.setText("App Info / uninstall this upgrader");
        ownAppInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                // This package is the updater, NOT the suite embedded inside its assets.
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:ca.arc7freedom.upgrader")));
            }
        });
        card.addView(ownAppInfo);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(5);
        card.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);

        actionBtn = new Button(this);
        actionBtn.setText("Run v2.6.5 Upgrade Again");
        actionBtn.setTextColor(Color.WHITE);
        actionBtn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        actionBtn.setBackgroundDrawable(box(Color.parseColor("#0284C7"), 0, 10));
        actionBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        actionBtn.setEnabled(false);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpgradeAsync();
            }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(8);
        btnRow.addView(actionBtn, b1);

        launchBtn = new Button(this);
        launchBtn.setText("Open Freedom v2.6.5 ->");
        launchBtn.setTextColor(Color.WHITE);
        launchBtn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        launchBtn.setBackgroundDrawable(box(Color.parseColor("#059669"), 0, 10));
        launchBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        launchBtn.setVisibility(View.GONE);
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchMainApp();
            }
        });
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(launchBtn, b2);

        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(12);
        logScroll.setBackgroundDrawable(box(Color.parseColor("#0B111B"), Color.parseColor("#1E293B"), 12));
        logScroll.setPadding(dp(12), dp(12), dp(12), dp(12));

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#A7F3D0"));
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView);
        root.addView(logScroll, scrollLp);

        setContentView(root);
        if (!awaitingInstall) startUpgradeAsync();
        else uiProgress(40, "Waiting for Android's suite install confirmation...");
    }

    private void uiLog(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                logView.append(msg + "\n");
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void uiProgress(final int pct, final String sub) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                progressBar.setProgress(pct);
                statusSub.setText(sub);
            }
        });
    }

    private static void makeWorldReadableDir(File d) {
        if (d == null) return;
        if (!d.exists()) d.mkdirs();
        d.setReadable(true, false);
        d.setExecutable(true, false);
    }

    private static void writeStreamToWorldFile(InputStream in, File dst, boolean executable) throws Exception {
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } finally {
            fos.close();
        }
        dst.setReadable(true, false);
        if (executable) dst.setExecutable(true, false);
    }

    private String findExistingSu() {
        String[] candidates = new String[]{
                "/sbin/su", "/system/xbin/su", "/system/bin/su", "/su/bin/su"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return c;
        }
        return "su";
    }

    private String runSuScript(String suBin, int mode, String scriptBody, File scriptFile) {
        Process p = null;
        StringBuilder out = new StringBuilder();
        try {
            if (mode == 0) {
                // Mode 0: Direct STDIN pipe into `su` — exact mechanism proven working as `uid=0(root)`
                // on real Kobo Arc 7 hardware (zero file-path traversal issues before shell starts)!
                ProcessBuilder pb = new ProcessBuilder(suBin);
                pb.redirectErrorStream(true);
                p = pb.start();
                OutputStream os = p.getOutputStream();
                String payload = scriptBody + "\nexit $?\n";
                os.write(payload.getBytes("UTF-8"));
                os.flush();
                os.close();
            } else if (mode == 1) {
                ProcessBuilder pb = new ProcessBuilder(
                        suBin, "-c", "/system/bin/sh " + scriptFile.getAbsolutePath());
                pb.redirectErrorStream(true);
                p = pb.start();
                p.getOutputStream().close();
            } else {
                ProcessBuilder pb = new ProcessBuilder(
                        suBin, "0", "/system/bin/sh", scriptFile.getAbsolutePath());
                pb.redirectErrorStream(true);
                p = pb.start();
                p.getOutputStream().close();
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                uiLog("  " + line);
            }
            br.close();
            p.waitFor();
        } catch (Throwable t) {
            out.append("ERR: ").append(t.getMessage());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
        return out.toString();
    }

    private void startUpgradeAsync() {
        if (running) return;
        running = true; actionBtn.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    uiProgress(10, "Unpacking complete offline patch bundle...");
                    File appFiles = getFilesDir();
                    makeWorldReadableDir(appFiles == null ? null : appFiles.getParentFile());
                    makeWorldReadableDir(appFiles);
                    File stageDir = new File(appFiles, "maintenance");
                    makeWorldReadableDir(stageDir);
                    final File mainApk = new File(stageDir, "KoboArc7Freedom.apk");
                    InputStream in = getAssets().open("KoboArc7Freedom.apk");
                    try { writeStreamToWorldFile(in, mainApk, false); } finally { in.close(); }
                    expectedMainHash = BundledApk.digest(new FileInputStream(mainApk));
                    android.content.pm.PackageInfo bundled = getPackageManager().getPackageArchiveInfo(mainApk.getAbsolutePath(), 0);
                    if (bundled == null || !"ca.arc7freedom.app".equals(bundled.packageName)) throw new IOException("Invalid embedded suite APK");
                    try {
                        if (getPackageManager().getPackageInfo("ca.arc7freedom.app", 0).versionCode > bundled.versionCode) {
                            uiLog("Newer suite installed; keeping it and opening its patch pipeline.");
                            ui.post(new Runnable() { @Override public void run() { launchMainApp(); } });
                            return;
                        }
                    } catch (android.content.pm.PackageManager.NameNotFoundException missing) {}

                    // Exactly the same tested in-place pm wrapper as the suite's companion installer.
                    String body = BundledApk.installScript(mainApk.getAbsolutePath(),
                            "/data/local/tmp/arc7-maintenance-suite.apk", "ca.arc7freedom.app");
                    File script = new File(stageDir, "install.sh");
                    writeStreamToWorldFile(new ByteArrayInputStream(body.getBytes("UTF-8")), script, true);
                    String combined = "";
                    for (int mode = 0; mode < 3; mode++) {
                        combined = runSuScript(findExistingSu(), mode, body, script);
                        if (combined.contains("ARC7_APK_INSTALL_OK") || combined.contains("UPDATE_FAILED_DATA_PRESERVED")) break;
                    }
                    if (combined.contains("UPDATE_FAILED_DATA_PRESERVED")) {
                        throw new IOException("In-place suite update failed. Existing app/data retained; back up before a signing-key migration. See package error above.");
                    }
                    if (combined.contains("ARC7_APK_INSTALL_OK")) {
                        if (!installedMainMatches()) throw new IOException("Installed suite checksum not verified; not claiming success. Existing data retained.");
                        uiProgress(100, "Suite update verified. Opening shared patch pipeline for ALL companion/default apps and system repairs...");
                        uiLog("Suite APK verified. Remaining patch results and any root/signature/storage restrictions appear in Install / Repair All Patches.");
                        ui.post(new Runnable() { @Override public void run() { launchMainApp(); } });
                    } else {
                        uiProgress(40, "Root unavailable — Android must confirm the suite update first.");
                        uiLog("After installing, open Arc7 Freedom > Install / Repair All Patches. Android confirmations are still required for each companion without root.");
                        ui.post(new Runnable() {
                            @Override public void run() {
                                actionBtn.setEnabled(true);
                                Intent prompt = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                                prompt.setDataAndType(Uri.fromFile(mainApk), "application/vnd.android.package-archive");
                                prompt.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                                try {
                                    awaitingInstall = true; actionBtn.setEnabled(false);
                                    startActivityForResult(prompt, 73);
                                } catch (RuntimeException error) {
                                    awaitingInstall = false; actionBtn.setEnabled(true);
                                    uiLog("Could not open Android installer: " + error.getMessage() + ". Open the staged APK manually.");
                                }
                            }
                        });
                    }
                } catch (final Exception error) {
                    uiLog("ERROR: " + error.getMessage()); uiProgress(100, "Not complete: " + error.getMessage());
                    ui.post(new Runnable() { @Override public void run() { actionBtn.setEnabled(true); } });
                } finally { running = false; }
            }
        }, "Arc7UpgradeSuite").start();
    }

    private boolean installedMainMatches() {
        try { return expectedMainHash != null && expectedMainHash.equals(BundledApk.digest(new FileInputStream(
                getPackageManager().getApplicationInfo("ca.arc7freedom.app", 0).sourceDir))); }
        catch (Exception unavailable) { return false; }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("expected_main_hash", expectedMainHash); out.putBoolean("awaiting_install", awaitingInstall);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 73) return;
        awaitingInstall = false;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean verified = installedMainMatches();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (verified) launchMainApp();
                        else { uiLog("Suite install cancelled or not verified. No app was uninstalled. Retry after resolving the Android installer error."); actionBtn.setEnabled(true); }
                    }
                });
            }
        }, "VerifySuiteInstall").start();
    }

    private void launchMainApp() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("ca.arc7freedom.app", "ca.arc7freedom.app.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        } catch (Throwable t) {
            uiLog("Launch error: " + t.getMessage());
        }
    }
}
