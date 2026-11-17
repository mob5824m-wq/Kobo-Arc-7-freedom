package ca.arc7freedom.net;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Small self-contained TLS transport for companion APKs; no suite context, root or IPC. */
public final class OwnTlsGateway {
    // Outside the generated Arc7 app range (22000+), stable across upgrades.
    public static final int AURORA_PORT = 21118;
    public static final int FDROID_PORT = 21120;
    public static final int VLC_PORT = 21122;

    private OwnTlsGateway() {}

    /** Called on a network worker before a request, not at boot or on the UI thread. */
    public static synchronized void ensureRunning(Context suppliedContext, int port) throws IOException {
        Context context = applicationContext(suppliedContext);
        String owner = context.getPackageName();
        if (isReady(port, owner)) return;
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libarc7tls.so");
        if (!binary.isFile()) throw new IOException("This app's own TLS engine is missing; reinstall this APK.");
        File directory = new File(context.getFilesDir(), "independent_tls");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private TLS directory");
        File bundle = new File(directory, "ca-bundle.crt");
        InputStream input = context.getAssets().open("cacerts/ca-bundle-2026.crt");
        try {
            FileOutputStream output = new FileOutputStream(bundle);
            try {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } finally { output.close(); }
        } finally { input.close(); }

        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "proxy", bundle.getAbsolutePath(), String.valueOf(port));
        builder.environment().put("ARC7_GATEWAY_OWNER", owner);
        builder.redirectErrorStream(true);
        final Process process = builder.start(); // Inherits THIS app's UID, never a root shell.
        process.getOutputStream().close();
        Thread drain = new Thread(new Runnable() {
            @Override public void run() {
                InputStream stream = process.getInputStream();
                try {
                    byte[] buffer = new byte[1024];
                    while (stream.read(buffer) != -1) { /* prevent a full child stdout pipe */ }
                } catch (IOException ignored) {
                } finally { try { stream.close(); } catch (IOException ignored) {} }
            }
        }, "OwnTlsOutput");
        drain.setDaemon(true);
        drain.start();
        for (int i = 0; i < 40; i++) {
            if (isReady(port, owner)) return;
            try { Thread.sleep(75); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("Interrupted while starting this app's TLS engine", interrupted);
            }
        }
        process.destroy();
        throw new IOException("This app's TLS engine could not start on its private port " + port);
    }

    private static boolean isReady(int port, String owner) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 150);
            socket.setSoTimeout(300);
            socket.getOutputStream().write("GET /__ping HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            socket.getOutputStream().flush();
            byte[] bytes = new byte[1024];
            StringBuilder response = new StringBuilder();
            int count;
            while (response.length() < 4096 && (count = socket.getInputStream().read(bytes)) != -1) {
                response.append(new String(bytes, 0, count, "UTF-8"));
            }
            String ping = response.toString();
            return ping.contains("ARC7_TLS13_READY_V191") && ping.contains("\nOWNER=" + owner + "\n");
        } catch (IOException unavailable) { return false; }
        finally { try { socket.close(); } catch (IOException ignored) {} }
    }

    private static Context applicationContext(Context context) throws IOException {
        if (context != null) return context.getApplicationContext() != null ? context.getApplicationContext() : context;
        // F-Droid's static NetCipher API does not take a Context. Preserve its Application
        // and resolve it locally on Android 4.x, rather than reaching into another APK.
        try {
            Object application = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (application instanceof Context) return (Context) application;
        } catch (Exception unavailable) { throw new IOException("This app's Application context is unavailable", unavailable); }
        throw new IOException("This app's Application context is unavailable");
    }
}
