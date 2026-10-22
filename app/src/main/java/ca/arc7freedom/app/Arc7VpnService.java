package ca.arc7freedom.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Native System-Wide Android 4.2+ `/dev/tun` VPN Daemon (`Arc7VpnService` — `v2.3.0`):
 *
 *   - Uses genuine Android `android.net.VpnService` (`API 17`) so the Android key badge appears
 *     in the system bar and 100% of Kobo app IPv4 TCP + DNS traffic flows through `/dev/tun`!
 *   - Bridges `/dev/tun` over abstract Unix `SCM_RIGHTS` IPC directly into our static ARMv7 C
 *     packet engine (`libarc7vpn.so`), honoring real-time `VpnService.protect(int fd)` callbacks
 *     for zero routing loops across ALL modes:
 *       1. `"tailscale"` / `"socks5"` -> `127.0.0.1:1055` (`tailscaled` userspace daemon) or any SOCKS5 VPN
 *       2. `"tls"`                   -> Port-443 TLS 1.3 School Stealth Tunnel (`CONNECT` inside TLS 1.3)
 *       3. `"http"`                  -> HTTP-CONNECT VPN / School HTTP Proxy Tunnel
 *       4. `"direct"`                -> Zero-Server Encrypted DNS-over-HTTPS (`1.1.1.1` / `9.9.9.9` over TLS 1.3 `:443`)
 *   - Optional Root `iptables` Kill Switch blocks non-VPN outbound leaks cleanly!
 */
public class Arc7VpnService extends VpnService {

    public static final String ACTION_START = "ca.arc7freedom.app.VPN_START";
    public static final String ACTION_STOP = "ca.arc7freedom.app.VPN_STOP";
    public static final String PREFS = "arc7_vpn_prefs";
    private static final int NOTIF_ID = 7230;

    private static volatile boolean sActive = false;
    private static volatile String sActiveLabel = "Disconnected";

    private ParcelFileDescriptor tunInterface;
    private Process nativeVpnProcess;
    private LocalServerSocket ipcServer;
    private volatile boolean loopRunning = false;

    public static boolean isRunning() {
        return sActive;
    }

    public static String getActiveLabel() {
        return sActiveLabel;
    }

    public static File getStatusFile(Context ctx) {
        return new File(ctx.getFilesDir(), "arc7vpn_status.json");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnelCleanly();
            stopSelf();
            return START_NOT_STICKY;
        }
        startTunnelAsync();
        return START_STICKY;
    }

    private void startTunnelAsync() {
        stopTunnelCleanly();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String mode = sp.getString("mode", "direct");
        final String upstream = sp.getString("upstream", "127.0.0.1:1055");
        final String auth = sp.getString("auth", "");
        final String sni = sp.getString("sni", "");
        final String dohHost = sp.getString("doh_host", "1.1.1.1");
        final String label = sp.getString("profile_name", "Arc7 Stealth DoH + VPN");
        final boolean killSwitch = sp.getBoolean("kill_switch", false);

        Intent openUi = new Intent(this, Arc7VpnActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openUi, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notif = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle("Arc7 VPN Active: " + label)
                .setContentText("Mode: " + mode.toUpperCase() + "  \u2022  DNS-over-HTTPS TLS 1.3")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Builder builder = new Builder();
                    builder.setSession(label);
                    builder.setMtu(1500);
                    builder.addAddress("10.200.0.2", 24);
                    builder.addDnsServer(dohHost.contains(":") ? "1.1.1.1" : dohHost);
                    builder.addDnsServer("8.8.8.8");
                    builder.addRoute("0.0.0.0", 0);
                    builder.setConfigureIntent(pi);

                    tunInterface = builder.establish();
                    if (tunInterface == null) {
                        sActive = false;
                        sActiveLabel = "Permission required";
                        return;
                    }

                    final String ipcName = "arc7vpn_ipc_" + android.os.Process.myPid() + "_" + (System.currentTimeMillis() & 0xFFFF);
                    ipcServer = new LocalServerSocket(ipcName);
                    loopRunning = true;

                    Thread ipcThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serveScmRightsAndProtectLoop();
                        }
                    });
                    ipcThread.setDaemon(true);
                    ipcThread.start();

                    NetworkCertManager certMgr = new NetworkCertManager(Arc7VpnService.this);
                    File caBundle = certMgr.ensureCaBundleFile();
                    File statusFile = getStatusFile(Arc7VpnService.this);
                    File vpnBin = new File(getApplicationInfo().nativeLibraryDir, "libarc7vpn.so");

                    List<String> cmd = new ArrayList<String>();
                    cmd.add(vpnBin.getAbsolutePath());
                    cmd.add("tun2socks");
                    cmd.add("--tun-ipc");
                    cmd.add(ipcName);
                    cmd.add("--mode");
                    cmd.add("tailscale".equals(mode) ? "socks5" : mode);
                    if (!upstream.isEmpty()) {
                        cmd.add("--upstream");
                        cmd.add(upstream);
                    }
                    if (!auth.isEmpty()) {
                        cmd.add("--auth");
                        cmd.add(auth);
                    }
                    if (!sni.isEmpty()) {
                        cmd.add("--sni");
                        cmd.add(sni);
                    }
                    cmd.add("--doh-host");
                    cmd.add(dohHost.isEmpty() ? "1.1.1.1" : dohHost);
                    cmd.add("--cafile");
                    cmd.add(caBundle.getAbsolutePath());
                    cmd.add("--status-file");
                    cmd.add(statusFile.getAbsolutePath());

                    if (killSwitch) applyRootKillSwitch(true);

                    nativeVpnProcess = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    sActive = true;
                    sActiveLabel = label;

                    nativeVpnProcess.waitFor();
                } catch (Throwable t) {
                    sActiveLabel = "Stopped: " + t.getMessage();
                } finally {
                    sActive = false;
                    if (killSwitch) applyRootKillSwitch(false);
                }
            }
        }).start();
    }

    private void serveScmRightsAndProtectLoop() {
        LocalSocket client = null;
        try {
            client = ipcServer.accept();
            ParcelFileDescriptor pfd = tunInterface;
            if (pfd == null) return;

            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            // 1. Send /dev/tun FileDescriptor directly to libarc7vpn.so via SCM_RIGHTS
            client.setFileDescriptorsForSend(new FileDescriptor[]{ pfd.getFileDescriptor() });
            os.write('T');
            os.flush();
            client.setFileDescriptorsForSend(null);

            // 2. Receive outbound sockets from libarc7vpn.so and mark VpnService.protect(fd)
            while (loopRunning) {
                int b = is.read();
                if (b < 0) break;
                FileDescriptor[] recvFds = client.getAncillaryFileDescriptors();
                if (recvFds != null && recvFds.length > 0 && recvFds[0] != null) {
                    protectRawFileDescriptor(recvFds[0]);
                }
                os.write('K');
                os.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (client != null) client.close(); } catch (Throwable ignored) {}
        }
    }

    private void protectRawFileDescriptor(FileDescriptor fdObj) {
        ParcelFileDescriptor dupPfd = null;
        try {
            dupPfd = ParcelFileDescriptor.dup(fdObj);
            protect(dupPfd.getFd());
        } catch (Throwable t) {
            try {
                Field f = FileDescriptor.class.getDeclaredField("descriptor");
                f.setAccessible(true);
                int raw = f.getInt(fdObj);
                if (raw >= 0) protect(raw);
            } catch (Throwable ignored) {}
        } finally {
            try { if (dupPfd != null) dupPfd.close(); } catch (Throwable ignored) {}
            try {
                Field f = FileDescriptor.class.getDeclaredField("descriptor");
                f.setAccessible(true);
                int orig = f.getInt(fdObj);
                if (orig >= 0) ParcelFileDescriptor.adoptFd(orig).close();
            } catch (Throwable ignored) {}
        }
    }

    private void applyRootKillSwitch(boolean enable) {
        RootShell root = new RootShell(this);
        if (!root.isRooted()) return;
        int myUid = android.os.Process.myUid();
        if (enable) {
            root.execRoot(
                    "iptables -N ARC7_VPN_KS 2>/dev/null || iptables -F ARC7_VPN_KS\n"
                    + "iptables -A ARC7_VPN_KS -o lo -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -o tun+ -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -m owner --uid-owner 0 -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -m owner --uid-owner " + myUid + " -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -d 10.0.0.0/8 -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -d 172.16.0.0/12 -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -d 192.168.0.0/16 -j ACCEPT\n"
                    + "iptables -A ARC7_VPN_KS -j REJECT\n"
                    + "iptables -C OUTPUT -j ARC7_VPN_KS 2>/dev/null || iptables -I OUTPUT 1 -j ARC7_VPN_KS\n");
        } else {
            root.execRoot(
                    "iptables -D OUTPUT -j ARC7_VPN_KS 2>/dev/null || true\n"
                    + "iptables -F ARC7_VPN_KS 2>/dev/null || true\n");
        }
    }

    private void stopTunnelCleanly() {
        loopRunning = false;
        sActive = false;
        sActiveLabel = "Disconnected";
        try { if (ipcServer != null) ipcServer.close(); } catch (Throwable ignored) {}
        ipcServer = null;
        try { if (nativeVpnProcess != null) nativeVpnProcess.destroy(); } catch (Throwable ignored) {}
        nativeVpnProcess = null;
        try { if (tunInterface != null) tunInterface.close(); } catch (Throwable ignored) {}
        tunInterface = null;
        stopForeground(true);
    }

    @Override
    public void onRevoke() {
        stopTunnelCleanly();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnelCleanly();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
