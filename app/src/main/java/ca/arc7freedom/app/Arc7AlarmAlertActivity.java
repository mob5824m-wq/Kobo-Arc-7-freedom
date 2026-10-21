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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen wake-up Alarm & Timer Alert Activity (`SHOW_WHEN_LOCKED | TURN_SCREEN_ON`).
 * Shows huge readable clock + label + one-tap SNOOZE / +1 MIN and DISMISS buttons.
 */
public class Arc7AlarmAlertActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView liveClockTv;
    private boolean isTimer;
    private String label;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (liveClockTv != null) {
                liveClockTv.setText(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
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
        Arc7ClockStore.startAlarmRinging(getApplicationContext());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0A121E);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView kindBadge = new TextView(this);
        kindBadge.setText(isTimer ? "TIMER FINISHED" : "ALARM RINGING");
        kindBadge.setTextColor(0xFFFFD54F);
        kindBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        kindBadge.setTypeface(Typeface.DEFAULT_BOLD);
        kindBadge.setGravity(Gravity.CENTER);
        root.addView(kindBadge);

        liveClockTv = new TextView(this);
        liveClockTv.setTextColor(Color.WHITE);
        liveClockTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76);
        liveClockTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        liveClockTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        root.addView(liveClockTv, clp);

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextColor(0xFF64D8FF);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        labelTv.setTypeface(Typeface.DEFAULT_BOLD);
        labelTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        llp.bottomMargin = dp(32);
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
                    Arc7ClockStore.scheduleSnooze(Arc7AlarmAlertActivity.this, label, 5);
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
        handler.post(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readExtras(intent);
        Arc7ClockStore.startAlarmRinging(getApplicationContext());
    }

    private void readExtras(Intent i) {
        isTimer = i != null && i.getBooleanExtra("is_timer", false);
        label = (i != null && i.getStringExtra("label") != null)
                ? i.getStringExtra("label")
                : (isTimer ? "Countdown Timer Complete!" : "Alarm");
    }

    private Button makeBigButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(2), 0x55FFFFFF);
        b.setBackgroundDrawable(gd);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        Arc7ClockStore.stopAlarmRinging();
        super.onDestroy();
    }
}
