package ca.arc7freedom.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Process-local network identity. Shared source code must never mean shared sockets/UIDs. */
final class AppNetworkScope {
    static final String SUITE_PACKAGE = "ca.arc7freedom.app";
    static final int LEGACY_SUITE_PORT = 8118;
    private static String packageName = SUITE_PACKAGE;

    private AppNetworkScope() {}

    /** Called by Application before any activity, service or receiver is created. */
    static void initialize(String ownPackage) {
        packageName = ownPackage;
        if (!isSuite()) installIndependentProxySelector();
    }

    static String ownPackage() { return packageName; }

    static boolean isSuite() { return SUITE_PACKAGE.equals(packageName); }

    static int gatewayPort() { return gatewayPortFor(packageName); }

    static int browserPort() {
        return isSuite() ? 18119 : gatewayPort() + 1;
    }

    /**
     * Stable across installs/updates; never use a list index (adding an app would move ports).
     * Reserve an even/odd pair per package. The regression test rejects collisions between
     * all published packages. Port 8118 is retained ONLY for explicitly supported legacy apps.
     */
    static int gatewayPortFor(String pkg) {
        if (SUITE_PACKAGE.equals(pkg)) return LEGACY_SUITE_PORT;
        if (pkg == null || !pkg.startsWith("ca.arc7freedom.")) {
            throw new IllegalArgumentException("Not an Arc7 application package");
        }
        return 22000 + (pkg.hashCode() & 0x7fffffff) % 16000 * 2;
    }

    /**
     * Old installs may still have the suite's global proxy selected. Do not send a
     * standalone app's requests through it, or recursively proxy loopback traffic.
     * Preserve unrelated school/work proxies and explicitly supplied Proxy objects.
     */
    private static void installIndependentProxySelector() {
        final ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                if (uri == null) throw new IllegalArgumentException("uri");
                if (isLoopback(uri.getHost()) || previous == null) {
                    return Collections.singletonList(Proxy.NO_PROXY);
                }
                List<Proxy> selected = previous.select(uri);
                List<Proxy> independent = new ArrayList<Proxy>();
                if (selected != null) for (Proxy proxy : selected) {
                    SocketAddress address = proxy.address();
                    if (address instanceof InetSocketAddress) {
                        InetSocketAddress inet = (InetSocketAddress) address;
                        if (inet.getPort() == LEGACY_SUITE_PORT && isLoopback(inet.getHostName())) {
                            continue;
                        }
                    }
                    independent.add(proxy);
                }
                return independent.isEmpty() ? Collections.singletonList(Proxy.NO_PROXY) : independent;
            }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException error) {
                if (previous != null) previous.connectFailed(uri, sa, error);
            }
        });
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
