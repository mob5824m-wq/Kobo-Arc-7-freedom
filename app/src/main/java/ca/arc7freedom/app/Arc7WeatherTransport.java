package ca.arc7freedom.app;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;

/** Bounded, cancellable HTTPS over Weather's OWN unprivileged TLS engine, not a suite proxy. */
public final class Arc7WeatherTransport implements Arc7WeatherStore.Transport {
    private final Context context;
    private volatile boolean cancelled;
    private HttpURLConnection active;
    private boolean ready;
    public static final String USER_AGENT = "Arc7Weather/2.5.4 (https://github.com/mob5824m-wq/Kobo-Arc-7-freedom)";

    public Arc7WeatherTransport(Context context) {
        this.context = context.getApplicationContext();
        if (!"ca.arc7freedom.weather".equals(this.context.getPackageName()))
            throw new IllegalArgumentException("Weather requests must run inside the independent Weather app.");
    }
    @Override public String get(String url) throws Exception { return new String(bytes(url, 2 * 1024 * 1024), "UTF-8"); }

    public static boolean allowed(String address) {
        try {
            URL u = new URL(address);
            if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return false;
            String host = u.getHost().toLowerCase(Locale.US);
            return host.equals("api.open-meteo.com") || host.equals("air-quality-api.open-meteo.com")
                    || host.equals("geocoding-api.open-meteo.com") || host.equals("api.weather.gov")
                    || host.equals("api.weather.gc.ca") || host.equals("api.rainviewer.com")
                    || host.equals("tilecache.rainviewer.com") || host.equals("tile.openstreetmap.org");
        } catch (Exception badUrl) { return false; }
    }

    public byte[] bytes(String address, int limit) throws Exception {
        check();
        if (!allowed(address)) throw new IOException("Unsupported weather service URL");
        if (!ready) {
            RootShell shell = new RootShell(context);
            // This method only starts an owning-UID engine in standalone apps. No su/CA installation.
            ready = new NetworkCertManager(context, shell).startBrowserGateway();
            check();
            if (!ready) throw new IOException("Weather's TLS engine could not start. Reinstall Weather.apk if needed.");
        }
        long deadline = SystemClock.elapsedRealtime() + 18000L;
        String url = address;
        for (int hop = 0; hop < 4; hop++) {
            check();
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Weather request timed out");
            if (!allowed(url)) throw new IOException("Weather service redirected to an unsupported host");
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + AppNetworkScope.gatewayPort()
                    + "/__https/" + url.substring(8)).openConnection(Proxy.NO_PROXY);
            synchronized (this) { check(); active = c; }
            try {
                c.setConnectTimeout((int)Math.max(1,Math.min(4000,deadline-SystemClock.elapsedRealtime())));
                c.setReadTimeout((int)Math.max(1,Math.min(10000,deadline-SystemClock.elapsedRealtime())));
                c.setInstanceFollowRedirects(false); c.setUseCaches(false);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "application/geo+json,application/json,image/png;q=0.9,*/*;q=0.1");
                c.setRequestProperty("Accept-Encoding", "identity");
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Weather redirect has no destination");
                    url = new URL(new URL(url), location).toString(); continue;
                }
                if (status != 200) throw new IOException("Weather service HTTP " + status + (status == 429 ? " (rate limit; try later)" : ""));
                if (c.getContentLength() > limit) throw new IOException("Weather response exceeded the safe size limit");
                InputStream in = c.getInputStream();
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        check();
                        if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Weather request timed out");
                        if (out.size() + n > limit) throw new IOException("Weather response exceeded the safe size limit");
                        out.write(buffer, 0, n);
                    }
                    check(); return out.toByteArray();
                } finally { in.close(); }
            } finally {
                synchronized (this) { if (active == c) active = null; }
                c.disconnect();
            }
        }
        throw new IOException("Too many weather service redirects");
    }
    private void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Weather request cancelled");
    }
    @Override public boolean cancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    @Override public synchronized void cancel() {
        cancelled = true;
        if (active != null) active.disconnect();
    }
}
