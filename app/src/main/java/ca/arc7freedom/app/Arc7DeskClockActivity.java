package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fullscreen `Clock Standby` Display (`v2.4.0`):
 *   - Split-Flap Bedside & Desk Clock (`12H AM/PM` or `24H`), Anti-Burn-In Pixel Shift & Amber Night Mode
 *   - Live Now-Playing Media Panel for ANY audio playing on the tablet (`YouTube Music`, `YouTube`,
 *     `Music`, `Radio & Podcasts`, `Voice Recorder`, `VLC`, or any external Android media stream!)
 *   - Live TLS 1.3 Local Weather Station & MicroSD Digital Photo Frame!
 */
public class Arc7DeskClockActivity extends Activity {

    private static final String PREFS = "arc7_desk_clock";
    private static final String KEY_CITY = "weather_city";
    private static final String KEY_NIGHT_RED = "nightstand_amber_mode";

    private RootShell shell;
    private NetworkCertManager certMgr;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<File> photos = new ArrayList<File>();
    private int photoIdx = 0;
    private boolean nightRedMode = false;

    private ImageView bgPhotoView;
    private LinearLayout clockCard;
    private TextView txtClock;
    private TextView txtDate;
    private TextView txtWeather;
    private TextView txtBattery;

    // Live Standby Now-Playing Panel (`v2.4.0`)
    private LinearLayout nowPlayingBox;
    private ImageView npArtIv;
    private TextView npHeaderTv;
    private TextView npTitleTv;
    private TextView npArtistTv;
    private ProgressBar npProgressBar;
    private Button npToggleBtn;

    private LinearLayout topControls;

    private final BroadcastReceiver mediaUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStandbyNowPlaying();
        }
    };

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            txtClock.setText(Arc7ClockStore.formatClockNow(Arc7DeskClockActivity.this, now, false));
            txtDate.setText(new SimpleDateFormat("EEEE, MMMM d, yyyy  \u2022  ss's'", Locale.US).format(now));

            /* Subtle pixel-shift every minute to protect aging IPS panel against image retention */
            int min = (int) ((now.getTime() / 60000L) % 9L);
            int shiftX = ((min % 3) - 1) * dp(10);
            int shiftY = ((min / 3) - 1) * dp(8);
            clockCard.setTranslationX(shiftX);
            clockCard.setTranslationY(shiftY);

            Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                int lv = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                int pct = sc > 0 && lv >= 0 ? (lv * 100 / sc) : 0;
                txtBattery.setText("Battery: " + pct + "%" + (chg ? " (Charging / Docked)" : ""));
            }

            refreshStandbyNowPlaying();
            ui.postDelayed(this, 1000L);
        }
    };

    private final Runnable slideshowTicker = new Runnable() {
        @Override
        public void run() {
            if (!nightRedMode && !photos.isEmpty()) {
                photoIdx = (photoIdx + 1) % photos.size();
                showPhotoSafe(photos.get(photoIdx));
            }
            ui.postDelayed(this, 20000L);
        }
    };

    private final Runnable weatherTicker = new Runnable() {
        @Override
        public void run() {
            refreshWeatherViaTls13Async();
            ui.postDelayed(this, 1800000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                             WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN |
                             WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Arc7AudioService.ensureWatcherRunning(this);
        shell = new RootShell(this);
        certMgr = new NetworkCertManager(this, shell);
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        nightRedMode = sp.getBoolean(KEY_NIGHT_RED, false);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        bgPhotoView = new ImageView(this);
        bgPhotoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgPhotoView.setAlpha(0.45f);
        frame.addView(bgPhotoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clockCard = new LinearLayout(this);
        clockCard.setOrientation(LinearLayout.VERTICAL);
        clockCard.setGravity(Gravity.CENTER);
        clockCard.setPadding(dp(26), dp(14), dp(26), dp(16));

        txtClock = new TextView(this);
        txtClock.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        txtClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 84);
        clockCard.addView(txtClock);

        txtDate = new TextView(this);
        txtDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        clockCard.addView(txtDate);

        txtWeather = new TextView(this);
        txtWeather.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        txtWeather.setPadding(0, dp(8), 0, dp(4));
        txtWeather.setText("Fetching live weather via TLS 1.3...");
        clockCard.addView(txtWeather);

        txtBattery = new TextView(this);
        txtBattery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clockCard.addView(txtBattery);

        // Live Standby Now-Playing Audio Display Card (`v2.4.0`)
        nowPlayingBox = new LinearLayout(this);
        nowPlayingBox.setOrientation(LinearLayout.VERTICAL);
        nowPlayingBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams npBoxLp = new LinearLayout.LayoutParams(dp(490), ViewGroup.LayoutParams.WRAP_CONTENT);
        npBoxLp.topMargin = dp(12);
        nowPlayingBox.setVisibility(View.GONE);

        LinearLayout npTopRow = new LinearLayout(this);
        npTopRow.setOrientation(LinearLayout.HORIZONTAL);
        npTopRow.setGravity(Gravity.CENTER_VERTICAL);

        npArtIv = new ImageView(this);
        npArtIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArtIv.setImageResource(R.drawable.ic_audio);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(54), dp(54));
        artLp.rightMargin = dp(12);
        npTopRow.addView(npArtIv, artLp);

        LinearLayout npTextCol = new LinearLayout(this);
        npTextCol.setOrientation(LinearLayout.VERTICAL);

        npHeaderTv = new TextView(this);
        npHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        npHeaderTv.setTypeface(Typeface.DEFAULT_BOLD);
        npHeaderTv.setSingleLine(true);
        npTextCol.addView(npHeaderTv);

        npTitleTv = new TextView(this);
        npTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        npTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        npTitleTv.setSingleLine(true);
        npTextCol.addView(npTitleTv);

        npArtistTv = new TextView(this);
        npArtistTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        npArtistTv.setSingleLine(true);
        npTextCol.addView(npArtistTv);

        npTopRow.addView(npTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nowPlayingBox.addView(npTopRow);

        npProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        npProgressBar.setMax(1000);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pblp.topMargin = dp(7);
        pblp.bottomMargin = dp(7);
        nowPlayingBox.addView(npProgressBar, pblp);

        LinearLayout npControls = new LinearLayout(this);
        npControls.setOrientation(LinearLayout.HORIZONTAL);
        npControls.setGravity(Gravity.CENTER);

        npControls.addView(miniBtn("\u23EE Prev", "#1E293B", new View.OnClickListener() {
            @Override public void onClick(View v) {
                NowPlayingManager.dispatchTransportAction(Arc7DeskClockActivity.this, Arc7AudioService.ACTION_PREV);
            }
        }));
        npToggleBtn = miniBtn("\u23F8 Pause", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) {
                NowPlayingManager.dispatchTransportAction(Arc7DeskClockActivity.this, Arc7AudioService.ACTION_TOGGLE);
            }
        });
        npControls.addView(npToggleBtn);
        npControls.addView(miniBtn("Next \u23ED", "#1E293B", new View.OnClickListener() {
            @Override public void onClick(View v) {
                NowPlayingManager.dispatchTransportAction(Arc7DeskClockActivity.this, Arc7AudioService.ACTION_NEXT);
            }
        }));
        npControls.addView(miniBtn("\uD83D\uDD09 Vol-", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { adjustMusicVolume(-1); }
        }));
        npControls.addView(miniBtn("\uD83D\uDD0A Vol+", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { adjustMusicVolume(1); }
        }));
        npControls.addView(miniBtn("Open App", "#059669", new View.OnClickListener() {
            @Override public void onClick(View v) {
                NowPlayingManager.Snapshot s = NowPlayingManager.getSnapshot(Arc7DeskClockActivity.this);
                try {
                    startActivity(NowPlayingManager.buildOpenPlayerIntent(Arc7DeskClockActivity.this, s));
                } catch (Throwable ignored) {}
            }
        }));
        nowPlayingBox.addView(npControls);
        clockCard.addView(nowPlayingBox, npBoxLp);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        frame.addView(clockCard, centerLp);

        topControls = new LinearLayout(this);
        topControls.setOrientation(LinearLayout.HORIZONTAL);
        topControls.setGravity(Gravity.RIGHT);
        topControls.setPadding(dp(12), dp(10), dp(12), dp(10));

        topControls.addView(miniBtn("12H / 24H", "#059669", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7ClockStore.set12HourMode(Arc7DeskClockActivity.this,
                        !Arc7ClockStore.is12HourMode(Arc7DeskClockActivity.this));
                txtClock.setText(Arc7ClockStore.formatClockNow(Arc7DeskClockActivity.this, new Date(), false));
            }
        }));
        topControls.addView(miniBtn("Set Weather City", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) { promptCityDialog(); }
        }));
        topControls.addView(miniBtn("Nightstand Amber/Black", "#7C3AED", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nightRedMode = !nightRedMode;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NIGHT_RED, nightRedMode).commit();
                applyVisualMode();
                refreshStandbyNowPlaying();
            }
        }));
        topControls.addView(miniBtn("Auto-Dock Charge", "#D97706", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences sp2 = getSharedPreferences(PREFS, MODE_PRIVATE);
                boolean next = !sp2.getBoolean("auto_dock_on_charge", false);
                sp2.edit().putBoolean("auto_dock_on_charge", next).commit();
                android.widget.Toast.makeText(Arc7DeskClockActivity.this,
                        "Auto-Launch Clock Standby on USB Power: " + (next ? "ON" : "OFF"),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
        topControls.addView(miniBtn("Sleep Fade 30m", "#0D9488", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7AudioService.startSleepTimer(Arc7DeskClockActivity.this, 30);
                android.widget.Toast.makeText(Arc7DeskClockActivity.this,
                        "Audio Sleep Timer armed: 30 min + smooth 30s fade-out!",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
        topControls.addView(miniBtn("Reload Photos", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { scanPhotosAsync(); }
        }));
        topControls.addView(miniBtn("Exit Standby", "#475569", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        frame.addView(topControls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                topControls.setVisibility(topControls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        setContentView(frame);
        Arc7UiAnimator.attachActivityAnimations(this);
        registerReceiver(mediaUpdateReceiver, new IntentFilter(NowPlayingManager.ACTION_NOW_PLAYING_CHANGED));
        applyVisualMode();
        refreshStandbyNowPlaying();
        scanPhotosAsync();
        ui.post(clockTicker);
        ui.postDelayed(slideshowTicker, 5000L);
        ui.post(weatherTicker);
    }

    private void adjustMusicVolume(int direction) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void refreshStandbyNowPlaying() {
        if (nowPlayingBox == null) return;
        NowPlayingManager.Snapshot s = NowPlayingManager.getSnapshot(this);
        if (!s.active || s.title == null || s.title.length() == 0) {
            nowPlayingBox.setVisibility(View.GONE);
            return;
        }
        nowPlayingBox.setVisibility(View.VISIBLE);

        GradientDrawable npBg = new GradientDrawable();
        npBg.setCornerRadius(dp(14));
        if (nightRedMode) {
            npBg.setColor(0xEE180A00);
            npBg.setStroke(dp(1), 0xFF78350F);
            npHeaderTv.setTextColor(0xFFF59E0B);
            npTitleTv.setTextColor(0xFFFF8C24);
            npArtistTv.setTextColor(0xFFD97706);
        } else {
            npBg.setColor(0xDD0B1324);
            npBg.setStroke(dp(1), 0xFF1E3A8A);
            npHeaderTv.setTextColor(0xFF38BDF8);
            npTitleTv.setTextColor(Color.WHITE);
            npArtistTv.setTextColor(0xFFCBD5E1);
        }
        nowPlayingBox.setBackgroundDrawable(npBg);

        long livePos = s.livePositionMs();
        String timeStr = s.durationMs > 0L
                ? (NowPlayingManager.formatDurationMs(livePos) + " / " + NowPlayingManager.formatDurationMs(s.durationMs))
                : (s.playing ? "LIVE" : "PAUSED");

        npHeaderTv.setText((s.playing ? "\u266B NOW PLAYING  \u2022  " : "\u23F8 PAUSED  \u2022  ")
                + s.sourceApp.toUpperCase(Locale.US) + "  \u2022  " + timeStr);
        npTitleTv.setText(s.title);
        npArtistTv.setText(s.artist.length() > 0 ? s.artist : s.sourceApp);
        npToggleBtn.setText(s.playing ? "\u23F8 Pause" : "\u25B6 Play");

        int permille = s.durationMs > 0L
                ? (int) Math.min(1000L, (livePos * 1000L) / s.durationMs)
                : (s.playing ? 1000 : 0);
        npProgressBar.setProgress(permille);

        if (s.artwork != null) {
            npArtIv.setImageBitmap(s.artwork);
        } else {
            npArtIv.setImageResource(R.drawable.ic_audio);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyVisualMode() {
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(16));
        if (nightRedMode) {
            bgPhotoView.setImageBitmap(null);
            cardBg.setColor(Color.TRANSPARENT);
            txtClock.setTextColor(Color.parseColor("#FF6B00"));
            txtDate.setTextColor(Color.parseColor("#B45309"));
            txtWeather.setTextColor(Color.parseColor("#D97706"));
            txtBattery.setTextColor(Color.parseColor("#92400E"));
        } else {
            cardBg.setColor(Color.argb(150, 15, 23, 42));
            txtClock.setTextColor(Color.WHITE);
            txtDate.setTextColor(Color.parseColor("#E2E8F0"));
            txtWeather.setTextColor(Color.parseColor("#38BDF8"));
            txtBattery.setTextColor(Color.parseColor("#94A3B8"));
            if (!photos.isEmpty()) showPhotoSafe(photos.get(photoIdx % photos.size()));
        }
        clockCard.setBackgroundDrawable(cardBg);
    }

    private void promptCityDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("e.g. Kitchener  or  Toronto  (leave blank for auto-IP)");
        input.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CITY, ""));
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Live Weather Location (Zero API Key — TLS 1.3)")
                .setView(input)
                .setPositiveButton("Save & Refresh", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_CITY, input.getText().toString().trim()).commit();
                        refreshWeatherViaTls13Async();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshWeatherViaTls13Async() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String city = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CITY, "").trim();
                    String pathCity = city.isEmpty() ? "" : java.net.URLEncoder.encode(city, "UTF-8");
                    String url = "https://wttr.in/" + pathCity + "?format=j1";
                    File bundle = certMgr.ensureCaBundleFile();
                    File outJson = new File(getCacheDir(), "weather_j1.json");
                    RootShell.ExecResult r = shell.execCommandArray(new String[]{
                        shell.getTlsEngineBinary().getAbsolutePath(),
                        "fetch", bundle.getAbsolutePath(), url, outJson.getAbsolutePath()
                    }, null);
                    if (r.exitCode == 0 && outJson.exists() && outJson.length() > 50) {
                        byte[] raw = new byte[(int) Math.min(65536L, outJson.length())];
                        FileInputStream fis = new FileInputStream(outJson);
                        int n = fis.read(raw);
                        fis.close();
                        JSONObject root = new JSONObject(new String(raw, 0, n, "UTF-8"));
                        JSONObject cur = root.getJSONArray("current_condition").getJSONObject(0);
                        String tempC = cur.optString("temp_C", "?");
                        String feels = cur.optString("FeelsLikeC", tempC);
                        JSONArray descArr = cur.optJSONArray("weatherDesc");
                        String desc = (descArr != null && descArr.length() > 0)
                                ? descArr.getJSONObject(0).optString("value", "") : "";
                        String areaName = city;
                        JSONArray nearest = root.optJSONArray("nearest_area");
                        if (areaName.isEmpty() && nearest != null && nearest.length() > 0) {
                            JSONArray an = nearest.getJSONObject(0).optJSONArray("areaName");
                            if (an != null && an.length() > 0) areaName = an.getJSONObject(0).optString("value", "");
                        }
                        final String summary = (areaName.isEmpty() ? "" : (areaName + ": "))
                                + tempC + " \u00B0C (feels " + feels + " \u00B0C)  \u2022  " + desc;
                        ui.post(new Runnable() {
                            @Override public void run() { txtWeather.setText(summary); }
                        });
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void scanPhotosAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<File> found = new ArrayList<File>();
                String sd = TabletOptimizerManager.findMicroSdRoot();
                String[] dirs = {
                    sd != null ? sd + "/Pictures" : null,
                    sd != null ? sd + "/DCIM" : null,
                    sd,
                    Environment.getExternalStorageDirectory() + "/Pictures",
                    Environment.getExternalStorageDirectory() + "/DCIM"
                };
                for (String d : dirs) {
                    if (d == null) continue;
                    collectImages(new File(d), found, 0);
                    if (found.size() >= 150) break;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        photos.clear();
                        photos.addAll(found);
                        if (!nightRedMode && !photos.isEmpty()) showPhotoSafe(photos.get(0));
                    }
                });
            }
        }).start();
    }

    private static void collectImages(File dir, List<File> out, int depth) {
        if (depth > 2 || dir == null || !dir.isDirectory() || out.size() >= 150) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collectImages(f, out, depth + 1);
            else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) out.add(f);
            }
        }
    }

    private void showPhotoSafe(File imgFile) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imgFile.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 800) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), opts);
            if (bmp != null) bgPhotoView.setImageBitmap(bmp);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private Button miniBtn(String label, String hex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(Color.parseColor(hex));
        b.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(mediaUpdateReceiver); } catch (Throwable ignored) {}
        ui.removeCallbacks(clockTicker);
        ui.removeCallbacks(slideshowTicker);
        ui.removeCallbacks(weatherTicker);
        super.onDestroy();
    }
}
