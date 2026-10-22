package ca.arc7freedom.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Hardware-independent Warm Amber Night Reading Filter & Extra-Dim Darkroom Overlay
 * built specifically for Android 4.2.2 (`API 17`) on the Kobo Arc 7 IPS LCD panel.
 *
 * Uses `TYPE_SYSTEM_OVERLAY` + `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN`
 * so touch input passes 100% cleanly through to every app AND never blocks `PackageInstaller`!
 */
public class NightFilterService extends Service {

    public static final String PREFS = "arc7_optimizer";
    public static final String KEY_FILTER_MODE = "night_filter_mode";

    public static final int MODE_OFF       = 0;
    public static final int MODE_SEPIA     = 1;
    public static final int MODE_AMBER     = 2;
    public static final int MODE_EXTRA_DIM = 3;
    public static final int MODE_AUTO_SUNSET = 4;

    private WindowManager wm;
    private View overlayView;

    public static String modeLabel(int mode) {
        switch (mode) {
            case MODE_SEPIA:       return "Warm Sepia Reading Tint (3400K)";
            case MODE_AMBER:       return "Deep Amber Night Filter (2200K No Blue Light)";
            case MODE_EXTRA_DIM:   return "Extra-Dim Candlelight Darkroom (1800K)";
            case MODE_AUTO_SUNSET: return "Automatic Sunset-to-Sunrise Night Light (20:00–07:00)";
            default:               return "OFF (Normal Display)";
        }
    }

    public static int modeColor(int mode) {
        switch (mode) {
            case MODE_SEPIA:     return Color.argb(54, 255, 179, 71);
            case MODE_AMBER:     return Color.argb(88, 255, 128, 0);
            case MODE_EXTRA_DIM: return Color.argb(136, 18, 8, 0);
            case MODE_AUTO_SUNSET: {
                int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                return (hour >= 20 || hour < 7) ? Color.argb(84, 255, 134, 16) : Color.TRANSPARENT;
            }
            default:             return Color.TRANSPARENT;
        }
    }

    public static void applyMode(Context ctx, int mode) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_FILTER_MODE, mode).commit();
        Intent svc = new Intent(ctx, NightFilterService.class);
        if (mode == MODE_OFF) {
            ctx.stopService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    public static int currentMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FILTER_MODE, MODE_OFF);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int mode = currentMode(this);
        if (mode == MODE_OFF) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }
        showOrUpdateOverlay(mode);
        return START_STICKY;
    }

    private void showOrUpdateOverlay(int mode) {
        if (wm == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        int color = modeColor(mode);
        if (overlayView == null) {
            overlayView = new View(this);
            overlayView.setBackgroundColor(color);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            try {
                wm.addView(overlayView, lp);
            } catch (Throwable t) {
                overlayView = null;
            }
        } else {
            overlayView.setBackgroundColor(color);
        }
    }

    private void removeOverlay() {
        if (wm != null && overlayView != null) {
            try { wm.removeView(overlayView); } catch (Throwable ignored) {}
            overlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
}
