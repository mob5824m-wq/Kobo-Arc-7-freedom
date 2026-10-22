package ca.arc7freedom.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/**
 * Persistent Background `TLS 1.0–1.3` Gateway Watchdog Service (`127.0.0.1:8118`).
 *
 * Keeps `libarc7tls.so proxy` alive 24/7 even when `MainActivity` is closed, so:
 *   1. F-Droid Classic (`http://127.0.0.1:8118/__https/...`) ALWAYS gets live TLS 1.3 repo index updates!
 *   2. Chrome / Stock Android 4.2 Browser / third-party apps ALWAYS get clean TLS 1.0–1.2 -> TLS 1.3
 *      `CONNECT` upgrades with zero `"SSL connection error"`!
 */
public class Arc7TlsGatewayService extends Service {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean started = false;
    private NetworkCertManager gatewayCertMgr;

    public static void ensureStarted(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx.getApplicationContext(), Arc7TlsGatewayService.class));
        } catch (Throwable ignored) {}
    }

    public static boolean isGatewayAlive() {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://127.0.0.1:" + NetworkCertManager.DEFAULT_PROXY_PORT + "/__ping");
            conn = (HttpURLConnection) u.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(450);
            conn.setReadTimeout(750);
            conn.setInstanceFollowRedirects(false);
            return conn.getResponseCode() == 200;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ensureNativeGatewayRunning();
                }
            }).start();
            handler.postDelayed(this, 18000L);
        }
    };

    private synchronized void ensureNativeGatewayRunning() {
        try {
            if (isGatewayAlive()) return;
            if (gatewayCertMgr == null) {
                gatewayCertMgr = new NetworkCertManager(getApplicationContext(), new RootShell(getApplicationContext()));
            }
            gatewayCertMgr.startBrowserGateway();
            Arc7PowerWidgetProvider.refreshAll(getApplicationContext());
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            handler.post(watchdog);
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ensureNativeGatewayRunning();
                }
            }).start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        started = false;
        super.onDestroy();
    }
}
