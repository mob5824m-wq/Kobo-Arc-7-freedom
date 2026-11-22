package ca.arc7freedom.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Full native, independent Weather app for Android 4.0.3+: real data, explicit ages and no API key. */
public class Arc7WeatherActivity extends Arc7Activity {
    private static final String[] TABS={"Today","Hourly","14 days","Air & sun","Alerts","Radar"};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService weatherWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService searchWorker=Executors.newSingleThreadExecutor();
    private Future<?> weatherTask, searchTask;
    private Arc7WeatherTransport weatherTransport, searchTransport;
    private Arc7WeatherData.Place place;
    private Arc7WeatherData.Forecast forecast;
    private Arc7WeatherData.Air air;
    private Arc7WeatherData.Alerts alerts;
    private Arc7WeatherData.Units units;
    private Arc7Skin.Palette pal;
    private LinearLayout content, navigation;
    private ScrollView scroll;
    private TextView city, freshness;
    private Button refreshButton;
    private Arc7WeatherRadar radar;
    private AlertDialog dialog;
    private int tab, chartMode, weatherGeneration, searchGeneration;
    private final int[] positions=new int[6];
    private boolean active, busy, receiverRegistered;
    private String notice="", stage="";
    private LocationManager locationManager;
    private LocationListener locationListener;
    private final Runnable locationTimeout=new Runnable() {
        @Override public void run() { stopLocation(); notice="Location timed out. Try city search or enter coordinates instead."; render(); }
    };
    private final Runnable automaticRefresh=new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (!busy && place!=null && Arc7WeatherStore.refreshMinutes(Arc7WeatherActivity.this)>0
                    && Arc7WeatherStore.needsRefresh(Arc7WeatherActivity.this,place,System.currentTimeMillis())) refresh(false);
            else updateHeader();
            ui.postDelayed(this,60000L);
        }
    };
    private final BroadcastReceiver updates=new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (active) { loadData(); render(); } }
    };
    private int dp(int n) { return Arc7Skin.dp(this,n); }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); requestWindowFeature(Window.FEATURE_NO_TITLE);
        pal=Arc7Skin.current(this); units=Arc7WeatherStore.units(this);
        if (saved!=null) { tab=Math.max(0,Math.min(5,saved.getInt("tab",0))); chartMode=saved.getInt("chart",0); }
        if (getIntent().hasExtra("weather_tab")) tab=Math.max(0,Math.min(5,getIntent().getIntExtra("weather_tab",0)));
        LinearLayout root=column(); root.setBackgroundDrawable(Arc7Skin.pageDrawable(pal));
        root.addView(Arc7Skin.buildAppTopBar(this,pal,R.drawable.ic_weather,"Weather","Your places \u00b7 Hourly & 14-day forecasts",null));
        LinearLayout actions=new LinearLayout(this);
        action(actions,"Cities",new Runnable() { public void run() { showCities(); } });
        action(actions,"Locate",new Runnable() { public void run() { explainLocation(); } });
        action(actions,"Units",new Runnable() { public void run() { showUnits(); } });
        refreshButton=action(actions,"Refresh",new Runnable() { public void run() { refresh(true); } });
        action(actions,"More",new Runnable() { public void run() { showMore(); } });
        HorizontalScrollView actionScroll=new HorizontalScrollView(this); actionScroll.addView(actions); root.addView(actionScroll);
        LinearLayout heading=column(); heading.setPadding(dp(16),dp(8),dp(16),dp(8));
        city=text("Choose a city",21,pal.textPrimary,true); city.setContentDescription("Selected city. Tap to choose or manage saved locations.");
        city.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showCities(); } }); heading.addView(city);
        freshness=text("",12,pal.textMuted,false); heading.addView(freshness); root.addView(heading);
        navigation=new LinearLayout(this);
        for (int i=0; i<TABS.length; i++) {
            final int index=i;
            action(navigation,TABS[i],new Runnable() { public void run() {
                positions[tab]=scroll.getScrollY(); tab=index; scroll.scrollTo(0,positions[tab]); render();
            }});
        }
        HorizontalScrollView navScroll=new HorizontalScrollView(this); navScroll.addView(navigation); root.addView(navScroll);
        scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=column(); content.setPadding(dp(14),dp(10),dp(14),dp(24)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        setContentView(root); Arc7UiAnimator.attachActivityAnimations(this);
        loadData(); render();
    }
    @Override protected void onResume() {
        super.onResume(); active=true;
        registerReceiver(updates,new IntentFilter(Arc7WeatherService.UPDATED)); receiverRegistered=true;
        loadData(); render();
        try { Arc7WeatherService.schedule(this); } catch (Exception e) { notice=Arc7WeatherStore.message(e); render(); }
        if (place!=null && Arc7WeatherStore.refreshMinutes(this)>0 && Arc7WeatherStore.needsRefresh(this,place,System.currentTimeMillis())) refresh(false);
        ui.postDelayed(automaticRefresh,60000L);
    }
    @Override protected void onStop() {
        active=false; cancelWeather(); cancelSearch(); stopLocation(); closeRadar();
        if (dialog!=null) { dialog.dismiss(); dialog=null; }
        ui.removeCallbacksAndMessages(null);
        if (receiverRegistered) { unregisterReceiver(updates); receiverRegistered=false; }
        super.onStop();
    }
    @Override protected void onDestroy() { weatherWorker.shutdownNow(); searchWorker.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle b) { b.putInt("tab",tab); b.putInt("chart",chartMode); super.onSaveInstanceState(b); }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i); setIntent(i);
        if (i.hasExtra("weather_tab")) tab=Math.max(0,Math.min(5,i.getIntExtra("weather_tab",0)));
        loadData(); render();
    }
    private void loadData() {
        try {
            Arc7WeatherData.Place selected=Arc7WeatherStore.selected(this);
            if (place!=null && (selected==null || !place.id.equals(selected.id))) { cancelWeather(); closeRadar(); }
            place=selected; units=Arc7WeatherStore.units(this);
            forecast=Arc7WeatherStore.forecast(this,place); air=Arc7WeatherStore.air(this,place); alerts=Arc7WeatherStore.alerts(this,place);
        } catch (Exception storageError) {
            cancelWeather(); closeRadar(); place=null; forecast=null; air=null; alerts=null;
            notice="Weather storage unavailable: "+Arc7WeatherStore.message(storageError);
        }
    }
    private void cancelWeather() {
        weatherGeneration++; busy=false;
        if (weatherTransport!=null) weatherTransport.cancel(); if (weatherTask!=null) weatherTask.cancel(true);
        weatherTransport=null; weatherTask=null;
    }
    private void cancelSearch() {
        searchGeneration++;
        if (searchTransport!=null) searchTransport.cancel(); if (searchTask!=null) searchTask.cancel(true);
        searchTransport=null; searchTask=null;
    }
    private void closeRadar() { if (radar!=null) { radar.close(); radar=null; } }
    private void refresh(boolean manual) {
        if (place==null) { showSearch(); return; }
        if (!active || busy) return;
        if (!manual && !Arc7WeatherStore.needsRefresh(this,place,System.currentTimeMillis())) return;
        if (!Arc7WeatherService.connected(this,false)) {
            notice="Offline \u00b7 No connection. Any displayed measurements are saved data; nothing has been refreshed."; render(); return;
        }
        cancelWeather(); busy=true; stage="forecast"; notice="";
        final int token=weatherGeneration;
        final Arc7WeatherData.Place target=place;
        final Arc7WeatherTransport net=new Arc7WeatherTransport(this); weatherTransport=net;
        updateHeader();
        weatherTask=weatherWorker.submit(new Runnable() {
            @Override public void run() {
                try {
                    Arc7WeatherStore.refresh(getApplicationContext(),target,net,new Arc7WeatherStore.Listener() {
                        @Override public void stage(final String kind, final String error) {
                            Arc7WeatherWidgetProvider.refreshAll(getApplicationContext());
                            if (Arc7WeatherStore.ALERTS.equals(kind) && error.length()==0)
                                Arc7WeatherService.notifyAlerts(getApplicationContext(),target);
                            ui.post(new Runnable() { @Override public void run() {
                                if (!valid(token,target)) return;
                                stage=Arc7WeatherStore.FORECAST.equals(kind) ? "air quality" : "official alerts";
                                loadData(); render();
                            }});
                        }
                    });
                } catch (final Exception error) {
                    ui.post(new Runnable() { public void run() { if (valid(token,target)) notice=Arc7WeatherStore.message(error); } });
                } finally {
                    net.cancel();
                    ui.post(new Runnable() { public void run() {
                        if (!valid(token,target)) return;
                        busy=false; weatherTransport=null; loadData(); render();
                    }});
                }
            }
        });
    }
    private boolean valid(int token, Arc7WeatherData.Place target) {
        return active && token==weatherGeneration && place!=null && place.id.equals(target.id) && !isFinishing();
    }
    private void updateHeader() {
        if (city==null) return;
        city.setText(place==null ? "Make yourself at home" : place.name);
        String status;
        if (place==null) status="Add a city, enter coordinates or opt into a one-time device location lookup.";
        else if (forecast==null) status="No forecast saved for this location.";
        else {
            long now=System.currentTimeMillis();
            status=(forecast.stale(now) ? "STALE / SAVED \u00b7 " : "Downloaded ")+Arc7WeatherData.age(forecast.fetchedAt,now)
                    + "\nLocal time "+Arc7WeatherData.formatTime(now,forecast.zone,units.clock12 ? "EEE, MMM d \u00b7 h:mm a z" : "EEE, MMM d \u00b7 HH:mm z");
        }
        if (busy) status="Updating "+stage+"\u2026\n"+status;
        if (locationListener!=null) status="Looking for device location (up to 20 seconds)\u2026\n"+status;
        freshness.setText(status); refreshButton.setText(busy ? "Updating\u2026" : "Refresh"); refreshButton.setEnabled(!busy);
        for (int i=0; i<navigation.getChildCount(); i++) navigation.getChildAt(i).setAlpha(i==tab ? 1f : 0.65f);
    }
    private void render() {
        if (content==null) return;
        final int y=scroll.getScrollY(); content.removeAllViews(); updateHeader();
        if (tab!=5) closeRadar();
        if (notice.length()>0) messageCard("STATUS",notice);
        if (place==null) {
            LinearLayout welcome=card("YOUR WEATHER, YOUR PLACES");
            add(welcome,"Current conditions, the next 48 hours, a 14-day outlook, air quality, sun times, official alerts and past radar. No account or API key.",16,pal.textPrimary);
            add(welcome,"No sample readings are shown. Pick a place to download a forecast. Location permission is only used when you tap Locate.",14,pal.textMuted);
            action(welcome,"Search for a city",new Runnable() { public void run() { showSearch(); } });
            action(welcome,"Enter coordinates",new Runnable() { public void run() { coordinateDialog(null,null); } });
            action(welcome,"Use device location once",new Runnable() { public void run() { explainLocation(); } });
            return;
        }
        String error=Arc7WeatherStore.error(this,place,Arc7WeatherStore.FORECAST);
        if (error.length()>0) messageCard("FORECAST UPDATE FAILED",error+(forecast==null ? "\nNo forecast available for this city yet." : "\nThe last good forecast is retained, not a new observation."));
        if (forecast!=null && forecast.stale(System.currentTimeMillis())) messageCard("SAVED DATA \u2014 NOT CURRENT",
                "Last download: "+stamp(forecast.fetchedAt)+". Current estimate as of "+stamp(forecast.currentTime)
                        +". Check your tablet clock and connection; tap Refresh to try again.");
        if (forecast==null && tab<3) {
            messageCard("WAITING FOR A FORECAST",busy ? "Downloading real weather for this city\u2026" : "Tap Refresh to try again. Air, alerts and radar can still be available independently.");
        } else if (tab==0) renderToday();
        else if (tab==1) renderHourly();
        else if (tab==2) renderDaily();
        else if (tab==3) renderAirSun();
        else if (tab==4) renderAlerts();
        else if (tab==5 && active) {
            if (radar==null) radar=new Arc7WeatherRadar(this,place,units,pal);
            if (radar.getParent()!=null) ((ViewGroup)radar.getParent()).removeView(radar);
            content.addView(radar);
        }
        TextView source=text("Weather: Open-Meteo \u00b7 Search: GeoNames \u00b7 Air: CAMS via Open-Meteo\nTimes use the city's timezone when supplied; otherwise UTC is explicit. Radar uses UTC. Model forecasts are not station observations. Tap More for sources, privacy and settings.",11,pal.textMuted,false);
        source.setPadding(0,dp(15),0,0); content.addView(source);
        ui.post(new Runnable() { public void run() { if (active) scroll.scrollTo(0,y); } });
    }
    private void renderToday() {
        final long now=System.currentTimeMillis();
        Arc7WeatherData.Hour c=forecast.current, h=forecast.nearCurrent(); Arc7WeatherData.Day d=forecast.today(now);
        LinearLayout hero=card("CURRENT MODEL ESTIMATE");
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new Arc7WeatherViews.Icon(this,c.code,c.isDay,pal),new LinearLayout.LayoutParams(dp(90),dp(90)));
        LinearLayout values=column(); add(values,units.temp(c.temp),46,pal.textPrimary);
        add(values,Arc7WeatherData.description(c.code)+(c.isDay==0 ? " \u00b7 Night" : ""),18,pal.textPrimary);
        add(values,"Feels like "+units.temp(c.feels),15,pal.textMuted); row.addView(values,new LinearLayout.LayoutParams(0,-2,1)); hero.addView(row);
        if (d!=null) add(hero,"High "+units.temp(d.high)+"   /   Low "+units.temp(d.low)+"   \u00b7   Precipitation "+Arc7WeatherData.pct(d.probability),14,pal.textMuted);
        add(hero,"Estimate valid "+stamp(forecast.currentTime)+" \u00b7 downloaded "+stamp(forecast.fetchedAt),11,pal.textMuted);
        LinearLayout details=card("WEATHER DETAILS");
        metricRow(details,"Wind from "+Arc7WeatherData.direction(c.direction),units.speed(c.wind),"Gusts",units.speed(c.gust));
        metricRow(details,"Humidity",Arc7WeatherData.pct(c.humidity),"Sea-level pressure",units.pressure(c.pressure));
        metricRow(details,"Cloud cover",Arc7WeatherData.pct(c.cloud),"Current precipitation",units.rain(c.rain));
        metricRow(details,"Dew point (hourly)",units.temp(h.dew),"Visibility (hourly)",units.visibility(h.visibility));
        metricRow(details,"UV (hourly)",Arc7WeatherData.measure(h.uv,1,"")+" \u00b7 "+Arc7WeatherData.uvLabel(h.uv),"Max UV today",d==null ? Arc7WeatherData.MISSING : Arc7WeatherData.measure(d.uv,1,"")+" \u00b7 "+Arc7WeatherData.uvLabel(d.uv));
        LinearLayout plan=card("PLAN YOUR DAY \u00b7 FORECAST GUIDANCE, NOT OFFICIAL ALERTS");
        for (String tip : Arc7WeatherData.guidance(forecast,now,units)) add(plan,"\u2022 "+tip,14,pal.textPrimary);
        add(plan,"Hourly totals are not a minute-by-minute rain prediction.",11,pal.textMuted);
        action(plan,"View official alerts",new Runnable() { public void run() { tab=4; scroll.scrollTo(0,0); render(); } });
        List<Arc7WeatherData.Hour> next=forecast.upcoming(now,24);
        if (!next.isEmpty()) {
            LinearLayout preview=card("NEXT 24 HOURS \u00b7 TEMPERATURE"); chart(preview,next,0);
            action(preview,"Explore all 48 hours",new Runnable() { public void run() { tab=1; scroll.scrollTo(0,0); render(); } });
        }
        if (d!=null) {
            LinearLayout sun=card("SUNRISE & SUNSET");
            metricRow(sun,"Sunrise",units.time(d.sunrise,forecast.zone),"Sunset",units.time(d.sunset,forecast.zone));
            add(sun,"Daylight "+Arc7WeatherData.duration(d.daylight)+" \u00b7 Sunshine forecast "+Arc7WeatherData.duration(d.sunshine),13,pal.textMuted);
            if (d.sunrise<=0 || d.sunset<=0) add(sun,"Sunrise/sunset may be unavailable during polar day/night or outside coverage.",12,pal.textMuted);
        }
        if (alerts!=null && !alerts.active(now).isEmpty()) messageCard("OFFICIAL ALERTS",alerts.active(now).size()+" saved active alert(s). Open Alerts for instructions and freshness.");
    }
    private void chart(LinearLayout parent, final List<Arc7WeatherData.Hour> next, int mode) {
        parent.addView(new Arc7WeatherViews.Chart(this,next,forecast,units,pal,mode,new Arc7WeatherViews.Pick() {
            public void hour(int i) { showHour(next.get(i)); }
        }),new LinearLayout.LayoutParams(-1,dp(190)));
        add(parent,"Tap the chart or an hourly row for details. Gaps mean missing data.",11,pal.textMuted);
    }
    private void renderHourly() {
        final List<Arc7WeatherData.Hour> next=forecast.upcoming(System.currentTimeMillis(),48);
        LinearLayout head=card("NEXT 48 HOURS");
        if (next.isEmpty()) { add(head,"No upcoming hours in this saved forecast. Refresh for new data.",15,pal.textPrimary); return; }
        LinearLayout modes=new LinearLayout(this);
        final String[] names={"Temperature","Rain %","Wind"};
        for (int i=0; i<names.length; i++) {
            final int n=i; Button b=action(modes,names[i],new Runnable() { public void run() { chartMode=n; render(); } }); b.setEnabled(i!=chartMode);
        }
        HorizontalScrollView hs=new HorizontalScrollView(this); hs.addView(modes); head.addView(hs);
        chart(head,next,chartMode);
        add(head,"Hourly precipitation is a total for the preceding hour ending at the provider timestamp. Chance and amount describe different things.",12,pal.textMuted);
        for (final Arc7WeatherData.Hour h : next) {
            LinearLayout r=card(Arc7WeatherData.formatTime(h.time,forecast.zone,"EEE, MMM d")+"  \u00b7  "+units.time(h.time,forecast.zone));
            add(r,units.temp(h.temp)+"   "+Arc7WeatherData.description(h.code),18,pal.textPrimary);
            add(r,"Feels "+units.temp(h.feels)+" \u00b7 Precipitation "+Arc7WeatherData.pct(h.probability)+" / "+units.rain(h.rain)
                    +" \u00b7 Wind "+units.speed(h.wind)+" "+Arc7WeatherData.direction(h.direction),13,pal.textMuted);
            action(r,"Hour details",new Runnable() { public void run() { showHour(h); } });
        }
    }
    private String hourDetails(Arc7WeatherData.Hour h) {
        return Arc7WeatherData.description(h.code)+"\nTemperature: "+units.temp(h.temp)+"\nFeels like: "+units.temp(h.feels)
                +"\nPrecipitation chance: "+Arc7WeatherData.pct(h.probability)+"\nTotal precipitation: "+units.rain(h.rain)
                +"\nSnowfall: "+units.snow(h.snow)+"\nWind from: "+Arc7WeatherData.direction(h.direction)+" ("+Arc7WeatherData.measure(h.direction,0,"\u00b0")+")"
                +"\nWind: "+units.speed(h.wind)+"\nGusts: "+units.speed(h.gust)+"\nHumidity: "+Arc7WeatherData.pct(h.humidity)
                +"\nDew point: "+units.temp(h.dew)+"\nCloud cover: "+Arc7WeatherData.pct(h.cloud)
                +"\nVisibility: "+units.visibility(h.visibility)+"\nSea-level pressure: "+units.pressure(h.pressure)
                +"\nUV: "+Arc7WeatherData.measure(h.uv,1,"")+" ("+Arc7WeatherData.uvLabel(h.uv)+")"
                +"\n\nModel forecast downloaded "+stamp(forecast.fetchedAt)+". \u2014 means not provided.";
    }
    private void showHour(Arc7WeatherData.Hour h) { showMessage(stamp(h.time),hourDetails(h)); }
    private void renderDaily() {
        LinearLayout intro=card("14-DAY OUTLOOK");
        add(intro,"Tap Day details for feels-like, rainfall, snow, wind, UV, sun times and that day's hourly breakdown. Later forecasts are less certain; some fields can be unavailable.",14,pal.textMuted);
        String today=Arc7WeatherData.formatTime(System.currentTimeMillis(),forecast.zone,"yyyy-MM-dd"); int count=0;
        double overallLow=Double.POSITIVE_INFINITY, overallHigh=Double.NEGATIVE_INFINITY;
        for (Arc7WeatherData.Day d : forecast.days) if (d.date.compareTo(today)>=0) {
            if (Arc7WeatherData.finite(d.low)) overallLow=Math.min(overallLow,d.low);
            if (Arc7WeatherData.finite(d.high)) overallHigh=Math.max(overallHigh,d.high);
        }
        for (final Arc7WeatherData.Day d : forecast.days) {
            if (d.date.compareTo(today)<0) continue; count++;
            LinearLayout card=card(d.date.equals(today) ? "TODAY \u00b7 "+Arc7WeatherData.formatTime(d.time,forecast.zone,"MMM d")
                    : Arc7WeatherData.formatTime(d.time,forecast.zone,"EEEE, MMM d"));
            add(card,Arc7WeatherData.description(d.code)+"   \u00b7   "+units.temp(d.high)+" / "+units.temp(d.low),18,pal.textPrimary);
            if (Arc7WeatherData.finite(d.high) && Arc7WeatherData.finite(d.low) && overallHigh>overallLow) {
                card.addView(new Arc7WeatherRange(this,d.low,d.high,overallLow,overallHigh,pal),new LinearLayout.LayoutParams(-1,dp(16)));
            }
            add(card,"Precipitation "+Arc7WeatherData.pct(d.probability)+" \u00b7 "+units.rain(d.rain)+" total \u00b7 Wind up to "+units.speed(d.wind),13,pal.textMuted);
            action(card,"Day details",new Runnable() { public void run() { showDay(d); } });
        }
        if (count==0) add(intro,"No upcoming days remain in this saved forecast. Refresh for a new outlook.",15,pal.textPrimary);
    }
    private void showDay(Arc7WeatherData.Day d) {
        StringBuilder b=new StringBuilder(Arc7WeatherData.description(d.code));
        b.append("\nHigh / low: ").append(units.temp(d.high)).append(" / ").append(units.temp(d.low))
                .append("\nFeels-like high / low: ").append(units.temp(d.feelsHigh)).append(" / ").append(units.temp(d.feelsLow))
                .append("\nPrecipitation chance: ").append(Arc7WeatherData.pct(d.probability))
                .append("\nTotal precipitation: ").append(units.rain(d.rain)).append("\nSnowfall: ").append(units.snow(d.snow))
                .append("\nHours with precipitation: ").append(Arc7WeatherData.measure(d.wetHours,1," h"))
                .append("\nMaximum wind: ").append(units.speed(d.wind)).append("\nMaximum gust: ").append(units.speed(d.gust))
                .append("\nDominant wind from: ").append(Arc7WeatherData.direction(d.direction))
                .append("\nMaximum UV: ").append(Arc7WeatherData.measure(d.uv,1,"")).append(" ("+Arc7WeatherData.uvLabel(d.uv)+")")
                .append("\nSunrise: ").append(units.time(d.sunrise,forecast.zone)).append("\nSunset: ").append(units.time(d.sunset,forecast.zone))
                .append("\nDaylight: ").append(Arc7WeatherData.duration(d.daylight)).append("\nSunshine: ").append(Arc7WeatherData.duration(d.sunshine))
                .append("\n\nHOURLY BREAKDOWN\nTime \u00b7 Temperature \u00b7 Precipitation chance \u00b7 Wind\n");
        int count=0;
        for (Arc7WeatherData.Hour h : forecast.hours) if (d.date.equals(Arc7WeatherData.formatTime(h.time,forecast.zone,"yyyy-MM-dd"))) {
            b.append(units.time(h.time,forecast.zone)).append(" \u00b7 ").append(units.temp(h.temp)).append(" \u00b7 ")
                    .append(Arc7WeatherData.pct(h.probability)).append(" \u00b7 ").append(units.speed(h.wind)).append('\n'); count++;
        }
        if (count==0) b.append("No hourly data returned for this day.\n");
        b.append("\nDownloaded ").append(stamp(forecast.fetchedAt)).append(". \u2014 = not provided.");
        showMessage(Arc7WeatherData.formatTime(d.time,forecast.zone,"EEEE, MMM d"),b.toString());
    }
    private void renderAirSun() {
        long now=System.currentTimeMillis(); TimeZone z=zone();
        LinearLayout card=card("AIR QUALITY \u00b7 "+(units.europeanAqi ? "EUROPEAN AQI" : "US AQI"));
        String error=Arc7WeatherStore.error(this,place,Arc7WeatherStore.AIR);
        if (error.length()>0) add(card,"Air quality refresh failed: "+error,14,pal.textPrimary);
        if (air==null) add(card,"Air quality not downloaded or not available for this location. Forecasts work independently of this provider.",14,pal.textMuted);
        else {
            Arc7WeatherData.AirHour h=air.at(now); double aqi=h.index(units);
            add(card,Arc7WeatherData.measure(aqi,0,"")+"   "+Arc7WeatherData.aqiLabel(aqi,units.europeanAqi),27,pal.textPrimary);
            add(card,"Model hour "+stamp(h.time)+" \u00b7 downloaded "+stamp(air.fetchedAt)+(now-air.fetchedAt>2*Arc7WeatherData.HOUR ? " \u00b7 STALE" : ""),12,pal.textMuted);
            metricRow(card,"PM2.5",Arc7WeatherData.measure(h.pm25,1," \u00b5g/m\u00b3"),"PM10",Arc7WeatherData.measure(h.pm10,1," \u00b5g/m\u00b3"));
            metricRow(card,"Ozone",Arc7WeatherData.measure(h.ozone,1," \u00b5g/m\u00b3"),"Nitrogen dioxide",Arc7WeatherData.measure(h.no2,1," \u00b5g/m\u00b3"));
            metricRow(card,"Sulphur dioxide",Arc7WeatherData.measure(h.so2,1," \u00b5g/m\u00b3"),"Carbon monoxide",Arc7WeatherData.measure(h.co,0," \u00b5g/m\u00b3"));
            if (Arc7WeatherData.finite(aqi) && aqi>(units.europeanAqi ? 60 : 100))
                add(card,"Air pollution is elevated in the model. Consider reducing strenuous outdoor activity, especially if sensitive. Follow local health advice.",14,pal.textPrimary);
            add(card,"CAMS model estimates via Open-Meteo, not a nearby sensor reading or medical advice. AQI scales differ; use Units to switch US/European AQI.",12,pal.textMuted);
            LinearLayout pollen=card("POLLEN \u00b7 GRAINS/m\u00b3");
            boolean available=false;
            for (int i=0; i<Arc7WeatherData.POLLEN_NAMES.length; i++) {
                add(pollen,Arc7WeatherData.POLLEN_NAMES[i]+": "+Arc7WeatherData.measure(h.pollen[i],0," grains/m\u00b3"),14,pal.textPrimary);
                if (Arc7WeatherData.finite(h.pollen[i])) available=true;
            }
            add(pollen,available ? "Regional model estimates; not an allergy diagnosis." : "Pollen is not available here. Provider pollen coverage is primarily European; missing is NOT zero.",12,pal.textMuted);
            LinearLayout outlook=card("AIR QUALITY \u00b7 NEXT 24 HOURS"); int count=0;
            for (Arc7WeatherData.AirHour n : air.hours) if (n.time>=now-Arc7WeatherData.HOUR && n.time<now+24*Arc7WeatherData.HOUR) {
                if (count++%3==0) add(outlook,Arc7WeatherData.formatTime(n.time,z,"EEE ")+units.time(n.time,z)+"  \u00b7  "+Arc7WeatherData.measure(n.index(units),0,"")+"  "+Arc7WeatherData.aqiLabel(n.index(units),units.europeanAqi),14,pal.textPrimary);
            }
            if (count==0) add(outlook,"No upcoming air-quality hours available. Refresh for a new outlook.",14,pal.textMuted);
        }
        LinearLayout sun=card("SUN & MOON");
        Arc7WeatherData.Day d=forecast==null ? null : forecast.today(now);
        if (d!=null) {
            metricRow(sun,"Sunrise",units.time(d.sunrise,z),"Sunset",units.time(d.sunset,z));
            metricRow(sun,"Daylight",Arc7WeatherData.duration(d.daylight),"Sunshine forecast",Arc7WeatherData.duration(d.sunshine));
            add(sun,"Today's maximum UV: "+Arc7WeatherData.measure(d.uv,1,"")+" \u00b7 "+Arc7WeatherData.uvLabel(d.uv),15,pal.textPrimary);
        } else add(sun,"Sun times require today's weather forecast. Missing sunrise/sunset can also indicate polar day/night.",14,pal.textMuted);
        add(sun,Arc7WeatherData.moon(now),16,pal.textPrimary);
        add(sun,"Moon phase is an approximate astronomical calculation, not an observed condition. Moonrise/moonset are not supplied.",12,pal.textMuted);
    }
    private void renderAlerts() {
        long now=System.currentTimeMillis();
        LinearLayout info=card("OFFICIAL WEATHER ALERTS");
        add(info,"Alerts apply to the selected saved city, not a continuously tracked position. Always follow the issuing authority. This app is NOT an emergency-alert system.",14,pal.textPrimary);
        if (Arc7WeatherStore.alertUrl(place).length()==0) {
            add(info,"In-app official alerts are available for Canada (ECCC) and the United States (NWS). This location has no supported alert region. Unsupported does NOT mean no warnings.",14,pal.textMuted);
            action(info,"Set location name / alert region",new Runnable() { public void run() { coordinateDialog(place,null); } });
            action(info,"Find your national weather service",new Runnable() { public void run() { openUrl("https://worldweather.wmo.int/"); } });
        } else {
            String error=Arc7WeatherStore.error(this,place,Arc7WeatherStore.ALERTS);
            if (error.length()>0) add(info,"Alert refresh failed: "+error+". Saved alerts may be incomplete or outdated.",14,pal.textPrimary);
            if (alerts==null) add(info,"No verified alert response yet. Tap Refresh. Never assume this means there are no warnings.",15,pal.textPrimary);
            else {
                boolean fresh=alerts.fetchedAt>0 && alerts.fetchedAt<=now+300000L && now-alerts.fetchedAt<=30*60000L && error.length()==0;
                add(info,alerts.provider+"\nLast checked "+stamp(alerts.fetchedAt)+(fresh ? "" : " \u00b7 SAVED / NOT CURRENT"),13,pal.textMuted);
                if (!alerts.complete) add(info,"INCOMPLETE provider response (pagination or missing fields). This is not a complete list of warnings.",14,pal.textPrimary);
                List<Arc7WeatherData.Alert> activeAlerts=alerts.active(now);
                if (activeAlerts.isEmpty()) add(info,fresh && alerts.complete ? "No active alerts returned by the provider at the last check. Conditions can change quickly."
                        : "No currently valid alerts in the saved response. Current official alert status is UNKNOWN; check the authority below.",15,pal.textPrimary);
                for (Arc7WeatherData.Alert a : activeAlerts) {
                    LinearLayout alertCard=card(a.severity.length()==0 ? "OFFICIAL ALERT" : a.severity.toUpperCase(java.util.Locale.US));
                    add(alertCard,a.title,19,pal.textPrimary); add(alertCard,a.area,13,pal.textMuted);
                    add(alertCard,"Expires "+stamp(a.expires),12,pal.textMuted);
                    add(alertCard,plain(a.detail),14,pal.textPrimary);
                    if (a.instruction.length()>0) add(alertCard,"INSTRUCTIONS\n"+plain(a.instruction),15,pal.textPrimary);
                }
            }
            action(info,"Open official warning website",new Runnable() { public void run() {
                openUrl("CA".equals(place.country) ? "https://weather.gc.ca/warnings/index_e.html" : "https://www.weather.gov/");
            }});
        }
        LinearLayout notifications=card("OPTIONAL ALERT NOTIFICATIONS");
        add(notifications,Arc7WeatherStore.notifications(this) ? "Enabled for the selected city." : "Off by default. Enable in Weather settings if wanted.",14,pal.textPrimary);
        add(notifications,"Notifications are checked on refresh, not pushed in real time. Background checks need a chosen interval and a connection, and can be delayed by sleep or Android. No background location tracking.",13,pal.textMuted);
        action(notifications,"Weather settings",new Runnable() { public void run() { showSettings(); } });
    }
    private static String plain(String s) { return s.indexOf('<')>=0 ? Html.fromHtml(s).toString() : s; }

    private void choosePlace(Arc7WeatherData.Place p) {
        try {
            for (Arc7WeatherData.Place old : Arc7WeatherStore.places(this)) if (old.id.equals(p.id)) {
                p=new Arc7WeatherData.Place(old.name,old.lat,old.lon,old.country.length()>0 ? old.country : p.country); break;
            }
            Arc7WeatherStore.savePlace(this,p,true); selectedChanged();
        } catch (Exception e) { showMessage("Location not saved",Arc7WeatherStore.message(e)); }
    }
    private void selectedChanged() {
        cancelWeather(); closeRadar(); stopLocation(); notice=""; Arc7WeatherService.cancelNotification(this);
        for (int i=0; i<positions.length; i++) positions[i]=0;
        scroll.scrollTo(0,0); loadData(); render(); Arc7WeatherWidgetProvider.refreshAll(this); Arc7WeatherService.schedule(this);
        if (place!=null) {
            if (Arc7WeatherStore.alertUrl(place).length()>0 && alerts==null) refresh(true);
            else if (Arc7WeatherStore.needsRefresh(this,place,System.currentTimeMillis())) refresh(false);
        }
    }
    private void showCities() {
        LinearLayout box=dialogBox();
        add(box,"The selected city is used by the home-screen widget and optional alert notifications. Save up to 12 places; each keeps its own offline forecast.",13,pal.textMuted);
        List<Arc7WeatherData.Place> all=Arc7WeatherStore.places(this);
        for (final Arc7WeatherData.Place p : all) {
            LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            Button select=action(row,p.name+(place!=null && place.id.equals(p.id) ? "  [selected]" : ""),new Runnable() {
                public void run() { dismissDialog(); Arc7WeatherStore.select(Arc7WeatherActivity.this,p); selectedChanged(); }
            }); select.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
            action(row,"Edit",new Runnable() { public void run() { placeMenu(p); } }); box.addView(row);
            add(box,Arc7WeatherData.coordinates(p.lat,p.lon)+" \u00b7 Saved forecast: "+Arc7WeatherData.age(Arc7WeatherStore.fetchedAt(this,p,Arc7WeatherStore.FORECAST),System.currentTimeMillis()),11,pal.textMuted);
        }
        action(box,"Add city",new Runnable() { public void run() { showSearch(); } });
        action(box,"Add coordinates",new Runnable() { public void run() { coordinateDialog(null,null); } });
        showDialog(new AlertDialog.Builder(this).setTitle("Saved places").setView(wrap(box)).setNegativeButton("Close",null).create());
    }
    private void placeMenu(final Arc7WeatherData.Place p) {
        showDialog(new AlertDialog.Builder(this).setTitle(p.name).setItems(new String[]{"Rename / set alert region","Move to top","Remove saved city"},new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which==0) coordinateDialog(p,null);
                else if (which==1) {
                    try { Arc7WeatherStore.moveFirst(Arc7WeatherActivity.this,p); showCities(); }
                    catch (Exception e) { showMessage("Could not reorder cities",Arc7WeatherStore.message(e)); }
                }
                else showDialog(new AlertDialog.Builder(Arc7WeatherActivity.this).setTitle("Remove "+p.name+"?")
                        .setMessage("This removes only this city's private cached weather and saved location. Other cities and settings are kept.")
                        .setPositiveButton("Remove",new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                            try { Arc7WeatherStore.remove(Arc7WeatherActivity.this,p); selectedChanged(); }
                            catch (Exception e) { showMessage("Could not remove city",Arc7WeatherStore.message(e)); }
                        }}).setNegativeButton("Cancel",null).create());
            }
        }).setNegativeButton("Cancel",null).create());
    }
    private void showSearch() {
        final LinearLayout box=dialogBox();
        final EditText input=new EditText(this); input.setSingleLine(true); input.setTextColor(pal.textPrimary); input.setHintTextColor(pal.textMuted); input.setHint("City name or postal code (2+ characters)"); box.addView(input);
        final TextView status=text("Choose the correct city from the results; country and region are included.",13,pal.textMuted,false); box.addView(status);
        final LinearLayout results=column();
        final Button search=action(box,"Search",null); box.addView(results);
        action(box,"Enter coordinates instead",new Runnable() { public void run() { coordinateDialog(null,null); } });
        final AlertDialog searchDialog=new AlertDialog.Builder(this).setTitle("Find a city").setView(wrap(box)).setNegativeButton("Cancel",null).create();
        search.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String url;
                try { url=Arc7WeatherStore.searchUrl(input.getText().toString()); }
                catch (Exception e) { status.setText(Arc7WeatherStore.message(e)); return; }
                cancelSearch(); final int token=searchGeneration;
                final Arc7WeatherTransport net=new Arc7WeatherTransport(Arc7WeatherActivity.this); searchTransport=net;
                status.setText("Searching\u2026"); results.removeAllViews(); search.setEnabled(false);
                searchTask=searchWorker.submit(new Runnable() { public void run() {
                    try {
                        final List<Arc7WeatherData.Place> hits=Arc7WeatherData.parseSearch(net.get(url));
                        ui.post(new Runnable() { public void run() {
                            if (!active || token!=searchGeneration || !searchDialog.isShowing()) return;
                            search.setEnabled(true); status.setText(hits.isEmpty() ? "No matching cities. Try a nearby larger city or coordinates." : "Select a city to save and view:");
                            for (final Arc7WeatherData.Place p : hits) action(results,p.name+"\n"+Arc7WeatherData.coordinates(p.lat,p.lon),new Runnable() {
                                public void run() { searchDialog.dismiss(); choosePlace(p); }
                            });
                        }});
                    } catch (final Exception e) {
                        ui.post(new Runnable() { public void run() {
                            if (active && token==searchGeneration && searchDialog.isShowing()) { search.setEnabled(true); status.setText("Search failed: "+Arc7WeatherStore.message(e)+". You can still use saved places or enter coordinates."); }
                        }});
                    } finally { net.cancel(); }
                }});
            }
        });
        searchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() { public void onDismiss(DialogInterface d) { cancelSearch(); } });
        showDialog(searchDialog);
    }
    private void coordinateDialog(final Arc7WeatherData.Place editing, Location fix) {
        LinearLayout box=dialogBox();
        final EditText name=input(box,"Location name",editing==null ? (fix==null ? "" : "Device location") : editing.name,false);
        double lat=editing!=null ? editing.lat : fix!=null ? fix.getLatitude() : Double.NaN;
        double lon=editing!=null ? editing.lon : fix!=null ? fix.getLongitude() : Double.NaN;
        final EditText latitude=input(box,"Latitude (-90 to 90)",Arc7WeatherData.finite(lat) ? String.valueOf(lat) : "",true);
        final EditText longitude=input(box,"Longitude (-180 to 180)",Arc7WeatherData.finite(lon) ? String.valueOf(lon) : "",true);
        if (editing!=null) { latitude.setEnabled(false); longitude.setEnabled(false); }
        final Spinner region=spinner(box,"Official alert region",new String[]{"Other / not set","Canada (ECCC)","United States (NWS)"},editing!=null && "CA".equals(editing.country) ? 1 : editing!=null && "US".equals(editing.country) ? 2 : 0);
        if (fix!=null) add(box,"One-time device fix, accuracy about "+Arc7WeatherData.measure(fix.hasAccuracy() ? fix.getAccuracy()/1000.0 : Double.NaN,1," km")+". Verify the location and alert region before saving.",12,pal.textMuted);
        final TextView error=text("Coordinates are stored privately in Weather and sent to weather providers only for requests. This is not background tracking.",12,pal.textMuted,false); box.addView(error);
        final AlertDialog d=new AlertDialog.Builder(this).setTitle(editing==null ? "Add coordinates" : "Edit saved city").setView(wrap(box))
                .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        showDialog(d);
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                String country=region.getSelectedItemPosition()==1 ? "CA" : region.getSelectedItemPosition()==2 ? "US" : "";
                Arc7WeatherData.Place p=new Arc7WeatherData.Place(name.getText().toString(),Double.parseDouble(latitude.getText().toString().trim()),Double.parseDouble(longitude.getText().toString().trim()),country);
                boolean select=editing==null || (place!=null && place.id.equals(editing.id));
                Arc7WeatherStore.savePlace(Arc7WeatherActivity.this,p,select); d.dismiss(); selectedChanged();
            } catch (Exception invalid) { error.setText(Arc7WeatherStore.message(invalid)); }
        }});
    }
    private void explainLocation() {
        showDialog(new AlertDialog.Builder(this).setTitle("Use device location once?")
                .setMessage("Weather can ask Android's enabled network/GPS provider for one position, for up to 20 seconds. The coordinates are then used for forecasts. No continuous or background tracking. Some Kobo models/ROMs have no working location provider; city search and coordinates always remain available.")
                .setPositiveButton("Find location",new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { locate(); } })
                .setNegativeButton("Cancel",null).create());
    }
    private boolean acceptable(Location l) {
        return l!=null && Arc7WeatherData.validFix(l.getLatitude(),l.getLongitude(),l.getTime(),
                l.hasAccuracy() ? l.getAccuracy() : Double.NaN,System.currentTimeMillis());
    }
    private void locate() {
        stopLocation();
        try {
            locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
            if (locationManager==null) throw new IllegalStateException("Android location service is unavailable.");
            List<String> providers=locationManager.getProviders(true); Location best=null; boolean supported=false;
            for (String provider : new String[]{LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER}) if (providers.contains(provider)) {
                supported=true; Location l=locationManager.getLastKnownLocation(provider);
                if (acceptable(l) && (best==null || l.getTime()>best.getTime())) best=l;
            }
            if (best!=null) { coordinateDialog(null,best); return; }
            if (!supported) throw new IllegalStateException("No enabled GPS/network provider. Enable Location in Android settings, or use city search / coordinates.");
            locationListener=new LocationListener() {
                @Override public void onLocationChanged(Location l) { if (active && acceptable(l)) { stopLocation(); notice=""; coordinateDialog(null,l); updateHeader(); } }
                @Override public void onProviderDisabled(String p) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
            };
            for (String provider : new String[]{LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER})
                if (providers.contains(provider)) locationManager.requestLocationUpdates(provider,0,0,locationListener,Looper.getMainLooper());
            notice="Looking for a one-time location fix. You can keep using Weather."; render(); ui.postDelayed(locationTimeout,20000L);
        } catch (Exception unavailable) { stopLocation(); notice=Arc7WeatherStore.message(unavailable); render(); }
    }
    private void stopLocation() {
        ui.removeCallbacks(locationTimeout);
        if (locationManager!=null && locationListener!=null) try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        locationListener=null; locationManager=null;
    }
    private void showUnits() {
        LinearLayout box=dialogBox();
        final Spinner temp=spinner(box,"Temperature",new String[]{"Celsius (\u00b0C)","Fahrenheit (\u00b0F)"},units.fahrenheit ? 1 : 0);
        final Spinner wind=spinner(box,"Wind",new String[]{"km/h","mph","m/s","knots"},units.wind);
        final Spinner rain=spinner(box,"Precipitation / snow",new String[]{"mm / cm","inches"},units.inches ? 1 : 0);
        final Spinner distance=spinner(box,"Visibility",new String[]{"kilometres","miles"},units.miles ? 1 : 0);
        final Spinner pressure=spinner(box,"Pressure",new String[]{"hPa","inHg"},units.inHg ? 1 : 0);
        final Spinner clock=spinner(box,"Time",new String[]{"24-hour","12-hour"},units.clock12 ? 1 : 0);
        final Spinner aqi=spinner(box,"Air quality index",new String[]{"US AQI","European AQI"},units.europeanAqi ? 1 : 0);
        showDialog(new AlertDialog.Builder(this).setTitle("Display units").setView(wrap(box)).setPositiveButton("Save",new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                Arc7WeatherData.Units u=new Arc7WeatherData.Units(); u.fahrenheit=temp.getSelectedItemPosition()==1; u.wind=wind.getSelectedItemPosition();
                u.inches=rain.getSelectedItemPosition()==1; u.miles=distance.getSelectedItemPosition()==1; u.inHg=pressure.getSelectedItemPosition()==1;
                u.clock12=clock.getSelectedItemPosition()==1; u.europeanAqi=aqi.getSelectedItemPosition()==1;
                try {
                    Arc7WeatherStore.saveUnits(Arc7WeatherActivity.this,u); units=u; closeRadar(); render(); Arc7WeatherWidgetProvider.refreshAll(Arc7WeatherActivity.this);
                } catch (Exception e) { showMessage("Could not save units",Arc7WeatherStore.message(e)); }
            }
        }).setNegativeButton("Cancel",null).create());
    }
    private void showMore() {
        showDialog(new AlertDialog.Builder(this).setTitle("Weather").setItems(new String[]{"Weather settings","Share forecast","Sources, privacy & limitations","Clear downloaded caches (keep cities)"},new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w==0) showSettings(); else if (w==1) share(); else if (w==2) about();
                else showDialog(new AlertDialog.Builder(Arc7WeatherActivity.this).setTitle("Clear weather caches?")
                        .setMessage("Saved cities, units and notification preferences are kept. Forecasts, air quality, alerts and map tiles must be downloaded again. This does not clear Android or any other app's data.")
                        .setPositiveButton("Clear caches",new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int which) {
                            try {
                                cancelWeather(); closeRadar(); Arc7WeatherStore.clearCaches(Arc7WeatherActivity.this); Arc7WeatherRadar.clearCache(Arc7WeatherActivity.this);
                                Arc7WeatherService.cancelNotification(Arc7WeatherActivity.this); loadData(); notice="Downloaded weather caches cleared. Tap Refresh to download again."; render(); Arc7WeatherWidgetProvider.refreshAll(Arc7WeatherActivity.this);
                            } catch (Exception e) { loadData(); render(); showMessage("Could not clear all caches",Arc7WeatherStore.message(e)); }
                        }}).setNegativeButton("Cancel",null).create());
            }
        }).setNegativeButton("Close",null).create());
    }
    private void showSettings() {
        LinearLayout box=dialogBox();
        int minutes=Arc7WeatherStore.refreshMinutes(this);
        final Spinner interval=spinner(box,"Automatic refresh",new String[]{"Manual only","Every 30 minutes","Every hour","Every 3 hours"},minutes==0 ? 0 : minutes==30 ? 1 : minutes==60 ? 2 : 3);
        final CheckBox wifi=check(box,"Background updates on Wi-Fi / Ethernet only",Arc7WeatherStore.wifiOnly(this));
        final CheckBox notifications=check(box,"Notify about official CA / US alerts for selected city",Arc7WeatherStore.notifications(this));
        final CheckBox sound=check(box,"Play Android notification sound for new alerts",Arc7WeatherStore.alertSound(this));
        add(box,"In-app automatic refresh runs while Weather is visible. Background updates run only with a Weather widget or alert notifications enabled, never track your position, never turn Wi-Fi on, and never wake a sleeping tablet. Manual only disables background polling. Manual Refresh may use any active connection.",13,pal.textMuted);
        add(box,"Alerts are polled, not pushed. Updates can be delayed or fail. Do not rely on this app for emergency warning delivery. Official alerts are supported only for locations explicitly marked Canada or United States.",13,pal.textMuted);
        showDialog(new AlertDialog.Builder(this).setTitle("Weather settings").setView(wrap(box)).setPositiveButton("Save",new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                try {
                    int[] periods={0,30,60,180}; Arc7WeatherStore.saveOptions(Arc7WeatherActivity.this,periods[interval.getSelectedItemPosition()],wifi.isChecked(),notifications.isChecked(),sound.isChecked());
                    Arc7WeatherService.schedule(Arc7WeatherActivity.this);
                    if (notifications.isChecked()) Arc7WeatherService.notifyAlerts(Arc7WeatherActivity.this,place);
                    render();
                } catch (Exception e) { showMessage("Could not save weather settings",Arc7WeatherStore.message(e)); }
            }
        }).setNegativeButton("Cancel",null).create());
    }
    private void share() {
        if (forecast==null || place==null) { showMessage("Nothing to share","Download a forecast for a saved city first."); return; }
        Arc7WeatherData.Day d=forecast.today(System.currentTimeMillis());
        String s=place.name+"\n"+units.temp(forecast.current.temp)+" \u00b7 "+Arc7WeatherData.description(forecast.current.code)
                +"\nFeels like "+units.temp(forecast.current.feels)+" \u00b7 Wind "+units.speed(forecast.current.wind)
                +(d==null ? "" : "\nToday's high / low: "+units.temp(d.high)+" / "+units.temp(d.low)+" \u00b7 Precipitation chance "+Arc7WeatherData.pct(d.probability))
                +"\nEstimate valid "+stamp(forecast.currentTime)+"\nDownloaded "+stamp(forecast.fetchedAt)
                +(forecast.stale(System.currentTimeMillis()) ? " (STALE SAVED DATA)" : "")+"\nForecast: Open-Meteo \u00b7 Arc7 Weather";
        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,s);
        try { startActivity(Intent.createChooser(send,"Share forecast")); } catch (Exception unavailable) { showMessage("No sharing app",s); }
    }
    private void about() {
        LinearLayout box=dialogBox();
        add(box,"WEATHER & PLACES",14,pal.accent);
        add(box,"Forecasts: Open-Meteo, using multiple national weather models. City search: GeoNames via Open-Meteo. Air quality: Copernicus Atmosphere Monitoring Service (CAMS) via Open-Meteo. These are model estimates, not observations at your street; some variables and locations have gaps. Later daily forecasts have higher uncertainty.",14,pal.textPrimary);
        add(box,"ALERTS & MAPS",14,pal.accent);
        add(box,"Official alerts: Environment and Climate Change Canada GeoMet weather-alerts collection, or US National Weather Service active alerts. ECCC regions are filtered to the saved coordinates. Unsupported, incomplete, failed and stale checks are explicitly marked. Other countries need their national authority. Notifications are optional and polled, not emergency broadcasts.\nRadar: RainViewer public past frames plus provider short-range nowcast frames when available, max zoom 7. Nowcasts are estimates, not guaranteed forecasts; no satellite is provided. Base map: \u00a9 OpenStreetMap contributors, ODbL. Blank radar is not a guarantee of no rain.",14,pal.textPrimary);
        add(box,"PRIVACY & OFFLINE",14,pal.accent);
        add(box,"No account, API key, ads or analytics. Saved cities, preferences and weather are in Weather's private app storage, not a shared SD-card file. Requests send city queries/coordinates and your IP to the chosen providers. Locate is a one-time, optional Android lookup that is stopped when you leave the app. There is no background location tracking. Offline forecasts always retain their download and validity times; missing values display \u2014, never zero or sample conditions. Map tiles use a separate private 12 MB cache. Android's own backup settings may include private app data.\n\nThe app uses its own UID/TLS engine and works without root, Play Services or the suite's proxy. Provider availability depends on your connection. Location support depends on the Kobo model and ROM; city search and manual coordinates do not require GPS.",14,pal.textPrimary);
        action(box,"Open-Meteo & attribution",new Runnable() { public void run() { openUrl("https://open-meteo.com/"); } });
        action(box,"RainViewer",new Runnable() { public void run() { openUrl("https://www.rainviewer.com/"); } });
        action(box,"OpenStreetMap copyright",new Runnable() { public void run() { openUrl("https://www.openstreetmap.org/copyright"); } });
        showDialog(new AlertDialog.Builder(this).setTitle("Sources, privacy & limitations").setView(wrap(box)).setPositiveButton("Close",null).create());
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch (Exception e) { showMessage("No browser available",url); }
    }
    private String stamp(long at) { return Arc7WeatherData.formatTime(at,zone(),units.clock12 ? "MMM d, h:mm a z" : "MMM d, HH:mm z"); }
    private TimeZone zone() { return forecast!=null ? forecast.zone : air!=null ? air.zone : TimeZone.getTimeZone("UTC"); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String s, float size, int color, boolean bold) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private void add(LinearLayout p, String s, float size, int color) {
        TextView t=text(s,size,color,false); t.setPadding(0,dp(4),0,dp(6)); p.addView(t);
    }
    private LinearLayout card(String title) {
        LinearLayout l=Arc7Skin.card(this,pal); add(l,title,12,pal.accent);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(12); content.addView(l,lp); return l;
    }
    private void messageCard(String title, String message) { LinearLayout l=card(title); add(l,message,14,pal.textPrimary); }
    private void metricRow(LinearLayout p, String a, String av, String b, String bv) {
        LinearLayout row=new LinearLayout(this);
        LinearLayout one=column(), two=column(); one.setPadding(0,dp(5),dp(8),dp(5)); two.setPadding(dp(8),dp(5),0,dp(5));
        one.addView(text(a,12,pal.textMuted,false)); one.addView(text(av,17,pal.textPrimary,true));
        two.addView(text(b,12,pal.textMuted,false)); two.addView(text(bv,17,pal.textPrimary,true));
        row.addView(one,new LinearLayout.LayoutParams(0,-2,1)); row.addView(two,new LinearLayout.LayoutParams(0,-2,1)); p.addView(row);
    }
    private Button action(LinearLayout p, String title, final Runnable run) {
        Button b=Arc7Skin.actionButton(this,title,pal.lightMode ? 0xFF0369A1 : 0xFF164E63); b.setTextSize(13);
        b.setMinHeight(dp(48));
        if (run!=null) b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try { run.run(); } catch (Exception e) { showMessage("Weather action failed",Arc7WeatherStore.message(e)); }
        } });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2); lp.rightMargin=dp(4); p.addView(b,lp); return b;
    }
    private LinearLayout dialogBox() { LinearLayout l=column(); l.setPadding(dp(14),dp(8),dp(14),dp(12)); l.setBackgroundColor(pal.bgPage); return l; }
    private ScrollView wrap(LinearLayout l) { ScrollView s=new ScrollView(this); s.addView(l); return s; }
    private EditText input(LinearLayout box, String label, String value, boolean numeric) {
        add(box,label,13,pal.textMuted); EditText e=new EditText(this); e.setSingleLine(true); e.setText(value); e.setTextColor(pal.textPrimary);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        box.addView(e); return e;
    }
    private Spinner spinner(LinearLayout p, String label, String[] values, int index) {
        add(p,label,13,pal.textMuted); Spinner s=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                View v=super.getView(position,convert,parent); ((TextView)v).setTextColor(pal.textPrimary); return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a); s.setSelection(index); p.addView(s,new LinearLayout.LayoutParams(-1,dp(48))); return s;
    }
    private CheckBox check(LinearLayout p, String text, boolean checked) {
        CheckBox c=new CheckBox(this); c.setText(text); c.setTextColor(pal.textPrimary); c.setChecked(checked); p.addView(c); return c;
    }
    private void showMessage(String title, String message) { showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close",null).create()); }
    private void dismissDialog() { if (dialog!=null) { AlertDialog d=dialog; dialog=null; d.dismiss(); } }
    private void showDialog(AlertDialog d) { dismissDialog(); dialog=d; if (!isFinishing()) d.show(); }
}
