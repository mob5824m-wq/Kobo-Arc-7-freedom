package ca.arc7freedom.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.IBinder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Built-In On-Tablet Wi-Fi File Drive, Remote `.APK` Installer,
 * Factory Backup Downloader & Live `screencap` Screen Viewer (`http://<tablet-ip>:8080`).
 *
 * Hardened for real-world home Wi-Fi & modern 2026 desktop/mobile browsers:
 *   1. Runs as a true Android Foreground Service (`startForeground`) with persistent IP/PIN
 *      status notification so Android 4.2's 1 GB RAM killer never drops port 8080.
 *   2. Binds explicitly to IPv4 `0.0.0.0` with `SO_REUSEADDR` set BEFORE `bind()` and
 *      automatic port fallback (`8080` -> `8088` -> `8888`) if port 8080 is occupied.
 *   3. Fast-rejects TLS `ClientHello` (`0x16` / `0x80`) in <1ms so Chrome/Edge/Safari
 *      HTTPS-First mode immediately falls back to plain `http://<ip>:8080` instead of
 *      stalling for 30 seconds on `ERR_SSL_PROTOCOL_ERROR` / `ERR_TIMED_OUT`.
 *   4. Preserves session auth across BOTH `Set-Cookie` AND tokenized `?pin=` links/forms
 *      (works even in Private/Incognito windows or cookie-blocking browsers), with an
 *      optional 1-tap `Require PIN: ON/OFF` toggle on trusted home Wi-Fi networks.
 */
public class Arc7WifiDriveService extends Service {

    public static final int PORT = 8080;
    private static final int[] CANDIDATE_PORTS = new int[]{8080, 8088, 8888};
    private static final int NOTIF_ID = 8080;

    public static final String PREFS = "arc7_wifi_drive";
    public static final String KEY_PIN = "drive_pin";
    public static final String KEY_REQUIRE_PIN = "require_pin";
    public static final String KEY_ACTIVE = "drive_active";

    private static volatile boolean running = false;
    private static volatile int activePort = PORT;
    private static volatile String lastBindError = "";
    private ServerSocket serverSocket;
    private RootShell shell;

    public static boolean isRunning() {
        return running;
    }

    public static int getActivePort() {
        return activePort;
    }

    public static String getLastBindError() {
        return lastBindError;
    }

    public static boolean ensureStartedAndWait(Context ctx, long maxWaitMs) {
        if (isRunning()) return true;
        ctx.startService(new Intent(ctx, Arc7WifiDriveService.class));
        long deadline = System.currentTimeMillis() + Math.max(100L, maxWaitMs);
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) return true;
            try {
                Thread.sleep(40L);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        return isRunning();
    }

    public static String getOrCreatePin(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pin = sp.getString(KEY_PIN, "");
        if (pin == null || pin.length() != 4) {
            pin = String.format(Locale.US, "%04d", 1000 + new Random().nextInt(9000));
            sp.edit().putString(KEY_PIN, pin).commit();
        }
        return pin;
    }

    public static boolean isPinRequired(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUIRE_PIN, true);
    }

    public static void setPinRequired(Context ctx, boolean required) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REQUIRE_PIN, required).commit();
    }

    public static List<String> detectAllLanIps(Context ctx) {
        List<String> preferred = new ArrayList<String>();
        List<String> others = new ArrayList<String>();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    int ip = wi.getIpAddress();
                    if (ip != 0) {
                        String s = String.format(Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                        if (!"0.0.0.0".equals(s) && !s.startsWith("127.") && !s.startsWith("169.254.")) {
                            preferred.add(s);
                        }
                    }
                }
            }
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() != null ? nif.getName().toLowerCase(Locale.US) : "";
                boolean isWifiOrEth = name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("eth") || name.startsWith("tiwlan");
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        String host = a.getHostAddress();
                        if (host != null && !host.startsWith("169.254.") && !preferred.contains(host) && !others.contains(host)) {
                            if (isWifiOrEth) preferred.add(host);
                            else others.add(host);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        preferred.addAll(others);
        return preferred;
    }

    public static String detectLanIp(Context ctx) {
        List<String> all = detectAllLanIps(ctx);
        return all.isEmpty() ? "127.0.0.1" : all.get(0);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        shell = new RootShell(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).commit();
        promoteForegroundNotification();
        if (!running || serverSocket == null || serverSocket.isClosed()) {
            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void promoteForegroundNotification() {
        try {
            String ip = detectLanIp(this);
            String pin = getOrCreatePin(this);
            Intent openApp = new Intent(this, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT);

            String subtitle = isPinRequired(this)
                    ? ("Open http://" + ip + ":" + activePort + "  (PIN: " + pin + ")")
                    : ("Open http://" + ip + ":" + activePort + "  (No PIN needed)");

            Notification n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Arc7 Wi-Fi Drive Live on :" + activePort)
                    .setContentText(subtitle)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
            startForeground(NOTIF_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private ServerSocket bindAvailableServerSocket() throws Exception {
        Exception lastErr = null;
        for (int candidate : CANDIDATE_PORTS) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate), 64);
                activePort = candidate;
                lastBindError = "";
                return ss;
            } catch (Exception e) {
                lastErr = e;
                if (ss != null) {
                    try { ss.close(); } catch (Throwable ignored) {}
                }
            }
        }
        throw (lastErr != null ? lastErr : new RuntimeException("Unable to bind Wi-Fi Drive TCP port"));
    }

    private void acceptLoop() {
        try {
            serverSocket = bindAvailableServerSocket();
            running = true;
            promoteForegroundNotification();
            while (running && serverSocket != null && !serverSocket.isClosed()) {
                final Socket client = serverSocket.accept();
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable t) {
            lastBindError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        } finally {
            running = false;
        }
    }

    private void handleClient(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(25000);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), 32768);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 32768);

            /* Immediately reject TLS ClientHello (`0x16` / SSLv2 `0x80`) in <1ms so Chrome/Safari
             * HTTPS-First mode instantly falls back to `http://<tablet-ip>:8080` without hanging! */
            int firstByte = in.read();
            if (firstByte == -1 || firstByte == 0x16 || firstByte == 0x80) {
                return;
            }

            /* Read raw HTTP headers up to `\r\n\r\n` */
            ByteArrayOutputStream hdrBuf = new ByteArrayOutputStream();
            hdrBuf.write(firstByte);
            int prev3 = 0, prev2 = 0, prev1 = firstByte, cur;
            while ((cur = in.read()) != -1) {
                hdrBuf.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') break;
                if (hdrBuf.size() > 32768) return;
                prev3 = prev2; prev2 = prev1; prev1 = cur;
            }
            String rawHdr = new String(hdrBuf.toByteArray(), "ISO-8859-1");
            String[] lines = rawHdr.split("\r\n");
            if (lines.length == 0) return;

            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0].toUpperCase(Locale.US);
            String fullUri = reqParts[1];
            boolean isHead = "HEAD".equals(method);

            long contentLength = 0;
            String contentType = "";
            String cookies = "";
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i];
                int col = l.indexOf(':');
                if (col < 0) continue;
                String k = l.substring(0, col).trim().toLowerCase(Locale.US);
                String v = l.substring(col + 1).trim();
                if ("content-length".equals(k)) {
                    try { contentLength = Long.parseLong(v); } catch (Throwable ignored) {}
                } else if ("content-type".equals(k)) {
                    contentType = v;
                } else if ("cookie".equals(k)) {
                    cookies = v;
                }
            }

            String path = fullUri;
            String query = "";
            int qIdx = fullUri.indexOf('?');
            if (qIdx >= 0) {
                path = fullUri.substring(0, qIdx);
                query = fullUri.substring(qIdx + 1);
            }

            if ("/favicon.ico".equals(path)) {
                out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                out.flush();
                return;
            }

            if ("/ping".equals(path)) {
                sendHtml(out, 200, "", "{\"ok\":true,\"port\":" + activePort + "}", "application/json; charset=utf-8", isHead);
                return;
            }

            String expectedPin = getOrCreatePin(this);
            boolean pinNeeded = isPinRequired(this);
            String suppliedPin = queryParam(query, "pin").trim();
            boolean authed = !pinNeeded
                    || expectedPin.equals(suppliedPin)
                    || cookies.contains("arc7pin=" + expectedPin);
            if (!authed) {
                sendPinLoginPage(out, queryParam(query, "dir"), isHead);
                return;
            }

            String authTokenSuffix = pinNeeded ? ("&pin=" + URLEncoder.encode(expectedPin, "UTF-8")) : "";
            String setCookie = "Set-Cookie: arc7pin=" + expectedPin
                    + "; Path=/; Max-Age=2592000; SameSite=Lax\r\n";

            if ("POST".equals(method) && "/upload".equals(path)) {
                String dirParam = queryParam(query, "dir");
                boolean autoInstall = "1".equals(queryParam(query, "install"));
                File targetDir = safeStorageDir(dirParam);
                File saved = receiveMultipartUpload(in, contentLength, contentType, targetDir);
                String msg = saved != null ? ("Uploaded " + saved.getName() + " (" + (saved.length() / 1024) + " KB)") : "Upload failed";
                if (saved != null && autoInstall) {
                    msg += " — " + remoteInstallApk(saved);
                }
                String loc = "/?dir=" + URLEncoder.encode(targetDir.getAbsolutePath(), "UTF-8")
                        + "&msg=" + URLEncoder.encode(msg, "UTF-8") + authTokenSuffix;
                String redirect = "HTTP/1.1 303 See Other\r\n" + setCookie + "Location: " + loc + "\r\nConnection: close\r\n\r\n";
                out.write(redirect.getBytes("UTF-8"));
                out.flush();
                return;
            }

            if ("/mkdir".equals(path)) {
                File base = safeStorageDir(queryParam(query, "dir"));
                String folder = queryParam(query, "name").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
                if (!folder.isEmpty()) new File(base, folder).mkdirs();
                String loc = "/?dir=" + URLEncoder.encode(base.getAbsolutePath(), "UTF-8") + authTokenSuffix;
                out.write(("HTTP/1.1 303 See Other\r\n" + setCookie + "Location: " + loc + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                return;
            }

            if ("/install-existing".equals(path)) {
                File f = new File(queryParam(query, "f"));
                String resMsg = f.exists() ? remoteInstallApk(f) : "File not found";
                String parent = f.getParentFile() != null ? f.getParentFile().getAbsolutePath() : "/sdcard";
                String loc = "/?dir=" + URLEncoder.encode(parent, "UTF-8")
                        + "&msg=" + URLEncoder.encode(resMsg, "UTF-8") + authTokenSuffix;
                out.write(("HTTP/1.1 303 See Other\r\n" + setCookie + "Location: " + loc + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                return;
            }

            if ("/download".equals(path)) {
                File f = new File(queryParam(query, "f"));
                if (!f.exists() || !f.isFile()) {
                    sendHtml(out, 404, setCookie, "<h3>File not found.</h3>", "text/html; charset=utf-8", isHead);
                    return;
                }
                String mime = f.getName().toLowerCase(Locale.US).endsWith(".apk")
                        ? "application/vnd.android.package-archive"
                        : "application/octet-stream";
                String hdr = "HTTP/1.1 200 OK\r\n" + setCookie +
                        "Content-Type: " + mime + "\r\n" +
                        "Content-Length: " + f.length() + "\r\n" +
                        "Content-Disposition: attachment; filename=\"" + f.getName().replace("\"", "_") + "\"\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(hdr.getBytes("UTF-8"));
                if (!isHead) {
                    FileInputStream fis = new FileInputStream(f);
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = fis.read(buf)) > 0) out.write(buf, 0, r);
                    } finally {
                        fis.close();
                    }
                }
                out.flush();
                return;
            }

            if ("/screenshot.png".equals(path)) {
                File shot = new File(getCacheDir(), "live_screen.png");
                shell.execRoot("screencap -p '" + shot.getAbsolutePath() + "'", null);
                if (!shot.exists() || shot.length() < 100) {
                    sendHtml(out, 500, setCookie,
                            "<h3>Root required for live `screencap -p`! Tap 'Root Kobo Arc 7 Now' on Tab 1 first.</h3>",
                            "text/html; charset=utf-8", isHead);
                    return;
                }
                String hdr = "HTTP/1.1 200 OK\r\n" + setCookie +
                        "Content-Type: image/png\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: " + shot.length() + "\r\nConnection: close\r\n\r\n";
                out.write(hdr.getBytes("UTF-8"));
                if (!isHead) {
                    FileInputStream fis = new FileInputStream(shot);
                    try {
                        byte[] buf = new byte[32768];
                        int r;
                        while ((r = fis.read(buf)) > 0) out.write(buf, 0, r);
                    } finally {
                        fis.close();
                    }
                }
                out.flush();
                return;
            }

            /* Default `/` directory listing + upload + remote APK installer + live screenshot UI */
            File curDir = safeStorageDir(queryParam(query, "dir"));
            String flashMsg = queryParam(query, "msg");
            sendDirectoryDashboard(out, setCookie, curDir, flashMsg, authTokenSuffix, isHead);
        } catch (Throwable ignored) {
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    private String remoteInstallApk(File apkFile) {
        if (shell.isRootAvailable()) {
            RootShell.ExecResult r = shell.execRoot("pm install -r " + RootShell.shellQuote(apkFile.getAbsolutePath()), null);
            if (r.stdout.contains("Success")) {
                return "SILENT ROOT INSTALL SUCCEEDED on tablet (" + apkFile.getName() + ")!";
            }
        }
        Intent prompt = new Intent(Intent.ACTION_VIEW);
        prompt.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(prompt);
        return "Opened Android PackageInstaller prompt on tablet screen (" + apkFile.getName() + ")!";
    }

    private File safeStorageDir(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            String sd = TabletOptimizerManager.findMicroSdRoot();
            return sd != null ? new File(sd) : Environment.getExternalStorageDirectory();
        }
        File f = new File(raw);
        if (f.exists() && f.isDirectory()) return f;
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Streams a `multipart/form-data` upload directly to disk with constant 64 KB memory footprint
     * so uploading 500 MB movies, ROM `.zip`s, or large `.epub`/`.cbz` archives never OOMs 1 GB RAM!
     */
    private File receiveMultipartUpload(InputStream in, long contentLen, String contentType, File targetDir) throws Exception {
        int bIdx = contentType.indexOf("boundary=");
        if (bIdx < 0 || contentLen <= 0) return null;
        String boundary = "--" + contentType.substring(bIdx + 9).trim();
        int semi = boundary.indexOf(';');
        if (semi >= 0) boundary = boundary.substring(0, semi).trim();
        if (boundary.startsWith("\"--") && boundary.endsWith("\"")) {
            boundary = "--" + boundary.substring(3, boundary.length() - 1);
        }
        byte[] closeMarker = ("\r\n" + boundary).getBytes("ISO-8859-1");

        /* Read part headers up to `\r\n\r\n` */
        ByteArrayOutputStream partHdr = new ByteArrayOutputStream();
        int p3 = 0, p2 = 0, p1 = 0, c;
        long consumed = 0;
        while (consumed < contentLen && (c = in.read()) != -1) {
            consumed++;
            partHdr.write(c);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') break;
            if (partHdr.size() > 16384) return null;
            p3 = p2; p2 = p1; p1 = c;
        }
        String hdrStr = new String(partHdr.toByteArray(), "UTF-8");
        String fileName = "uploaded_" + (System.currentTimeMillis() / 1000L) + ".bin";
        int fnIdx = hdrStr.indexOf("filename=\"");
        if (fnIdx >= 0) {
            int endQ = hdrStr.indexOf('"', fnIdx + 10);
            if (endQ > fnIdx + 10) {
                String rawName = hdrStr.substring(fnIdx + 10, endQ);
                int slash = Math.max(rawName.lastIndexOf('/'), rawName.lastIndexOf('\\'));
                if (slash >= 0) rawName = rawName.substring(slash + 1);
                rawName = rawName.replaceAll("[^A-Za-z0-9._() -]", "_").trim();
                if (!rawName.isEmpty()) fileName = rawName;
            }
        }

        if (!targetDir.exists()) targetDir.mkdirs();
        File outFile = new File(targetDir, fileName);
        FileOutputStream fos = new FileOutputStream(outFile);

        /* Hold tail window of `closeMarker.length + 8` bytes so trailing `\r\n--boundary--\r\n` is trimmed cleanly */
        int keep = closeMarker.length + 8;
        byte[] window = new byte[65536];
        int winLen = 0;
        byte[] readBuf = new byte[32768];
        try {
            while (consumed < contentLen) {
                int toRead = (int) Math.min(readBuf.length, contentLen - consumed);
                int n = in.read(readBuf, 0, toRead);
                if (n <= 0) break;
                consumed += n;
                if (winLen + n > window.length) {
                    int flush = (winLen + n) - keep;
                    if (flush > 0 && flush <= winLen) {
                        fos.write(window, 0, flush);
                        System.arraycopy(window, flush, window, 0, winLen - flush);
                        winLen -= flush;
                    }
                }
                System.arraycopy(readBuf, 0, window, winLen, n);
                winLen += n;
            }
            int endPos = indexOfBytes(window, winLen, closeMarker);
            fos.write(window, 0, endPos >= 0 ? endPos : winLen);
            fos.flush();
        } finally {
            fos.close();
        }
        return outFile;
    }

    private static int indexOfBytes(byte[] hay, int hayLen, byte[] needle) {
        for (int i = 0; i <= hayLen - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    private void sendPinLoginPage(OutputStream out, String nextDir, boolean headOnly) throws Exception {
        String hiddenDir = (nextDir != null && !nextDir.isEmpty())
                ? "<input type='hidden' name='dir' value='" + esc(nextDir) + "'>"
                : "";
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>Kobo Arc 7 Wi-Fi Drive Login</title>" +
                "<style>body{font-family:sans-serif;background:#0f172a;color:#f8fafc;padding:32px 16px;text-align:center;margin:0;}" +
                ".box{background:#1e293b;padding:28px;border-radius:12px;border:1px solid #334155;max-width:400px;margin:24px auto;text-align:left;}" +
                "input[type=text]{width:100%;box-sizing:border-box;font-size:24px;letter-spacing:8px;text-align:center;" +
                "padding:12px;border-radius:8px;border:1px solid #475569;background:#0f172a;color:#38bdf8;margin:12px 0;}" +
                "button{width:100%;padding:13px;background:#0284c7;color:#fff;font-weight:bold;border:0;border-radius:8px;font-size:15px;cursor:pointer;}</style>" +
                "</head><body><div class='box'><h2 style='margin:0 0 8px 0;color:#38bdf8;'>Kobo Arc 7 Wi-Fi Drive</h2>" +
                "<p style='color:#cbd5e1;font-size:14px;line-height:1.5;'>Enter the 4-digit PIN shown on your tablet screen " +
                "(<b>Tab 5 &rarr; Wi-Fi Drive</b> or in the top status bar notification):</p>" +
                "<form method='GET' action='/'>" + hiddenDir +
                "<input type='text' inputmode='numeric' name='pin' maxlength='4' placeholder='0000' autofocus>" +
                "<button type='submit'>Unlock Wireless Drive &amp; Remote Installer</button></form></div></body></html>";
        sendHtml(out, 200, "", html, "text/html; charset=utf-8", headOnly);
    }

    private void sendDirectoryDashboard(OutputStream out, String cookieHdr, File dir,
                                        String msg, String authSuffix, boolean headOnly) throws Exception {
        String encDir = URLEncoder.encode(dir.getAbsolutePath(), "UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>Kobo Arc 7 Wireless Drive — ").append(esc(dir.getAbsolutePath())).append("</title>")
          .append("<style>body{font-family:sans-serif;background:#0f172a;color:#f1f5f9;padding:20px;max-width:1040px;margin:0 auto;}")
          .append(".card{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:16px;margin-bottom:16px;}")
          .append("a{color:#38bdf8;text-decoration:none;}a:hover{text-decoration:underline;}")
          .append(".btn{display:inline-block;background:#0284c7;color:#fff !important;padding:8px 14px;border-radius:6px;border:0;font-weight:bold;font-size:13px;margin:3px 6px 3px 0;cursor:pointer;}")
          .append(".green{background:#16a34a;}.slate{background:#475569;}")
          .append("table{width:100%;border-collapse:collapse;margin-top:10px;}td,th{padding:9px 8px;border-bottom:1px solid #334155;text-align:left;font-size:14px;}")
          .append("</style></head><body>");

        sb.append("<div class='card'><h2 style='margin:0 0 6px 0;color:#38bdf8;'>Kobo Arc 7 Wireless Drive, Backup Vault &amp; Remote APK Installer</h2>");
        if (msg != null && !msg.isEmpty()) {
            sb.append("<div style='background:#064e3b;border:1px solid #10b981;padding:10px;border-radius:6px;margin:8px 0;'>")
              .append(esc(msg)).append("</div>");
        }
        sb.append("<div style='margin-top:8px;'>")
          .append("<a class='btn slate' href='/?dir=%2Fsdcard").append(authSuffix).append("'>Internal (/sdcard)</a>")
          .append("<a class='btn slate' href='/?dir=%2Fstorage%2Fsdcard1").append(authSuffix).append("'>MicroSD (/storage/sdcard1)</a>")
          .append("<a class='btn slate' href='/?dir=%2Fsdcard%2FDownload").append(authSuffix).append("'>Downloads</a>")
          .append("<a class='btn slate' href='/?dir=%2Fstorage%2Fsdcard1%2FKoboArc7Backups").append(authSuffix).append("'>OS Backups (MicroSD)</a>")
          .append("<a class='btn green' href='/screenshot.png?t=").append(System.currentTimeMillis() / 1000L).append(authSuffix)
          .append("' target='_blank'>Live Tablet Screenshot (.png)</a>")
          .append("</div></div>");

        /* Upload & Remote APK Install Card */
        sb.append("<div class='card'><b>Upload Books (.epub/.cbz), Movies, ROMs (.zip) or Remote-Install .APK to <code>")
          .append(esc(dir.getAbsolutePath())).append("</code>:</b>")
          .append("<form method='POST' action='/upload?dir=").append(encDir).append(authSuffix)
          .append("' enctype='multipart/form-data' style='margin-top:10px;'>")
          .append("<input type='file' name='file' required style='margin-right:10px;'>")
          .append("<button class='btn' type='submit'>Upload to Current Folder</button></form>")
          .append("<form method='POST' action='/upload?dir=%2Fsdcard%2FDownload&install=1").append(authSuffix)
          .append("' enctype='multipart/form-data' style='margin-top:10px;'>")
          .append("<input type='file' name='file' accept='.apk' required style='margin-right:10px;'>")
          .append("<button class='btn green' type='submit'>Upload &amp; Install .APK Straight onto Tablet</button></form></div>");

        /* Directory Contents Table */
        sb.append("<div class='card'><b>Current Directory: <code>").append(esc(dir.getAbsolutePath())).append("</code></b>");
        sb.append("<table><tr><th>Name</th><th>Size</th><th>Actions</th></tr>");
        if (dir.getParentFile() != null) {
            sb.append("<tr><td colspan='3'><a href='/?dir=")
              .append(URLEncoder.encode(dir.getParentFile().getAbsolutePath(), "UTF-8")).append(authSuffix)
              .append("'>&#11014; .. (Parent Directory)</a></td></tr>");
        }
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                String pEnc = URLEncoder.encode(f.getAbsolutePath(), "UTF-8");
                if (f.isDirectory()) {
                    sb.append("<tr><td>&#128193; <a href='/?dir=").append(pEnc).append(authSuffix).append("'><b>")
                      .append(esc(f.getName())).append("/</b></a></td><td>Folder</td><td></td></tr>");
                } else {
                    String sz = f.length() >= 1048576L
                            ? String.format(Locale.US, "%.1f MB", f.length() / 1048576.0)
                            : ((f.length() / 1024L) + " KB");
                    sb.append("<tr><td>&#128196; ").append(esc(f.getName())).append("</td><td>").append(sz)
                      .append("</td><td><a class='btn slate' href='/download?f=").append(pEnc).append(authSuffix).append("'>Download</a>");
                    if (f.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                        sb.append("<a class='btn green' href='/install-existing?f=").append(pEnc).append(authSuffix).append("'>Install on Tablet</a>");
                    }
                    sb.append("</td></tr>");
                }
            }
        }
        sb.append("</table></div></body></html>");
        sendHtml(out, 200, cookieHdr, sb.toString(), "text/html; charset=utf-8", headOnly);
    }

    private static String statusReason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 303: return "See Other";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private void sendHtml(OutputStream out, int code, String extraHdr, String html,
                          String contentType, boolean headOnly) throws Exception {
        byte[] body = html.getBytes("UTF-8");
        String hdr = "HTTP/1.1 " + code + " " + statusReason(code) + "\r\n" + extraHdr +
                "Content-Type: " + contentType + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(hdr.getBytes("UTF-8"));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                try { return URLDecoder.decode(pair.substring(eq + 1), "UTF-8"); } catch (Throwable t) { return ""; }
            }
        }
        return "";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    @Override
    public void onDestroy() {
        running = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).commit();
        try { stopForeground(true); } catch (Throwable ignored) {}
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
