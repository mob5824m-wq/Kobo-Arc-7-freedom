package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Upgrade #5: Built-In Root App Manager, Bloatware Freezer & APK Backup Extractor
 * (`Arc7 App Manager`).
 */
public class Arc7AppManagerActivity extends Activity {

    private static final int REQUEST_UNINSTALL = 0xA741;

    private static class AppEntry {
        String label;
        String pkg;
        String sourceApk;
        boolean systemApp;
        boolean updatedSystemApp;
        boolean enabled;
    }

    private RootShell shell;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private LinearLayout listContainer;
    private EditText searchBox;
    private TextView countLabel;
    private int filterTab = 0; // 0=User, 1=System, 2=Frozen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shell = new RootShell(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(8), dp(10), dp(8));
        top.setBackgroundColor(Color.parseColor("#1E293B"));

        top.addView(actionBtn("User Apps", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) { filterTab = 0; renderList(); }
        }));
        top.addView(actionBtn("System Apps", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { filterTab = 1; renderList(); }
        }));
        top.addView(actionBtn("Frozen / Disabled", "#7C3AED", new View.OnClickListener() {
            @Override public void onClick(View v) { filterTab = 2; renderList(); }
        }));

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Filter app name or package...");
        searchBox.setHintTextColor(Color.parseColor("#64748B"));
        searchBox.setTextColor(Color.WHITE);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        searchBox.setBackgroundColor(Color.parseColor("#0F172A"));
        searchBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { renderList(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.rightMargin = dp(6);
        top.addView(searchBox, slp);

        top.addView(actionBtn("Trim All Caches", "#16A34A", new View.OnClickListener() {
            @Override public void onClick(View v) { trimAllCaches(); }
        }));
        top.addView(actionBtn("Close", "#64748B", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        root.addView(top);

        countLabel = new TextView(this);
        countLabel.setTextColor(Color.parseColor("#94A3B8"));
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        countLabel.setPadding(dp(14), dp(6), dp(14), dp(4));
        root.addView(countLabel);

        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(16));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        reloadPackagesAsync();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNINSTALL) {
            Toast.makeText(this, "Refreshing installed apps…", Toast.LENGTH_SHORT).show();
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reloadPackagesAsync();
                }
            }, 500L);
        }
    }

    private void reloadPackagesAsync() {
        countLabel.setText("Scanning installed Android 4.2 packages...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> infos = pm.getInstalledApplications(0);
                final List<AppEntry> built = new ArrayList<AppEntry>();
                for (ApplicationInfo ai : infos) {
                    AppEntry e = new AppEntry();
                    CharSequence cs = pm.getApplicationLabel(ai);
                    e.label = cs != null ? cs.toString() : ai.packageName;
                    e.pkg = ai.packageName;
                    e.sourceApk = ai.sourceDir;
                    e.systemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    e.updatedSystemApp = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                    e.enabled = ai.enabled;
                    built.add(e);
                }
                Collections.sort(built, new Comparator<AppEntry>() {
                    @Override
                    public int compare(AppEntry a, AppEntry b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(built);
                        renderList();
                    }
                });
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void renderList() {
        listContainer.removeAllViews();
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.US);
        int shown = 0;

        for (final AppEntry app : allApps) {
            if (filterTab == 0 && (app.systemApp || !app.enabled)) continue;
            if (filterTab == 1 && !app.systemApp) continue;
            if (filterTab == 2 && app.enabled) continue;
            if (!q.isEmpty() && !app.label.toLowerCase(Locale.US).contains(q)
                    && !app.pkg.toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            shown++;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(7));
            bg.setColor(Color.parseColor("#1E293B"));
            bg.setStroke(dp(1), Color.parseColor(app.enabled ? "#334155" : "#9333EA"));
            row.setBackgroundDrawable(bg);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(app.label + (app.enabled ? "" : "  [FROZEN]"));
            t1.setTextColor(app.enabled ? Color.WHITE : Color.parseColor("#D8B4FE"));
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            col.addView(t1);

            TextView t2 = new TextView(this);
            t2.setText(app.pkg + (app.systemApp ? "  •  System" : "  •  User"));
            t2.setTextColor(Color.parseColor("#94A3B8"));
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            col.addView(t2);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button manage = actionBtn("Manage", "#0284C7", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showManageDialog(app);
                }
            });
            row.addView(manage);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            listContainer.addView(row, lp);
        }
        countLabel.setText("Showing " + shown + " package(s) — Manage apps to Uninstall, Export APK Backup, Freeze/Unfreeze, Launch, or Clear Data.");
    }

    private void showManageDialog(final AppEntry app) {
        final String freezeLabel = app.enabled ? "Freeze Background App (`pm disable`)" : "Unfreeze App (`pm enable`)";
        final String uninstallBlockReason = getUninstallBlockReason(app);
        final boolean canUninstall = uninstallBlockReason == null;
        final String uninstallLabel = canUninstall
                ? (app.updatedSystemApp ? "Remove System App Updates" : "Uninstall App")
                : (app.systemApp && !app.updatedSystemApp
                    ? "Uninstall unavailable (preinstalled app)"
                    : "Uninstall blocked (Android safety)" );
        String[] actions = new String[]{
            "Launch App Now",
            "Export .APK Backup to Storage (`APK-Backups/`)",
            freezeLabel,
            "Clear App Data & Cache (`pm clear`)",
            uninstallLabel
        };
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(app.label + " (" + app.pkg + ")")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            Intent launch = getPackageManager().getLaunchIntentForPackage(app.pkg);
                            if (launch != null) startActivity(launch);
                            else Toast.makeText(Arc7AppManagerActivity.this, "No launcher activity.", Toast.LENGTH_SHORT).show();
                        } else if (which == 1) {
                            exportApkBackup(app);
                        } else if (which == 2) {
                            toggleFreezeApp(app);
                        } else if (which == 3) {
                            runRootPkgCmd("pm clear " + app.pkg, "Cleared app data for " + app.label);
                        } else if (which == 4) {
                            if (canUninstall) {
                                confirmUninstall(app);
                            } else {
                                new AlertDialog.Builder(Arc7AppManagerActivity.this, AlertDialog.THEME_HOLO_DARK)
                                        .setTitle("App cannot be uninstalled")
                                        .setMessage(uninstallBlockReason)
                                        .setPositiveButton("OK", null)
                                        .show();
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String getUninstallBlockReason(AppEntry app) {
        if (app == null || app.pkg == null) return "The selected package name is invalid.";
        if (app.systemApp && !app.updatedSystemApp) {
            return "This is a preinstalled system app. Android does not allow removing its system image from App Manager. "
                    + "Use Freeze / Unfreeze if you want to disable it safely.";
        }
        if (isCriticalUninstallPackage(app.pkg)) {
            return "This package is required for Android, device drivers, the Package Installer, or the only Home launcher. "
                    + "It is protected to prevent a boot or navigation failure.";
        }
        return null;
    }

    private void confirmUninstall(final AppEntry app) {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle((app.updatedSystemApp ? "Remove updates for " : "Uninstall ") + app.label + "?")
                .setMessage("Android will show its package confirmation next. "
                        + (app.updatedSystemApp
                            ? "This removes the installed update and restores the factory version."
                            : "Uninstalling removes the app and its private data. Export an APK backup first if you may want it later."))
                .setNegativeButton("Cancel", null)
                .setPositiveButton(app.updatedSystemApp ? "Remove Updates" : "Continue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        launchUninstaller(app);
                    }
                })
                .show();
    }

    private void launchUninstaller(final AppEntry app) {
        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        uninstall.setData(Uri.fromParts("package", app.pkg, null));
        uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (uninstall.resolveActivity(getPackageManager()) == null) {
            uninstall.setAction(Intent.ACTION_DELETE);
        }
        if (uninstall.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "Android Package Installer is unavailable; no app was removed.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivityForResult(uninstall, REQUEST_UNINSTALL);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not open Android's uninstall confirmation: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportApkBackup(final AppEntry app) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String sd = TabletOptimizerManager.findMicroSdRoot();
                File dir = new File(sd != null ? sd : Environment.getExternalStorageDirectory().getAbsolutePath(),
                        "Download/APK-Backups");
                dir.mkdirs();
                final File dst = new File(dir, app.pkg + ".apk");
                final boolean ok = RootShell.copyFile(new File(app.sourceApk), dst);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(Arc7AppManagerActivity.this,
                                ok ? ("Exported APK -> " + dst.getAbsolutePath()) : "Failed copying APK",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void toggleFreezeApp(final AppEntry app) {
        if (app.enabled && isCriticalBootEssentialPackage(app.pkg)) {
            new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                    .setTitle("Brick-Guard: Critical Android Package Protected!")
                    .setMessage("Freezing `" + app.pkg + "` would break Android 4.2's boot process, "
                            + "navigation bar, settings UI, keyboard, or Home screen!\n\n"
                            + "Arc7 Freedom automatically protects all core Android & hardware driver packages.")
                    .setPositiveButton("Got It (Keep Safe)", null)
                    .show();
            return;
        }
        String sub = app.enabled ? "disable" : "enable";
        runRootPkgCmd("pm " + sub + " " + app.pkg, (app.enabled ? "Froze " : "Re-enabled ") + app.label);
    }

    /**
     * Blocks uninstall requests that could remove Android's package installer, system UI,
     * hardware services, providers, keyboard, or the only enabled Home launcher. Other Arc7
     * standalone apps remain uninstallable like any other user-installed package.
     */
    private boolean isCriticalUninstallPackage(String pkg) {
        if (pkg == null) return true;
        String p = pkg.toLowerCase(Locale.US);
        if (p.equals("android")
                || p.equals("com.android.systemui")
                || p.equals("com.android.settings")
                || p.equals("com.android.packageinstaller")
                || p.equals("com.android.defcontainer")
                || p.equals("com.android.keychain")
                || p.equals("com.android.certinstaller")
                || p.equals("com.android.shell")
                || p.equals("com.android.phone")
                || p.equals("com.android.bluetooth")
                || p.equals("com.android.nfc")
                || p.startsWith("com.android.providers.")
                || p.startsWith("com.android.inputmethod")
                || p.startsWith("com.mediatek")
                || p.startsWith("com.nvidia")) {
            return true;
        }
        return isOnlyEnabledHomeLauncher(pkg);
    }

    /**
     * Prevents users from accidentally soft-bricking Android 4.2 by disabling core system_server,
     * SystemUI, Settings, PackageInstaller, InputMethod keyboard, hardware HALs, or the last Home launcher!
     */
    private boolean isCriticalBootEssentialPackage(String pkg) {
        if (pkg == null) return true;
        String p = pkg.toLowerCase(Locale.US);
        if (p.equals("android")
                || p.startsWith("ca.arc7freedom.")
                || p.equals("com.android.systemui")
                || p.equals("com.android.settings")
                || p.equals("com.android.packageinstaller")
                || p.equals("com.android.defcontainer")
                || p.equals("com.android.keychain")
                || p.equals("com.android.certinstaller")
                || p.equals("com.android.shell")
                || p.equals("com.android.phone")
                || p.equals("com.android.bluetooth")
                || p.equals("com.android.nfc")
                || p.startsWith("com.android.providers.")
                || p.startsWith("com.android.inputmethod")
                || p.startsWith("com.mediatek")
                || p.startsWith("com.nvidia")) {
            return true;
        }
        /* Never allow freezing the ONLY active CATEGORY_HOME launcher on the tablet! */
        return isOnlyEnabledHomeLauncher(pkg);
    }

    private boolean isOnlyEnabledHomeLauncher(String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        List<android.content.pm.ResolveInfo> homes = getPackageManager().queryIntentActivities(home, 0);
        if (homes == null) return false;
        boolean isHome = false;
        int enabledHomeCount = 0;
        for (android.content.pm.ResolveInfo ri : homes) {
            if (ri.activityInfo != null && ri.activityInfo.applicationInfo.enabled) {
                enabledHomeCount++;
                if (pkg.equals(ri.activityInfo.packageName)) isHome = true;
            }
        }
        return isHome && enabledHomeCount <= 1;
    }

    private void trimAllCaches() {
        runRootPkgCmd("pm trim-caches 8G 2>/dev/null || rm -rf /data/data/*/cache/* 2>/dev/null || true",
                "Trimmed all Android 4.2 application caches and freed internal eMMC storage!");
    }

    private void runRootPkgCmd(final String cmd, final String doneMsg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.ExecResult r = shell.execRoot(cmd, null);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(Arc7AppManagerActivity.this,
                                r.exitCode == 0 ? doneMsg : ("Root needed: " + r.stdout.trim()),
                                Toast.LENGTH_LONG).show();
                        reloadPackagesAsync();
                    }
                });
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private Button actionBtn(String text, String hex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(Color.parseColor(hex));
        b.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
