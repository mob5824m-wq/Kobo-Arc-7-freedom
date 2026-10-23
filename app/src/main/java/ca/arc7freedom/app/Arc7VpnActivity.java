package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Unified System-Wide VPN, Official Tailscale Mesh & School Strict Wi-Fi Rescue App
 * (`Arc7 VPN, Tailscale & School Wi-Fi` — `v2.3.0`):
 *
 *   - **Tab 1 — School Wi-Fi Fixer:** Solves hidden school SSIDs (`scan_ssid=1`), 802.1X WPA2-Enterprise
 *     (`PEAP`/`TTLS` + 2026 CAs for `eduroam` / student IDs), regulatory channel unlock (`CA`/`US`),
 *     TLS 1.3 Captive Portal Sign-In, BYOD MAC helper, & 5 GHz -> 2.4 GHz Hotspot / USB bridge!
 *   - **Tab 2 — Tailscale Mesh & Exit Nodes:** 1-Tap Official Upstream `tailscaled` ARMv7 installer,
 *     Auth-Key or Interactive Browser Login, Exit Node routing right over port 443 HTTPS DERP,
 *     and 1-tap connect from any Tailnet PC straight into `Arc7 Stream Deck`!
 *   - **Tab 3 — System-Wide VPN & Stealth Tunnels (`VpnService`):** Zero-server Encrypted DoH
 *     (`1.1.1.1`/`9.9.9.9` over TLS 1.3 `:443`), Port-443 TLS 1.3 School Stealth Tunnel, SOCKS5 VPN,
 *     HTTP-CONNECT Proxy VPN, WireGuard `.conf` importer + Curve25519 Keygen, OpenVPN `.ovpn` importer,
 *     and Root `iptables` Kill Switch!
 */
public class Arc7VpnActivity extends Activity {

    private static final int REQ_VPN_PERMISSION = 7201;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Arc7Skin.Palette pal;
    private int currentTab = 0;

    private TextView liveBannerTv;
    private LinearLayout tabBar;
    private LinearLayout contentPane;
    private volatile boolean alive = true;
    /** Last login URL we already auto-opened, so the watcher can never spam browsers. */
    private volatile String autoOpenedLoginUrl = "";

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        pal = Arc7Skin.current(this);

        if (getIntent() != null && "tailscale".equalsIgnoreCase(getIntent().getStringExtra("tab"))) {
            currentTab = 1;
        } else if (getIntent() != null && "vpn".equalsIgnoreCase(getIntent().getStringExtra("tab"))) {
            currentTab = 2;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgColor);

        Button quickDisconnect = Arc7Skin.secondaryButton(this, pal, "\u23F9 Disconnect VPN");
        quickDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent stop = new Intent(Arc7VpnActivity.this, Arc7VpnService.class);
                stop.setAction(Arc7VpnService.ACTION_STOP);
                startService(stop);
                Toast.makeText(Arc7VpnActivity.this, "Disconnected VPN tunnel.", Toast.LENGTH_SHORT).show();
            }
        });

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_vpn,
                "Wi-Fi & VPN",
                "Official Tailscale Google Sign-In  \u2022  School Wi-Fi Fixer  \u2022  System-Wide VpnService + DoH TLS 1.3",
                quickDisconnect));

        liveBannerTv = new TextView(this);
        liveBannerTv.setTextColor(0xFF67E8F9);
        liveBannerTv.setBackgroundColor(0xFF0B1324);
        liveBannerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        liveBannerTv.setTypeface(Typeface.DEFAULT_BOLD);
        liveBannerTv.setPadding(dp(14), dp(7), dp(14), dp(7));
        liveBannerTv.setText("System VPN Status: Disconnected  \u2022  Ready for School Wi-Fi Fixer, Tailscale or Stealth DoH");
        root.addView(liveBannerTv);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(12), dp(8), dp(12), dp(6));
        root.addView(tabBar);

        ScrollView scroll = new ScrollView(this);
        contentPane = new LinearLayout(this);
        contentPane.setOrientation(LinearLayout.VERTICAL);
        contentPane.setPadding(dp(14), dp(6), dp(14), dp(22));
        scroll.addView(contentPane, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        renderTabs();
        renderActiveTab();
        startStatusPoller();
    }

    private void renderTabs() {
        tabBar.removeAllViews();
        String[] labels = new String[]{
                "\uD83C\uDFEB School Wi-Fi Fixer",
                "\uD83E\uDD85 Tailscale Mesh",
                "\uD83D\uDEE1 VPN & WireGuard / Stealth"
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = (i == currentTab)
                    ? Arc7Skin.actionButton(this, labels[i], pal.accentColor)
                    : Arc7Skin.secondaryButton(this, pal, labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < labels.length - 1) lp.rightMargin = dp(8);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentTab = idx;
                    renderTabs();
                    renderActiveTab();
                }
            });
            tabBar.addView(b, lp);
        }
    }

    private void renderActiveTab() {
        contentPane.removeAllViews();
        if (currentTab == 0) buildSchoolWifiTab();
        else if (currentTab == 1) buildTailscaleTab();
        else buildVpnTunnelsTab();
    }

    /* ==================== TAB 1: SCHOOL STRICT WI-FI FIXER ==================== */

    private void buildSchoolWifiTab() {
        LinearLayout card1 = makeCard();
        card1.addView(sectionTitle("1. Why Your School Wi-Fi Isn't Showing Up — Live Radio Scan & Instant Fix"));
        final TextView reportTv = monoBox(Arc7SchoolWifiHelper.buildLiveSchoolWifiDiagnosticReport(this));

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);

        Button rescanBtn = Arc7Skin.actionButton(this, "\uD83D\uDCE1 Unlock Channels 1–13 & Rescan", 0xFF0284C7);
        rescanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = Arc7SchoolWifiHelper.unlockCountryAndRescan(Arc7VpnActivity.this, "CA");
                reportTv.setText(Arc7SchoolWifiHelper.buildLiveSchoolWifiDiagnosticReport(Arc7VpnActivity.this));
                Toast.makeText(Arc7VpnActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
        scanRow.addView(rescanBtn, weightLp(8));

        Button captiveBtn = Arc7Skin.actionButton(this, "\uD83D\uDD11 School Captive Login", 0xFF059669);
        captiveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Arc7VpnActivity.this, Arc7BrowserActivity.class);
                i.setData(Uri.parse("http://neverssl.com"));
                startActivity(i);
            }
        });
        scanRow.addView(captiveBtn, weightLp(8));

        Button usbBridgeBtn = Arc7Skin.actionButton(this, "\uD83D\uDD0C Chromebook USB Net", 0xFF7C3AED);
        usbBridgeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Arc7VpnActivity.this, Arc7WiredInternetActivity.class));
            }
        });
        scanRow.addView(usbBridgeBtn, weightLp(0));

        card1.addView(scanRow);
        card1.addView(reportTv);
        contentPane.addView(card1, cardLp());

        // Hidden SSID & 802.1X WPA2-Enterprise (`eduroam` / Student Login) Profile Connector
        LinearLayout card2 = makeCard();
        card2.addView(sectionTitle("2. Connect Hidden School SSID (`scan_ssid=1`) or WPA2-Enterprise (`802.1X PEAP / eduroam`)"));

        final EditText ssidEt = inputField("Exact School SSID (e.g. eduroam, School-BYOD, Student-WiFi)");
        card2.addView(ssidEt);

        final Spinner secSpin = new Spinner(this);
        secSpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Open School Guest / Captive Portal Network",
                "WPA / WPA2 Password (`PSK`)",
                "WPA2-Enterprise 802.1X `PEAP + MSCHAPv2` (Most Schools & eduroam!)",
                "WPA2-Enterprise 802.1X `EAP-TTLS + MSCHAPv2`"
        }));
        secSpin.setSelection(2);
        card2.addView(secSpin);

        final EditText userEt = inputField("School Student Username / Email (for 802.1X Enterprise, e.g. s123456@school.edu)");
        card2.addView(userEt);

        final EditText passEt = inputField("School Wi-Fi Password");
        card2.addView(passEt);

        final EditText anonEt = inputField("Optional Anonymous Identity (leave blank unless school IT specifies e.g. anonymous@school.edu)");
        card2.addView(anonEt);

        final CheckBox hiddenCb = new CheckBox(this);
        hiddenCb.setText("  Hidden School Network (`scan_ssid=1` — forces Kobo to probe hidden SSID!)");
        hiddenCb.setChecked(true);
        hiddenCb.setTextColor(Color.WHITE);
        card2.addView(hiddenCb);

        final CheckBox caCb = new CheckBox(this);
        caCb.setText("  Attach 2026 Mozilla Root CA Bundle (`ISRG / DigiCert / Sectigo` RADIUS certs)");
        caCb.setChecked(true);
        caCb.setTextColor(Color.WHITE);
        card2.addView(caCb);

        Button saveSchoolWifi = Arc7Skin.actionButton(this,
                "\u2714 Inject School Wi-Fi Profile & Connect Now", 0xFF0284C7);
        saveSchoolWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String res = Arc7SchoolWifiHelper.connectSchoolNetwork(
                        Arc7VpnActivity.this,
                        ssidEt.getText().toString(),
                        secSpin.getSelectedItemPosition(),
                        userEt.getText().toString(),
                        passEt.getText().toString(),
                        anonEt.getText().toString(),
                        caCb.isChecked(),
                        hiddenCb.isChecked());
                reportTv.setText(res + "\n\n" + Arc7SchoolWifiHelper.buildLiveSchoolWifiDiagnosticReport(Arc7VpnActivity.this));
                Toast.makeText(Arc7VpnActivity.this, "Applied School Wi-Fi profile!", Toast.LENGTH_LONG).show();
            }
        });
        card2.addView(saveSchoolWifi);
        contentPane.addView(card2, cardLp());
    }

    /* ==================== TAB 2: OFFICIAL TAILSCALE MESH + EXIT NODE ==================== */

    private void buildTailscaleTab() {
        final SharedPreferences sp = getSharedPreferences(Arc7VpnService.PREFS, MODE_PRIVATE);

        LinearLayout card = makeCard();
        card.addView(sectionTitle("Official Tailscale VPN  \u2022  Sign in with Google (`login.tailscale.com`)"));

        final TextView tsLogTv = monoBox(
                "Installed: " + (TailscaleManager.isTailscaleInstalled(this) ? "YES (Ready!)" : "Not installed yet — tap 'Sign in with Google' below to auto-install!")
                + "\n" + TailscaleManager.queryStatusSummary(this));

        final LinearLayout inlineQrHolder = new LinearLayout(this);
        inlineQrHolder.setOrientation(LinearLayout.VERTICAL);

        final EditText authKeyEt = inputField("Optional Pre-Auth Key (`tskey-auth-...` — leave blank to Sign in with Google!)");
        authKeyEt.setText(sp.getString("ts_authkey", ""));

        final EditText exitNodeEt = inputField("Optional Tailscale Exit Node IP (`100.x.y.z` — routes ALL school traffic out your Home PC!)");
        exitNodeEt.setText(sp.getString("ts_exit_node", ""));

        final EditText headscaleEt = inputField("Optional Custom Control Server URL (blank = official controlplane.tailscale.com + Google SSO)");
        headscaleEt.setText(sp.getString("ts_control_url", ""));

        // PRIMARY HERO CTA: 1-TAP OFFICIAL GOOGLE SIGN-IN!
        Button googleAuthBtn = Arc7Skin.actionButton(this,
                "\uD83D\uDD11  Sign in with Google  (Official Tailscale Auth)", 0xFF2563EB);
        googleAuthBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String exitIp = exitNodeEt.getText().toString().trim();
                final String ctrl = headscaleEt.getText().toString().trim();
                sp.edit()
                        .putString("ts_exit_node", exitIp)
                        .putString("ts_control_url", ctrl)
                        .putString("mode", "tailscale")
                        .putString("upstream", "127.0.0.1:" + TailscaleManager.TAILSCALE_SOCKS5_PORT)
                        .putString("profile_name", exitIp.isEmpty() ? "Tailscale Mesh" : ("Tailscale Exit " + exitIp))
                        .commit();
                startOfficialGoogleSignInUiFlow(tsLogTv, inlineQrHolder, exitIp, ctrl);
            }
        });
        card.addView(googleAuthBtn);
        card.addView(inlineQrHolder);
        card.addView(authKeyEt);
        card.addView(exitNodeEt);
        card.addView(headscaleEt);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button installTsBtn = Arc7Skin.actionButton(this, "\u2B07 1. Install Official Tailscale", 0xFF7C3AED);
        installTsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tsLogTv.setText("Downloading & extracting official Tailscale static ARMv7 package over TLS 1.3...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String msg = TailscaleManager.installOrUpdateOfficialTailscale(Arc7VpnActivity.this);
                            ui.post(new Runnable() {
                                @Override public void run() { tsLogTv.setText(msg); }
                            });
                        } catch (final Throwable t) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    tsLogTv.setText("Download note: " + t.getMessage()
                                            + "\nTip: You can also drop `tailscale_*_arm.tgz` into `/sdcard/Download/` and tap again for offline install!");
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        btnRow.addView(installTsBtn, weightLp(8));

        Button connectTsBtn = Arc7Skin.actionButton(this, "\uD83E\uDD85 2. Start Tailscale + System VPN", 0xFF059669);
        connectTsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String ak = authKeyEt.getText().toString().trim();
                final String exitIp = exitNodeEt.getText().toString().trim();
                final String ctrl = headscaleEt.getText().toString().trim();
                sp.edit()
                        .putString("ts_authkey", ak)
                        .putString("ts_exit_node", exitIp)
                        .putString("ts_control_url", ctrl)
                        .putString("mode", "tailscale")
                        .putString("upstream", "127.0.0.1:" + TailscaleManager.TAILSCALE_SOCKS5_PORT)
                        .putString("profile_name", exitIp.isEmpty() ? "Tailscale Mesh" : ("Tailscale Exit " + exitIp))
                        .commit();

                if (ak.isEmpty()) {
                    startOfficialGoogleSignInUiFlow(tsLogTv, inlineQrHolder, exitIp, ctrl);
                    return;
                }

                tsLogTv.setText("Starting tailscaled userspace mesh + binding Android VpnService...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String out = TailscaleManager.startDaemonAndUp(Arc7VpnActivity.this, ak, exitIp, ctrl);
                            final boolean authed = TailscaleManager.isTailscaleAuthenticated(Arc7VpnActivity.this);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    tsLogTv.setText(out);
                                    if (authed) {
                                        requestAndLaunchSystemVpn();
                                    }
                                }
                            });
                        } catch (final Throwable t) {
                            ui.post(new Runnable() {
                                @Override public void run() { tsLogTv.setText("Tailscale error: " + t.getMessage()); }
                            });
                        }
                    }
                }).start();
            }
        });
        btnRow.addView(connectTsBtn, weightLp(8));

        Button refreshPeersBtn = Arc7Skin.secondaryButton(this, pal, "\uD83D\uDD04 Refresh Peers");
        refreshPeersBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tsLogTv.setText(TailscaleManager.queryStatusSummary(Arc7VpnActivity.this));
                renderActiveTab();
            }
        });
        btnRow.addView(refreshPeersBtn, weightLp(8));

        Button signOutBtn = Arc7Skin.secondaryButton(this, pal, "\uD83D\uDEAA Sign Out");
        signOutBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String msg = TailscaleManager.logoutTailscale(Arc7VpnActivity.this);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                inlineQrHolder.removeAllViews();
                                tsLogTv.setText(msg + "\n" + TailscaleManager.queryStatusSummary(Arc7VpnActivity.this));
                            }
                        });
                    }
                }).start();
            }
        });
        btnRow.addView(signOutBtn, weightLp(0));

        card.addView(btnRow);
        card.addView(tsLogTv);

        String existingAuthUrl = TailscaleManager.queryCurrentAuthUrl(this);
        if (!TailscaleManager.isTailscaleAuthenticated(this) && !existingAuthUrl.isEmpty()) {
            renderOfficialGoogleQrCard(inlineQrHolder, tsLogTv, existingAuthUrl);
        }

        // Quick 1-Tap "Open Peer in Stream Deck" buttons for every discovered Tailnet PC!
        List<TailscaleManager.TailnetPeer> peers = TailscaleManager.parseTailscalePeers(
                TailscaleManager.queryStatusJson(this));
        for (int i = 0; i < peers.size(); i++) {
            final TailscaleManager.TailnetPeer p = peers.get(i);
            Button peerDeckBtn = Arc7Skin.secondaryButton(this, pal,
                    "\uD83C\uDF9B Use `" + p.hostName + "` (" + p.tailscaleIp + ") as Stream Deck PC!");
            peerDeckBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent sd = new Intent(Arc7VpnActivity.this, Arc7StreamDeckActivity.class);
                    sd.putExtra("pc_host", p.tailscaleIp);
                    startActivity(sd);
                }
            });
            card.addView(peerDeckBtn);
        }

        contentPane.addView(card, cardLp());
    }

    /* ==================== TAB 3: SYSTEM-WIDE VPN, STEALTH :443 & WIREGUARD ==================== */

    private void buildVpnTunnelsTab() {
        final SharedPreferences sp = getSharedPreferences(Arc7VpnService.PREFS, MODE_PRIVATE);

        LinearLayout card = makeCard();
        card.addView(sectionTitle("System-Wide Android `VpnService` (`/dev/tun` + TLS 1.3 DoH + Stealth `:443` / SOCKS5 / HTTP / WireGuard)"));

        final Spinner modeSpin = new Spinner(this);
        final String[] modeIds = new String[]{"direct", "tls", "socks5", "http"};
        modeSpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "1. Encrypted DNS-over-HTTPS Stealth VPN (`1.1.1.1` TLS 1.3 `:443` — Zero Server Needed!)",
                "2. Port-443 HTTPS Stealth VPN Tunnel (Defeats Strict School UDP/DPI Firewalls!)",
                "3. SOCKS5 Tunnel VPN (SSH `-D`, Orbot/Tor, Tailscale, Shadowsocks, Private SOCKS5)",
                "4. HTTP-CONNECT Tunnel / School Explicit Proxy (`host:port`)"
        }));
        String savedMode = sp.getString("mode", "direct");
        for (int i = 0; i < modeIds.length; i++) if (modeIds[i].equals(savedMode)) modeSpin.setSelection(i);
        card.addView(modeSpin);

        final EditText nameEt = inputField("VPN Profile Name (e.g. School Stealth DoH or Home PC :443)");
        nameEt.setText(sp.getString("profile_name", "School Stealth DoH TLS 1.3"));
        card.addView(nameEt);

        final EditText upEt = inputField("VPN / Proxy Server `host:port` (leave blank for Mode #1 Zero-Server DoH!)");
        upEt.setText(sp.getString("upstream", "127.0.0.1:1055"));
        card.addView(upEt);

        final EditText authEt = inputField("Optional `username:password` Auth");
        authEt.setText(sp.getString("auth", ""));
        card.addView(authEt);

        final EditText dohEt = inputField("Encrypted DNS-over-HTTPS Provider (`1.1.1.1` Cloudflare, `9.9.9.9` Quad9, `8.8.8.8` Google)");
        dohEt.setText(sp.getString("doh_host", "1.1.1.1"));
        card.addView(dohEt);

        final CheckBox ksCb = new CheckBox(this);
        ksCb.setText("  Enable Root `iptables` Kill Switch (block all non-VPN traffic if tunnel drops)");
        ksCb.setChecked(sp.getBoolean("kill_switch", false));
        ksCb.setTextColor(Color.WHITE);
        card.addView(ksCb);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button connectBtn = Arc7Skin.actionButton(this, "\uD83D\uDD12 Connect System-Wide VPN Now", 0xFF059669);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int idx = modeSpin.getSelectedItemPosition();
                sp.edit()
                        .putString("mode", modeIds[idx])
                        .putString("profile_name", nameEt.getText().toString().trim())
                        .putString("upstream", upEt.getText().toString().trim())
                        .putString("auth", authEt.getText().toString().trim())
                        .putString("doh_host", dohEt.getText().toString().trim())
                        .putBoolean("kill_switch", ksCb.isChecked())
                        .commit();
                requestAndLaunchSystemVpn();
            }
        });
        actionRow.addView(connectBtn, weightLp(8));

        final TextView wgKeyBox = monoBox("WireGuard `.conf` & OpenVPN `.ovpn` Profile Tools ready.");

        Button keygenBtn = Arc7Skin.actionButton(this, "\uD83D\uDD11 Generate WireGuard Keys", 0xFF0284C7);
        keygenBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    File bin = new File(getApplicationInfo().nativeLibraryDir, "libarc7vpn.so");
                    Process p = new ProcessBuilder(bin.getAbsolutePath(), "wg-keygen").start();
                    InputStream in = p.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] b = new byte[512];
                    int n;
                    while ((n = in.read(b)) > 0) baos.write(b, 0, n);
                    in.close();
                    wgKeyBox.setText("Generated RFC 7748 Curve25519 WireGuard Keypair:\n" + baos.toString("UTF-8"));
                } catch (Throwable t) {
                    wgKeyBox.setText("Keygen error: " + t.getMessage());
                }
            }
        });
        actionRow.addView(keygenBtn, weightLp(0));

        card.addView(actionRow);

        // WireGuard `.conf` / OpenVPN `.ovpn` File Importer from `/sdcard/Download/`
        final EditText confPaste = inputField("Paste WireGuard `.conf` or OpenVPN `.ovpn` profile (or tap Import from `/sdcard/Download/`)");
        confPaste.setSingleLine(false);
        confPaste.setMinLines(4);
        card.addView(confPaste);

        Button importConfBtn = Arc7Skin.secondaryButton(this, pal,
                "\uD83D\uDCC2 Parse `.conf` / `.ovpn` Profile (or Auto-Scan `/sdcard/Download/`)");
        importConfBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String txt = confPaste.getText().toString().trim();
                if (txt.isEmpty()) txt = tryReadFirstVpnProfileFromDownloads();
                if (txt.isEmpty()) {
                    wgKeyBox.setText("Place any `.conf` or `.ovpn` file in `/sdcard/Download/` or paste it above!");
                    return;
                }
                if (txt.contains("[Interface]") || txt.contains("PrivateKey")) {
                    TailscaleManager.WireGuardConfig wg = TailscaleManager.parseWireGuardConf(txt);
                    dohEt.setText(wg.dns.split(",")[0].trim());
                    upEt.setText(wg.endpoint);
                    wgKeyBox.setText("Parsed WireGuard Profile!\n  Address  : " + wg.address
                            + "\n  DNS      : " + wg.dns + "\n  Endpoint : " + wg.endpoint
                            + "\n  Peer Key : " + wg.publicKey);
                } else {
                    Map<String, String> ov = TailscaleManager.parseOpenVpnConf(txt);
                    String ep = ov.get("host") + ":" + (ov.containsKey("port") ? ov.get("port") : "443");
                    upEt.setText(ep);
                    wgKeyBox.setText("Parsed OpenVPN Profile!\n  Remote : " + ep
                            + "\n  Proto  : " + ov.get("proto"));
                }
            }
        });
        card.addView(importConfBtn);
        card.addView(wgKeyBox);
        contentPane.addView(card, cardLp());
    }

    private String tryReadFirstVpnProfileFromDownloads() {
        File dl = new File("/sdcard/Download");
        File[] list = dl.listFiles();
        if (list == null) return "";
        for (int i = 0; i < list.length; i++) {
            String n = list[i].getName().toLowerCase();
            if (n.endsWith(".conf") || n.endsWith(".ovpn")) {
                try {
                    FileInputStream fis = new FileInputStream(list[i]);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int r;
                    while ((r = fis.read(b)) > 0) baos.write(b, 0, r);
                    fis.close();
                    return baos.toString("UTF-8");
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    private void requestAndLaunchSystemVpn() {
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN_PERMISSION);
        } else {
            onActivityResult(REQ_VPN_PERMISSION, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_PERMISSION && resultCode == RESULT_OK) {
            Intent svc = new Intent(this, Arc7VpnService.class);
            svc.setAction(Arc7VpnService.ACTION_START);
            startService(svc);
            Toast.makeText(this, "System-Wide Arc7 VPN + TLS 1.3 DoH Active!", Toast.LENGTH_SHORT).show();
        }
    }

    private void startStatusPoller() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (alive) {
                    try {
                        final String statusLine = buildBannerStatus();
                        ui.post(new Runnable() {
                            @Override public void run() { liveBannerTv.setText(statusLine); }
                        });
                    } catch (Throwable ignored) {}
                    try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private String buildBannerStatus() {
        File sf = Arc7VpnService.getStatusFile(this);
        if (Arc7VpnService.isRunning() && sf.exists()) {
            try {
                FileInputStream fis = new FileInputStream(sf);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                while ((n = fis.read(b)) > 0) baos.write(b, 0, n);
                fis.close();
                JSONObject o = new JSONObject(baos.toString("UTF-8"));
                long txKb = o.optLong("tx_bytes", 0L) / 1024L;
                long rxKb = o.optLong("rx_bytes", 0L) / 1024L;
                long dns = o.optLong("dns_queries", 0L);
                int flows = o.optInt("active_flows", 0);
                return "\uD83D\uDFE2 CONNECTED: " + Arc7VpnService.getActiveLabel()
                        + "  \u2022  \u2191 " + txKb + " KB   \u2193 " + rxKb + " KB"
                        + "  \u2022  Encrypted DoH Queries: " + dns + "  \u2022  Flows: " + flows;
            } catch (Throwable ignored) {}
        }
        return "\u26AA VPN Status: " + Arc7VpnService.getActiveLabel()
                + "  \u2022  Tap 'School Wi-Fi Fixer', 'Tailscale Mesh', or 'VPN & Stealth' below!";
    }

    /* ==================== UI Helpers ==================== */

    private LinearLayout makeCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        c.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 14));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    private LinearLayout.LayoutParams weightLp(int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightDp > 0) lp.rightMargin = dp(rightDp);
        return lp;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(pal.accentColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private EditText inputField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0x88FFFFFF);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        return et;
    }

    private TextView monoBox(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE2E8F0);
        tv.setBackgroundColor(0xFF070D19);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void startOfficialGoogleSignInUiFlow(
            final TextView tsLogTv, final LinearLayout qrHolder, final String exitIp, final String ctrl) {
        tsLogTv.setText("Preparing Official Tailscale Google Sign-In (`login.tailscale.com`)...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!TailscaleManager.isTailscaleInstalled(Arc7VpnActivity.this)) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                tsLogTv.setText("Downloading official Tailscale ARMv7 (`1.78.1`, Linux 3.4 compatible) over TLS 1.3...");
                            }
                        });
                        TailscaleManager.installOrUpdateOfficialTailscale(Arc7VpnActivity.this);
                    }
                    final String out = TailscaleManager.startDaemonAndUp(Arc7VpnActivity.this, "", exitIp, ctrl);
                    final boolean authed = TailscaleManager.isTailscaleAuthenticated(Arc7VpnActivity.this);
                    String foundUrl = TailscaleManager.queryCurrentAuthUrl(Arc7VpnActivity.this);
                    if (foundUrl.isEmpty()) foundUrl = TailscaleManager.extractAuthUrlFromText(out);
                    final String finalAuthUrl = foundUrl;

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tsLogTv.setText(out);
                            if (authed) {
                                qrHolder.removeAllViews();
                                Toast.makeText(Arc7VpnActivity.this,
                                        "Signed in with Google! Enabling System VPN...", Toast.LENGTH_LONG).show();
                                requestAndLaunchSystemVpn();
                            } else if (!finalAuthUrl.isEmpty()) {
                                renderOfficialGoogleQrCard(qrHolder, tsLogTv, finalAuthUrl);
                                autoOpenLoginPage(tsLogTv, finalAuthUrl);
                                watchForOfficialSignInAsync(qrHolder, tsLogTv, finalAuthUrl);
                            } else {
                                // The URL sometimes shows up a few seconds later (slow DNS / cold
                                // control-plane connection). Show *something* actionable and keep
                                // polling instead of leaving the user on a blank card.
                                renderLoginPendingCard(qrHolder, tsLogTv);
                                watchForOfficialSignInAsync(qrHolder, tsLogTv, "");
                            }
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            tsLogTv.setText("Google Sign-In error: " + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Opens the Tailscale / Google sign-in page exactly once per URL.
     *
     * This is the fix for "the Tailscale login web page never pops up": previously the flow only
     * rendered a QR code and waited for the user to find the button, and if the auth URL arrived
     * after the old 12 s window it was never shown at all.
     */
    private void autoOpenLoginPage(final TextView tsLogTv, final String authUrl) {
        if (authUrl == null || authUrl.isEmpty()) return;
        if (authUrl.equals(autoOpenedLoginUrl)) return;
        autoOpenedLoginUrl = authUrl;
        final String msg = TailscaleManager.openLoginPage(this, authUrl);
        try {
            tsLogTv.append("\n" + msg + "\n");
        } catch (Throwable ignored) {}
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void renderLoginPendingCard(final LinearLayout holder, final TextView tsLogTv) {
        holder.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackgroundColor(0xFF0B1F35);

        TextView tv = new TextView(this);
        tv.setText("Connecting to the Tailscale control server...\n\n"
                + "The Google sign-in page will open automatically the moment the tablet gets its "
                + "login link (usually 5-20 seconds). You can also scan the QR code that appears here "
                + "with your phone.");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        box.addView(tv);

        Button retry = Arc7Skin.actionButton(this, "\u21BB Retry Sign-In", 0xFF2563EB);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoOpenedLoginUrl = "";
                startOfficialGoogleSignInUiFlow(tsLogTv, (LinearLayout) holder, "", "");
            }
        });
        box.addView(retry);

        Button diag = Arc7Skin.actionButton(this, "\uD83E\uDDE9 Show tailscaled Diagnostics", 0xFF475569);
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tsLogTv.setText(TailscaleManager.diagnostics(Arc7VpnActivity.this));
            }
        });
        box.addView(diag);

        holder.addView(box);
    }

    private void renderOfficialGoogleQrCard(
            final LinearLayout holder, final TextView tsLogTv, final String authUrl) {
        holder.removeAllViews();
        LinearLayout qrBox = new LinearLayout(this);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        qrBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        qrBox.setBackgroundColor(0xFF0B1F35);

        android.widget.ImageView qrIv = new android.widget.ImageView(this);
        qrIv.setImageBitmap(TailscaleManager.buildVisualCodeCard(authUrl, dp(224)));
        qrBox.addView(qrIv, new LinearLayout.LayoutParams(dp(224), dp(224)));

        TextView cap = new TextView(this);
        cap.setText("\nThe sign-in page opens automatically. If it did not, tap below.\n"
                + "You can also scan this code with your phone camera -> 'Continue with Google'.\n\n"
                + "Login link: " + authUrl);
        cap.setTextColor(Color.WHITE);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cap.setGravity(Gravity.CENTER);
        cap.setTextIsSelectable(true);
        qrBox.addView(cap);

        Button browserBtn = Arc7Skin.actionButton(this,
                "\uD83C\uDF10 Open Tailscale Sign-In Page", 0xFF16A34A);
        browserBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoOpenedLoginUrl = "";
                autoOpenLoginPage(tsLogTv, authUrl);
            }
        });
        qrBox.addView(browserBtn);

        Button copyBtn = Arc7Skin.actionButton(this,
                "\uD83D\uDCCB Copy Login Link (paste on your phone)", 0xFF0F766E);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TailscaleManager.copyLoginUrl(Arc7VpnActivity.this, authUrl);
                Toast.makeText(Arc7VpnActivity.this,
                        "Login link copied - paste it into any browser on your phone or PC.",
                        Toast.LENGTH_LONG).show();
            }
        });
        qrBox.addView(copyBtn);

        Button diagBtn = Arc7Skin.actionButton(this,
                "\uD83E\uDDE9 Show tailscaled Diagnostics", 0xFF475569);
        diagBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tsLogTv.setText(TailscaleManager.diagnostics(Arc7VpnActivity.this));
            }
        });
        qrBox.addView(diagBtn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        holder.addView(qrBox, lp);
    }

    /**
     * Polls Tailscale while the sign-in page is open on the tablet (or another device).
     *
     * @param knownUrl the login URL we already have, or "" when it has not appeared yet. A URL that
     *                 only shows up later is opened automatically the moment it is seen.
     */
    private void watchForOfficialSignInAsync(
            final LinearLayout qrHolder, final TextView tsLogTv, final String knownUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String watched = knownUrl == null ? "" : knownUrl;
                for (int i = 0; i < 200 && alive; i++) {
                    try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                    String raw = TailscaleManager.queryStatusJson(Arc7VpnActivity.this);
                    String late = TailscaleManager.queryAuthUrlFromStatus(raw);
                    if (!late.isEmpty() && !late.equals(watched)) {
                        watched = late;
                        final String fresh = late;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                renderOfficialGoogleQrCard(qrHolder, tsLogTv, fresh);
                                autoOpenLoginPage(tsLogTv, fresh);
                            }
                        });
                    }
                    if (TailscaleManager.isAuthenticatedFromStatus(raw)) {
                        final String summary = TailscaleManager.queryStatusSummary(Arc7VpnActivity.this);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                qrHolder.removeAllViews();
                                tsLogTv.setText("\u2714 Google Sign-In Complete!\n" + summary);
                                Toast.makeText(Arc7VpnActivity.this,
                                        "Tailscale Google Sign-In complete — starting System VPN!",
                                        Toast.LENGTH_LONG).show();
                                requestAndLaunchSystemVpn();
                            }
                        });
                        return;
                    }
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }
}
