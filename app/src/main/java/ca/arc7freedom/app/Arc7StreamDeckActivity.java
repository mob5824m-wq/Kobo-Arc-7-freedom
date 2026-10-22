package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full Standalone Elgato-Style PC Stream Deck (`Arc7 Stream Deck` — `v2.3.0`):
 *
 *   - Selectable tactile LCD key matrices: `2x3` (6), `3x5` (15 Classic), `4x6` (24 XL), `4x8` (32 Pro)
 *   - 4 built-in multi-button profiles (`OBS & Stream`, `Discord & Audio`, `PC Shortcuts`, `Dev & Custom`)
 *     with full per-key customization on Kobo OR live push from PC Browser Studio (`http://127.0.0.1:18995`)
 *   - Supports Tap Macros (`MACRO`), Momentary Hold Push-to-Talk (`KEY_DOWN` / `KEY_UP`),
 *     Stateful Glow Toggles (`TOGGLE`), OBS WebSocket v5 (`OBS_*`), App/URL Launch (`OPEN`),
 *     Text Snippet Typing (`TEXT`), Shell Commands (`RUN`), and Live PC Master Volume Fader (`VOL_SET`)!
 *   - Works over Wi-Fi LAN (`UDP/TCP :18991` auto-discovery), Zero-Lag USB (`adb forward tcp:18991 tcp:18991`),
 *     AND Remote Tailscale Mesh (`100.x.y.z:18991`)!
 */
public class Arc7StreamDeckActivity extends Activity {

    public static final int STREAM_DECK_PORT = 18991;
    private static final String PREFS = "arc7_stream_deck";

    public static final class DeckKey {
        public String icon;
        public String label;
        public String command;
        public int color;
        public int mode; // 0 = tap, 1 = toggle, 2 = hold momentary (PTT)
        public boolean activeState;

        public DeckKey(String icon, String label, String command, int color, int mode) {
            this.icon = icon;
            this.label = label;
            this.command = command;
            this.color = color;
            this.mode = mode;
            this.activeState = false;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("icon", icon);
            o.put("label", label);
            o.put("command", command);
            o.put("color", color);
            o.put("mode", mode);
            return o;
        }

        public static DeckKey fromJson(JSONObject o) {
            return new DeckKey(
                    o.optString("icon", "\u26A1"),
                    o.optString("label", "Macro"),
                    o.optString("command", "HOTKEY F13"),
                    o.optInt("color", 0xFF0284C7),
                    o.optInt("mode", 0));
        }
    }

    private static final String[] PROFILE_NAMES = new String[]{
            "\uD83C\uDFAC OBS & Stream",
            "\uD83C\uDFA7 Discord & Media",
            "\u26A1 PC Shortcuts",
            "\uD83D\uDCBB Dev & Custom"
    };

    private static final int[][] GRID_PRESETS = new int[][]{
            {2, 3}, {3, 5}, {4, 6}, {4, 8}
    };
    private static final String[] GRID_LABELS = new String[]{
            "2\u00D73 Mini (6)", "3\u00D75 Classic (15)", "4\u00D76 XL (24)", "4\u00D78 Studio (32)"
    };

    private static final int[] KEY_PALETTE = new int[]{
            0xFF0284C7, 0xFF7C3AED, 0xFF059669, 0xFFDC2626,
            0xFFD97706, 0xFFDB2777, 0xFF2563EB, 0xFF334155
    };
    private static final String[] KEY_PALETTE_NAMES = new String[]{
            "Cyan Neon", "Electric Violet", "Emerald", "Broadcast Red",
            "Amber Gold", "Magenta Pulse", "Royal Blue", "Stealth Slate"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Arc7Skin.Palette pal;
    private Vibrator vibrator;

    private TextView telemetryView;
    private TextView connBadgeView;
    private LinearLayout profileStrip;
    private LinearLayout deckMatrixContainer;
    private SeekBar volumeFader;
    private TextView volumeLabel;
    private Button editToggleBtn;
    private Button gridCycleBtn;

    private int activeProfile = 0;
    private int gridPresetIdx = 1; // 3x5 Classic 15-key default
    private boolean editKeysMode = false;

    @SuppressWarnings("unchecked")
    private final List<DeckKey>[] profiles = new ArrayList[PROFILE_NAMES.length];

    private volatile boolean running = true;
    private volatile Socket activeSocket = null;
    private volatile OutputStream activeOut = null;
    private ServerSocket usbOrReverseServer = null;
    private String pcHostIp = "";

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pal = Arc7Skin.current(this);
        try { vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE); } catch (Throwable ignored) {}

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        activeProfile = Math.max(0, Math.min(PROFILE_NAMES.length - 1, sp.getInt("active_profile", 0)));
        gridPresetIdx = Math.max(0, Math.min(GRID_PRESETS.length - 1, sp.getInt("grid_preset", 1)));
        pcHostIp = sp.getString("pc_host_ip", "");
        String extraHost = getIntent() != null ? getIntent().getStringExtra("pc_host") : null;
        if (extraHost != null && !extraHost.trim().isEmpty()) {
            pcHostIp = extraHost.trim();
            sp.edit().putString("pc_host_ip", pcHostIp).commit();
        }

        loadOrInitProfiles();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF060912);

        // Header action buttons
        LinearLayout hdrActions = new LinearLayout(this);
        hdrActions.setOrientation(LinearLayout.HORIZONTAL);
        hdrActions.setGravity(Gravity.CENTER_VERTICAL);

        gridCycleBtn = Arc7Skin.secondaryButton(this, pal, GRID_LABELS[gridPresetIdx]);
        gridCycleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gridPresetIdx = (gridPresetIdx + 1) % GRID_PRESETS.length;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("grid_preset", gridPresetIdx).commit();
                gridCycleBtn.setText(GRID_LABELS[gridPresetIdx]);
                renderDeckMatrix();
            }
        });
        hdrActions.addView(gridCycleBtn);

        editToggleBtn = Arc7Skin.secondaryButton(this, pal, "\u270E Edit Keys");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.leftMargin = dp(6);
        editToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editKeysMode = !editKeysMode;
                editToggleBtn.setText(editKeysMode ? "\u2713 Done Editing" : "\u270E Edit Keys");
                renderDeckMatrix();
                Toast.makeText(Arc7StreamDeckActivity.this,
                        editKeysMode ? "Tap ANY key to customize its icon, label, hotkey or PC command!"
                                     : "Saved Stream Deck profile!",
                        Toast.LENGTH_SHORT).show();
            }
        });
        hdrActions.addView(editToggleBtn, elp);

        Button connBtn = Arc7Skin.actionButton(this, "\uD83D\uDD0C Connect PC", 0xFF0284C7);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        connBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showConnectPcDialog(); }
        });
        hdrActions.addView(connBtn, clp);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_streamdeck,
                "Stream Deck",
                "PC / OBS / Discord / Macro LCD Controller  \u2022  Wi-Fi, USB Cable & Tailscale `:18991`",
                hdrActions));

        // Live PC Telemetry + Profile Switcher Strip
        LinearLayout subBar = new LinearLayout(this);
        subBar.setOrientation(LinearLayout.HORIZONTAL);
        subBar.setGravity(Gravity.CENTER_VERTICAL);
        subBar.setBackgroundColor(0xFF0B1220);
        subBar.setPadding(dp(10), dp(5), dp(10), dp(5));

        connBadgeView = new TextView(this);
        connBadgeView.setText("\u25CF Searching PC (:" + STREAM_DECK_PORT + ")");
        connBadgeView.setTextColor(0xFFFBBF24);
        connBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        connBadgeView.setTypeface(Typeface.DEFAULT_BOLD);
        subBar.addView(connBadgeView);

        telemetryView = new TextView(this);
        telemetryView.setText("   Kobo IP: " + localWifiIp() + "  \u2022  Run Start-PC-Stream-Deck.py on PC");
        telemetryView.setTextColor(0xFF94A3B8);
        telemetryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        telemetryView.setSingleLine(true);
        telemetryView.setEllipsize(TextUtils.TruncateAt.END);
        subBar.addView(telemetryView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(subBar);

        // Profile Switcher Tabs
        profileStrip = new LinearLayout(this);
        profileStrip.setOrientation(LinearLayout.HORIZONTAL);
        profileStrip.setBackgroundColor(0xFF090E1A);
        profileStrip.setPadding(dp(10), dp(6), dp(10), dp(4));
        root.addView(profileStrip);

        // Main Tactile LCD Stream Deck Matrix
        deckMatrixContainer = new LinearLayout(this);
        deckMatrixContainer.setOrientation(LinearLayout.VERTICAL);
        deckMatrixContainer.setPadding(dp(10), dp(6), dp(10), dp(6));
        root.addView(deckMatrixContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom Live PC Master Volume Touch Fader Strip
        LinearLayout faderBar = new LinearLayout(this);
        faderBar.setOrientation(LinearLayout.HORIZONTAL);
        faderBar.setGravity(Gravity.CENTER_VERTICAL);
        faderBar.setBackgroundColor(0xFF0B1220);
        faderBar.setPadding(dp(14), dp(6), dp(14), dp(8));

        volumeLabel = new TextView(this);
        volumeLabel.setText("\uD83D\uDD0A PC Volume: 70%");
        volumeLabel.setTextColor(0xFFE2E8F0);
        volumeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        volumeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        faderBar.addView(volumeLabel);

        volumeFader = new SeekBar(this);
        volumeFader.setMax(100);
        volumeFader.setProgress(70);
        volumeFader.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private long lastSentMs = 0L;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLabel.setText("\uD83D\uDD0A PC Volume: " + progress + "%");
                if (fromUser) {
                    long now = System.currentTimeMillis();
                    if (now - lastSentMs > 75L) {
                        lastSentMs = now;
                        sendDeckPacket(buildActionJson("VOL_SET " + progress, "Volume", 0, true));
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sendDeckPacket(buildActionJson("VOL_SET " + seekBar.getProgress(), "Volume", 0, true));
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = dp(12);
        flp.rightMargin = dp(10);
        faderBar.addView(volumeFader, flp);

        Button muteQuick = Arc7Skin.secondaryButton(this, pal, "\uD83D\uDD07 Mute");
        muteQuick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pulseHaptic(22);
                sendDeckPacket(buildActionJson("MEDIA VOL_MUTE", "Mute", 0, true));
            }
        });
        faderBar.addView(muteQuick);
        root.addView(faderBar);

        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);

        renderProfileTabs();
        renderDeckMatrix();
        startUsbAndInboundServer();
        startAutoDiscoveryAndClientLoop();
    }

    /* ==================== Default 4-Profile 32-Slot Library ==================== */

    public static List<DeckKey> buildDefaultProfile(int idx) {
        List<DeckKey> list = new ArrayList<DeckKey>();
        if (idx == 0) {
            list.add(new DeckKey("\uD83D\uDD34", "GO LIVE", "OBS_TOGGLE_STREAM", 0xFFDC2626, 1));
            list.add(new DeckKey("\u23FA", "RECORD", "OBS_TOGGLE_RECORD", 0xFFDB2777, 1));
            list.add(new DeckKey("\u23EA", "SAVE REPLAY", "OBS_SAVE_REPLAY", 0xFF7C3AED, 0));
            list.add(new DeckKey("\uD83C\uDFA5", "SCENE: LIVE", "OBS_SCENE Live", 0xFF0284C7, 0));
            list.add(new DeckKey("\uD83C\uDFAE", "SCENE: GAME", "OBS_SCENE Gameplay", 0xFF2563EB, 0));
            list.add(new DeckKey("\uD83D\uDCAC", "JUST CHATTING", "OBS_SCENE Chatting", 0xFF059669, 0));
            list.add(new DeckKey("\u23F8", "SCENE: BRB", "OBS_SCENE BRB", 0xFFD97706, 0));
            list.add(new DeckKey("\uD83C\uDF99", "MIC MUTE", "OBS_TOGGLE_MUTE Mic/Aux", 0xFFDC2626, 1));
            list.add(new DeckKey("\uD83D\uDD0A", "DESKTOP AUDIO", "OBS_TOGGLE_MUTE Desktop Audio", 0xFF059669, 1));
            list.add(new DeckKey("\uD83C\uDF9B", "STUDIO MODE", "OBS_STUDIO_MODE", 0xFF7C3AED, 1));
            list.add(new DeckKey("\uD83D\uDCF8", "SCREENSHOT", "HOTKEY PRINT_SCREEN", 0xFF0284C7, 0));
            list.add(new DeckKey("\uD83D\uDCCC", "STREAM MARK", "HOTKEY CTRL+SHIFT+F12", 0xFFD97706, 0));
            list.add(new DeckKey("\u23EF", "PLAY / PAUSE", "MEDIA PLAY_PAUSE", 0xFF059669, 0));
            list.add(new DeckKey("\uD83D\uDD09", "VOL -", "MEDIA VOL_DOWN", 0xFF334155, 0));
            list.add(new DeckKey("\uD83D\uDD0A", "VOL +", "MEDIA VOL_UP", 0xFF334155, 0));
        } else if (idx == 1) {
            list.add(new DeckKey("\uD83C\uDF99", "DISCORD MUTE", "HOTKEY CTRL+SHIFT+M", 0xFFDC2626, 1));
            list.add(new DeckKey("\uD83C\uDFA7", "DEAFEN", "HOTKEY CTRL+SHIFT+D", 0xFFDB2777, 1));
            list.add(new DeckKey("\uD83D\uDCE2", "HOLD TALK", "HOTKEY F13", 0xFF059669, 2));
            list.add(new DeckKey("\u23EE", "PREV TRACK", "MEDIA PREV_TRACK", 0xFF0284C7, 0));
            list.add(new DeckKey("\u23EF", "PLAY / PAUSE", "MEDIA PLAY_PAUSE", 0xFF0284C7, 0));
            list.add(new DeckKey("\u23ED", "NEXT TRACK", "MEDIA NEXT_TRACK", 0xFF0284C7, 0));
            list.add(new DeckKey("\uD83C\uDFAC", "CLIP 30s", "HOTKEY ALT+F10", 0xFF7C3AED, 0));
            list.add(new DeckKey("\uD83D\uDDA5", "SHARE SCREEN", "HOTKEY CTRL+SHIFT+S", 0xFF2563EB, 0));
            list.add(new DeckKey("\uD83D\uDE00", "EMOJI BAR", "HOTKEY WIN+.", 0xFFD97706, 0));
            list.add(new DeckKey("\uD83C\uDFB5", "SPOTIFY", "OPEN https://open.spotify.com", 0xFF059669, 0));
            list.add(new DeckKey("\u25B6", "YOUTUBE", "OPEN https://www.youtube.com", 0xFFDC2626, 0));
            list.add(new DeckKey("\uD83D\uDCAC", "TWITCH CHAT", "OPEN https://www.twitch.tv", 0xFF7C3AED, 0));
            list.add(new DeckKey("\uD83D\uDD07", "MUTE PC", "MEDIA VOL_MUTE", 0xFF334155, 1));
            list.add(new DeckKey("\uD83D\uDD09", "VOL -5%", "MEDIA VOL_DOWN", 0xFF334155, 0));
            list.add(new DeckKey("\uD83D\uDD12", "LOCK PC", "SYSTEM LOCK_PC", 0xFFDC2626, 0));
        } else if (idx == 2) {
            list.add(new DeckKey("\uD83D\uDCCA", "TASK MGR", "HOTKEY CTRL+SHIFT+ESC", 0xFFDC2626, 0));
            list.add(new DeckKey("\u2702", "SNIP SCREEN", "HOTKEY WIN+SHIFT+S", 0xFF0284C7, 0));
            list.add(new DeckKey("\u25C0", "DESKTOP \u25C0", "HOTKEY CTRL+WIN+LEFT", 0xFF7C3AED, 0));
            list.add(new DeckKey("\u25B6", "DESKTOP \u25B6", "HOTKEY CTRL+WIN+RIGHT", 0xFF7C3AED, 0));
            list.add(new DeckKey("\uD83E\uDDF9", "SHOW DESKTOP", "HOTKEY WIN+D", 0xFF2563EB, 0));
            list.add(new DeckKey("\uD83D\uDCCB", "COPY", "HOTKEY CTRL+C", 0xFF059669, 0));
            list.add(new DeckKey("\uD83D\uDCCC", "PASTE", "HOTKEY CTRL+V", 0xFF059669, 0));
            list.add(new DeckKey("\uD83D\uDDC2", "CLIPBOARD", "HOTKEY WIN+V", 0xFFD97706, 0));
            list.add(new DeckKey("\u21A9", "UNDO", "HOTKEY CTRL+Z", 0xFF334155, 0));
            list.add(new DeckKey("\u21AA", "REDO", "HOTKEY CTRL+Y", 0xFF334155, 0));
            list.add(new DeckKey("\uD83D\uDCC1", "EXPLORER", "HOTKEY WIN+E", 0xFFD97706, 0));
            list.add(new DeckKey("\uD83C\uDF10", "NEW TAB", "HOTKEY CTRL+T", 0xFF0284C7, 0));
            list.add(new DeckKey("\uD83D\uDD0E", "FIND", "HOTKEY CTRL+F", 0xFF2563EB, 0));
            list.add(new DeckKey("\u2795", "ZOOM IN", "HOTKEY CTRL+PLUS", 0xFF334155, 0));
            list.add(new DeckKey("\u2715", "CLOSE APP", "HOTKEY ALT+F4", 0xFFDC2626, 0));
        } else {
            list.add(new DeckKey("\u25B6", "RUN / F5", "HOTKEY F5", 0xFF059669, 0));
            list.add(new DeckKey("\uD83D\uDD28", "BUILD", "HOTKEY CTRL+SHIFT+B", 0xFF0284C7, 0));
            list.add(new DeckKey("\uD83D\uDCBB", "TERMINAL", "HOTKEY CTRL+`", 0xFF7C3AED, 0));
            list.add(new DeckKey("\uD83D\uDC1E", "DEVTOOLS", "HOTKEY CTRL+SHIFT+I", 0xFFD97706, 0));
            list.add(new DeckKey("\u2B05", "SNAP LEFT", "HOTKEY WIN+LEFT", 0xFF2563EB, 0));
            list.add(new DeckKey("\u27A1", "SNAP RIGHT", "HOTKEY WIN+RIGHT", 0xFF2563EB, 0));
            list.add(new DeckKey("\uD83C\uDF10", "LOCAL :3000", "OPEN http://localhost:3000", 0xFF059669, 0));
            list.add(new DeckKey("\uD83C\uDF9B", "DECK STUDIO", "OPEN http://127.0.0.1:18995", 0xFFDB2777, 0));
            list.add(new DeckKey("\u270D", "TYPE GG!", "TEXT gg well played!", 0xFF7C3AED, 0));
            list.add(new DeckKey("\uD83D\uDDA5", "2ND MONITOR", "@SECOND_SCREEN", 0xFF0284C7, 0));
        }
        while (list.size() < 32) {
            int n = list.size() + 1;
            list.add(new DeckKey("\u2728", "MACRO " + n, "HOTKEY F" + ((n % 12) + 1), 0xFF1E293B, 0));
        }
        return list;
    }

    private void loadOrInitProfiles() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (int p = 0; p < PROFILE_NAMES.length; p++) {
            List<DeckKey> list = new ArrayList<DeckKey>();
            String raw = sp.getString("profile_json_" + p, "");
            if (!raw.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        list.add(DeckKey.fromJson(arr.getJSONObject(i)));
                    }
                } catch (Throwable ignored) {}
            }
            if (list.size() < 32) {
                List<DeckKey> defaults = buildDefaultProfile(p);
                for (int i = list.size(); i < defaults.size(); i++) list.add(defaults.get(i));
            }
            profiles[p] = list;
        }
    }

    private void saveProfile(int p) {
        try {
            JSONArray arr = new JSONArray();
            List<DeckKey> list = profiles[p];
            for (int i = 0; i < list.size(); i++) arr.put(list.get(i).toJson());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("profile_json_" + p, arr.toString())
                    .commit();
        } catch (Throwable ignored) {}
    }

    private void renderProfileTabs() {
        profileStrip.removeAllViews();
        for (int i = 0; i < PROFILE_NAMES.length; i++) {
            final int idx = i;
            boolean sel = (i == activeProfile);
            TextView tab = new TextView(this);
            tab.setText(PROFILE_NAMES[i]);
            tab.setTextColor(sel ? Color.WHITE : 0xFF94A3B8);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(10), dp(7), dp(10), dp(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(sel ? 0xFF0284C7 : 0xFF111827);
            bg.setStroke(dp(1), sel ? 0xFF7DD3FC : 0xFF1F2937);
            tab.setBackgroundDrawable(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < PROFILE_NAMES.length - 1) lp.rightMargin = dp(8);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeProfile = idx;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("active_profile", idx).commit();
                    renderProfileTabs();
                    renderDeckMatrix();
                }
            });
            profileStrip.addView(tab, lp);
        }
    }

    private void renderDeckMatrix() {
        deckMatrixContainer.removeAllViews();
        int rows = GRID_PRESETS[gridPresetIdx][0];
        int cols = GRID_PRESETS[gridPresetIdx][1];
        final List<DeckKey> keys = profiles[activeProfile];

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r < rows - 1) rlp.bottomMargin = dp(8);

            for (int c = 0; c < cols; c++) {
                final int keyIndex = r * cols + c;
                final DeckKey key = keys.get(keyIndex);
                final FrameLayout bezel = buildLcdStreamDeckKey(key, keyIndex, rows >= 4);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c < cols - 1) clp.rightMargin = dp(8);
                row.addView(bezel, clp);
            }
            deckMatrixContainer.addView(row, rlp);
        }
    }

    private FrameLayout buildLcdStreamDeckKey(final DeckKey key, final int keyIndex, boolean compact) {
        final FrameLayout outer = new FrameLayout(this);
        outer.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable chassis = new GradientDrawable();
        chassis.setColor(0xFF030712);
        chassis.setCornerRadius(dp(16));
        chassis.setStroke(dp(1), editKeysMode ? 0xFFFBBF24 : 0xFF1E293B);
        outer.setBackgroundDrawable(chassis);

        final LinearLayout lcd = new LinearLayout(this);
        lcd.setOrientation(LinearLayout.VERTICAL);
        lcd.setGravity(Gravity.CENTER);
        lcd.setPadding(dp(6), dp(4), dp(6), dp(4));
        lcd.setBackgroundDrawable(makeKeyLcdDrawable(key.color, key.activeState));

        TextView glyph = new TextView(this);
        glyph.setText(key.icon);
        glyph.setTextColor(Color.WHITE);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 22f : 28f);
        glyph.setGravity(Gravity.CENTER);
        lcd.addView(glyph);

        TextView title = new TextView(this);
        title.setText(key.activeState ? ("\u25CF " + key.label) : key.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 10.5f : 12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        lcd.addView(title);

        if (key.mode == 2 || editKeysMode) {
            TextView sub = new TextView(this);
            sub.setText(editKeysMode ? key.command : "HOLD PTT");
            sub.setTextColor(0xCCBAE6FD);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setGravity(Gravity.CENTER);
            lcd.addView(sub);
        }

        outer.addView(lcd, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        outer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start();
                    pulseHaptic(18);
                    if (!editKeysMode && key.mode == 2) {
                        key.activeState = true;
                        lcd.setBackgroundDrawable(makeKeyLcdDrawable(0xFF10B981, true));
                        sendDeckPacket(buildActionJson(key.command, key.label, 2, true));
                    }
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                    if (!editKeysMode && key.mode == 2) {
                        key.activeState = false;
                        lcd.setBackgroundDrawable(makeKeyLcdDrawable(key.color, false));
                        sendDeckPacket(buildActionJson(key.command, key.label, 2, false));
                        return true;
                    }
                }
                return false;
            }
        });

        outer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (editKeysMode) {
                    showEditKeyDialog(key, keyIndex);
                    return;
                }
                if ("@SECOND_SCREEN".equals(key.command)) {
                    startActivity(new Intent(Arc7StreamDeckActivity.this, Arc7SecondScreenActivity.class));
                    return;
                }
                if (key.mode == 1) {
                    key.activeState = !key.activeState;
                    renderDeckMatrix();
                }
                sendDeckPacket(buildActionJson(key.command, key.label, key.mode, key.activeState));
            }
        });

        outer.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showEditKeyDialog(key, keyIndex);
                return true;
            }
        });
        return outer;
    }

    private GradientDrawable makeKeyLcdDrawable(int baseColor, boolean lit) {
        int top = lit ? brighten(baseColor, 1.32f) : brighten(baseColor, 0.95f);
        int bot = lit ? baseColor : darken(baseColor, 0.52f);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bot});
        g.setCornerRadius(dp(13));
        g.setStroke(dp(lit ? 2 : 1), lit ? Color.WHITE : brighten(baseColor, 1.35f));
        return g;
    }

    private int brighten(int c, float f) {
        int r = Math.min(255, Math.round(Color.red(c) * f + 24));
        int g = Math.min(255, Math.round(Color.green(c) * f + 24));
        int b = Math.min(255, Math.round(Color.blue(c) * f + 24));
        return Color.argb(255, r, g, b);
    }

    private int darken(int c, float f) {
        return Color.argb(255,
                Math.max(0, Math.round(Color.red(c) * f)),
                Math.max(0, Math.round(Color.green(c) * f)),
                Math.max(0, Math.round(Color.blue(c) * f)));
    }

    /* ==================== Key Editor & PC Connection Dialogs ==================== */

    private void showEditKeyDialog(final DeckKey key, final int index) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        final EditText iconEt = new EditText(this);
        iconEt.setText(key.icon);
        iconEt.setHint("Emoji or 2-3 char badge (e.g. \uD83D\uDD34, OBS, PTT)");
        iconEt.setSingleLine(true);
        box.addView(makeFieldLabel("Button Icon / Glyph:"));
        box.addView(iconEt);

        final EditText labelEt = new EditText(this);
        labelEt.setText(key.label);
        labelEt.setHint("Button Label (e.g. DISCORD MUTE)");
        labelEt.setSingleLine(true);
        box.addView(makeFieldLabel("Button Title:"));
        box.addView(labelEt);

        final EditText cmdEt = new EditText(this);
        cmdEt.setText(key.command);
        cmdEt.setHint("HOTKEY CTRL+SHIFT+M | OBS_SCENE Live | OPEN https://... | TEXT hi | RUN calc");
        cmdEt.setSingleLine(true);
        box.addView(makeFieldLabel("PC Action Command (`HOTKEY ...`, `MEDIA ...`, `OBS_SCENE ...`, `OPEN ...`, `TEXT ...`, `RUN ...`):"));
        box.addView(cmdEt);

        final int[] chosenColor = new int[]{key.color};
        final int[] chosenMode = new int[]{key.mode};

        Button colorPick = Arc7Skin.secondaryButton(this, pal, "Choose Key Glow Color");
        colorPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(Arc7StreamDeckActivity.this)
                        .setTitle("Key Glow Color")
                        .setItems(KEY_PALETTE_NAMES, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { chosenColor[0] = KEY_PALETTE[w]; }
                        }).show();
            }
        });
        box.addView(colorPick);

        final String[] modeLabels = new String[]{
                "Tap Trigger (Standard Macro / Shortcut)",
                "Stateful ON/OFF Glow Toggle (Mute / Stream / Record)",
                "Hold-to-Talk Momentary Key (Press = KeyDown, Release = KeyUp)"
        };
        Button modePick = Arc7Skin.secondaryButton(this, pal, "Mode: " + modeLabels[key.mode]);
        modePick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                new AlertDialog.Builder(Arc7StreamDeckActivity.this)
                        .setTitle("Button Trigger Behavior")
                        .setItems(modeLabels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                chosenMode[0] = w;
                                ((Button) v).setText("Mode: " + modeLabels[w]);
                            }
                        }).show();
            }
        });
        box.addView(modePick);

        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Customize Key #" + (index + 1))
                .setView(sv)
                .setPositiveButton("Save Key", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        key.icon = iconEt.getText().toString().trim().isEmpty() ? "\u26A1" : iconEt.getText().toString().trim();
                        key.label = labelEt.getText().toString().trim().isEmpty() ? "MACRO" : labelEt.getText().toString().trim();
                        key.command = cmdEt.getText().toString().trim();
                        key.color = chosenColor[0];
                        key.mode = chosenMode[0];
                        saveProfile(activeProfile);
                        renderDeckMatrix();
                    }
                })
                .setNeutralButton("Swap Key Slot...", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int total = GRID_PRESETS[gridPresetIdx][0] * GRID_PRESETS[gridPresetIdx][1];
                        String[] slotNames = new String[total];
                        final List<DeckKey> cur = profiles[activeProfile];
                        for (int i = 0; i < total; i++) {
                            slotNames[i] = "#" + (i + 1) + "  " + cur.get(i).icon + " " + cur.get(i).label;
                        }
                        new AlertDialog.Builder(Arc7StreamDeckActivity.this)
                                .setTitle("Move / Swap Key With Slot")
                                .setItems(slotNames, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int target) {
                                        DeckKey tmp = cur.get(index);
                                        cur.set(index, cur.get(target));
                                        cur.set(target, tmp);
                                        saveProfile(activeProfile);
                                        renderDeckMatrix();
                                    }
                                }).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView makeFieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF93C5FD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
    }

    private void showConnectPcDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView guide = new TextView(this);
        guide.setText(
                "1. On your Windows / Linux / Mac PC, run:\n"
                + "     Start-PC-Stream-Deck.bat   (or: python3 Start-PC-Stream-Deck.py)\n\n"
                + "2. Works automatically in ALL 3 modes:\n"
                + "   \u2022 Wi-Fi LAN Auto-Discovery (UDP + TCP port 18991)\n"
                + "   \u2022 Zero-Lag USB Cable: plug Kobo into PC (works offline / at school!)\n"
                + "   \u2022 Remote Tailscale IP (`100.x.y.z`): control your home PC from anywhere!\n\n"
                + "Optional manual PC IP or Tailscale IP (`100.x.y.z`):");
        guide.setTextColor(0xFFE2E8F0);
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        box.addView(guide);

        final EditText ipEt = new EditText(this);
        ipEt.setText(pcHostIp);
        ipEt.setHint("Auto-Discover LAN / USB, or enter e.g. 192.168.1.25 / 100.88.1.4");
        ipEt.setSingleLine(true);
        box.addView(ipEt);

        new AlertDialog.Builder(this)
                .setTitle("Connect Kobo Stream Deck to PC")
                .setView(box)
                .setPositiveButton("Connect Now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pcHostIp = ipEt.getText().toString().trim();
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("pc_host_ip", pcHostIp).commit();
                        closeActiveSocket();
                        Toast.makeText(Arc7StreamDeckActivity.this,
                                pcHostIp.isEmpty() ? "Scanning USB + LAN for PC..." : ("Connecting to " + pcHostIp + ":18991..."),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Reset Default Keys", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        profiles[activeProfile] = buildDefaultProfile(activeProfile);
                        saveProfile(activeProfile);
                        renderDeckMatrix();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /* ==================== Bidirectional TCP + UDP Auto-Discovery Protocol ==================== */

    public static String buildActionJson(String cmd, String label, int mode, boolean downOrActive) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "deck_action");
            o.put("cmd", cmd == null ? "" : cmd);
            o.put("label", label == null ? "" : label);
            o.put("mode", mode);
            o.put("active", downOrActive);
            return o.toString() + "\n";
        } catch (Throwable t) {
            return "{\"type\":\"deck_action\",\"cmd\":\"\"}\n";
        }
    }

    private void sendDeckPacket(final String line) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream os = activeOut;
                    if (os != null) {
                        synchronized (os) {
                            os.write(line.getBytes("UTF-8"));
                            os.flush();
                        }
                    }
                } catch (Throwable t) {
                    closeActiveSocket();
                }
            }
        }).start();
    }

    private void startUsbAndInboundServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    usbOrReverseServer = new ServerSocket();
                    usbOrReverseServer.setReuseAddress(true);
                    usbOrReverseServer.bind(new InetSocketAddress(STREAM_DECK_PORT));
                    while (running) {
                        Socket s = usbOrReverseServer.accept();
                        attachConnectedSocket(s, "USB / Direct");
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void startAutoDiscoveryAndClientLoop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    if (activeSocket == null || activeSocket.isClosed()) {
                        String target = pcHostIp;
                        if (target == null || target.isEmpty()) {
                            target = discoverPcViaUdpBroadcast();
                        }
                        if (target != null && !target.isEmpty() && !target.equals(localWifiIp())) {
                            try {
                                Socket s = new Socket();
                                s.connect(new InetSocketAddress(target, STREAM_DECK_PORT), 2200);
                                attachConnectedSocket(s, target);
                            } catch (Throwable ignored) {}
                        }
                    }
                    try { Thread.sleep(2200L); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private String discoverPcViaUdpBroadcast() {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            ds.setSoTimeout(900);
            byte[] probe = "ARC7_STREAMDECK_DISCOVER_V1".getBytes("UTF-8");
            DatagramPacket pkt = new DatagramPacket(
                    probe, probe.length, InetAddress.getByName("255.255.255.255"), STREAM_DECK_PORT);
            ds.send(pkt);
            byte[] buf = new byte[256];
            DatagramPacket rep = new DatagramPacket(buf, buf.length);
            ds.receive(rep);
            String txt = new String(rep.getData(), 0, rep.getLength(), "UTF-8").trim();
            if (txt.startsWith("ARC7_STREAMDECK_PC")) {
                return rep.getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        } finally {
            if (ds != null) try { ds.close(); } catch (Throwable ignored) {}
        }
        return "";
    }

    private void attachConnectedSocket(final Socket s, final String label) {
        closeActiveSocket();
        try {
            s.setTcpNoDelay(true);
            activeSocket = s;
            activeOut = s.getOutputStream();
            sendDeckPacket("{\"type\":\"hello\",\"device\":\"Kobo Arc 7\",\"version\":\"2.3.0\"}\n");
            ui.post(new Runnable() {
                @Override
                public void run() {
                    connBadgeView.setText("\u25CF LIVE: " + label);
                    connBadgeView.setTextColor(0xFF34D399);
                }
            });

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        String line;
                        while (running && (line = br.readLine()) != null) {
                            handlePcInboundMessage(line);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (activeSocket == s) closeActiveSocket();
                    }
                }
            }).start();
        } catch (Throwable t) {
            closeActiveSocket();
        }
    }

    private void handlePcInboundMessage(String rawLine) {
        try {
            final JSONObject o = new JSONObject(rawLine);
            String type = o.optString("type", "");
            if ("telemetry".equals(type)) {
                final String host = o.optString("host", "PC");
                final int cpu = o.optInt("cpu", 0);
                final int ram = o.optInt("ram", 0);
                final boolean obs = o.optBoolean("obs", false);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        telemetryView.setText("   Host: " + host
                                + "  \u2022  CPU " + cpu + "%  \u2022  RAM " + ram + "%"
                                + (obs ? "  \u2022  OBS WebSocket LIVE" : "  \u2022  Studio: http://127.0.0.1:18995"));
                    }
                });
            } else if ("push_profile".equals(type)) {
                final int pIdx = Math.max(0, Math.min(PROFILE_NAMES.length - 1, o.optInt("profile", activeProfile)));
                JSONArray arr = o.optJSONArray("keys");
                if (arr != null && arr.length() > 0) {
                    List<DeckKey> updated = new ArrayList<DeckKey>();
                    for (int i = 0; i < arr.length(); i++) {
                        updated.add(DeckKey.fromJson(arr.getJSONObject(i)));
                    }
                    while (updated.size() < 32) {
                        updated.add(new DeckKey("\u2728", "MACRO", "HOTKEY F13", 0xFF1E293B, 0));
                    }
                    profiles[pIdx] = updated;
                    saveProfile(pIdx);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            activeProfile = pIdx;
                            renderProfileTabs();
                            renderDeckMatrix();
                            Toast.makeText(Arc7StreamDeckActivity.this,
                                    "Synced live profile from PC Browser Studio!", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void closeActiveSocket() {
        try { if (activeSocket != null) activeSocket.close(); } catch (Throwable ignored) {}
        activeSocket = null;
        activeOut = null;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (connBadgeView != null) {
                    connBadgeView.setText("\u25CF Waiting for PC (:" + STREAM_DECK_PORT + ")");
                    connBadgeView.setTextColor(0xFFFBBF24);
                }
            }
        });
    }

    private void pulseHaptic(long ms) {
        try { if (vibrator != null) vibrator.vibrate(ms); } catch (Throwable ignored) {}
    }

    private String localWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                int ip = wi != null ? wi.getIpAddress() : 0;
                if (ip != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }
        } catch (Throwable ignored) {}
        return "USB/Tailscale";
    }

    @Override
    protected void onDestroy() {
        running = false;
        closeActiveSocket();
        try { if (usbOrReverseServer != null) usbOrReverseServer.close(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
