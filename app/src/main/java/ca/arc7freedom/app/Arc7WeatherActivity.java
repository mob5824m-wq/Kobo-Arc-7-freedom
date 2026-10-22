package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Standalone Live `Open-Meteo` Weather Station (`Arc7 Weather`, `v2.2.0`):
 *   - Current temperature, feels-like, WMO condition icon, wind, humidity & UV index
 *   - 24-hour horizontal hourly strip + 7-day daily High/Low & precipitation probability list
 *   - Global city geocoding search over TLS 1.3 (zero API key!), °C / °F toggle, & offline cache!
 */
public class Arc7WeatherActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell shell;
    private NetworkCertManager certMgr;
    private LinearLayout contentBox;
    private Arc7Skin.Palette pal;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell = new RootShell(this);
        certMgr = new NetworkCertManager(this, shell);
        pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button cityBtn = Arc7Skin.actionButton(this, "Change City", 0xFF0284C7);
        cityBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptCitySearch(); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.rightMargin = dp(6);
        actions.addView(cityBtn, lp1);

        Button unitBtn = Arc7Skin.actionButton(this, "°C / °F", 0xFF334155);
        unitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Arc7WeatherStore.setFahrenheit(Arc7WeatherActivity.this,
                        !Arc7WeatherStore.isFahrenheit(Arc7WeatherActivity.this));
                Arc7WeatherWidgetProvider.refreshAll(Arc7WeatherActivity.this);
                renderSnapshot(Arc7WeatherStore.loadCached(Arc7WeatherActivity.this));
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.rightMargin = dp(6);
        actions.addView(unitBtn, lp2);

        Button refreshBtn = Arc7Skin.actionButton(this, "↻ Refresh", 0xFF059669);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchLiveAsync(null); }
        });
        actions.addView(refreshBtn);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_weather,
                "Arc7 Weather",
                "Open-Meteo TLS 1.3 Live Forecast • Hourly & 7-Day Outlook • Home Screen Widget",
                actions));

        ScrollView sv = new ScrollView(this);
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(dp(16), dp(14), dp(16), dp(22));
        sv.addView(contentBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        renderSnapshot(Arc7WeatherStore.loadCached(this));
        fetchLiveAsync(null);
    }

    private void promptCitySearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("e.g. Kitchener, Toronto, Montreal, London, Tokyo");
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Search Any City Worldwide (Open-Meteo TLS 1.3)")
                .setView(input)
                .setPositiveButton("Search & Load Forecast", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String c = input.getText().toString().trim();
                        if (!c.isEmpty()) fetchLiveAsync(c);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetchLiveAsync(final String newCity) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Arc7WeatherStore.ForecastSnapshot snap =
                            Arc7WeatherStore.refreshCityForecast(Arc7WeatherActivity.this, shell, certMgr, newCity);
                    ui.post(new Runnable() {
                        @Override public void run() { renderSnapshot(snap); }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Arc7WeatherActivity.this,
                                    "Showing cached forecast (" + t.getMessage() + ")", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void renderSnapshot(Arc7WeatherStore.ForecastSnapshot s) {
        contentBox.removeAllViews();
        boolean fahr = Arc7WeatherStore.isFahrenheit(this);

        // Hero Card
        LinearLayout hero = Arc7Skin.card(this, pal);
        TextView cityTv = new TextView(this);
        cityTv.setText(s.cityLabel);
        cityTv.setTextColor(pal.accent);
        cityTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cityTv.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(cityTv);

        TextView bigTemp = new TextView(this);
        bigTemp.setText(Arc7WeatherStore.formatTemp(s.tempC, fahr) + "   "
                + Arc7WeatherStore.wmoDescription(s.weatherCode));
        bigTemp.setTextColor(pal.textPrimary);
        bigTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        bigTemp.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        bigTemp.setPadding(0, dp(4), 0, dp(4));
        hero.addView(bigTemp);

        TextView meta = new TextView(this);
        meta.setText("Feels Like: " + Arc7WeatherStore.formatTemp(s.feelsLikeC, fahr)
                + "   •   Wind: " + Math.round(s.windKmh) + " km/h"
                + "   •   Humidity: " + s.humidityPct + "%"
                + "   •   Max UV Index: " + String.format("%.1f", s.uvMax));
        meta.setTextColor(pal.textMuted);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        hero.addView(meta);
        contentBox.addView(hero, bottomSpace(14));

        // 24-Hour Strip
        if (!s.hourly.isEmpty()) {
            LinearLayout hCard = Arc7Skin.card(this, pal);
            TextView hh = new TextView(this);
            hh.setText("NEXT 24 HOURS");
            hh.setTextColor(pal.accent);
            hh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hh.setTypeface(Typeface.DEFAULT_BOLD);
            hh.setPadding(0, 0, 0, dp(8));
            hCard.addView(hh);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < s.hourly.size(); i++) {
                Arc7WeatherStore.HourlyEntry he = s.hourly.get(i);
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setBackgroundDrawable(Arc7Skin.elevatedDrawable(this, pal, 10));
                cell.setPadding(dp(12), dp(8), dp(12), dp(8));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(8);
                cell.setLayoutParams(clp);

                TextView t1 = new TextView(this);
                t1.setText(he.hourLabel);
                t1.setTextColor(pal.textMuted);
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                cell.addView(t1);

                TextView t2 = new TextView(this);
                t2.setText(Arc7WeatherStore.formatTemp(he.tempC, fahr));
                t2.setTextColor(pal.textPrimary);
                t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t2.setTypeface(Typeface.DEFAULT_BOLD);
                t2.setPadding(0, dp(4), 0, dp(2));
                cell.addView(t2);

                TextView t3 = new TextView(this);
                t3.setText(Arc7WeatherStore.wmoDescription(he.weatherCode));
                t3.setTextColor(Color.LTGRAY);
                t3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                cell.addView(t3);
                strip.addView(cell);
            }
            hsv.addView(strip);
            hCard.addView(hsv);
            contentBox.addView(hCard, bottomSpace(14));
        }

        // 7-Day Outlook
        LinearLayout dCard = Arc7Skin.card(this, pal);
        TextView dh = new TextView(this);
        dh.setText("7-DAY FORECAST");
        dh.setTextColor(pal.accent);
        dh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dh.setTypeface(Typeface.DEFAULT_BOLD);
        dh.setPadding(0, 0, 0, dp(8));
        dCard.addView(dh);

        for (int i = 0; i < s.daily.size(); i++) {
            Arc7WeatherStore.DailyEntry d = s.daily.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView dt = new TextView(this);
            dt.setText(d.date);
            dt.setTextColor(pal.textPrimary);
            dt.setTypeface(Typeface.DEFAULT_BOLD);
            dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            row.addView(dt, new LinearLayout.LayoutParams(dp(115), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView cond = new TextView(this);
            cond.setText(Arc7WeatherStore.wmoDescription(d.weatherCode)
                    + (d.precipProb > 0 ? ("  •  💧 " + d.precipProb + "%") : ""));
            cond.setTextColor(pal.textMuted);
            cond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            row.addView(cond, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView hiLo = new TextView(this);
            hiLo.setText("H: " + Arc7WeatherStore.formatTemp(d.maxC, fahr)
                    + "   L: " + Arc7WeatherStore.formatTemp(d.minC, fahr));
            hiLo.setTextColor(pal.textPrimary);
            hiLo.setTypeface(Typeface.DEFAULT_BOLD);
            hiLo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            row.addView(hiLo);
            dCard.addView(row);
        }
        contentBox.addView(dCard);
    }

    private LinearLayout.LayoutParams bottomSpace(int dpVal) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(dpVal);
        return lp;
    }
}
