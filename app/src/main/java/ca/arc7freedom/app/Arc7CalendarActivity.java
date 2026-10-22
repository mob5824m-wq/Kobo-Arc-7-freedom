package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Full Skinned Standalone **Arc7 Calendar** App (`v2.0.0`):
 *   - Interactive Month Grid with category color dots & Today highlight
 *   - Selected-Day Timeline & Event Editor (Title, Time, All-Day, Location, Notes,
 *     Color Category, Daily/Weekly/Monthly/Yearly Recurrence, & Wake-Screen Reminders!)
 *   - 30-Day Upcoming Agenda Overview
 *   - Built-in Canadian / US / International Holidays
 *   - 1-Tap RFC 5545 `.ics` iCalendar Export & Import
 */
public class Arc7CalendarActivity extends Activity {

    private int viewYear;
    private int viewMonth; // 0-based
    private int selectedDay;
    private TextView monthHeaderTv;
    private LinearLayout monthGridBox;
    private TextView selectedDayHeaderTv;
    private LinearLayout dayEventsBox;
    private LinearLayout upcomingAgendaBox;

    private int dp(int v) {
        return Arc7Skin.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Calendar now = Calendar.getInstance();
        viewYear = now.get(Calendar.YEAR);
        viewMonth = now.get(Calendar.MONTH);
        selectedDay = now.get(Calendar.DAY_OF_MONTH);

        Arc7Skin.Palette pal = Arc7Skin.current(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        LinearLayout headerActions = new LinearLayout(this);
        headerActions.setOrientation(LinearLayout.HORIZONTAL);

        Button todayBtn = Arc7Skin.actionButton(this, "Today", 0xFF334155);
        todayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar c = Calendar.getInstance();
                viewYear = c.get(Calendar.YEAR);
                viewMonth = c.get(Calendar.MONTH);
                selectedDay = c.get(Calendar.DAY_OF_MONTH);
                renderCalendar();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = dp(8);
        headerActions.addView(todayBtn, tlp);

        Button icsBtn = Arc7Skin.actionButton(this, ".ICS Sync", 0xFF0284C7);
        icsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showIcsMenuDialog();
            }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = dp(8);
        headerActions.addView(icsBtn, ilp);

        Button addBtn = Arc7Skin.actionButton(this, "+ Event", 0xFF059669);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEventDialog(null);
            }
        });
        headerActions.addView(addBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_calendar,
                "Calendar",
                "Month Grid • Reminders • Recurring Events • .ICS Sync",
                headerActions));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(22));

        // Month Card
        LinearLayout monthCard = new LinearLayout(this);
        monthCard.setOrientation(LinearLayout.VERTICAL);
        monthCard.setPadding(dp(12), dp(10), dp(12), dp(12));
        monthCard.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 16));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        Button prevBtn = Arc7Skin.actionButton(this, "◀ Prev", 0xFF334155);
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepMonth(-1);
            }
        });
        navRow.addView(prevBtn);

        monthHeaderTv = new TextView(this);
        monthHeaderTv.setTextColor(pal.textPrimary);
        monthHeaderTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        monthHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        monthHeaderTv.setGravity(Gravity.CENTER);
        navRow.addView(monthHeaderTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button nextBtn = Arc7Skin.actionButton(this, "Next ▶", 0xFF334155);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepMonth(1);
            }
        });
        navRow.addView(nextBtn);
        monthCard.addView(navRow);

        // Weekday column labels
        LinearLayout dowRow = new LinearLayout(this);
        dowRow.setOrientation(LinearLayout.HORIZONTAL);
        dowRow.setPadding(0, dp(10), 0, dp(4));
        String[] dows = new String[]{"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
        for (int i = 0; i < 7; i++) {
            TextView d = new TextView(this);
            d.setText(dows[i]);
            d.setGravity(Gravity.CENTER);
            d.setTextColor((i == 0 || i == 6) ? pal.accent : pal.textMuted);
            d.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            dowRow.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        monthCard.addView(dowRow);

        monthGridBox = new LinearLayout(this);
        monthGridBox.setOrientation(LinearLayout.VERTICAL);
        monthCard.addView(monthGridBox);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        body.addView(monthCard, cardLp);

        // Selected Day Events Card
        LinearLayout dayCard = new LinearLayout(this);
        dayCard.setOrientation(LinearLayout.VERTICAL);
        dayCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        dayCard.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, pal, 16));

        selectedDayHeaderTv = new TextView(this);
        selectedDayHeaderTv.setTextColor(pal.textPrimary);
        selectedDayHeaderTv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        selectedDayHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        dayCard.addView(selectedDayHeaderTv);

        dayEventsBox = new LinearLayout(this);
        dayEventsBox.setOrientation(LinearLayout.VERTICAL);
        dayEventsBox.setPadding(0, dp(8), 0, 0);
        dayCard.addView(dayEventsBox);
        body.addView(dayCard, cardLp);

        // Upcoming 30-Day Agenda Card
        LinearLayout agendaCard = new LinearLayout(this);
        agendaCard.setOrientation(LinearLayout.VERTICAL);
        agendaCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        agendaCard.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 16));

        TextView agHdr = new TextView(this);
        agHdr.setText("Upcoming 30-Day Agenda");
        agHdr.setTextColor(pal.textPrimary);
        agHdr.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        agHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        agendaCard.addView(agHdr);

        upcomingAgendaBox = new LinearLayout(this);
        upcomingAgendaBox.setOrientation(LinearLayout.VERTICAL);
        upcomingAgendaBox.setPadding(0, dp(8), 0, 0);
        agendaCard.addView(upcomingAgendaBox);
        body.addView(agendaCard, cardLp);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        renderCalendar();
    }

    private void stepMonth(int delta) {
        Calendar c = Calendar.getInstance();
        c.set(viewYear, viewMonth, 1);
        c.add(Calendar.MONTH, delta);
        viewYear = c.get(Calendar.YEAR);
        viewMonth = c.get(Calendar.MONTH);
        int maxDay = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (selectedDay > maxDay) selectedDay = maxDay;
        renderCalendar();
    }

    private void renderCalendar() {
        Arc7Skin.Palette pal = Arc7Skin.current(this);
        Calendar cal = Calendar.getInstance();
        cal.set(viewYear, viewMonth, 1);
        monthHeaderTv.setText(new SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.getTime()));

        Calendar today = Calendar.getInstance();
        int todayY = today.get(Calendar.YEAR);
        int todayM = today.get(Calendar.MONTH);
        int todayD = today.get(Calendar.DAY_OF_MONTH);

        int firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0 = Sun
        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        monthGridBox.removeAllViews();
        int cellCursor = 1 - firstDow;
        for (int week = 0; week < 6; week++) {
            if (cellCursor > daysInMonth) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(4);

            for (int col = 0; col < 7; col++, cellCursor++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(2), dp(6), dp(2), dp(6));

                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(52), 1f);
                clp.leftMargin = dp(2);
                clp.rightMargin = dp(2);

                if (cellCursor >= 1 && cellCursor <= daysInMonth) {
                    final int dayNum = cellCursor;
                    boolean isSel = (dayNum == selectedDay);
                    boolean isToday = (viewYear == todayY && viewMonth == todayM && dayNum == todayD);

                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(10));
                    if (isSel) {
                        bg.setColor(pal.accentDark);
                        bg.setStroke(dp(2), pal.accent);
                    } else if (isToday) {
                        bg.setColor(pal.surfaceElevated);
                        bg.setStroke(dp(2), pal.accentSecondary);
                    } else {
                        bg.setColor(pal.surfaceElevated);
                    }
                    cell.setBackgroundDrawable(bg);

                    TextView num = new TextView(this);
                    num.setText(String.valueOf(dayNum));
                    num.setTextColor(isSel ? Color.WHITE : pal.textPrimary);
                    num.setTypeface(Typeface.SANS_SERIF, (isSel || isToday) ? Typeface.BOLD : Typeface.NORMAL);
                    num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    cell.addView(num);

                    List<Arc7CalendarStore.CalEvent> evs =
                            Arc7CalendarStore.eventsForDay(this, viewYear, viewMonth, dayNum);
                    if (!evs.isEmpty()) {
                        LinearLayout dots = new LinearLayout(this);
                        dots.setOrientation(LinearLayout.HORIZONTAL);
                        dots.setGravity(Gravity.CENTER);
                        dots.setPadding(0, dp(3), 0, 0);
                        for (int k = 0; k < Math.min(3, evs.size()); k++) {
                            View dot = new View(this);
                            GradientDrawable dg = new GradientDrawable();
                            dg.setShape(GradientDrawable.OVAL);
                            dg.setColor(evs.get(k).accentColor());
                            dot.setBackgroundDrawable(dg);
                            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
                            dlp.leftMargin = dp(2);
                            dlp.rightMargin = dp(2);
                            dots.addView(dot, dlp);
                        }
                        cell.addView(dots);
                    }

                    cell.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            selectedDay = dayNum;
                            renderCalendar();
                        }
                    });
                }
                row.addView(cell, clp);
            }
            monthGridBox.addView(row, rowLp);
        }

        // Render Selected Day Events
        Calendar selCal = Calendar.getInstance();
        selCal.set(viewYear, viewMonth, selectedDay);
        selectedDayHeaderTv.setText(new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(selCal.getTime()));
        dayEventsBox.removeAllViews();

        List<Arc7CalendarStore.CalEvent> dayList =
                Arc7CalendarStore.eventsForDay(this, viewYear, viewMonth, selectedDay);
        if (dayList.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No events scheduled on this day. Tap '+ Event' in the top bar to schedule an appointment, class, or reminder.");
            none.setTextColor(pal.textMuted);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            dayEventsBox.addView(none);
        } else {
            for (int i = 0; i < dayList.size(); i++) {
                dayEventsBox.addView(buildEventCardRow(pal, dayList.get(i), null));
            }
        }

        // Render Upcoming 30-Day Agenda
        upcomingAgendaBox.removeAllViews();
        Calendar scan = Calendar.getInstance();
        int count = 0;
        SimpleDateFormat shortDate = new SimpleDateFormat("EEE, MMM d", Locale.US);
        for (int d = 0; d < 30 && count < 12; d++) {
            List<Arc7CalendarStore.CalEvent> evs = Arc7CalendarStore.eventsForDay(
                    this, scan.get(Calendar.YEAR), scan.get(Calendar.MONTH), scan.get(Calendar.DAY_OF_MONTH));
            for (int i = 0; i < evs.size() && count < 12; i++) {
                upcomingAgendaBox.addView(buildEventCardRow(pal, evs.get(i), shortDate.format(scan.getTime())));
                count++;
            }
            scan.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private View buildEventCardRow(Arc7Skin.Palette pal, final Arc7CalendarStore.CalEvent e, String datePrefix) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(9), dp(10), dp(9));
        card.setBackgroundDrawable(Arc7Skin.cardDrawable(this, pal, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        card.setLayoutParams(lp);

        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(e.accentColor());
        bg.setCornerRadius(dp(3));
        bar.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(5), dp(38));
        blp.rightMargin = dp(10);
        card.addView(bar, blp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(e.title);
        t.setTextColor(pal.textPrimary);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.addView(t);

        String sub = (datePrefix != null ? datePrefix + "  •  " : "")
                + Arc7CalendarStore.formatTimeRange(this, e)
                + (e.location.length() > 0 ? "  •  " + e.location : "");
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextColor(pal.textMuted);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.addView(st);

        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!e.holidayBuiltIn) {
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEventDialog(e);
                }
            });
        }
        return card;
    }

    private void showEventDialog(final Arc7CalendarStore.CalEvent existing) {
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(10), dp(16), dp(6));
        sv.addView(form);

        final EditText titleEt = new EditText(this);
        titleEt.setHint("Event title (e.g. Dentist, Book Club, Flight)");
        titleEt.setText(existing != null ? existing.title : "");
        titleEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(titleEt);

        final EditText locEt = new EditText(this);
        locEt.setHint("Location or room (optional)");
        locEt.setText(existing != null ? existing.location : "");
        form.addView(locEt);

        final CheckBox allDayCb = new CheckBox(this);
        allDayCb.setText("All-day event");
        allDayCb.setChecked(existing != null && existing.allDay);
        form.addView(allDayCb);

        final TimePicker tp = new TimePicker(this);
        tp.setIs24HourView(!Arc7ClockStore.is12HourMode(this));
        tp.setCurrentHour(existing != null ? existing.startHour : 9);
        tp.setCurrentMinute(existing != null ? existing.startMin : 0);
        form.addView(tp);

        final int[] catIdx = new int[]{existing != null ? existing.categoryIdx : 0};
        final Button catBtn = Arc7Skin.actionButton(this,
                "Category: " + Arc7CalendarStore.CATEGORY_NAMES[catIdx[0]],
                Arc7CalendarStore.CATEGORY_COLORS[catIdx[0]]);
        catBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                catIdx[0] = (catIdx[0] + 1) % Arc7CalendarStore.CATEGORY_NAMES.length;
                catBtn.setText("Category: " + Arc7CalendarStore.CATEGORY_NAMES[catIdx[0]]);
                catBtn.setBackgroundDrawable(Arc7Skin.buttonDrawable(
                        Arc7CalendarActivity.this, Arc7CalendarStore.CATEGORY_COLORS[catIdx[0]], 10));
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(8);
        form.addView(catBtn, mlp);

        final int[] repIdx = new int[]{existing != null ? existing.repeatMode : 0};
        final Button repBtn = Arc7Skin.actionButton(this,
                "Repeat: " + Arc7CalendarStore.REPEAT_LABELS[repIdx[0]], 0xFF334155);
        repBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repIdx[0] = (repIdx[0] + 1) % Arc7CalendarStore.REPEAT_LABELS.length;
                repBtn.setText("Repeat: " + Arc7CalendarStore.REPEAT_LABELS[repIdx[0]]);
            }
        });
        form.addView(repBtn, mlp);

        final int[] remChoice = new int[]{2}; // default 10 min before
        if (existing != null) {
            for (int i = 0; i < Arc7CalendarStore.REMINDER_MINS.length; i++) {
                if (Arc7CalendarStore.REMINDER_MINS[i] == existing.reminderMinutesBefore) remChoice[0] = i;
            }
        }
        final Button remBtn = Arc7Skin.actionButton(this,
                "Reminder: " + Arc7CalendarStore.REMINDER_LABELS[remChoice[0]], 0xFF0F766E);
        remBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remChoice[0] = (remChoice[0] + 1) % Arc7CalendarStore.REMINDER_LABELS.length;
                remBtn.setText("Reminder: " + Arc7CalendarStore.REMINDER_LABELS[remChoice[0]]);
            }
        });
        form.addView(remBtn, mlp);

        final EditText notesEt = new EditText(this);
        notesEt.setHint("Notes / agenda details (optional)");
        notesEt.setText(existing != null ? existing.notes : "");
        form.addView(notesEt);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null
                        ? String.format(Locale.US, "New Event (%d-%02d-%02d)", viewYear, viewMonth + 1, selectedDay)
                        : "Edit Event")
                .setView(sv)
                .setPositiveButton("Save Event", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        tp.clearFocus();
                        String t = titleEt.getText().toString().trim();
                        if (t.length() == 0) t = "Event";
                        Arc7CalendarStore.CalEvent ev = existing != null ? existing : new Arc7CalendarStore.CalEvent();
                        if (existing == null) {
                            ev.year = viewYear;
                            ev.month = viewMonth;
                            ev.day = selectedDay;
                        }
                        ev.title = t;
                        ev.location = locEt.getText().toString().trim();
                        ev.notes = notesEt.getText().toString().trim();
                        ev.allDay = allDayCb.isChecked();
                        ev.startHour = tp.getCurrentHour();
                        ev.startMin = tp.getCurrentMinute();
                        ev.categoryIdx = catIdx[0];
                        ev.repeatMode = repIdx[0];
                        ev.reminderMinutesBefore = Arc7CalendarStore.REMINDER_MINS[remChoice[0]];
                        Arc7CalendarStore.upsertEvent(Arc7CalendarActivity.this, ev);
                        renderCalendar();
                    }
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            b.setNeutralButton("Delete Event", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    Arc7CalendarStore.deleteEvent(Arc7CalendarActivity.this, existing.id);
                    renderCalendar();
                }
            });
        }
        b.show();
    }

    private void showIcsMenuDialog() {
        String[] opts = new String[]{
                "Export Calendar to Arc7Freedom/Calendar/Arc7-Calendar-Backup.ics",
                "Import Events from .ics File in Download/ or Arc7Freedom/Calendar/"
        };
        new AlertDialog.Builder(this)
                .setTitle("iCalendar (.ICS) Sync")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            try {
                                File out = Arc7CalendarStore.exportToIcs(Arc7CalendarActivity.this);
                                Toast.makeText(Arc7CalendarActivity.this,
                                        "Exported to " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
                            } catch (Throwable t) {
                                Toast.makeText(Arc7CalendarActivity.this,
                                        "Export failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        } else {
                            tryImportFirstIcs();
                        }
                    }
                })
                .show();
    }

    private void tryImportFirstIcs() {
        File[] candidates = new File[]{
                new File(android.os.Environment.getExternalStorageDirectory(), "Arc7Freedom/Calendar/Arc7-Calendar-Backup.ics"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Download/calendar.ics")
        };
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i].isFile()) {
                try {
                    int n = Arc7CalendarStore.importFromIcs(this, candidates[i]);
                    renderCalendar();
                    Toast.makeText(this, "Imported " + n + " events from " + candidates[i].getName(), Toast.LENGTH_LONG).show();
                    return;
                } catch (Throwable t) {
                    Toast.makeText(this, "Import error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        Toast.makeText(this, "Place any .ics file at Download/calendar.ics to import!", Toast.LENGTH_LONG).show();
    }
}
