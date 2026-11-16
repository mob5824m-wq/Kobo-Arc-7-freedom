package ca.arc7freedom.app;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Android 4.0–4.2 stores per-network proxies separately from Settings' global proxy. */
final class LegacyWifiProxyCleanup {
    private LegacyWifiProxyCleanup() {}

    static boolean isLegacy(String host, int port) {
        return port == 8118 && host != null
                && ("127.0.0.1".equals(host.trim()) || "localhost".equalsIgnoreCase(host.trim()));
    }

    /** Read the configured global proxy as well as the active proxy. A successful Wi-Fi
     * update is not evidence that an independently configured global proxy was removed.
     * Global was introduced in API 17; older supported ROMs keep these keys in Secure.
     * Reflection avoids linking Settings.Global on Android 4.0/4.1. Unreadable is pending,
     * never success, and a stale active-proxy cache is handled conservatively on retry. */
    static boolean globalAndActiveProxyAreClear(Context context) {
        try {
            Class<?> table;
            try { table = Class.forName("android.provider.Settings$Global"); }
            catch (ClassNotFoundException olderAndroid) {
                table = Class.forName("android.provider.Settings$Secure");
            }
            Method get = table.getMethod("getString", android.content.ContentResolver.class, String.class);
            Object resolver = context.getContentResolver();
            String proxy = (String) get.invoke(null, resolver, "http_proxy");
            String host = (String) get.invoke(null, resolver, "global_http_proxy_host");
            String port = (String) get.invoke(null, resolver, "global_http_proxy_port");
            if (isLegacyConfiguration(proxy, host, port)) return false;
            return !isLegacy(android.net.Proxy.getDefaultHost(), android.net.Proxy.getDefaultPort());
        } catch (Throwable deniedOrDifferentRom) {
            return false;
        }
    }

    static boolean isLegacyConfiguration(String proxy, String host, String port) {
        String value = proxy == null ? "" : proxy.trim();
        if ("127.0.0.1:8118".equals(value) || "localhost:8118".equalsIgnoreCase(value)) return true;
        // A real http_proxy takes precedence over the old split host/port representation.
        if (!(value.length() == 0 || "null".equals(value) || ":0".equals(value))) return false;
        return "8118".equals(port == null ? "" : port.trim()) && isLegacy(host, 8118);
    }

    static final class Result {
        int matched;
        int cleared;
        boolean inspectionUnavailable;
        boolean saveFailed;
        boolean complete() { return !inspectionUnavailable && !saveFailed && matched == cleared; }
    }

    static Result clear(Context context) {
        Result result = new Result();
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) { result.inspectionUnavailable = true; return result; }
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs == null) { result.inspectionUnavailable = true; return result; }
            for (WifiConfiguration config : configs) {
                try {
                    // Hidden on API 15–17, but present on the stock Kobo ROM. Change only
                    // proxy fields; keep SSID, credentials, static IP/DNS and networkId intact.
                    Field linkField = config.getClass().getField("linkProperties");
                    Object links = linkField.get(config);
                    if (links == null) continue;
                    Object proxy = links.getClass().getMethod("getHttpProxy").invoke(links);
                    if (proxy == null) continue;
                    String host = (String) proxy.getClass().getMethod("getHost").invoke(proxy);
                    int port = ((Number) proxy.getClass().getMethod("getPort").invoke(proxy)).intValue();
                    if (!isLegacy(host, port)) continue;
                    result.matched++;
                    Field setting = config.getClass().getField("proxySettings");
                    Object none = null;
                    for (Object value : setting.getType().getEnumConstants()) {
                        if ("NONE".equals(value.toString())) none = value;
                    }
                    if (none == null) continue;
                    Method setter = links.getClass().getMethod("setHttpProxy", proxy.getClass());
                    setter.invoke(links, new Object[]{null});
                    setting.set(config, none);
                    if (wifi.updateNetwork(config) >= 0) result.cleared++;
                } catch (Throwable deniedOrDifferentRom) {
                    result.inspectionUnavailable = true;
                }
            }
            if (result.cleared > 0 && !wifi.saveConfiguration()) result.saveFailed = true;
            // Re-read the framework's copies instead of trusting modified Java objects.
            if (result.cleared > 0) {
                List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
                if (saved == null) result.inspectionUnavailable = true;
                else for (WifiConfiguration config : saved) {
                    Object links = config.getClass().getField("linkProperties").get(config);
                    if (links == null) continue;
                    Object proxy = links.getClass().getMethod("getHttpProxy").invoke(links);
                    if (proxy != null && isLegacy(
                            (String) proxy.getClass().getMethod("getHost").invoke(proxy),
                            ((Number) proxy.getClass().getMethod("getPort").invoke(proxy)).intValue())) {
                        result.saveFailed = true;
                    }
                }
            }
        } catch (Throwable deniedOrDifferentRom) {
            result.inspectionUnavailable = true;
        }
        return result;
    }
}
