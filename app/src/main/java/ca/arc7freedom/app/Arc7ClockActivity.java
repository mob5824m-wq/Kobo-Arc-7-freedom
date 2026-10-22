package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Full-featured Android 4.2 Holo Dark **Arc7 Clock** App with 4 dedicated tabs:
 *   1. CLOCK & WORLD TIME (+ 1-tap Nightstand / Photo Dock launcher)
 *   2. ALARMS (Unlimited scheduled wake-up alarms, weekdays/weekends/daily/once, snooze, boot persistence)
 *   3. TIMERS (Background `AlarmManager.RTC_WAKEUP` countdown timers + quick `+1m..+1h` presets)
 *   4. STOPWATCH (Centisecond `00:00.00` split/lap timer)
 */
public class Arc7ClockActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Button[] tabButtons = new Button[4];
    private View[] tabPages = new View[4];
    private int currentTab = 0;

    // Tab 0 — Live Clock & World Time
    private TextView mainDigitalClockTv;
    private TextView mainDateTv;
    private TextView nextAlarmBannerTv;
    private LinearLayout worldClockBox;

    // Tab 1 — Alarms
    private TextView alarmSummaryTv;
    private LinearLayout alarmsListContainer;

    // Tab 2 — Countdown Timer
    private TextView timerDisplayTv;
    private TextView timerStatusTv;
    private EditText timerLabelInput;
    private Button timerStartPauseBtn;

    // Tab 3 — Stopwatch
    private TextView stopwatchDisplayTv;
    private Button swStartPauseBtn;
    private Button swLapBtn;
    private LinearLayout lapsListContainer;
    private boolean swRunning = false;
    private long swBaseElapsed = 0L;
    private long swAccumMs = 0L;
    private long swLastLapMs = 0L;
    private final List<String> swLaps = new ArrayList<String>();

    private static final String[][] WORLD_ZONES = new String[][]{
            {"UTC / GMT", "UTC"},
            {"New York / Toronto", "America/Toronto"},
            {"London", "Europe/London"},
            {"Berlin / Paris", "Europe/Berlin"},
            {"Tokyo", "Asia/Tokyo"},
            {"Sydney", "Australia/Sydney"}
    };

    private final Runnable fastTick = new Runnable() {
        @Override
        public void run() {
            updateLiveDisplays();
            ui.postDelayed(this, 50L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E141B);

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF14212D);
        header.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView title = new TextView(this);
        title.setText("Arc7 Clock  •  Alarms, Timers & Stopwatch");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Button modeToggleBtn = makeButton(
                Arc7ClockStore.is12HourMode(this) ? "12-HR (AM/PM)" : "24-HR MODE",
                0xFF2E7D32);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        mlp.rightMargin = dp(8);
        modeToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !Arc7ClockStore.is12HourMode(Arc7ClockActivity.this);
                Arc7ClockStore.set12HourMode(Arc7ClockActivity.this, next);
                modeToggleBtn.setText(next ? "12-HR (AM/PM)" : "24-HR MODE");
                refreshAlarmsList();
                updateLiveDisplays();
            }
        });
        header.addView(modeToggleBtn, mlp);

        Button standBtn = makeButton("Nightstand Dock", 0xFF00838F);
        standBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7UiAnimator.startAnimated(Arc7ClockActivity.this,
                        new Intent(Arc7ClockActivity.this, Arc7DeskClockActivity.class));
            }
        });
        header.addView(standBtn);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 4-tab bar
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xFF192836);
        tabs.setPadding(dp(6), dp(6), dp(6), dp(6));
        String[] labels = {"1. CLOCK", "2. ALARMS", "3. TIMERS", "4. STOPWATCH"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            tabButtons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            tabs.addView(b, lp);
        }
        root.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Tab pages container
        LinearLayout contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        tabPages[0] = buildClockPage();
        tabPages[1] = buildAlarmsPage();
        tabPages[2] = buildTimersPage();
        tabPages[3] = buildStopwatchPage();
        for (View p : tabPages) {
            contentHost.addView(p, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
        root.addView(contentHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);

        int initialTab = getIntent() != null ? getIntent().getIntExtra("tab", 0) : 0;
        selectTab(Math.max(0, Math.min(3, initialTab)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAlarmsList();
        ui.removeCallbacks(fastTick);
        ui.post(fastTick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(fastTick);
        super.onPause();
    }

    private void selectTab(int idx) {
        boolean changed = (currentTab != idx);
        currentTab = idx;
        for (int i = 0; i < 4; i++) {
            tabPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(6));
            if (i == idx) {
                gd.setColor(0xFF0097A7);
                tabButtons[i].setTextColor(Color.WHITE);
                if (changed) Arc7UiAnimator.animatePageSwitch(tabPages[i]);
            } else {
                gd.setColor(0xFF233446);
                tabButtons[i].setTextColor(0xFFB0BEC5);
            }
            tabButtons[i].setBackgroundDrawable(gd);
        }
        if (idx == 1) refreshAlarmsList();
        updateLiveDisplays();
    }

    /* ==================== TAB 0: CLOCK & WORLD TIME ==================== */

    private View buildClockPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(24));

        LinearLayout hero = makeCard();
        hero.setGravity(Gravity.CENTER);
        mainDigitalClockTv = new TextView(this);
        mainDigitalClockTv.setTextColor(Color.WHITE);
        mainDigitalClockTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        mainDigitalClockTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        mainDigitalClockTv.setGravity(Gravity.CENTER);
        hero.addView(mainDigitalClockTv);

        mainDateTv = new TextView(this);
        mainDateTv.setTextColor(0xFF80DEEA);
        mainDateTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        mainDateTv.setGravity(Gravity.CENTER);
        hero.addView(mainDateTv);

        nextAlarmBannerTv = new TextView(this);
        nextAlarmBannerTv.setTextColor(0xFFFFD54F);
        nextAlarmBannerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nextAlarmBannerTv.setGravity(Gravity.CENTER);
        nextAlarmBannerTv.setPadding(0, dp(10), 0, dp(6));
        hero.addView(nextAlarmBannerTv);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER);

        Button quickAlarmBtn = makeButton("+ Set New Alarm", 0xFF00796B);
        quickAlarmBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(1);
                showEditAlarmDialog(null);
            }
        });
        quickRow.addView(quickAlarmBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));

        View sp1 = new View(this);
        quickRow.addView(sp1, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button quickTimerBtn = makeButton("Start Countdown Timer", 0xFF1565C0);
        quickTimerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(2);
            }
        });
        quickRow.addView(quickTimerBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));

        View sp2 = new View(this);
        quickRow.addView(sp2, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button nightDockBtn = makeButton("Fullscreen Nightstand", 0xFF6A1B9A);
        nightDockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Arc7ClockActivity.this, Arc7DeskClockActivity.class));
            }
        });
        quickRow.addView(nightDockBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));

        hero.addView(quickRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(hero);

        LinearLayout worldCard = makeCard();
        TextView wh = new TextView(this);
        wh.setText("World Clocks");
        wh.setTextColor(0xFF4DD0E1);
        wh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        wh.setTypeface(Typeface.DEFAULT_BOLD);
        wh.setPadding(0, 0, 0, dp(8));
        worldCard.addView(wh);

        worldClockBox = new LinearLayout(this);
        worldClockBox.setOrientation(LinearLayout.VERTICAL);
        worldCard.addView(worldClockBox);
        page.addView(worldCard);

        sv.addView(page);
        return sv;
    }

    /* ==================== TAB 1: ALARMS ==================== */

    private View buildAlarmsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(24));

        LinearLayout topCard = makeCard();
        alarmSummaryTv = new TextView(this);
        alarmSummaryTv.setTextColor(0xFFFFD54F);
        alarmSummaryTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        alarmSummaryTv.setTypeface(Typeface.DEFAULT_BOLD);
        alarmSummaryTv.setPadding(0, 0, 0, dp(10));
        topCard.addView(alarmSummaryTv);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button addBtn = makeButton("+ Add Alarm", 0xFF2E7D32);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditAlarmDialog(null);
            }
        });
        actions.addView(addBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        View sp = new View(this);
        actions.addView(sp, new LinearLayout.LayoutParams(dp(12), dp(1)));

        Button testBtn = makeButton("Test Alarm Chime & Screen Wake Now", 0xFFEF6C00);
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent alert = new Intent(Arc7ClockActivity.this, Arc7AlarmAlertActivity.class);
                alert.putExtra("is_timer", false);
                alert.putExtra("label", "Test Alarm Preview (Loud Chime OK!)");
                startActivity(alert);
            }
        });
        actions.addView(testBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        topCard.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(topCard);

        alarmsListContainer = new LinearLayout(this);
        alarmsListContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(alarmsListContainer);

        sv.addView(page);
        return sv;
    }

    private void refreshAlarmsList() {
        if (alarmsListContainer == null) return;
        alarmsListContainer.removeAllViews();
        final List<Arc7ClockStore.AlarmEntry> list = Arc7ClockStore.loadAlarms(this);
        String summary = Arc7ClockStore.describeNearestEnabledAlarm(this);
        if (alarmSummaryTv != null) alarmSummaryTv.setText(summary);
        if (nextAlarmBannerTv != null) nextAlarmBannerTv.setText(summary);

        for (final Arc7ClockStore.AlarmEntry entry : list) {
            LinearLayout card = makeCard();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView timeTv = new TextView(this);
            timeTv.setText(entry.formatTimePreferred(this));
            timeTv.setTextColor(entry.enabled ? Color.WHITE : 0xFF78909C);
            timeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            timeTv.setTypeface(Typeface.DEFAULT_BOLD);
            info.addView(timeTv);

            TextView metaTv = new TextView(this);
            metaTv.setText(entry.label + "  •  " + entry.describeRepeat()
                    + "\nVol: " + entry.volumePercent + "%  •  " + Arc7ClockStore.getChimeLabel(entry.chimeId));
            metaTv.setTextColor(entry.enabled ? 0xFF80DEEA : 0xFF607D8B);
            metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            info.addView(metaTv);

            info.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditAlarmDialog(entry);
                }
            });
            row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button toggleBtn = makeButton(entry.enabled ? "ON" : "OFF",
                    entry.enabled ? 0xFF2E7D32 : 0xFF455A64);
            toggleBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    entry.enabled = !entry.enabled;
                    Arc7ClockStore.saveAlarms(Arc7ClockActivity.this, list);
                    Arc7ClockStore.scheduleOrCancelAlarm(Arc7ClockActivity.this, entry);
                    refreshAlarmsList();
                }
            });
            row.addView(toggleBtn, new LinearLayout.LayoutParams(dp(90), dp(46)));

            View gap = new View(this);
            row.addView(gap, new LinearLayout.LayoutParams(dp(8), dp(1)));

            Button delBtn = makeButton("Delete", 0xFFB71C1C);
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    entry.enabled = false;
                    Arc7ClockStore.scheduleOrCancelAlarm(Arc7ClockActivity.this, entry);
                    list.remove(entry);
                    Arc7ClockStore.saveAlarms(Arc7ClockActivity.this, list);
                    refreshAlarmsList();
                }
            });
            row.addView(delBtn, new LinearLayout.LayoutParams(dp(86), dp(46)));

            card.addView(row);
            alarmsListContainer.addView(card);
        }
    }

    private void showEditAlarmDialog(final Arc7ClockStore.AlarmEntry existing) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(6));
        scroll.addView(form);

        final TimePicker tp = new TimePicker(this);
        tp.setIs24HourView(!Arc7ClockStore.is12HourMode(this));
        Calendar cal = Calendar.getInstance();
        tp.setCurrentHour(existing != null ? existing.hour : cal.get(Calendar.HOUR_OF_DAY));
        tp.setCurrentMinute(existing != null ? existing.minute : cal.get(Calendar.MINUTE));
        form.addView(tp);

        final EditText labelEt = new EditText(this);
        labelEt.setHint("Alarm label (e.g. Morning Wake Up)");
        labelEt.setText(existing != null ? existing.label : "Alarm");
        labelEt.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(labelEt);

        final int[] repeatOptions = new int[]{
                Arc7ClockStore.REPEAT_EVERY_DAY,
                Arc7ClockStore.REPEAT_WEEKDAYS,
                Arc7ClockStore.REPEAT_WEEKENDS,
                Arc7ClockStore.REPEAT_ONCE
        };
        final String[] repeatNames = new String[]{
                "Repeat: Every Day",
                "Repeat: Weekdays (Mon–Fri)",
                "Repeat: Weekends (Sat–Sun)",
                "Repeat: One-Time Only"
        };
        final int[] chosenIdx = new int[]{0};
        if (existing != null) {
            for (int i = 0; i < repeatOptions.length; i++) {
                if (repeatOptions[i] == existing.repeatMask) chosenIdx[0] = i;
            }
        }
        final Button repeatCycleBtn = makeButton(repeatNames[chosenIdx[0]], 0xFF00695C);
        repeatCycleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosenIdx[0] = (chosenIdx[0] + 1) % repeatOptions.length;
                repeatCycleBtn.setText(repeatNames[chosenIdx[0]]);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        rlp.topMargin = dp(8);
        form.addView(repeatCycleBtn, rlp);

        // Nostalgic Apple & Android Alarm Chime Selector + Live Preview!
        final int[] selectedChimeIdx = new int[]{
                existing != null ? Arc7ClockStore.getChimeIndex(existing.chimeId) : 0
        };
        final int[] chosenVolPct = new int[]{
                existing != null ? existing.volumePercent : 85
        };

        LinearLayout chimeRow = new LinearLayout(this);
        chimeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        crlp.topMargin = dp(8);

        final Button chimeBtn = makeButton(
                "Chime: " + Arc7ClockStore.CHIME_LABELS[selectedChimeIdx[0]], 0xFF4A148C);
        chimeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chimeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(Arc7ClockActivity.this, AlertDialog.THEME_HOLO_DARK)
                        .setTitle("Choose Nostalgic Apple or Android Chime")
                        .setSingleChoiceItems(Arc7ClockStore.CHIME_LABELS, selectedChimeIdx[0],
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        selectedChimeIdx[0] = which;
                                        chimeBtn.setText("Chime: " + Arc7ClockStore.CHIME_LABELS[which]);
                                        Arc7ClockStore.previewChime(
                                                Arc7ClockActivity.this,
                                                Arc7ClockStore.CHIME_IDS[which],
                                                Math.max(15, chosenVolPct[0]));
                                    }
                                })
                        .setPositiveButton("Use Selected Chime", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Arc7ClockStore.stopAlarmRinging();
                            }
                        })
                        .show();
            }
        });
        chimeRow.addView(chimeBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View cgap = new View(this);
        chimeRow.addView(cgap, new LinearLayout.LayoutParams(dp(8), dp(1)));

        Button previewBtn = makeButton("\u25B6 Test", 0xFF00838F);
        previewBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7ClockStore.previewChime(
                        Arc7ClockActivity.this,
                        Arc7ClockStore.CHIME_IDS[selectedChimeIdx[0]],
                        chosenVolPct[0]);
            }
        });
        chimeRow.addView(previewBtn, new LinearLayout.LayoutParams(dp(92), dp(44)));
        form.addView(chimeRow, crlp);

        // Individual Per-Alarm Volume Slider (`0% - 100%`)
        final TextView volLabel = new TextView(this);
        volLabel.setText("Individual Alarm Volume: " + chosenVolPct[0] + "%");
        volLabel.setTextColor(0xFFFFD54F);
        volLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        volLabel.setTypeface(Typeface.DEFAULT_BOLD);
        volLabel.setPadding(dp(2), dp(10), dp(2), dp(2));
        form.addView(volLabel);

        SeekBar volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(chosenVolPct[0]);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                chosenVolPct[0] = progress;
                volLabel.setText("Individual Alarm Volume: " + progress + "%"
                        + (progress == 0 ? " (Silent Screen-Wake Only)" : ""));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (chosenVolPct[0] > 0) {
                    Arc7ClockStore.previewChime(
                            Arc7ClockActivity.this,
                            Arc7ClockStore.CHIME_IDS[selectedChimeIdx[0]],
                            chosenVolPct[0]);
                }
            }
        });
        form.addView(volBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(existing == null ? "Set New Alarm" : "Edit Alarm")
                .setView(scroll)
                .setPositiveButton("Save & Enable Alarm", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Arc7ClockStore.stopAlarmRinging();
                        tp.clearFocus();
                        List<Arc7ClockStore.AlarmEntry> list = Arc7ClockStore.loadAlarms(Arc7ClockActivity.this);
                        Arc7ClockStore.AlarmEntry target = null;
                        if (existing != null) {
                            for (Arc7ClockStore.AlarmEntry e : list) {
                                if (e.id == existing.id) { target = e; break; }
                            }
                        }
                        if (target == null) {
                            target = new Arc7ClockStore.AlarmEntry();
                            target.id = Arc7ClockStore.nextAlarmId(Arc7ClockActivity.this);
                            list.add(target);
                        }
                        target.hour = tp.getCurrentHour();
                        target.minute = tp.getCurrentMinute();
                        String lbl = labelEt.getText().toString().trim();
                        target.label = lbl.isEmpty() ? "Alarm" : lbl;
                        target.repeatMask = repeatOptions[chosenIdx[0]];
                        target.chimeId = Arc7ClockStore.CHIME_IDS[selectedChimeIdx[0]];
                        target.volumePercent = chosenVolPct[0];
                        target.enabled = true;
                        Arc7ClockStore.saveAlarms(Arc7ClockActivity.this, list);
                        Arc7ClockStore.scheduleOrCancelAlarm(Arc7ClockActivity.this, target);
                        refreshAlarmsList();
                        Toast.makeText(Arc7ClockActivity.this,
                                Arc7ClockStore.describeNearestEnabledAlarm(Arc7ClockActivity.this),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Arc7ClockStore.stopAlarmRinging();
                    }
                })
                .show();
    }

    /* ==================== TAB 2: COUNTDOWN TIMERS ==================== */

    private View buildTimersPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(24));

        LinearLayout card = makeCard();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        timerDisplayTv = new TextView(this);
        timerDisplayTv.setTextColor(Color.WHITE);
        timerDisplayTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        timerDisplayTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerDisplayTv.setGravity(Gravity.CENTER);
        card.addView(timerDisplayTv);

        timerStatusTv = new TextView(this);
        timerStatusTv.setTextColor(0xFF80DEEA);
        timerStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timerStatusTv.setGravity(Gravity.CENTER);
        timerStatusTv.setPadding(0, dp(4), 0, dp(10));
        card.addView(timerStatusTv);

        timerLabelInput = new EditText(this);
        timerLabelInput.setHint("Timer name (e.g. Tea, Baking, Pomodoro 25m)");
        timerLabelInput.setText(Arc7ClockStore.getTimerLabel(this));
        timerLabelInput.setTextColor(Color.WHITE);
        timerLabelInput.setHintTextColor(0xFF78909C);
        card.addView(timerLabelInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Quick-add duration row
        LinearLayout presets1 = new LinearLayout(this);
        presets1.setOrientation(LinearLayout.HORIZONTAL);
        presets1.setPadding(0, dp(12), 0, dp(8));
        final int[] addSeconds = new int[]{60, 180, 300, 600, 900, 1800, 3600};
        final String[] addTitles = new String[]{"+1m", "+3m", "+5m", "+10m", "+15m", "+30m", "+1h"};
        for (int i = 0; i < addSeconds.length; i++) {
            final long deltaMs = addSeconds[i] * 1000L;
            Button pb = makeButton(addTitles[i], 0xFF26384C);
            pb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    addDurationToTimer(deltaMs);
                }
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            plp.leftMargin = dp(2);
            plp.rightMargin = dp(2);
            presets1.addView(pb, plp);
        }
        card.addView(presets1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Main controls
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setPadding(0, dp(8), 0, 0);

        timerStartPauseBtn = makeButton("START TIMER", 0xFF2E7D32);
        timerStartPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTimerStartPause();
            }
        });
        ctrl.addView(timerStartPauseBtn, new LinearLayout.LayoutParams(0, dp(56), 1.4f));

        View sp1 = new View(this);
        ctrl.addView(sp1, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button reset5Btn = makeButton("Reset 05:00", 0xFF455A64);
        reset5Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7ClockStore.saveTimerState(Arc7ClockActivity.this, false, 0L, 300000L, getCleanTimerLabel());
                updateLiveDisplays();
            }
        });
        ctrl.addView(reset5Btn, new LinearLayout.LayoutParams(0, dp(56), 1f));

        View sp2 = new View(this);
        ctrl.addView(sp2, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button clear0Btn = makeButton("Clear 00:00", 0xFFB71C1C);
        clear0Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7ClockStore.saveTimerState(Arc7ClockActivity.this, false, 0L, 0L, getCleanTimerLabel());
                updateLiveDisplays();
            }
        });
        ctrl.addView(clear0Btn, new LinearLayout.LayoutParams(0, dp(56), 1f));

        card.addView(ctrl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(card);
        sv.addView(page);
        return sv;
    }

    private String getCleanTimerLabel() {
        String s = timerLabelInput != null ? timerLabelInput.getText().toString().trim() : "";
        return s.isEmpty() ? "Countdown Timer" : s;
    }

    private void addDurationToTimer(long deltaMs) {
        if (Arc7ClockStore.isTimerRunning(this)) {
            long newEnd = Math.max(System.currentTimeMillis(), Arc7ClockStore.getTimerEndWallMs(this)) + deltaMs;
            Arc7ClockStore.saveTimerState(this, true, newEnd, 0L, getCleanTimerLabel());
        } else {
            long rem = Arc7ClockStore.getTimerPausedRemainingMs(this) + deltaMs;
            Arc7ClockStore.saveTimerState(this, false, 0L, rem, getCleanTimerLabel());
        }
        updateLiveDisplays();
    }

    private void toggleTimerStartPause() {
        if (Arc7ClockStore.isTimerRunning(this)) {
            long rem = Math.max(0L, Arc7ClockStore.getTimerEndWallMs(this) - System.currentTimeMillis());
            Arc7ClockStore.saveTimerState(this, false, 0L, rem, getCleanTimerLabel());
        } else {
            long rem = Arc7ClockStore.getTimerPausedRemainingMs(this);
            if (rem <= 0L) rem = 60000L; // Default 1 minute if starting from 00:00:00
            long end = System.currentTimeMillis() + rem;
            Arc7ClockStore.saveTimerState(this, true, end, 0L, getCleanTimerLabel());
        }
        updateLiveDisplays();
    }

    /* ==================== TAB 3: STOPWATCH ==================== */

    private View buildStopwatchPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(24));

        LinearLayout card = makeCard();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        stopwatchDisplayTv = new TextView(this);
        stopwatchDisplayTv.setText("00:00.00");
        stopwatchDisplayTv.setTextColor(Color.WHITE);
        stopwatchDisplayTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        stopwatchDisplayTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stopwatchDisplayTv.setGravity(Gravity.CENTER);
        card.addView(stopwatchDisplayTv);

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setPadding(0, dp(14), 0, dp(6));

        swStartPauseBtn = makeButton("START", 0xFF2E7D32);
        swStartPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (swRunning) {
                    swAccumMs += SystemClock.elapsedRealtime() - swBaseElapsed;
                    swRunning = false;
                } else {
                    swBaseElapsed = SystemClock.elapsedRealtime();
                    swRunning = true;
                }
                updateLiveDisplays();
            }
        });
        ctrl.addView(swStartPauseBtn, new LinearLayout.LayoutParams(0, dp(54), 1.3f));

        View sp1 = new View(this);
        ctrl.addView(sp1, new LinearLayout.LayoutParams(dp(10), dp(1)));

        swLapBtn = makeButton("LAP SPLIT", 0xFF1565C0);
        swLapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long total = currentStopwatchMs();
                if (total <= 0L) return;
                long split = total - swLastLapMs;
                swLastLapMs = total;
                int lapNum = swLaps.size() + 1;
                swLaps.add(0, String.format(Locale.US, "Lap %02d   +%s   (Total  %s)",
                        lapNum, formatCentis(split), formatCentis(total)));
                rebuildLapsView();
            }
        });
        ctrl.addView(swLapBtn, new LinearLayout.LayoutParams(0, dp(54), 1f));

        View sp2 = new View(this);
        ctrl.addView(sp2, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button resetBtn = makeButton("RESET", 0xFF455A64);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swRunning = false;
                swAccumMs = 0L;
                swLastLapMs = 0L;
                swLaps.clear();
                rebuildLapsView();
                updateLiveDisplays();
            }
        });
        ctrl.addView(resetBtn, new LinearLayout.LayoutParams(0, dp(54), 1f));

        card.addView(ctrl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(card);

        lapsListContainer = new LinearLayout(this);
        lapsListContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(lapsListContainer);
        sv.addView(page);
        return sv;
    }

    private void rebuildLapsView() {
        if (lapsListContainer == null) return;
        lapsListContainer.removeAllViews();
        if (swLaps.isEmpty()) return;
        LinearLayout card = makeCard();
        for (String line : swLaps) {
            TextView tv = new TextView(this);
            tv.setText(line);
            tv.setTextColor(0xFFE0F7FA);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            tv.setPadding(0, dp(5), 0, dp(5));
            card.addView(tv);
        }
        lapsListContainer.addView(card);
    }

    private long currentStopwatchMs() {
        return swRunning ? (swAccumMs + (SystemClock.elapsedRealtime() - swBaseElapsed)) : swAccumMs;
    }

    private static String formatCentis(long ms) {
        long centis = (ms % 1000L) / 10L;
        long totalSec = ms / 1000L;
        long s = totalSec % 60L;
        long m = (totalSec / 60L) % 60L;
        long h = totalSec / 3600L;
        if (h > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, centis);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, centis);
    }

    private static String formatHhMmSs(long ms) {
        long totalSec = Math.max(0L, (ms + 999L) / 1000L);
        long s = totalSec % 60L;
        long m = (totalSec / 60L) % 60L;
        long h = totalSec / 3600L;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private void updateLiveDisplays() {
        Date now = new Date();
        boolean use12 = Arc7ClockStore.is12HourMode(this);
        if (currentTab == 0 && mainDigitalClockTv != null) {
            mainDigitalClockTv.setText(Arc7ClockStore.formatClockNow(this, now, true));
            TimeZone localTz = TimeZone.getDefault();
            mainDateTv.setText(new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(now)
                    + "  •  " + localTz.getID());
            if (worldClockBox != null && worldClockBox.getChildCount() != WORLD_ZONES.length) {
                worldClockBox.removeAllViews();
                for (int i = 0; i < WORLD_ZONES.length; i++) {
                    TextView row = new TextView(this);
                    row.setTextColor(0xFFCFD8DC);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    row.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    row.setPadding(0, dp(4), 0, dp(4));
                    worldClockBox.addView(row);
                }
            }
            for (int i = 0; i < WORLD_ZONES.length && worldClockBox != null; i++) {
                SimpleDateFormat zf = new SimpleDateFormat(use12 ? "h:mm a  (EEE)" : "HH:mm  (EEE)", Locale.US);
                zf.setTimeZone(TimeZone.getTimeZone(WORLD_ZONES[i][1]));
                TextView row = (TextView) worldClockBox.getChildAt(i);
                row.setText(String.format(Locale.US, "%-22s %s", WORLD_ZONES[i][0], zf.format(now)));
            }
        }

        // Countdown Timer display
        if (timerDisplayTv != null) {
            boolean running = Arc7ClockStore.isTimerRunning(this);
            long rem;
            if (running) {
                rem = Arc7ClockStore.getTimerEndWallMs(this) - System.currentTimeMillis();
                if (rem <= 0L) {
                    rem = 0L;
                }
                timerStatusTv.setText("RUNNING — Background screen-wake chime armed even if screen is OFF!");
                timerStartPauseBtn.setText("PAUSE TIMER");
                styleButtonBg(timerStartPauseBtn, 0xFFEF6C00);
            } else {
                rem = Arc7ClockStore.getTimerPausedRemainingMs(this);
                timerStatusTv.setText("Tap +1m / +5m / +15m to adjust duration, then START TIMER.");
                timerStartPauseBtn.setText("START TIMER");
                styleButtonBg(timerStartPauseBtn, 0xFF2E7D32);
            }
            timerDisplayTv.setText(formatHhMmSs(rem));
        }

        // Stopwatch display
        if (stopwatchDisplayTv != null) {
            stopwatchDisplayTv.setText(formatCentis(currentStopwatchMs()));
            swStartPauseBtn.setText(swRunning ? "PAUSE" : "START");
            styleButtonBg(swStartPauseBtn, swRunning ? 0xFFEF6C00 : 0xFF2E7D32);
        }
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF172330);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), 0xFF2A3E52);
        card.setBackgroundDrawable(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private Button makeButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        styleButtonBg(b, bgColor);
        return b;
    }

    private void styleButtonBg(Button b, int bgColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(7));
        gd.setStroke(dp(1), 0x44FFFFFF);
        b.setBackgroundDrawable(gd);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
