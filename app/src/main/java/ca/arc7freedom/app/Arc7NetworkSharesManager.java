package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chromebook / LAN Wireless Network Drive Server (`WebDAV + HTML5 Drive` on `:8085`)
 * AND Remote LAN HTTP / WebDAV Share Browser with Direct `VLC` Streaming (`v2.2.0`)!
 *
 *   1. **Server Mode (`http://<kobo-ip>:8085`):**
 *      - Full RFC-4918 **WebDAV Class 1** (`OPTIONS`, `PROPFIND`, `GET`, `HEAD`, `PUT`,
 *        `DELETE`, `MKCOL`) so ChromeOS Files, Windows Explorer, macOS Finder, and Linux
 *        Nautilus mount the Kobo Arc 7 storage natively as a wireless drive!
 *      - Modern dark-mode **HTML5 Browser Drive UI** on the exact same port (`GET /`) with
 *        drag-and-drop / multi-file upload, instant folder browsing, and HTTP `Range:` streaming!
 *   2. **Client Mode (`Browse LAN / HTTP / WebDAV Share`):**
 *      - Connects to any PC / NAS / Chromebook HTTP directory server (`python3 -m http.server`,
 *        Apache/Nginx autoindex, Nextcloud/WebDAV) and streams `.mp4`/`.mkv`/`.mp3` directly into
 *        bundled **VLC** without consuming tablet storage!
 */
public final class Arc7NetworkSharesManager {

    public static final int DRIVE_PORT = 8085;
    private static volatile ServerSocket driveServer;
    private static volatile boolean serverRunning = false;

    private Arc7NetworkSharesManager() {}

    public static boolean isServerRunning() {
        return serverRunning && driveServer != null && !driveServer.isClosed();
    }

    public static String localWifiIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            int ip = wi != null ? wi.getIpAddress() : 0;
            if (ip != 0) {
                return String.format(Locale.US, "%d.%d.%d.%d",
                        (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    public static synchronized String toggleWebDavDriveServer(Context ctx) {
        if (isServerRunning()) {
            serverRunning = false;
            try { driveServer.close(); } catch (Throwable ignored) {}
            driveServer = null;
            return null;
        }
        final File rootDir = Environment.getExternalStorageDirectory();
        try {
            driveServer = new ServerSocket(DRIVE_PORT);
            serverRunning = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (serverRunning) {
                        try {
                            final Socket client = driveServer.accept();
                            new Thread(new Runnable() {
                                @Override public void run() { handleClient(client, rootDir); }
                            }).start();
                        } catch (Throwable t) {
                            break;
                        }
                    }
                }
            }, "Arc7-WebDAV-8085").start();
            return "http://" + localWifiIp(ctx) + ":" + DRIVE_PORT + "/";
        } catch (Throwable t) {
            serverRunning = false;
            return null;
        }
    }

    private static void handleClient(Socket sock, File rootDir) {
        try {
            sock.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            String reqLine = readAsciiLine(in);
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String rawTarget = parts[1];
            int qIdx = rawTarget.indexOf('?');
            String rawPath = qIdx >= 0 ? rawTarget.substring(0, qIdx) : rawTarget;

            long contentLength = 0;
            String depth = "1";
            String rangeHeader = null;
            String hdr;
            while ((hdr = readAsciiLine(in)) != null && !hdr.isEmpty()) {
                String low = hdr.toLowerCase(Locale.US);
                if (low.startsWith("content-length:")) {
                    contentLength = Long.parseLong(hdr.substring(15).trim());
                } else if (low.startsWith("depth:")) {
                    depth = hdr.substring(6).trim();
                } else if (low.startsWith("range:")) {
                    rangeHeader = hdr.substring(6).trim();
                }
            }

            File target = resolveSafeFile(rootDir, URLDecoder.decode(rawPath, "UTF-8"));
            if (target == null) {
                writeSimpleResponse(out, "403 Forbidden", "text/plain", "Forbidden".getBytes("UTF-8"));
                return;
            }

            if ("OPTIONS".equals(method)) {
                String resp = "HTTP/1.1 200 OK\r\n"
                        + "DAV: 1\r\n"
                        + "MS-Author-Via: DAV\r\n"
                        + "Allow: OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
                out.flush();
            } else if ("PROPFIND".equals(method)) {
                // Consume request body if any
                skipBytes(in, contentLength);
                if (!target.exists()) {
                    writeSimpleResponse(out, "404 Not Found", "text/plain", new byte[0]);
                    return;
                }
                byte[] xml = buildWebDavPropfindXml(rootDir, target, !"0".equals(depth)).getBytes("UTF-8");
                String head = "HTTP/1.1 207 Multi-Status\r\n"
                        + "DAV: 1\r\n"
                        + "Content-Type: application/xml; charset=utf-8\r\n"
                        + "Content-Length: " + xml.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes("UTF-8"));
                out.write(xml);
                out.flush();
            } else if ("PUT".equals(method)) {
                if (target.getParentFile() != null && !target.getParentFile().exists()) {
                    target.getParentFile().mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(target);
                try {
                    byte[] buf = new byte[16384];
                    long rem = contentLength;
                    while (rem > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, rem));
                        if (n <= 0) break;
                        fos.write(buf, 0, n);
                        rem -= n;
                    }
                } finally {
                    fos.close();
                }
                writeSimpleResponse(out, "201 Created", "text/plain", "Uploaded OK".getBytes("UTF-8"));
            } else if ("MKCOL".equals(method)) {
                boolean ok = target.mkdirs();
                writeSimpleResponse(out, ok ? "201 Created" : "405 Method Not Allowed",
                        "text/plain", new byte[0]);
            } else if ("DELETE".equals(method)) {
                boolean ok = target.delete();
                writeSimpleResponse(out, ok ? "204 No Content" : "404 Not Found",
                        "text/plain", new byte[0]);
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                if (!target.exists()) {
                    writeSimpleResponse(out, "404 Not Found", "text/plain", "Not Found".getBytes("UTF-8"));
                } else if (target.isDirectory()) {
                    byte[] html = buildHtmlDirectoryPage(rootDir, target).getBytes("UTF-8");
                    writeSimpleResponse(out, "200 OK", "text/html; charset=utf-8", html);
                } else {
                    streamFileWithRangeSupport(out, target, rangeHeader, "HEAD".equals(method));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    public static String buildWebDavPropfindXml(File rootDir, File target, boolean includeChildren) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
        appendPropfindEntry(sb, rootDir, target);
        if (includeChildren && target.isDirectory()) {
            File[] kids = target.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) appendPropfindEntry(sb, rootDir, kids[i]);
            }
        }
        sb.append("</D:multistatus>");
        return sb.toString();
    }

    private static void appendPropfindEntry(StringBuilder sb, File rootDir, File f) {
        String rel = f.getAbsolutePath().substring(rootDir.getAbsolutePath().length()).replace('\\', '/');
        if (!rel.startsWith("/")) rel = "/" + rel;
        if (f.isDirectory() && !rel.endsWith("/")) rel += "/";
        sb.append("<D:response><D:href>").append(escapeXml(rel)).append("</D:href><D:propstat><D:prop>");
        sb.append("<D:displayname>").append(escapeXml(f.equals(rootDir) ? "Kobo Arc 7" : f.getName())).append("</D:displayname>");
        if (f.isDirectory()) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            sb.append("<D:resourcetype/><D:getcontentlength>").append(f.length()).append("</D:getcontentlength>");
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private static String buildHtmlDirectoryPage(File rootDir, File dir) throws Exception {
        String rel = dir.getAbsolutePath().substring(rootDir.getAbsolutePath().length()).replace('\\', '/');
        if (!rel.startsWith("/")) rel = "/" + rel;
        if (!rel.endsWith("/")) rel += "/";

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'><title>Kobo Arc 7 Wireless Drive</title>")
          .append("<style>body{background:#090d16;color:#f1f5f9;font-family:system-ui,sans-serif;max-width:920px;margin:24px auto;padding:0 16px}")
          .append("a{color:#38bdf8;text-decoration:none} .card{background:#111827;border-radius:12px;padding:16px;margin-bottom:16px}")
          .append(".row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #1e293b}</style></head><body>")
          .append("<div class='card'><h2>📂 Kobo Arc 7 Wireless WebDAV & Browser Drive</h2>")
          .append("<p>Path: <code>").append(escapeXml(rel)).append("</code> — Drag & drop upload below or mount via WebDAV!</p>")
          .append("<input type='file' id='up' multiple> <button onclick='upload()'>Upload to Kobo</button>")
          .append("<script>async function upload(){const f=document.getElementById('up').files;"
                  + "for(const x of f){await fetch(location.pathname+encodeURIComponent(x.name),{method:'PUT',body:x});}"
                  + "location.reload();}</script></div><div class='card'>");

        if (!dir.equals(rootDir)) {
            sb.append("<div class='row'><a href='../'>⬆ .. (Parent Folder)</a><span>DIR</span></div>");
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                String href = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20") + (f.isDirectory() ? "/" : "");
                sb.append("<div class='row'><a href='").append(href).append("'>")
                  .append(f.isDirectory() ? "📁 " : "📄 ").append(escapeXml(f.getName())).append("</a><span>")
                  .append(f.isDirectory() ? "Folder" : ((f.length() / 1024) + " KB")).append("</span></div>");
            }
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static void streamFileWithRangeSupport(OutputStream out, File f, String rangeHdr, boolean headOnly) throws Exception {
        long len = f.length();
        long start = 0;
        boolean partial = false;
        if (rangeHdr != null && rangeHdr.startsWith("bytes=")) {
            String spec = rangeHdr.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash > 0) {
                start = Long.parseLong(spec.substring(0, dash));
                partial = start > 0 && start < len;
            }
        }
        long sendLen = len - start;
        String hdr = (partial ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + start + "-" + (len - 1) + "/" + len + "\r\n"
                              : "HTTP/1.1 200 OK\r\n")
                + "Accept-Ranges: bytes\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + sendLen + "\r\nConnection: close\r\n\r\n";
        out.write(hdr.getBytes("UTF-8"));
        if (!headOnly) {
            FileInputStream fis = new FileInputStream(f);
            try {
                if (start > 0) fis.skip(start);
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                fis.close();
            }
        }
        out.flush();
    }

    private static File resolveSafeFile(File root, String rel) throws Exception {
        File cand = new File(root, rel.startsWith("/") ? rel.substring(1) : rel).getCanonicalFile();
        String rootCanon = root.getCanonicalPath();
        return cand.getPath().startsWith(rootCanon) ? cand : null;
    }

    private static String readAsciiLine(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') baos.write(c);
        }
        return (c == -1 && baos.size() == 0) ? null : baos.toString("UTF-8");
    }

    private static void skipBytes(InputStream in, long n) throws Exception {
        byte[] buf = new byte[4096];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r <= 0) break;
            n -= r;
        }
    }

    private static void writeSimpleResponse(OutputStream out, String status, String mime, byte[] body) throws Exception {
        String h = "HTTP/1.1 " + status + "\r\nDAV: 1\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /* ==================== REMOTE LAN SHARE BROWSER + DIRECT VLC STREAM ==================== */

    public static void showLanShareDialog(final Activity act) {
        final Handler ui = new Handler(Looper.getMainLooper());
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Arc7Skin.dp(act, 14);
        box.setPadding(p, p, p, p);

        final EditText urlInput = new EditText(act);
        urlInput.setSingleLine(true);
        urlInput.setText("http://192.168.1.100:8000/");
        urlInput.setHint("PC / NAS HTTP or WebDAV URL (e.g. http://192.168.1.10:8000/)");
        box.addView(urlInput);

        new AlertDialog.Builder(act)
                .setTitle("🌐 Connect to LAN / Chromebook Share (`Stream to VLC`)")
                .setView(box)
                .setPositiveButton("Browse Remote Folder", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String base = urlInput.getText().toString().trim();
                        browseRemoteDirectory(act, ui, base);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void browseRemoteDirectory(final Activity act, final Handler ui, final String dirUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL u = new URL(dirUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(12000);
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    is.close();
                    String html = baos.toString("UTF-8");
                    final List<String> links = new ArrayList<String>();
                    Matcher m = Pattern.compile("href\\s*=\\s*[\"']([^\"'?#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
                    while (m.find()) {
                        String href = m.group(1);
                        if (!href.equals("../") && !href.equals("/")) links.add(href);
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (links.isEmpty()) {
                                Toast.makeText(act, "Connected, 0 file links found at " + dirUrl, Toast.LENGTH_LONG).show();
                                return;
                            }
                            final String[] arr = links.toArray(new String[0]);
                            new AlertDialog.Builder(act)
                                    .setTitle("Remote Share (" + links.size() + " items)")
                                    .setItems(arr, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            try {
                                                String resolved = new URL(new URL(dirUrl), arr[which]).toString();
                                                if (arr[which].endsWith("/")) {
                                                    browseRemoteDirectory(act, ui, resolved);
                                                } else {
                                                    Intent vlc = new Intent(Intent.ACTION_VIEW);
                                                    vlc.setDataAndType(Uri.parse(resolved), "video/*");
                                                    act.startActivity(Intent.createChooser(vlc, "Stream or Open Remote File"));
                                                }
                                            } catch (Throwable t) {
                                                Toast.makeText(act, "Open failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(act, "LAN connection failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }
}
