package ca.arc7freedom.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native past-radar and short-range nowcast map, max zoom 7, loaded only on demand. */
public final class Arc7WeatherRadar extends LinearLayout {
    private static final Object CACHE_LOCK=new Object();
    private static long cacheEpoch;
    private final long diskEpoch;
    private final Arc7WeatherData.Place place;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ImageView image;
    private final TextView status;
    private final Button play;
    private volatile List<Arc7WeatherRadarMath.Frame> frames;
    private Arc7WeatherTransport transport;
    private Future<?> task;
    private boolean closed, playing;
    private int sequence, position, zoom=5;
    private double centreLat, centreLon;
    private float downX, downY;
    private final Runnable advance=new Runnable() {
        @Override public void run() {
            if (!playing || closed || frames==null) return;
            if (position >= frames.size()-1) {
                boolean hasNowcast=false;
                for (Arc7WeatherRadarMath.Frame frame : frames) if (frame.nowcast) { hasNowcast=true; break; }
                if (hasNowcast) { stop(); return; }
                position=0;
            } else position++;
            load(false);
        }
    };
    public Arc7WeatherRadar(Context c, Arc7WeatherData.Place place, Arc7WeatherData.Units u, Arc7Skin.Palette pal) {
        super(c); this.place=place; centreLat=place.lat; centreLon=place.lon;
        synchronized (CACHE_LOCK) { diskEpoch=cacheEpoch; }
        setOrientation(VERTICAL);
        TextView help=label("PRECIPITATION RADAR + SHORT-RANGE NOWCAST\nRainViewer \u00b7 past observations followed by provider nowcast frames when available. A nowcast is an estimate, not a guaranteed rain forecast.\nBlank/transparent areas can mean no echo OR no radar coverage. Never use this map as an all-clear.",pal.textPrimary,13);
        addView(help);
        LinearLayout buttons=new LinearLayout(c); buttons.setGravity(Gravity.CENTER_VERTICAL);
        button(buttons,"Previous",new Runnable() { public void run() { step(-1); } });
        play=button(buttons,"Play",new Runnable() {
            public void run() { playing=!playing; play.setText(playing ? "Pause" : "Play"); ui.removeCallbacks(advance); if (playing) ui.post(advance); }
        });
        button(buttons,"Next",new Runnable() { public void run() { step(1); } });
        button(buttons,"Zoom +",new Runnable() { public void run() { if (zoom<7) { stop(); zoom++; load(false); } } });
        button(buttons,"Zoom -",new Runnable() { public void run() { if (zoom>2) { stop(); zoom--; load(false); } } });
        button(buttons,"Recenter",new Runnable() { public void run() { stop(); centreLat=Arc7WeatherRadar.this.place.lat; centreLon=Arc7WeatherRadar.this.place.lon; load(false); } });
        button(buttons,"Reload",new Runnable() { public void run() { stop(); frames=null; load(true); } });
        HorizontalScrollView scroll=new HorizontalScrollView(c); scroll.addView(buttons); addView(scroll);
        status=label("Loading radar metadata\u2026",pal.textMuted,12); addView(status);
        image=new ImageView(c); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFFE2E8F0);
        image.setContentDescription("Radar map. Drag to pan; use zoom and frame buttons. Red dot marks your saved location when visible.");
        addView(image,new LayoutParams(LayoutParams.MATCH_PARENT,Arc7Skin.dp(c,330)));
        image.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction()==MotionEvent.ACTION_DOWN) {
                    downX=e.getX(); downY=e.getY(); v.getParent().requestDisallowInterceptTouchEvent(true); return true;
                }
                if (e.getAction()==MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    float dx=e.getX()-downX, dy=e.getY()-downY;
                    if (Math.abs(dx)+Math.abs(dy)>18) {
                        double displayed=Math.max(1,Math.min(image.getWidth(),image.getHeight()));
                        centreLon=Arc7WeatherRadarMath.longitude(Arc7WeatherRadarMath.x(centreLon,zoom)-dx/displayed*2,zoom);
                        centreLat=Arc7WeatherRadarMath.latitude(Arc7WeatherRadarMath.y(centreLat,zoom)-dy/displayed*2,zoom);
                        stop(); load(false);
                    }
                    return true;
                }
                if (e.getAction()==MotionEvent.ACTION_CANCEL) v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        });
        addView(label("Drag map to pan. Red dot: saved city. Previous/Next includes future nowcast frames when supplied. Zoom limited to 7.\nRadar \u00a9 RainViewer \u00b7 Map data \u00a9 OpenStreetMap contributors (ODbL).\nInteractive requests send the viewed map area to these providers. Cached tiles are private and capped at 12 MB.",pal.textMuted,11));
        load(false);
    }
    private TextView label(String text, int color, float size) {
        TextView t=new TextView(getContext()); t.setText(text); t.setTextColor(color); t.setTextSize(size);
        t.setPadding(0,Arc7Skin.dp(getContext(),7),0,Arc7Skin.dp(getContext(),7)); return t;
    }
    private Button button(LinearLayout row, String text, final Runnable action) {
        Button b=Arc7Skin.actionButton(getContext(),text,0xFF0369A1); b.setTextSize(12);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        row.addView(b); return b;
    }
    private void stop() { playing=false; play.setText("Play"); ui.removeCallbacks(advance); }
    private void step(int n) {
        stop(); if (frames==null) return; position=(position+n+frames.size())%frames.size(); load(false);
    }
    public void close() {
        closed=true; sequence++; stop();
        if (transport!=null) transport.cancel(); if (task!=null) task.cancel(true);
        worker.shutdownNow(); ui.removeCallbacksAndMessages(null); image.setImageDrawable(null);
    }
    private void load(final boolean forceMetadata) {
        if (closed) return;
        if (transport!=null) transport.cancel(); if (task!=null) task.cancel(true);
        final int token=++sequence, z=zoom, requestedPosition=position;
        final boolean firstFrame=frames==null;
        final List<Arc7WeatherRadarMath.Frame> previousFrames=frames;
        final double lat=centreLat, lon=centreLon;
        final Arc7WeatherTransport net=new Arc7WeatherTransport(getContext()); transport=net;
        status.setText("Loading past radar\u2026");
        task=worker.submit(new Runnable() {
            @Override public void run() {
                try {
                    List<Arc7WeatherRadarMath.Frame> available=previousFrames;
                    boolean oldMetadata=false;
                    if (available==null || forceMetadata) {
                        File meta=new File(directory(getContext()),"metadata.json"); String raw;
                        long age=System.currentTimeMillis()-meta.lastModified();
                        if (!forceMetadata && meta.isFile() && age>=0 && age<300000L) raw=new String(read(meta,262144),"UTF-8");
                        else try {
                            raw=net.get("https://api.rainviewer.com/public/weather-maps.json");
                            Arc7WeatherRadarMath.frames(raw,System.currentTimeMillis());
                            cacheWrite(meta,raw.getBytes("UTF-8"),net);
                        } catch (Exception offline) {
                            if (net.cancelled() || !meta.isFile()) throw offline;
                            raw=new String(read(meta,262144),"UTF-8"); oldMetadata=true;
                        }
                        available=Arc7WeatherRadarMath.frames(raw,System.currentTimeMillis());
                    }
                    final List<Arc7WeatherRadarMath.Frame> selectedFrames=available;
                    final int at=Math.max(0,Math.min(requestedPosition,available.size()-1));
                    int currentPosition=available.size()-1;
                    for (int i=0;i<available.size();i++) if (available.get(i).nowcast) { currentPosition=i-1; break; }
                    if (currentPosition<0) currentPosition=0;
                    final Arc7WeatherRadarMath.Frame frame=available.get(firstFrame ? currentPosition : at);
                    final int selectedPosition=available.indexOf(frame);
                    Bitmap map=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(map); canvas.drawColor(0xFFE2E8F0);
                    double cx=Arc7WeatherRadarMath.x(lon,z), cy=Arc7WeatherRadarMath.y(lat,z);
                    int left=(int)Math.floor(cx-1), top=(int)Math.floor(cy-1), baseCount=0, radarCount=0, count=0;
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); long deadline=SystemClock.elapsedRealtime()+35000L;
                    for (int row=top; row<=top+2; row++) for (int col=left; col<=left+2; col++) {
                        if (net.cancelled()) return;
                        if (row<0 || row>=(1<<z)) continue;
                        count++;
                        float x=(float)((col-cx)*256+256), y=(float)((row-cy)*256+256);
                        if (SystemClock.elapsedRealtime()>deadline) continue;
                        try {
                            Bitmap tile=tile(net,"https://tile.openstreetmap.org/"+z+"/"+Arc7WeatherRadarMath.wrap(col,z)+"/"+row+".png");
                            canvas.drawBitmap(tile,x,y,paint); tile.recycle(); baseCount++;
                        } catch (Exception missingBase) { if (net.cancelled()) return; }
                        if (SystemClock.elapsedRealtime()>deadline) continue;
                        try {
                            Bitmap tile=tile(net,frame.tile(z,col,row));
                            paint.setAlpha(190); canvas.drawBitmap(tile,x,y,paint); paint.setAlpha(255); tile.recycle(); radarCount++;
                        } catch (Exception missingRadar) { if (net.cancelled()) return; }
                    }
                    double px=Arc7WeatherRadarMath.x(place.lon,z)-cx;
                    if (px>(1<<z)/2.0) px-=1<<z; if (px<-(1<<z)/2.0) px+=1<<z;
                    float mx=(float)(256+px*256), my=(float)(256+(Arc7WeatherRadarMath.y(place.lat,z)-cy)*256);
                    paint.setColor(0xFFFFFFFF); canvas.drawCircle(mx,my,8,paint); paint.setColor(0xFFDC2626); canvas.drawCircle(mx,my,5,paint);
                    final Bitmap result=map;
                    final boolean complete=baseCount==count && radarCount==count;
                    final String message=(oldMetadata ? "CACHED metadata \u00b7 " : "")
                            + (frame.nowcast ? "NOWCAST estimate \u00b7 " : (System.currentTimeMillis()-frame.time>1800000L ? "STALE radar \u00b7 " : "Past radar \u00b7 "))
                            + Arc7WeatherData.formatTime(frame.time,java.util.TimeZone.getTimeZone("UTC"),"MMM d HH:mm")+" UTC"
                            + " \u00b7 frame "+(selectedPosition+1)+"/"+available.size()+" \u00b7 zoom "+z
                            + (complete ? "" : "\nPARTIAL / UNAVAILABLE tiles: base "+baseCount+"/"+count+", radar "+radarCount+"/"+count)
                            + "\nMap centre: "+Arc7WeatherData.coordinates(lat,lon);
                    ui.post(new Runnable() { @Override public void run() {
                        if (closed || token!=sequence) return;
                        frames=selectedFrames; position=selectedPosition; image.setImageBitmap(result); status.setText(message);
                        if (playing && complete) ui.postDelayed(advance,1500L); else if (!complete) stop();
                    }});
                } catch (final Exception error) {
                    ui.post(new Runnable() { @Override public void run() {
                        if (!closed && token==sequence) { stop(); status.setText("Radar unavailable: "+Arc7WeatherStore.message(error)+". Use Reload to retry. Any previous image is not current."); }
                    }});
                } finally { net.cancel(); }
            }
        });
    }
    private Bitmap tile(Arc7WeatherTransport net, String url) throws Exception {
        StringBuilder name=new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"))) name.append(String.format(java.util.Locale.US,"%02x",b&255));
        File f=new File(directory(getContext()),name+".png");
        byte[] data;
        // Tile URLs are immutable for radar frames. OSM tiles cached for at least seven days.
        long age=System.currentTimeMillis()-f.lastModified();
        if (f.isFile() && age>=0 && age<7*24*Arc7WeatherData.HOUR) data=read(f,1024*1024);
        else {
            try { data=net.bytes(url,1024*1024); }
            catch (Exception offline) { if (net.cancelled() || !f.isFile()) throw offline; data=read(f,1024*1024); }
        }
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if (bounds.outWidth!=256 || bounds.outHeight!=256) throw new IllegalArgumentException("Invalid radar/map tile image");
        Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
        if (bitmap==null) throw new IllegalArgumentException("Cannot decode map tile");
        if (!f.isFile() || age>=7*24*Arc7WeatherData.HOUR) { cacheWrite(f,data,net); }
        return bitmap;
    }
    public static File directory(Context c) {
        File d=new File(c.getCacheDir(),"weather_radar");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("Cannot create private radar cache"); return d;
    }
    public static void clearCache(Context c) {
        synchronized (CACHE_LOCK) {
            cacheEpoch++;
            File[] files=directory(c).listFiles();
            if (files!=null) for (File f : files) if (!f.delete() && f.exists())
                throw new IllegalStateException("Could not remove a radar cache file. Check storage permissions.");
        }
    }
    private void cacheWrite(File file, byte[] bytes, Arc7WeatherTransport net) throws Exception {
        synchronized (CACHE_LOCK) {
            if (net.cancelled() || diskEpoch!=cacheEpoch) return;
            trim(directory(getContext())); write(file,bytes);
        }
    }
    private static void trim(File d) {
        File[] files=d.listFiles(); if (files==null) return; long size=0;
        for (File f : files) size+=f.length();
        if (size<11*1024*1024) return;
        Arrays.sort(files,new Comparator<File>() { public int compare(File a, File b) { return a.lastModified()<b.lastModified() ? -1 : a.lastModified()==b.lastModified() ? 0 : 1; } });
        for (File f : files) { long bytes=f.length(); if (f.delete()) size-=bytes; if (size<9*1024*1024) break; }
    }
    private static byte[] read(File f, int limit) throws Exception {
        if (f.length()>limit) throw new IllegalArgumentException("Oversized radar cache entry");
        FileInputStream in=new FileInputStream(f);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
            while ((n=in.read(b))!=-1) { if (out.size()+n>limit) throw new IllegalArgumentException("Oversized radar cache entry"); out.write(b,0,n); }
            return out.toByteArray();
        } finally { in.close(); }
    }
    private static void write(File f, byte[] bytes) throws Exception {
        File temp=new File(f.getParentFile(),f.getName()+".tmp");
        FileOutputStream out=new FileOutputStream(temp);
        try { out.write(bytes); } finally { out.close(); }
        if (!temp.renameTo(f)) { temp.delete(); throw new IllegalStateException("Could not save radar cache"); }
    }
}
