package ca.arc7freedom.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Legacy launcher aliases are shortcuts/install help only; never execute suite app copies. */
public class SplitAppLaunchActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView help = new TextView(this);
        help.setPadding(24, 24, 24, 24);
        help.setText("This shortcut opens a separately installed app.\n\n"
                + "If it is missing, install its APK from the Wi-Fi installer portal or GitHub release. "
                + "Press Back to return to the launcher.");
        setContentView(help);
        Intent incoming = getIntent();
        if (incoming == null || incoming.getComponent() == null) { finish(); return; }
        String alias = incoming.getComponent().getClassName();
        for (String[] row : SplitApps.ALL) {
            if ((AppNetworkScope.SUITE_PACKAGE + row[0]).equals(alias)) {
                Intent target = new Intent(incoming);
                target.setClassName(row[1], row[3]);
                target.setPackage(row[1]);
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(target);
                    finish();
                } catch (ActivityNotFoundException missing) {
                    SplitAppRouter.showMissingApp(this, target, true);
                }
                return;
            }
        }
        finish();
    }
}
