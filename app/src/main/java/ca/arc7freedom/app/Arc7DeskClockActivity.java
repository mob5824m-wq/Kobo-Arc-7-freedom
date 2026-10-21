package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Upgrade #10: Standalone `Arc7 Desk Clock` — Smart Desk Display, Live TLS 1.3 Local Weather
 * Station & MicroSD Digital Photo Frame (`1024x600`, Anti-Burn-In Shift, Bedside Nightstand Mode).
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
    private LinearLayout topControls;

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            txtClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(now));
            txtDate.setText(new SimpleDateFormat("EEEE, MMMM d, yyyy  •  ss's'", Locale.US).format(now));

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
            ui.postDelayed(this, 1800000L); // Refresh every 30 minutes
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
        clockCard.setPadding(dp(28), dp(18), dp(28), dp(18));

        txtClock = new TextView(this);
        txtClock.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        txtClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 96);
        clockCard.addView(txtClock);

        txtDate = new TextView(this);
        txtDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        clockCard.addView(txtDate);

        txtWeather = new TextView(this);
        txtWeather.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        txtWeather.setPadding(0, dp(10), 0, dp(6));
        txtWeather.setText("Fetching live weather via TLS 1.3...");
        clockCard.addView(txtWeather);

        txtBattery = new TextView(this);
        txtBattery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clockCard.addView(txtBattery);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        frame.addView(clockCard, centerLp);

        topControls = new LinearLayout(this);
        topControls.setOrientation(LinearLayout.HORIZONTAL);
        topControls.setGravity(Gravity.RIGHT);
        topControls.setPadding(dp(12), dp(10), dp(12), dp(10));

        topControls.addView(miniBtn("Set Weather City", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) { promptCityDialog(); }
        }));
        topControls.addView(miniBtn("Nightstand Amber/Black", "#7C3AED", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nightRedMode = !nightRedMode;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NIGHT_RED, nightRedMode).commit();
                applyVisualMode();
            }
        }));
        topControls.addView(miniBtn("Reload Photos", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { scanPhotosAsync(); }
        }));
        topControls.addView(miniBtn("Exit Stand", "#475569", new View.OnClickListener() {
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
        applyVisualMode();
        scanPhotosAsync();
        ui.post(clockTicker);
        ui.postDelayed(slideshowTicker, 5000L);
        ui.post(weatherTicker);
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
                                + tempC + " °C (feels " + feels + " °C)  •  " + desc;
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
        ui.removeCallbacks(clockTicker);
        ui.removeCallbacks(slideshowTicker);
        ui.removeCallbacks(weatherTicker);
        super.onDestroy();
    }
}
