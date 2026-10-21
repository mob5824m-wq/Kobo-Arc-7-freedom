package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Main UI controller for Kobo Arc 7 Freedom.
 * Built natively against Android 4.2.2 (API 17) with a clean high-contrast slate & Holo-cyan interface
 * designed specifically for the Kobo Arc 7 (1024x600) and Kobo Arc 7HD (1920x1200) screens.
 */
public class MainActivity extends Activity {

    private static final int BG_PAGE       = 0xFF0E1319;
    private static final int BG_SURFACE    = 0xFF161E28;
    private static final int BG_ELEVATED   = 0xFF1F2A38;
    private static final int BORDER_SUBTLE = 0xFF2B394B;
    private static final int ACCENT_CYAN   = 0xFF33B5E5;
    private static final int ACCENT_DARK   = 0xFF0284C7;
    private static final int STATUS_OK     = 0xFF10B981;
    private static final int STATUS_WARN   = 0xFFF59E0B;
    private static final int STATUS_ERR    = 0xFFEF4444;
    private static final int TEXT_PRIMARY  = 0xFFF1F5F9;
    private static final int TEXT_MUTED    = 0xFF94A3B8;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private RootShell rootShell;
    private BackupManager backupManager;
    private Android4Installer android4Installer;
    private DeviceInspector.Snapshot snapshot;
    private int selectedVolumeIndex = 0;
    private File selectedRomZip = null;
    private File selectedRecoveryImg = null;
    private volatile boolean taskRunning = false;

    // Header & Navigation
    private TextView headerDeviceSubtitle;
    private TextView rootStatusPill;
    private TextView[] navTabs = new TextView[4];
    private LinearLayout[] tabPages = new LinearLayout[4];

    // Progress & Console Drawer
    private LinearLayout progressBanner;
    private TextView progressStatusLabel;
    private ProgressBar progressBar;
    private ScrollView consoleScroll;
    private TextView consoleText;
    private boolean consoleExpanded = false;

    // Dynamic section containers refreshed after state changes
    private LinearLayout rootInfoContainer;
    private LinearLayout backupVolumeContainer;
    private LinearLayout savedBackupsContainer;
    private LinearLayout android4SafetyContainer;
    private TextView pickedRomLabel;
    private TextView pickedRecoveryLabel;

    // Checkboxes
    private CheckBox cbBootRec;
    private CheckBox cbNvramScatter;
    private CheckBox cbSystemImg;
    private CheckBox cbUserApps;

    private CheckBox cbKeepKoboReader;
    private CheckBox cbEnableUnknownSources;
    private CheckBox cbWipeCache;
    private CheckBox cbWipeData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootShell = new RootShell(this);
        backupManager = new BackupManager(this, rootShell);
        android4Installer = new Android4Installer(this, rootShell, backupManager);

        buildRootLayout();
        appendLog("Kobo Arc 7 Freedom v1.0.0 ready (Android API " + android.os.Build.VERSION.SDK_INT + ").");
        refreshDeviceSnapshotAsync();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable box(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) gd.setStroke(dp(strokeDp), stroke);
        return gd;
    }

    @SuppressWarnings("deprecation")
    private void setBg(View v, GradientDrawable d) {
        v.setBackgroundDrawable(d);
    }

    private void buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_PAGE);

        // 1. Top App Header Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(BG_SURFACE);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Kobo Arc 7 Freedom");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        titleCol.addView(title);

        headerDeviceSubtitle = new TextView(this);
        headerDeviceSubtitle.setText("Inspecting hardware, partitions & root state...");
        headerDeviceSubtitle.setTextColor(TEXT_MUTED);
        headerDeviceSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(headerDeviceSubtitle);

        topBar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rootStatusPill = new TextView(this);
        rootStatusPill.setText("CHECKING...");
        rootStatusPill.setTextColor(TEXT_PRIMARY);
        rootStatusPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        rootStatusPill.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        rootStatusPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        setBg(rootStatusPill, box(BG_ELEVATED, BORDER_SUBTLE, 1, 12));
        topBar.addView(rootStatusPill);

        Button refreshBtn = makeSecondaryButton("Refresh");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        rlp.leftMargin = dp(8);
        refreshBtn.setPadding(dp(12), 0, dp(12), 0);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDeviceSnapshotAsync();
            }
        });
        topBar.addView(refreshBtn, rlp);

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Step Tab Strip
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(BG_SURFACE);
        String[] tabTitles = new String[]{
                "1. ROOT",
                "2. BACKUP OS",
                "3. ANDROID 4",
                "4. TOOLS"
        };
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(tabTitles[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            tv.setPadding(dp(6), dp(12), dp(6), dp(12));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            navTabs[i] = tv;
            tabBar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(BORDER_SUBTLE);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 3. Active Operation Progress Banner
        progressBanner = new LinearLayout(this);
        progressBanner.setOrientation(LinearLayout.VERTICAL);
        progressBanner.setBackgroundColor(0xFF132232);
        progressBanner.setPadding(dp(16), dp(10), dp(16), dp(10));
        progressBanner.setVisibility(View.GONE);

        progressStatusLabel = new TextView(this);
        progressStatusLabel.setTextColor(ACCENT_CYAN);
        progressStatusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        progressStatusLabel.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        progressBanner.addView(progressStatusLabel);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pblp.topMargin = dp(6);
        progressBanner.addView(progressBar, pblp);

        root.addView(progressBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 4. Scrollable Content Area
        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        LinearLayout pagesHolder = new LinearLayout(this);
        pagesHolder.setOrientation(LinearLayout.VERTICAL);
        pagesHolder.setPadding(dp(14), dp(14), dp(14), dp(18));

        tabPages[0] = buildTab1Root();
        tabPages[1] = buildTab2Backup();
        tabPages[2] = buildTab3Android4();
        tabPages[3] = buildTab4Tools();
        for (LinearLayout page : tabPages) {
            pagesHolder.addView(page, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        mainScroll.addView(pagesHolder);
        root.addView(mainScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 5. Collapsible Live Kernel / Shell Console Footer
        View cDiv = new View(this);
        cDiv.setBackgroundColor(BORDER_SUBTLE);
        root.addView(cDiv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout consoleHeader = new LinearLayout(this);
        consoleHeader.setOrientation(LinearLayout.HORIZONTAL);
        consoleHeader.setGravity(Gravity.CENTER_VERTICAL);
        consoleHeader.setBackgroundColor(0xFF0A0E13);
        consoleHeader.setPadding(dp(14), dp(6), dp(14), dp(6));

        TextView cTitle = new TextView(this);
        cTitle.setText(">_ LIVE OUTPUT CONSOLE");
        cTitle.setTextColor(ACCENT_CYAN);
        cTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        cTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        consoleHeader.addView(cTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView toggleSizeBtn = new TextView(this);
        toggleSizeBtn.setText("Expand");
        toggleSizeBtn.setTextColor(TEXT_MUTED);
        toggleSizeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        toggleSizeBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        toggleSizeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                consoleExpanded = !consoleExpanded;
                toggleSizeBtn.setText(consoleExpanded ? "Compact" : "Expand");
                ViewGroup.LayoutParams lp = consoleScroll.getLayoutParams();
                lp.height = dp(consoleExpanded ? 210 : 84);
                consoleScroll.setLayoutParams(lp);
            }
        });
        consoleHeader.addView(toggleSizeBtn);

        TextView saveLogBtn = new TextView(this);
        saveLogBtn.setText("Save Log");
        saveLogBtn.setTextColor(TEXT_MUTED);
        saveLogBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        saveLogBtn.setPadding(dp(10), dp(4), dp(4), dp(4));
        saveLogBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportDiagnosticLog();
            }
        });
        consoleHeader.addView(saveLogBtn);

        root.addView(consoleHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        consoleScroll = new ScrollView(this);
        consoleScroll.setBackgroundColor(0xFF070A0E);
        consoleScroll.setPadding(dp(14), dp(6), dp(14), dp(8));

        consoleText = new TextView(this);
        consoleText.setTextColor(0xFFCBD5E1);
        consoleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        consoleText.setTypeface(Typeface.MONOSPACE);
        consoleScroll.addView(consoleText);

        root.addView(consoleScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));

        setContentView(root);
        selectTab(0);
    }

    /* ========================== TAB 1: ROOT ========================== */
    private LinearLayout buildTab1Root() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout heroCard = makeCard();
        addCardHeader(heroCard, "Step 1 — Root Your Kobo Arc 7",
                "Obtains kernel uid=0 privilege on Kobo Arc 7 (MT8125 Linux 3.4) & Arc 7HD (Tegra 3), "
                + "remounts /system, installs a clean ARMv7 su binary (/system/xbin/su + root socket daemon), "
                + "and prevents Kobo stock ROM from overwriting recovery.");

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        Button rootBtn = makePrimaryButton("Root Kobo Arc 7 Now");
        rootBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRootOperation();
            }
        });
        btnRow.addView(rootBtn, new LinearLayout.LayoutParams(0, dp(48), 1.4f));

        View sp = new View(this);
        btnRow.addView(sp, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button verifyBtn = makeSecondaryButton("Test Root Shell");
        verifyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testRootShellInteractive();
            }
        });
        btnRow.addView(verifyBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        heroCard.addView(btnRow);
        page.addView(heroCard, cardMargin());

        LinearLayout hwCard = makeCard();
        addCardHeader(hwCard, "Detected Tablet Hardware, Kernel & eMMC Partition Map",
                "Live readings from /proc/dumchar_info, /proc/emmc, /proc/mounts, and kernel exploit probes:");
        rootInfoContainer = new LinearLayout(this);
        rootInfoContainer.setOrientation(LinearLayout.VERTICAL);
        rootInfoContainer.setPadding(0, dp(8), 0, 0);
        hwCard.addView(rootInfoContainer);
        page.addView(hwCard, cardMargin());

        return page;
    }

    /* ========================== TAB 2: BACKUP OS ========================== */
    private LinearLayout buildTab2Backup() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout cfgCard = makeCard();
        addCardHeader(cfgCard, "Step 2 — Backup Complete Kobo Stock OS",
                "Creates compressed, SHA-256 verified partition images so you can restore your exact factory "
                + "Kobo OS (or unbrick with MediaTek SP Flash Tool via the generated scatter file) at any time.");

        TextView volLabel = makeSectionLabel("Backup Storage Destination (microSD recommended on 8 GB Arc 7):");
        cfgCard.addView(volLabel);

        backupVolumeContainer = new LinearLayout(this);
        backupVolumeContainer.setOrientation(LinearLayout.VERTICAL);
        cfgCard.addView(backupVolumeContainer);

        TextView partLabel = makeSectionLabel("Partitions & Data to Include:");
        cfgCard.addView(partLabel);

        cbBootRec = makeCheckBox("Boot Kernel & Recovery Partition Images (boot.img.gz, recovery.img.gz, lk/logo)  [~20 MB]", true);
        cbNvramScatter = makeCheckBox("MediaTek NVRAM Wi-Fi Calibration + SP Flash Tool Scatter File  [~28 MB]", true);
        cbSystemImg = makeCheckBox("Full Stock System OS Partition (system.ext4.img.gz — bit-exact OS backup)  [~320-420 MB]", true);
        cbUserApps = makeCheckBox("Installed User Apps & Settings (userdata_apps.tar.gz, excludes /sdcard media)", false);

        cfgCard.addView(cbBootRec);
        cfgCard.addView(cbNvramScatter);
        cfgCard.addView(cbSystemImg);
        cfgCard.addView(cbUserApps);

        Button startBackupBtn = makePrimaryButton("Create Verified OS Backup Now");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        blp.topMargin = dp(12);
        startBackupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCreateBackupOperation();
            }
        });
        cfgCard.addView(startBackupBtn, blp);
        page.addView(cfgCard, cardMargin());

        LinearLayout listCard = makeCard();
        addCardHeader(listCard, "Saved OS Backups (Verify & Restore)",
                "Every backup is verified against SHA256SUMS before a single byte is written back to eMMC flash.");
        savedBackupsContainer = new LinearLayout(this);
        savedBackupsContainer.setOrientation(LinearLayout.VERTICAL);
        savedBackupsContainer.setPadding(0, dp(8), 0, 0);
        listCard.addView(savedBackupsContainer);
        page.addView(listCard, cardMargin());

        return page;
    }

    /* ========================== TAB 3: INSTALL NORMAL ANDROID 4 ========================== */
    private LinearLayout buildTab3Android4() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        android4SafetyContainer = new LinearLayout(this);
        android4SafetyContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(android4SafetyContainer, cardMargin());

        // OPTION A: One-Tap Stock Android 4.2 Jelly Bean Conversion
        LinearLayout optACard = makeCard();
        addCardHeader(optACard,
                "Option A — One-Tap Stock Android 4.2 Jelly Bean Conversion (Recommended)",
                "Transforms your Kobo Arc 7 into clean, pure Android 4.2.2 Jelly Bean right away while keeping "
                + "100% of your working MediaTek MT8125 / Tegra hardware drivers (Wi-Fi, Bluetooth, GPU, touch, audio, camera). "
                + "Installs the pure Android 4 Home Launcher in /system/app, removes Kobo's bricked Setup Wizard & Tapestry launcher, "
                + "and permanently fixes the Android Home & Recents buttons — even across future Factory Resets!");

        cbKeepKoboReader = makeCheckBox("Keep optional Kobo eReader book app (unchecked = pure Android 4 with zero Kobo apps)", false);
        cbEnableUnknownSources = makeCheckBox("Enable side-loading apps from Unknown Sources by default", true);
        optACard.addView(cbKeepKoboReader);
        optACard.addView(cbEnableUnknownSources);

        LinearLayout optABtns = new LinearLayout(this);
        optABtns.setOrientation(LinearLayout.HORIZONTAL);
        optABtns.setPadding(0, dp(12), 0, 0);

        Button convertNowBtn = makePrimaryButton("Install Normal Android 4.2 Now");
        convertNowBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndConvertStockAndroid4();
            }
        });
        optABtns.addView(convertNowBtn, new LinearLayout.LayoutParams(0, dp(48), 1.5f));

        View spA = new View(this);
        optABtns.addView(spA, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button rollbackKoboBtn = makeSecondaryButton("Restore Kobo UI");
        rollbackKoboBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndRestoreKoboUi();
            }
        });
        optABtns.addView(rollbackKoboBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        optACard.addView(optABtns);
        page.addView(optACard, cardMargin());

        // OPTION B: Custom Recovery + Flashable Android 4 ROM .zip Installer & Builder
        LinearLayout optBCard = makeCard();
        addCardHeader(optBCard,
                "Option B — Custom Recovery (TWRP / CTR / CWM) & ROM .zip Flasher",
                "Prefer flashing a standalone custom Android 4 ROM .zip via custom recovery? "
                + "Flash a custom recovery.img (with ANDROID! header check & hardware SHA-256 read-back verification), "
                + "build a standalone Vanilla-Android-4.2.2-KoboArc7.zip ROM right onto your SD card, or flash any compatible Android 4 .zip!");

        // Recovery Image sub-box
        pickedRecoveryLabel = makeBodyText("Selected recovery image: (none selected)");
        pickedRecoveryLabel.setPadding(0, dp(8), 0, dp(6));
        optBCard.addView(pickedRecoveryLabel);

        LinearLayout recRow = new LinearLayout(this);
        recRow.setOrientation(LinearLayout.HORIZONTAL);
        Button browseRecBtn = makeSecondaryButton("Browse recovery.img");
        browseRecBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File start = getSelectedVolumeRoot();
                FilePickerDialog.show(MainActivity.this, start, new String[]{".img"},
                        "Select Custom Recovery Image", new FilePickerDialog.OnFilePicked() {
                            @Override
                            public void onPicked(File file) {
                                selectedRecoveryImg = file;
                                pickedRecoveryLabel.setText("Selected recovery image: " + file.getAbsolutePath());
                            }
                        });
            }
        });
        recRow.addView(browseRecBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View spR = new View(this);
        recRow.addView(spR, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button flashRecBtn = makePrimaryButton("Flash Custom Recovery");
        flashRecBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFlashRecoveryOperation();
            }
        });
        recRow.addView(flashRecBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        optBCard.addView(recRow);

        // ROM .zip sub-box
        pickedRomLabel = makeBodyText("Selected Android 4 ROM .zip: (none selected)");
        pickedRomLabel.setPadding(0, dp(14), 0, dp(6));
        optBCard.addView(pickedRomLabel);

        cbWipeCache = makeCheckBox("Wipe Cache & Dalvik Cache during recovery install (Recommended)", true);
        cbWipeData = makeCheckBox("Factory Wipe /data before installing ROM (erases user apps)", false);
        optBCard.addView(cbWipeCache);
        optBCard.addView(cbWipeData);

        LinearLayout romRow = new LinearLayout(this);
        romRow.setOrientation(LinearLayout.HORIZONTAL);
        romRow.setPadding(0, dp(8), 0, 0);

        Button browseZipBtn = makeSecondaryButton("Select ROM .zip");
        browseZipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File start = getSelectedVolumeRoot();
                FilePickerDialog.show(MainActivity.this, start, new String[]{".zip"},
                        "Select Android 4 ROM .zip", new FilePickerDialog.OnFilePicked() {
                            @Override
                            public void onPicked(File file) {
                                onRomZipPicked(file);
                            }
                        });
            }
        });
        romRow.addView(browseZipBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));

        View spZ1 = new View(this);
        romRow.addView(spZ1, new LinearLayout.LayoutParams(dp(8), dp(1)));

        Button buildZipBtn = makeSecondaryButton("Build Clean Android 4 .zip");
        buildZipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBuildVanillaRomZipOperation();
            }
        });
        romRow.addView(buildZipBtn, new LinearLayout.LayoutParams(0, dp(46), 1.15f));

        View spZ2 = new View(this);
        romRow.addView(spZ2, new LinearLayout.LayoutParams(dp(8), dp(1)));

        Button flashZipBtn = makePrimaryButton("Flash .zip in Recovery");
        flashZipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndFlashRomZip();
            }
        });
        romRow.addView(flashZipBtn, new LinearLayout.LayoutParams(0, dp(46), 1.15f));

        optBCard.addView(romRow);
        page.addView(optBCard, cardMargin());

        return page;
    }

    /* ========================== TAB 4: SYSTEM TOOLS ========================== */
    private LinearLayout buildTab4Tools() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout launcherCard = makeCard();
        addCardHeader(launcherCard, "Launcher, Home Button & System Controls",
                "Quick utilities for launching Stock Android 4 Home, fixing Android setup provisioning flags, "
                + "opening stock Android Settings, and rebooting directly into Recovery or Bootloader modes.");

        addToolActionRow(launcherCard, "Launch Stock Android 4 Home Screen Now",
                "Opens VanillaAndroid4Home immediately (installs user copy first if not yet installed).",
                "Open Android 4 Home", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchOrInstallVanillaHome();
                    }
                });

        addToolActionRow(launcherCard, "Fix Unresponsive Home / Recents Buttons",
                "Sets Settings.Global.DEVICE_PROVISIONED=1 & USER_SETUP_COMPLETE=1 after removing KoboSetupWizard.",
                "Fix Home Button", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        runQuickRootCmd("settings put global device_provisioned 1; "
                                + "settings put secure device_provisioned 1; "
                                + "settings put secure user_setup_complete 1",
                                "Provisioning flags updated! Home & Recents buttons enabled.");
                    }
                });

        addToolActionRow(launcherCard, "Open Standard Android System Settings",
                "Opens AOSP Settings (About Tablet, Wi-Fi, Apps, Developer Options, Security).",
                "System Settings", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        } catch (Throwable ignored) {}
                    }
                });

        page.addView(launcherCard, cardMargin());

        LinearLayout powerCard = makeCard();
        addCardHeader(powerCard, "Power & Boot Mode Controls",
                "Kobo Arc 7 & 7HD hardware button combos can be tricky — reboot directly into any mode below:");

        LinearLayout pRow = new LinearLayout(this);
        pRow.setOrientation(LinearLayout.HORIZONTAL);
        pRow.setPadding(0, dp(10), 0, 0);

        Button rbNorm = makeSecondaryButton("Reboot Android");
        rbNorm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReboot("", "Normal Android OS"); }
        });
        pRow.addView(rbNorm, new LinearLayout.LayoutParams(0, dp(46), 1f));

        pRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(1)));

        Button rbRec = makePrimaryButton("Reboot Recovery");
        rbRec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReboot("recovery", "Recovery Mode (TWRP/CTR/Stock)"); }
        });
        pRow.addView(rbRec, new LinearLayout.LayoutParams(0, dp(46), 1f));

        pRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(1)));

        Button rbBl = makeSecondaryButton("Reboot Bootloader");
        rbBl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReboot("bootloader", "Fastboot / Bootloader"); }
        });
        pRow.addView(rbBl, new LinearLayout.LayoutParams(0, dp(46), 1f));

        powerCard.addView(pRow);
        page.addView(powerCard, cardMargin());

        return page;
    }

    /* ========================== ASYNC OPERATIONS ========================== */
    private void refreshDeviceSnapshotAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DeviceInspector.Snapshot snap = DeviceInspector.collect(MainActivity.this, rootShell);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        snapshot = snap;
                        renderSnapshot();
                    }
                });
            }
        }).start();
    }

    private void renderSnapshot() {
        if (snapshot == null) return;
        headerDeviceSubtitle.setText(snapshot.variantTitle + "  •  Android "
                + snapshot.androidRelease + " (API " + snapshot.sdkInt + ")");

        if (snapshot.rooted) {
            rootStatusPill.setText("ROOTED (uid=0)");
            rootStatusPill.setTextColor(0xFF052E16);
            setBg(rootStatusPill, box(STATUS_OK, STATUS_OK, 1, 12));
        } else {
            rootStatusPill.setText("UNROOTED");
            rootStatusPill.setTextColor(0xFF451A03);
            setBg(rootStatusPill, box(STATUS_WARN, STATUS_WARN, 1, 12));
        }

        // Populate Tab 1 info table
        rootInfoContainer.removeAllViews();
        addKvRow(rootInfoContainer, "Detected Tablet Profile", snapshot.variantTitle);
        addKvRow(rootInfoContainer, "Model / Codename / SoC",
                snapshot.model + " / " + snapshot.deviceCodename + " / " + snapshot.hardware + " (" + snapshot.cpuAbi + ")");
        addKvRow(rootInfoContainer, "Firmware Build", snapshot.buildDisplay);
        addKvRow(rootInfoContainer, "Linux Kernel", snapshot.kernelLine);
        addKvRow(rootInfoContainer, "Root Shell Status",
                snapshot.rooted ? ("ACTIVE via " + snapshot.suPath) : "Not yet rooted — tap 'Root Kobo Arc 7 Now' above");
        addKvRow(rootInfoContainer, "Stock Android 4 Launcher",
                snapshot.vanillaHomeInstalled ? "Installed (ca.arc7freedom.launcher ready)" : "Not installed yet (Step 3)");
        addKvRow(rootInfoContainer, "Kobo System APKs Present",
                snapshot.detectedKoboSystemApks.isEmpty()
                        ? "0 (Clean de-Kobo'd /system!)"
                        : snapshot.detectedKoboSystemApks.size() + " found in /system/app");

        StringBuilder pMap = new StringBuilder();
        for (Map.Entry<String, DeviceInspector.PartitionTarget> e : snapshot.partitions.entrySet()) {
            pMap.append(String.format(Locale.US, "%-10s -> %s\n", e.getKey(), e.getValue().describe()));
        }
        addKvRow(rootInfoContainer, "Discovered eMMC Partitions",
                pMap.length() > 0 ? pMap.toString().trim() : "Standard mounts (root device to unlock raw partition nodes)");
        addKvRow(rootInfoContainer, "Kernel Exploit Probe", snapshot.exploitProbeSummary);

        // Populate Tab 2 storage volume selector
        backupVolumeContainer.removeAllViews();
        if (selectedVolumeIndex >= snapshot.volumes.size()) selectedVolumeIndex = 0;
        for (int i = 0; i < snapshot.volumes.size(); i++) {
            final int idx = i;
            DeviceInspector.StorageVolumeInfo v = snapshot.volumes.get(i);
            String prefix = (i == selectedVolumeIndex) ? "● [SELECTED]  " : "○  ";
            String rec = v.removableSd ? "  ★ Recommended" : "";
            TextView row = new TextView(this);
            row.setText(prefix + v.formatSummary() + rec);
            row.setTextColor(i == selectedVolumeIndex ? ACCENT_CYAN : TEXT_PRIMARY);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            setBg(row, box(BG_ELEVATED, i == selectedVolumeIndex ? ACCENT_CYAN : BORDER_SUBTLE, 1, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectedVolumeIndex = idx;
                    renderSnapshot();
                }
            });
            backupVolumeContainer.addView(row, lp);
        }

        // Populate Saved Backups list + Tab 3 safety banner
        List<BackupManager.SavedBackup> backups = backupManager.scanSavedBackups(snapshot.volumes);
        savedBackupsContainer.removeAllViews();
        if (backups.isEmpty()) {
            savedBackupsContainer.addView(makeBodyText(
                    "No saved OS backups found yet on internal storage or microSD card."));
        } else {
            for (final BackupManager.SavedBackup b : backups) {
                LinearLayout bCard = new LinearLayout(this);
                bCard.setOrientation(LinearLayout.VERTICAL);
                bCard.setPadding(dp(12), dp(10), dp(12), dp(10));
                setBg(bCard, box(BG_ELEVATED, BORDER_SUBTLE, 1, 6));

                TextView bt = new TextView(this);
                bt.setText(b.name + "  (" + (b.totalCompressedBytes / 1048576L) + " MB)");
                bt.setTextColor(TEXT_PRIMARY);
                bt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
                bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                bCard.addView(bt);

                TextView bm = makeBodyText("Path: " + b.directory.getAbsolutePath()
                        + "\nCreated: " + b.createdAt + "  •  Images: " + b.imageFiles.size());
                bCard.addView(bm);

                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, dp(8), 0, 0);

                Button verBtn = makeSecondaryButton("Verify SHA-256");
                verBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { verifySavedBackup(b); }
                });
                actions.addView(verBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));

                actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), dp(1)));

                Button restBtn = makePrimaryButton("Restore OS Backup");
                restBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmAndRestoreBackup(b); }
                });
                actions.addView(restBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));

                bCard.addView(actions);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = dp(8);
                savedBackupsContainer.addView(bCard, blp);
            }
        }

        // Tab 3 Backup Safety Banner
        android4SafetyContainer.removeAllViews();
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (backups.isEmpty()) {
            setBg(banner, box(0xFF2B1F0E, STATUS_WARN, 1, 8));
            TextView warnTxt = makeBodyText(
                    "SAFETY REMINDER: No verified OS Backup found yet. Back up your stock Kobo OS in Step 2 before modifying /system!");
            warnTxt.setTextColor(0xFFFDE68A);
            banner.addView(warnTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button goBackup = makeSecondaryButton("Go to Step 2");
            goBackup.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(1); }
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            glp.leftMargin = dp(10);
            banner.addView(goBackup, glp);
        } else {
            setBg(banner, box(0xFF0A291E, STATUS_OK, 1, 8));
            TextView okTxt = makeBodyText("OS BACKUP VERIFIED PRESENT (" + backups.get(0).name
                    + "). Safe to install Normal Android 4.2!");
            okTxt.setTextColor(0xFFA7F3D0);
            banner.addView(okTxt);
        }
        android4SafetyContainer.addView(banner);
    }

    private void startRootOperation() {
        if (taskRunning) {
            Toast.makeText(this, "An operation is already running.", Toast.LENGTH_SHORT).show();
            return;
        }
        taskRunning = true;
        setProgressUi(true, 10, "Running multi-stage Kobo Arc 7 kernel root engine...");
        appendLog("=== Starting Kobo Arc 7 Root Workflow ===");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.ExecResult res = rootShell.runRootExploit(new RootShell.LineListener() {
                    @Override
                    public void onLine(String line) {
                        appendLog(line);
                        if (line.contains("[Stage 1]")) setProgressUi(true, 30, "Testing CVE-2013-6282 kernel get_user/put_user...");
                        else if (line.contains("[Stage 2]")) setProgressUi(true, 55, "Testing MediaTek MT8125 driver cred elevation...");
                        else if (line.contains("[Stage 3]")) setProgressUi(true, 75, "Running CVE-2014-3153 futex_requeue exploit...");
                        else if (line.contains("STAGE:")) setProgressUi(true, 90, line);
                    }
                });
                final DeviceInspector.Snapshot updated = DeviceInspector.collect(MainActivity.this, rootShell);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        taskRunning = false;
                        snapshot = updated;
                        renderSnapshot();
                        if (res.exitCode == 0 && updated.rooted) {
                            setProgressUi(false, 100, "");
                            showInfoDialog("Root Complete (uid=0 Verified)!",
                                    "Your tablet now has persistent root access installed at /system/xbin/su.\n\n"
                                    + "Next recommended step: tap '2. BACKUP OS' to make a complete verified backup of your stock Kobo OS!");
                        } else {
                            setProgressUi(false, 0, "");
                            showInfoDialog("On-Device Root Finished Without uid=0",
                                    "All safe on-device exploit stages finished without elevating this kernel build.\n\n"
                                    + "Check the Live Console below for details, or connect over USB and run the companion "
                                    + "script included in this repo: tools/usb-sideload-and-root.sh");
                        }
                    }
                });
            }
        }).start();
    }

    private void testRootShellInteractive() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.ExecResult r = rootShell.execRoot("id && uname -a && df /system /data",
                        new RootShell.LineListener() {
                            @Override public void onLine(String line) { appendLog(line); }
                        });
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        showInfoDialog(r.exitCode == 0 ? "Root Shell Verified!" : "Root Not Active Yet", r.stdout);
                    }
                });
            }
        }).start();
    }

    private void startCreateBackupOperation() {
        if (taskRunning || snapshot == null) return;
        final File volRoot = getSelectedVolumeRoot();
        final BackupManager.Options opts = new BackupManager.Options();
        opts.includeBootAndRecovery = cbBootRec.isChecked();
        opts.includeNvramAndScatter = cbNvramScatter.isChecked();
        opts.includeSystemPartition = cbSystemImg.isChecked();
        opts.includeUserAppsData = cbUserApps.isChecked();

        taskRunning = true;
        setProgressUi(true, 2, "Preparing OS backup...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File out = backupManager.createBackup(snapshot, volRoot, opts,
                            new BackupManager.ProgressCallback() {
                                @Override public void onProgress(int pct, String status) { setProgressUi(true, pct, status); }
                                @Override public void onLog(String line) { appendLog(line); }
                            });
                    final DeviceInspector.Snapshot updated = DeviceInspector.collect(MainActivity.this, rootShell);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            snapshot = updated;
                            setProgressUi(false, 100, "");
                            renderSnapshot();
                            showInfoDialog("OS Backup Complete & Verified!",
                                    "Saved full verified backup to:\n" + out.getAbsolutePath()
                                    + "\n\nIncludes SHA256SUMS + SP Flash Tool scatter metadata. "
                                    + "You are now completely protected to proceed to Step 3 (Install Normal Android 4)!");
                        }
                    });
                } catch (final Throwable t) {
                    appendLog("BACKUP ERROR: " + t.getMessage());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 0, "");
                            showInfoDialog("Backup Stopped", t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void verifySavedBackup(final BackupManager.SavedBackup b) {
        if (taskRunning) return;
        taskRunning = true;
        setProgressUi(true, 5, "Verifying SHA-256 hashes for " + b.name + "...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = backupManager.verifyBackupIntegrity(b, new BackupManager.ProgressCallback() {
                    @Override public void onProgress(int pct, String s) { setProgressUi(true, pct, s); }
                    @Override public void onLog(String line) { appendLog(line); }
                });
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        taskRunning = false;
                        setProgressUi(false, 100, "");
                        showInfoDialog(ok ? "Backup Checksums 100% Valid" : "Checksum Verification Failed!",
                                ok ? ("Every image file in " + b.name + " matches its original SHA-256 hash.")
                                   : "One or more files failed SHA-256 verification. Check console log.");
                    }
                });
            }
        }).start();
    }

    private void confirmAndRestoreBackup(final BackupManager.SavedBackup b) {
        final boolean[] choices = new boolean[]{true, true, false, true};
        String[] labels = new String[]{
                "Boot Kernel Partition (boot.img.gz)",
                "Recovery Partition (recovery.img.gz)",
                "MediaTek NVRAM / Protect Calibration",
                "System OS Partition (system.ext4.img.gz)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Restore OS Backup: " + b.name)
                .setMultiChoiceItems(labels, choices, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        choices[which] = isChecked;
                    }
                })
                .setPositiveButton("Verify & Restore Now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runRestoreBackupAsync(b, choices[0], choices[1], choices[2], choices[3]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runRestoreBackupAsync(final BackupManager.SavedBackup b,
                                       final boolean boot, final boolean rec,
                                       final boolean nvram, final boolean sys) {
        if (taskRunning || snapshot == null) return;
        taskRunning = true;
        setProgressUi(true, 2, "Verifying SHA-256 checksums before restore...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    backupManager.restoreBackup(b, snapshot, boot, rec, nvram, sys,
                            new BackupManager.ProgressCallback() {
                                @Override public void onProgress(int pct, String s) { setProgressUi(true, pct, s); }
                                @Override public void onLog(String line) { appendLog(line); }
                            });
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 100, "");
                            confirmReboot("", "Reboot Now to Boot Restored OS");
                        }
                    });
                } catch (final Throwable t) {
                    appendLog("RESTORE ERROR: " + t.getMessage());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 0, "");
                            showInfoDialog("Restore Aborted Safely", t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void confirmAndConvertStockAndroid4() {
        if (taskRunning || snapshot == null) return;
        List<BackupManager.SavedBackup> backups = backupManager.scanSavedBackups(snapshot.volumes);
        String warnPrefix = backups.isEmpty()
                ? "NOTE: You have not created a full OS Backup in Step 2 yet! (Your removed Kobo APKs WILL still be archived to Arc7Freedom/kobo-system-archive/.)\n\n"
                : "";

        new AlertDialog.Builder(this)
                .setTitle("Install Normal Android 4.2 (Jelly Bean)")
                .setMessage(warnPrefix
                        + "This will:\n"
                        + "1. Install pure Stock Android 4.2 Launcher (/system/app/VanillaAndroid4Home.apk)\n"
                        + "2. Archive & remove Kobo Setup Wizard (fixes Error 7 lock-out!) + Kobo Tapestry Launcher\n"
                        + "3. Enable DEVICE_PROVISIONED=1 so Home & Recents keys work permanently\n"
                        + "4. Preserve 100% of working MT8125/Tegra Wi-Fi, GPU, touch & audio drivers.\n\nProceed?")
                .setPositiveButton("Install Normal Android 4.2", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runStockAndroid4ConversionAsync();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runStockAndroid4ConversionAsync() {
        taskRunning = true;
        final File volRoot = getSelectedVolumeRoot();
        final Android4Installer.ConversionOptions opts = new Android4Installer.ConversionOptions();
        opts.keepKoboReaderApp = cbKeepKoboReader.isChecked();
        opts.enableUnknownSources = cbEnableUnknownSources.isChecked();

        setProgressUi(true, 5, "Converting OS to clean Stock Android 4.2 Jelly Bean...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android4Installer.convertInPlaceToStockAndroid4(snapshot, volRoot, opts,
                            new BackupManager.ProgressCallback() {
                                @Override public void onProgress(int pct, String s) { setProgressUi(true, pct, s); }
                                @Override public void onLog(String line) { appendLog(line); }
                            });
                    final DeviceInspector.Snapshot updated = DeviceInspector.collect(MainActivity.this, rootShell);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            snapshot = updated;
                            setProgressUi(false, 100, "");
                            renderSnapshot();
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Normal Android 4.2 Installed!")
                                    .setMessage("Your Kobo Arc 7 now runs clean Stock Android 4.2 Jelly Bean!\n\n"
                                            + "Reboot now for a completely clean startup, or tap 'Open Android 4 Home' right away "
                                            + "(select 'Android 4 Home' -> 'Always' when prompted).")
                                    .setPositiveButton("Reboot Now (Recommended)", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) {
                                            rootShell.execRoot("sync && reboot", null);
                                        }
                                    })
                                    .setNeutralButton("Open Android 4 Home Now", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) {
                                            launchOrInstallVanillaHome();
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (final Throwable t) {
                    appendLog("ANDROID 4 INSTALL ERROR: " + t.getMessage());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 0, "");
                            showInfoDialog("Conversion Error", t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void confirmAndRestoreKoboUi() {
        if (taskRunning || snapshot == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Restore Stock Kobo UI?")
                .setMessage("This copies all archived Kobo system APKs back to /system/app and re-enables Kobo packages.")
                .setPositiveButton("Restore Kobo UI", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        taskRunning = true;
                        final File volRoot = getSelectedVolumeRoot();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    android4Installer.restoreStockKoboUi(snapshot, volRoot,
                                            new BackupManager.ProgressCallback() {
                                                @Override public void onProgress(int p, String s) { setProgressUi(true, p, s); }
                                                @Override public void onLog(String l) { appendLog(l); }
                                            });
                                    uiHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            taskRunning = false;
                                            setProgressUi(false, 100, "");
                                            confirmReboot("", "Reboot to Load Kobo UI");
                                        }
                                    });
                                } catch (final Throwable t) {
                                    uiHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            taskRunning = false;
                                            setProgressUi(false, 0, "");
                                            showInfoDialog("Rollback Notice", t.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startFlashRecoveryOperation() {
        if (taskRunning || snapshot == null) return;
        if (selectedRecoveryImg == null || !selectedRecoveryImg.exists()) {
            showInfoDialog("Select recovery.img First",
                    "Tap 'Browse recovery.img' and pick your TWRP / CTR / CWM recovery image (.img) for Kobo Arc 7.");
            return;
        }
        taskRunning = true;
        final File volRoot = getSelectedVolumeRoot();
        setProgressUi(true, 5, "Validating & flashing custom recovery...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android4Installer.flashRecoveryImage(snapshot, selectedRecoveryImg, volRoot,
                            new BackupManager.ProgressCallback() {
                                @Override public void onProgress(int p, String s) { setProgressUi(true, p, s); }
                                @Override public void onLog(String l) { appendLog(l); }
                            });
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 100, "");
                            confirmReboot("recovery", "Custom Recovery Verified! Boot into Recovery Now?");
                        }
                    });
                } catch (final Throwable t) {
                    appendLog("RECOVERY FLASH ERROR: " + t.getMessage());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 0, "");
                            showInfoDialog("Recovery Flash Stopped Safely", t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void onRomZipPicked(File file) {
        selectedRomZip = file;
        Android4Installer.ZipInspection zi = android4Installer.inspectRomZip(file, snapshot);
        pickedRomLabel.setText("Selected ROM: " + file.getName() + "\n" + zi.summary);
        pickedRomLabel.setTextColor(zi.boardMismatchWarning ? STATUS_ERR : (zi.validFlashableZip ? STATUS_OK : STATUS_WARN));
        appendLog("Inspected ROM .zip: " + file.getAbsolutePath() + " -> " + zi.summary);
    }

    private void confirmAndFlashRomZip() {
        if (taskRunning || snapshot == null) return;
        if (selectedRomZip == null || !selectedRomZip.exists()) {
            showInfoDialog("No ROM .zip Selected",
                    "Tap 'Select ROM .zip' to pick an Android 4 ROM file, or tap 'Build Clean Android 4 .zip' to generate one automatically!");
            return;
        }
        Android4Installer.ZipInspection zi = android4Installer.inspectRomZip(selectedRomZip, snapshot);
        if (!zi.validFlashableZip) {
            showInfoDialog("Invalid Recovery ZIP", zi.summary);
            return;
        }
        String msg = (zi.boardMismatchWarning ? (zi.summary + "\n\nARE YOU SURE YOU WANT TO PROCEED?\n\n") : "")
                + "Writes OpenRecoveryScript + recovery commands for:\n" + selectedRomZip.getAbsolutePath()
                + "\nand immediately reboots into Recovery Mode to install.";
        new AlertDialog.Builder(this)
                .setTitle("Stage ROM .zip & Reboot to Recovery")
                .setMessage(msg)
                .setPositiveButton("Reboot & Install ROM", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final boolean wc = cbWipeCache.isChecked();
                        final boolean wd = cbWipeData.isChecked();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    android4Installer.stageRomZipAndRebootRecovery(selectedRomZip, wc, wd,
                                            new BackupManager.ProgressCallback() {
                                                @Override public void onProgress(int p, String s) { setProgressUi(true, p, s); }
                                                @Override public void onLog(String l) { appendLog(l); }
                                            });
                                } catch (final Throwable t) {
                                    uiHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            setProgressUi(false, 0, "");
                                            showInfoDialog("Recovery Stage Failed", t.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startBuildVanillaRomZipOperation() {
        if (taskRunning || snapshot == null) return;
        taskRunning = true;
        final File volRoot = getSelectedVolumeRoot();
        setProgressUi(true, 10, "Building standalone Vanilla-Android-4.2.2-KoboArc7.zip...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File zip = android4Installer.buildCleanVanillaAndroid4Zip(snapshot, volRoot,
                            new BackupManager.ProgressCallback() {
                                @Override public void onProgress(int p, String s) { setProgressUi(true, p, s); }
                                @Override public void onLog(String l) { appendLog(l); }
                            });
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 100, "");
                            onRomZipPicked(zip);
                            showInfoDialog("Flashable Vanilla Android 4.2 ROM .zip Built!",
                                    "Generated complete recovery-flashable ROM package:\n" + zip.getAbsolutePath()
                                    + "\n\nIt has been automatically selected above so you can flash it via Custom Recovery anytime!");
                        }
                    });
                } catch (final Throwable t) {
                    appendLog("ROM BUILDER ERROR: " + t.getMessage());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskRunning = false;
                            setProgressUi(false, 0, "");
                            showInfoDialog("ROM Builder Error", t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void launchOrInstallVanillaHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setComponent(new ComponentName(
                    "ca.arc7freedom.launcher", "ca.arc7freedom.launcher.VanillaHomeActivity"));
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable notInstalledYet) {
            try {
                File apk = android4Installer.extractBundledLauncherApk();
                File pubCopy = new File(Environment.getExternalStorageDirectory(), "VanillaAndroid4Home.apk");
                RootShell.copyFile(apk, pubCopy);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(android.net.Uri.fromFile(pubCopy), "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(install);
            } catch (Throwable e) {
                showInfoDialog("Cannot Launch Launcher", e.getMessage());
            }
        }
    }

    private void runQuickRootCmd(final String cmd, final String doneMsg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell.ExecResult r = rootShell.execRoot(cmd, new RootShell.LineListener() {
                    @Override public void onLine(String l) { appendLog(l); }
                });
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        showInfoDialog(r.exitCode == 0 ? "Done" : "Failed",
                                r.exitCode == 0 ? doneMsg : r.stdout);
                    }
                });
            }
        }).start();
    }

    private void confirmReboot(final String mode, String label) {
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage("Reboot tablet now" + (mode.isEmpty() ? "?" : (" into '" + mode + "' mode?")))
                .setPositiveButton("Reboot Now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String c = mode.isEmpty() ? "sync && reboot" : ("sync && reboot " + mode);
                                rootShell.execRoot(c, null);
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void exportDiagnosticLog() {
        try {
            File root = getSelectedVolumeRoot();
            File out = new File(root, "Arc7Freedom/arc7-diagnostic-log.txt");
            BackupManager.writeTextFile(out, consoleText.getText().toString());
            Toast.makeText(this, "Saved log to " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Save failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private File getSelectedVolumeRoot() {
        if (snapshot != null && !snapshot.volumes.isEmpty()
                && selectedVolumeIndex >= 0 && selectedVolumeIndex < snapshot.volumes.size()) {
            return snapshot.volumes.get(selectedVolumeIndex).rootDir;
        }
        return Environment.getExternalStorageDirectory();
    }

    /* ========================== UI HELPERS ========================== */
    private void selectTab(int index) {
        for (int i = 0; i < 4; i++) {
            tabPages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navTabs[i].setTextColor(i == index ? ACCENT_CYAN : TEXT_MUTED);
            navTabs[i].setBackgroundColor(i == index ? BG_ELEVATED : BG_SURFACE);
        }
    }

    private void setProgressUi(final boolean visible, final int pct, final String msg) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                progressBanner.setVisibility(visible ? View.VISIBLE : View.GONE);
                progressBar.setProgress(Math.max(0, Math.min(100, pct)));
                progressStatusLabel.setText(msg);
            }
        });
    }

    private void appendLog(final String line) {
        if (line == null) return;
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (consoleText == null) return;
                consoleText.append(line + "\n");
                consoleScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        consoleScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void showInfoDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        setBg(card, box(BG_SURFACE, BORDER_SUBTLE, 1, 8));
        return card;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private void addCardHeader(LinearLayout card, String title, String subtitle) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        card.addView(t);

        TextView sub = makeBodyText(subtitle);
        sub.setPadding(0, dp(4), 0, dp(4));
        card.addView(sub);
    }

    private TextView makeSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ACCENT_CYAN);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private TextView makeBodyText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return tv;
    }

    private CheckBox makeCheckBox(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(TEXT_PRIMARY);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cb.setChecked(checked);
        cb.setPadding(dp(6), dp(6), dp(6), dp(6));
        return cb;
    }

    private Button makePrimaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        setBg(b, box(ACCENT_DARK, ACCENT_CYAN, 1, 6));
        return b;
    }

    private Button makeSecondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT_PRIMARY);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setBg(b, box(BG_ELEVATED, BORDER_SUBTLE, 1, 6));
        return b;
    }

    private void addKvRow(LinearLayout parent, String key, String val) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        setBg(row, box(BG_ELEVATED, BORDER_SUBTLE, 1, 5));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(ACCENT_CYAN);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        k.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        row.addView(k);

        TextView v = new TextView(this);
        v.setText(val);
        v.setTextColor(TEXT_PRIMARY);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(0, dp(2), 0, 0);
        row.addView(v);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(row, lp);
    }

    private void addToolActionRow(LinearLayout parent, String title, String desc,
                                  String btnText, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        setBg(row, box(BG_ELEVATED, BORDER_SUBTLE, 1, 6));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_PRIMARY);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(t);

        TextView d = makeBodyText(desc);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button b = makeSecondaryButton(btnText);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        blp.leftMargin = dp(10);
        row.addView(b, blp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        parent.addView(row, rlp);
    }
}
