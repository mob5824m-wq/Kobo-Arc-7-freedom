package ca.arc7freedom.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;

/**
 * Upgrade #4: Draggable Floating Soft-Button Pill (`Save Worn Power & Volume Buttons!`).
 *
 * Kobo Arc 7 physical power switches & volume rockers wear out after 13+ years.
 * Renders a compact draggable edge button `[≡]` (`FLAG_NOT_FOCUSABLE` so keyboards always work!)
 * that expands into **Lock/Off**, **Home**, **Back**, **Vol-**, **Vol+**, and **Screenshot**.
 */
public class SoftKeysOverlayService extends Service {

    private static volatile boolean active = false;
    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private LinearLayout rootPill;
    private LinearLayout expandedRow;
    private RootShell shell;
    private AudioManager audioManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean expanded = false;

    public static boolean isActive() {
        return active;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        shell = new RootShell(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (rootPill == null) {
            buildFloatingPill();
        }
        active = true;
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void buildFloatingPill() {
        rootPill = new LinearLayout(this);
        rootPill.setOrientation(LinearLayout.HORIZONTAL);
        rootPill.setGravity(Gravity.CENTER_VERTICAL);
        rootPill.setPadding(dp(4), dp(4), dp(4), dp(4));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(18));
        pillBg.setColor(Color.argb(215, 15, 23, 42));
        pillBg.setStroke(dp(1), Color.parseColor("#38BDF8"));
        rootPill.setBackgroundDrawable(pillBg);

        final Button handleBtn = makeMiniBtn("≡", "#0284C7");
        rootPill.addView(handleBtn);

        expandedRow = new LinearLayout(this);
        expandedRow.setOrientation(LinearLayout.HORIZONTAL);
        expandedRow.setVisibility(View.GONE);

        Button homeBtn = makeMiniBtn("Home", "#334155");
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent h = new Intent(Intent.ACTION_MAIN);
                h.addCategory(Intent.CATEGORY_HOME);
                h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(h);
                toggleExpand(false);
            }
        });
        expandedRow.addView(homeBtn);

        Button backBtn = makeMiniBtn("Back", "#334155");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyEventAsync(4);
            }
        });
        expandedRow.addView(backBtn);

        Button volDown = makeMiniBtn("Vol-", "#334155");
        volDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                }
            }
        });
        expandedRow.addView(volDown);

        Button volUp = makeMiniBtn("Vol+", "#334155");
        volUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                }
            }
        });
        expandedRow.addView(volUp);

        Button shotBtn = makeMiniBtn("Shot", "#16A34A");
        shotBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleExpand(false);
                captureScreenshotAsync();
            }
        });
        expandedRow.addView(shotBtn);

        Button lockBtn = makeMiniBtn("Screen Off", "#DC2626");
        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleExpand(false);
                sendKeyEventAsync(26); // KEYCODE_POWER
            }
        });
        expandedRow.addView(lockBtn);

        Button closeBtn = makeMiniBtn("×", "#64748B");
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSelf();
            }
        });
        expandedRow.addView(closeBtn);

        rootPill.addView(expandedRow);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 12;
        params.y = 140;

        /* Drag OR tap on `[≡]` handle */
        handleBtn.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            dragged = true;
                            params.x = startX + dx;
                            params.y = startY + dy;
                            try { wm.updateViewLayout(rootPill, params); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) toggleExpand(!expanded);
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(rootPill, params);
        } catch (Throwable ignored) {
            rootPill = null;
        }
    }

    private void toggleExpand(boolean show) {
        expanded = show;
        if (expandedRow != null) {
            expandedRow.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void sendKeyEventAsync(final int keyCode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.ExecResult r = shell.execRoot("input keyevent " + keyCode, null);
                if (r.exitCode != 0) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SoftKeysOverlayService.this,
                                    "Root needed for hardware key injection — tap Root on Tab 1 first!",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void captureScreenshotAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String sd = TabletOptimizerManager.findMicroSdRoot();
                File dir = new File(sd != null ? sd : Environment.getExternalStorageDirectory().getAbsolutePath(),
                        "Pictures/Screenshots");
                dir.mkdirs();
                final File out = new File(dir, "Arc7_" + (System.currentTimeMillis() / 1000L) + ".png");
                shell.execRoot("sleep 1 && screencap -p " + RootShell.shellQuote(out.getAbsolutePath()), null);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SoftKeysOverlayService.this,
                                out.exists() ? ("Saved screenshot -> " + out.getAbsolutePath())
                                             : "Screenshot requires Root on Tab 1 first.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private Button makeMiniBtn(String text, String hex) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setPadding(dp(9), dp(6), dp(9), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor(hex));
        b.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    public void onDestroy() {
        active = false;
        if (wm != null && rootPill != null) {
            try { wm.removeView(rootPill); } catch (Throwable ignored) {}
            rootPill = null;
        }
        super.onDestroy();
    }
}
