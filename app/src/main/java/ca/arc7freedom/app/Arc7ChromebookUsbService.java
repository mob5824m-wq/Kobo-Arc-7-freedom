package ca.arc7freedom.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Full-Duplex Chromebook <-> Kobo Arc 7 Wired USB Internet Engine (`v2.0.0`):
 *
 * 1. `Chromebook -> Kobo` Reverse-Pool USB Tunnel (`:18880` pool + `:18881` HTTP/HTTPS proxy + `:18853` DNS):
 *    Works on ALL Android 4.0.3 – 4.2.2 devices WITHOUT `adb reverse` (which only exists on Android 5.0+!).
 *    Automatically chains with `libarc7tls.so` (`127.0.0.1:8118`) for full verified 2026 TLS 1.3 over USB
 *    AND configures root kernel `SO_ORIGINAL_DST` transparent NAT (`libarc7tool.so usb-nat-relay 18882 18881`)!
 * 2. `Chromebook / OTG Ethernet -> Kobo` Native `rndis0` / `eth0` / `usb0` DHCP Client Mode.
 * 3. `Kobo Wi-Fi -> Chromebook` Plug-and-Play USB RNDIS Ethernet Tethering (`192.168.42.129/24` +
 *    built-in RFC 2131 UDP `:67` DHCPv4 server + NAT `MASQUERADE`).
 */
public class Arc7ChromebookUsbService extends Service {

    public static final String ACTION_START = "ca.arc7freedom.action.START_USB_INTERNET";
    public static final int USB_POOL_PORT = 18880;
    public static final int LOCAL_HTTP_PROXY_PORT = 18881;
    public static final int LOCAL_TRANSPARENT_NAT_PORT = 18882;
    public static final int LOCAL_DNS_UDP_PORT = 18853;

    private static final class WarmUsbSlot {
        final Socket socket;
        final long acceptedMs;

        WarmUsbSlot(Socket s) {
            this.socket = s;
            this.acceptedMs = System.currentTimeMillis();
        }
    }

    private static final LinkedBlockingDeque<WarmUsbSlot> USB_POOL =
            new LinkedBlockingDeque<WarmUsbSlot>(32);
    private static volatile ServerSocket poolServer;
    private static volatile ServerSocket proxyServer;
    private static volatile DatagramSocket dnsServer;
    private static volatile DatagramSocket dhcpServer;
    private static volatile boolean running = false;
    private static volatile boolean systemWideNatEnabled = false;
    private static volatile boolean rndisTetherEnabled = false;
    private static volatile boolean syncedOnceThisSession = false;
    private static final AtomicLong BYTES_TRANSFERRED = new AtomicLong(0L);
    private static final AtomicLong CONNECTIONS_BRIDGED = new AtomicLong(0L);

    public static void ensureStarted(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, Arc7ChromebookUsbService.class);
            i.setAction(ACTION_START);
            ctx.startService(i);
        } catch (Throwable ignored) {}
    }

    public static int getConnectedChromebookSlots() {
        pruneDeadSlots();
        return USB_POOL.size();
    }

    public static boolean isChromebookTunnelConnected() {
        return getConnectedChromebookSlots() > 0;
    }

    public static boolean isSystemWideNatEnabled() {
        return systemWideNatEnabled;
    }

    public static boolean isRndisTetherEnabled() {
        return rndisTetherEnabled;
    }

    public static long getBytesTransferred() {
        return BYTES_TRANSFERRED.get();
    }

    public static long getConnectionsBridged() {
        return CONNECTIONS_BRIDGED.get();
    }

    public static File exportHelperScriptToDownloads(Context ctx) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            File dl = new File(Environment.getExternalStorageDirectory(), "Download");
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "Start-Chromebook-Wired-Internet.sh");
            in = ctx.getAssets().open("chromebook/Start-Chromebook-Wired-Internet.sh");
            fos = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            out.setReadable(true, false);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startListenersIfNeeded();
        return START_STICKY;
    }

    private synchronized void startListenersIfNeeded() {
        if (running) return;
        running = true;
        exportHelperScriptToDownloads(getApplicationContext());

        // 1. Reverse-Pool Listener (`127.0.0.1:18880`) accepting Chromebook warm sockets
        new Thread(new Runnable() {
            @Override
            public void run() {
                runUsbPoolAcceptLoop();
            }
        }, "Arc7UsbPoolListener").start();

        // 2. Local HTTP / HTTPS CONNECT Tunnel Proxy (`127.0.0.1:18881`)
        new Thread(new Runnable() {
            @Override
            public void run() {
                runLocalProxyLoop();
            }
        }, "Arc7UsbLocalProxy").start();

        // 3. Local UDP DNS-over-Chromebook-USB Server (`127.0.0.1:18853`)
        new Thread(new Runnable() {
            @Override
            public void run() {
                runLocalDnsLoop();
            }
        }, "Arc7UsbDnsServer").start();
    }

    private static void pruneDeadSlots() {
        long now = System.currentTimeMillis();
        for (WarmUsbSlot slot : USB_POOL) {
            if (slot.socket.isClosed() || !slot.socket.isConnected() || (now - slot.acceptedMs) > 42000L) {
                if (USB_POOL.remove(slot)) {
                    try { slot.socket.close(); } catch (Throwable ignored) {}
                }
            }
        }
        if (USB_POOL.isEmpty()) {
            new File("/data/local/tmp/arc7_usb_net_active").delete();
        }
    }

    private void runUsbPoolAcceptLoop() {
        while (running) {
            try {
                poolServer = new ServerSocket();
                poolServer.setReuseAddress(true);
                poolServer.bind(new InetSocketAddress("127.0.0.1", USB_POOL_PORT));
                while (running) {
                    final Socket s = poolServer.accept();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            registerWarmSocket(s);
                        }
                    }, "Arc7UsbSlotInit").start();
                }
            } catch (Throwable t) {
                try { Thread.sleep(1000L); } catch (Throwable ignored) {}
            } finally {
                try { if (poolServer != null) poolServer.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void registerWarmSocket(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);
            String greet = readLineAscii(s.getInputStream(), 128);
            if (!greet.startsWith("ARC7USB1 READY")) {
                s.close();
                return;
            }
            s.setSoTimeout(0);
            pruneDeadSlots();
            while (!USB_POOL.offerLast(new WarmUsbSlot(s))) {
                WarmUsbSlot oldest = USB_POOL.pollFirst();
                if (oldest != null) {
                    try { oldest.socket.close(); } catch (Throwable ignored) {}
                }
            }
            markUsbActiveFlag(true);
            if (!syncedOnceThisSession) {
                syncedOnceThisSession = true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Context appCtx = getApplicationContext();
                            RootShell rs = new RootShell(appCtx);
                            NetworkCertManager ncm = new NetworkCertManager(appCtx, rs);
                            ncm.startBrowserGateway();
                            ncm.syncTimeAndTimezoneAutomatically(rs.isRootAvailable(), null);
                            Arc7ClockStore.autoSyncInternetArchiveChimes(appCtx, null);
                        } catch (Throwable ignored) {}
                    }
                }, "Arc7UsbAutoSync").start();
            }
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static void markUsbActiveFlag(boolean active) {
        try {
            File f = new File("/data/local/tmp/arc7_usb_net_active");
            if (active) {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write("1\n".getBytes("UTF-8"));
                fos.close();
                f.setReadable(true, false);
            } else {
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static Socket checkoutChromebookTunnel(String cmdLine, byte[] extraPayload) throws Exception {
        pruneDeadSlots();
        Exception lastErr = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            WarmUsbSlot slot = USB_POOL.pollLast(1800L, TimeUnit.MILLISECONDS);
            if (slot == null) break;
            Socket usb = slot.socket;
            try {
                usb.setSoTimeout(12000);
                OutputStream out = usb.getOutputStream();
                out.write((cmdLine + "\n").getBytes("UTF-8"));
                if (extraPayload != null && extraPayload.length > 0) {
                    out.write(extraPayload);
                }
                out.flush();
                return usb;
            } catch (Exception e) {
                lastErr = e;
                try { usb.close(); } catch (Throwable ignored) {}
            }
        }
        throw lastErr != null ? lastErr : new Exception("No active Chromebook USB bridge stream on port " + USB_POOL_PORT);
    }

    private void runLocalProxyLoop() {
        while (running) {
            try {
                proxyServer = new ServerSocket();
                proxyServer.setReuseAddress(true);
                proxyServer.bind(new InetSocketAddress("127.0.0.1", LOCAL_HTTP_PROXY_PORT));
                while (running) {
                    final Socket client = proxyServer.accept();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleLocalProxyClient(client);
                        }
                    }, "Arc7UsbProxyWorker").start();
                }
            } catch (Throwable t) {
                try { Thread.sleep(1000L); } catch (Throwable ignored) {}
            } finally {
                try { if (proxyServer != null) proxyServer.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void handleLocalProxyClient(Socket client) {
        Socket usb = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            InputStream cin = new BufferedInputStream(client.getInputStream(), 16384);
            String reqLine = readLineAscii(cin, 4096);
            if (reqLine.length() == 0) {
                client.close();
                return;
            }
            ByteArrayOutputStream restHdrs = new ByteArrayOutputStream();
            String hline;
            String hostHeader = "";
            while ((hline = readLineAscii(cin, 4096)).length() > 0) {
                if (hline.toLowerCase(Locale.US).startsWith("host:")) {
                    hostHeader = hline.substring(5).trim();
                }
                if (!hline.toLowerCase(Locale.US).startsWith("proxy-connection:")) {
                    restHdrs.write((hline + "\r\n").getBytes("UTF-8"));
                }
            }

            String[] parts = reqLine.split("\\s+");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];

            if ("CONNECT".equals(method)) {
                String hp = target.contains(":") ? target : (target + ":443");
                usb = checkoutChromebookTunnel("CONNECT " + hp, null);
                String status = readLineAscii(usb.getInputStream(), 256);
                if (!status.startsWith("OK")) {
                    client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8"));
                    client.close();
                    usb.close();
                    return;
                }
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
                client.getOutputStream().flush();
                client.setSoTimeout(0);
                usb.setSoTimeout(0);
                CONNECTIONS_BRIDGED.incrementAndGet();
                pumpBothWays(client, cin, usb, usb.getInputStream());
                usb = null;
                client = null;
            } else {
                // Plain HTTP proxy request (`GET http://example.com/foo HTTP/1.1`)
                String hostPort = hostHeader;
                String relPath = target;
                if (target.startsWith("http://")) {
                    String rest = target.substring(7);
                    int sl = rest.indexOf('/');
                    hostPort = sl >= 0 ? rest.substring(0, sl) : rest;
                    relPath = sl >= 0 ? rest.substring(sl) : "/";
                }
                if (!hostPort.contains(":")) hostPort = hostPort + ":80";
                ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
                forwarded.write((method + " " + relPath + " HTTP/1.1\r\n").getBytes("UTF-8"));
                forwarded.write(restHdrs.toByteArray());
                forwarded.write("\r\n".getBytes("UTF-8"));

                usb = checkoutChromebookTunnel("CONNECT " + hostPort, forwarded.toByteArray());
                String status = readLineAscii(usb.getInputStream(), 256);
                if (!status.startsWith("OK")) {
                    client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8"));
                    client.close();
                    usb.close();
                    return;
                }
                client.setSoTimeout(0);
                usb.setSoTimeout(0);
                CONNECTIONS_BRIDGED.incrementAndGet();
                pumpBothWays(client, cin, usb, usb.getInputStream());
                usb = null;
                client = null;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (client != null) client.close(); } catch (Throwable ignored) {}
            try { if (usb != null) usb.close(); } catch (Throwable ignored) {}
        }
    }

    private void runLocalDnsLoop() {
        while (running) {
            try {
                dnsServer = new DatagramSocket(null);
                dnsServer.setReuseAddress(true);
                dnsServer.bind(new InetSocketAddress("127.0.0.1", LOCAL_DNS_UDP_PORT));
                byte[] buf = new byte[1500];
                while (running) {
                    final DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    dnsServer.receive(pkt);
                    final byte[] query = new byte[pkt.getLength()];
                    System.arraycopy(pkt.getData(), pkt.getOffset(), query, 0, pkt.getLength());
                    final InetAddress clientAddr = pkt.getAddress();
                    final int clientPort = pkt.getPort();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Socket usb = null;
                            try {
                                usb = checkoutChromebookTunnel("DNSRAW " + query.length, query);
                                InputStream in = usb.getInputStream();
                                int hi = in.read();
                                int lo = in.read();
                                if (hi < 0 || lo < 0) return;
                                int ansLen = ((hi & 0xFF) << 8) | (lo & 0xFF);
                                if (ansLen >= 12 && ansLen <= 4096) {
                                    byte[] ans = new byte[ansLen];
                                    int got = 0;
                                    while (got < ansLen) {
                                        int n = in.read(ans, got, ansLen - got);
                                        if (n <= 0) break;
                                        got += n;
                                    }
                                    if (got == ansLen && dnsServer != null) {
                                        BYTES_TRANSFERRED.addAndGet(ansLen);
                                        dnsServer.send(new DatagramPacket(ans, ansLen, clientAddr, clientPort));
                                    }
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                try { if (usb != null) usb.close(); } catch (Throwable ignored) {}
                            }
                        }
                    }, "Arc7UsbDnsWorker").start();
                }
            } catch (Throwable t) {
                try { Thread.sleep(1000L); } catch (Throwable ignored) {}
            } finally {
                try { if (dnsServer != null) dnsServer.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void pumpBothWays(final Socket a, final InputStream ain,
                                     final Socket b, final InputStream bin) {
        Thread reverse = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[32768];
                try {
                    OutputStream aout = a.getOutputStream();
                    int n;
                    while ((n = bin.read(buf)) > 0) {
                        aout.write(buf, 0, n);
                        aout.flush();
                        BYTES_TRANSFERRED.addAndGet(n);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { a.shutdownOutput(); } catch (Throwable ignored) {}
                }
            }
        }, "Arc7UsbPumpDown");
        reverse.start();

        byte[] buf = new byte[32768];
        try {
            OutputStream bout = b.getOutputStream();
            int n;
            while ((n = ain.read(buf)) > 0) {
                bout.write(buf, 0, n);
                bout.flush();
                BYTES_TRANSFERRED.addAndGet(n);
            }
        } catch (Throwable ignored) {
        } finally {
            try { b.shutdownOutput(); } catch (Throwable ignored) {}
        }
        try { reverse.join(3000L); } catch (Throwable ignored) {}
        try { a.close(); } catch (Throwable ignored) {}
        try { b.close(); } catch (Throwable ignored) {}
    }

    private static String readLineAscii(InputStream in, int maxLen) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (bos.size() < maxLen) {
            int c = in.read();
            if (c < 0) break;
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /* ========================================================================
     * Root Transparent All-Apps NAT + USB Ethernet (`rndis0` / `eth0`) Modes
     * ======================================================================== */

    public static String setSystemWideTransparentUsbNat(Context ctx, RootShell shell, boolean enable) {
        ensureStarted(ctx);
        int myUid = android.os.Process.myUid();
        File tool = shell.getPartitionToolBinary();
        if (!shell.isRootAvailable()) {
            return "Root not yet active — Arc7 Browser, F-Droid, Aurora Store, apt & Internet Archive Chimes ALREADY work over USB right now! Tap 'Root Kobo Arc 7 Now' if you also want kernel iptables routing for 3rd-party apps.";
        }
        if (enable) {
            String qTool = RootShell.shellQuote(tool.getAbsolutePath());
            String cmd =
                    "iptables -t nat -N ARC7_USB 2>/dev/null || iptables -t nat -F ARC7_USB\n" +
                    "iptables -t nat -D OUTPUT -j ARC7_USB 2>/dev/null || true\n" +
                    "iptables -t nat -A ARC7_USB -m owner --uid-owner " + myUid + " -j RETURN\n" +
                    "iptables -t nat -A ARC7_USB -m owner --uid-owner 0 -j RETURN\n" +
                    "iptables -t nat -A ARC7_USB -d 127.0.0.0/8 -j RETURN\n" +
                    "iptables -t nat -A ARC7_USB -d 10.0.0.0/8 -j RETURN\n" +
                    "iptables -t nat -A ARC7_USB -d 192.168.0.0/16 -j RETURN\n" +
                    "iptables -t nat -A ARC7_USB -p udp --dport 53 -j REDIRECT --to-ports " + LOCAL_DNS_UDP_PORT + "\n" +
                    "iptables -t nat -A ARC7_USB -p tcp -j REDIRECT --to-ports " + LOCAL_TRANSPARENT_NAT_PORT + "\n" +
                    "iptables -t nat -A OUTPUT -j ARC7_USB\n" +
                    "settings put global http_proxy 127.0.0.1:" + LOCAL_HTTP_PROXY_PORT + " 2>/dev/null || true\n" +
                    "killall arc7_usb_nat 2>/dev/null || true\n" +
                    "cat " + qTool + " > /data/local/tmp/arc7_usb_nat && chmod 0755 /data/local/tmp/arc7_usb_nat\n" +
                    "(/data/local/tmp/arc7_usb_nat usb-nat-relay " + LOCAL_TRANSPARENT_NAT_PORT + " " + LOCAL_HTTP_PROXY_PORT + " </dev/null >/dev/null 2>&1 &)\n" +
                    "echo NAT_ON_OK";
            RootShell.ExecResult r = shell.execRoot(cmd, null);
            systemWideNatEnabled = r.stdout.contains("NAT_ON_OK");
            return systemWideNatEnabled
                    ? "ENABLED: Transparent kernel NAT (`SO_ORIGINAL_DST` :18882) + UDP `:53` DNS over USB active for EVERY app on Kobo Arc 7!"
                    : ("iptables output: " + r.stdout);
        } else {
            String cmd =
                    "iptables -t nat -D OUTPUT -j ARC7_USB 2>/dev/null || true\n" +
                    "iptables -t nat -F ARC7_USB 2>/dev/null || true\n" +
                    "iptables -t nat -X ARC7_USB 2>/dev/null || true\n" +
                    "settings delete global http_proxy 2>/dev/null || settings put global http_proxy :0 2>/dev/null || true\n" +
                    "killall arc7_usb_nat 2>/dev/null || true\n" +
                    "echo NAT_OFF_OK";
            shell.execRoot(cmd, null);
            systemWideNatEnabled = false;
            return "Restored direct Wi-Fi kernel routing cleanly (TLS 1.3 auto-fallback over USB stays ready automatically!).";
        }
    }

    public static String connectUsbEthernetAdapterOrChromebookDhcp(Context ctx, RootShell shell) {
        if (!shell.isRootAvailable()) {
            return "Root access required to configure kernel USB Ethernet (`rndis0` / `eth0` / `usb0`).";
        }
        String cmd =
                "setprop sys.usb.config rndis,adb 2>/dev/null || true\n" +
                "sleep 1\n" +
                "FOUND=''\n" +
                "for IFACE in eth0 eth1 usb0 rndis0; do\n" +
                "  if [ -d \"/sys/class/net/$IFACE\" ]; then\n" +
                "    ip link set \"$IFACE\" up 2>/dev/null || ifconfig \"$IFACE\" up 2>/dev/null || true\n" +
                "    (netcfg \"$IFACE\" dhcp || dhcpcd -ABKL \"$IFACE\") >/dev/null 2>&1 &\n" +
                "    sleep 2\n" +
                "    IP=''\n" +
                "    for _tok in $(ip -4 addr show \"$IFACE\" 2>/dev/null); do\n" +
                "      case \"$_tok\" in [0-9]*.*.*.*) IP=\"$_tok\"; break ;; esac\n" +
                "    done\n" +
                "    if [ -n \"$IP\" ]; then\n" +
                "      FOUND=\"$IFACE ($IP)\"\n" +
                "      ndc resolver setifdns \"$IFACE\" '' 1.1.1.1 8.8.8.8 2>/dev/null || true\n" +
                "      ndc resolver setdefaultif \"$IFACE\" 2>/dev/null || true\n" +
                "      break\n" +
                "    fi\n" +
                "  fi\n" +
                "done\n" +
                "if [ -n \"$FOUND\" ]; then echo \"WIRED_ETH_OK: $FOUND\"; else echo 'NO_DHCP_LEASE_YET'; fi";
        RootShell.ExecResult r = shell.execRoot(cmd, null);
        if (r.stdout.contains("WIRED_ETH_OK")) {
            return "Connected over native kernel USB Ethernet -> " + r.stdout.trim();
        }
        return "Switched USB gadget to `rndis,adb`! If your Chromebook doesn't share DHCP directly over USB Ethernet, run `bash ~/Downloads/Start-Chromebook-Wired-Internet.sh` inside Chromebook Linux Terminal for instant zero-driver USB internet!";
    }

    public static String setUsbRndisTetherToChromebook(Context ctx, RootShell shell, boolean enable) {
        if (!shell.isRootAvailable()) {
            return "Root access required to switch USB hardware to `rndis,adb` Ethernet tethering.";
        }
        if (!enable) {
            rndisTetherEnabled = false;
            try { if (dhcpServer != null) dhcpServer.close(); } catch (Throwable ignored) {}
            shell.execRoot(
                    "echo 0 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true; " +
                    "iptables -t nat -D POSTROUTING -s 192.168.42.0/24 -j MASQUERADE 2>/dev/null || true; " +
                    "setprop sys.usb.config mtp,adb 2>/dev/null || true", null);
            return "USB RNDIS tethering turned OFF (returned USB cable to `mtp,adb` mode).";
        }
        RootShell.ExecResult r = shell.execRoot(
                "setprop sys.usb.config rndis,adb\n" +
                "sleep 1\n" +
                "for IFACE in rndis0 usb0; do\n" +
                "  if [ -d \"/sys/class/net/$IFACE\" ]; then\n" +
                "    ip addr flush dev \"$IFACE\" 2>/dev/null || true\n" +
                "    ip addr add 192.168.42.129/24 dev \"$IFACE\" 2>/dev/null || ifconfig \"$IFACE\" 192.168.42.129 netmask 255.255.255.0 up\n" +
                "    ip link set \"$IFACE\" up 2>/dev/null || true\n" +
                "    echo 1 > /proc/sys/net/ipv4/ip_forward\n" +
                "    iptables -t nat -A POSTROUTING -s 192.168.42.0/24 -j MASQUERADE 2>/dev/null || true\n" +
                "    ip rule add from all lookup main pref 9999 2>/dev/null || true\n" +
                "    echo RNDIS_TETHER_OK:$IFACE\n" +
                "    break\n" +
                "  fi\n" +
                "done", null);
        rndisTetherEnabled = r.stdout.contains("RNDIS_TETHER_OK");
        if (rndisTetherEnabled) {
            startMiniDhcpServerForChromebook();
            return "LIVE! Kobo USB Ethernet (`192.168.42.129/24` + built-in DHCPv4 Server) is active — your Chromebook automatically sees 'Ethernet connected' (`192.168.42.100`) over USB!";
        }
        return "Result: " + r.stdout.trim();
    }

    private static void startMiniDhcpServerForChromebook() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (dhcpServer != null) dhcpServer.close();
                    dhcpServer = new DatagramSocket(null);
                    dhcpServer.setReuseAddress(true);
                    dhcpServer.setBroadcast(true);
                    dhcpServer.bind(new InetSocketAddress("0.0.0.0", 67));
                    byte[] req = new byte[1024];
                    byte[] serverIp = new byte[]{(byte) 192, (byte) 168, 42, (byte) 129};
                    byte[] clientIp = new byte[]{(byte) 192, (byte) 168, 42, 100};
                    while (rndisTetherEnabled) {
                        DatagramPacket p = new DatagramPacket(req, req.length);
                        dhcpServer.receive(p);
                        if (p.getLength() < 244 || req[0] != 1) continue;
                        int msgType = 1;
                        for (int i = 240; i + 2 < p.getLength() && (req[i] & 0xFF) != 255; ) {
                            int code = req[i] & 0xFF;
                            if (code == 0) { i++; continue; }
                            int len = req[i + 1] & 0xFF;
                            if (code == 53 && len >= 1) msgType = req[i + 2] & 0xFF;
                            i += 2 + len;
                        }
                        byte replyType = (byte) (msgType == 1 ? 2 : 5); // OFFER (2) or ACK (5)
                        byte[] resp = new byte[300];
                        resp[0] = 2; // BOOTREPLY
                        resp[1] = 1;
                        resp[2] = 6;
                        System.arraycopy(req, 4, resp, 4, 4); // xid
                        System.arraycopy(clientIp, 0, resp, 16, 4); // yiaddr
                        System.arraycopy(serverIp, 0, resp, 20, 4); // siaddr
                        System.arraycopy(req, 28, resp, 28, 16); // chaddr
                        resp[236] = 99; resp[237] = (byte) 130; resp[238] = 83; resp[239] = 99; // magic cookie
                        int o = 240;
                        resp[o++] = 53; resp[o++] = 1; resp[o++] = replyType;
                        resp[o++] = 54; resp[o++] = 4; System.arraycopy(serverIp, 0, resp, o, 4); o += 4;
                        resp[o++] = 51; resp[o++] = 4; resp[o++] = 0; resp[o++] = 0; resp[o++] = 0x0E; resp[o++] = 0x10;
                        resp[o++] = 1;  resp[o++] = 4; resp[o++] = (byte) 255; resp[o++] = (byte) 255; resp[o++] = (byte) 255; resp[o++] = 0;
                        resp[o++] = 3;  resp[o++] = 4; System.arraycopy(serverIp, 0, resp, o, 4); o += 4;
                        resp[o++] = 6;  resp[o++] = 8;
                        resp[o++] = 1; resp[o++] = 1; resp[o++] = 1; resp[o++] = 1;
                        resp[o++] = 8; resp[o++] = 8; resp[o++] = 8; resp[o++] = 8;
                        resp[o++] = (byte) 255;
                        dhcpServer.send(new DatagramPacket(
                                resp, o, InetAddress.getByName("255.255.255.255"), 68));
                    }
                } catch (Throwable ignored) {}
            }
        }, "Arc7UsbDhcpServer").start();
    }
}
