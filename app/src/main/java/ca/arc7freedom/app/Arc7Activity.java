package ca.arc7freedom.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/** Apply the same independent-app routing to toolbar, file-open and in-app shortcuts. */
public abstract class Arc7Activity extends Activity {
    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        super.onCreateOptionsMenu(menu);
        return ca.arc7freedom.support.AppSupport.addMenu(menu);
    }
    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == ca.arc7freedom.support.AppSupport.MENU_TOOLS) {
            ca.arc7freedom.support.AppSupport.open(this); return true;
        }
        return super.onOptionsItemSelected(item);
    }

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
