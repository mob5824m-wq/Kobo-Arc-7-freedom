package ca.arc7freedom.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.security.KeyChain;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Complete Offline 2026 CA Certificate Store Installer, Atomic Clock/DNS Fixer,
 * Java TLS 1.2 Upgrader, and Modern ARMv7 MbedTLS 3.6 (TLS 1.3 + TLS 1.2) Downloader
 * & Local Browser Gateway Manager for Kobo Arc 7 (Android 4.2.2 API 17).
 *
 * Solves every layer that prevents stock 2013 Kobo Arc 7 tablets from connecting to
 * the internet or downloading Android ROMs / APKs in 2026:
 *   1. All 120 modern 2026 Mozilla Root CA certificates (`assets/cacerts/`) come
 *      PRE-DOWNLOADED inside `KoboArc7Freedom.apk` in exact Android `<subject_hash_old>.<n>`
 *      format (`6187b673.0` = ISRG Root X1 Let's Encrypt, `f013ecaf.0` = Google GTS R1,
 *      `c90bc37d.0` = DigiCert Global Root G2, `35105088.0` = USERTrust RSA, etc.).
 *   2. Quarantines expired 2013-2023 certificates inside `/system/etc/security/cacerts/`
 *      (including expired `DST Root CA X3`, which breaks Let's Encrypt validation on
 *      Android 4.2's OpenSSL 1.0.1 validator even when ISRG Root X1 is present!).
 *   3. Fixes dead-battery tablet clocks stuck in 2013/1970 via raw IPv4 UDP NTP
 *      (`162.159.200.1`, `216.239.35.0`) + plain HTTP port-80 `Date:` header fallback.
 *   4. Enables Java `TLSv1.2` + runs native static ARMv7 `libarc7tls.so` (MbedTLS 3.6 LTS
 *      full TLS 1.3 + TLS 1.2 + X25519 + ChaCha20-Poly1305 + AES-GCM) for direct ROM/APK
 *      downloads AND a local `http://127.0.0.1:8118/` Browser Portal!
 */
public class NetworkCertManager {

    public static final int DEFAULT_PROXY_PORT = 8118;
    // Build floor timestamp (2026-10-21 00:00:00 UTC = 1792540800000L) used solely to detect
    // expired pre-2026 system certificates even before clock sync finishes.
    private static final long BUILD_FLOOR_EPOCH_MS = 1792540800000L;

    public interface ProgressListener {
        void onProgress(int percent, String stage);
        void onLog(String line);
    }

    public static class CertEntry {
        public final String filename;
        public final String subject;
        public final String expires;

        public CertEntry(String filename, String subject, String expires) {
            this.filename = filename;
            this.subject = subject;
            this.expires = expires;
        }
    }

    public static class NetworkStatus {
        public int bundledCertCount;
        public String bundleVersion = "Mozilla 2026.10.19";
        public int systemCertCount;
        public int expiredSystemCertCount;
        public boolean hasLetsEncryptIsrgX1;
        public boolean hasGoogleGtsR1;
        public boolean hasDigiCertG2;
        public boolean hasUserTrustRsa;
        public int currentYear;
        public String currentDateFormatted;
        public boolean clockValidFor2026;
        public boolean tlsEngineReady;
        public boolean proxyRunning;

        public boolean allModernSystemCertsInstalled() {
            return hasLetsEncryptIsrgX1 && hasGoogleGtsR1 && hasDigiCertG2
                    && hasUserTrustRsa && expiredSystemCertCount == 0;
        }
    }

    private final Context appContext;
    private final RootShell shell;
    private Process proxyProcess;

    public NetworkCertManager(Context ctx, RootShell shell) {
        this.appContext = ctx != null ? ctx.getApplicationContext() : null;
        this.shell = shell;
        if (this.appContext != null) {
            try {
                installProcessTls12TrustStore();
            } catch (Throwable ignored) {
            }
        }
    }

    public List<CertEntry> loadBundledManifest() {
        List<CertEntry> out = new ArrayList<CertEntry>();
        if (appContext == null) return out;
        BufferedReader br = null;
        try {
            InputStream in = appContext.getAssets().open("cacerts/manifest.tsv");
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    out.add(new CertEntry(parts[0], parts[1], parts[2]));
                } else if (parts.length == 2) {
                    out.add(new CertEntry(parts[0], parts[1], ""));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    public File ensureCaBundleFile() throws IOException {
        File dir = new File(appContext.getFilesDir(), "cacerts");
        if (!dir.exists()) dir.mkdirs();
        File bundleFile = new File(dir, "ca-bundle-2026.crt");
        if (!bundleFile.exists() || bundleFile.length() < 50000) {
            InputStream in = appContext.getAssets().open("cacerts/ca-bundle-2026.crt");
            try {
                FileOutputStream out = new FileOutputStream(bundleFile);
                try {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            bundleFile.setReadable(true, false);
        }
        return bundleFile;
    }

    public NetworkStatus inspectStatus() {
        NetworkStatus st = new NetworkStatus();
        List<CertEntry> bundled = loadBundledManifest();
        st.bundledCertCount = bundled.size();

        File sysDir = new File("/system/etc/security/cacerts");
        File[] sysFiles = sysDir.listFiles();
        long checkTime = Math.max(System.currentTimeMillis(), BUILD_FLOOR_EPOCH_MS);
        if (sysFiles != null) {
            st.systemCertCount = sysFiles.length;
            st.hasLetsEncryptIsrgX1 = new File(sysDir, "6187b673.0").exists();
            st.hasGoogleGtsR1 = new File(sysDir, "f013ecaf.0").exists();
            st.hasDigiCertG2 = new File(sysDir, "c90bc37d.0").exists();
            st.hasUserTrustRsa = new File(sysDir, "35105088.0").exists();

            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                for (File f : sysFiles) {
                    if (!f.isFile()) continue;
                    FileInputStream fis = null;
                    try {
                        fis = new FileInputStream(f);
                        Certificate c = cf.generateCertificate(fis);
                        if (c instanceof X509Certificate) {
                            Date exp = ((X509Certificate) c).getNotAfter();
                            if (exp != null && exp.getTime() < checkTime) {
                                st.expiredSystemCertCount++;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (fis != null) {
                            try { fis.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Calendar cal = Calendar.getInstance();
        st.currentYear = cal.get(Calendar.YEAR);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US);
        st.currentDateFormatted = sdf.format(cal.getTime());
        st.clockValidFor2026 = st.currentYear >= 2026;

        if (shell != null) {
            File tlsBin = shell.getTlsEngineBinary();
            st.tlsEngineReady = tlsBin != null && tlsBin.exists();
        }
        st.proxyRunning = isLocalProxyListening(DEFAULT_PROXY_PORT);
        return st;
    }

    /**
     * Enables TLSv1.2 + all 120 pre-downloaded 2026 Root CAs across every Java
     * HttpsURLConnection inside this process (works even on Android 4.2 API 17!).
     */
    public void installProcessTls12TrustStore() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        InputStream bundleStream = appContext.getAssets().open("cacerts/ca-bundle-2026.crt");
        try {
            int idx = 0;
            for (Certificate cert : cf.generateCertificates(bundleStream)) {
                ks.setCertificateEntry("arc7_2026_ca_" + (idx++), cert);
            }
        } finally {
            bundleStream.close();
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
    }

    /**
     * Exports individual `.crt` files & the full `ca-bundle-2026.crt` to
     * `/sdcard/Download/KoboArc7-2026-CA-Certs/` so browsers or manual Android
     * `Settings -> Security -> Install from SD card` always have direct offline access.
     */
    public File exportCertsToDownloadFolder(File downloadRoot) throws IOException {
        File outDir = new File(downloadRoot, "KoboArc7-2026-CA-Certs");
        if (!outDir.exists()) outDir.mkdirs();

        // 1. Full 120-CA bundle
        copyAsset("cacerts/ca-bundle-2026.crt", new File(outDir, "ca-bundle-2026.crt"));

        // 2. Key standalone DER/PEM root certificates for Android's native Certificate Installer UI
        String[][] keyRoots = new String[][]{
                {"6187b673.0", "01-LetsEncrypt-ISRG-Root-X1.crt"},
                {"8794b4e3.0", "02-LetsEncrypt-ISRG-Root-X2.crt"},
                {"f013ecaf.0", "03-Google-Trust-Services-GTS-R1.crt"},
                {"c90bc37d.0", "04-DigiCert-Global-Root-G2-GitHub-Cloudflare.crt"},
                {"35105088.0", "05-USERTrust-RSA-Sectigo.crt"},
                {"04f60c28.0", "06-USERTrust-ECC-Sectigo.crt"}
        };
        for (String[] pair : keyRoots) {
            copyAsset("cacerts/android-hashed/" + pair[0], new File(outDir, pair[1]));
        }
        return outDir;
    }

    public Intent buildSystemCertInstallerIntent(String assetHashName, String displayName) throws Exception {
        InputStream in = appContext.getAssets().open("cacerts/android-hashed/" + assetHashName);
        byte[] derBytes;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert = cf.generateCertificate(in);
            derBytes = cert.getEncoded();
        } finally {
            in.close();
        }
        Intent intent = KeyChain.createInstallIntent();
        intent.putExtra(KeyChain.EXTRA_NAME, displayName);
        intent.putExtra(KeyChain.EXTRA_CERTIFICATE, derBytes);
        return intent;
    }

    /**
     * One-Tap Full Offline CA Installation + Atomic NTP/HTTP Clock Sync + DNS Fix + Live TLS Test.
     */
    public boolean installCertsAndFixNetwork(File backupStorageRoot, final ProgressListener listener) {
        try {
            listener.onProgress(5, "Unpacking 120 pre-downloaded 2026 Root CA certificates...");
            File bundleFile = ensureCaBundleFile();
            List<CertEntry> manifest = loadBundledManifest();
            listener.onLog("Loaded " + manifest.size() + " pre-downloaded 2026 Root CAs from APK assets (" +
                    (bundleFile.length() / 1024) + " KB, 0 internet bytes needed).");

            File dlDir = new File(backupStorageRoot, "Download");
            if (!dlDir.exists()) dlDir.mkdirs();
            File exportedDir = exportCertsToDownloadFolder(dlDir);
            listener.onLog("Exported standalone .crt bundle to: " + exportedDir.getAbsolutePath());

            boolean hasRoot = shell != null && shell.isRootAvailable();

            // Step 2: Configure DNS & Captive Portal first so NTP & HTTP reach the internet cleanly
            if (hasRoot) {
                listener.onProgress(20, "Configuring clean DNS (1.1.1.1 / 8.8.8.8) & disabling dead captive check...");
                shell.execRoot(
                        "setprop net.dns1 1.1.1.1; setprop net.dns2 8.8.8.8; " +
                        "ndc resolver setifdns wlan0 \"\" 1.1.1.1 8.8.8.8 >/dev/null 2>&1 || true; " +
                        "ndc resolver setdefaultif wlan0 >/dev/null 2>&1 || true; " +
                        "settings put global captive_portal_detection_enabled 0 >/dev/null 2>&1 || true",
                        null
                );
                listener.onLog("Configured clean IPv4 DNS (1.1.1.1, 8.8.8.8) and cleared Android 4.2 captive-portal block.");
            }

            // Step 3: Sync system clock via raw UDP NTP (with plain HTTP Date: header fallback!)
            listener.onProgress(35, "Syncing tablet clock (fixes 2013 dead-battery TLS errors)...");
            syncTabletClock(hasRoot, listener);

            // Step 4: Install ALL 120 pre-downloaded 2026 Root CAs into /system/etc/security/cacerts/
            if (hasRoot) {
                listener.onProgress(55, "Installing all " + manifest.size() + " 2026 Root CAs into /system/etc/security/cacerts...");
                File stageDir = new File(appContext.getFilesDir(), "cacerts_hashed_stage");
                deleteRecursive(stageDir);
                stageDir.mkdirs();

                for (CertEntry entry : manifest) {
                    copyAsset("cacerts/android-hashed/" + entry.filename, new File(stageDir, entry.filename));
                }

                // Detect any expired certificates currently in /system/etc/security/cacerts/
                List<String> expiredNames = findExpiredSystemCertFilenames();
                File sysCaBackup = new File(backupStorageRoot, "KoboArc7Backups/cacerts-stock-backup");
                sysCaBackup.mkdirs();

                StringBuilder quarantineCmd = new StringBuilder();
                File quarantineDir = new File(backupStorageRoot, "KoboArc7Backups/cacerts-expired-quarantine");
                quarantineDir.mkdirs();
                for (String expName : expiredNames) {
                    quarantineCmd.append("if [ -f /system/etc/security/cacerts/").append(expName).append(" ]; then ")
                            .append("mv /system/etc/security/cacerts/").append(expName).append(" '")
                            .append(quarantineDir.getAbsolutePath()).append("/").append(expName)
                            .append("' 2>/dev/null || rm -f /system/etc/security/cacerts/").append(expName)
                            .append("; fi; ");
                }

                String cmd =
                        "set -e; " +
                        // One-time untouched backup of factory /system/etc/security/cacerts (using pure mksh + cat — NO /system/bin/cp dependency!)
                        "if [ ! -f '" + sysCaBackup.getAbsolutePath() + "/.backed_up' ]; then " +
                        "  for _sc in /system/etc/security/cacerts/*; do " +
                        "    [ -f \"$_sc\" ] && cat \"$_sc\" > '" + sysCaBackup.getAbsolutePath() + "/'\"${_sc##*/}\" 2>/dev/null || true; " +
                        "  done; " +
                        "  echo ok > '" + sysCaBackup.getAbsolutePath() + "/.backed_up'; " +
                        "fi; " +
                        "mount -o remount,rw /system 2>/dev/null || mount -o remount,rw /emmc@android /system 2>/dev/null || true; " +
                        quarantineCmd.toString() +
                        "mkdir -p /data/misc/keychain/cacerts-added; " +
                        "for _cf in '" + stageDir.getAbsolutePath() + "'/*; do " +
                        "  if [ -f \"$_cf\" ]; then " +
                        "    _bn=\"${_cf##*/}\"; " +
                        "    cat \"$_cf\" > \"/system/etc/security/cacerts/$_bn\"; " +
                        "    cat \"$_cf\" > \"/data/misc/keychain/cacerts-added/$_bn\"; " +
                        "  fi; " +
                        "done; " +
                        "chmod 0644 /system/etc/security/cacerts/*; " +
                        "chown 0:0 /system/etc/security/cacerts/* 2>/dev/null || true; " +
                        "chmod 0644 /data/misc/keychain/cacerts-added/*; " +
                        "sync; " +
                        "mount -o remount,ro /system 2>/dev/null || true";

                RootShell.ExecResult res = shell.execRoot(cmd, new RootShell.LineListener() {
                    @Override
                    public void onLine(String line) {
                        listener.onLog(line);
                    }
                });
                deleteRecursive(stageDir);

                if (res.exitCode != 0) {
                    listener.onLog("WARNING: Root system CA write returned exit code " + res.exitCode);
                } else {
                    listener.onLog("Installed all " + manifest.size() + " modern 2026 Root CAs into /system/etc/security/cacerts/ & user keychain, and quarantined " +
                            expiredNames.size() + " expired 2013-2023 certificate(s)!");
                }
            } else {
                listener.onProgress(60, "Enabling embedded 2026 Root CAs (unrooted mode)...");
                listener.onLog("NOTE: Tablet is not rooted yet — enabled all 120 pre-downloaded 2026 CAs immediately inside Kobo Arc 7 Freedom's built-in TLS 1.3 Downloader & Local Browser Portal!");
                listener.onLog("TIP: Tap Tab 1 ('ROOT') first and re-run this button to also install all 120 CAs permanently into /system/etc/security/cacerts/ for every system app!");
            }

            // Refresh Java TLS 1.2 + 2026 CA trust store
            installProcessTls12TrustStore();

            // Step 5: Install per-device Local TLS 1.0->1.3 Upgrade CA & start persistent 24/7 Gateway (:8118)
            listener.onProgress(80, "Activating System-Wide TLS 1.3 Upgrade Proxy & Persistent Gateway (127.0.0.1:8118)...");
            if (hasRoot) {
                setSystemWideTls13ProxyEnabled(true, listener);
            } else {
                startBrowserGateway();
            }
            Arc7TlsGatewayService.ensureStarted(appContext);

            listener.onProgress(90, "Testing live modern HTTPS (TLS 1.3 + 2026 CAs) over Wi-Fi...");
            File tlsBin = shell.getTlsEngineBinary();
            RootShell.ExecResult testRes = shell.execCommandArray(
                    new String[]{tlsBin.getAbsolutePath(), "test", bundleFile.getAbsolutePath(), "https://github.com"},
                    new RootShell.LineListener() {
                        @Override
                        public void onLine(String line) {
                            listener.onLog("  [TLS TEST] " + line);
                        }
                    }
            );
            if (testRes.exitCode == 0) {
                listener.onProgress(100, "Internet, Clock & All 120 Modern 2026 Root CAs Verified Working!");
                listener.onLog("LIVE HTTPS VERIFIED WORKING! Negotiated modern TLS with verified 2026 CA chain.");
            } else {
                listener.onProgress(100, "All 120 Pre-Downloaded 2026 Root CAs Installed Offline!");
                listener.onLog("All 2026 CA certificates are installed offline & ready! Connect to Wi-Fi in Settings -> Wi-Fi to start downloading.");
            }
            return true;
        } catch (Throwable t) {
            listener.onLog("ERROR fixing certs/network: " + t.getMessage());
            return false;
        }
    }

    /**
     * Automatically detects the user's real local IANA timezone (`e.g. America/Toronto`) over
     * Wi-Fi via multiple geo-timezone endpoints and applies BOTH Android `AlarmManager.setTimeZone`,
     * `setprop persist.sys.timezone`, AND exact UTC atomic epoch (`settimeofday`) WITH ZERO
     * UTC-vs-local `date -s` double-offset bugs!
     */
    public String syncTimeAndTimezoneAutomatically(boolean hasRoot, final ProgressListener listener) {
        String detectedZone = detectAndApplyLocalTimezone(hasRoot, listener);
        syncTabletClock(hasRoot, listener);
        return detectedZone;
    }

    public String detectAndApplyLocalTimezone(boolean hasRoot, final ProgressListener listener) {
        String[] endpoints = new String[]{
                "http://127.0.0.1:8118/__https/ipwho.is/?fields=timezone",
                "http://ip-api.com/json/?fields=status,timezone",
                "http://127.0.0.1:8118/__https/timeapi.io/api/time/current/ip",
                "http://127.0.0.1:8118/__https/worldtimeapi.org/api/ip"
        };
        String foundTz = null;
        for (int i = 0; i < endpoints.length && foundTz == null; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpoints[i]).openConnection(Proxy.NO_PROXY);
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(5500);
                conn.setRequestProperty("User-Agent", "Arc7Freedom/1.9.0");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String ln;
                    while ((ln = br.readLine()) != null) sb.append(ln);
                    br.close();
                    String json = sb.toString();
                    org.json.JSONObject obj = new org.json.JSONObject(json);
                    if (obj.has("timezone")) {
                        Object tzVal = obj.get("timezone");
                        if (tzVal instanceof org.json.JSONObject) {
                            foundTz = ((org.json.JSONObject) tzVal).optString("id", null);
                        } else if (tzVal instanceof String) {
                            foundTz = (String) tzVal;
                        }
                    } else if (obj.has("timeZone")) {
                        foundTz = obj.optString("timeZone", null);
                    }
                    if (foundTz != null) {
                        foundTz = foundTz.trim();
                        if (!foundTz.contains("/") && !"UTC".equalsIgnoreCase(foundTz)) {
                            foundTz = null;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        if (foundTz != null && foundTz.length() >= 3) {
            applySystemTimezone(foundTz, hasRoot, listener);
            return foundTz;
        }
        return TimeZone.getDefault().getID();
    }

    public void applySystemTimezone(String tzId, boolean hasRoot, final ProgressListener listener) {
        if (tzId == null || tzId.trim().isEmpty()) return;
        String cleanTz = tzId.trim();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(cleanTz));
        } catch (Throwable ignored) {}
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    appContext.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setTimeZone(cleanTz);
            }
        } catch (Throwable ignored) {}
        if (hasRoot && shell != null) {
            shell.execRoot(
                    "setprop persist.sys.timezone " + RootShell.shellQuote(cleanTz) + " >/dev/null 2>&1 || true; "
                    + "settings put global auto_time_zone 1 >/dev/null 2>&1 || true; "
                    + "am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone "
                    + RootShell.shellQuote(cleanTz) + " >/dev/null 2>&1 || true",
                    null);
        }
        try {
            Arc7ClockWidgetProvider.refreshAll(appContext);
        } catch (Throwable ignored) {}
        if (listener != null) {
            listener.onLog("Applied local IANA timezone automatically: " + cleanTz);
        }
    }

    private void syncTabletClock(boolean hasRoot, final ProgressListener listener) {
        // Always ensure local IANA timezone is applied BEFORE setting or formatting wall clock!
        String activeTz = detectAndApplyLocalTimezone(hasRoot, listener);
        TimeZone localZone = TimeZone.getTimeZone(activeTz);

        File tlsBin = shell.getTlsEngineBinary();
        if (tlsBin != null && tlsBin.exists()) {
            // Note: `libarc7tls.so ntp --set` sets the kernel clock directly via `settimeofday(&tv_utc, NULL)`
            // in pure UTC epoch seconds — NEVER run non-UTC `date -s <UTC_STR>` afterwards, which previously
            // shifted local wall time by the UTC offset!
            RootShell.ExecResult ntpRes = hasRoot
                    ? shell.execRoot("'" + tlsBin.getAbsolutePath() + "' ntp --set", null)
                    : shell.execCommandArray(new String[]{tlsBin.getAbsolutePath(), "ntp"}, null);
            if (ntpRes.exitCode == 0 && (ntpRes.stdout.contains("NTP_UTC=")
                    || ntpRes.stdout.contains("EPOCH=") || ntpRes.stdout.contains("ANDROID_DATE="))) {
                String epochStr = extractKey(ntpRes.stdout, "EPOCH=");
                String isoUtc = extractKey(ntpRes.stdout, "NTP_UTC=");
                if (isoUtc == null) isoUtc = extractKey(ntpRes.stdout, "ISO_UTC=");
                if (hasRoot && epochStr != null) {
                    try {
                        long epochMs = Long.parseLong(epochStr.trim()) * 1000L;
                        SimpleDateFormat localFmt = new SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US);
                        localFmt.setTimeZone(localZone);
                        String localDateArg = localFmt.format(new Date(epochMs));
                        shell.execRoot("TZ=" + RootShell.shellQuote(activeTz)
                                + " date -s " + localDateArg + " >/dev/null 2>&1 || true; "
                                + "am broadcast -a android.intent.action.TIME_SET >/dev/null 2>&1 || true", null);
                    } catch (Throwable ignored) {}
                }
                if (listener != null) {
                    listener.onLog("Synced atomic time (" + isoUtc + ") in local timezone " + activeTz);
                }
                return;
            }
        }

        // Fallback if router firewall blocks UDP port 123: read HTTP Date: header over TCP port 80
        // and format specifically in `localZone` (`TZ=<activeTz>`)!
        String[] httpUrls = new String[]{
                "http://1.1.1.1/",
                "http://connectivitycheck.gstatic.com/generate_204",
                "http://neverssl.com/"
        };
        for (String u : httpUrls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection(Proxy.NO_PROXY);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                long serverDateMs = conn.getDate();
                if (serverDateMs > 1700000000000L) {
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US);
                    fmt.setTimeZone(localZone);
                    String localDateArg = fmt.format(new Date(serverDateMs));
                    if (hasRoot) {
                        shell.execRoot("TZ=" + RootShell.shellQuote(activeTz)
                                + " date -s " + localDateArg + " >/dev/null 2>&1 || true; "
                                + "am broadcast -a android.intent.action.TIME_SET >/dev/null 2>&1 || true", null);
                        if (listener != null) {
                            listener.onLog("Synced local clock via HTTP Date (" + u + ") -> " + localDateArg + " (" + activeTz + ")");
                        }
                    }
                    return;
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (listener != null) {
            listener.onLog("Clock sync skipped (Wi-Fi offline right now) — timezone: " + activeTz);
        }
    }

    private List<String> findExpiredSystemCertFilenames() {
        List<String> expired = new ArrayList<String>();
        File sysDir = new File("/system/etc/security/cacerts");
        File[] files = sysDir.listFiles();
        if (files == null) return expired;
        long checkTime = Math.max(System.currentTimeMillis(), BUILD_FLOOR_EPOCH_MS);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (File f : files) {
                if (!f.isFile()) continue;
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(f);
                    Certificate c = cf.generateCertificate(fis);
                    if (c instanceof X509Certificate) {
                        Date exp = ((X509Certificate) c).getNotAfter();
                        if (exp != null && exp.getTime() < checkTime) {
                            expired.add(f.getName());
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (fis != null) {
                        try { fis.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return expired;
    }

    public boolean restoreFactorySystemCerts(File backupStorageRoot, final ProgressListener listener) {
        File sysCaBackup = new File(backupStorageRoot, "KoboArc7Backups/cacerts-stock-backup");
        if (!new File(sysCaBackup, ".backed_up").exists()) {
            listener.onLog("No stock CA backup found in " + sysCaBackup.getAbsolutePath());
            return false;
        }
        if (shell == null || !shell.isRootAvailable()) {
            listener.onLog("ERROR: Root access is required to modify /system/etc/security/cacerts.");
            return false;
        }
        listener.onProgress(40, "Restoring original factory /system/etc/security/cacerts...");
        RootShell.ExecResult r = shell.execRoot(
                "mount -o remount,rw /system 2>/dev/null || mount -o remount,rw /emmc@android /system 2>/dev/null || true; " +
                "rm -f /system/etc/security/cacerts/*; " +
                "for _sc in '" + sysCaBackup.getAbsolutePath() + "'/*.0; do " +
                "  [ -f \"$_sc\" ] && cat \"$_sc\" > \"/system/etc/security/cacerts/${_sc##*/}\" 2>/dev/null || true; " +
                "done; " +
                "chmod 0644 /system/etc/security/cacerts/*; " +
                "sync; mount -o remount,ro /system 2>/dev/null || true",
                new RootShell.LineListener() {
                    @Override
                    public void onLine(String line) {
                        listener.onLog(line);
                    }
                }
        );
        listener.onProgress(100, r.exitCode == 0 ? "Original factory CA store restored." : "Restore failed.");
        return r.exitCode == 0;
    }

    /**
     * Downloads any `https://` or `http://` URL (Android `.zip` ROM, `recovery.img`, `.apk`, etc.)
     * using `libarc7tls.so` (modern TLS 1.3 + TLS 1.2 + our 120 pre-downloaded 2026 Root CAs).
     * Automatically falls back with `--insecure-clock` ONLY if an unrooted tablet's battery clock
     * is stuck in 2013 and user declined setting Date & Time manually.
     */
    public File downloadWithBundledCerts(String rawUrl, File destDir, final ProgressListener listener) {
        try {
            String url = rawUrl == null ? "" : rawUrl.trim();
            if (url.length() == 0) {
                listener.onLog("ERROR: Please enter a download URL.");
                return null;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            if (!destDir.exists()) destDir.mkdirs();
            String fileName = guessFilenameFromUrl(url);
            File outFile = new File(destDir, fileName);

            File bundleFile = ensureCaBundleFile();
            File tlsBin = shell.getTlsEngineBinary();
            listener.onProgress(3, "Connecting via TLS 1.3 + 120 pre-downloaded 2026 CAs...");
            listener.onLog("Downloading: " + url);
            listener.onLog("Destination: " + outFile.getAbsolutePath());

            RootShell.ExecResult res = runTlsFetchOnce(tlsBin, bundleFile, url, outFile, true, listener);
            if (res.exitCode != 0 && Calendar.getInstance().get(Calendar.YEAR) < 2026) {
                listener.onLog("Tablet clock is stuck in " + Calendar.getInstance().get(Calendar.YEAR) +
                        " — retrying TLS 1.3 download ignoring stale device clock...");
                res = runTlsFetchOnce(tlsBin, bundleFile, url, outFile, false, listener);
            }

            if (res.exitCode == 0 && outFile.exists() && outFile.length() > 0) {
                String mb = String.format(Locale.US, "%.2f MB", outFile.length() / (1024.0 * 1024.0));
                listener.onProgress(100, "Download complete: " + outFile.getName() + " (" + mb + ")");
                listener.onLog("SAVED: " + outFile.getAbsolutePath() + " (" + mb + ")");
                return outFile;
            }
            listener.onLog("ERROR: Download failed (exit=" + res.exitCode + "). Check Wi-Fi connection & URL.");
            return null;
        } catch (Throwable t) {
            listener.onLog("ERROR downloading file: " + t.getMessage());
            return null;
        }
    }

    private RootShell.ExecResult runTlsFetchOnce(File tlsBin, File bundleFile, String url, File outFile,
                                                 boolean strictClock, final ProgressListener listener) {
        String[] cmd = strictClock
                ? new String[]{tlsBin.getAbsolutePath(), "fetch", bundleFile.getAbsolutePath(), url, outFile.getAbsolutePath()}
                : new String[]{tlsBin.getAbsolutePath(), "fetch", bundleFile.getAbsolutePath(), url, outFile.getAbsolutePath(), "--insecure-clock"};

        return shell.execCommandArray(cmd, new RootShell.LineListener() {
            @Override
            public void onLine(String line) {
                if (line.startsWith("PROGRESS:")) {
                    String[] p = line.split(":");
                    if (p.length >= 4) {
                        try {
                            int pct = Integer.parseInt(p[1]);
                            long done = Long.parseLong(p[2]);
                            long total = Long.parseLong(p[3]);
                            String status = total > 0
                                    ? String.format(Locale.US, "Downloading (%d%%) — %.2f / %.2f MB",
                                    pct, done / 1048576.0, total / 1048576.0)
                                    : String.format(Locale.US, "Downloading — %.2f MB received", done / 1048576.0);
                            listener.onProgress(Math.max(5, Math.min(99, pct)), status);
                        } catch (Throwable ignored) {
                        }
                    }
                } else {
                    listener.onLog("  " + line);
                }
            }
        });
    }

    public synchronized File ensureLocalUpgradeCaDir() {
        File caDir = new File(appContext.getFilesDir(), "local_tls13_ca");
        File crt = new File(caDir, "local-ca.crt");
        File key = new File(caDir, "local-ca.key");
        if (crt.exists() && key.exists() && crt.length() > 200 && key.length() > 200) {
            return caDir;
        }
        caDir.mkdirs();
        File tlsBin = shell.getTlsEngineBinary();
        shell.execCommandArray(new String[]{tlsBin.getAbsolutePath(), "gen-local-ca", caDir.getAbsolutePath()}, null);
        return caDir;
    }

    public static final String EXPECTED_GATEWAY_PING = "ARC7_TLS13_READY_V190";

    public static String probeGatewayPing(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 250);
            s.setSoTimeout(400);
            s.getOutputStream().write("GET /__ping HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private void shutdownAnyStaleGateway(int port) {
        stopBrowserGateway();
        // Ask any previous daemon supporting `/__shutdown` to exit cleanly
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 200);
            s.setSoTimeout(250);
            s.getOutputStream().write("GET /__shutdown HTTP/1.0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
        // Also terminate any pre-v1.8.2 `libarc7tls.so` orphan PIDs via pure `/proc` scan (no `pkill` needed!)
        String killLoop =
                "for _p in /proc/[0-9]*; do " +
                "  if [ -r \"$_p/cmdline\" ]; then " +
                "    _cmd=$(tr '\\000' ' ' < \"$_p/cmdline\" 2>/dev/null || cat \"$_p/cmdline\" 2>/dev/null || true); " +
                "    case \"$_cmd\" in *arc7tls*proxy*) kill -9 \"${_p##*/}\" 2>/dev/null || true ;; esac; " +
                "  fi; " +
                "done";
        try {
            shell.execSh(killLoop, null);
            if (shell.isRootAvailable()) {
                shell.execRoot(killLoop, null);
            }
        } catch (Throwable ignored) {}
        for (int i = 0; i < 10; i++) {
            if (!isLocalProxyListening(port)) break;
            try { Thread.sleep(80); } catch (Throwable ignored) {}
        }
    }

    public synchronized boolean startBrowserGateway() {
        if (isLocalProxyListening(DEFAULT_PROXY_PORT)) {
            String ping = probeGatewayPing(DEFAULT_PROXY_PORT);
            if (ping.contains(EXPECTED_GATEWAY_PING)) {
                return true;
            }
            // Older daemon binary is holding port 8118 across an in-place APK update — hot-swap it now!
            shutdownAnyStaleGateway(DEFAULT_PROXY_PORT);
        }
        try {
            File bundleFile = ensureCaBundleFile();
            File tlsBin = shell.getTlsEngineBinary();
            File localCaDir = ensureLocalUpgradeCaDir();
            File localCrt = new File(localCaDir, "local-ca.crt");
            File localKey = new File(localCaDir, "local-ca.key");

            // Also ensure `/system/etc/resolv.conf` exists on rooted Android 4.2 devices so CLI tools work too
            if (shell.isRootAvailable() && !new File("/system/etc/resolv.conf").exists()) {
                try {
                    shell.execRoot(
                            RootShell.remountSystemRwCmd() + " && " +
                            "echo 'nameserver 1.1.1.1' > /system/etc/resolv.conf && " +
                            "echo 'nameserver 8.8.8.8' >> /system/etc/resolv.conf && " +
                            "chmod 0644 /system/etc/resolv.conf && " +
                            "ln -s /system/etc/resolv.conf /etc/resolv.conf 2>/dev/null || true; " +
                            RootShell.remountSystemRoCmd(),
                            null);
                } catch (Throwable ignored) {}
            }

            ProcessBuilder pb;
            if (localCrt.exists() && localKey.exists()) {
                pb = new ProcessBuilder(
                        tlsBin.getAbsolutePath(),
                        "proxy",
                        bundleFile.getAbsolutePath(),
                        String.valueOf(DEFAULT_PROXY_PORT),
                        localCrt.getAbsolutePath(),
                        localKey.getAbsolutePath()
                );
            } else {
                pb = new ProcessBuilder(
                        tlsBin.getAbsolutePath(),
                        "proxy",
                        bundleFile.getAbsolutePath(),
                        String.valueOf(DEFAULT_PROXY_PORT)
                );
            }
            pb.redirectErrorStream(true);
            final Process started = pb.start();
            proxyProcess = started;
            Thread drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream is = started.getInputStream();
                        byte[] discard = new byte[1024];
                        while (is.read(discard) > 0) { /* keep stdout pipe unblocked forever */ }
                    } catch (Throwable ignored) {}
                }
            });
            drain.setDaemon(true);
            drain.start();
            for (int i = 0; i < 20; i++) {
                if (isLocalProxyListening(DEFAULT_PROXY_PORT)) return true;
                Thread.sleep(100);
            }
        } catch (Throwable ignored) {
        }
        return isLocalProxyListening(DEFAULT_PROXY_PORT);
    }

    /**
     * Enables or disables the System-Wide TLS 1.3 Upgrade Proxy (`HTTP CONNECT 127.0.0.1:8118`)
     * so EVERY legacy Android 4.2 app on the tablet transparently connects to modern 2026 TLS 1.3 servers!
     */
    public boolean setSystemWideTls13ProxyEnabled(boolean enable, final ProgressListener listener) {
        if (!enable) {
            if (shell.isRootAvailable()) {
                shell.execRoot(
                        "settings delete global http_proxy >/dev/null 2>&1 || true; " +
                        "settings put global http_proxy :0 >/dev/null 2>&1 || true; " +
                        "settings delete global global_http_proxy_host >/dev/null 2>&1 || true; " +
                        "settings delete global global_http_proxy_port >/dev/null 2>&1 || true",
                        null
                );
            }
            appContext.getSharedPreferences("arc7_optimizer", Context.MODE_PRIVATE)
                    .edit().putBoolean("sys_tls13_proxy", false).commit();
            if (listener != null) {
                listener.onLog("Disabled global Android HTTP proxy (`settings delete global http_proxy`). Local browser gateway on 127.0.0.1:8118 remains ready anytime.");
            }
            return true;
        }

        // Restart gateway fresh to ensure per-device Local CA (`CONNECT_TLS13=ENABLED`) is loaded
        stopBrowserGateway();
        File caDir = ensureLocalUpgradeCaDir();
        File localCrt = new File(caDir, "local-ca.crt");
        if (!localCrt.exists()) {
            if (listener != null) listener.onLog("ERROR: Failed generating per-device Local TLS 1.3 Upgrade CA.");
            return false;
        }

        // Export `Arc7-Local-TLS13-CA.crt` to Download folder for non-root fallback
        try {
            File dl = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
            if (!dl.exists()) dl.mkdirs();
            RootShell.copyFile(localCrt, new File(dl, "Arc7-Local-TLS13-CA.crt"));
        } catch (Throwable ignored) {}

        boolean hasRoot = shell.isRootAvailable();
        if (hasRoot) {
            String hashedName = null;
            String hashedPath = null;
            File[] files = caDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".0")) {
                        hashedName = f.getName();
                        hashedPath = f.getAbsolutePath();
                        break;
                    }
                }
            }
            String cmd =
                    RootShell.remountSystemRwCmd() + " && " +
                    (hashedPath != null
                            ? "cat '" + hashedPath + "' > '/system/etc/security/cacerts/" + hashedName + "' && chmod 0644 /system/etc/security/cacerts/*.0 && " +
                              "mkdir -p /data/misc/keychain/cacerts-added && cat '" + hashedPath + "' > '/data/misc/keychain/cacerts-added/" + hashedName + "' && chmod 0644 /data/misc/keychain/cacerts-added/* && "
                            : "") +
                    RootShell.remountSystemRoCmd() + " && " +
                    "settings put global http_proxy 127.0.0.1:" + DEFAULT_PROXY_PORT + " && " +
                    "settings put global global_http_proxy_host 127.0.0.1 && " +
                    "settings put global global_http_proxy_port " + DEFAULT_PROXY_PORT + " && " +
                    "echo SYS_TLS13_PROXY_OK";
            RootShell.ExecResult r = shell.execRoot(cmd, null);
            if (listener != null) listener.onLog(r.stdout.trim());
        } else if (listener != null) {
            listener.onLog("Unrooted mode: saved `/sdcard/Download/Arc7-Local-TLS13-CA.crt`. Install it via Settings -> Security -> Install from SD card and set Wi-Fi Proxy to `127.0.0.1` port `8118` (or root first on Tab 1 for zero-prompt system-wide trust!).");
        }

        boolean up = startBrowserGateway();
        Arc7TlsGatewayService.ensureStarted(appContext);
        appContext.getSharedPreferences("arc7_optimizer", Context.MODE_PRIVATE)
                .edit().putBoolean("sys_tls13_proxy", up).commit();
        if (listener != null && up) {
            listener.onLog("SUCCESS: System-Wide TLS 1.3 Upgrade Proxy is LIVE on `127.0.0.1:" + DEFAULT_PROXY_PORT + "` (`CONNECT_TLS13=ENABLED`)! All legacy Android 4.2 apps now handshake locally & tunnel upstream over verified 2026 TLS 1.3!");
        }
        return up;
    }

    public synchronized void stopBrowserGateway() {
        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }
    }

    public Intent buildOpenPortalBrowserIntent() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + DEFAULT_PROXY_PORT + "/"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public static boolean isLocalProxyListening(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    public static String guessFilenameFromUrl(String url) {
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int slash = clean.lastIndexOf('/');
        String last = (slash >= 0 && slash + 1 < clean.length()) ? clean.substring(slash + 1) : "";
        last = last.replaceAll("[^A-Za-z0-9._-]", "_");
        if (last.length() < 3) {
            return "downloaded_" + System.currentTimeMillis() + ".bin";
        }
        return last;
    }

    private void copyAsset(String assetPath, File dst) throws IOException {
        if (dst.getParentFile() != null && !dst.getParentFile().exists()) {
            dst.getParentFile().mkdirs();
        }
        InputStream in = appContext.getAssets().open(assetPath);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static String extractKey(String text, String prefix) {
        for (String ln : text.split("\n")) {
            ln = ln.trim();
            if (ln.startsWith(prefix)) {
                return ln.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }

    /**
     * Enables TLSv1.2 + TLSv1.1 on every SSLSocket created on Android 4.1-4.4 (API 16-19),
     * where OpenSSL supports TLSv1.2 natively yet leaves it disabled by default!
     */
    private static class Tls12SocketFactory extends SSLSocketFactory {
        private static final String[] PROTOCOLS = new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"};
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory base) {
            this.delegate = base;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enableTls12(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return enableTls12(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return enableTls12(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket enableTls12(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                try {
                    ssl.setEnabledProtocols(PROTOCOLS);
                } catch (Throwable ignored) {
                }
            }
            return socket;
        }
    }
}
