package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Custom Android 4.2 APK Package Installer (`Arc7PackageInstallerActivity`).
 *
 * Replaces stock Android 4.2's barebones PackageInstaller with:
 *   1. Binary Manifest / DEX / Native ABI / Signature compatibility inspector (`Arc7ApkKit`)
 *   2. Automatic `SHA1withRSA` JAR `v1` + 4-byte alignment fixer (permanently eliminating
 *      `"Parse error: There is a problem parsing the package"` on `SHA256withRSA` / `v2`-only APKs!)
 *   3. Side-by-side `New Install` vs `Upgrade (vX -> vY)` & Signing Certificate check
 *   4. Categorized Permissions Inspector
 *   5. Fast silent Root `/data/local/tmp/` installation + Clean Signature-Conflict Reinstall
 *   6. Built-in `.apk` scanner across `Download/` & MicroSD when opened from the Home Launcher!
 */
public class Arc7PackageInstallerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell rootShell;
    private File activeApkFile;
    private String activePackageName = "";

    private ImageView appIconIv;
    private TextView appTitleTv;
    private TextView appPkgTv;
    private TextView compatBadgeTv;
    private TextView techDetailsTv;
    private LinearLayout permissionsBox;
    private LinearLayout scannedApksBox;
    private ProgressBar busyBar;
    private TextView statusLogTv;
    private Button installBtn;
    private Button cleanReinstallBtn;
    private Button openInstalledBtn;

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        rootShell = new RootShell(this);

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        Button rescanBtn = Arc7Skin.actionButton(this, "Scan APKs", pal.accentDark);
        rescanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                populateLocalApkList();
            }
        });

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_apk_installer,
                "Package Installer",
                "Android 4.2 Binary Validator • Auto JAR v1 Signer • Fast Root Installer",
                rescanBtn));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(22));

        // Selected APK Hero Card
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(15), dp(14), dp(15), dp(14));
        hero.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, pal, 16));

        LinearLayout hdrRow = new LinearLayout(this);
        hdrRow.setOrientation(LinearLayout.HORIZONTAL);
        hdrRow.setGravity(Gravity.CENTER_VERTICAL);

        appIconIv = new ImageView(this);
        appIconIv.setImageResource(R.drawable.ic_apk_installer);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        icLp.rightMargin = dp(14);
        hdrRow.addView(appIconIv, icLp);

        LinearLayout metaCol = new LinearLayout(this);
        metaCol.setOrientation(LinearLayout.VERTICAL);

        appTitleTv = new TextView(this);
        appTitleTv.setText("Select an APK below or open any .apk file");
        appTitleTv.setTextColor(pal.textPrimary);
        appTitleTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        appTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        metaCol.addView(appTitleTv);

        appPkgTv = new TextView(this);
        appPkgTv.setText("Supports file://, content://, Download/, and MicroSD packages");
        appPkgTv.setTextColor(pal.textMuted);
        appPkgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        metaCol.addView(appPkgTv);

        compatBadgeTv = new TextView(this);
        compatBadgeTv.setText("READY TO INSPECT");
        compatBadgeTv.setTextColor(Color.WHITE);
        compatBadgeTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        compatBadgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        compatBadgeTv.setPadding(dp(10), dp(4), dp(10), dp(4));
        compatBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFF0284C7, 10));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(6);
        metaCol.addView(compatBadgeTv, badgeLp);

        hdrRow.addView(metaCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(hdrRow);

        techDetailsTv = new TextView(this);
        techDetailsTv.setTextColor(pal.textMuted);
        techDetailsTv.setTypeface(Typeface.MONOSPACE);
        techDetailsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        techDetailsTv.setPadding(0, dp(12), 0, dp(10));
        hero.addView(techDetailsTv);

        busyBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setIndeterminate(true);
        busyBar.setVisibility(View.GONE);
        hero.addView(busyBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(10), 0, 0);

        installBtn = Arc7Skin.actionButton(this, "Install App", 0xFF059669);
        installBtn.setEnabled(false);
        installBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInstallAsync(false);
            }
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a1.rightMargin = dp(8);
        actionRow.addView(installBtn, a1);

        cleanReinstallBtn = Arc7Skin.actionButton(this, "Clean Reinstall", 0xFFD97706);
        cleanReinstallBtn.setVisibility(View.GONE);
        cleanReinstallBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInstallAsync(true);
            }
        });
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a2.rightMargin = dp(8);
        actionRow.addView(cleanReinstallBtn, a2);

        openInstalledBtn = Arc7Skin.actionButton(this, "Open App ->", 0xFF2563EB);
        openInstalledBtn.setVisibility(View.GONE);
        openInstalledBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(activePackageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } else {
                    Toast.makeText(Arc7PackageInstallerActivity.this,
                            "Package installed (no launcher activity exported).", Toast.LENGTH_SHORT).show();
                }
            }
        });
        actionRow.addView(openInstalledBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(actionRow);

        statusLogTv = new TextView(this);
        statusLogTv.setTextColor(0xFFA7F3D0);
        statusLogTv.setTypeface(Typeface.MONOSPACE);
        statusLogTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLogTv.setPadding(0, dp(8), 0, 0);
        hero.addView(statusLogTv);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        body.addView(hero, cardLp);

        // Permissions Card
        LinearLayout permCard = new LinearLayout(this);
        permCard.setOrientation(LinearLayout.VERTICAL);
        permCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        permCard.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 14));

        TextView permHdr = new TextView(this);
        permHdr.setText("Requested Permissions");
        permHdr.setTextColor(pal.textPrimary);
        permHdr.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        permHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        permCard.addView(permHdr);

        permissionsBox = new LinearLayout(this);
        permissionsBox.setOrientation(LinearLayout.VERTICAL);
        permissionsBox.setPadding(0, dp(8), 0, 0);
        permCard.addView(permissionsBox);
        body.addView(permCard, cardLp);

        // Local APKs on Device Card
        LinearLayout localCard = new LinearLayout(this);
        localCard.setOrientation(LinearLayout.VERTICAL);
        localCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        localCard.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 14));

        TextView localHdr = new TextView(this);
        localHdr.setText("APK Packages Found on Device (Tap Any Package to Inspect & Install)");
        localHdr.setTextColor(pal.textPrimary);
        localHdr.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        localHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        localCard.addView(localHdr);

        scannedApksBox = new LinearLayout(this);
        scannedApksBox.setOrientation(LinearLayout.VERTICAL);
        scannedApksBox.setPadding(0, dp(8), 0, 0);
        localCard.addView(scannedApksBox);
        body.addView(localCard, cardLp);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);

        handleIncomingUri(getIntent());
        populateLocalApkList();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingUri(intent);
    }

    private void handleIncomingUri(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri u = intent.getData();
        if ("file".equalsIgnoreCase(u.getScheme()) && u.getPath() != null) {
            inspectSelectedApk(new File(u.getPath()));
            return;
        }
        // Copy `content://` stream into staging file so binary inspector + signer can read it
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            File stage = new File(getCacheDir(), "incoming_package.apk");
            in = getContentResolver().openInputStream(u);
            if (in != null) {
                fos = new FileOutputStream(stage);
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
                inspectSelectedApk(stage);
            }
        } catch (Throwable t) {
            statusLogTv.setText("Could not open URI: " + t.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    private void inspectSelectedApk(File apkFile) {
        activeApkFile = apkFile;
        openInstalledBtn.setVisibility(View.GONE);
        statusLogTv.setText("");
        permissionsBox.removeAllViews();

        Arc7ApkKit.ApkInspection info = Arc7ApkKit.inspectApk(apkFile);
        activePackageName = info.packageName;

        String label = apkFile.getName();
        String verName = "";
        Drawable icon = null;
        String[] perms = null;

        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(
                    apkFile.getAbsolutePath(), PackageManager.GET_PERMISSIONS);
            if (pi != null) {
                if (pi.packageName != null && pi.packageName.length() > 0) {
                    activePackageName = pi.packageName;
                }
                verName = pi.versionName != null ? pi.versionName : "";
                perms = pi.requestedPermissions;
                ApplicationInfo ai = pi.applicationInfo;
                if (ai != null) {
                    ai.sourceDir = apkFile.getAbsolutePath();
                    ai.publicSourceDir = apkFile.getAbsolutePath();
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null) label = cs.toString();
                    icon = pm.getApplicationIcon(ai);
                }
            }
        } catch (Throwable ignored) {}

        appIconIv.setImageDrawable(Arc7Skin.loadReskinnedAppIcon(this, activePackageName, "", icon));
        appTitleTv.setText(label + (verName.length() > 0 ? "  v" + verName : ""));
        appPkgTv.setText(activePackageName + String.format(Locale.US, "  •  %.2f MB", apkFile.length() / 1048576.0));

        // Check existing installed package
        String existingStatus = "New installation";
        boolean alreadyInstalled = false;
        try {
            PackageInfo cur = getPackageManager().getPackageInfo(activePackageName, 0);
            alreadyInstalled = true;
            existingStatus = "Upgrade installed v" + cur.versionName + " (" + cur.versionCode
                    + ") -> (" + info.versionCode + ")";
        } catch (Throwable ignored) {}

        if (info.isCompatibleWithAndroid42()) {
            compatBadgeTv.setText(info.hasValidAndroid42V1Sig
                    ? "VERIFIED ANDROID 4.2 READY"
                    : "AUTO RE-SIGN JAR v1 ENABLED");
            compatBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFF059669, 10));
            installBtn.setEnabled(true);
            installBtn.setText(alreadyInstalled ? "Update App" : "Install App");
            cleanReinstallBtn.setVisibility(alreadyInstalled ? View.VISIBLE : View.GONE);
        } else {
            compatBadgeTv.setText("INCOMPATIBLE WITH ANDROID 4.2");
            compatBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFFDC2626, 10));
            installBtn.setEnabled(false);
            cleanReinstallBtn.setVisibility(View.GONE);
        }

        techDetailsTv.setText(
                "Status      : " + existingStatus + "\n"
                + "Min / Tgt   : API " + info.minSdkVersion + " / API " + info.targetSdkVersion
                + " (Kobo Arc 7 = Android 4.2.2 / API 17)\n"
                + "Dalvik DEX  : DEX 0" + info.highestDexVersion
                + (info.highestDexVersion <= 35 ? " (Native Dalvik 035 OK)" : " (Requires Android 7.0+ ART!)") + "\n"
                + "CPU ABI     : " + (!info.hasNativeLibs ? "Universal Java (100% ARMv7 compatible)"
                        : (info.supportsArmv7 ? "ARMv7 / ARM 32-bit OK" : "Incompatible ABI (64-bit / x86 only)")) + "\n"
                + "JAR v1 Sig  : " + (info.hasValidAndroid42V1Sig
                        ? "Valid SHA1withRSA v1 present"
                        : "Missing SHA1-RSA v1 -> Will auto-sign before installing!")
                + (info.errorReason != null ? ("\nReason      : " + info.errorReason) : ""));

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        if (perms == null || perms.length == 0) {
            TextView none = new TextView(this);
            none.setText("✓ No special Android system permissions requested.");
            none.setTextColor(0xFF34D399);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            permissionsBox.addView(none);
        } else {
            for (int i = 0; i < perms.length; i++) {
                String p = perms[i];
                String shortP = p.startsWith("android.permission.") ? p.substring(19) : p;
                TextView row = new TextView(this);
                boolean sensitive = shortP.contains("CAMERA") || shortP.contains("RECORD_AUDIO")
                        || shortP.contains("LOCATION") || shortP.contains("CONTACTS") || shortP.contains("SMS");
                row.setText((sensitive ? "⚠  " : "•  ") + shortP);
                row.setTextColor(sensitive ? 0xFFFBBF24 : pal.textMuted);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                row.setPadding(0, dp(2), 0, dp(2));
                permissionsBox.addView(row);
            }
        }
    }

    private void startInstallAsync(final boolean cleanUninstallFirst) {
        if (activeApkFile == null) return;
        busyBar.setVisibility(View.VISIBLE);
        installBtn.setEnabled(false);
        cleanReinstallBtn.setEnabled(false);
        statusLogTv.setText("Preparing Android 4.2 package verification & installation...\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (cleanUninstallFirst && activePackageName.length() > 0 && rootShell.isRootAvailable()) {
                        logLine("Removing old conflicting signature for " + activePackageName + "...");
                        rootShell.execRoot("pm uninstall " + RootShell.shellQuote(activePackageName) + " 2>&1 || true", null);
                    }
                    final Arc7ApkKit.RootInstallOutcome outcome = Arc7ApkKit.stageAndInstallApk(
                            Arc7PackageInstallerActivity.this,
                            activeApkFile,
                            cleanUninstallFirst);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busyBar.setVisibility(View.GONE);
                            installBtn.setEnabled(true);
                            cleanReinstallBtn.setEnabled(true);
                            if (outcome.succeeded) {
                                compatBadgeTv.setText("INSTALLED SUCCESSFULLY");
                                compatBadgeTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(
                                        Arc7PackageInstallerActivity.this, 0xFF059669, 10));
                                openInstalledBtn.setVisibility(View.VISIBLE);
                                Toast.makeText(Arc7PackageInstallerActivity.this,
                                        "Installed " + activePackageName + "!", Toast.LENGTH_SHORT).show();
                            } else {
                                cleanReinstallBtn.setVisibility(View.VISIBLE);
                                statusLogTv.append("Result: " + outcome.output + "\nTip: Tap 'Clean Reinstall' if replacing an app signed by a different developer.\n");
                            }
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busyBar.setVisibility(View.GONE);
                            installBtn.setEnabled(true);
                            cleanReinstallBtn.setEnabled(true);
                            statusLogTv.append("Install error: " + t.getMessage() + "\n");
                        }
                    });
                }
            }
        }, "Arc7PkgInstallWorker").start();
    }

    private void logLine(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusLogTv.append("• " + msg + "\n");
            }
        });
    }

    private void populateLocalApkList() {
        scannedApksBox.removeAllViews();
        final Arc7Skin.Palette pal = Arc7Skin.current(this);
        List<File> found = new ArrayList<File>();
        String[] roots = new String[]{
                new File(Environment.getExternalStorageDirectory(), "Download").getAbsolutePath(),
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                "/storage/sdcard1/Download",
                "/storage/sdcard1"
        };
        for (int r = 0; r < roots.length; r++) {
            File d = new File(roots[r]);
            File[] children = d.listFiles();
            if (children == null) continue;
            for (int i = 0; i < children.length; i++) {
                File f = children[i];
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".apk") && !found.contains(f)) {
                    found.add(f);
                }
            }
        }
        Collections.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.valueOf(b.lastModified()).compareTo(Long.valueOf(a.lastModified()));
            }
        });
        if (found.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No .apk files currently sitting in Download/ or MicroSD root.");
            empty.setTextColor(pal.textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            scannedApksBox.addView(empty);
            return;
        }
        for (int i = 0; i < found.size(); i++) {
            final File f = found.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, pal, 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(7);

            TextView tv = new TextView(this);
            tv.setText(String.format(Locale.US, "%s  (%.1f MB)", f.getName(), f.length() / 1048576.0));
            tv.setTextColor(pal.textPrimary);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button pickBtn = Arc7Skin.actionButton(this, "Inspect", pal.accentDark);
            pickBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    inspectSelectedApk(f);
                }
            });
            row.addView(pickBtn);
            scannedApksBox.addView(row, rlp);
        }
        if (activeApkFile == null && !found.isEmpty()) {
            inspectSelectedApk(found.get(0));
        }
    }
}
