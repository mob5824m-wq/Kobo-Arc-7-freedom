package ca.arc7freedom.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

/** Share Wi-Fi via Android Bluetooth PAN, or use another device's Bluetooth tethering. */
public class Arc7BluetoothInternetActivity extends Arc7Activity {
    private final Handler ui = new Handler();
    private BluetoothInternetManager bluetooth;
    private Arc7Skin.Palette palette;
    private TextView status;
    private TextView result;
    private Spinner devices;
    private List<BluetoothDevice> paired;
    private Button shareOn;
    private Button shareOff;
    private Button connect;
    private Button disconnect;
    private boolean receiverRegistered;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        palette = Arc7Skin.current(this);
        bluetooth = new BluetoothInternetManager(this, new Runnable() {
            @Override public void run() { refreshOnUi(); }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bgPage);
        root.addView(Arc7Skin.buildAppTopBar(this, palette, R.drawable.ic_bluetooth,
                "Bluetooth Internet", "Android PAN sharing • No Arc7 network proxy", null));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(22));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout live = card(body, "Connection status",
                "Pairing alone does not share internet. Both devices need Bluetooth PAN support.");
        status = text(live, "Checking Bluetooth hardware...");
        button(live, "Enable Bluetooth", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!bluetooth.hasAdapter()) { showResult(noHardwareMessage()); return; }
                openSettings(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            }
        });
        button(live, "Pair devices / Bluetooth settings", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        });
        button(live, "Android tethering settings", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(new Intent("android.settings.TETHER_SETTINGS")); }
        });
        button(live, "Refresh devices and connection", new View.OnClickListener() {
            @Override public void onClick(View v) { bluetooth.requestPan(); refresh(); }
        });

        LinearLayout sharing = card(body, "1. Share this Kobo's Wi-Fi over Bluetooth",
                "Connect the Kobo to Wi-Fi, pair the other device, then enable Bluetooth tethering. "
                + "On the other device, select the Kobo and enable Internet access / Connect to network. "
                + "Android handles routing and DHCP; no traffic is sent through Arc7 Freedom.");
        shareOn = button(sharing, "Share Kobo Wi-Fi via Bluetooth", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!networkConnected(ConnectivityManager.TYPE_WIFI)) {
                    showResult("Connect this Kobo to Wi-Fi first. Bluetooth shares an existing connection; it does not create internet access.");
                    return;
                }
                showResult(bluetooth.setSharing(true));
                refresh();
            }
        });
        shareOff = button(sharing, "Stop Bluetooth sharing", new View.OnClickListener() {
            @Override public void onClick(View v) { showResult(bluetooth.setSharing(false)); refresh(); }
        });

        LinearLayout receiving = card(body, "2. Receive internet from a phone or computer",
                "On the other device, enable Bluetooth tethering, then pair it with this Kobo. "
                + "Select it below and connect. Some phones share only mobile data, not their Wi-Fi; "
                + "some Kobo ROMs do not support the PAN client role. No root or Arc7 VPN is needed.");
        devices = new Spinner(this);
        receiving.addView(devices);
        connect = button(receiving, "Connect to selected device's internet", new View.OnClickListener() {
            @Override public void onClick(View v) { showResult(bluetooth.connect(selectedDevice())); refresh(); }
        });
        disconnect = button(receiving, "Disconnect selected PAN device", new View.OnClickListener() {
            @Override public void onClick(View v) { showResult(bluetooth.disconnect(selectedDevice())); refresh(); }
        });

        LinearLayout help = card(body, "Independent app networking",
                "Bluetooth is a system connection, just like Wi-Fi. Browser, Weather, YouTube and the other "
                + "separate APKs use their own Android app IDs and network engines. Force-stopping Arc7 Freedom "
                + "does not stop their Wi-Fi/Bluetooth internet. An explicitly enabled VPN or legacy global proxy "
                + "can still combine traffic; disable those in Wi-Fi & VPN / Arc7 Freedom if you do not want them.");
        result = text(help, "If there is no Bluetooth adapter, software cannot add it. Use Wi-Fi or USB Internet instead.");
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        refresh();
    }

    private int dp(int value) { return Arc7Skin.dp(this, value); }

    private LinearLayout card(LinearLayout body, String heading, String description) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackgroundDrawable(Arc7Skin.cardDrawable(this, palette, 14));
        TextView title = text(card, heading);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text(card, description).setTextColor(palette.textMuted);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(12);
        body.addView(card, layout);
        return card;
    }

    private TextView text(LinearLayout parent, String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(palette.textPrimary);
        text.setTextSize(14);
        text.setPadding(0, dp(4), 0, dp(8));
        parent.addView(text);
        return text;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = Arc7Skin.actionButton(this, label, palette.accentDark);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private BluetoothDevice selectedDevice() {
        int position = devices.getSelectedItemPosition();
        return paired != null && position >= 0 && position < paired.size() ? paired.get(position) : null;
    }

    private boolean networkConnected(int type) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getNetworkInfo(type) : null;
            return info != null && info.isConnected();
        } catch (RuntimeException denied) { return false; }
    }

    private void refresh() {
        if (destroyed || status == null) return;
        BluetoothDevice selected = selectedDevice();
        paired = bluetooth.pairedDevices();
        String[] labels = new String[Math.max(1, paired.size())];
        int selectedPosition = 0;
        labels[0] = "No paired devices — open Bluetooth settings";
        for (int i = 0; i < paired.size(); i++) {
            labels[i] = BluetoothInternetManager.deviceLabel(paired.get(i));
            if (paired.get(i).equals(selected)) selectedPosition = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        devices.setAdapter(adapter);
        devices.setSelection(selectedPosition);
        boolean ready = bluetooth.isEnabled() && bluetooth.hasPan();
        shareOn.setEnabled(ready);
        shareOff.setEnabled(ready);
        connect.setEnabled(ready && !paired.isEmpty());
        disconnect.setEnabled(ready && !paired.isEmpty());
        Boolean sharing = bluetooth.isSharing();
        String bluetoothStatus = !bluetooth.hasAdapter() ? noHardwareMessage()
                : !bluetooth.isEnabled() ? "Bluetooth is off. Tap Enable Bluetooth to continue."
                : bluetooth.profileStatus();
        status.setText(bluetoothStatus
                + "\nWi-Fi connected: " + (networkConnected(ConnectivityManager.TYPE_WIFI) ? "Yes" : "No")
                + "\nBluetooth tethering: " + (sharing == null ? "Not reported by this ROM" : sharing ? "On" : "Off")
                + "\nPAN peers: " + bluetooth.connectedDeviceCount()
                + "\nBluetooth network connected: " + (networkConnected(ConnectivityManager.TYPE_BLUETOOTH) ? "Yes" : "No")
                + "\nInternet reachability has not been tested.");
    }

    private String noHardwareMessage() {
        return "No Bluetooth adapter was detected on this device. Some Kobo Arc models have no Bluetooth hardware. Use Wi-Fi or USB instead.";
    }

    private void showResult(String message) { result.setText(message); }

    private void openSettings(Intent preferred) {
        try { startActivity(preferred); }
        catch (android.content.ActivityNotFoundException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (android.content.ActivityNotFoundException missing) { showResult("This ROM has no compatible Bluetooth/tethering settings screen."); }
        }
    }

    private void refreshOnUi() {
        ui.post(new Runnable() { @Override public void run() { refresh(); } });
    }

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            bluetooth.requestPan();
            refresh();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction("android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED");
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(changes, filter);
            receiverRegistered = true;
        }
        bluetooth.requestPan();
        refresh();
    }

    @Override protected void onPause() {
        if (receiverRegistered) { unregisterReceiver(changes); receiverRegistered = false; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        bluetooth.close();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
