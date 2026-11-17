package ca.arc7freedom.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** Small native charts/icons: no WebView, emoji font, graphics library or modern Android dependency. */
public final class Arc7WeatherViews {
    private Arc7WeatherViews() {}
    public interface Pick { void hour(int index); }

    public static final class Chart extends View {
        private final List<Arc7WeatherData.Hour> hours;
        private final Arc7WeatherData.Forecast forecast;
        private final Arc7WeatherData.Units units;
        private final Arc7Skin.Palette palette;
        private final int mode; // temperature, precipitation chance, wind
        private final Pick pick;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density, scaled;
        public Chart(Context c, List<Arc7WeatherData.Hour> h, Arc7WeatherData.Forecast f,
                     Arc7WeatherData.Units u, Arc7Skin.Palette pal, int mode, Pick pick) {
            super(c); hours=h; forecast=f; units=u; palette=pal; this.mode=mode; this.pick=pick;
            density=c.getResources().getDisplayMetrics().density; scaled=c.getResources().getDisplayMetrics().scaledDensity;
            setMinimumHeight((int)(180*density)); setFocusable(true); setClickable(true);
            StringBuilder a = new StringBuilder(mode==0 ? "Temperature chart. " : mode==1 ? "Precipitation chance chart. " : "Wind chart. ");
            for (int i=0; i<h.size(); i+=3) a.append(u.time(h.get(i).time,f.zone)).append(": ").append(label(value(h.get(i)))).append(". ");
            a.append("The hourly list below provides the same measurements and detailed accessible buttons.");
            setContentDescription(a.toString());
        }
        private double value(Arc7WeatherData.Hour h) { return mode==0 ? h.temp : mode==1 ? h.probability : h.wind; }
        private String label(double n) { return mode==0 ? units.temp(n) : mode==1 ? Arc7WeatherData.pct(n) : units.speed(n); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float left=62*density, right=getWidth()-12*density, top=18*density, bottom=getHeight()-34*density;
            if (hours.size()<2 || right<=left) return;
            double min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
            for (Arc7WeatherData.Hour h : hours) if (Arc7WeatherData.finite(value(h))) { min=Math.min(min,value(h)); max=Math.max(max,value(h)); }
            p.setTextSize(11*scaled); p.setStrokeWidth(density);
            if (!Arc7WeatherData.finite(min)) { p.setColor(palette.textMuted); c.drawText("Chart data unavailable",left,top+35*density,p); return; }
            if (mode==1) { min=0; max=100; }
            else if (mode==2) { min=0; max=Math.max(10,max*1.1); }
            else { double pad=Math.max(2,(max-min)*0.15); min-=pad; max+=pad; }
            for (int i=0; i<=3; i++) {
                float y=top+(bottom-top)*i/3;
                p.setColor(palette.border); c.drawLine(left,y,right,y,p);
                p.setColor(palette.textMuted); c.drawText(label(max-(max-min)*i/3),0,y+4*density,p);
            }
            Path line=new Path(); boolean started=false;
            float spacing=(right-left)/(hours.size()-1);
            for (int i=0; i<hours.size(); i++) {
                double v=value(hours.get(i));
                if (!Arc7WeatherData.finite(v)) { started=false; continue; }
                float x=left+i*spacing, y=(float)(bottom-(v-min)/(max-min)*(bottom-top));
                if (mode==1) {
                    p.setColor(palette.accent); c.drawRect(x-spacing*0.32f,y,x+spacing*0.32f,bottom,p);
                } else {
                    if (!started) line.moveTo(x,y); else line.lineTo(x,y); started=true;
                    p.setColor(palette.accentSecondary); c.drawCircle(x,y,2.6f*density,p);
                }
            }
            if (mode!=1) { p.setColor(palette.accent); p.setStrokeWidth(2*density); p.setStyle(Paint.Style.STROKE); c.drawPath(line,p); p.setStyle(Paint.Style.FILL); }
            p.setColor(palette.textMuted); p.setTextSize(11*scaled);
            int step=Math.max(1,hours.size()/4);
            for (int i=0; i<hours.size(); i+=step) {
                String s=units.time(hours.get(i).time,forecast.zone);
                float x=left+i*spacing-p.measureText(s)/2;
                c.drawText(s,Math.min(right-p.measureText(s),Math.max(left,x)),getHeight()-8*density,p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction()==MotionEvent.ACTION_UP) {
                float left=62*density, width=getWidth()-74*density;
                int i=Math.round((e.getX()-left)/Math.max(1,width)*(hours.size()-1));
                if (pick!=null && !hours.isEmpty()) pick.hour(Math.max(0,Math.min(hours.size()-1,i)));
                performClick(); return true;
            }
            return true;
        }
        @Override public boolean performClick() { return super.performClick(); }
    }

    public static final class Icon extends View {
        private final int code, day;
        private final Arc7Skin.Palette pal;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Icon(Context c, int code, int isDay, Arc7Skin.Palette pal) {
            super(c); this.code=code; day=isDay; this.pal=pal;
            setContentDescription(Arc7WeatherData.description(code)+(day==0 ? ", night" : ""));
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save();
            float scale=Math.min(getWidth(),getHeight())/100f;
            c.translate((getWidth()-100*scale)/2,(getHeight()-100*scale)/2); c.scale(scale,scale);
            if (code<0) {
                p.setColor(pal.textMuted); p.setTextSize(45); c.drawText("?",37,65,p); c.restore(); return;
            }
            if (code<=2) {
                p.setColor(day==0 ? 0xFFA5B4FC : 0xFFFBBF24); c.drawCircle(43,37,20,p);
                if (day==0) { p.setColor(pal.surface); c.drawCircle(53,29,18,p); }
                else {
                    p.setStrokeWidth(3);
                    for (int i=0; i<8; i++) {
                        double a=i*Math.PI/4;
                        c.drawLine(43+(float)Math.cos(a)*26,37+(float)Math.sin(a)*26,
                                43+(float)Math.cos(a)*32,37+(float)Math.sin(a)*32,p);
                    }
                }
                if (code<2) { c.restore(); return; }
            }
            p.setColor(pal.lightMode ? 0xFF94A3B8 : 0xFFCBD5E1);
            c.drawCircle(38,47,17,p); c.drawCircle(57,40,21,p); c.drawCircle(76,50,14,p);
            c.drawRoundRect(new RectF(23,47,90,65),9,9,p);
            if (code==45 || code==48) {
                p.setColor(pal.textMuted); p.setStrokeWidth(4);
                c.drawLine(24,75,84,75,p); c.drawLine(32,84,76,84,p);
            } else if (code>=51) {
                p.setColor(pal.accent); p.setStrokeWidth(3);
                boolean snow=(code>=71 && code<=77) || code==85 || code==86;
                if (code>=95) {
                    p.setColor(0xFFFBBF24); Path bolt=new Path();
                    bolt.moveTo(56,65); bolt.lineTo(44,82); bolt.lineTo(56,80); bolt.lineTo(48,96); bolt.lineTo(70,74); bolt.lineTo(57,76); bolt.close(); c.drawPath(bolt,p);
                } else for (int i=0; i<3; i++) {
                    float x=34+i*20;
                    if (snow) { c.drawLine(x-4,79,x+4,79,p); c.drawLine(x,75,x,83,p); c.drawLine(x-3,76,x+3,82,p); }
                    else c.drawLine(x,73,x-4,84,p);
                }
            }
            c.restore();
        }
    }
}
