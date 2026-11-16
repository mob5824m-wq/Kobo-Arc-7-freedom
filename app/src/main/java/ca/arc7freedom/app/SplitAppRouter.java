package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.Toast;

/** Cross-app navigation must launch the owning APK, not another copy inside the caller. */
final class SplitAppRouter {
    private SplitAppRouter() {}

    static String ownerPackage(String className) {
        if ("ca.arc7freedom.app.MainActivity".equals(className)) return AppNetworkScope.SUITE_PACKAGE;
        if ("ca.arc7freedom.app.YouTubeActivity".equals(className)) return "ca.arc7freedom.youtube";
        if ("ca.arc7freedom.app.YouTubeMusicActivity".equals(className)) return "ca.arc7freedom.ytmusic";
        if ("ca.arc7freedom.app.Arc7DeskClockActivity".equals(className)) return "ca.arc7freedom.clock";
        for (String[] row : SplitApps.ALL) {
            if (row[3].equals(className) || (AppNetworkScope.SUITE_PACKAGE + row[0]).equals(className)) return row[1];
        }
        return null;
    }

    /** Preserve the action, URI, MIME type, extras and grants (e.g. Files -> Books). */
    static Intent route(Context context, Intent original, int requestCode) {
        if (original == null || original.getComponent() == null) return original;
        ComponentName component = original.getComponent();
        String owner = ownerPackage(component.getClassName());
        if (owner == null) return original;
        String cls = component.getClassName();
        for (String[] row : SplitApps.ALL) {
            if ((AppNetworkScope.SUITE_PACKAGE + row[0]).equals(cls)) { cls = row[3]; break; }
        }
        if (owner.equals(component.getPackageName()) && owner.equals(context.getPackageName())
                && cls.equals(component.getClassName())) return original;
        Intent routed = new Intent(original);
        routed.setComponent(new ComponentName(owner, cls));
        routed.setPackage(owner);
        if (!owner.equals(context.getPackageName()) && requestCode < 0) {
            routed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return routed;
    }

    static void showMissingApp(Context context, Intent target, final boolean finishAfter) {
        String owner = target != null && target.getComponent() != null
                ? ownerPackage(target.getComponent().getClassName()) : null;
        String label = owner;
        for (String[] row : SplitApps.ALL) if (row[1].equals(owner)) label = row[2];
        if ("ca.arc7freedom.youtube".equals(owner)) label = "YouTube";
        if ("ca.arc7freedom.ytmusic".equals(owner)) label = "YouTube Music";
        if (AppNetworkScope.SUITE_PACKAGE.equals(owner)) label = "Arc7 Freedom";
        String message = "Install or enable " + (label != null ? label : "the separate app")
                + " from the Wi-Fi installer portal or GitHub release.\n\n"
                + "This shortcut will not run a second copy inside " + context.getPackageName()
                + ". Each app keeps its own data, process and network usage.";
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;
            new AlertDialog.Builder(activity).setTitle("Separate app required")
                    .setMessage(message).setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            if (finishAfter) activity.finish();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface dialog) {
                            if (finishAfter) activity.finish();
                        }
                    }).create().show();
        } else {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}
