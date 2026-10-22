package ca.arc7freedom.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone Interactive Root (`#`) & User (`$`) Linux Terminal Emulator (`Arc7 Terminal`)
 * built natively for the Kobo Arc 7 (`1024x600`, Android 4.2.2 `API 17`).
 */
public class Arc7TerminalActivity extends Activity {

    private final ExecutorService shellPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> history = new ArrayList<String>();
    private RootShell shell;
    private int historyIdx = -1;

    private boolean rootMode = true;
    private String currentDir = "/sdcard";

    private Button btnPrivilegeMode;
    private TextView lblPrompt;
    private TextView txtScreen;
    private ScrollView scrollScreen;
    private EditText edtCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shell = new RootShell(this);
        rootMode = shell.isRootAvailable();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#020617"));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(Color.parseColor("#0F172A"));

        btnPrivilegeMode = chipButton("", "#DC2626", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!rootMode && !shell.isRootAvailable()) {
                    Toast.makeText(Arc7TerminalActivity.this,
                            "Not rooted yet — tap 'Root Kobo Arc 7 Now' on Tab 1 first!",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                rootMode = !rootMode;
                refreshPromptUi();
                appendConsole("\n[Switched session mode to " + (rootMode ? "ROOT (uid=0 #)" : "USER ($)") + "]\n");
            }
        });
        topBar.addView(btnPrivilegeMode);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[][] presets = {
            {"id", "id"},
            {"df / Storage", "df"},
            {"RAM / free", "while IFS= read -r l; do case \"$l\" in MemTotal:*|MemFree:*|Buffers:*|Cached:*|Swap*) echo \"$l\" ;; esac; done < /proc/meminfo"},
            {"Partitions", "cat /proc/emmc 2>/dev/null || cat /proc/dumchar_info 2>/dev/null || cat /proc/partitions"},
            {"Mounts", "mount"},
            {"Network", "netstat 2>/dev/null || cat /proc/net/route"},
            {"Kernel Version", "cat /proc/version && uptime"},
            {"Props", "getprop | while IFS= read -r l; do case \"$l\" in *product*|*build*|*usb*|*dns*) echo \"$l\" ;; esac; done"}
        };
        for (int i = 0; i < presets.length; i++) {
            final String cmdStr = presets[i][1];
            chips.addView(chipButton(presets[i][0], "#1E293B", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeCommand(cmdStr);
                }
            }));
        }
        chipScroll.addView(chips);
        topBar.addView(chipScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topBar.addView(chipButton("Hist -", "#334155", new View.OnClickListener() {
            @Override public void onClick(View v) { browseHistory(-1); }
        }));
        topBar.addView(chipButton("Save", "#0284C7", new View.OnClickListener() {
            @Override public void onClick(View v) { saveTranscript(); }
        }));
        topBar.addView(chipButton("Clear", "#475569", new View.OnClickListener() {
            @Override public void onClick(View v) { txtScreen.setText(""); }
        }));
        topBar.addView(chipButton("Close", "#64748B", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        root.addView(topBar);

        scrollScreen = new ScrollView(this);
        scrollScreen.setFillViewport(true);
        txtScreen = new TextView(this);
        txtScreen.setTextColor(Color.parseColor("#86EFAC"));
        txtScreen.setTypeface(Typeface.MONOSPACE);
        txtScreen.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        txtScreen.setPadding(dp(12), dp(10), dp(12), dp(10));
        txtScreen.setTextIsSelectable(true);
        txtScreen.setText(
                "Kobo Arc 7 Freedom — Interactive Root & Linux Shell (v1.7.0)\n" +
                "Native binaries on PATH: " + getApplicationInfo().nativeLibraryDir + "\n" +
                "Tip: Tap any quick-chip above or type any shell command below (`su`, `arc7tool`, `arc7tls`, `pm`, `ls`, etc.).\n\n");
        scrollScreen.addView(txtScreen);
        root.addView(scrollScreen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dp(10), dp(6), dp(10), dp(8));
        inputBar.setBackgroundColor(Color.parseColor("#1E293B"));

        lblPrompt = new TextView(this);
        lblPrompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblPrompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        lblPrompt.setPadding(0, 0, dp(8), 0);
        inputBar.addView(lblPrompt);

        edtCommand = new EditText(this);
        edtCommand.setSingleLine(true);
        edtCommand.setTypeface(Typeface.MONOSPACE);
        edtCommand.setTextColor(Color.WHITE);
        edtCommand.setHint("Enter shell command...");
        edtCommand.setHintTextColor(Color.parseColor("#64748B"));
        edtCommand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        edtCommand.setBackgroundColor(Color.parseColor("#0F172A"));
        edtCommand.setPadding(dp(10), dp(8), dp(10), dp(8));
        edtCommand.setImeOptions(EditorInfo.IME_ACTION_SEND);
        edtCommand.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    submitInput();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams edtLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        edtLp.rightMargin = dp(8);
        inputBar.addView(edtCommand, edtLp);

        inputBar.addView(chipButton("Run", "#16A34A", new View.OnClickListener() {
            @Override public void onClick(View v) { submitInput(); }
        }));
        root.addView(inputBar);

        setContentView(root);
        refreshPromptUi();
    }

    @SuppressWarnings("deprecation")
    private void refreshPromptUi() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(Color.parseColor(rootMode ? "#DC2626" : "#0284C7"));
        btnPrivilegeMode.setBackgroundDrawable(bg);
        btnPrivilegeMode.setText(rootMode ? "ROOT (#)" : "USER ($)");
        lblPrompt.setTextColor(Color.parseColor(rootMode ? "#FCA5A5" : "#38BDF8"));
        lblPrompt.setText((rootMode ? "root@arc7:" : "u0@arc7:") + currentDir + (rootMode ? " # " : " $ "));
    }

    @SuppressWarnings("deprecation")
    private Button chipButton(String text, String hex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(Color.parseColor(hex));
        b.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void submitInput() {
        String cmd = edtCommand.getText().toString().trim();
        if (cmd.isEmpty()) return;
        edtCommand.setText("");
        history.add(cmd);
        historyIdx = history.size();
        executeCommand(cmd);
    }

    private void browseHistory(int delta) {
        if (history.isEmpty()) return;
        historyIdx = Math.max(0, Math.min(history.size() - 1, historyIdx + delta));
        edtCommand.setText(history.get(historyIdx));
        edtCommand.setSelection(edtCommand.getText().length());
    }

    private void executeCommand(final String cmd) {
        appendConsole(lblPrompt.getText().toString() + cmd + "\n");
        final String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        shellPool.execute(new Runnable() {
            @Override
            public void run() {
                String wrapped = "export PATH='" + nativeLibDir + "':/system/xbin:/system/bin:/sbin:$PATH\n" +
                        "cd '" + currentDir.replace("'", "'\\''") + "' 2>/dev/null || cd /\n" +
                        cmd + "\n" +
                        "echo __ARC7_PWD__=\"$(pwd)\"\n";
                final RootShell.ExecResult r = rootMode
                        ? shell.execRoot(wrapped, null)
                        : shell.execSh(wrapped, null);

                String raw = r.stdout == null ? "" : r.stdout;
                int marker = raw.lastIndexOf("__ARC7_PWD__=");
                if (marker >= 0) {
                    String tail = raw.substring(marker + "__ARC7_PWD__=".length()).trim();
                    int nl = tail.indexOf('\n');
                    currentDir = (nl >= 0 ? tail.substring(0, nl) : tail).trim();
                    raw = raw.substring(0, marker);
                }
                final String display = raw;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!display.isEmpty()) appendConsole(display + (display.endsWith("\n") ? "" : "\n"));
                        refreshPromptUi();
                    }
                });
            }
        });
    }

    private void appendConsole(String text) {
        txtScreen.append(text);
        scrollScreen.post(new Runnable() {
            @Override
            public void run() {
                scrollScreen.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void saveTranscript() {
        try {
            File out = new File(Environment.getExternalStorageDirectory(),
                    "Download/arc7-terminal-" + (System.currentTimeMillis() / 1000L) + ".txt");
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(txtScreen.getText().toString().getBytes("UTF-8"));
            fos.close();
            Toast.makeText(this, "Saved -> " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Save failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        shellPool.shutdownNow();
        super.onDestroy();
    }
}
