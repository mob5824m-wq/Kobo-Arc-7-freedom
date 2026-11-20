package ca.arc7freedom.app;

import android.app.Application;

/** Every APK initializes its own identity; no sharedUserId, remote suite service or boot proxy. */
public class Arc7Application extends ca.arc7freedom.support.SupportApplication {
    @Override public void onCreate() {
        super.onCreate();
        AppNetworkScope.initialize(getPackageName());
        if (AppNetworkScope.isSuite()) {
            InstallMaintenanceService.request(this, "suite process started");
        }
    }
}
