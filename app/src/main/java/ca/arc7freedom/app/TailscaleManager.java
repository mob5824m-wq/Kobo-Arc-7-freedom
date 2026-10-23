package ca.arc7freedom.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Official Tailscale (`tailscaled` + `tailscale` ARMv7 Userspace Mesh Daemon) + WireGuard (`.conf`)
 * + OpenVPN (`.ovpn`) Manager for Android 4.2.2 (`TailscaleManager` — `v2.3.0`):
 *
 *   - Official Tailscale Android APK requires Android 8.0+ (`minSdk=26`). `TailscaleManager` brings
 *     REAL upstream Tailscale (`pkgs.tailscale.com/stable/tailscale_*_arm.tgz`) to Android 4.2.2 by:
 *       1. Downloading via `arc7tls` TLS 1.3 OR importing any local `tailscale_*_arm.tgz` on `/sdcard/`
 *       2. Extracting `tailscaled` + `tailscale` in pure streaming Java (`GZIPInputStream` + POSIX UStar)
 *       3. Launching `tailscaled --tun=userspace-networking --socks5-server=127.0.0.1:1055
 *          --outbound-http-proxy-listen=127.0.0.1:1056` with our 2026 `SSL_CERT_FILE` CA bundle
 *          (connects through strict school firewalls over standard TCP Port 443 HTTPS DERP relays!)
 *       4. Automatically binding `Arc7VpnService` (`/dev/tun` -> `libarc7vpn.so` -> `127.0.0.1:1055`)
 *          so EVERY app on the Kobo routes seamlessly over your Tailnet & Tailscale Exit Nodes!
 */
public final class TailscaleManager {

    public static final int TAILSCALE_SOCKS5_PORT = 1055;
    public static final int TAILSCALE_HTTP_PROXY_PORT = 1056;
    public static final String LINUX34_OFFICIAL_TARBALL = "tailscale_1.78.1_arm.tgz";
    public static final String LINUX34_OFFICIAL_TARBALL_URL =
            "https://pkgs.tailscale.com/stable/tailscale_1.78.1_arm.tgz";

    private static final java.util.regex.Pattern AUTH_URL_REGEX = java.util.regex.Pattern.compile(
            "(https://[A-Za-z0-9._:-]+/[aA]/[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+)");
    /** Headscale / self-hosted control servers use `https://host/register/<id>` instead of `/a/<id>`. */
    private static final java.util.regex.Pattern REGISTER_URL_REGEX = java.util.regex.Pattern.compile(
            "(https://[A-Za-z0-9._:-]+/register/[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+)");
    private static final java.util.regex.Pattern ANY_URL_REGEX = java.util.regex.Pattern.compile(
            "https://[A-Za-z0-9._:/-]+");

    public static final class TailnetPeer {
        public String hostName;
        public String dnsName;
        public String tailscaleIp;
        public boolean online;
        public boolean exitNodeOption;
    }

    public static final class WireGuardConfig {
        public String privateKey = "";
        public String address = "10.200.0.2/24";
        public String dns = "1.1.1.1";
        public String publicKey = "";
        public String presharedKey = "";
        public String endpoint = "";
        public String allowedIps = "0.0.0.0/0";
    }

    private TailscaleManager() {}

    public static File binDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "tailscale_bin");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File tailscaledBin(Context ctx) {
        return new File(binDir(ctx), "tailscaled");
    }

    public static File tailscaleCliBin(Context ctx) {
        return new File(binDir(ctx), "tailscale");
    }

    public static boolean isTailscaleInstalled(Context ctx) {
        File d = tailscaledBin(ctx);
        File c = tailscaleCliBin(ctx);
        return d.exists() && d.length() > 1024 && c.exists() && c.length() > 1024;
    }

    /**
     * Pure-Java POSIX UStar `.tar.gz` / `.tgz` extractor for `tailscale_*_arm.tgz` —
     * requires ZERO external `tar` binary on stock Kobo Arc 7 `toolbox`!
     */
    public static int extractTailscaleTarGz(File tgzFile, File outDir) throws Exception {
        if (!outDir.exists()) outDir.mkdirs();
        int extracted = 0;
        InputStream gis = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tgzFile)));
        try {
            byte[] header = new byte[512];
            byte[] buf = new byte[32768];
            while (true) {
                int r = readFullyOrEof(gis, header, 512);
                if (r < 512 || isAllZero(header)) break;

                String name = parseTarString(header, 0, 100);
                String prefix = parseTarString(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = parseTarOctal(header, 124, 12);
                byte typeFlag = header[156];

                String base = name;
                int slash = base.lastIndexOf('/');
                if (slash >= 0) base = base.substring(slash + 1);

                boolean want = (typeFlag == '0' || typeFlag == 0)
                        && ("tailscaled".equals(base) || "tailscale".equals(base));

                if (want && size > 0) {
                    File dst = new File(outDir, base);
                    FileOutputStream fos = new FileOutputStream(dst);
                    long rem = size;
                    try {
                        while (rem > 0) {
                            int chunk = (int) Math.min(buf.length, rem);
                            int n = gis.read(buf, 0, chunk);
                            if (n <= 0) break;
                            fos.write(buf, 0, n);
                            rem -= n;
                        }
                    } finally {
                        fos.close();
                    }
                    dst.setExecutable(true, false);
                    dst.setReadable(true, false);
                    extracted++;
                } else {
                    skipExact(gis, size);
                }

                long pad = (512L - (size % 512L)) % 512L;
                if (pad > 0) skipExact(gis, pad);
            }
        } finally {
            gis.close();
        }
        return extracted;
    }

    private static int readFullyOrEof(InputStream in, byte[] b, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    private static void skipExact(InputStream in, long bytes) throws Exception {
        byte[] dump = new byte[8192];
        long rem = bytes;
        while (rem > 0) {
            int n = in.read(dump, 0, (int) Math.min(dump.length, rem));
            if (n <= 0) break;
            rem -= n;
        }
    }

    private static boolean isAllZero(byte[] b) {
        for (int i = 0; i < b.length; i++) if (b[i] != 0) return false;
        return true;
    }

    private static String parseTarString(byte[] b, int off, int maxLen) {
        int end = off;
        while (end < off + maxLen && b[end] != 0) end++;
        try {
            return new String(b, off, end - off, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static long parseTarOctal(byte[] b, int off, int len) {
        long val = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (val > 0) break;
                continue;
            }
            if (c >= '0' && c <= '7') val = (val << 3) + (c - '0');
        }
        return val;
    }

    /**
     * Downloads & installs official upstream `tailscale_*_arm.tgz` over TLS 1.3 via `arc7tls`
     * (or imports a pre-downloaded tarball from `/sdcard/Download/tailscale*_arm.tgz`)!
     */
    public static String installOrUpdateOfficialTailscale(Context ctx) throws Exception {
        // 1. Check `/sdcard/Download/` first for instant offline install if user dropped tarball!
        File dlDir = new File("/sdcard/Download");
        if (dlDir.exists() && dlDir.isDirectory()) {
            File[] files = dlDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    String fn = files[i].getName().toLowerCase(Locale.US);
                    if (fn.startsWith("tailscale") && (fn.endsWith(".tgz") || fn.endsWith(".tar.gz"))) {
                        int n = extractTailscaleTarGz(files[i], binDir(ctx));
                        if (n >= 2) {
                            markExecutableViaRoot(ctx);
                            return "Installed offline " + files[i].getName() + " (" + n + " ARMv7 binaries)!";
                        }
                    }
                }
            }
        }

        // 2. Download official Tailscale static Linux ARMv7 release over TLS 1.3 Gateway.
        // Kobo Arc 7 (`MT8125`) runs Linux kernel `3.4.5`, so select official Go 1.23 build
        // `tailscale_1.78.1_arm.tgz` FIRST on Linux `< 3.17` (avoiding Go 1.24+ `FATAL: kernel too old`
        // while keeping 100% current `login.tailscale.com` Official Google Sign-In support!).
        NetworkCertManager certMgr = new NetworkCertManager(ctx);
        try { certMgr.ensureCaBundleFile(); } catch (Throwable ignored) {}
        certMgr.startBrowserGateway();

        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", NetworkCertManager.DEFAULT_PROXY_PORT));

        String armTarball = LINUX34_OFFICIAL_TARBALL;
        String kv = System.getProperty("os.version", "3.4.5");
        boolean modernKernel = false;
        try {
            String[] p = kv.split("[^0-9]+");
            if (p.length >= 2) {
                int maj = Integer.parseInt(p[0]);
                int min = Integer.parseInt(p[1]);
                modernKernel = maj > 3 || (maj == 3 && min >= 17);
            }
        } catch (Throwable ignored) {}
        if (modernKernel) {
            try {
                String metaJson = fetchViaGateway("http://127.0.0.1:" + NetworkCertManager.BROWSER_GATEWAY_PORT
                        + "/__https/pkgs.tailscale.com/stable/?mode=json");
                armTarball = new JSONObject(metaJson).getJSONObject("Tarballs").getString("arm");
            } catch (Throwable ignored) {}
        }

        File tmpTgz = new File(ctx.getCacheDir(), armTarball);
        downloadViaGatewayToFile("http://127.0.0.1:" + NetworkCertManager.BROWSER_GATEWAY_PORT
                + "/__https/pkgs.tailscale.com/stable/" + armTarball, tmpTgz, proxy);

        int count = extractTailscaleTarGz(tmpTgz, binDir(ctx));
        tmpTgz.delete();
        markExecutableViaRoot(ctx);
        return "Installed official Tailscale ARMv7 (" + armTarball + ", " + count + " binaries — Official Google Sign-In Ready)!";
    }

    private static void markExecutableViaRoot(Context ctx) {
        RootShell root = new RootShell(ctx);
        if (root.isRooted()) {
            root.execRoot("chmod 755 '" + tailscaledBin(ctx).getAbsolutePath() + "' '"
                    + tailscaleCliBin(ctx).getAbsolutePath() + "' 2>/dev/null || true\n");
        }
    }

    private static String fetchViaGateway(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return baos.toString("UTF-8");
    }

    private static void downloadViaGatewayToFile(String urlStr, File dst, Proxy ignored) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        InputStream in = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            fos.close();
            in.close();
        }
    }

    /**
     * Starts `tailscaled --tun=userspace-networking` AND requests login (`--authkey=...` OR interactive URL)!
     */
    public static String startDaemonAndUp(Context ctx, String authKey, String exitNodeIp, String customControlUrl) throws Exception {
        if (!isTailscaleInstalled(ctx)) {
            return "Tailscale binary not installed yet — tap '1-Tap Install Official Tailscale' first!";
        }
        NetworkCertManager certMgr = new NetworkCertManager(ctx);
        File caFile = certMgr.ensureCaBundleFile();
        File stateDir = new File(ctx.getFilesDir(), "tailscale_state");
        if (!stateDir.exists()) stateDir.mkdirs();
        File sockFile = new File(stateDir, "tailscaled.sock");
        File logFile = new File(stateDir, "tailscaled.log");

        // Ensure `/etc/resolv.conf` exists via pure `mksh` builtin `echo` so Go DNS resolves `controlplane.tailscale.com` & `login.tailscale.com` cleanly on Android 4.2!
        RootShell root = new RootShell(ctx);
        if (root.isRooted()) {
            root.execRoot("if [ ! -s /etc/resolv.conf ]; then mount -o remount,rw /system 2>/dev/null; "
                    + "(echo 'nameserver 1.1.1.1'; echo 'nameserver 8.8.8.8') > /etc/resolv.conf 2>/dev/null; "
                    + "mount -o remount,ro /system 2>/dev/null; fi\n");
        }

        // Only launch a new `tailscaled` if socket is not already live!
        if (queryStatusJson(ctx).trim().isEmpty()) {
            List<String> daemonCmd = new ArrayList<String>();
            daemonCmd.add(tailscaledBin(ctx).getAbsolutePath());
            daemonCmd.add("--tun=userspace-networking");
            daemonCmd.add("--statedir=" + stateDir.getAbsolutePath());
            daemonCmd.add("--socket=" + sockFile.getAbsolutePath());
            daemonCmd.add("--socks5-server=127.0.0.1:" + TAILSCALE_SOCKS5_PORT);
            daemonCmd.add("--outbound-http-proxy-listen=127.0.0.1:" + TAILSCALE_HTTP_PROXY_PORT);

            ProcessBuilder pb = new ProcessBuilder(daemonCmd);
            pb.environment().put("SSL_CERT_FILE", caFile.getAbsolutePath());
            pb.environment().put("TS_DEBUG_TLS_DIAL_INSECURE_SKIP_VERIFY", "false");
            pb.redirectErrorStream(true);
            final Process daemonProc = pb.start();
            // `tailscaled` logs constantly: with nobody reading its pipe it fills (~64 KB) and the
            // daemon freezes, so `tailscale up` then never prints the login URL. Drain it forever.
            drainToLog(daemonProc, new File(stateDir, "tailscaled.log"));
            // Give the socket a moment to appear before asking it for a status.
            for (int i = 0; i < 12; i++) {
                if (!queryStatusJson(ctx).trim().isEmpty()) break;
                Thread.sleep(400L);
            }
        }

        // Official Interactive Google Sign-In flow when no pre-auth key is supplied!
        if (authKey == null || authKey.trim().isEmpty()) {
            if (isTailscaleAuthenticated(ctx)) {
                return queryStatusSummary(ctx);
            }
            // Suspend VpnService while signing in so `controlplane.tailscale.com` & `accounts.google.com` never blackhole!
            try {
                android.content.Intent stopVpn = new android.content.Intent(ctx, Arc7VpnService.class);
                stopVpn.setAction(Arc7VpnService.ACTION_STOP);
                ctx.startService(stopVpn);
            } catch (Throwable ignored) {}

            List<String> bgUp = new ArrayList<String>();
            bgUp.add(tailscaleCliBin(ctx).getAbsolutePath());
            bgUp.add("--socket=" + sockFile.getAbsolutePath());
            bgUp.add("up");
            bgUp.add("--reset");
            bgUp.add("--accept-dns=true");
            bgUp.add("--accept-routes=true");
            bgUp.add("--hostname=kobo-arc-7");
            if (exitNodeIp != null && !exitNodeIp.trim().isEmpty()) {
                bgUp.add("--exit-node=" + exitNodeIp.trim());
                bgUp.add("--exit-node-allow-lan-access=true");
            }
            if (customControlUrl != null && !customControlUrl.trim().isEmpty()) {
                bgUp.add("--login-server=" + customControlUrl.trim());
            }
            ProcessBuilder bgPb = new ProcessBuilder(bgUp);
            bgPb.environment().put("SSL_CERT_FILE", caFile.getAbsolutePath());
            bgPb.redirectErrorStream(true);
            final Process bgProc = bgPb.start();
            final StringBuilder captured = new StringBuilder();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream is = bgProc.getInputStream();
                        byte[] b = new byte[1024];
                        int r;
                        while ((r = is.read(b)) > 0) {
                            synchronized (captured) {
                                captured.append(new String(b, 0, r, "UTF-8"));
                            }
                        }
                        is.close();
                    } catch (Throwable ignored) {}
                }
            }).start();

            // 40 x 750 ms = 30 s: a cold `tailscaled` + DNS + control-plane round trip can easily
            // take 10-15 s on a 2013 tablet, and the old 12 s window often timed out *just* before
            // the URL appeared — which is why the sign-in page never popped up.
            for (int i = 0; i < 40; i++) {
                Thread.sleep(750L);
                if (isTailscaleAuthenticated(ctx)) {
                    return queryStatusSummary(ctx);
                }
                String curUrl = queryCurrentAuthUrl(ctx);
                if (curUrl.isEmpty()) {
                    synchronized (captured) {
                        curUrl = extractAuthUrlFromText(captured.toString());
                    }
                }
                if (!curUrl.isEmpty()) {
                    appendLog(logFile, "AuthURL: " + curUrl);
                    return "AuthURL: " + curUrl + "\n" + queryStatusSummary(ctx);
                }
            }
            synchronized (captured) {
                appendLog(logFile, captured.toString());
                String tail = readTail(new File(stateDir, "tailscaled.log"), 14);
                String out = captured.toString().trim();
                if (tail.length() > 0) out = out + "\n--- tailscaled.log ---\n" + tail;
                return out.length() > 0 ? out : queryStatusSummary(ctx);
            }
        }

        List<String> upCmd = new ArrayList<String>();
        upCmd.add(tailscaleCliBin(ctx).getAbsolutePath());
        upCmd.add("--socket=" + sockFile.getAbsolutePath());
        upCmd.add("up");
        upCmd.add("--accept-dns=true");
        upCmd.add("--accept-routes=true");
        upCmd.add("--hostname=kobo-arc-7");
        upCmd.add("--authkey=" + authKey.trim());
        if (exitNodeIp != null && !exitNodeIp.trim().isEmpty()) {
            upCmd.add("--exit-node=" + exitNodeIp.trim());
            upCmd.add("--exit-node-allow-lan-access=true");
        }
        if (customControlUrl != null && !customControlUrl.trim().isEmpty()) {
            upCmd.add("--login-server=" + customControlUrl.trim());
        }

        ProcessBuilder upPb = new ProcessBuilder(upCmd);
        upPb.environment().put("SSL_CERT_FILE", caFile.getAbsolutePath());
        upPb.redirectErrorStream(true);
        Process upProc = upPb.start();
        InputStream in = upProc.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        in.close();
        String out = baos.toString("UTF-8");
        appendLog(logFile, out);
        return out.trim().isEmpty() ? queryStatusSummary(ctx) : out.trim();
    }

    public static String queryStatusJson(Context ctx) {
        if (!isTailscaleInstalled(ctx)) return "";
        try {
            File sockFile = new File(new File(ctx.getFilesDir(), "tailscale_state"), "tailscaled.sock");
            Process p = new ProcessBuilder(
                    tailscaleCliBin(ctx).getAbsolutePath(),
                    "--socket=" + sockFile.getAbsolutePath(),
                    "status", "--json")
                    .redirectErrorStream(true)
                    .start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            in.close();
            return baos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public static List<TailnetPeer> parseTailscalePeers(String statusJson) {
        List<TailnetPeer> peers = new ArrayList<TailnetPeer>();
        if (statusJson == null || statusJson.isEmpty()) return peers;
        try {
            JSONObject root = new JSONObject(statusJson);
            JSONObject peerMap = root.optJSONObject("Peer");
            if (peerMap == null) return peers;
            Iterator<String> keys = peerMap.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject p = peerMap.optJSONObject(k);
                if (p == null) continue;
                TailnetPeer tp = new TailnetPeer();
                tp.hostName = p.optString("HostName", "peer");
                tp.dnsName = p.optString("DNSName", "");
                tp.online = p.optBoolean("Online", false);
                tp.exitNodeOption = p.optBoolean("ExitNodeOption", false);
                org.json.JSONArray ips = p.optJSONArray("TailscaleIPs");
                tp.tailscaleIp = (ips != null && ips.length() > 0) ? ips.optString(0, "") : "";
                peers.add(tp);
            }
        } catch (Throwable ignored) {}
        return peers;
    }

    public static String queryStatusSummary(Context ctx) {
        String raw = queryStatusJson(ctx);
        if (raw.isEmpty()) return "tailscaled idle (not running yet)";
        try {
            JSONObject root = new JSONObject(raw);
            String state = root.optString("BackendState", "Unknown");
            String authUrl = root.optString("AuthURL", "");
            JSONObject self = root.optJSONObject("Self");
            String myIp = "";
            if (self != null && self.optJSONArray("TailscaleIPs") != null
                    && self.optJSONArray("TailscaleIPs").length() > 0) {
                myIp = self.optJSONArray("TailscaleIPs").optString(0, "");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Tailscale State : ").append(state).append("\n");
            if (!myIp.isEmpty()) sb.append("Kobo Tailnet IP : ").append(myIp).append("\n");
            if (!authUrl.isEmpty()) sb.append("Login URL       : ").append(authUrl).append("\n");
            List<TailnetPeer> peers = parseTailscalePeers(raw);
            sb.append("Tailnet Peers   : ").append(peers.size()).append("\n");
            for (int i = 0; i < peers.size(); i++) {
                TailnetPeer tp = peers.get(i);
                sb.append("  \u2022 ").append(tp.hostName).append(" (").append(tp.tailscaleIp).append(") ")
                  .append(tp.online ? "[ONLINE]" : "[offline]")
                  .append(tp.exitNodeOption ? " [EXIT NODE]" : "").append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return raw;
        }
    }

    /**
     * Parses standard WireGuard `[Interface]` + `[Peer]` `.conf` profiles cleanly.
     */
    public static WireGuardConfig parseWireGuardConf(String text) {
        WireGuardConfig cfg = new WireGuardConfig();
        if (text == null) return cfg;
        String section = "";
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.isEmpty() || ln.startsWith("#") || ln.startsWith(";")) continue;
            if (ln.startsWith("[") && ln.endsWith("]")) {
                section = ln.toLowerCase(Locale.US);
                continue;
            }
            int eq = ln.indexOf('=');
            if (eq <= 0) continue;
            String k = ln.substring(0, eq).trim().toLowerCase(Locale.US);
            String v = ln.substring(eq + 1).trim();
            if ("[interface]".equals(section)) {
                if ("privatekey".equals(k)) cfg.privateKey = v;
                else if ("address".equals(k)) cfg.address = v;
                else if ("dns".equals(k)) cfg.dns = v;
            } else if ("[peer]".equals(section)) {
                if ("publickey".equals(k)) cfg.publicKey = v;
                else if ("presharedkey".equals(k)) cfg.presharedKey = v;
                else if ("endpoint".equals(k)) cfg.endpoint = v;
                else if ("allowedips".equals(k)) cfg.allowedIps = v;
            }
        }
        return cfg;
    }

    /**
     * Parses standard OpenVPN `.ovpn` `remote <host> <port> [proto]` directives.
     */
    public static Map<String, String> parseOpenVpnConf(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) return out;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.isEmpty() || ln.startsWith("#") || ln.startsWith(";")) continue;
            if (ln.startsWith("remote ")) {
                String[] p = ln.split("\\s+");
                if (p.length >= 2) out.put("host", p[1]);
                if (p.length >= 3) out.put("port", p[2]);
                if (p.length >= 4) out.put("proto", p[3]);
            } else if (ln.startsWith("proto ")) {
                out.put("proto", ln.substring(6).trim());
            }
        }
        return out;
    }

    public static String extractAuthUrlFromText(String text) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Matcher m = AUTH_URL_REGEX.matcher(text);
        if (m.find()) return trimUrl(m.group(1));
        m = REGISTER_URL_REGEX.matcher(text);
        if (m.find()) return trimUrl(m.group(1));
        // Last resort: any https URL that mentions a login/auth host (some control servers print
        // "visit https://headscale.example.com/..." without the /a/ or /register/ path).
        m = ANY_URL_REGEX.matcher(text);
        while (m.find()) {
            String u = trimUrl(m.group());
            String low = u.toLowerCase(Locale.US);
            if (low.contains("login.tailscale.com") || low.contains("controlplane.tailscale.com")
                    || low.contains("/a/") || low.contains("/register/")) {
                return u;
            }
        }
        return "";
    }

    private static String trimUrl(String u) {
        if (u == null) return "";
        int cut = u.length();
        while (cut > 0) {
            char c = u.charAt(cut - 1);
            if (c == '.' || c == ',' || c == ')' || c == ']' || c == ';' || c == '"' || c == '\'') cut--;
            else break;
        }
        return u.substring(0, cut).trim();
    }

    /** Opens the official Tailscale / Google login page so the user never has to hunt for it. */
    public static String openLoginPage(Context ctx, String url) {
        if (url == null || !url.startsWith("https://")) {
            return "No Tailscale login URL yet - the tablet is still contacting the control server.";
        }
        // Google OAuth refuses to run inside an embedded WebView, so a real browser wins when one
        // is installed. 4.2 tablets usually only ship the stock Browser, whose TLS stack is too old
        // for 2026 Google - our Arc7 Browser bridges TLS 1.3 and is the fallback.
        String[] external = {
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.opera.browser",
                "com.sec.android.app.sbrowser",
                "com.android.browser",
        };
        for (int i = 0; i < external.length; i++) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                it.setPackage(external[i]);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return "Opened the Tailscale sign-in page in " + external[i];
            } catch (Throwable ignored) {}
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return "Opened the Tailscale sign-in page in the system browser";
        } catch (Throwable ignored) {}
        try {
            Intent it = new Intent(ctx, Arc7BrowserActivity.class);
            it.setData(Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return "Opened the Tailscale sign-in page in Arc7 Browser (TLS 1.3 bridge)";
        } catch (Throwable t) {
            return "Could not open the sign-in page automatically (" + t.getMessage()
                    + ") - scan the QR code with your phone instead.";
        }
    }

    public static void copyLoginUrl(Context ctx, String url) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Tailscale login", url));
        } catch (Throwable ignored) {}
    }

    /** Background pump that keeps a child process from blocking on a full stdout pipe. */
    private static void drainToLog(final Process process, final File out) {
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    java.io.BufferedReader br = null;
                    try {
                        br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(process.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            appendLog(out, line);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { if (br != null) br.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "arc7-tailscaled-log");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {}
    }

    /** Appends to the daemon log, trimming the file so it can never fill the data partition. */
    private static synchronized void appendLog(File f, String txt) {
        if (f == null || txt == null || txt.isEmpty()) return;
        try {
            if (f.exists() && f.length() > 192 * 1024) {
                String keep = readTail(f, 300);
                FileOutputStream trunc = new FileOutputStream(f, false);
                try { trunc.write(keep.getBytes("UTF-8")); } finally { trunc.close(); }
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(txt.getBytes("UTF-8"));
                fos.write('\n');
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {}
    }

    /** Returns the last {@code lines} lines of a (possibly large) log file. */
    static String readTail(File f, int lines) {
        if (f == null || !f.exists() || lines <= 0) return "";
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 16384);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String[] all = new String(buf, "UTF-8").split("\\r?\\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, all.length - lines);
            for (int i = from; i < all.length; i++) {
                if (all[i].trim().isEmpty()) continue;
                sb.append(all[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    /** Diagnostics shown in the VPN screen when no login URL appears. */
    public static String diagnostics(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("tailscaled log:\n");
        String tail = readTail(new File(new File(ctx.getFilesDir(), "tailscale_state"), "tailscaled.log"), 18);
        sb.append(tail.isEmpty() ? "(empty - daemon has not written anything yet)" : tail);
        return sb.toString();
    }

    public static boolean isTailscaleAuthenticated(Context ctx) {
        String raw = queryStatusJson(ctx);
        if (raw.isEmpty()) return false;
        try {
            return "Running".equalsIgnoreCase(new JSONObject(raw).optString("BackendState", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pulls `AuthURL` out of an already-fetched `tailscale status --json` payload (no extra process). */
    public static String queryAuthUrlFromStatus(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return "";
        try {
            String u = new JSONObject(rawJson).optString("AuthURL", "");
            if (u.startsWith("https://")) return u;
        } catch (Throwable ignored) {}
        return "";
    }

    /** `BackendState == Running` from an already-fetched status payload. */
    public static boolean isAuthenticatedFromStatus(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return false;
        try {
            return "Running".equalsIgnoreCase(new JSONObject(rawJson).optString("BackendState", ""));
        } catch (Throwable ignored) {}
        return false;
    }

    public static String queryCurrentAuthUrl(Context ctx) {
        String raw = queryStatusJson(ctx);
        if (!raw.isEmpty()) {
            try {
                String u = new JSONObject(raw).optString("AuthURL", "");
                if (u.startsWith("https://")) return u;
            } catch (Throwable ignored) {}
        }
        return "";
    }

    public static String logoutTailscale(Context ctx) {
        try {
            android.content.Intent stopVpn = new android.content.Intent(ctx, Arc7VpnService.class);
            stopVpn.setAction(Arc7VpnService.ACTION_STOP);
            ctx.startService(stopVpn);
        } catch (Throwable ignored) {}
        if (!isTailscaleInstalled(ctx)) return "Tailscale binary not installed.";
        try {
            File sockFile = new File(new File(ctx.getFilesDir(), "tailscale_state"), "tailscaled.sock");
            Process p = new ProcessBuilder(
                    tailscaleCliBin(ctx).getAbsolutePath(),
                    "--socket=" + sockFile.getAbsolutePath(),
                    "logout").redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) baos.write(b, 0, n);
            in.close();
            return "Signed out of Tailscale cleanly. " + baos.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "Logout note: " + t.getMessage();
        }
    }

    /**
     * Renders a 100% standards-compliant **ISO/IEC 18004 Reed-Solomon QR Code** via {@link QrCodeEncoder}
     * so smartphone & PC cameras scan official `https://login.tailscale.com/a/...` Google OAuth URLs instantly!
     */
    public static Bitmap buildVisualCodeCard(String text, int sizePx) {
        return QrCodeEncoder.renderQrBitmap(text, sizePx);
    }

}
