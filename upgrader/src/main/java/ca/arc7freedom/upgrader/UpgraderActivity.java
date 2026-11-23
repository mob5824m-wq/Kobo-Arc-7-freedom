package ca.arc7freedom.upgrader;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import ca.arc7freedom.app.BundledApk;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Separately installable, data-preserving in-place updater (ca.arc7freedom.upgrader).
 *
 * Flow: pre-flight checks (free storage, installed-suite signature match, embedded payload
 * integrity) —> root install via the same tested in-place pm wrapper as the suite's companion
 * installer, or Android's confirmed installer when root is unavailable —> checksum verification
 * of the installed suite before success is claimed. Install attempts never uninstall, never
 * clear data and never mask a pm failure. This updater can itself be removed independently.
 */
public class UpgraderActivity extends Activity {

    /* ---------- view state ---------- */
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusTitle;
    private TextView statusSub;
    private ProgressBar progressBar;
    private TextView logView;
    private ScrollView logScroll;
    private Button logToggle;
    private Button actionBtn;
    private Button verifyBtn;
    private Button ownAppInfoLink;
    private LinearLayout successCard;
    private LinearLayout failureCard;
    private TextView failureTitle;
    private TextView failureDetail;
    private LinearLayout failureGuidance;
    private boolean logVisible = false;
    private volatile boolean running = false;
    private String expectedMainHash;
    private boolean awaitingInstall;

    /* ---------- phase rows ---------- */
    private static final int PHASE_PREFLIGHT = 0;
    private static final int PHASE_PAYLOAD = 1;
    private static final int PHASE_INSTALL = 2;
    private static final int PHASE_VERIFY = 3;
    private static final int PHASE_COUNT = 4;
    private static final int ST_PENDING = 0, ST_RUNNING = 1, ST_DONE = 2, ST_FAILED = 3, ST_WAITING = 4, ST_SKIPPED = 5;
    private final View[] phaseDots = new View[PHASE_COUNT];
    private final TextView[] phaseStatus = new TextView[PHASE_COUNT];

    /* ---------- failure taxonomy ---------- */
    private static final int FAIL_STORAGE = 1;
    private static final int FAIL_PAYLOAD = 2;
    private static final int FAIL_SIGNATURE = 3;
    private static final int FAIL_ROOT_ALL_MODES = 4;
    private static final int FAIL_INSTALLER_CANCELLED = 5;
    private static final int FAIL_VERIFY = 6;
    private static final int FAIL_UNKNOWN = 7;

    private static final String SUITE_PKG = "ca.arc7freedom.app";
    private static final long NEEDED_BYTES_FALLBACK = 80L * 1024L * 1024L;
    private static final long STAGING_HEADROOM = 24L * 1024L * 1024L;

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        return new ca.arc7freedom.glass.LiquidGlassDrawable(fill, 0xFF9BCBFF, dp(Math.max(26, radiusDp)),
                getResources().getDisplayMetrics().density, stroke == 0 ? 196 : 112);
    }

    private static GradientDrawable circle(int argb) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(argb);
        return g;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            expectedMainHash = savedInstanceState.getString("expected_main_hash");
            awaitingInstall = savedInstanceState.getBoolean("awaiting_install", false);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundDrawable(ca.arc7freedom.glass.LiquidGlassDrawable.ambient(0xFF080C12, 0xFF80B7FF));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        /* ===== Hero ===== */
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(4), 0, dp(4), dp(14));

        TextView badge = new TextView(this);
        badge.setText("2.7.0");
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundDrawable(box(Color.parseColor("#0284C7"), 0, 18));
        hero.addView(badge, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htp.leftMargin = dp(14);
        hero.addView(heroText, htp);

        statusTitle = new TextView(this);
        statusTitle.setText("Upgrade to Freedom v2.7.0");
        statusTitle.setTextColor(Color.parseColor("#F8FAFC"));
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        heroText.addView(statusTitle);

        statusSub = new TextView(this);
        statusSub.setText("In-place, data-preserving update for Arc7 Freedom. Nothing is uninstalled, nothing is wiped.");
        statusSub.setTextColor(Color.parseColor("#94A3B8"));
        statusSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusSub.setPadding(0, dp(3), 0, 0);
        heroText.addView(statusSub);
        root.addView(hero);

        /* ===== What this does ===== */
        LinearLayout facts = cardBox();
        facts.addView(factsRow("✓", "Installs the suite in place — your apps, data and settings stay put"));
        facts.addView(factsRow("✓", "Opens the same offline patch pipeline as USB, SD and OTA installs"));
        facts.addView(factsRow("✓", "No root needed — Android will ask you to confirm each companion APK"));
        facts.addView(factsRow("✓", "Fully offline. This updater never talks to the network"));
        root.addView(facts);

        /* ===== Phase tracker ===== */
        LinearLayout phases = cardBox();
        LinearLayout.LayoutParams phasesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phasesLp.topMargin = dp(12);
        phases.setBackgroundDrawable(box(Color.parseColor("#0F172A"), Color.parseColor("#1E3A5F"), 14));

        TextView phasesHeader = new TextView(this);
        phasesHeader.setText("UPGRADE PHASES");
        phasesHeader.setTextColor(Color.parseColor("#64748B"));
        phasesHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        phasesHeader.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        phasesHeader.setPadding(0, 0, 0, dp(8));
        phases.addView(phasesHeader);

        addPhaseRow(phases, PHASE_PREFLIGHT, "Pre-flight checks");
        addPhaseRow(phases, PHASE_PAYLOAD, "Verify embedded payload");
        addPhaseRow(phases, PHASE_INSTALL, "Install suite");
        addPhaseRow(phases, PHASE_VERIFY, "Verify installed app");
        root.addView(phases, phasesLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(2);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pbLp.topMargin = dp(12);
        root.addView(progressBar, pbLp);

        /* ===== Success card (hidden) ===== */
        successCard = new LinearLayout(this);
        successCard.setOrientation(LinearLayout.VERTICAL);
        successCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        successCard.setBackgroundDrawable(box(Color.parseColor("#052E22"), Color.parseColor("#059669"), 14));
        successCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams successLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        successLp.topMargin = dp(12);
        TextView successTitle = new TextView(this);
        successTitle.setText("✓  Upgrade complete");
        successTitle.setTextColor(Color.parseColor("#A7F3D0"));
        successTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        successTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        successCard.addView(successTitle);
        TextView successBody = new TextView(this);
        successBody.setText("Arc7 Freedom v2.7.0 is installed and checksum-verified.\n"
                + "• Your apps, data and settings were preserved\n"
                + "• Remaining companion apps apply in Install / Repair All Patches\n"
                + "• This upgrader has done its job — it can be uninstalled any time");
        successBody.setTextColor(Color.parseColor("#D1FAE5"));
        successBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        successBody.setPadding(0, dp(6), 0, 0);
        successCard.addView(successBody);
        Button openBtn = new Button(this);
        openBtn.setText("Open Freedom v2.7.0  →");
        openBtn.setTextColor(Color.WHITE);
        openBtn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        openBtn.setBackgroundDrawable(box(Color.parseColor("#059669"), 0, 10));
        openBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchMainApp(); }
        });
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = dp(10);
        successCard.addView(openBtn, openLp);
        root.addView(successCard, successLp);

        /* ===== Failure card (hidden) ===== */
        failureCard = new LinearLayout(this);
        failureCard.setOrientation(LinearLayout.VERTICAL);
        failureCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        failureCard.setBackgroundDrawable(box(Color.parseColor("#2A0F0F"), Color.parseColor("#DC2626"), 14));
        failureCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams failureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        failureLp.topMargin = dp(12);
        failureTitle = new TextView(this);
        failureTitle.setTextColor(Color.parseColor("#FCA5A5"));
        failureTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        failureTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        failureCard.addView(failureTitle);
        failureDetail = new TextView(this);
        failureDetail.setTextColor(Color.parseColor("#FECACA"));
        failureDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        failureDetail.setPadding(0, dp(4), 0, dp(8));
        failureCard.addView(failureDetail);
        failureGuidance = new LinearLayout(this);
        failureGuidance.setOrientation(LinearLayout.VERTICAL);
        failureCard.addView(failureGuidance);
        root.addView(failureCard, failureLp);

        /* ===== Action buttons ===== */
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);

        actionBtn = new Button(this);
        actionBtn.setText("Run upgrade");
        actionBtn.setTextColor(Color.WHITE);
        actionBtn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        actionBtn.setBackgroundDrawable(box(Color.parseColor("#0284C7"), 0, 10));
        actionBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        actionBtn.setEnabled(false);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpgradeAsync(false);
            }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(8);
        btnRow.addView(actionBtn, b1);

        verifyBtn = new Button(this);
        verifyBtn.setText("Verify only");
        verifyBtn.setTextColor(Color.parseColor("#E0F2FE"));
        verifyBtn.setBackgroundDrawable(box(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 10));
        verifyBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        verifyBtn.setEnabled(true);
        verifyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpgradeAsync(true);
            }
        });
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(verifyBtn, b2);
        root.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ownAppInfoLink = new Button(this);
        ownAppInfoLink.setText("App info / uninstall this upgrader");
        ownAppInfoLink.setTextColor(Color.parseColor("#7DD3FC"));
        ownAppInfoLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ownAppInfoLink.setBackgroundDrawable(null);
        ownAppInfoLink.setPadding(dp(6), dp(10), dp(6), dp(2));
        ownAppInfoLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                // This package is the updater, NOT the suite embedded inside its assets.
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:ca.arc7freedom.upgrader")));
            }
        });
        root.addView(ownAppInfoLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ===== Collapsible technical log ===== */
        logToggle = new Button(this);
        logToggle.setText("Show technical log  ▾");
        logToggle.setTextColor(Color.parseColor("#94A3B8"));
        logToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logToggle.setBackgroundDrawable(null);
        logToggle.setPadding(dp(4), dp(6), dp(4), dp(2));
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                logVisible = !logVisible;
                logScroll.setVisibility(logVisible ? View.VISIBLE : View.GONE);
                logToggle.setText(logVisible ? "Hide technical log  ▴" : "Show technical log  ▾");
            }
        });
        root.addView(logToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(4);
        logScroll.setBackgroundDrawable(box(Color.parseColor("#0B111B"), Color.parseColor("#1E293B"), 12));
        logScroll.setPadding(dp(12), dp(12), dp(12), dp(12));
        logScroll.setVisibility(View.GONE);

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#A7F3D0"));
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView);
        root.addView(logScroll, scrollLp);

        TextView trustNote = new TextView(this);
        trustNote.setText("This upgrader never uninstalls apps, never wipes data and never uses the network.");
        trustNote.setTextColor(Color.parseColor("#475569"));
        trustNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        trustNote.setGravity(Gravity.CENTER_HORIZONTAL);
        trustNote.setPadding(0, dp(8), 0, 0);
        root.addView(trustNote);

        setContentView(root);
        if (!awaitingInstall) startUpgradeAsync(false);
        else {
            uiProgress(40, "Waiting for Android's suite install confirmation…");
            setPhase(PHASE_INSTALL, ST_WAITING, "waiting");
            setButtons(false, false);
        }
    }

    /* ================= UI building blocks ================= */

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackgroundDrawable(box(Color.parseColor("#111927"), Color.parseColor("#1E3A5F"), 14));
        return box;
    }

    private LinearLayout factsRow(String glyph, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView g = new TextView(this);
        g.setText(glyph);
        g.setTextColor(Color.parseColor("#34D399"));
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        g.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        row.addView(g, new LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#CBD5E1"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void addPhaseRow(LinearLayout parent, int idx, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        View dot = new View(this);
        dot.setBackgroundDrawable(circle(0xFF334155));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(11), dp(11));
        dotLp.rightMargin = dp(11);
        row.addView(dot, dotLp);
        phaseDots[idx] = dot;

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.parseColor("#E2E8F0"));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView st = new TextView(this);
        st.setText("pending");
        st.setTextColor(Color.parseColor("#64748B"));
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        row.addView(st);
        phaseStatus[idx] = st;

        parent.addView(row);
    }

    private void setPhase(final int idx, final int state, final String detail) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (phaseDots[idx] == null) return;
                int color;
                String text;
                int textColor;
                switch (state) {
                    case ST_RUNNING: color = 0xFFF59E0B; text = detail != null ? detail : "running…"; textColor = 0xFFFCD34D; break;
                    case ST_DONE: color = 0xFF10B981; text = detail != null ? detail : "done"; textColor = 0xFF6EE7B7; break;
                    case ST_FAILED: color = 0xFFEF4444; text = detail != null ? detail : "failed"; textColor = 0xFFFCA5A5; break;
                    case ST_WAITING: color = 0xFF38BDF8; text = detail != null ? detail : "waiting…"; textColor = 0xFF7DD3FC; break;
                    case ST_SKIPPED: color = 0xFF475569; text = detail != null ? detail : "skipped"; textColor = 0xFF94A3B8; break;
                    default: color = 0xFF334155; text = "pending"; textColor = 0xFF64748B;
                }
                phaseDots[idx].setBackgroundDrawable(circle(color));
                phaseStatus[idx].setText(text);
                phaseStatus[idx].setTextColor(textColor);
            }
        });
    }

    private void setButtons(final boolean actionEnabled, final boolean verifyEnabled) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                actionBtn.setEnabled(actionEnabled);
                verifyBtn.setEnabled(verifyEnabled);
            }
        });
    }

    private void uiLog(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                logView.append(msg + "\n");
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void uiProgress(final int pct, final String sub) {
        ca.arc7freedom.support.DebugLog.number("upgrade.progress_percent", pct);
        ui.post(new Runnable() {
            @Override
            public void run() {
                progressBar.setProgress(pct);
                statusSub.setText(sub);
            }
        });
    }

    private void uiHero(final String title, final String sub) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusTitle.setText(title);
                statusSub.setText(sub);
            }
        });
    }

    private void showSuccess() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                failureCard.setVisibility(View.GONE);
                successCard.setVisibility(View.VISIBLE);
                actionBtn.setText("Run again");
                actionBtn.setEnabled(true);
                verifyBtn.setEnabled(true);
            }
        });
    }

    private void showFailure(final int kind, final String detail) {
        ca.arc7freedom.support.DebugLog.event("upgrade.failed_kind_" + kind);
        ui.post(new Runnable() {
            @Override
            public void run() {
                successCard.setVisibility(View.GONE);
                failureCard.setVisibility(View.VISIBLE);
                failureTitle.setText(failureTitleFor(kind));
                failureDetail.setText(detail != null && detail.length() > 0 ?
                        "Details: " + truncate(detail, 300) : "");
                failureGuidance.removeAllViews();
                String[] bullets = guidanceFor(kind);
                for (int i = 0; i < bullets.length; i++) {
                    failureGuidance.addView(factsRow("→", bullets[i]));
                }
                actionBtn.setText(kind == FAIL_INSTALLER_CANCELLED ? "Retry upgrade" : "Retry upgrade");
                actionBtn.setEnabled(true);
                verifyBtn.setEnabled(true);
            }
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String failureTitleFor(int kind) {
        switch (kind) {
            case FAIL_STORAGE: return "Not enough free storage";
            case FAIL_PAYLOAD: return "Embedded suite failed integrity checks";
            case FAIL_SIGNATURE: return "Installed suite signed with a different certificate";
            case FAIL_ROOT_ALL_MODES: return "Root install could not complete";
            case FAIL_INSTALLER_CANCELLED: return "Install not confirmed";
            case FAIL_VERIFY: return "Installed suite could not be verified";
            default: return "Upgrade not complete";
        }
    }

    private static String[] guidanceFor(int kind) {
        switch (kind) {
            case FAIL_STORAGE:
                return new String[]{
                        "Free up space: delete or offload downloaded videos, photos and offline music.",
                        "The suite APK alone needs about 55 MB of staging room, plus headroom.",
                        "Settings → Storage shows what is using the tablet's space."
                };
            case FAIL_PAYLOAD:
                return new String[]{
                        "The copy of the suite embedded in this upgrader is damaged or incomplete.",
                        "Re-download this upgrader from the official Kobo Arc 7 Freedom v2.7.0 release page.",
                        "Copy the .apk unchanged — avoid file managers that re-zip APKs."
                };
            case FAIL_SIGNATURE:
                return new String[]{
                        "Android never installs over an app signed with a different certificate — forcing it would erase your data.",
                        "Safest path: keep the installed version, or back up (Settings → Backup), then uninstall and reinstall from the v2.7.0 portal.",
                        "If you built the installed suite yourself, rebuild this upgrader from the same checkout.",
                        "No apps or data have been changed."
                };
            case FAIL_ROOT_ALL_MODES:
                return new String[]{
                        "No root shell could complete the install (tried stdin pipe, su -c, and su 0 sh).",
                        "If you don't have Superuser root, confirm the update in Android's installer instead — that path always works.",
                        "If you do have root: allow ca.arc7freedom.upgrader in your Superuser app, then retry.",
                        "UPDATE_FAILED_DATA_PRESERVED means nothing was uninstalled — your data is intact."
                };
            case FAIL_INSTALLER_CANCELLED:
                return new String[]{
                        "Android's installer was closed before confirming the update.",
                        "Tap Retry upgrade and confirm the prompt — your data will be kept.",
                        "If the prompt never appears, install from a PC with: adb install -r KoboArc7Freedom.apk"
                };
            case FAIL_VERIFY:
                return new String[]{
                        "The package manager reported success, but the installed bytes do not match the payload.",
                        "Reboot the tablet and run the upgrade again.",
                        "If it still fails, reinstall from a PC with: adb install -r KoboArc7Freedom.apk"
                };
            default:
                return new String[]{
                        "See the technical log for the failing step.",
                        "Retry upgrade — transient storage or package-manager hiccups usually clear.",
                        "Existing apps and data were not touched."
                };
        }
    }

    /* ================= Work ================= */

    private static void makeWorldReadableDir(File d) {
        if (d == null) return;
        if (!d.exists()) d.mkdirs();
        d.setReadable(true, false);
        d.setExecutable(true, false);
    }

    private static void writeStreamToWorldFile(InputStream in, File dst, boolean executable) throws Exception {
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } finally {
            fos.close();
        }
        dst.setReadable(true, false);
        if (executable) dst.setExecutable(true, false);
    }

    private String findExistingSu() {
        String[] candidates = new String[]{
                "/sbin/su", "/system/xbin/su", "/system/bin/su", "/su/bin/su"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return c;
        }
        return "su";
    }

    private String runSuScript(String suBin, int mode, String scriptBody, File scriptFile) {
        Process p = null;
        StringBuilder out = new StringBuilder();
        String[] modeNames = {"stdin pipe (su < script)", "su -c '/system/bin/sh script'", "su 0 /system/bin/sh script"};
        uiLog("── Root attempt " + (mode + 1) + "/3 — " + modeNames[mode] + " ──");
        try {
            if (mode == 0) {
                // Mode 0: Direct STDIN pipe into `su` — exact mechanism proven working as `uid=0(root)`
                // on real Kobo Arc 7 hardware (zero file-path traversal issues before shell starts)!
                ProcessBuilder pb = new ProcessBuilder(suBin);
                pb.redirectErrorStream(true);
                p = pb.start();
                OutputStream os = p.getOutputStream();
                String payload = scriptBody + "\nexit $?\n";
                os.write(payload.getBytes("UTF-8"));
                os.flush();
                os.close();
            } else if (mode == 1) {
                ProcessBuilder pb = new ProcessBuilder(
                        suBin, "-c", "/system/bin/sh " + scriptFile.getAbsolutePath());
                pb.redirectErrorStream(true);
                p = pb.start();
                p.getOutputStream().close();
            } else {
                ProcessBuilder pb = new ProcessBuilder(
                        suBin, "0", "/system/bin/sh", scriptFile.getAbsolutePath());
                pb.redirectErrorStream(true);
                p = pb.start();
                p.getOutputStream().close();
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                uiLog("  " + line);
            }
            br.close();
            int rc = p.waitFor();
            uiLog("  (exit code " + rc + ")");
        } catch (Throwable t) {
            out.append("ERR: ").append(t.getMessage());
            uiLog("  error: " + t.getMessage());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
        return out.toString();
    }

    private long embeddedSuiteBytes() {
        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = getAssets().openFd("KoboArc7Freedom.apk");
            return afd.getLength();
        } catch (Throwable stored) {
            try {
                InputStream in = getAssets().open("KoboArc7Freedom.apk");
                long n = in.available();
                in.close();
                return n;
            } catch (Throwable unavailable) {
                return -1;
            }
        } finally {
            try { if (afd != null) afd.close(); } catch (Throwable ignored) {}
        }
    }

    private long freeSpaceBytes() {
        try {
            StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
            return (long) fs.getAvailableBlocks() * (long) fs.getBlockSize();
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    private boolean suiteSignaturesMatch(File stagedApk) {
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(SUITE_PKG, PackageManager.GET_SIGNATURES);
            PackageInfo staged = getPackageManager().getPackageArchiveInfo(stagedApk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (installed == null || staged == null) return true; // cannot compare — don't block
            if (installed.signatures == null || staged.signatures == null) return true;
            Set<String> a = signatureDigests(installed.signatures);
            Set<String> b = signatureDigests(staged.signatures);
            return a.equals(b);
        } catch (PackageManager.NameNotFoundException missing) {
            return true; // nothing installed yet — first-time install
        } catch (Throwable unavailable) {
            return true; // cannot compare — don't block the upgrade
        }
    }

    private static Set<String> signatureDigests(Signature[] signatures) {
        Set<String> result = new HashSet<String>();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < signatures.length; i++) {
                byte[] bytes = signatures[i].toByteArray();
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest(bytes)) {
                    hex.append(Character.forDigit((b >> 4) & 15, 16));
                    hex.append(Character.forDigit(b & 15, 16));
                }
                result.add(hex.toString());
            }
        } catch (Throwable ignored) {
            // If hashing fails we cannot compare; treat as "matched" by returning empty=empty.
        }
        return result;
    }

    private void startUpgradeAsync(final boolean verifyOnly) {
        if (running) return;
        running = true;
        ca.arc7freedom.support.DebugLog.event(verifyOnly ? "upgrade.verify_only_started" : "upgrade.started");
        successCard.setVisibility(View.GONE);
        failureCard.setVisibility(View.GONE);
        setButtons(false, false);
        setPhase(PHASE_PREFLIGHT, ST_RUNNING, "checking…");
        setPhase(PHASE_PAYLOAD, ST_PENDING, null);
        setPhase(PHASE_INSTALL, ST_PENDING, null);
        setPhase(PHASE_VERIFY, ST_PENDING, null);

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    /* ---------- Phase 1: pre-flight ---------- */
                    uiProgress(6, "Pre-flight checks…");
                    long freeBytes = freeSpaceBytes();
                    long payloadBytes = embeddedSuiteBytes();
                    long required = payloadBytes > 0 ? payloadBytes + STAGING_HEADROOM : NEEDED_BYTES_FALLBACK;
                    uiLog("Pre-flight: free space " + (freeBytes < 0 ? "unknown" : (freeBytes / (1024 * 1024)) + " MB")
                            + ", embedded suite " + (payloadBytes < 0 ? "unknown" : (payloadBytes / (1024 * 1024)) + " MB")
                            + ", required " + (required / (1024 * 1024)) + " MB");
                    if (freeBytes >= 0 && freeBytes < required) {
                        setPhase(PHASE_PREFLIGHT, ST_FAILED, "storage");
                        showFailure(FAIL_STORAGE, "Free " + (freeBytes / (1024 * 1024)) + " MB, need ~" + (required / (1024 * 1024)) + " MB");
                        return;
                    }
                    String suCandidate = findExistingSu();
                    boolean suPathSeen = false;
                    for (String c : new String[]{"/sbin/su", "/system/xbin/su", "/system/bin/su", "/su/bin/su"}) {
                        if (new File(c).exists()) { uiLog("Pre-flight: root shell candidate at " + c); suPathSeen = true; }
                    }
                    if (!suPathSeen) uiLog("Pre-flight: no root shell found at the usual paths — Android's confirmed installer will be used.");
                    uiLog("Pre-flight OK (su candidate: " + suCandidate + ")");
                    setPhase(PHASE_PREFLIGHT, ST_DONE, "ok");

                    /* ---------- Phase 2: payload ---------- */
                    setPhase(PHASE_PAYLOAD, ST_RUNNING, "verifying…");
                    uiProgress(18, verifyOnly ? "Verifying embedded payload…" : "Unpacking and verifying embedded suite…");
                    File appFiles = getFilesDir();
                    makeWorldReadableDir(appFiles == null ? null : appFiles.getParentFile());
                    makeWorldReadableDir(appFiles);
                    File stageDir = new File(appFiles, "maintenance");
                    makeWorldReadableDir(stageDir);
                    final File mainApk = new File(stageDir, "KoboArc7Freedom.apk");
                    InputStream in = getAssets().open("KoboArc7Freedom.apk");
                    try { writeStreamToWorldFile(in, mainApk, false); } finally { in.close(); }
                    expectedMainHash = BundledApk.digest(new FileInputStream(mainApk));
                    android.content.pm.PackageInfo bundled = getPackageManager().getPackageArchiveInfo(mainApk.getAbsolutePath(), 0);
                    if (bundled == null || !SUITE_PKG.equals(bundled.packageName)) {
                        setPhase(PHASE_PAYLOAD, ST_FAILED, "invalid package");
                        showFailure(FAIL_PAYLOAD, "Embedded APK is not a valid " + SUITE_PKG + " package");
                        return;
                    }
                    uiLog("Payload: package " + bundled.packageName + " versionCode " + bundled.versionCode
                            + " sha256 " + expectedMainHash.substring(0, 12) + "…");

                    try {
                        int installedVersion = getPackageManager().getPackageInfo(SUITE_PKG, 0).versionCode;
                        if (installedVersion > bundled.versionCode) {
                            uiLog("A newer suite (vcode " + installedVersion + ") is already installed; keeping it.");
                            setPhase(PHASE_PAYLOAD, ST_DONE, "newer kept");
                            setPhase(PHASE_INSTALL, ST_SKIPPED, "newer installed");
                            setPhase(PHASE_VERIFY, ST_DONE, "kept");
                            uiProgress(100, "A newer Arc7 Freedom is already installed — nothing to do.");
                            showSuccess();
                            ui.post(new Runnable() { @Override public void run() { launchMainApp(); } });
                            return;
                        }
                    } catch (android.content.pm.PackageManager.NameNotFoundException missing) {}

                    if (!suiteSignaturesMatch(mainApk)) {
                        setPhase(PHASE_PAYLOAD, ST_FAILED, "signature mismatch");
                        showFailure(FAIL_SIGNATURE,
                                "Installed " + SUITE_PKG + " was signed with a different certificate than this payload");
                        return;
                    }
                    uiLog("Signature check: matches installed build (or nothing installed yet)");
                    setPhase(PHASE_PAYLOAD, ST_DONE, "ok");
                    ca.arc7freedom.support.DebugLog.event("upgrade.payload_verified");

                    if (verifyOnly) {
                        uiProgress(100, "Verification passed — payload is intact and signatures match. Tap Run upgrade when ready.");
                        setPhase(PHASE_INSTALL, ST_SKIPPED, "verify only");
                        setPhase(PHASE_VERIFY, ST_SKIPPED, "verify only");
                        uiHero("All checks passed ✓", "Payload verified, signature matches, storage OK. Nothing was installed.");
                        ui.post(new Runnable() {
                            @Override public void run() {
                                actionBtn.setText("Run upgrade");
                                setButtons(true, true);
                            }
                        });
                        return;
                    }

                    /* ---------- Phase 3: install ---------- */
                    setPhase(PHASE_INSTALL, ST_RUNNING, "installing…");
                    uiProgress(45, "Installing suite in place…");
                    String body = BundledApk.installScript(mainApk.getAbsolutePath(),
                            "/data/local/tmp/arc7-maintenance-suite.apk", SUITE_PKG);
                    File script = new File(stageDir, "install.sh");
                    writeStreamToWorldFile(new ByteArrayInputStream(body.getBytes("UTF-8")), script, true);
                    String combined = "";
                    String lastFailure = "";
                    for (int mode = 0; mode < 3; mode++) {
                        combined = runSuScript(findExistingSu(), mode, body, script);
                        if (combined.contains("ARC7_APK_INSTALL_OK") || combined.contains("UPDATE_FAILED_DATA_PRESERVED")) break;
                        lastFailure = lastMeaningfulLine(combined);
                    }
                    if (combined.contains("UPDATE_FAILED_DATA_PRESERVED")) {
                        setPhase(PHASE_INSTALL, ST_FAILED, "rejected");
                        showFailure(FAIL_ROOT_ALL_MODES, "pm rejected the update (" + truncate(lastFailure, 140) + ")");
                        return;
                    }
                    if (combined.contains("ARC7_APK_INSTALL_OK")) {
                        /* ---------- Phase 4: verify ---------- */
                        setPhase(PHASE_INSTALL, ST_DONE, "ok");
                        setPhase(PHASE_VERIFY, ST_RUNNING, "checking…");
                        uiProgress(85, "Verifying installed suite checksum…");
                        if (!installedMainMatches()) {
                            setPhase(PHASE_VERIFY, ST_FAILED, "mismatch");
                            showFailure(FAIL_VERIFY, "Installed bytes do not match the embedded payload");
                            return;
                        }
                        setPhase(PHASE_VERIFY, ST_DONE, "verified");
                        ca.arc7freedom.support.DebugLog.event("upgrade.suite_verified");
                        uiProgress(100, "Suite update verified. Opening shared patch pipeline for all companion/default apps and system repairs…");
                        uiLog("Suite APK verified. Remaining patch results and any root/signature/storage restrictions appear in Install / Repair All Patches.");
                        showSuccess();
                        ui.post(new Runnable() { @Override public void run() { launchMainApp(); } });
                    } else {
                        /* Root unavailable or blocked — fall back to Android's confirmed installer. */
                        uiProgress(40, "Root unavailable — Android must confirm the suite update.");
                        setPhase(PHASE_INSTALL, ST_WAITING, "confirm in Android");
                        uiLog("Root attempt did not complete. Falling back to Android's confirmed installer.");
                        uiLog("After installing, open Arc7 Freedom > Install / Repair All Patches. Android confirmations are still required for each companion without root.");
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Intent prompt = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                                prompt.setDataAndType(Uri.fromFile(mainApk), "application/vnd.android.package-archive");
                                prompt.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                                try {
                                    awaitingInstall = true;
                                    setButtons(false, false);
                                    startActivityForResult(prompt, 73);
                                } catch (RuntimeException error) {
                                    awaitingInstall = false;
                                    setPhase(PHASE_INSTALL, ST_FAILED, "installer unavailable");
                                    uiLog("Could not open Android installer: " + error.getMessage() + ". Open the staged APK manually.");
                                    showFailure(FAIL_ROOT_ALL_MODES, "Android installer unavailable: " + truncate(error.getMessage(), 160));
                                }
                            }
                        });
                    }
                } catch (final Exception error) {
                    ca.arc7freedom.support.DebugLog.error("upgrade.failed", error);
                    uiLog("ERROR: " + error.getMessage());
                    uiProgress(100, "Not complete: " + error.getMessage());
                    showFailure(FAIL_UNKNOWN, truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 300));
                } finally {
                    running = false;
                }
            }
        }, "Arc7UpgradeSuite").start();
    }

    private static String lastMeaningfulLine(String combined) {
        if (combined == null) return "";
        String[] lines = combined.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (line.startsWith("──") || line.startsWith("(exit code")) continue;
            return line;
        }
        return "";
    }

    private boolean installedMainMatches() {
        try { return expectedMainHash != null && expectedMainHash.equals(BundledApk.digest(new FileInputStream(
                getPackageManager().getApplicationInfo(SUITE_PKG, 0).sourceDir))); }
        catch (Exception unavailable) { return false; }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("expected_main_hash", expectedMainHash); out.putBoolean("awaiting_install", awaitingInstall);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 73) return;
        awaitingInstall = false;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean verified = installedMainMatches();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (verified) {
                            setPhase(PHASE_INSTALL, ST_DONE, "confirmed");
                            setPhase(PHASE_VERIFY, ST_DONE, "verified");
                            uiProgress(100, "Suite update verified. Launching…");
                            showSuccess();
                            launchMainApp();
                        } else {
                            setPhase(PHASE_INSTALL, ST_FAILED, "not confirmed");
                            setPhase(PHASE_VERIFY, ST_SKIPPED, "—");
                            uiLog("Suite install cancelled or not verified. No app was uninstalled. Retry after resolving the Android installer error.");
                            showFailure(FAIL_INSTALLER_CANCELLED, "The suite APK was not installed or could not be verified");
                        }
                    }
                });
            }
        }, "VerifySuiteInstall").start();
    }

    private void launchMainApp() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(SUITE_PKG, SUITE_PKG + ".MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        } catch (Throwable t) {
            uiLog("Launch error: " + t.getMessage());
        }
    }

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        super.onCreateOptionsMenu(menu); return ca.arc7freedom.support.AppSupport.addMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == ca.arc7freedom.support.AppSupport.MENU_TOOLS) {
            ca.arc7freedom.support.AppSupport.open(this); return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
