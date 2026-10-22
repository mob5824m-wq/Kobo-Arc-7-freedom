package ca.arc7freedom.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Direct Zero-Storage USB ADB Laptop Backup & Restore Streaming Server (`127.0.0.1:18777`).
 *
 * Streams raw eMMC flash partitions (`boot`, `recovery`, `nvram`, `pro_info`, `protect_f`,
 * `protect_s`, `sec_ro`, `uboot`, `logo`, `system`), `dumchar_info.txt`, `/proc/emmc`,
 * `/system/build.prop`, and user SD storage files directly over lossless 8-bit USB TCP
 * (`adb forward tcp:18777 tcp:18777`) into either any desktop web browser OR the 1-click
 * `Backup-Kobo-To-Laptop-ADB.bat / .sh` tool using 0 MB of temporary tablet storage!
 */
public class Arc7AdbBackupService extends Service {

    public static final int PORT = 18777;

    private static volatile ServerSocket serverSocket;
    private static volatile String lastStatusLine = "Ready on USB ADB 127.0.0.1:" + PORT;
    private static volatile long totalBytesStreamed = 0L;

    public static boolean isRunning() {
        ServerSocket ss = serverSocket;
        return ss != null && !ss.isClosed();
    }

    public static String getLastStatusLine() {
        return lastStatusLine;
    }

    public static long getTotalBytesStreamed() {
        return totalBytesStreamed;
    }

    public static void ensureStarted(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx.getApplicationContext(), Arc7AdbBackupService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServerIfNeeded();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startServerIfNeeded() {
        if (isRunning()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RootShell rs = new RootShell(getApplicationContext());
                    if (rs.isRootAvailable()) {
                        rs.execRoot(
                                "setprop persist.service.adb.enable 1 >/dev/null 2>&1 || true; "
                                + "setprop service.adb.root 1 >/dev/null 2>&1 || true; "
                                + "start adbd >/dev/null 2>&1 || true",
                                null
                        );
                    }
                    final ServerSocket ss = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                    serverSocket = ss;
                    lastStatusLine = "Listening on USB ADB 127.0.0.1:" + PORT + " — plug USB into laptop!";
                    while (!ss.isClosed()) {
                        final Socket client = ss.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        }).start();
                    }
                } catch (Throwable t) {
                    lastStatusLine = "USB ADB Server note: " + t.getMessage();
                }
            }
        }, "Arc7AdbBackupServer").start();
    }

    private void handleClient(Socket sock) {
        try {
            sock.setSoTimeout(60000);
            InputStream in = sock.getInputStream();
            OutputStream rawOut = new BufferedOutputStream(sock.getOutputStream(), 65536);

            ByteArrayOutputStream reqBuf = new ByteArrayOutputStream(512);
            int prev3 = 0, prev2 = 0, prev1 = 0, b;
            while (reqBuf.size() < 8192 && (b = in.read()) != -1) {
                reqBuf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
            String req = new String(reqBuf.toByteArray(), "UTF-8");
            String firstLine = req.split("\r\n", 2)[0];
            String[] parts = firstLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawTarget = parts.length > 1 ? parts[1] : "/";

            if (method.startsWith("\u0016") || (!method.equals("GET") && !method.equals("HEAD"))) {
                return;
            }

            String path = rawTarget;
            String query = "";
            int qIdx = rawTarget.indexOf('?');
            if (qIdx >= 0) {
                path = rawTarget.substring(0, qIdx);
                query = rawTarget.substring(qIdx + 1);
            }

            if ("/ping".equals(path)) {
                sendText(rawOut, 200, "text/plain; charset=utf-8", "ARC7_ADB_BACKUP_READY\n");
            } else if ("/api/status".equals(path)) {
                RootShell rs = new RootShell(getApplicationContext());
                DeviceInspector.Snapshot snap = DeviceInspector.collect(getApplicationContext(), rs);
                String json = "{"
                        + "\"status\":\"ready\","
                        + "\"model\":\"" + jsonEsc(snap.model) + "\","
                        + "\"device\":\"" + jsonEsc(snap.deviceCodename) + "\","
                        + "\"release\":\"" + jsonEsc(snap.androidRelease) + "\","
                        + "\"rooted\":" + snap.rooted + ","
                        + "\"partitions\":" + snap.partitions.size() + ","
                        + "\"bytes_streamed\":" + totalBytesStreamed
                        + "}\n";
                sendText(rawOut, 200, "application/json; charset=utf-8", json);
            } else if ("/bundle.zip".equals(path)) {
                boolean includeSystem = query.contains("full=1");
                streamBackupZipBundle(rawOut, includeSystem);
            } else if ("/sdcard-files.zip".equals(path)) {
                streamUserStorageZip(rawOut);
            } else if (path.startsWith("/part/")) {
                String role = path.substring("/part/".length()).replace(".img.gz", "").replace(".img", "");
                boolean gzip = path.endsWith(".gz");
                streamSinglePartition(rawOut, role, gzip);
            } else {
                serveLaptopDashboardHtml(rawOut);
            }
            rawOut.flush();
        } catch (Throwable ignored) {
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    private void serveLaptopDashboardHtml(OutputStream out) throws Exception {
        RootShell rs = new RootShell(getApplicationContext());
        DeviceInspector.Snapshot snap = DeviceInspector.collect(getApplicationContext(), rs);
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, DeviceInspector.PartitionTarget> e : snap.partitions.entrySet()) {
            DeviceInspector.PartitionTarget p = e.getValue();
            long mb = p.sizeBytes > 0 ? Math.max(1L, p.sizeBytes / 1048576L) : 0L;
            rows.append("<tr><td><b>").append(htmlEsc(p.role)).append("</b></td>")
                .append("<td><code>").append(htmlEsc(p.describe())).append("</code></td>")
                .append("<td>").append(mb > 0 ? (mb + " MiB") : "Full device").append("</td>")
                .append("<td><a class='dlsm' href='/part/").append(htmlEsc(p.role))
                .append(".img.gz'>Download .img.gz</a></td></tr>");
        }

        String rootBanner = snap.rooted
                ? "<div class='ok'><b>&#10003; Root Flash Access Active:</b> Direct bit-for-bit eMMC partition streaming enabled (uses <b>0 MB</b> of tablet storage!).</div>"
                : "<div class='warn'><b>Note:</b> Tablet is not rooted yet — open Tab 1 (<i>ROOT</i>) on the tablet first for raw partition dumps, or back up your Books / Music / Photos archive right now below!</div>";

        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<title>Kobo Arc 7 — Direct USB ADB Laptop Backup</title>"
                + "<style>"
                + "body{font-family:-apple-system,Segoe UI,sans-serif;background:#0b1120;color:#e2e8f0;margin:0;padding:28px}"
                + ".wrap{max-width:880px;margin:0 auto}"
                + ".card{background:#111c33;border:1px solid #263959;border-radius:14px;padding:22px;margin-bottom:18px}"
                + "h1{margin:0 0 8px;color:#60a5fa;font-size:24px}h2{margin:0 0 12px;font-size:18px;color:#f8fafc}"
                + ".btn{display:inline-block;padding:13px 20px;border-radius:9px;text-decoration:none;font-weight:700;margin:6px 10px 6px 0;color:#fff;background:#2563eb}"
                + ".btn.green{background:#059669}.btn.purple{background:#6d28d9}"
                + ".dlsm{color:#93c5fd;text-decoration:none;font-weight:600}"
                + ".ok{background:#064e3b;border:1px solid #10b981;padding:12px 16px;border-radius:10px;margin:12px 0}"
                + ".warn{background:#78350f;border:1px solid #f59e0b;padding:12px 16px;border-radius:10px;margin:12px 0}"
                + "table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:9px 8px;border-bottom:1px solid #1e293b}"
                + "code{background:#0f172a;padding:2px 6px;border-radius:4px;color:#cbd5e1}"
                + "</style></head><body><div class='wrap'>"
                + "<div class='card'><h1>&#128187; Kobo Arc 7 &rarr; Laptop Direct USB ADB Backup</h1>"
                + "<p>Connected to <b>" + htmlEsc(snap.model) + " (" + htmlEsc(snap.deviceCodename) + ", Android " + htmlEsc(snap.androidRelease) + ")</b> over lossless USB ADB tunnel (<code>127.0.0.1:18777</code>).</p>"
                + rootBanner
                + "<a class='btn green' href='/bundle.zip'>&#11015; Essential Unbrick Backup (.zip, ~35 MB)</a>"
                + "<a class='btn' href='/bundle.zip?full=1'>&#11015; Full Factory OS + System Clone (.zip, ~850 MB)</a>"
                + "<a class='btn purple' href='/sdcard-files.zip'>&#11015; Backup My Books, Music &amp; Photos (.zip)</a>"
                + "</div>"
                + "<div class='card'><h2>Individual Partition Stream Downloads (Gzipped Directly onto Your Laptop)</h2>"
                + "<table><tr><th>Partition</th><th>Block Slice</th><th>Raw Size</th><th>Stream to Laptop</th></tr>"
                + rows.toString()
                + "</table></div></div></body></html>";
        sendText(out, 200, "text/html; charset=utf-8", html);
    }

    private void streamBackupZipBundle(OutputStream rawOut, boolean includeSystem) throws Exception {
        RootShell rs = new RootShell(getApplicationContext());
        DeviceInspector.Snapshot snap = DeviceInspector.collect(getApplicationContext(), rs);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String fn = (includeSystem ? "KoboArc7-Full-Clone-" : "KoboArc7-Unbrick-Backup-") + stamp + ".zip";

        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename=\"" + fn + "\"\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        rawOut.write(hdr.getBytes("UTF-8"));

        ZipOutputStream zos = new ZipOutputStream(rawOut);
        zos.setLevel(includeSystem ? 3 : 5);
        StringBuilder shaManifest = new StringBuilder();

        String layoutInfo = "=== /proc/dumchar_info ===\n" + snap.rawDumcharText
                + "\n=== /proc/emmc ===\n" + snap.rawEmmcText
                + "\n=== /proc/partitions ===\n" + snap.rawPartitionsText
                + "\n=== /proc/mounts ===\n" + snap.rawMountsText + "\n";
        addBytesEntry(zos, shaManifest, "partition_layout.txt", layoutInfo.getBytes("UTF-8"));

        if (snap.rawDumcharText != null && snap.rawDumcharText.contains("Part_Name")) {
            String scatter = DeviceInspector.buildScatterFile(snap.rawDumcharText);
            addBytesEntry(zos, shaManifest, "MT8125_Android_scatter.txt", scatter.getBytes("UTF-8"));
        }

        String buildProp = rs.execSh("cat /system/build.prop 2>/dev/null || cat /default.prop", null).stdout;
        addBytesEntry(zos, shaManifest, "build.prop", buildProp.getBytes("UTF-8"));

        String restoreGuide =
                "Kobo Arc 7 (MT8125 / apollojr) Verified Laptop Backup Bundle\n"
                + "Generated: " + stamp + "\n\n"
                + "HOW TO RESTORE BOOT / RECOVERY OVER ADB ROOT SHELL:\n"
                + "  adb push boot.img /data/local/tmp/boot.img\n"
                + "  adb shell su -c \"dd if=/data/local/tmp/boot.img of=/dev/bootimg bs=1048576 && sync\"\n\n"
                + "HOW TO RESTORE RECOVERY OVER ADB ROOT SHELL:\n"
                + "  adb push recovery.img /data/local/tmp/recovery.img\n"
                + "  adb shell su -c \"dd if=/data/local/tmp/recovery.img of=/dev/recovery bs=1048576 && sync\"\n";
        addBytesEntry(zos, shaManifest, "README-RESTORE.txt", restoreGuide.getBytes("UTF-8"));

        if (snap.rooted) {
            final String[] roles = includeSystem
                    ? new String[]{"mbr", "pro_info", "nvram", "protect_f", "protect_s", "sec_ro", "uboot", "logo", "boot", "recovery", "system"}
                    : new String[]{"mbr", "pro_info", "nvram", "protect_f", "protect_s", "sec_ro", "uboot", "logo", "boot", "recovery"};

            for (int i = 0; i < roles.length; i++) {
                DeviceInspector.PartitionTarget p = snap.partitions.get(roles[i]);
                if (p == null) continue;
                String entryName = "system".equals(roles[i]) ? "system.ext4.img" : (roles[i] + ".img");
                lastStatusLine = "Streaming " + entryName + " (" + p.describe() + ") to laptop...";
                streamPartitionIntoZip(zos, shaManifest, entryName, p);
            }
        }

        ZipEntry sumEntry = new ZipEntry("SHA256SUMS.txt");
        zos.putNextEntry(sumEntry);
        zos.write(shaManifest.toString().getBytes("UTF-8"));
        zos.closeEntry();
        zos.finish();
        zos.flush();
        lastStatusLine = "Completed backup stream (" + fn + ") — verified SHA-256 manifest written!";
    }

    private void streamSinglePartition(OutputStream rawOut, String role, boolean gzip) throws Exception {
        RootShell rs = new RootShell(getApplicationContext());
        DeviceInspector.Snapshot snap = DeviceInspector.collect(getApplicationContext(), rs);
        DeviceInspector.PartitionTarget p = snap.partitions.get(role);
        if (p == null) {
            sendText(rawOut, 404, "text/plain", "Partition not found: " + role + "\n");
            return;
        }
        String outName = role + (gzip ? ".img.gz" : ".img");
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Disposition: attachment; filename=\"" + outName + "\"\r\n"
                + "Connection: close\r\n\r\n";
        rawOut.write(hdr.getBytes("UTF-8"));

        lastStatusLine = "Streaming " + outName + " to laptop over USB ADB...";
        OutputStream payloadOut = gzip ? new GZIPOutputStream(rawOut) : rawOut;
        streamRawPartitionSlice(p, payloadOut);
        if (gzip) ((GZIPOutputStream) payloadOut).finish();
        payloadOut.flush();
        lastStatusLine = "Finished streaming " + outName + " to laptop!";
    }

    private void streamUserStorageZip(OutputStream rawOut) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String fn = "KoboArc7-UserFiles-" + stamp + ".zip";
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename=\"" + fn + "\"\r\n"
                + "Connection: close\r\n\r\n";
        rawOut.write(hdr.getBytes("UTF-8"));

        ZipOutputStream zos = new ZipOutputStream(rawOut);
        zos.setLevel(1);
        StringBuilder shaManifest = new StringBuilder();
        File sd = Environment.getExternalStorageDirectory();
        if (sd != null && sd.isDirectory()) {
            addDirectoryRecursively(zos, shaManifest, sd, "");
        }
        ZipEntry sumEntry = new ZipEntry("SHA256SUMS.txt");
        zos.putNextEntry(sumEntry);
        zos.write(shaManifest.toString().getBytes("UTF-8"));
        zos.closeEntry();
        zos.finish();
        zos.flush();
        lastStatusLine = "Completed user files backup (" + fn + ")!";
    }

    private void addDirectoryRecursively(ZipOutputStream zos, StringBuilder shaManifest,
                                         File dir, String prefix) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        byte[] buf = new byte[65536];
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            String name = f.getName();
            if ("Android".equals(name) || ".thumbnails".equals(name) || name.startsWith(".")) continue;
            String rel = prefix.isEmpty() ? name : (prefix + "/" + name);
            if (f.isDirectory()) {
                addDirectoryRecursively(zos, shaManifest, f, rel);
            } else if (f.isFile()) {
                lastStatusLine = "Streaming " + rel + " to laptop...";
                zos.putNextEntry(new ZipEntry(rel));
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                InputStream fis = new BufferedInputStream(new FileInputStream(f), 65536);
                try {
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        md.update(buf, 0, n);
                        zos.write(buf, 0, n);
                        totalBytesStreamed += n;
                    }
                } finally {
                    fis.close();
                }
                zos.closeEntry();
                shaManifest.append(toHex(md.digest())).append("  ").append(rel).append('\n');
            }
        }
    }

    private void addBytesEntry(ZipOutputStream zos, StringBuilder shaManifest,
                               String name, byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
        totalBytesStreamed += data.length;
        shaManifest.append(toHex(digest)).append("  ").append(name).append('\n');
    }

    private void streamPartitionIntoZip(ZipOutputStream zos, StringBuilder shaManifest,
                                        String entryName, DeviceInspector.PartitionTarget p) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        DigestOutputStream dos = new DigestOutputStream(zos, md);
        streamRawPartitionSlice(p, dos);
        dos.flush();
        zos.closeEntry();
        shaManifest.append(toHex(md.digest())).append("  ").append(entryName).append('\n');
    }

    private void streamRawPartitionSlice(DeviceInspector.PartitionTarget p, OutputStream dest) throws Exception {
        String suBin = findSuBinary();
        long skip4k = p.offsetBytes > 0 ? (p.offsetBytes / 4096L) : 0L;
        long limitBytes = p.sizeBytes > 0 ? p.sizeBytes : (32L * 1048576L);
        long count4k = (limitBytes + 4095L) / 4096L;
        String ddCmd = String.format(Locale.US,
                "dd if='%s' bs=4096 skip=%d count=%d 2>/dev/null",
                p.sourcePath, skip4k, count4k);
        Process proc = new ProcessBuilder(suBin, "-c", ddCmd).start();
        InputStream in = proc.getInputStream();
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                dest.write(buf, 0, n);
                totalBytesStreamed += n;
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { proc.waitFor(); } catch (Throwable ignored) {}
        }
    }

    private static String findSuBinary() {
        final String[] paths = { "/sbin/su", "/system/xbin/su", "/system/bin/su", "/su/bin/su" };
        for (int i = 0; i < paths.length; i++) {
            if (new File(paths[i]).exists()) return paths[i];
        }
        return "su";
    }

    private static void sendText(OutputStream out, int code, String ctype, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String status = code == 200 ? "200 OK" : (code + " Error");
        String h = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(b);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String htmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
