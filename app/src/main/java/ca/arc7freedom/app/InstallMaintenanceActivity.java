package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Compatibility entry point for old shortcuts; patches now live inside the main app. */
public class InstallMaintenanceActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_tab", "patches"));
        finish();
    }
}
