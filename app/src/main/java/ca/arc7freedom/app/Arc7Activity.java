package ca.arc7freedom.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/** Apply the same independent-app routing to toolbar, file-open and in-app shortcuts. */
public abstract class Arc7Activity extends Activity {
    @Override public void startActivityForResult(Intent intent, int requestCode) {
        Intent routed = SplitAppRouter.route(this, intent, requestCode);
        try {
            super.startActivityForResult(routed, requestCode);
        } catch (ActivityNotFoundException missing) {
            if (routed != null && routed.getComponent() != null
                    && SplitAppRouter.ownerPackage(routed.getComponent().getClassName()) != null) {
                SplitAppRouter.showMissingApp(this, routed, false);
            } else throw missing;
        }
    }

    // Jelly Bean's startActivity bypasses the two-argument for-result overload. Override
    // this API-1 method too, without referencing any API-16-only super methods on ICS.
    @Override public void startActivity(Intent intent) {
        Intent routed = SplitAppRouter.route(this, intent, -1);
        try {
            super.startActivity(routed);
        } catch (ActivityNotFoundException missing) {
            if (routed != null && routed.getComponent() != null
                    && SplitAppRouter.ownerPackage(routed.getComponent().getClassName()) != null) {
                SplitAppRouter.showMissingApp(this, routed, false);
            } else throw missing;
        }
    }
}
