package ca.arc7freedom.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Upgrade #9 (UI): Standalone `Arc7 Audio` Background Music & Audiobook Player
 * (`PARTIAL_WAKE_LOCK` screen-off playback, automatic audiobook bookmark resume,
 * `-30s / +30s` skip, and 15 / 30 / 60-minute Bedtime Sleep Timer).
 */
public class Arc7AudioPlayerActivity extends Activity {

    private Arc7AudioService svc;
    private boolean bound = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView lblTrack;
    private TextView lblTime;
    private TextView lblSleep;
    private Button btnPlayPause;
    private SeekBar seekBar;
    private LinearLayout queueContainer;
    private boolean userDragging = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            svc = ((Arc7AudioService.LocalBinder) service).getService();
            bound = true;
            renderQueue();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            svc = null;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (bound && svc != null) {
                lblTrack.setText(svc.getCurrentTitle());
                btnPlayPause.setText(svc.isPlaying() ? "Pause" : "Play");
                int dur = Math.max(1, svc.getDurationMs());
                int pos = svc.getPositionMs();
                if (!userDragging) {
                    seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                }
                lblTime.setText(fmtTime(pos) + "  /  " + fmtTime(svc.getDurationMs()));
                long sleepSec = svc.remainingSleepSeconds();
                lblSleep.setText(sleepSec > 0
                        ? ("Sleep Timer Active: " + fmtTime((int) (sleepSec * 1000L)) + " remaining (plays with screen OFF!)")
                        : "Screen-Off Playback Ready • Auto-Bookmark Active");
            }
            ui.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent si = new Intent(this, Arc7AudioService.class);
        startService(si);
        bindService(si, conn, Context.BIND_AUTO_CREATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("Arc7 Audio — Screen-Off Music & Audiobook Player");
        appTitle.setTextColor(Color.parseColor("#38BDF8"));
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.addView(appTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(btn("Open Track / Folder", "#0284C7", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String sd = TabletOptimizerManager.findMicroSdRoot();
                File start = sd != null ? new File(sd) : new File("/sdcard");
                FilePickerDialog.show(Arc7AudioPlayerActivity.this, start,
                        new String[]{".mp3", ".m4a", ".aac", ".ogg", ".flac", ".wav", ".opus"},
                        "Pick Music or Audiobook File (Loads Whole Folder Queue)",
                        new FilePickerDialog.OnFilePicked() {
                            @Override
                            public void onPicked(File file) {
                                if (svc != null) {
                                    svc.loadTrackAndFolderQueue(file);
                                    renderQueue();
                                }
                            }
                        });
            }
        }));
        header.addView(btn("Close (Keeps Playing)", "#475569", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        root.addView(header);

        /* Now Playing Card */
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        setCardBg(card);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        clp.bottomMargin = dp(10);

        lblTrack = new TextView(this);
        lblTrack.setTextColor(Color.WHITE);
        lblTrack.setTypeface(Typeface.DEFAULT_BOLD);
        lblTrack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        lblTrack.setText("Tap 'Open Track / Folder' to play MP3 / M4A / OGG / FLAC / WAV");
        card.addView(lblTrack);

        lblSleep = new TextView(this);
        lblSleep.setTextColor(Color.parseColor("#94A3B8"));
        lblSleep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lblSleep.setPadding(0, dp(4), 0, dp(8));
        card.addView(lblSleep);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) { userDragging = true; }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userDragging = false;
                if (svc != null) svc.seekAbsoluteMs(sb.getProgress());
            }
        });
        card.addView(seekBar);

        lblTime = new TextView(this);
        lblTime.setTextColor(Color.parseColor("#38BDF8"));
        lblTime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblTime.setGravity(Gravity.CENTER_HORIZONTAL);
        lblTime.setPadding(0, dp(4), 0, dp(10));
        lblTime.setText("00:00  /  00:00");
        card.addView(lblTime);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.addView(btn("|< Prev", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) { svc.stepTrack(-1); renderQueue(); } }
        }));
        transport.addView(btn("-30s", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.seekRelativeMs(-30000); }
        }));
        btnPlayPause = btn("Play / Pause", "#16A34A", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.togglePlayPause(); }
        });
        transport.addView(btnPlayPause);
        transport.addView(btn("+30s", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.seekRelativeMs(30000); }
        }));
        transport.addView(btn("Next >|", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) { svc.stepTrack(1); renderQueue(); } }
        }));
        card.addView(transport);

        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        sleepRow.setGravity(Gravity.CENTER);
        sleepRow.setPadding(0, dp(10), 0, 0);
        sleepRow.addView(btn("Sleep: OFF", "#475569", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.setSleepTimerMinutes(0); }
        }));
        sleepRow.addView(btn("Sleep 15m", "#7C3AED", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.setSleepTimerMinutes(15); }
        }));
        sleepRow.addView(btn("Sleep 30m", "#7C3AED", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.setSleepTimerMinutes(30); }
        }));
        sleepRow.addView(btn("Sleep 60m", "#7C3AED", new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.setSleepTimerMinutes(60); }
        }));
        sleepRow.addView(btn("Stop Service", "#DC2626", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(Arc7AudioPlayerActivity.this, Arc7AudioService.class));
                finish();
            }
        }));
        card.addView(sleepRow);
        root.addView(card, clp);

        /* Folder Playlist Queue */
        ScrollView qScroll = new ScrollView(this);
        queueContainer = new LinearLayout(this);
        queueContainer.setOrientation(LinearLayout.VERTICAL);
        qScroll.addView(queueContainer);
        root.addView(qScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        ui.post(tick);

        if (getIntent() != null && getIntent().getData() != null &&
                "file".equalsIgnoreCase(getIntent().getData().getScheme())) {
            final File incoming = new File(getIntent().getData().getPath());
            if (incoming.exists()) {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (svc != null) {
                            svc.loadTrackAndFolderQueue(incoming);
                            renderQueue();
                        }
                    }
                }, 250L);
            }
        }
    }

    private void renderQueue() {
        if (svc == null) return;
        queueContainer.removeAllViews();
        List<File> list = svc.getPlaylistSnapshot();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            File f = list.get(i);
            Button item = btn((i + 1) + ".  " + f.getName(),
                    i == svc.getCurrentIndex() ? "#0284C7" : "#1E293B",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            svc.startTrackAtIndex(idx, true);
                            renderQueue();
                        }
                    });
            item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(5);
            item.setLayoutParams(lp);
            queueContainer.addView(item);
        }
    }

    @SuppressWarnings("deprecation")
    private void setCardBg(View v) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(10));
        gd.setColor(Color.parseColor("#1E293B"));
        gd.setStroke(dp(1), Color.parseColor("#334155"));
        v.setBackgroundDrawable(gd);
    }

    @SuppressWarnings("deprecation")
    private Button btn(String label, String hex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(Color.parseColor(hex));
        b.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private static String fmtTime(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%02d:%02d", m, s);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }
}
