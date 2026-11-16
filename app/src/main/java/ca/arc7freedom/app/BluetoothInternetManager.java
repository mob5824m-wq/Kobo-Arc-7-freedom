package ca.arc7freedom.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Android 4.x PAN control. Android owns DHCP/routing; there is no Arc7 traffic proxy. */
final class BluetoothInternetManager {
    // BluetoothProfile.PAN is hidden in the Android 4.x public SDK (profile id in AOSP).
    private static final int PAN_PROFILE = 5;
    private final Context context;
    private final Runnable changed;
    private final BluetoothAdapter adapter;
    private BluetoothProfile pan;
    private boolean closed;
    private boolean panRequested;
    private String profileStatus = "Bluetooth PAN is not connected to the system service.";

    BluetoothInternetManager(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        BluetoothAdapter found = null;
        try { found = BluetoothAdapter.getDefaultAdapter(); } catch (RuntimeException ignored) {}
        adapter = found;
    }

    boolean hasAdapter() { return adapter != null; }

    boolean isEnabled() {
        try { return adapter != null && adapter.isEnabled(); } catch (RuntimeException denied) { return false; }
    }

    boolean hasPan() { return pan != null; }
    String profileStatus() { return profileStatus; }

    void requestPan() {
        if (closed || !isEnabled() || pan != null || panRequested) return;
        try {
            panRequested = true;
            profileStatus = "Waiting for Android's Bluetooth PAN service (or use Android tethering settings)...";
            if (!adapter.getProfileProxy(context, listener, PAN_PROFILE)) {
                panRequested = false;
                profileStatus = "This ROM does not expose Bluetooth PAN. Try Android tethering settings.";
            }
        } catch (RuntimeException unavailable) {
            panRequested = false;
            profileStatus = "PAN control is unavailable: " + unavailable.getMessage();
        }
    }

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != PAN_PROFILE) return;
            if (closed) {
                if (adapter != null) adapter.closeProfileProxy(PAN_PROFILE, proxy);
                return;
            }
            panRequested = false;
            pan = proxy;
            profileStatus = "Android Bluetooth PAN service is available.";
            if (changed != null) changed.run();
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile != PAN_PROFILE) return;
            panRequested = false;
            pan = null;
            profileStatus = "Android Bluetooth PAN service disconnected.";
            if (!closed && changed != null) changed.run();
        }
    };

    List<BluetoothDevice> pairedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        if (!isEnabled()) return devices;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
            Collections.sort(devices, new Comparator<BluetoothDevice>() {
                @Override public int compare(BluetoothDevice a, BluetoothDevice b) {
                    return deviceLabel(a).compareToIgnoreCase(deviceLabel(b));
                }
            });
        } catch (RuntimeException ignored) {}
        return devices;
    }

    static String deviceLabel(BluetoothDevice device) {
        if (device == null) return "No paired device";
        String name = device.getName();
        return (name != null && name.length() > 0 ? name : "Bluetooth device") + " (" + device.getAddress() + ")";
    }

    /** null means the ROM cannot report this state; never display a guessed ON/OFF. */
    Boolean isSharing() {
        try {
            Object state = callPan("isTetheringOn", new Class<?>[0], new Object[0]);
            return state instanceof Boolean ? (Boolean) state : null;
        } catch (Exception unavailable) { return null; }
    }

    int connectedDeviceCount() {
        try { return pan != null ? pan.getConnectedDevices().size() : 0; }
        catch (RuntimeException unavailable) { return 0; }
    }

    String setSharing(boolean enable) {
        try {
            Object result = callPan("setBluetoothTethering", new Class<?>[]{boolean.class}, new Object[]{enable});
            if (Boolean.FALSE.equals(result)) return "Android rejected the tethering request. Open tethering settings.";
            Boolean actual = isSharing();
            if (actual != null && actual.booleanValue() == enable) {
                return enable ? "Bluetooth tethering is enabled. On the other device, enable Internet access for this Kobo."
                        : "Bluetooth tethering is disabled.";
            }
            return "Request sent. Confirm Bluetooth tethering in Android settings; some ROMs require a system-app permission.";
        } catch (Exception unavailable) {
            return "Cannot change Bluetooth tethering here: " + reason(unavailable)
                    + ". Use Android tethering settings instead.";
        }
    }

    String connect(BluetoothDevice device) {
        if (device == null) return "Pair a phone/computer first, then select it above.";
        try {
            Object accepted = callPan("connect", new Class<?>[]{BluetoothDevice.class}, new Object[]{device});
            return Boolean.TRUE.equals(accepted)
                    ? "PAN connection requested. Wait for Bluetooth network connected below; internet is not yet verified."
                    : "Android could not start PAN. Enable Bluetooth tethering on the other device, or use its Internet access setting.";
        } catch (Exception unavailable) {
            return "Cannot connect PAN here: " + reason(unavailable)
                    + ". This ROM may only support sharing, not receiving Bluetooth internet. Try Bluetooth settings.";
        }
    }

    String disconnect(BluetoothDevice device) {
        if (device == null) return "Select a paired device first.";
        try {
            Object accepted = callPan("disconnect", new Class<?>[]{BluetoothDevice.class}, new Object[]{device});
            return Boolean.FALSE.equals(accepted) ? "Android rejected the disconnect request." : "PAN disconnect requested.";
        } catch (Exception unavailable) { return "Cannot disconnect PAN: " + reason(unavailable); }
    }

    private Object callPan(String name, Class<?>[] types, Object[] args) throws Exception {
        if (!isEnabled()) throw new IllegalStateException("Bluetooth is off or unavailable");
        if (pan == null) throw new IllegalStateException("PAN service is unavailable");
        Method method = pan.getClass().getMethod(name, types);
        return method.invoke(pan, args);
    }

    private static String reason(Exception error) {
        Throwable cause = error instanceof InvocationTargetException
                ? ((InvocationTargetException) error).getTargetException() : error;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    void close() {
        closed = true;
        if (adapter != null && pan != null) {
            try { adapter.closeProfileProxy(PAN_PROFILE, pan); } catch (RuntimeException ignored) {}
        }
        pan = null;
    }
}
