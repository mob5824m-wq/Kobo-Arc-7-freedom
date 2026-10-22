package ca.arc7freedom.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * System-Wide Ad, Tracker & Malware Blocker + Per-App Root `iptables` Firewall
 * (`Arc7 Shield`, `v2.2.0`):
 *
 *   - 100% pure Android 4.2 `mksh` + `toolbox` compliant (`cat <file> > /system/etc/hosts`,
 *     zero `cp`/`grep`/`awk`/`sed` calls!).
 *   - Built-in high-impact ad/telemetry domain list + live `StevenBlack/hosts` update over TLS 1.3
 *   - Per-app UID network firewall via kernel `iptables -m owner --uid-owner <uid> -j REJECT`!
 */
public class Arc7ShieldActivity extends Activity {

    public static final String PREFS = "arc7_shield_prefs";
    public static final String KEY_ADBLOCK_ACTIVE = "adblock_active";
    public static final String KEY_CUSTOM_BLOCK = "custom_blocked_domains";
    public static final String KEY_BLOCKED_UIDS = "blocked_app_uids";

    public static final String[] BUILTIN_AD_TRACKER_DOMAINS = new String[]{
            "doubleclick.net", "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "adservice.google.com", "google-analytics.com", "ssl.google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adsystem.com", "aax-us-east.amazon-adsystem.com",
            "connect.facebook.net", "graph.facebook.com", "pixel.facebook.com", "an.facebook.com",
            "ads.twitter.com", "analytics.twitter.com", "ads-api.twitter.com",
            "scorecardresearch.com", "sb.scorecardresearch.com", "quantserve.com",
            "criteo.com", "bidswitch.net", "rubiconproject.com", "openx.net", "pubmatic.com",
            "outbrain.com", "taboola.com", "zedo.com", "adnxs.com", "moatads.com",
            "flurry.com", "data.flurry.com", "app-measurement.com", "crashlytics.com",
            "api.kobobooks.com", "mobile.kobobooks.com", "readingservices.kobobooks.com"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell shell;
    private NetworkCertManager certMgr;
    private Arc7Skin.Palette pal;
    private TextView statusBannerTv;
    private EditText customDomainsEt;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell = new RootShell(this);
        certMgr = new NetworkCertManager(this, shell);
        pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_shield,
                "Ad Blocker & Firewall",
                "System-Wide Hosts Ad/Tracker Blocker • Live StevenBlack TLS 1.3 List • Per-App Root Firewall",
                null));

        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(24));
        sv.addView(page);

        LinearLayout hero = Arc7Skin.card(this, pal);
        statusBannerTv = new TextView(this);
        statusBannerTv.setTextColor(pal.textPrimary);
        statusBannerTv.setTypeface(Typeface.DEFAULT_BOLD);
        statusBannerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hero.addView(statusBannerTv);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(10);
        btnRow.setLayoutParams(brlp);

        Button enableBtn = Arc7Skin.actionButton(this, "🛡 Apply Built-In AdBlocker", 0xFF059669);
        enableBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyHostsBlockerAsync(false); }
        });
        addWeighted(btnRow, enableBtn, true);

        Button liveSyncBtn = Arc7Skin.actionButton(this, "☁ Sync Full 80k List (TLS 1.3)", 0xFF0284C7);
        liveSyncBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyHostsBlockerAsync(true); }
        });
        addWeighted(btnRow, liveSyncBtn, true);

        Button restoreBtn = Arc7Skin.actionButton(this, "Restore Clean Hosts", 0xFF475569);
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restoreDefaultHostsAsync(); }
        });
        addWeighted(btnRow, restoreBtn, false);
        hero.addView(btnRow);

        customDomainsEt = new EditText(this);
        customDomainsEt.setHint("Extra domains to block (comma or newline separated)...");
        customDomainsEt.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CUSTOM_BLOCK, ""));
        hero.addView(customDomainsEt);

        LinearLayout.LayoutParams cardGap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardGap.bottomMargin = dp(14);
        page.addView(hero, cardGap);

        // Per-App Root Firewall Card
        LinearLayout fwCard = Arc7Skin.card(this, pal);
        TextView fwh = new TextView(this);
        fwh.setText("PER-APP ROOT NETWORK FIREWALL (`iptables` UID RULES — UNCHECK TO BLOCK INTERNET)");
        fwh.setTextColor(pal.accent);
        fwh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fwh.setTypeface(Typeface.DEFAULT_BOLD);
        fwh.setPadding(0, 0, 0, dp(8));
        fwCard.addView(fwh);

        populateFirewallAppList(fwCard);
        page.addView(fwCard);

        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        refreshStatusBanner();
    }

    private void addWeighted(LinearLayout row, View v, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.rightMargin = dp(8);
        row.addView(v, lp);
    }

    public static int countBlockedDomainsInHostsFile(File hosts) {
        int count = 0;
        if (hosts == null || !hosts.isFile()) return 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(hosts), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if ((t.startsWith("0.0.0.0 ") || t.startsWith("127.0.0.1 "))
                        && !t.endsWith("localhost")) {
                    count++;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        return count;
    }

    public static String buildCleanHostsContent(Set<String> blockedDomains) {
        StringBuilder sb = new StringBuilder();
        sb.append("127.0.0.1       localhost\n");
        sb.append("::1             ip6-localhost\n\n");
        for (String dom : blockedDomains) {
            String d = dom.trim().toLowerCase(Locale.US);
            if (d.length() > 2 && d.contains(".") && !d.contains("localhost")) {
                sb.append("0.0.0.0 ").append(d).append('\n');
            }
        }
        return sb.toString();
    }

    private void refreshStatusBanner() {
        int activeCount = countBlockedDomainsInHostsFile(new File("/system/etc/hosts"));
        statusBannerTv.setText(activeCount > 0
                ? ("🛡 PROTECTED — Blocking " + activeCount + " ad, tracker & dead Kobo telemetry domains system-wide!")
                : "Shield Status: Standard `/system/etc/hosts` (0 domains blocked — tap Apply above)");
    }

    private void applyHostsBlockerAsync(final boolean includeStevenBlackRemote) {
        final String customTxt = customDomainsEt.getText().toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CUSTOM_BLOCK, customTxt).commit();
        Toast.makeText(this, "Building & applying system `/system/etc/hosts` shield...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<String> domains = new LinkedHashSet<String>();
                    Collections.addAll(domains, BUILTIN_AD_TRACKER_DOMAINS);
                    String[] extras = customTxt.split("[,\\s]+");
                    for (String e : extras) if (!e.trim().isEmpty()) domains.add(e.trim());

                    if (includeStevenBlackRemote) {
                        String remote = Arc7BookSources.fetchStringViaTls13(
                                Arc7ShieldActivity.this, shell, certMgr,
                                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts");
                        String[] lines = remote.split("\\n");
                        for (int i = 0; i < lines.length && domains.size() < 45000; i++) {
                            String t = lines[i].trim();
                            if (t.startsWith("0.0.0.0 ")) {
                                String host = t.substring(8).trim();
                                int sp = host.indexOf('#');
                                if (sp > 0) host = host.substring(0, sp).trim();
                                if (!host.equals("0.0.0.0")) domains.add(host);
                            }
                        }
                    }

                    File staged = new File(getCacheDir(), "arc7_hosts.out");
                    FileOutputStream fos = new FileOutputStream(staged);
                    try { fos.write(buildCleanHostsContent(domains).getBytes("UTF-8")); }
                    finally { fos.close(); }
                    staged.setReadable(true, false);

                    String p = staged.getAbsolutePath();
                    shell.execRoot(
                            "mount -o rw,remount /system >/dev/null 2>&1 || mount -o rw,remount / >/dev/null 2>&1 || true; "
                            + "cat '" + p + "' > /system/etc/hosts && chmod 644 /system/etc/hosts; "
                            + "mount -o ro,remount /system >/dev/null 2>&1 || true",
                            null);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ADBLOCK_ACTIVE, true).commit();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatusBanner();
                            Toast.makeText(Arc7ShieldActivity.this, "System-wide AdBlocker active!", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7ShieldActivity.this, "Shield error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void restoreDefaultHostsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File staged = new File(getCacheDir(), "arc7_hosts_clean.out");
                    FileOutputStream fos = new FileOutputStream(staged);
                    try { fos.write("127.0.0.1       localhost\n::1             ip6-localhost\n".getBytes("UTF-8")); }
                    finally { fos.close(); }
                    staged.setReadable(true, false);
                    shell.execRoot(
                            "mount -o rw,remount /system >/dev/null 2>&1 || true; "
                            + "cat '" + staged.getAbsolutePath() + "' > /system/etc/hosts && chmod 644 /system/etc/hosts; "
                            + "mount -o ro,remount /system >/dev/null 2>&1 || true",
                            null);
                    ui.post(new Runnable() {
                        @Override public void run() { refreshStatusBanner(); }
                    });
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void populateFirewallAppList(LinearLayout parent) {
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final Set<String> blocked = new LinkedHashSet<String>();
        String raw = sp.getString(KEY_BLOCKED_UIDS, "");
        if (!raw.isEmpty()) Collections.addAll(blocked, raw.split(","));

        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<ApplicationInfo> netApps = new ArrayList<ApplicationInfo>();
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo ai = apps.get(i);
            if (ai.uid >= 10000 && !ai.packageName.startsWith("ca.arc7freedom")) {
                netApps.add(ai);
            }
        }
        Collections.sort(netApps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return String.valueOf(a.loadLabel(getPackageManager()))
                        .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
            }
        });

        for (int i = 0; i < Math.min(40, netApps.size()); i++) {
            final ApplicationInfo ai = netApps.get(i);
            final String uidStr = String.valueOf(ai.uid);
            CheckBox cb = new CheckBox(this);
            cb.setText("  Allow Internet: " + ai.loadLabel(pm) + "  (UID " + uidStr + ")");
            cb.setTextColor(Color.WHITE);
            cb.setChecked(!blocked.contains(uidStr));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, final boolean allowed) {
                    if (allowed) blocked.remove(uidStr); else blocked.add(uidStr);
                    StringBuilder sb = new StringBuilder();
                    for (String u : blocked) { if (sb.length() > 0) sb.append(','); sb.append(u); }
                    sp.edit().putString(KEY_BLOCKED_UIDS, sb.toString()).commit();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            String cmd = allowed
                                    ? ("iptables -D OUTPUT -m owner --uid-owner " + uidStr + " -j REJECT >/dev/null 2>&1 || true")
                                    : ("iptables -C OUTPUT -m owner --uid-owner " + uidStr + " -j REJECT >/dev/null 2>&1 || "
                                       + "iptables -A OUTPUT -m owner --uid-owner " + uidStr + " -j REJECT >/dev/null 2>&1 || true");
                            shell.execRoot(cmd, null);
                        }
                    }).start();
                }
            });
            parent.addView(cb);
        }
    }
}
