package ca.arc7freedom.support;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import ca.arc7freedom.glass.LiquidGlassDrawable;

/** Lightweight common material; no opaque gradient replacement and no continuous blur/capture. */
public final class SupportUi {
    private SupportUi() {}
    public static void window(Activity activity) { activity.getWindow().setBackgroundDrawable(LiquidGlassDrawable.ambient(0xff091321,0xff6cceff)); }
    public static void panel(Activity activity,TextView text,boolean action) {
        float density=activity.getResources().getDisplayMetrics().density;
        text.setTextColor(action?0xffe6f5ff:0xffd8e7f7);
        text.setPadding((int)(14*density),(int)(10*density),(int)(14*density),(int)(12*density));
        text.setBackgroundDrawable(new LiquidGlassDrawable(0xff172d48,0xff8cdaff,22*density,density,action?100:66));
    }
}
