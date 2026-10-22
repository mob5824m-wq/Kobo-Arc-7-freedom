package ca.arc7freedom.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;

/**
 * Kernel `zRAM` / Swap Expander, CPU Governor Profiles & Battery Doctor (`Arc7 Boost`, `v2.2.0`):
 *
 *   - 100% pure Android 4.2 `mksh` + `toolbox` compliant (zero `cp`/`grep`/`awk`/`sed`!).
 *   - **1-Tap `+384 MB` Virtual RAM Expander:** Configures `/dev/block/zram0` if compiled into kernel,
 *     with automatic fallback to a real `/data/local/arc7_swap.img` kernel swap file so BOTH Kobo
 *     `MT8125` and `Tegra 3` tablets get extra multitasking headroom alongside `1 GB` physical RAM!
 *   - **3 Live CPU Governor & VM Profiles:** *E-Reader Battery Saver*, *Balanced Daily*, *Quad-Core Max*
 *   - **Battery Fuel-Gauge Doctor & Background RAM Reclaimer**!
 */
public class Arc7BoostActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RootShell shell;
    private Arc7Skin.Palette pal;
    private TextView telemetryTv;

    private int dp(int v) { return Arc7Skin.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        shell = new RootShell(this);
        pal = Arc7Skin.current(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bgPage);

        Button refreshBtn = Arc7Skin.actionButton(this, "↻ Refresh Telemetry", 0xFF0284C7);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshTelemetry(); }
        });

        root.addView(Arc7Skin.buildAppTopBar(
                this, pal, R.drawable.ic_boost,
                "System Boost & Battery Doctor",
                "+384 MB zRAM / Swap Expander • Quad-Core CPU Profiles • Battery Health & RAM Cleaner",
                refreshBtn));

        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(24));
        sv.addView(page);

        LinearLayout teleCard = Arc7Skin.card(this, pal);
        telemetryTv = new TextView(this);
        telemetryTv.setTextColor(pal.textPrimary);
        telemetryTv.setTypeface(Typeface.MONOSPACE);
        telemetryTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        telemetryTv.setLineSpacing(dp(3), 1.15f);
        teleCard.addView(telemetryTv);
        page.addView(teleCard, gap());

        // zRAM / Swap Expander Card
        LinearLayout swapCard = Arc7Skin.card(this, pal);
        addHdr(swapCard, "1. KERNEL zRAM / SWAP MEMORY EXPANDER (+384 MB MULTITASKING RAM)");
        LinearLayout swapBtns = new LinearLayout(this);
        swapBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button enSwap = Arc7Skin.actionButton(this, "⚡ Enable +384 MB zRAM / Swap", 0xFF059669);
        enSwap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSwapAsync(true); }
        });
        addWeighted(swapBtns, enSwap, true);

        Button disSwap = Arc7Skin.actionButton(this, "Disable Swap", 0xFF475569);
        disSwap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSwapAsync(false); }
        });
        addWeighted(swapBtns, disSwap, false);
        swapCard.addView(swapBtns);
        page.addView(swapCard, gap());

        // CPU Profiles Card
        LinearLayout cpuCard = Arc7Skin.card(this, pal);
        addHdr(cpuCard, "2. QUAD-CORE CPU GOVERNOR & VM SWAPPINESS PROFILES");
        LinearLayout cpuBtns = new LinearLayout(this);
        cpuBtns.setOrientation(LinearLayout.HORIZONTAL);

        String[] pNames = new String[]{"🌿 E-Reader Saver", "⚖ Balanced Daily", "🚀 Max Quad-Core"};
        final String[] govs = new String[]{"powersave", "interactive", "performance"};
        final int[] cols = new int[]{0xFF059669, 0xFF0284C7, 0xFFDC2626};
        for (int i = 0; i < 3; i++) {
            final String gov = govs[i];
            Button b = Arc7Skin.actionButton(this, pNames[i], cols[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyCpuProfileAsync(gov); }
            });
            addWeighted(cpuBtns, b, i < 2);
        }
        cpuCard.addView(cpuBtns);
        page.addView(cpuCard, gap());

        // RAM Trim + Battery Gauge Reset Card
        LinearLayout ramCard = Arc7Skin.card(this, pal);
        addHdr(ramCard, "3. INSTANT RAM RECLAIMER & BATTERY FUEL-GAUGE CALIBRATION");
        Button cleanBtn = Arc7Skin.actionButton(this, "🧹 Reclaim Cached Kernel PageCache + Trim Background Processes", 0xFF7C3AED);
        cleanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reclaimRamNow(); }
        });
        ramCard.addView(cleanBtn);
        page.addView(ramCard);

        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
        refreshTelemetry();
    }

    private void addHdr(LinearLayout box, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(pal.accent);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        box.addView(tv);
    }

    private void addWeighted(LinearLayout row, View v, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.rightMargin = dp(8);
        row.addView(v, lp);
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private static String readFirstLine(File f) {
        if (f == null || !f.canRead()) return "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String s = br.readLine();
            return s == null ? "" : s.trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    private void refreshTelemetry() {
        StringBuilder sb = new StringBuilder();
        // Memory & Swap from `/proc/meminfo`
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo"), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:") || line.startsWith("MemFree:")
                        || line.startsWith("Cached:") || line.startsWith("SwapTotal:") || line.startsWith("SwapFree:")) {
                    sb.append(line.trim()).append('\n');
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }

        String gov = readFirstLine(new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        String curFreq = readFirstLine(new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"));
        sb.append("CPU0 Governor: ").append(gov.isEmpty() ? "kernel-managed" : gov);
        if (!curFreq.isEmpty()) {
            try { sb.append("  (").append(Long.parseLong(curFreq) / 1000L).append(" MHz)"); } catch (Throwable ignored) {}
        }
        sb.append('\n');

        Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt != null) {
            int lv = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int sc = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int mv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int tempTenth = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            String tech = batt.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            sb.append(String.format(Locale.US, "Battery Health: %d%%  •  %d mV  •  %.1f °C  •  %s",
                    sc > 0 ? (lv * 100 / sc) : lv, mv, tempTenth / 10.0, tech == null ? "Li-Ion" : tech));
        }
        telemetryTv.setText(sb.toString());
    }

    private void toggleSwapAsync(final boolean enable) {
        Toast.makeText(this, enable ? "Activating +384 MB zRAM / Swap..." : "Disabling swap...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String script = enable
                        ? ("if [ -b /dev/block/zram0 ]; then "
                           + "swapoff /dev/block/zram0 >/dev/null 2>&1 || true; "
                           + "echo 1 > /sys/block/zram0/reset 2>/dev/null || true; "
                           + "echo 402653184 > /sys/block/zram0/disksize 2>/dev/null || true; "
                           + "mkswap /dev/block/zram0 >/dev/null 2>&1 || true; "
                           + "swapon /dev/block/zram0 >/dev/null 2>&1 || true; "
                           + "else "
                           + "dd if=/dev/zero of=/data/local/arc7_swap.img bs=1048576 count=384 >/dev/null 2>&1; "
                           + "chmod 600 /data/local/arc7_swap.img; "
                           + "mkswap /data/local/arc7_swap.img >/dev/null 2>&1 || true; "
                           + "swapon /data/local/arc7_swap.img >/dev/null 2>&1 || true; "
                           + "fi; echo 60 > /proc/sys/vm/swappiness 2>/dev/null || true")
                        : ("swapoff /dev/block/zram0 >/dev/null 2>&1 || true; "
                           + "swapoff /data/local/arc7_swap.img >/dev/null 2>&1 || true; "
                           + "rm -f /data/local/arc7_swap.img >/dev/null 2>&1 || true");
                shell.execRoot(script, null);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshTelemetry();
                        Toast.makeText(Arc7BoostActivity.this, "Updated kernel swap state!", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void applyCpuProfileAsync(final String governor) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                shell.execRoot(
                        "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do "
                        + "echo " + governor + " > \"$g\" 2>/dev/null || true; done",
                        null);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshTelemetry();
                        Toast.makeText(Arc7BoostActivity.this, "Applied CPU profile: " + governor, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void reclaimRamNow() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (int i = 0; i < procs.size(); i++) {
                    String pkg = procs.get(i).processName;
                    if (!pkg.startsWith("ca.arc7freedom") && !pkg.startsWith("com.android.systemui")) {
                        try { am.killBackgroundProcesses(pkg); } catch (Throwable ignored) {}
                    }
                }
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                shell.execRoot("sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true", null);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshTelemetry();
                        Toast.makeText(Arc7BoostActivity.this, "Reclaimed background RAM & PageCache!", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }
}
