package ca.arc7freedom.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * Chromebook / PC / Mac Extended Second Display & 12-Key Macro Deck (`Arc7 Second Screen`, `v2.2.0`):
 *
 *   - **Mode 1 — High-Speed USB (`adb forward tcp:18900 tcp:18900`) or Wi-Fi (`:18900`) Monitor:**
 *     Streams live desktop JPEG frames straight onto the Kobo Arc 7 `1280x800` IPS panel while
 *     sending normalized `TOUCH x y` & `MACRO id` events back over the same TCP socket to
 *     `tools/Start-Kobo-Second-Screen.py`!
 *   - **Mode 2 — 12-Key Touch Macro Pad:** Instant hardware/desktop shortcut triggers!
 */
public class Arc7SecondScreenActivity extends Activity {

    public static final int PORT = 18900;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Arc7Skin.Palette pal;
    private ImageView monitorSurface;
    private LinearLayout macroGrid;
    private TextView statusPill;
    private boolean macroMode = false;

    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private volatile OutputStream clientOut;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF05080F);

        LinearLayout hdrBtns = new LinearLayout(this);
        hdrBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button launchProDeck = Arc7Skin.actionButton(this, "\uD83C\uDF9B Open Stream Deck Pro", 0xFF0284C7);
        launchProDeck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(Arc7SecondScreenActivity.this, Arc7StreamDeckActivity.class));
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = dp(6);
        hdrBtns.addView(launchProDeck, dlp);

        Button switchMode = Arc7Skin.actionButton(this, "Switch: Macro Deck / Monitor", 0xFF7C3AED);
        switchMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                macroMode = !macroMode;
                monitorSurface.setVisibility(macroMode ? View.GONE : View.VISIBLE);
                macroGrid.setVisibility(macroMode ? View.VISIBLE : View.GONE);
            }
        });
        hdrBtns.addView(switchMode);

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_display,
                "Second Screen & Macro Deck",
                "USB (`adb forward tcp:18900 tcp:18900`) or Wi-Fi `:18900` Extended Display + Touch Return",
                hdrBtns));

        statusPill = new TextView(this);
        statusPill.setText("Listening on " + localIpHint() + ":" + PORT
                + "  —  Run `python3 Start-Kobo-Second-Screen.py` on your Chromebook / PC / Mac!");
        statusPill.setTextColor(0xFF67E8F9);
        statusPill.setBackgroundColor(0xFF0F172A);
        statusPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        statusPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(statusPill);

        FrameLayout stage = new FrameLayout(this);

        monitorSurface = new ImageView(this);
        monitorSurface.setScaleType(ImageView.ScaleType.FIT_CENTER);
        monitorSurface.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) {
                    float nx = Math.max(0f, Math.min(1f, ev.getX() / Math.max(1f, v.getWidth())));
                    float ny = Math.max(0f, Math.min(1f, ev.getY() / Math.max(1f, v.getHeight())));
                    sendControlLine(String.format(Locale.US, "TOUCH %.4f %.4f %d\n", nx, ny, ev.getAction()));
                }
                return true;
            }
        });
        stage.addView(monitorSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        macroGrid = buildMacroDeck();
        macroGrid.setVisibility(View.GONE);
        stage.addView(macroGrid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        startFrameServer();
    }

    private LinearLayout buildMacroDeck() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(16), dp(16), dp(16), dp(16));
        String[][] keys = new String[][]{
                {"⏯ Play / Pause", "MEDIA_PLAY_PAUSE"}, {"⏮ Prev Track", "MEDIA_PREV"},
                {"⏭ Next Track", "MEDIA_NEXT"}, {"🔇 Mute Audio", "VOL_MUTE"},
                {"🔉 Volume Down", "VOL_DOWN"}, {"🔊 Volume Up", "VOL_UP"},
                {"📸 Screenshot", "SCREENSHOT"}, {"🌐 New Tab", "BROWSER_NEW_TAB"},
                {"📋 Copy", "CLIP_COPY"}, {"📌 Paste", "CLIP_PASTE"},
                {"🔍 Zoom +", "ZOOM_IN"}, {"🔒 Lock Host", "LOCK_SCREEN"}
        };
        int[] rowColors = new int[]{0xFF0284C7, 0xFF059669, 0xFF7C3AED};
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r < 2) rlp.bottomMargin = dp(10);
            for (int c = 0; c < 4; c++) {
                final String label = keys[r * 4 + c][0];
                final String cmd = keys[r * 4 + c][1];
                Button b = Arc7Skin.actionButton(this, label, rowColors[r]);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendControlLine("MACRO " + cmd + "\n");
                        Toast.makeText(Arc7SecondScreenActivity.this, "Sent: " + label, Toast.LENGTH_SHORT).show();
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c < 3) clp.rightMargin = dp(10);
                row.addView(b, clp);
            }
            grid.addView(row, rlp);
        }
        return grid;
    }

    private void sendControlLine(final String line) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream os = clientOut;
                    if (os != null) {
                        synchronized (os) {
                            os.write(line.getBytes("UTF-8"));
                            os.flush();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void startFrameServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    while (running) {
                        final Socket sock = serverSocket.accept();
                        sock.setTcpNoDelay(true);
                        clientOut = sock.getOutputStream();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                statusPill.setText("● CONNECTED TO HOST (" + sock.getInetAddress().getHostAddress() + ")");
                                statusPill.setTextColor(Color.GREEN);
                            }
                        });
                        try {
                            DataInputStream dis = new DataInputStream(sock.getInputStream());
                            while (running) {
                                int len = dis.readInt(); // 4-byte big-endian JPEG byte length
                                if (len <= 0 || len > 4 * 1024 * 1024) break;
                                byte[] jpg = new byte[len];
                                dis.readFully(jpg);
                                final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, len);
                                if (bmp != null) {
                                    ui.post(new Runnable() {
                                        @Override public void run() { monitorSurface.setImageBitmap(bmp); }
                                    });
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            clientOut = null;
                            try { sock.close(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private String localIpHint() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            int ip = wi != null ? wi.getIpAddress() : 0;
            if (ip != 0) {
                return String.format(Locale.US, "%d.%d.%d.%d",
                        (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
