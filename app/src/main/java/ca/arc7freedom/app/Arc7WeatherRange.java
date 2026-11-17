package ca.arc7freedom.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Daily low/high range on one common Celsius scale (also correct when labels use Fahrenheit). */
final class Arc7WeatherRange extends View {
    private final double low, high, min, max;
    private final Arc7Skin.Palette pal;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    Arc7WeatherRange(Context c, double low, double high, double min, double max, Arc7Skin.Palette p) {
        super(c); this.low=low; this.high=high; this.min=min; this.max=max; pal=p;
        setContentDescription("Daily temperature range; numerical high and low are shown above.");
    }
    @Override protected void onDraw(Canvas canvas) {
        float start=getHeight()/2f, width=Math.max(1,getWidth()-2*start), y=getHeight()/2f;
        paint.setStrokeWidth(Math.max(3,getHeight()*0.45f)); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(pal.border); canvas.drawLine(start,y,start+width,y,paint);
        paint.setColor(pal.accent);
        canvas.drawLine(start+(float)((low-min)/(max-min))*width,y,start+(float)((high-min)/(max-min))*width,y,paint);
    }
}
