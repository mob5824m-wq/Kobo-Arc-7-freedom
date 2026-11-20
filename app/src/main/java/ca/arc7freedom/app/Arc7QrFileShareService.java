package ca.arc7freedom.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Foreground owner for an expiring QR-authorized local file-share server. */
public final class Arc7QrFileShareService extends Service {
    public static final String ACTION_START = "ca.arc7freedom.action.START_QR_FILE_SHARE";
    public static final String ACTION_STOP = "ca.arc7freedom.action.STOP_QR_FILE_SHARE";
    public static final String EXTRA_ROOT = "share_root";
    public static final long SESSION_MILLIS = 30L * 60L * 1000L;
    private static final int NOTIFICATION_ID = 8091;

    private static volatile boolean running;
    private static volatile String token = "";
    private static volatile String host = "";
    private static volatile String rootName = "";
    private static volatile String error = "";
    private static volatile int port;
    private static volatile long expiresAt;

    private final Handler handler = new Handler();
    private Arc7QrFileShareServer server;
    private final Runnable expire = new Runnable() {
        public void run() {
            if (running && System.currentTimeMillis() >= expiresAt) {
                error = "The QR file share expired after 30 minutes.";
                stopSelf();
            } else if (running) {
                handler.postDelayed(this, Math.max(1000L, expiresAt - System.currentTimeMillis()));
            }
        }
    };

    public static void startShare(Context context, File directory) {
        if (context == null || directory == null) return;
        error = "";
        Intent intent = new Intent(context, Arc7QrFileShareService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_ROOT, directory.getAbsolutePath());
        context.startService(intent);
    }

    public static void stopShare(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, Arc7QrFileShareService.class);
        intent.setAction(ACTION_STOP);
        try { context.startService(intent); }
        catch (Exception unavailable) { context.stopService(new Intent(context, Arc7QrFileShareService.class)); }
    }

    public static boolean isRunning() { return running; }
    public static String getRootName() { return rootName; }
    public static String getError() { return error; }
    public static int getPort() { return port; }
    public static long getExpiresAt() { return expiresAt; }
    public static String getShareUrl() {
        if (!running || token.length() == 0 || host.length() == 0 || port == 0) return "";
        return "http://" + host + ":" + port + "/?t=" + token;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            error = "";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        String requestedRoot = intent == null ? null : intent.getStringExtra(EXTRA_ROOT);
        if (requestedRoot == null || requestedRoot.length() == 0) {
            error = "Choose a folder in Files before starting a QR share.";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            File root = new File(requestedRoot).getCanonicalFile();
            if (!root.isDirectory() || !root.canRead()) throw new Exception("The selected folder cannot be read.");
            String address = findLanAddress();
            if (address.length() == 0) throw new Exception("Connect the Kobo and computer to the same Wi-Fi network, then try again.");

            startForeground(NOTIFICATION_ID, notification("Starting local file share…"));
            byte[] random = Arc7VaultCrypto.random(24);
            StringBuilder hex = new StringBuilder(48);
            for (byte value : random) hex.append(String.format(Locale.US, "%02x", value & 255));
            Arc7VaultCrypto.wipe(random);
            Arc7QrFileShareServer next = new Arc7QrFileShareServer(root, hex.toString());
            next.start();
            if (!next.isRunning()) throw new Exception("The local file-share server did not start.");

            server = next;
            token = hex.toString();
            host = address;
            rootName = root.getName().length() == 0 ? root.getAbsolutePath() : root.getName();
            port = next.getPort();
            expiresAt = System.currentTimeMillis() + SESSION_MILLIS;
            error = "";
            running = true;
            handler.removeCallbacks(expire);
            handler.postDelayed(expire, SESSION_MILLIS);
            startForeground(NOTIFICATION_ID, notification("Folder " + rootName + " · " + host + ":" + port + " · auto-stops in 30 min"));
        } catch (Exception failure) {
            error = failure.getMessage() == null ? "Could not start the QR file share." : failure.getMessage();
            stopInternal();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String description) {
        Intent open = new Intent(this, Arc7FileManagerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_files)
                .setContentTitle("QR file share active")
                .setContentText(description)
                .setContentIntent(pending)
                .setOngoing(true)
                .getNotification();
    }

    private String findLanAddress() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            int ip = info == null ? 0 : info.getIpAddress();
            if (ip != 0) {
                String address = String.format(Locale.US, "%d.%d.%d.%d", ip & 255, (ip >> 8) & 255,
                        (ip >> 16) & 255, (ip >> 24) & 255);
                if (usable(address)) return address;
            }
        } catch (Exception ignored) {}

        List<String> preferred = new ArrayList<String>();
        List<String> fallback = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.US);
                boolean localLan = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("tiwlan") || name.startsWith("usb");
                if (!localLan && (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet"))) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isAnyLocalAddress()
                            || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String text = address.getHostAddress();
                    if (usable(text)) (localLan ? preferred : fallback).add(text);
                }
            }
        } catch (Exception ignored) {}
        if (!preferred.isEmpty()) return preferred.get(0);
        return fallback.isEmpty() ? "" : fallback.get(0);
    }

    private static boolean usable(String address) {
        return address != null && address.length() > 0 && !address.startsWith("127.")
                && !address.startsWith("169.254.") && !"0.0.0.0".equals(address);
    }

    private void stopInternal() {
        handler.removeCallbacks(expire);
        running = false;
        if (server != null) { server.stop(); server = null; }
        token = ""; host = ""; rootName = ""; port = 0; expiresAt = 0;
    }

    @Override public void onDestroy() {
        stopInternal();
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
