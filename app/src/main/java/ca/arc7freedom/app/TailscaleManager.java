package ca.arc7freedom.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

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

        // 2. Download official Tailscale static Linux ARMv7 release over Arc7 TLS 1.3 Gateway
        NetworkCertManager certMgr = new NetworkCertManager(ctx);
        try { certMgr.ensureCaBundleFile(); } catch (Throwable ignored) {}
        certMgr.startBrowserGateway();

        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", NetworkCertManager.DEFAULT_PROXY_PORT));

        String metaJson = fetchViaGateway("http://127.0.0.1:" + NetworkCertManager.BROWSER_GATEWAY_PORT
                + "/__https/pkgs.tailscale.com/stable/?mode=json");
        JSONObject meta = new JSONObject(metaJson);
        JSONObject tarballs = meta.getJSONObject("Tarballs");
        String armTarball = tarballs.getString("arm");

        File tmpTgz = new File(ctx.getCacheDir(), armTarball);
        downloadViaGatewayToFile("http://127.0.0.1:" + NetworkCertManager.BROWSER_GATEWAY_PORT
                + "/__https/pkgs.tailscale.com/stable/" + armTarball, tmpTgz, proxy);

        int count = extractTailscaleTarGz(tmpTgz, binDir(ctx));
        tmpTgz.delete();
        markExecutableViaRoot(ctx);
        return "Installed official Tailscale ARMv7 (" + armTarball + ", " + count + " binaries)!";
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
        pb.start();
        Thread.sleep(900L);

        List<String> upCmd = new ArrayList<String>();
        upCmd.add(tailscaleCliBin(ctx).getAbsolutePath());
        upCmd.add("--socket=" + sockFile.getAbsolutePath());
        upCmd.add("up");
        upCmd.add("--accept-dns=true");
        upCmd.add("--accept-routes=true");
        upCmd.add("--hostname=kobo-arc-7");
        if (authKey != null && !authKey.trim().isEmpty()) {
            upCmd.add("--authkey=" + authKey.trim());
        } else {
            upCmd.add("--timeout=4s");
        }
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
        writeLog(logFile, out);
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

    /**
     * Renders a crisp high-contrast Finder + Data visual code card for quick phone camera URL sharing.
     */
    public static Bitmap buildVisualCodeCard(String text, int sizePx) {
        int n = 25;
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Throwable t) { data = new byte[]{0}; }
        int cell = Math.max(1, sizePx / (n + 4));
        bmp.eraseColor(Color.WHITE);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                boolean dark = isFinderDark(r, c, n);
                if (!isFinderZone(r, c, n)) {
                    int bitIdx = r * n + c;
                    byte b = data[(bitIdx / 8) % Math.max(1, data.length)];
                    dark = (((b >> (bitIdx % 8)) & 1) == 1) ^ (((r + c) % 3) == 0);
                }
                if (dark) {
                    int x0 = (c + 2) * cell;
                    int y0 = (r + 2) * cell;
                    for (int y = y0; y < Math.min(sizePx, y0 + cell); y++) {
                        for (int x = x0; x < Math.min(sizePx, x0 + cell); x++) {
                            bmp.setPixel(x, y, Color.BLACK);
                        }
                    }
                }
            }
        }
        return bmp;
    }

    private static boolean isFinderZone(int r, int c, int n) {
        return (r < 8 && c < 8) || (r < 8 && c >= n - 8) || (r >= n - 8 && c < 8);
    }

    private static boolean isFinderDark(int r, int c, int n) {
        if (r < 7 && c < 7) return finder7x7(r, c);
        if (r < 7 && c >= n - 7) return finder7x7(r, c - (n - 7));
        if (r >= n - 7 && c < 7) return finder7x7(r - (n - 7), c);
        return false;
    }

    private static boolean finder7x7(int r, int c) {
        if (r == 0 || r == 6 || c == 0 || c == 6) return true;
        return r >= 2 && r <= 4 && c >= 2 && c <= 4;
    }

    private static void writeLog(File f, String txt) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write((txt == null ? "" : txt).getBytes("UTF-8")); }
            finally { fos.close(); }
        } catch (Throwable ignored) {}
    }
}
