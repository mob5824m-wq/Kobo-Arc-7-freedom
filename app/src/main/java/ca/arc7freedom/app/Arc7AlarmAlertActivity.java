package ca.arc7freedom.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * Full-screen wake-up Alarm & Timer Alert Activity (`SHOW_WHEN_LOCKED | TURN_SCREEN_ON`)
 * with 12-hour/24-hour live clock, nostalgic Apple/Android chime + per-alarm volume playback,
 * smooth pulse animation, and large bedside SNOOZE / DISMISS controls.
 */
public class Arc7AlarmAlertActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView kindBadge;
    private TextView liveClockTv;
    private boolean isTimer;
    private String label;
    private String chimeId;
    private int volumePct = 85;
    private boolean pulseUp = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (liveClockTv != null) {
                liveClockTv.setText(Arc7ClockStore.formatClockNow(
                        Arc7AlarmAlertActivity.this, new Date(), true));
            }
            if (kindBadge != null) {
                pulseUp = !pulseUp;
                kindBadge.animate().scaleX(pulseUp ? 1.07f : 0.96f)
                        .scaleY(pulseUp ? 1.07f : 0.96f)
                        .setDuration(460).start();
            }
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        readExtras(getIntent());
        Arc7ClockStore.startAlarmRinging(getApplicationContext(), chimeId, volumePct);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0A121E);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));

        kindBadge = new TextView(this);
        kindBadge.setText((isTimer ? "TIMER FINISHED" : "ALARM RINGING")
                + "  •  VOL " + volumePct + "%");
        kindBadge.setTextColor(0xFFFFD54F);
        kindBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        kindBadge.setTypeface(Typeface.DEFAULT_BOLD);
        kindBadge.setGravity(Gravity.CENTER);
        root.addView(kindBadge);

        liveClockTv = new TextView(this);
        liveClockTv.setTextColor(Color.WHITE);
        liveClockTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        liveClockTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        liveClockTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        root.addView(liveClockTv, clp);

        TextView labelTv = new TextView(this);
        labelTv.setText(label + "\n" + Arc7ClockStore.getChimeLabel(chimeId));
        labelTv.setTextColor(0xFF64D8FF);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        labelTv.setTypeface(Typeface.DEFAULT_BOLD);
        labelTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        llp.bottomMargin = dp(28);
        root.addView(labelTv, llp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button secondaryBtn = makeBigButton(
                isTimer ? "+1 MIN EXTRA" : "SNOOZE 5 MIN", 0xFF1565C0);
        secondaryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7ClockStore.stopAlarmRinging();
                if (isTimer) {
                    long end = System.currentTimeMillis() + 60000L;
                    Arc7ClockStore.saveTimerState(Arc7AlarmAlertActivity.this, true, end, 0L, label);
                    Toast.makeText(Arc7AlarmAlertActivity.this, "Added +1 minute to timer", Toast.LENGTH_SHORT).show();
                } else {
                    Arc7ClockStore.scheduleSnooze(Arc7AlarmAlertActivity.this, label, 5, chimeId, volumePct);
                    Toast.makeText(Arc7AlarmAlertActivity.this, "Snoozed for 5 minutes", Toast.LENGTH_SHORT).show();
                }
                finish();
            }
        });
        btnRow.addView(secondaryBtn, new LinearLayout.LayoutParams(0, dp(72), 1f));

        View spacer = new View(this);
        btnRow.addView(spacer, new LinearLayout.LayoutParams(dp(18), dp(1)));

        Button dismissBtn = makeBigButton("DISMISS", 0xFF2E7D32);
        dismissBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7ClockStore.stopAlarmRinging();
                finish();
            }
        });
        btnRow.addView(dismissBtn, new LinearLayout.LayoutParams(0, dp(72), 1f));

        root.addView(btnRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        handler.post(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readExtras(intent);
        Arc7ClockStore.startAlarmRinging(getApplicationContext(), chimeId, volumePct);
    }

    private void readExtras(Intent intent) {
        isTimer = intent != null && intent.getBooleanExtra("is_timer", false);
        label = intent != null ? intent.getStringExtra("label") : null;
        if (label == null || label.isEmpty()) label = isTimer ? "Timer Complete!" : "Alarm";
        chimeId = intent != null ? intent.getStringExtra("chime_id") : null;
        if (chimeId == null || chimeId.isEmpty()) chimeId = Arc7ClockStore.CHIME_APPLE_MARIMBA;
        volumePct = intent != null ? intent.getIntExtra("volume_pct", 85) : 85;
    }

    @Override
    public void onBackPressed() {
        Arc7ClockStore.stopAlarmRinging();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    private Button makeBigButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(14));
        gd.setStroke(dp(2), 0x66FFFFFF);
        b.setBackgroundDrawable(gd);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                            .setInterpolator(new OvershootInterpolator()).start();
                }
                return false;
            }
        });
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
