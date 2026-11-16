package ca.arc7freedom.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps this APK's unprivileged TLS engine alive on its own loopback port.
 * Only the suite uses 8118 (optional third-party compatibility).
 * A standalone app never adopts or starts the suite's service.
 */
public class Arc7TlsGatewayService extends Service {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean started = false;
    private volatile boolean destroyed = false;
    private NetworkCertManager gatewayCertMgr;

    public static void ensureStarted(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx.getApplicationContext(), Arc7TlsGatewayService.class));
        } catch (Throwable ignored) {}
    }

    public static boolean isGatewayAlive() {
        String ping = NetworkCertManager.probeGatewayPing(NetworkCertManager.DEFAULT_PROXY_PORT);
        return ping.contains(NetworkCertManager.EXPECTED_GATEWAY_PING)
                && ping.contains("\nOWNER=" + AppNetworkScope.ownPackage() + "\n");
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
            if (destroyed || isGatewayAlive()) return;
            if (gatewayCertMgr == null) {
                gatewayCertMgr = new NetworkCertManager(getApplicationContext(), new RootShell(getApplicationContext()));
            }
            gatewayCertMgr.startBrowserGateway();
            if (destroyed) { gatewayCertMgr.stopBrowserGateway(); return; }
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
        destroyed = true;
        handler.removeCallbacks(watchdog);
        if (gatewayCertMgr != null) gatewayCertMgr.stopBrowserGateway();
        started = false;
        super.onDestroy();
    }
}
