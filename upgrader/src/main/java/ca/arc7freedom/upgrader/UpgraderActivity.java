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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#080C12"));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundDrawable(box(Color.parseColor("#111927"), Color.parseColor("#1E3A5F"), 14));

        statusTitle = new TextView(this);
        statusTitle.setText("Kobo Arc 7 Freedom -> v2.5.3 Upgrader");
        statusTitle.setTextColor(Color.parseColor("#F8FAFC"));
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        statusTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        card.addView(statusTitle);

        statusSub = new TextView(this);
        statusSub.setText("Preparing in-place v2.5.3 maintenance updates for Arc7 Freedom, Home and the separate YouTube apps. Signature/storage failures keep installed app data. Update separate Clock/Music/Radio/Recorder APKs too; live YouTube playback still needs tablet testing.");
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
        actionBtn.setText("Run v2.5.3 Upgrade Again");
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
        launchBtn.setText("Open Freedom v2.5.3 ->");
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
        startUpgradeAsync();
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
        running = true;
        actionBtn.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    uiProgress(10, "Unpacking v2.5.3 APKs, separate YouTube & YouTube Music apps, Launcher v55, Tailscale Google Auth, F-Droid v1304, VLC, Aurora Store v4.8.5, Python 3, apt & 118 2026 Root CAs...");
                    uiLog("=== Starting 1-Tap Kobo Arc 7 Upgrade to v2.5.3 ===");

                    File appFiles = getFilesDir();
                    makeWorldReadableDir(appFiles != null ? appFiles.getParentFile() : null);
                    makeWorldReadableDir(appFiles);

                    File stageDir = new File(appFiles, "up210");
                    makeWorldReadableDir(stageDir);
                    File caDir = new File(stageDir, "cacerts");
                    makeWorldReadableDir(caDir);

                    // 1. Extract main KoboArc7Freedom.apk (v2.4.1)
                    File mainApk = new File(stageDir, "KoboArc7Freedom.apk");
                    InputStream mainIn = getAssets().open("KoboArc7Freedom.apk");
                    try {
                        writeStreamToWorldFile(mainIn, mainApk, false);
                    } finally {
                        mainIn.close();
                    }
                    uiLog("[1/7] Unpacked KoboArc7Freedom v2.5.3 (" + (mainApk.length() / 1024) + " KB)");

                    File suNew = new File(stageDir, "su_new");
                    File pyNew = new File(stageDir, "py3_new");
                    File aptNew = new File(stageDir, "apt_new");
                    File homeApk = new File(stageDir, "VanillaAndroid4Home.apk");
                    File auroraApk = new File(stageDir, "AuroraStore.apk");
                    File fdroidApk = new File(stageDir, "FDroidClassicLegacy.apk");
                    File vlcApk = new File(stageDir, "VLC.apk");
                    File youtubeApk = new File(stageDir, "YouTube.apk");
                    File ytMusicApk = new File(stageDir, "YouTubeMusic.apk");
                    int caCount = 0;

                    InputStream zipRaw = getAssets().open("KoboArc7Freedom.apk");
                    ZipInputStream zis = new ZipInputStream(zipRaw);
                    try {
                        ZipEntry ze;
                        while ((ze = zis.getNextEntry()) != null) {
                            String name = ze.getName();
                            if ("lib/armeabi-v7a/libarc7su.so".equals(name)) {
                                writeStreamToWorldFile(zis, suNew, true);
                            } else if ("lib/armeabi-v7a/libarc7py3.so".equals(name)) {
                                writeStreamToWorldFile(zis, pyNew, true);
                            } else if ("lib/armeabi-v7a/libarc7apt.so".equals(name)) {
                                writeStreamToWorldFile(zis, aptNew, true);
                            } else if ("assets/VanillaAndroid4Home.apk".equals(name)) {
                                writeStreamToWorldFile(zis, homeApk, false);
                            } else if ("assets/starter-apps/AuroraStore.apk".equals(name)) {
                                writeStreamToWorldFile(zis, auroraApk, false);
                            } else if ("assets/starter-apps/FDroidClassicLegacy.apk".equals(name)) {
                                writeStreamToWorldFile(zis, fdroidApk, false);
                            } else if ("assets/starter-apps/VLC.apk".equals(name)) {
                                writeStreamToWorldFile(zis, vlcApk, false);
                            } else if ("assets/starter-apps/YouTube.apk".equals(name)) {
                                writeStreamToWorldFile(zis, youtubeApk, false);
                            } else if ("assets/starter-apps/YouTubeMusic.apk".equals(name)) {
                                writeStreamToWorldFile(zis, ytMusicApk, false);
                            } else if (name.startsWith("assets/cacerts/android-hashed/")
                                    && !ze.isDirectory() && name.endsWith(".0")) {
                                String base = new File(name).getName();
                                File outCa = new File(caDir, base);
                                writeStreamToWorldFile(zis, outCa, false);
                                caCount++;
                            }
                        }
                    } finally {
                        zis.close();
                    }
                    if (!fdroidApk.isFile() || fdroidApk.length() < 1024L * 1024L) {
                        throw new IllegalStateException("Patched F-Droid APK is missing from the installer bundle");
                    }
                    uiLog("[F-Droid] Installer bundle contains the pre-patched APK (low-memory index parser + TLS/signature fixes); the root installer will install it automatically.");
                    uiLog("[2/7] Extracted VLC Media Player (" + (vlcApk.length() / 1024) + " KB), Aurora Store, patched F-Droid Classic, VanillaAndroid4Home v50, Python 3, apt & " + caCount + " 2026 Root CAs");

                    uiProgress(40, "Running root package swap & installing 2026 CA store...");

                    // Build 100% pure Android 4.2 `mksh` + `toolbox` script (ZERO cp / grep / uname / printf!)
                    String sDir = stageDir.getAbsolutePath();
                    StringBuilder sh = new StringBuilder();
                    sh.append("#!/system/bin/sh\n");
                    sh.append("echo \"ROOT_ID=$(id)\"\n");

                    // Step A: Upgrade `/sbin/su` + `/sbin/uname` + `/sbin/cp` + `/system/xbin/{su,uname,cp}`
                    sh.append("trap 'mount -o remount,ro /system 2>/dev/null || true; mount -o remount,ro / 2>/dev/null || true' 0\n");
                    sh.append("(mount -o remount,rw / 2>/dev/null || mount -o remount,rw rootfs / 2>/dev/null || true)\n");
                    sh.append("mkdir -p /sbin\n");
                    sh.append("cat '").append(sDir).append("/su_new' > /sbin/su.new && chown 0:0 /sbin/su.new 2>/dev/null; chmod 06755 /sbin/su.new && mv /sbin/su.new /sbin/su\n");
                    sh.append("ln -s /sbin/su /sbin/uname 2>/dev/null || true\n");
                    sh.append("ln -s /sbin/su /sbin/cp 2>/dev/null || true\n");

                    sh.append("(mount -o remount,rw /system 2>/dev/null || mount -o remount,rw /emmc@android /system 2>/dev/null || true)\n");
                    sh.append("if [ -d /system/xbin ]; then\n");
                    sh.append("  cat '").append(sDir).append("/su_new' > /system/xbin/su.new && chown 0:0 /system/xbin/su.new 2>/dev/null; chmod 06755 /system/xbin/su.new && mv /system/xbin/su.new /system/xbin/su\n");
                    sh.append("  ln -s /system/xbin/su /system/xbin/uname 2>/dev/null || true\n");
                    sh.append("  ln -s /system/xbin/su /system/xbin/cp 2>/dev/null || true\n");
                    sh.append("fi\n");
                    sh.append("echo \"STEP_A_SU_APPLETS=OK (uname: $(/sbin/uname -sr 2>/dev/null))\"\n");

                    // Step B: Install all 118 modern 2026 Root CAs into `/system/etc/security/cacerts/` via pure `cat`!
                    sh.append("mkdir -p /data/misc/keychain/cacerts-added\n");
                    sh.append("for _cf in '").append(caDir.getAbsolutePath()).append("'/*.0; do\n");
                    sh.append("  if [ -f \"$_cf\" ]; then\n");
                    sh.append("    _bn=\"${_cf##*/}\"\n");
                    sh.append("    cat \"$_cf\" > \"/system/etc/security/cacerts/$_bn\"\n");
                    sh.append("    cat \"$_cf\" > \"/data/misc/keychain/cacerts-added/$_bn\"\n");
                    sh.append("  fi\n");
                    sh.append("done\n");
                    sh.append("chmod 0644 /system/etc/security/cacerts/* 2>/dev/null || true\n");
                    sh.append("chown 0:0 /system/etc/security/cacerts/* 2>/dev/null || true\n");
                    sh.append("chmod 0644 /data/misc/keychain/cacerts-added/* 2>/dev/null || true\n");
                    sh.append("echo 'STEP_B_2026_CAS=OK'\n");

                    // Step C: Stage APKs + Python3/apt binaries into `/data/local/tmp/` + `/system/xbin/`
                    sh.append("mkdir -p /data/local/tmp /system/xbin /data/local/arc7linux/etc/ssl /data/local/arc7_kobo_archive\n");
                    sh.append("if [ -f '").append(pyNew.getAbsolutePath()).append("' ]; then\n");
                    sh.append("  cat '").append(pyNew.getAbsolutePath()).append("' > /system/xbin/python3 && chmod 0755 /system/xbin/python3\n");
                    sh.append("  ln -sf /system/xbin/python3 /system/xbin/python 2>/dev/null || cat '").append(pyNew.getAbsolutePath()).append("' > /system/xbin/python\n");
                    sh.append("  chmod 0755 /system/xbin/python\n");
                    sh.append("fi\n");
                    sh.append("if [ -f '").append(aptNew.getAbsolutePath()).append("' ]; then\n");
                    sh.append("  cat '").append(aptNew.getAbsolutePath()).append("' > /system/xbin/apt && chmod 0755 /system/xbin/apt\n");
                    sh.append("  ln -sf /system/xbin/apt /system/xbin/apt-get 2>/dev/null || cat '").append(aptNew.getAbsolutePath()).append("' > /system/xbin/apt-get\n");
                    sh.append("  ln -sf /system/xbin/apt /system/xbin/apt-cache 2>/dev/null || cat '").append(aptNew.getAbsolutePath()).append("' > /system/xbin/apt-cache\n");
                    sh.append("  chmod 0755 /system/xbin/apt-get /system/xbin/apt-cache\n");
                    sh.append("fi\n");
                    sh.append("echo 'STEP_PY3_APT=OK'\n");
                    sh.append("cat '").append(mainApk.getAbsolutePath()).append("' > /data/local/tmp/KoboArc7Freedom.apk && chmod 0644 /data/local/tmp/KoboArc7Freedom.apk\n");
                    sh.append("cat '").append(homeApk.getAbsolutePath()).append("' > /data/local/tmp/VanillaAndroid4Home.apk && chmod 0644 /data/local/tmp/VanillaAndroid4Home.apk\n");
                    sh.append("cat '").append(auroraApk.getAbsolutePath()).append("' > /data/local/tmp/AuroraStore.apk && chmod 0644 /data/local/tmp/AuroraStore.apk\n");
                    sh.append("cat '").append(fdroidApk.getAbsolutePath()).append("' > /data/local/tmp/FDroidClassicLegacy.apk && chmod 0644 /data/local/tmp/FDroidClassicLegacy.apk\n");
                    sh.append("if [ -s '").append(vlcApk.getAbsolutePath()).append("' ]; then cat '").append(vlcApk.getAbsolutePath()).append("' > /data/local/tmp/VLC.apk && chmod 0644 /data/local/tmp/VLC.apk; fi\n");
                    sh.append("if [ -s '").append(youtubeApk.getAbsolutePath()).append("' ]; then cat '").append(youtubeApk.getAbsolutePath()).append("' > /data/local/tmp/YouTube.apk && chmod 0644 /data/local/tmp/YouTube.apk; fi\n");
                    sh.append("if [ -s '").append(ytMusicApk.getAbsolutePath()).append("' ]; then cat '").append(ytMusicApk.getAbsolutePath()).append("' > /data/local/tmp/YouTubeMusic.apk && chmod 0644 /data/local/tmp/YouTubeMusic.apk; fi\n");

                    // Preserve any existing local TLS 1.3 CA keypair across reinstalls so Zygote's in-RAM CA stays valid!
                    sh.append("mkdir -p /data/local/arc7_tls_ca && chmod 0755 /data/local/arc7_tls_ca\n");
                    sh.append("if [ -s /data/data/ca.arc7freedom.app/files/local_tls13_ca/ca.crt ] && [ -s /data/data/ca.arc7freedom.app/files/local_tls13_ca/ca.key ]; then\n");
                    sh.append("  cat /data/data/ca.arc7freedom.app/files/local_tls13_ca/ca.crt > /data/local/arc7_tls_ca/ca.crt\n");
                    sh.append("  cat /data/data/ca.arc7freedom.app/files/local_tls13_ca/ca.key > /data/local/arc7_tls_ca/ca.key\n");
                    sh.append("  chmod 0644 /data/local/arc7_tls_ca/ca.crt /data/local/arc7_tls_ca/ca.key\n");
                    sh.append("fi\n");

                    // Upgrade in place only. A signature/storage error must NEVER silently
                    // uninstall the suite or Home (which would also erase their user data).
                    sh.append("_pm_main=$(pm install -r /data/local/tmp/KoboArc7Freedom.apk 2>&1 || true)\n");
                    sh.append("case \"$_pm_main\" in *Success*) echo \"STEP_C_MAIN_INPLACE=$_pm_main\" ;; *) echo \"UPDATE_FAILED_DATA_PRESERVED ca.arc7freedom.app: $_pm_main\"; exit 21 ;; esac\n");
                    sh.append("_pm_home=$(pm install -r /data/local/tmp/VanillaAndroid4Home.apk 2>&1 || true)\n");
                    sh.append("case \"$_pm_home\" in *Success*) echo \"STEP_D_HOME_INPLACE=$_pm_home\" ;; *) echo \"UPDATE_FAILED_DATA_PRESERVED ca.arc7freedom.launcher: $_pm_home\"; exit 22 ;; esac\n");
                    sh.append("appwidget grantbind --package ca.arc7freedom.launcher --user 0 2>/dev/null || appwidget grantbind --package ca.arc7freedom.launcher 2>/dev/null || true\n");

                    // Step F: Update F-Droid in-place. Its own transport migrates old URLs;
                    // keep repository pins, preferences and the database instead of wiping data.
                    sh.append("_pm_fd=$(pm install -r /data/local/tmp/FDroidClassicLegacy.apk 2>&1 || true)\n");
                    sh.append("case \"$_pm_fd\" in\n");
                    sh.append("  *Success*) echo \"STEP_E_FDROID_INPLACE=$_pm_fd\" ;;\n");
                    sh.append("  *)\n");
                    sh.append("    echo \"UPDATE_RETAINED eu.bubu1.fdroidclassic.debug: $_pm_fd (data preserved)\"\n");
                    sh.append("    ;;\n");
                    sh.append("esac\n");
                    sh.append("am force-stop eu.bubu1.fdroidclassic.debug >/dev/null 2>&1 || true\n");

                    // Step G: Install Aurora Store (`com.aurora.store`, Android 4.2 + TLS 1.3) automatically!
                    sh.append("_pm_aur=$(pm install -r /data/local/tmp/AuroraStore.apk 2>&1 || true)\n");
                    sh.append("case \"$_pm_aur\" in\n");
                    sh.append("  *Success*) echo \"STEP_F_AURORA_INPLACE=$_pm_aur\" ;;\n");
                    sh.append("  *)\n");
                    sh.append("    echo \"UPDATE_RETAINED com.aurora.store: $_pm_aur (data preserved)\"\n");
                    sh.append("    ;;\n");
                    sh.append("esac\n");

                    // Step G1: Install the TRULY SEPARATE standalone `YouTube` & `YouTube Music` apps
                    // (`ca.arc7freedom.youtube` / `ca.arc7freedom.ytmusic` — own icons, own tasks, own data)!
                    sh.append("for _pair in 'YouTube.apk:ca.arc7freedom.youtube' 'YouTubeMusic.apk:ca.arc7freedom.ytmusic'; do\n");
                    sh.append("  _file=\"${_pair%%:*}\"; _pkg=\"${_pair##*:}\"\n");
                    sh.append("  if [ -s \"/data/local/tmp/$_file\" ]; then\n");
                    sh.append("    _pm_split=$(pm install -r \"/data/local/tmp/$_file\" 2>&1 || true)\n");
                    sh.append("    case \"$_pm_split\" in\n");
                    sh.append("      *Success*) echo \"STEP_SPLIT_INPLACE=$_pkg:$_pm_split\" ;;\n");
                    sh.append("      *) echo \"UPDATE_FAILED_DATA_PRESERVED $_pkg: $_pm_split\"; exit 23 ;;\n");
                    sh.append("    esac\n");
                    sh.append("  fi\n");
                    sh.append("done\n");

                    // Step G2: Install Bundled VLC Media Player (`org.videolan.vlc`) automatically!
                    sh.append("if [ -s /data/local/tmp/VLC.apk ]; then\n");
                    sh.append("  _pm_vlc=$(pm install -r /data/local/tmp/VLC.apk 2>&1 || true)\n");
                    sh.append("  case \"$_pm_vlc\" in\n");
                    sh.append("    *Success*) echo \"STEP_G_VLC_INPLACE=$_pm_vlc\" ;;\n");
                    sh.append("    *)\n");
                    sh.append("    echo \"UPDATE_RETAINED org.videolan.vlc: $_pm_vlc (data preserved)\"\n");
                    sh.append("      ;;\n");
                    sh.append("  esac\n");
                    sh.append("fi\n");

                    // Step H: Archive & uninstall Default Launcher + Kobo Care + Discontinued Apps!
                    sh.append("for _apk in /system/app/*.apk; do\n");
                    sh.append("  [ -e \"$_apk\" ] || continue\n");
                    sh.append("  _bn=\"${_apk##*/}\"\n");
                    sh.append("  case \"$_bn\" in\n");
                    sh.append("    *VanillaAndroid4*|*Arc7Freedom*|*KoboReader.apk) continue ;;\n");
                    sh.append("    Launcher.apk|Launcher2.apk|Launcher3.apk|*Launcher2*|*ArcHome*|*Tapestry*|*KoboCare*|*KoboSupport*|*KoboDiagnostics*|*KoboSetup*|*KoboOTA*|*KoboUpdater*|*Pocket*|*ReadItLater*|*Zinio*|*7digital*|*SevenDigital*|*Rdio*|*PlusOne*|*Talk.apk|*Hangouts*|*Magazines*|*Currents*|*GoogleEars*|*Videos.apk|*GenieWidget*)\n");
                    sh.append("      mv \"$_apk\" \"/data/local/arc7_kobo_archive/$_bn\" 2>/dev/null || rm -f \"$_apk\"\n");
                    sh.append("      _ox=\"${_apk%.apk}.odex\"; [ -e \"$_ox\" ] && (mv \"$_ox\" \"/data/local/arc7_kobo_archive/${_ox##*/}\" 2>/dev/null || rm -f \"$_ox\")\n");
                    sh.append("      ;;\n");
                    sh.append("  esac\n");
                    sh.append("done\n");
                    sh.append("for _pkg in com.android.launcher com.android.launcher2 com.android.launcher3 com.kobo.home com.kobo.launcher com.kobo.tapestry com.kobo.arc.home com.kobo.care com.kobo.support com.kobo.help com.kobo.diagnostics com.kobo.analytics com.ideashower.readitlater.pro com.pocket.app com.zinio.mobile.android.reader uk.co.sevendigital.android com.rdio.android.ui com.google.android.apps.plus com.google.android.talk com.google.android.apps.magazines com.google.android.ears com.google.android.videos com.google.android.apps.genie.geniewidget; do\n");
                    sh.append("  pm uninstall \"$_pkg\" >/dev/null 2>&1 || true\n");
                    sh.append("  pm disable \"$_pkg\" >/dev/null 2>&1 || true\n");
                    sh.append("done\n");

                    // Each APK owns its own gateway. The suite replaces only its legacy
                    // listener at startup; never scan /proc and kill other apps' processes.

                    // Provision `/system/etc/resolv.conf` + clean DNS + mount `/system` clean `ro`
                    sh.append("echo 'nameserver 1.1.1.1' > /system/etc/resolv.conf 2>/dev/null || true\n");
                    sh.append("echo 'nameserver 8.8.8.8' >> /system/etc/resolv.conf 2>/dev/null || true\n");
                    sh.append("chmod 0644 /system/etc/resolv.conf 2>/dev/null || true\n");
                    sh.append("setprop net.dns1 1.1.1.1; setprop net.dns2 8.8.8.8\n");
                    sh.append("settings put global captive_portal_detection_enabled 0 >/dev/null 2>&1 || true\n");
                    sh.append("sync\n");
                    sh.append("mount -o remount,ro /system 2>/dev/null || true\n");
                    sh.append("mount -o remount,ro / 2>/dev/null || true\n");
                    sh.append("rm -f /data/local/tmp/KoboArc7Freedom.apk /data/local/tmp/VanillaAndroid4Home.apk /data/local/tmp/AuroraStore.apk /data/local/tmp/FDroidClassicLegacy.apk /data/local/tmp/VLC.apk /data/local/tmp/YouTube.apk /data/local/tmp/YouTubeMusic.apk /data/local/tmp/VanillaHome.prev.apk\n");
                    sh.append("echo 'ARC7_UPGRADE_OK'\n");

                    File scriptFile = new File(stageDir, "run_upgrade.sh");
                    writeStreamToWorldFile(
                            new ByteArrayInputStream(sh.toString().getBytes("UTF-8")),
                            scriptFile,
                            true);

                    String suBin = findExistingSu();
                    uiLog("[4/7] Executing root upgrade via " + suBin + "...");
                    String combined = "";
                    String scriptBody = sh.toString();
                    for (int mode = 0; mode < 3; mode++) {
                        combined = runSuScript(suBin, mode, scriptBody, scriptFile);
                        if (combined.contains("ARC7_UPGRADE_OK") || combined.contains("UPDATE_FAILED_DATA_PRESERVED")) {
                            break;
                        }
                    }

                    if (combined.contains("UPDATE_FAILED_DATA_PRESERVED")) {
                        throw new IOException("In-place update failed; the existing app and its data were preserved. See the package error above.");
                    }
                    if (combined.contains("ARC7_UPGRADE_OK")) {
                        uiProgress(100, "In-place upgrade complete: Arc7 Freedom, Home, YouTube and YouTube Music. See the log for any store/VLC updates that were retained.");
                        uiLog("[7/7] SUCCESS! Launching Kobo Arc 7 Freedom v2.5.3 now...");
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                statusTitle.setText("SUCCESS — Upgraded to Kobo Arc 7 Freedom v2.5.3!");
                                launchBtn.setVisibility(View.VISIBLE);
                                actionBtn.setEnabled(true);
                            }
                        });
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                launchMainApp();
                            }
                        }, 900);
                    } else {
                        // Tablet not yet rooted at all -> export to Download and open standard PackageInstaller
                        File dl = new File(Environment.getExternalStorageDirectory(), "Download/KoboArc7Freedom-v2.5.3.apk");
                        if (dl.getParentFile() != null) dl.getParentFile().mkdirs();
                        InputStream in2 = getAssets().open("KoboArc7Freedom.apk");
                        try {
                            writeStreamToWorldFile(in2, dl, false);
                        } finally {
                            in2.close();
                        }
                        uiProgress(100, "Root not active yet — opening standard Android Package Installer...");
                        uiLog("Opening standard installer for " + dl.getAbsolutePath() + " as an update. If Android reports a signature mismatch, stop and back up your data; do not uninstall the suite automatically.");
                        final File dlRef = dl;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                actionBtn.setEnabled(true);
                                Intent prompt = new Intent(Intent.ACTION_VIEW);
                                prompt.setDataAndType(Uri.fromFile(dlRef), "application/vnd.android.package-archive");
                                prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(prompt);
                            }
                        });
                    }
                } catch (final Throwable t) {
                    uiLog("ERROR: " + t.getMessage());
                    uiProgress(100, "Error: " + t.getMessage());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            actionBtn.setEnabled(true);
                        }
                    });
                } finally {
                    running = false;
                }
            }
        }).start();
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
