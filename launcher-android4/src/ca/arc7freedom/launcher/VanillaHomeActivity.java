package ca.arc7freedom.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure Stock Android 4.2 Jelly Bean (Holo) Home Launcher tailored for Kobo Arc 7 (1024x600)
 * and Kobo Arc 7HD (1920x1200) in both portrait and landscape orientations.
 */
public class VanillaHomeActivity extends Activity {

    private static final int HOLO_BLUE = 0xFF33B5E5;

    private FrameLayout rootFrame;
    private LinearLayout desktopRoot;
    private LinearLayout drawerRoot;

    private TextView clockTimeText;
    private TextView clockDateText;
    private View clockCard;
    private GridView workspaceGrid;
    private View[] pageBars = new View[PinnedShortcutsStore.PAGE_COUNT];
    private LinearLayout dockContainer;

    private GridView drawerGrid;
    private EditText drawerFilterInput;
    private TextView tabAllApps;
    private TextView tabUserApps;
    private boolean userAppsOnly = false;

    private int currentPage = PinnedShortcutsStore.DEFAULT_PAGE;
    private List<PinnedShortcutsStore.AppEntry> allApps = new ArrayList<PinnedShortcutsStore.AppEntry>();
    private List<PinnedShortcutsStore.AppEntry> filteredApps = new ArrayList<PinnedShortcutsStore.AppEntry>();
    private WorkspaceAdapter workspaceAdapter;
    private DrawerAdapter drawerAdapter;

    private float touchDownX = 0f;
    private float touchDownY = 0f;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadAppsAndRefresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BootProvisionReceiver.ensureProvisioned(this);

        buildUi();
        reloadAppsAndRefresh();
        updateClock();

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(pkgReceiver, pkgFilter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        reloadAppsAndRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(timeReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home key from Drawer returns to Desktop; pressing Home on Desktop centers Page 1
        if (drawerRoot != null && drawerRoot.getVisibility() == View.VISIBLE) {
            setDrawerVisible(false);
        } else if (currentPage != PinnedShortcutsStore.DEFAULT_PAGE) {
            setPage(PinnedShortcutsStore.DEFAULT_PAGE);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerRoot != null && drawerRoot.getVisibility() == View.VISIBLE) {
            setDrawerVisible(false);
        }
        // Standard Android Home Launcher ignores Back on root workspace
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyResponsiveColumns();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable roundRect(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            gd.setStroke(dp(strokeDp), strokeColor);
        }
        return gd;
    }

    @SuppressWarnings("deprecation")
    private void buildUi() {
        rootFrame = new FrameLayout(this);
        // Subtle dark translucent vignette over the user's Android wallpaper for crisp legibility
        rootFrame.setBackgroundColor(0x38000000);

        desktopRoot = new LinearLayout(this);
        desktopRoot.setOrientation(LinearLayout.VERTICAL);
        desktopRoot.setPadding(dp(14), dp(10), dp(14), dp(8));

        // 1. Top Jelly Bean Search & Quick-Action Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundDrawable(roundRect(0x8811161D, 0x44FFFFFF, 1, 6));
        topBar.setPadding(dp(12), dp(6), dp(8), dp(6));

        TextView gBadge = new TextView(this);
        gBadge.setText("Google");
        gBadge.setTextColor(0xFFE2E8F0);
        gBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        gBadge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        gBadge.setPadding(dp(2), 0, dp(10), 0);
        topBar.addView(gBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View vSep = new View(this);
        vSep.setBackgroundColor(0x33FFFFFF);
        topBar.addView(vSep, new LinearLayout.LayoutParams(dp(1), dp(22)));

        final EditText searchInput = new EditText(this);
        searchInput.setHint("Search or enter URL...");
        searchInput.setHintTextColor(0xAA94A3B8);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setPadding(dp(10), dp(4), dp(10), dp(4));
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                String q = searchInput.getText().toString().trim();
                if (!q.isEmpty()) {
                    launchWebOrQuery(q);
                    searchInput.setText("");
                }
                return true;
            }
        });
        topBar.addView(searchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView wallpaperBtn = makePillButton("Wallpaper");
        wallpaperBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLauncherWallpaperMenu();
            }
        });
        topBar.addView(wallpaperBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(dp(6), dp(1)));

        TextView settingsBtn = makePillButton("Settings");
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemSettings();
            }
        });
        topBar.addView(settingsBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        desktopRoot.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Jelly Bean Digital Clock Widget (visible on center Home page)
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER);
        clockBox.setPadding(0, dp(14), 0, dp(8));
        clockBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openClockOrDateSettings();
            }
        });

        clockTimeText = new TextView(this);
        clockTimeText.setTextColor(Color.WHITE);
        clockTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        clockTimeText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockTimeText.setShadowLayer(6f, 0f, 2f, 0xAA000000);
        clockTimeText.setGravity(Gravity.CENTER);
        clockBox.addView(clockTimeText);

        clockDateText = new TextView(this);
        clockDateText.setTextColor(0xFFE2E8F0);
        clockDateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clockDateText.setAllCaps(true);
        clockDateText.setShadowLayer(4f, 0f, 1f, 0xAA000000);
        clockDateText.setGravity(Gravity.CENTER);
        clockBox.addView(clockDateText);

        clockCard = clockBox;
        desktopRoot.addView(clockBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Pinned Workspace Grid (supports horizontal swipe to change pages!)
        workspaceGrid = new GridView(this) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                handleWorkspaceSwipeGesture(ev);
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (handleWorkspaceSwipeGesture(ev)) return true;
                return super.onTouchEvent(ev);
            }
        };
        workspaceGrid.setVerticalSpacing(dp(12));
        workspaceGrid.setHorizontalSpacing(dp(8));
        workspaceGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        workspaceGrid.setGravity(Gravity.CENTER);
        workspaceGrid.setSelector(android.R.color.transparent);
        workspaceAdapter = new WorkspaceAdapter();
        workspaceGrid.setAdapter(workspaceAdapter);
        workspaceGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ComponentName cn = workspaceAdapter.getItem(position);
                launchComponent(cn);
            }
        });
        workspaceGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final ComponentName cn = workspaceAdapter.getItem(position);
                showWorkspaceShortcutMenu(cn);
                return true;
            }
        });

        LinearLayout.LayoutParams wsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wsParams.topMargin = dp(6);
        desktopRoot.addView(workspaceGrid, wsParams);

        // 4. Classic Jelly Bean 3-Segment Holo Blue Page Indicator Bar
        LinearLayout indicatorRow = new LinearLayout(this);
        indicatorRow.setOrientation(LinearLayout.HORIZONTAL);
        indicatorRow.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < PinnedShortcutsStore.PAGE_COUNT; i++) {
            final int pageIdx = i;
            View seg = new View(this);
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setPage(pageIdx);
                }
            });
            pageBars[i] = seg;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(3), 1f);
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            indicatorRow.addView(seg, lp);
        }
        desktopRoot.addView(indicatorRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 5. Classic 5-Icon Hotseat Dock
        dockContainer = new LinearLayout(this);
        dockContainer.setOrientation(LinearLayout.HORIZONTAL);
        dockContainer.setGravity(Gravity.CENTER_VERTICAL);
        dockContainer.setPadding(dp(4), dp(4), dp(4), dp(2));
        desktopRoot.addView(dockContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        rootFrame.addView(desktopRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 6. All Apps Drawer Overlay (Authentic Android 4.2 Holo Dark App Drawer)
        buildDrawerOverlay();
        rootFrame.addView(drawerRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        applyResponsiveColumns();
        updatePageIndicator();
    }

    @SuppressWarnings("deprecation")
    private void buildDrawerOverlay() {
        drawerRoot = new LinearLayout(this);
        drawerRoot.setOrientation(LinearLayout.VERTICAL);
        drawerRoot.setBackgroundColor(0xF20D1117);
        drawerRoot.setVisibility(View.GONE);
        drawerRoot.setPadding(dp(14), dp(10), dp(14), dp(10));

        // Drawer Header Tabs + Filter + Close
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tabAllApps = makeDrawerTab("APPS", true);
        tabAllApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userAppsOnly = false;
                refreshTabStyles();
                applyDrawerFilter();
            }
        });
        header.addView(tabAllApps);

        tabUserApps = makeDrawerTab("DOWNLOADED", false);
        tabUserApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userAppsOnly = true;
                refreshTabStyles();
                applyDrawerFilter();
            }
        });
        header.addView(tabUserApps);

        drawerFilterInput = new EditText(this);
        drawerFilterInput.setHint("Filter apps...");
        drawerFilterInput.setHintTextColor(0x8894A3B8);
        drawerFilterInput.setTextColor(Color.WHITE);
        drawerFilterInput.setSingleLine(true);
        drawerFilterInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        drawerFilterInput.setBackgroundDrawable(roundRect(0xFF1A222D, 0xFF2D3A4B, 1, 4));
        drawerFilterInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        drawerFilterInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyDrawerFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        filterLp.leftMargin = dp(12);
        filterLp.rightMargin = dp(10);
        header.addView(drawerFilterInput, filterLp);

        TextView homeBtn = makePillButton("Home");
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDrawerVisible(false);
            }
        });
        header.addView(homeBtn);

        drawerRoot.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View holoLine = new View(this);
        holoLine.setBackgroundColor(HOLO_BLUE);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        lineLp.topMargin = dp(8);
        lineLp.bottomMargin = dp(10);
        drawerRoot.addView(holoLine, lineLp);

        drawerGrid = new GridView(this);
        drawerGrid.setVerticalSpacing(dp(14));
        drawerGrid.setHorizontalSpacing(dp(8));
        drawerGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        drawerGrid.setSelector(android.R.color.transparent);
        drawerAdapter = new DrawerAdapter();
        drawerGrid.setAdapter(drawerAdapter);
        drawerGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PinnedShortcutsStore.AppEntry entry = filteredApps.get(position);
                setDrawerVisible(false);
                launchComponent(new ComponentName(entry.packageName, entry.className));
            }
        });
        drawerGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final PinnedShortcutsStore.AppEntry entry = filteredApps.get(position);
                showAppOptionsDialog(entry);
                return true;
            }
        });
        drawerRoot.addView(drawerGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @SuppressWarnings("deprecation")
    private TextView makePillButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackgroundDrawable(roundRect(0x55243040, 0x5564748B, 1, 4));
        return tv;
    }

    private TextView makeDrawerTab(String label, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setTextColor(active ? HOLO_BLUE : 0xFF94A3B8);
        return tv;
    }

    private void refreshTabStyles() {
        tabAllApps.setTextColor(!userAppsOnly ? HOLO_BLUE : 0xFF94A3B8);
        tabUserApps.setTextColor(userAppsOnly ? HOLO_BLUE : 0xFF94A3B8);
    }

    private boolean handleWorkspaceSwipeGesture(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = ev.getRawX();
                touchDownY = ev.getRawY();
                break;
            case MotionEvent.ACTION_UP:
                float dx = ev.getRawX() - touchDownX;
                float dy = ev.getRawY() - touchDownY;
                if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx < 0 && currentPage < PinnedShortcutsStore.PAGE_COUNT - 1) {
                        setPage(currentPage + 1);
                        return true;
                    } else if (dx > 0 && currentPage > 0) {
                        setPage(currentPage - 1);
                        return true;
                    }
                }
                break;
        }
        return false;
    }

    private void setPage(int page) {
        currentPage = Math.max(0, Math.min(PinnedShortcutsStore.PAGE_COUNT - 1, page));
        clockCard.setVisibility(currentPage == PinnedShortcutsStore.DEFAULT_PAGE ? View.VISIBLE : View.GONE);
        updatePageIndicator();
        workspaceAdapter.reload();
    }

    private void updatePageIndicator() {
        for (int i = 0; i < pageBars.length; i++) {
            pageBars[i].setBackgroundColor(i == currentPage ? HOLO_BLUE : 0x33FFFFFF);
        }
    }

    private void applyResponsiveColumns() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (workspaceGrid != null) workspaceGrid.setNumColumns(land ? 6 : 4);
        if (drawerGrid != null) drawerGrid.setNumColumns(land ? 6 : 5);
    }

    private void setDrawerVisible(boolean show) {
        drawerRoot.setVisibility(show ? View.VISIBLE : View.GONE);
        desktopRoot.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        if (!show && drawerFilterInput != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(drawerFilterInput.getWindowToken(), 0);
        }
    }

    private void updateClock() {
        Date now = new Date();
        SimpleDateFormat tf = new SimpleDateFormat("h:mm", Locale.getDefault());
        SimpleDateFormat df = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        if (clockTimeText != null) clockTimeText.setText(tf.format(now));
        if (clockDateText != null) clockDateText.setText(df.format(now));
    }

    private void reloadAppsAndRefresh() {
        allApps = PinnedShortcutsStore.loadAllLaunchableApps(this);
        PinnedShortcutsStore.ensureInitialized(this, allApps);
        applyDrawerFilter();
        workspaceAdapter.reload();
        rebuildHotseatDock();
    }

    private void applyDrawerFilter() {
        String q = drawerFilterInput != null ? drawerFilterInput.getText().toString().trim().toLowerCase(Locale.US) : "";
        filteredApps.clear();
        for (PinnedShortcutsStore.AppEntry e : allApps) {
            if (userAppsOnly && e.isSystemApp) continue;
            if (!q.isEmpty()
                    && !e.label.toLowerCase(Locale.US).contains(q)
                    && !e.packageName.toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            filteredApps.add(e);
        }
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private void rebuildHotseatDock() {
        dockContainer.removeAllViews();
        PinnedShortcutsStore.AppEntry browser = findExactComponent("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7BrowserActivity");
        if (browser == null) browser = findApp("com.android.browser", "com.android.chrome", "org.mozilla.firefox");

        PinnedShortcutsStore.AppEntry readerOrStore = findExactComponent("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7EbookReaderActivity");
        if (readerOrStore == null) readerOrStore = findApp("org.fdroid.fdroid", "com.android.vending");

        PinnedShortcutsStore.AppEntry clockOrGallery = findExactComponent("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7ClockActivity");
        if (clockOrGallery == null) clockOrGallery = findApp("com.android.gallery3d", "com.android.providers.downloads.ui");

        PinnedShortcutsStore.AppEntry freedom = findExactComponent("ca.arc7freedom.app", "ca.arc7freedom.app.MainActivity");
        if (freedom == null) freedom = findApp("ca.arc7freedom.app", "com.android.settings");

        addDockAppSlot(browser, "Browser", Intent.ACTION_WEB_SEARCH);
        addDockAppSlot(readerOrStore, "E-Reader", null);

        // Center Jelly Bean All-Apps 6-dot Circle Icon
        View allAppsBtn = new AllAppsCircleView(this);
        allAppsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDrawerVisible(true);
            }
        });
        dockContainer.addView(allAppsBtn, new LinearLayout.LayoutParams(0, dp(56), 1f));

        addDockAppSlot(clockOrGallery, "Clock", null);
        if (freedom != null) {
            addDockAppSlot(freedom, "Freedom", null);
        } else {
            addDockSettingsSlot();
        }
    }

    private void addDockAppSlot(final PinnedShortcutsStore.AppEntry app, final String fallbackTitle, final String fallbackAction) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (app != null) {
            try {
                iv.setImageDrawable(getPackageManager().getActivityIcon(
                        new ComponentName(app.packageName, app.className)));
            } catch (Throwable t) {
                iv.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchComponent(new ComponentName(app.packageName, app.className));
                }
            });
        } else {
            iv.setImageResource(android.R.drawable.ic_menu_search);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fallbackAction != null) {
                        launchWebOrQuery("https://www.google.com");
                    } else {
                        setDrawerVisible(true);
                    }
                }
            });
        }
        dockContainer.addView(iv, new LinearLayout.LayoutParams(0, dp(54), 1f));
    }

    private void addDockSettingsSlot() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setImageResource(android.R.drawable.ic_menu_preferences);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemSettings();
            }
        });
        dockContainer.addView(iv, new LinearLayout.LayoutParams(0, dp(54), 1f));
    }

    private PinnedShortcutsStore.AppEntry findExactComponent(String pkg, String cls) {
        for (PinnedShortcutsStore.AppEntry e : allApps) {
            if (pkg.equals(e.packageName) && cls.equals(e.className)) return e;
        }
        return null;
    }

    private void showLauncherWallpaperMenu() {
        final String[] options = new String[LauncherWallpapers.TITLES.length + 2];
        options[0] = "★ Open Visual HD Wallpaper Studio & Custom Photos...";
        for (int i = 0; i < LauncherWallpapers.TITLES.length; i++) {
            options[i + 1] = LauncherWallpapers.TITLES[i];
        }
        options[options.length - 1] = "System Wallpaper Chooser...";
        new AlertDialog.Builder(this)
                .setTitle("Choose Home Wallpaper (12 Built-In HD Themes)")
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            PinnedShortcutsStore.AppEntry wp = findExactComponent(
                                    "ca.arc7freedom.app", "ca.arc7freedom.app.Arc7WallpaperPickerActivity");
                            if (wp != null) {
                                launchComponent(new ComponentName(wp.packageName, wp.className));
                            } else {
                                try {
                                    startActivity(Intent.createChooser(
                                            new Intent(Intent.ACTION_SET_WALLPAPER), "Set Wallpaper"));
                                } catch (Throwable ignored) {}
                            }
                        } else if (which <= LauncherWallpapers.TITLES.length) {
                            try {
                                LauncherWallpapers.applyTheme(VanillaHomeActivity.this, which - 1);
                                Toast.makeText(VanillaHomeActivity.this,
                                        "Applied " + LauncherWallpapers.TITLES[which - 1],
                                        Toast.LENGTH_SHORT).show();
                            } catch (Throwable t) {
                                Toast.makeText(VanillaHomeActivity.this,
                                        "Wallpaper error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            try {
                                startActivity(Intent.createChooser(
                                        new Intent(Intent.ACTION_SET_WALLPAPER), "Set Wallpaper"));
                            } catch (Throwable ignored) {}
                        }
                    }
                })
                .show();
    }
    private PinnedShortcutsStore.AppEntry findApp(String... pkgs) {
        for (String p : pkgs) {
            for (PinnedShortcutsStore.AppEntry e : allApps) {
                if (p.equals(e.packageName)) return e;
            }
        }
        return null;
    }

    private void launchWebOrQuery(String input) {
        try {
            String url = input.startsWith("http://") || input.startsWith("https://")
                    ? input
                    : (input.contains(".") && !input.contains(" ")
                        ? "https://" + input
                        : "https://www.google.com/search?q=" + Uri.encode(input));
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "No browser installed", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchSystemSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void openClockOrDateSettings() {
        PinnedShortcutsStore.AppEntry arcClk = findExactComponent("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7ClockActivity");
        if (arcClk != null) {
            launchComponent(new ComponentName(arcClk.packageName, arcClk.className));
            return;
        }
        PinnedShortcutsStore.AppEntry clk = findApp("com.android.deskclock", "com.google.android.deskclock");
        if (clk != null) {
            launchComponent(new ComponentName(clk.packageName, clk.className));
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

    private void launchComponent(ComponentName cn) {
        if (cn == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
                if (fallback != null) startActivity(fallback);
            } catch (Throwable t2) {
                Toast.makeText(this, "Unable to launch app", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showAppOptionsDialog(final PinnedShortcutsStore.AppEntry entry) {
        String[] items = new String[]{
                "Pin to Home Screen (Center Page)",
                "Pin to Left Page",
                "Pin to Right Page",
                "App Info",
                "Uninstall App"
        };
        new AlertDialog.Builder(this)
                .setTitle(entry.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which <= 2) {
                            int targetPage = (which == 0) ? 1 : (which == 1 ? 0 : 2);
                            PinnedShortcutsStore.addShortcut(VanillaHomeActivity.this, targetPage, entry.flatten());
                            setPage(targetPage);
                            setDrawerVisible(false);
                            Toast.makeText(VanillaHomeActivity.this,
                                    "Pinned " + entry.label + " to Home", Toast.LENGTH_SHORT).show();
                        } else if (which == 3) {
                            openAppInfo(entry.packageName);
                        } else if (which == 4) {
                            uninstallPackage(entry.packageName);
                        }
                    }
                })
                .show();
    }

    private void showWorkspaceShortcutMenu(final ComponentName cn) {
        String[] items = new String[]{
                "Remove from Home Screen",
                "App Info",
                "Uninstall App"
        };
        new AlertDialog.Builder(this)
                .setTitle(cn.getPackageName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            PinnedShortcutsStore.removeShortcut(
                                    VanillaHomeActivity.this, currentPage, cn.flattenToString());
                            workspaceAdapter.reload();
                        } else if (which == 1) {
                            openAppInfo(cn.getPackageName());
                        } else if (which == 2) {
                            uninstallPackage(cn.getPackageName());
                        }
                    }
                })
                .show();
    }

    private void openAppInfo(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void uninstallPackage(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private View buildAppTile(View convertView, Drawable icon, String title) {
        LinearLayout box;
        ImageView iv;
        TextView tv;
        if (convertView instanceof LinearLayout) {
            box = (LinearLayout) convertView;
            iv = (ImageView) box.getChildAt(0);
            tv = (TextView) box.getChildAt(1);
        } else {
            box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(4), dp(6), dp(4), dp(6));

            iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));

            tv = new TextView(this);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setShadowLayer(4f, 0f, 1f, 0xDD000000);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            box.addView(tv, tlp);
        }
        iv.setImageDrawable(icon);
        tv.setText(title);
        return box;
    }

    private class WorkspaceAdapter extends BaseAdapter {
        private List<ComponentName> items = new ArrayList<ComponentName>();

        public void reload() {
            PackageManager pm = getPackageManager();
            List<ComponentName> raw = PinnedShortcutsStore.getPageShortcuts(VanillaHomeActivity.this, currentPage);
            items.clear();
            for (ComponentName cn : raw) {
                try {
                    ActivityInfo ai = pm.getActivityInfo(cn, 0);
                    if (ai != null && ai.enabled && ai.applicationInfo.enabled) {
                        items.add(cn);
                    }
                } catch (Throwable ignored) {
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public ComponentName getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ComponentName cn = items.get(position);
            PackageManager pm = getPackageManager();
            Drawable icon;
            String label = cn.getPackageName();
            try {
                ActivityInfo ai = pm.getActivityInfo(cn, 0);
                icon = ai.loadIcon(pm);
                CharSequence cs = ai.loadLabel(pm);
                if (cs != null) label = cs.toString();
            } catch (Throwable t) {
                icon = getResources().getDrawable(android.R.drawable.sym_def_app_icon);
            }
            return buildAppTile(convertView, icon, label);
        }
    }

    private class DrawerAdapter extends BaseAdapter {
        @Override public int getCount() { return filteredApps.size(); }
        @Override public PinnedShortcutsStore.AppEntry getItem(int position) { return filteredApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PinnedShortcutsStore.AppEntry e = filteredApps.get(position);
            Drawable icon;
            try {
                icon = getPackageManager().getActivityIcon(new ComponentName(e.packageName, e.className));
            } catch (Throwable t) {
                icon = getResources().getDrawable(android.R.drawable.sym_def_app_icon);
            }
            return buildAppTile(convertView, icon, e.label);
        }
    }

    /** Crisp vector-drawn Android 4.2 Jelly Bean All-Apps Circle Icon */
    private static class AllAppsCircleView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public AllAppsCircleView(Context context) {
            super(context);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setColor(0xDDFFFFFF);
            ringPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2.0f);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0x33FFFFFF);

            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float r = Math.min(getWidth(), getHeight()) * 0.42f;
            canvas.drawCircle(cx, cy, r, fillPaint);
            canvas.drawCircle(cx, cy, r, ringPaint);

            float step = r * 0.40f;
            float sq = r * 0.12f;
            for (int row = -1; row <= 1; row++) {
                for (int col = -1; col <= 1; col++) {
                    if (row == 1 && (col == -1 || col == 1)) {
                        // Classic 6-dot / 3x2+ grid motif of Android 4.2 All-Apps icon
                        canvas.drawRect(cx + col * step - sq, cy + row * step - sq,
                                cx + col * step + sq, cy + row * step + sq, dotPaint);
                    } else {
                        canvas.drawRect(cx + col * step - sq, cy + row * step - sq,
                                cx + col * step + sq, cy + row * step + sq, dotPaint);
                    }
                }
            }
        }
    }
}
