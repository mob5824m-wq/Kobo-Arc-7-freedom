#!/usr/bin/env python3
"""Offline regression tests for independent APKs, navigation, proxy migration and Bluetooth PAN.

Requires a host JDK (or scripts/bootstrap-toolchain.sh). No emulator, device, external network
or third-party Python packages are required. Android behavior is exercised with small fakes;
this does NOT claim to test physical Bluetooth/ROM support or Android UID traffic counters.

    python3 scripts/test-app-isolation.py
    python3 scripts/test-app-isolation.py --native build/native/host/arc7tls
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import http.server
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
JAVA_SRC = ROOT / "app/src/main/java/ca/arc7freedom/app"
NS = "{http://schemas.android.com/apk/res/android}"


def attr(element, name, default=""):
    return element.get(NS + name, default)


def canonical(package, name):
    return package + name if name.startswith(".") else name


def launcher(application):
    return [activity for activity in application.findall("activity")
            if any(attr(category, "name") == "android.intent.category.LAUNCHER"
                   for category in activity.findall("./intent-filter/category"))]


def check_manifests_and_generation():
    spec = importlib.util.spec_from_file_location("split_generator", ROOT / "scripts/gen-split-apps.py")
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    manifests = sorted((ROOT / "split-apps").glob("*/AndroidManifest.xml"))
    packages, roots = set(), {}
    for path in manifests:
        manifest = ET.parse(path).getroot()
        package = manifest.get("package")
        assert package not in packages
        packages.add(package)
        application = manifest.find("application")
        entries = launcher(application)
        assert len(entries) == 1, path
        roots[package] = canonical(package, attr(entries[0], "name"))

    for path in manifests:
        manifest = ET.parse(path).getroot()
        package = manifest.get("package")
        key = package.rsplit(".", 1)[-1]
        application = manifest.find("application")
        assert attr(manifest, "sharedUserId") == "", path
        assert attr(application, "name") == "ca.arc7freedom.app.Arc7Application", path
        own_activities = {canonical(package, attr(activity, "name"))
                          for activity in application.findall("activity")}
        assert own_activities & set(roots.values()) == {roots[package]}, path
        assert "ca.arc7freedom.app.MainActivity" not in own_activities, path
        for activity in application.findall("activity"):
            assert attr(activity, "taskAffinity").startswith(package + ".task."), path
        for service in application.findall("service"):
            assert attr(service, "exported") == "false", (path, service.attrib)
            assert not attr(service, "process"), path
        permissions = {attr(item, "name") for item in manifest.findall("uses-permission")}
        services = {attr(item, "name") for item in application.findall("service")}
        if key in generator.APP_META:
            assert permissions == {"android.permission." + name
                                   for name in generator.APP_PERMISSIONS[key]}, path
            uses_tls = key in generator.NETWORK_APPS
            assert ("ca.arc7freedom.app.Arc7TlsGatewayService" in services) == uses_tls, path
            assert ("android.permission.INTERNET" in permissions) == uses_tls, path
        if key in ("clock", "calendar"):
            assert "ca.arc7freedom.app.Arc7ScheduleBootReceiver" in {
                attr(receiver, "name") for receiver in application.findall("receiver")}, path
            assert "ca.arc7freedom.app.Arc7AlarmAlertActivity" in own_activities
        if key == "bluetoothinternet":
            for permission in ("BLUETOOTH", "BLUETOOTH_ADMIN", "ACCESS_NETWORK_STATE"):
                assert "android.permission." + permission in permissions
            feature = next(item for item in manifest.findall("uses-feature")
                           if attr(item, "name") == "android.hardware.bluetooth")
            assert attr(feature, "required") == "false"
            assert not services, "Bluetooth must be an OS connection, not an app proxy"

    suite = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    suite_app = suite.find("application")
    assert attr(suite_app, "name") == ".Arc7Application"
    assert [attr(activity, "name") for activity in launcher(suite_app)] == [".MainActivity"]
    aliases = suite_app.findall("activity-alias")
    assert len(aliases) == len(manifests) == 30
    assert all(attr(alias, "targetActivity") == ".SplitAppLaunchActivity" for alias in aliases)
    for activity in suite_app.findall("activity"):
        if canonical("ca.arc7freedom.app", attr(activity, "name")) in roots.values():
            assert attr(activity, "exported") == "false", activity.attrib

    # Re-generate in a disposable tree, not in the checkout. Catch stale output and prove
    # that aliases pointing at the shared forwarder do not lose their individual filters.
    with tempfile.TemporaryDirectory(prefix="arc7-generated-") as directory:
        temporary = Path(directory)
        inputs = [ROOT / "scripts/gen-split-apps.py", ROOT / "app/src/main/AndroidManifest.xml",
                  JAVA_SRC / "SplitApps.java",
                  ROOT / "launcher-android4/src/main/java/ca/arc7freedom/launcher/BuiltinApps.java"]
        inputs += list((ROOT / "split-apps").rglob("*.xml")) + [ROOT / "split-apps/apps.tsv"]
        for source in inputs:
            destination = temporary / source.relative_to(ROOT)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        snapshot = lambda: {str(path.relative_to(temporary)): path.read_bytes()
                            for path in temporary.rglob("*") if path.is_file()}
        before = snapshot()
        for _ in range(2):
            subprocess.run([sys.executable, str(temporary / "scripts/gen-split-apps.py")],
                           check=True, stdout=subprocess.DEVNULL)
            after = snapshot()
            assert before == after, "Generated manifests/registries are stale or not idempotent"
    network = (JAVA_SRC / "NetworkCertManager.java").read_text()
    assert "DEFAULT_PROXY_PORT = AppNetworkScope.gatewayPort()" in network
    assert 'pb.environment().put("ARC7_GATEWAY_OWNER", appContext.getPackageName())' in network
    assert "/proc/[0-9]" not in network and "kill -9" not in network
    assert "if (AppNetworkScope.isSuite() && localCrt.exists() && localKey.exists())" in network, (
        "Standalone apps must not reuse a local MITM CA left by older shared-engine builds")
    optimizer = (JAVA_SRC / "TabletOptimizerManager.java").read_text()
    assert '"pm clear eu.bubu1.fdroidclassic.debug' not in optimizer
    upgrader = (ROOT / "upgrader/src/main/java/ca/arc7freedom/upgrader/UpgraderActivity.java").read_text()
    assert "pm clear eu.bubu1.fdroidclassic.debug" not in upgrader
    assert "/proc/[0-9]" not in upgrader and "kill -9" not in upgrader

    for method in ("installBundledAuroraStore", "installBundledFDroidClassic"):
        body = optimizer.split("public boolean " + method, 1)[1].split("\n    }", 1)[0]
        assert "Arc7TlsGatewayService.ensureStarted" not in body

    install = network.split("public boolean installCertsAndFixNetwork", 1)[1].split(
        "public String syncTimeAndTimezoneAutomatically", 1)[0]
    assert "setSystemWideTls13ProxyEnabled(true" not in install
    assert "migrateLegacySuiteProxy()" in install
    assert '"http://127.0.0.1:8118/__arc7_raw' not in (JAVA_SRC / "Arc7ClockStore.java").read_text()
    print("PASS: 30 independent manifests, scoped permissions/services, forwarding-only aliases, reproducible generation")


def check_companion_transports():
    helper = (ROOT / "common/src/main/java/ca/arc7freedom/net/OwnTlsGateway.java").read_text()
    assert "ProcessBuilder" in helper and "ARC7_GATEWAY_OWNER" in helper
    assert "startService" not in helper and "execRoot" not in helper
    assert 'new File(context.getApplicationInfo().nativeLibraryDir, "libarc7tls.so")' in helper
    assert "AURORA_PORT = 21118" in helper and "FDROID_PORT = 21120" in helper
    aurora = (ROOT / "aurora-store/src/main/java/com/aurora/store/AuroraStoreActivity.java").read_text()
    assert "http://127.0.0.1:21118/__https/" in aurora and "OwnTlsGateway.ensureRunning" in aurora
    assert "127.0.0.1:8118" not in aurora
    patch = (ROOT / "scripts/patch-fdroid-apk.py").read_text()
    assert "OwnTlsGateway.ensureRunning(LAST_APP_CTX" in patch
    assert "lib/armeabi-v7a/libarc7tls.so" in patch and "assets/cacerts/ca-bundle-2026.crt" in patch
    build = (ROOT / "scripts/build-all.sh").read_text()
    assert build.index("native/arc7tls.c") < build.index('"$PY_BIN" scripts/patch-fdroid-apk.py')
    # Execute the actual migration SQL extracted from the injected production Java source.
    expression = patch.split('db.execSQL("UPDATE fdroid_repo SET address = replace(address,', 1)[1].split(");", 1)[0]
    expression = '\"UPDATE fdroid_repo SET address = replace(address,' + expression
    expression = expression.replace("LOCAL_BRIDGE_PREFIX", json.dumps("http://127.0.0.1:21120/__https/"))
    sql = "".join(json.loads(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', expression))
    with sqlite3.connect(":memory:") as database:
        database.execute("CREATE TABLE fdroid_repo (address TEXT, certificate TEXT)")
        old = "http://127.0.0.1:8118/__https/f-droid.org/repo"
        custom = "http://example.test/custom-repo"
        database.executemany("INSERT INTO fdroid_repo VALUES (?, ?)", [(old, "pinned-key"), (custom, "custom-key")])
        database.execute(sql)
        assert database.execute("SELECT * FROM fdroid_repo").fetchall() == [
            ("http://127.0.0.1:21120/__https/f-droid.org/repo", "pinned-key"), (custom, "custom-key")]
    print("PASS: Aurora/F-Droid/fallback VLC own their engines; F-Droid migrates old repo URLs without changing certificates")


def check_proxy_migration():
    source = (JAVA_SRC / "NetworkCertManager.java").read_text()
    body = source.split('String cmd = "_p=$(settings get global http_proxy', 1)[1]
    body = '"_p=$(settings get global http_proxy' + body.split("\n        RootShell.ExecResult result", 1)[0]
    command = "".join(json.loads(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', body))
    with tempfile.TemporaryDirectory(prefix="arc7-proxy-") as directory:
        path = Path(directory)
        settings = path / "settings"
        settings.write_text("#!" + sys.executable + "\n" + '''import json, os, pathlib, sys
args = sys.argv[1:]
state_file = pathlib.Path(os.environ["MOCK_STATE"])
state = json.loads(state_file.read_text()) if state_file.exists() else {
    "http_proxy": os.environ.get("MOCK_PROXY", "null"),
    "global_http_proxy_host": os.environ.get("MOCK_HOST", "null"),
    "global_http_proxy_port": os.environ.get("MOCK_PORT", "null")}
if args[0] == "get":
    if os.environ.get("MOCK_READ_FAIL") == args[2]:
        sys.exit(1)
    print(state.get(args[2], "null"))
else:
    if os.environ.get("MOCK_FAIL") == "1":
        sys.exit(1)
    with open(os.environ["MOCK_LOG"], "a") as log:
        log.write(" ".join(args) + "\\n")
    if os.environ.get("MOCK_NO_EFFECT") != "1":
        if args[0] == "put":
            state[args[2]] = args[3]
        elif args[0] == "delete":
            state.pop(args[2], None)
    state_file.write_text(json.dumps(state))
''')
        settings.chmod(0o755)
        cases = [
            ("null", "null", "null", False),
            (":0", "", "", False),
            ("127.0.0.1:8118", "127.0.0.1", "8118", True),
            ("localhost:8118", "", "", True),
            ("LOCALHOST:8118", "", "", True),
            ("null", "127.0.0.1", "8118", True),
            ("proxy.school.test:3128", "proxy.school.test", "3128", False),
            ("proxy.school.test:3128", "127.0.0.1", "8118", False),
            ("127.0.0.1:9000", "127.0.0.1", "9000", False),
        ]
        for index, (proxy, host, port, clear) in enumerate(cases):
            log = path / (str(index) + ".log")
            env = dict(os.environ, PATH=str(path) + os.pathsep + os.environ.get("PATH", ""),
                       MOCK_PROXY=proxy, MOCK_HOST=host, MOCK_PORT=port, MOCK_FAIL="0", MOCK_LOG=str(log),
                       MOCK_STATE=str(path / (str(index) + ".json")))
            result = subprocess.run(["sh", "-c", command], env=env, text=True, capture_output=True, check=True)
            changes = log.read_text().splitlines() if log.exists() else []
            assert len(changes) == (3 if clear else 0), (proxy, changes)
            assert ("ARC7_PROXY_CLEARED" in result.stdout) == clear
        env.update(MOCK_PROXY="127.0.0.1:8118", MOCK_HOST="127.0.0.1", MOCK_PORT="8118",
                   MOCK_FAIL="1", MOCK_STATE=str(path / "denied.json"))
        result = subprocess.run(["sh", "-c", command], env=env, text=True, capture_output=True)
        assert result.returncode != 0 and "ARC7_PROXY_CLEARED" not in result.stdout
        env.update(MOCK_FAIL="0", MOCK_NO_EFFECT="1", MOCK_STATE=str(path / "no-op.json"))
        result = subprocess.run(["sh", "-c", command], env=env, text=True, capture_output=True)
        assert result.returncode == 48 and "ARC7_PROXY_CLEARED" not in result.stdout
        for index, key in enumerate(("http_proxy", "global_http_proxy_host", "global_http_proxy_port")):
            env.update(MOCK_READ_FAIL=key, MOCK_STATE=str(path / ("unreadable-" + key + ".json")))
            result = subprocess.run(["sh", "-c", command], env=env, text=True, capture_output=True)
            assert result.returncode == 41 + index and "ARC7_PROXY_CLEARED" not in result.stdout
    print("PASS: legacy proxy migration clears only port 8118; disabled, work/school and unrelated proxies survive; failures are not success")


# Deliberately tiny Android fakes. Compile the REAL production routing/scope/PAN classes
# against these, rather than testing a Python reimplementation of their behavior.
STUBS = {
    "android/content/Context.java": '''package android.content;
public class Context {
    private String pkg;
    public Context() { this("ca.arc7freedom.app"); }
    public Context(String p) { pkg = p; }
    public Context getApplicationContext() { return this; }
    public String getPackageName() { return pkg; }
}''',
    "android/content/ComponentName.java": '''package android.content;
public class ComponentName {
    private String pkg, cls;
    public ComponentName(String p, String c) { pkg=p; cls=c; }
    public String getPackageName() { return pkg; }
    public String getClassName() { return cls; }
}''',
    "android/content/Intent.java": '''package android.content;
import java.util.*;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    private ComponentName component;
    private int flags;
    public String action, data, type, pkg;
    public Map<String,String> extras = new HashMap<String,String>();
    public Intent(String a) { action=a; }
    public Intent(Intent i) { action=i.action; data=i.data; type=i.type; pkg=i.pkg;
        flags=i.flags; component=i.component; extras.putAll(i.extras); }
    public ComponentName getComponent() { return component; }
    public Intent setComponent(ComponentName c) { component=c; return this; }
    public Intent setPackage(String p) { pkg=p; return this; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public int getFlags() { return flags; }
}''',
    "android/content/DialogInterface.java": '''package android.content;
public interface DialogInterface {
    interface OnClickListener { void onClick(DialogInterface d, int which); }
    interface OnCancelListener { void onCancel(DialogInterface d); }
}''',
    "android/app/Activity.java": '''package android.app;
public class Activity extends android.content.Context { public void finish() {} }''',
    "android/app/AlertDialog.java": '''package android.app;
import android.content.*;
public class AlertDialog {
    public void show() {}
    public static class Builder {
        public Builder(Activity a) {}
        public Builder setTitle(String s) { return this; }
        public Builder setMessage(String s) { return this; }
        public Builder setPositiveButton(String s, DialogInterface.OnClickListener l) { return this; }
        public Builder setOnCancelListener(DialogInterface.OnCancelListener l) { return this; }
        public AlertDialog create() { return new AlertDialog(); }
    }
}''',
    "android/widget/Toast.java": '''package android.widget;
public class Toast {
    public static final int LENGTH_LONG = 1;
    public static Toast makeText(android.content.Context c, String s, int t) { return new Toast(); }
    public void show() {}
}''',
    "android/bluetooth/BluetoothProfile.java": '''package android.bluetooth;
import java.util.*;
public interface BluetoothProfile {
    List<BluetoothDevice> getConnectedDevices();
    interface ServiceListener {
        void onServiceConnected(int profile, BluetoothProfile proxy);
        void onServiceDisconnected(int profile);
    }
}''',
    "android/bluetooth/BluetoothDevice.java": '''package android.bluetooth;
public class BluetoothDevice {
    private String name, address;
    public BluetoothDevice(String n, String a) { name=n; address=a; }
    public String getName() { return name; }
    public String getAddress() { return address; }
}''',
    "android/bluetooth/BluetoothAdapter.java": '''package android.bluetooth;
import java.util.*;
public class BluetoothAdapter {
    public static BluetoothAdapter current;
    public boolean enabled = true, supported = true, immediate = true;
    public int closes, requests;
    public BluetoothProfile proxy;
    public BluetoothProfile.ServiceListener listener;
    public Set<BluetoothDevice> bonded = new HashSet<BluetoothDevice>();
    public static BluetoothAdapter getDefaultAdapter() { return current; }
    public boolean isEnabled() { return enabled; }
    public Set<BluetoothDevice> getBondedDevices() { return bonded; }
    public boolean getProfileProxy(android.content.Context c, BluetoothProfile.ServiceListener l, int p) {
        requests++; listener=l;
        if (supported && immediate) l.onServiceConnected(p, proxy);
        return supported;
    }
    public void closeProfileProxy(int p, BluetoothProfile b) { closes++; }
}''',
}

HARNESS = '''package ca.arc7freedom.app;
import android.bluetooth.*;
import android.content.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class IsolationTest {
    public static class Pan implements BluetoothProfile {
        boolean sharing, deny, accept = true;
        List<BluetoothDevice> connected = new ArrayList<BluetoothDevice>();
        public List<BluetoothDevice> getConnectedDevices() { return connected; }
        public boolean isTetheringOn() { return sharing; }
        public void setBluetoothTethering(boolean enabled) {
            if (deny) throw new SecurityException("system permission required");
            sharing=enabled;
        }
        public boolean connect(BluetoothDevice d) { if (accept) connected.add(d); return accept; }
        public boolean disconnect(BluetoothDevice d) { connected.remove(d); return true; }
    }
    public static class NoControls implements BluetoothProfile {
        public List<BluetoothDevice> getConnectedDevices() { return Collections.emptyList(); }
    }
    static Intent intent(String pkg, String cls) {
        Intent i = new Intent("VIEW").setComponent(new ComponentName(pkg, cls));
        i.data="file:///sdcard/Books/example.epub"; i.type="application/epub+zip";
        i.extras.put("page", "42"); i.addFlags(1);
        return i;
    }
    static void navigation() throws Exception {
        Context suite = new Context("ca.arc7freedom.app");
        Context files = new Context("ca.arc7freedom.filemanager");
        Class<?> registry = Class.forName("ca.arc7freedom.launcher.BuiltinApps");
        java.lang.reflect.Method launcherRoute = registry.getDeclaredMethod("route", Intent.class);
        launcherRoute.setAccessible(true);
        for (String[] row : SplitApps.ALL) {
            assert row[1].equals(SplitAppRouter.ownerPackage(row[3]));
            Intent original = intent(suite.getPackageName(), row[3]);
            Intent routed = SplitAppRouter.route(suite, original, -1);
            assert routed.getComponent().getPackageName().equals(row[1]);
            assert routed.getComponent().getClassName().equals(row[3]);
            assert routed.data.equals(original.data) && routed.type.equals(original.type);
            assert routed.extras.get("page").equals("42");
            assert (routed.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0;
            assert original.getComponent().getPackageName().equals(suite.getPackageName());
            Context own = new Context(row[1]);
            Intent self = intent(row[1], row[3]);
            assert SplitAppRouter.route(own, self, -1) == self;
            Intent forResult = SplitAppRouter.route(files, original, 17);
            assert (forResult.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0;
            Intent alias = intent(suite.getPackageName(), suite.getPackageName() + row[0]);
            Intent migrated = SplitAppRouter.route(files, alias, -1);
            assert migrated.getComponent().getPackageName().equals(row[1]);
            assert migrated.getComponent().getClassName().equals(row[3]);
            Intent home = (Intent) launcherRoute.invoke(null, alias);
            assert home.getComponent().getPackageName().equals(row[1]);
            assert home.getComponent().getClassName().equals(row[3]);
            assert home.data.equals(original.data) && home.extras.get("page").equals("42");
        }
        Intent implicit = new Intent("android.settings.SETTINGS");
        assert SplitAppRouter.route(files, implicit, -1) == implicit;
        Intent settings = intent("com.android.settings", "com.android.settings.Settings");
        assert SplitAppRouter.route(files, settings, -1) == settings;
        Intent clock = intent("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7DeskClockActivity");
        assert SplitAppRouter.route(files, clock, -1).getComponent().getPackageName().equals("ca.arc7freedom.clock");
    }
    static ProxySelector fixed(final Proxy proxy) {
        return new ProxySelector() {
            public List<Proxy> select(URI uri) { return Collections.singletonList(proxy); }
            public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        };
    }
    static void scopes() throws Exception {
        Set<Integer> ports = new HashSet<Integer>();
        ports.add(8118); ports.add(18119); ports.add(21118); ports.add(21120); ports.add(21122);
        for (String[] row : SplitApps.ALL) {
            int port = AppNetworkScope.gatewayPortFor(row[1]);
            assert port >= 22000 && port < 54000;
            assert ports.add(port) && ports.add(port+1) : row[1] + " collides";
            assert port == AppNetworkScope.gatewayPortFor(row[1]);
        }
        assert AppNetworkScope.gatewayPortFor("ca.arc7freedom.app") == 8118;
        ProxySelector previous = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(fixed(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1",8118))));
            AppNetworkScope.initialize("ca.arc7freedom.browser");
            assert AppNetworkScope.gatewayPort() == AppNetworkScope.gatewayPortFor("ca.arc7freedom.browser");
            assert AppNetworkScope.browserPort() == AppNetworkScope.gatewayPort() + 1;
            assert ProxySelector.getDefault().select(new URI("https://example.test/")).get(0) == Proxy.NO_PROXY;
            Proxy corp = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.school.test",3128));
            ProxySelector.setDefault(fixed(corp));
            AppNetworkScope.initialize("ca.arc7freedom.weather");
            assert ProxySelector.getDefault().select(new URI("https://example.test/")).get(0) == corp;
            assert ProxySelector.getDefault().select(new URI("http://127.0.0.1:50174/")).get(0) == Proxy.NO_PROXY;
            assert ProxySelector.getDefault().select(new URI("http://[::1]/")).get(0) == Proxy.NO_PROXY;
        } finally { ProxySelector.setDefault(previous); }
    }
    static void bluetooth() {
        Context context = new Context("ca.arc7freedom.bluetoothinternet");
        BluetoothAdapter.current = null;
        BluetoothInternetManager absent = new BluetoothInternetManager(context, null);
        assert !absent.hasAdapter() && !absent.isEnabled() && absent.pairedDevices().isEmpty();
        absent.requestPan(); assert absent.setSharing(true).contains("unavailable"); absent.close();
        BluetoothAdapter adapter = new BluetoothAdapter(); BluetoothAdapter.current = adapter;
        adapter.enabled = false;
        BluetoothInternetManager off = new BluetoothInternetManager(context, null);
        off.requestPan(); assert adapter.requests == 0 && !off.hasPan(); off.close();
        adapter.enabled = true; adapter.supported = false;
        BluetoothInternetManager unsupported = new BluetoothInternetManager(context, null);
        unsupported.requestPan(); assert !unsupported.hasPan() && unsupported.profileStatus().contains("does not expose");
        unsupported.close();
        adapter.supported = true; Pan pan = new Pan(); adapter.proxy = pan;
        BluetoothDevice phone = new BluetoothDevice("Phone", "00:11:22:33:44:55");
        adapter.bonded.add(phone); adapter.bonded.add(new BluetoothDevice("Computer", "00:11:22:33:44:66"));
        BluetoothInternetManager manager = new BluetoothInternetManager(context, null);
        manager.requestPan(); assert manager.hasPan();
        assert manager.profileStatus().contains("available");
        assert manager.pairedDevices().get(0).getName().equals("Computer");
        assert manager.setSharing(true).contains("enabled") && manager.isSharing();
        assert manager.setSharing(false).contains("disabled") && !manager.isSharing();
        assert manager.connect(phone).contains("requested") && manager.connectedDeviceCount() == 1;
        assert manager.disconnect(phone).contains("requested") && manager.connectedDeviceCount() == 0;
        pan.accept=false; assert manager.connect(phone).contains("could not");
        pan.deny=true; assert manager.setSharing(true).contains("SecurityException");
        assert !manager.isSharing();
        manager.close(); assert adapter.closes == 1;
        adapter.proxy = new NoControls();
        BluetoothInternetManager noApi = new BluetoothInternetManager(context, null);
        noApi.requestPan(); assert noApi.isSharing() == null;
        assert noApi.setSharing(true).contains("NoSuchMethodException"); noApi.close();
        adapter.immediate = false; adapter.closes=0; adapter.proxy=pan;
        BluetoothInternetManager late = new BluetoothInternetManager(context, null);
        int requests = adapter.requests;
        late.requestPan(); late.requestPan(); assert adapter.requests == requests+1;
        late.close(); adapter.listener.onServiceConnected(5, pan);
        assert adapter.closes == 1 && !late.hasPan();
    }
    public static void main(String[] args) throws Exception {
        scopes(); navigation(); bluetooth();
        System.out.println("JAVA_ISOLATION_AND_BLUETOOTH_PASSED");
    }
}
'''


def check_java_behavior():
    local = ROOT / "build/toolchain"
    java = os.environ.get("JAVA") or shutil.which("java")
    if not java:
        for candidate in (Path("/opt/toolchain/jdk/bin/java"), local / "jdk/bin/java"):
            if candidate.is_file():
                java = str(candidate)
                break
    assert java, "Install a JDK or run scripts/bootstrap-toolchain.sh"
    ecj = os.environ.get("ECJ")
    if not ecj:
        ecj = next((str(path) for path in (Path("/opt/toolchain/jars/ecj-3.45.0.jar"),
                                         local / "jars/ecj-3.45.0.jar") if path.is_file()), None)
    with tempfile.TemporaryDirectory(prefix="arc7-java-") as directory:
        temporary = Path(directory)
        for name, content in STUBS.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        runner = temporary / "ca/arc7freedom/app/IsolationTest.java"
        runner.parent.mkdir(parents=True)
        runner.write_text(HARNESS)
        sources = [str(path) for path in temporary.rglob("*.java")]
        sources += [str(JAVA_SRC / name) for name in
                    ("AppNetworkScope.java", "SplitApps.java", "SplitAppRouter.java", "BluetoothInternetManager.java")]
        sources += [str(ROOT / "launcher-android4/src/main/java/ca/arc7freedom/launcher/BuiltinApps.java")]
        if ecj:
            compiler = [java, "-jar", ecj, "-1.8", "-nowarn"]
        else:
            compiler = [os.environ.get("JAVAC") or shutil.which("javac"), "-source", "8", "-target", "8"]
            assert compiler[0], "A Java compiler is required"
        subprocess.run(compiler + ["-d", str(temporary)] + sources, check=True)
        result = subprocess.run([java, "-ea", "-cp", str(temporary), "ca.arc7freedom.app.IsolationTest"],
                                check=True, text=True, capture_output=True)
        assert "JAVA_ISOLATION_AND_BLUETOOTH_PASSED" in result.stdout
    print("PASS: real Java code — unique ports, proxy selection, 30-app/legacy-alias routing with URI/extras/grants preserved")
    print("PASS: Bluetooth fakes — no adapter, disabled, no PAN, sharing, receiving, rejected/permission-denied APIs, late callback cleanup")


def check_native(binary):
    class Origin(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = b"independent-app-transport"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    origin = http.server.HTTPServer(("127.0.0.1", 0), Origin)
    thread = threading.Thread(target=origin.serve_forever, daemon=True)
    thread.start()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    processes = []
    try:
        for owner in ("ca.arc7freedom.browser", "ca.arc7freedom.weather"):
            with socket.socket() as reserve:
                reserve.bind(("127.0.0.1", 0))
                port = reserve.getsockname()[1]
            env = dict(os.environ, ARC7_GATEWAY_OWNER=owner)
            process = subprocess.Popen([str(Path(binary).resolve()), "proxy",
                                        str(ROOT / "app/src/main/assets/cacerts/ca-bundle-2026.crt"), str(port)],
                                       env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            processes.append((process, port, owner))
            ready, _, _ = select.select([process.stdout], [], [], 20)
            assert ready, "Native gateway did not become ready"
            line = process.stdout.readline()
            assert b"PROXY=READY" in line, line
        for process, port, owner in processes:
            with client.open(f"http://127.0.0.1:{port}/__ping", timeout=5) as response:
                ping = response.read().decode()
                assert "OWNER=" + owner + "\n" in ping and f"UID={os.getuid()}\n" in ping
            upstream = f"http://127.0.0.1:{origin.server_port}/payload"
            with client.open(f"http://127.0.0.1:{port}/__arc7_raw?u=" + urllib.parse.quote(upstream, safe=""), timeout=5) as response:
                assert response.read() == b"independent-app-transport"
        process, port, _ = processes[0]
        with client.open(f"http://127.0.0.1:{port}/__shutdown", timeout=5) as response:
            assert response.read() == b"BYE\n"
        process.wait(timeout=5)
        assert processes[1][0].poll() is None
        with client.open(f"http://127.0.0.1:{processes[1][1]}/__ping", timeout=5) as response:
            assert b"OWNER=ca.arc7freedom.weather\n" in response.read()
    finally:
        for process, _, _ in processes:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=5)
            process.stdout.close()
        origin.shutdown()
        origin.server_close()
        thread.join(timeout=5)
    print("PASS: two native gateways serve traffic independently; stopping one leaves the other alive (host UID, not a device test)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native", type=Path, help="Also test a host-architecture arc7tls binary")
    options = parser.parse_args()
    check_manifests_and_generation()
    check_proxy_migration()
    check_companion_transports()
    check_java_behavior()
    if options.native:
        check_native(options.native)
    print("ALL APP ISOLATION AND BLUETOOTH CHECKS PASSED")


if __name__ == "__main__":
    main()
