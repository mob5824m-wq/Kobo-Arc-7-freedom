package ca.arc7freedom.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Web Mercator math and validated past-only RainViewer metadata, separated for host tests. */
public final class Arc7WeatherRadarMath {
    private Arc7WeatherRadarMath() {}
    public static final class Frame {
        public final long time;
        public final String path;
        Frame(long time, String path) { this.time=time; this.path=path; }
        public String tile(int z, int x, int y) {
            if (z<1 || z>7 || y<0 || y>=(1<<z)) throw new IllegalArgumentException("Radar tile out of range");
            return "https://tilecache.rainviewer.com" + path + "/256/"+z+"/"+wrap(x,z)+"/"+y+"/2/1_1.png";
        }
    }
    public static List<Frame> frames(String json, long now) throws Exception {
        JSONObject root=Arc7WeatherData.checkedObject(json);
        String host=Arc7WeatherData.text(root,"host");
        if (!"https://tilecache.rainviewer.com".equals(host)) throw new IllegalArgumentException("Unknown radar tile provider");
        JSONObject radar=root.optJSONObject("radar");
        JSONArray past=radar==null ? null : radar.optJSONArray("past");
        List<Frame> result=new ArrayList<Frame>();
        if (past!=null) for (int i=0; i<past.length() && i<100; i++) {
            JSONObject f=past.optJSONObject(i);
            long at=Arc7WeatherData.time(f==null ? null : f.opt("time"),java.util.TimeZone.getTimeZone("UTC"));
            String path=Arc7WeatherData.text(f,"path");
            if (at>0 && at<=now+300000L && path.matches("/v2/radar/[0-9]+")) result.add(new Frame(at,path));
        }
        Collections.sort(result,new Comparator<Frame>() {
            @Override public int compare(Frame a, Frame b) { return a.time<b.time ? -1 : a.time==b.time ? 0 : 1; }
        });
        if (result.isEmpty()) throw new IllegalArgumentException("No past radar frames available");
        if (result.size()>6) return new ArrayList<Frame>(result.subList(result.size()-6,result.size()));
        return result;
    }
    public static double x(double longitude, int z) { return (longitude+180)/360*(1<<z); }
    public static double y(double latitude, int z) {
        double r=Math.toRadians(Math.max(-85.05112878,Math.min(85.05112878,latitude)));
        return (1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*(1<<z);
    }
    public static double longitude(double x, int z) { return wrapDegrees(x/(1<<z)*360-180); }
    public static double latitude(double y, int z) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*Math.max(0,Math.min(1<<z,y))/(1<<z)))));
    }
    public static int wrap(int x, int z) { int n=1<<z; return ((x%n)+n)%n; }
    public static double wrapDegrees(double x) { return ((x+180)%360+360)%360-180; }
}
