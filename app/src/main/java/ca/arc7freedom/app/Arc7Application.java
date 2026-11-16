package ca.arc7freedom.app;

import android.app.Application;

/** Every APK initializes its own identity; no sharedUserId, remote suite service or boot proxy. */
public class Arc7Application extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppNetworkScope.initialize(getPackageName());
        if (AppNetworkScope.isSuite()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        new NetworkCertManager(Arc7Application.this, new RootShell(Arc7Application.this))
                                .migrateLegacySuiteProxy();
                    } catch (Throwable ignored) {}
                }
            }, "Arc7-Remove-Old-Proxy").start();
        }
    }
}
