package ca.arc7freedom.app;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Dedicated Kobo Arc 7 <-> Chromebook Wired USB Internet Control Center (`v2.0.0`).
 *
 * Supports ALL 3 wired topologies cleanly on Android 4.0.3 – 4.2.2:
 *   1. Chromebook -> Kobo Reverse-Pool USB Internet (`Start-Chromebook-Wired-Internet.sh`
 *      over `adb forward tcp:18880 tcp:18880` — zero `adb reverse` needed!) + optional
 *      system-wide kernel `SO_ORIGINAL_DST` transparent `iptables` NAT for EVERY app!
 *   2. Chromebook / USB-OTG Ethernet Adapter -> Kobo (`eth0` / `usb0` / `rndis0` DHCP client).
 *   3. Kobo Wi-Fi -> Chromebook Plug-and-Play USB RNDIS Tethering (`192.168.42.129/24` +
 *      built-in RFC 2131 UDP `:67` DHCPv4 Server so ChromeOS connects instantly with zero setup!).
 */
public class Arc7WiredInternetActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell rootShell;
    private TextView statusPillTv;
    private TextView liveStatsTv;
    private TextView logTv;

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshLiveTelemetry();
            ui.postDelayed(this, 1000L);
        }
    };

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        rootShell = new RootShell(this);
        Arc7ChromebookUsbService.ensureStarted(this);
        Arc7TlsGatewayService.ensureStarted(this);

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        statusPillTv = new TextView(this);
        statusPillTv.setText("STANDBY");
        statusPillTv.setTextColor(Color.WHITE);
        statusPillTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        statusPillTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusPillTv.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusPillTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(this, 0xFF334155, 14));

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_wired_net,
                "USB Internet  •  Chromebook & USB Ethernet",
                "High-speed wired internet over Chromebook USB cable or USB-OTG Ethernet",
                statusPillTv));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(22));

        // Live Telemetry Banner Card
        LinearLayout teleCard = new LinearLayout(this);
        teleCard.setOrientation(LinearLayout.VERTICAL);
        teleCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        teleCard.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, pal, 14));

        liveStatsTv = new TextView(this);
        liveStatsTv.setTextColor(pal.textPrimary);
        liveStatsTv.setTypeface(Typeface.MONOSPACE);
        liveStatsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        teleCard.addView(liveStatsTv);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        body.addView(teleCard, cardLp);

        // Card 1: Chromebook -> Kobo Wired USB Internet (Works EVEN WITH ZERO WI-FI!)
        LinearLayout card1 = makeCard(pal,
                "1. Chromebook -> Kobo Wired USB Internet (Zero Wi-Fi Needed!)",
                "Plug your Kobo into any Chromebook, connect Kobo to Linux (Crostini) in ChromeOS USB settings,\n"
                + "copy 'Start-Chromebook-Wired-Internet.sh' from Kobo's Download folder (or GitHub Release), and run:\n"
                + "    bash ~/Downloads/Start-Chromebook-Wired-Internet.sh\n"
                + "• Arc7 Browser, F-Droid, Aurora Store, apt & Internet Archive Chimes tunnel TLS 1.3 AUTOMATICALLY!\n"
                + "• Tap 'Route ALL Apps Over USB' below to also tunnel 3rd-party apps & VLC via kernel NAT (`SO_ORIGINAL_DST`).");

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button exportScriptBtn = Arc7Skin.actionButton(this, "Save Script to Downloads", pal.accentDark);
        exportScriptBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = Arc7ChromebookUsbService.exportHelperScriptToDownloads(Arc7WiredInternetActivity.this);
                appendLog("Saved Chromebook bridge script -> " + (f != null ? f.getAbsolutePath() : "failed"));
                Toast.makeText(Arc7WiredInternetActivity.this,
                        "Saved to Download/Start-Chromebook-Wired-Internet.sh!", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bLp.rightMargin = dp(8);
        row1.addView(exportScriptBtn, bLp);

        Button allAppsNatBtn = Arc7Skin.actionButton(this, "Route ALL Apps Over USB", 0xFF059669);
        allAppsNatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBg(new Runnable() {
                    @Override
                    public void run() {
                        boolean next = !Arc7ChromebookUsbService.isSystemWideNatEnabled();
                        appendLog(Arc7ChromebookUsbService.setSystemWideTransparentUsbNat(
                                Arc7WiredInternetActivity.this, rootShell, next));
                    }
                });
            }
        });
        row1.addView(allAppsNatBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card1.addView(row1);
        body.addView(card1, cardLp);

        // Card 2: USB-OTG Ethernet Adapter / Chromebook Native Ethernet DHCP -> Kobo
        LinearLayout card2 = makeCard(pal,
                "2. USB-OTG Ethernet Adapter / Chromebook Ethernet Share -> Kobo",
                "Plug an Ethernet adapter (ASIX / Realtek CDC-ECM) via OTG cable OR share ChromeOS Ethernet over USB:");
        Button ethClientBtn = Arc7Skin.actionButton(this, "Activate USB Ethernet / DHCP Client (`eth0` / `rndis0`)", 0xFF2563EB);
        ethClientBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBg(new Runnable() {
                    @Override
                    public void run() {
                        appendLog(Arc7ChromebookUsbService.connectUsbEthernetAdapterOrChromebookDhcp(
                                Arc7WiredInternetActivity.this, rootShell));
                    }
                });
            }
        });
        card2.addView(ethClientBtn);
        body.addView(card2, cardLp);

        // Card 3: Kobo Wi-Fi -> Chromebook Plug-and-Play USB RNDIS Tethering
        LinearLayout card3 = makeCard(pal,
                "3. Share Kobo Internet -> Chromebook (Plug-and-Play USB RNDIS Tethering)",
                "Turns Kobo into a real plug-and-play USB Ethernet adapter (`192.168.42.129/24` + built-in DHCPv4 server) "
                + "so any Chromebook gets wired internet immediately with ZERO software installed on ChromeOS!");
        Button tetherBtn = Arc7Skin.actionButton(this, "Toggle USB RNDIS Tethering to Chromebook", 0xFF7C3AED);
        tetherBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBg(new Runnable() {
                    @Override
                    public void run() {
                        boolean next = !Arc7ChromebookUsbService.isRndisTetherEnabled();
                        appendLog(Arc7ChromebookUsbService.setUsbRndisTetherToChromebook(
                                Arc7WiredInternetActivity.this, rootShell, next));
                    }
                });
            }
        });
        card3.addView(tetherBtn);
        body.addView(card3, cardLp);

        // Console output card
        logTv = new TextView(this);
        logTv.setText("Wired USB listener ready on 127.0.0.1:18880 (Proxy :18881 • DNS :18853 • TLS 1.3 :8118 auto-chained).\n");
        logTv.setTextColor(0xFFA7F3D0);
        logTv.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 12));
        logTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        logTv.setTypeface(Typeface.MONOSPACE);
        logTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.addView(logTv);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        ui.post(statusTick);
    }

    private LinearLayout makeCard(Arc7Skin.Palette pal, String heading, String desc) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        c.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 14));

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextColor(pal.textPrimary);
        h.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        c.addView(h);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(pal.textMuted);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        d.setPadding(0, dp(5), 0, dp(10));
        c.addView(d);
        return c;
    }

    private void runBg(final Runnable task) {
        new Thread(task, "Arc7WiredAction").start();
    }

    private void appendLog(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                logTv.append("• " + msg + "\n");
                refreshLiveTelemetry();
            }
        });
    }

    private void refreshLiveTelemetry() {
        int slots = Arc7ChromebookUsbService.getConnectedChromebookSlots();
        boolean connected = slots > 0 || Arc7ChromebookUsbService.isRndisTetherEnabled();
        statusPillTv.setText(connected ? "USB WIRED LIVE" : "READY FOR USB");
        statusPillTv.setBackgroundDrawable(Arc7Skin.buttonDrawable(
                this, connected ? 0xFF059669 : 0xFF0284C7, 14));
        liveStatsTv.setText(String.format(Locale.US,
                "Chromebook USB Warm Streams : %d active on tcp:18880\n"
                + "Local HTTP/TLS 1.3 Tunnel   : 127.0.0.1:18881 -> 127.0.0.1:8118 (AUTO)\n"
                + "System-Wide Kernel NAT      : %s\n"
                + "Kobo -> Chromebook Tether   : %s\n"
                + "Connections / Traffic       : %d streams  •  %.2f MB transferred",
                slots,
                Arc7ChromebookUsbService.isSystemWideNatEnabled() ? "ON (All Apps Routed Over USB)" : "Auto-Proxy / On-Demand",
                Arc7ChromebookUsbService.isRndisTetherEnabled() ? "ON (`rndis0` 192.168.42.129/24 DHCP)" : "Standby",
                Arc7ChromebookUsbService.getConnectionsBridged(),
                Arc7ChromebookUsbService.getBytesTransferred() / 1048576.0));
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
