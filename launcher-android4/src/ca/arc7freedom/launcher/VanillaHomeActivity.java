package ca.arc7freedom.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Skinned Android 4.2.2 Jelly Bean Home Screen Launcher (`v5.1.0-arc7-skin`, `versionCode=51`):
 *   - Fully Customizable Multi-Edge Home App Bar (`AppBarConfigStore`):
 *       * Add, remove, reorder, or replace ANY installed or built-in app slot (up to 12 slots w/ smooth scroll)
 *       * Move App Bar to Bottom, Top, Left Side Rail, Right Side Rail, or Hidden (Swipe-Up opens All Apps)
 *       * Choose Floating Glass Pill, Full-Width Edge Bar, Minimal Transparent, Solid Slate, or Neon Rim
 *       * Customize All-Apps button placement, Icon Size (34–58 dp), App Labels, Glass Opacity, & Accent Swatch
 *       * Customize Home Top Bar style & individual chips (Clock/Date, Search, App Bar, Skin, Wallpaper)
 *       * Syncs In-App Top App Bar density, alignment, style & Quick App Switcher across all Arc7 apps
 *   - Long-press anywhere on empty Home workspace to place live Android AppWidgets (`AppWidgetHost` 1024)
 *   - Automatic 12-hour (`AM/PM`) and 24-hour time formatting + 60 fps spring & page animations
 */
public class VanillaHomeActivity extends Activity {

    private static final int MAX_PAGES = 5;
    private static final int APPWIDGET_HOST_ID = 1024;
    private static final int REQ_BIND_APPWIDGET = 4101;
    private static final int REQ_CONFIGURE_APPWIDGET = 4102;

    private static class AppEntry {
        String label;
        String pkg;
        String cls;
        String folderToken; // non-null when entry represents an `@folder:Name|...` desktop folder!
        Drawable icon;
    }

    private static class ResolvedBarSlot {
        String token;
        String label;
        Drawable icon;
        View.OnClickListener action;
    }

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> filteredApps = new ArrayList<AppEntry>();
    private final List<AppEntry> currentPageShortcuts = new ArrayList<AppEntry>();
    private final TextView[] pageDots = new TextView[MAX_PAGES];
    private GridView desktopGridView;

    private AppWidgetManager appWidgetManager;
    private AppWidgetHost appWidgetHost;
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private AppWidgetProviderInfo pendingWidgetInfo = null;

    private int currentPage = 0;
    private LinearLayout outerShell;
    private FrameLayout leftRailSlot;
    private FrameLayout rightRailSlot;
    private FrameLayout topBarSlot;
    private FrameLayout bottomBarSlot;
    private LinearLayout desktopView;
    private LinearLayout topCapsule;
    private LinearLayout clockCol;
    private LinearLayout widgetsContainer;
    private LinearLayout drawerOverlay;
    private LinearLayout dockBar;
    private TextView drawerTitleView;
    private View drawerDividerView;
    private TextView clockView;
    private TextView dateView;
    private TextView searchChip;
    private TextView appBarChip;
    private TextView skinChip;
    private TextView wallpaperChip;
    private EditText searchBox;
    private AppGridAdapter desktopAdapter;
    private AppGridAdapter drawerAdapter;
    private GestureDetector swipeDetector;
    private boolean drawerOpen = false;
    private long lastChildLongPressMs = 0L;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Arc7LauncherSkin.clearCache();
            reloadApps();
        }
    };

    private final BroadcastReceiver clockTickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateDateLabel();
            applyLauncherSkinChrome();
            rebuildDock();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appWidgetManager = AppWidgetManager.getInstance(getApplicationContext());
        appWidgetHost = new AppWidgetHost(getApplicationContext(), APPWIDGET_HOST_ID);

        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (drawerOpen) return;
                long now = System.currentTimeMillis();
                if (now - lastChildLongPressMs < 650L) return;
                if (dockBar != null && dockBar.isShown() && e != null && isPointInsideView(dockBar, e.getRawX(), e.getRawY())) {
                    lastChildLongPressMs = now;
                    dockBar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    showAppBarCustomizerDialog();
                    return;
                }
                if (desktopView != null) {
                    desktopView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                showWidgetPickerDialog();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null || drawerOpen) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > dp(75) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    if (dx < 0 && currentPage < PinnedShortcutsStore.getPageCount(VanillaHomeActivity.this) - 1) {
                        switchToPage(currentPage + 1);
                        return true;
                    } else if (dx > 0 && currentPage > 0) {
                        switchToPage(currentPage - 1);
                        return true;
                    }
                } else if (dy < -dp(85) && Math.abs(dy) > Math.abs(dx) * 1.4f && velocityY < -280f) {
                    // Smooth upward swipe opens All-Apps Drawer (essential when App Bar / Drawer jewel is hidden!)
                    openDrawer();
                    return true;
                }
                return false;
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Multi-edge shell supporting Left Rail + Center Desktop (Top Bar / Bottom Dock) + Right Rail
        outerShell = new LinearLayout(this);
        outerShell.setOrientation(LinearLayout.HORIZONTAL);
        outerShell.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        leftRailSlot = new FrameLayout(this);
        leftRailSlot.setVisibility(View.GONE);
        outerShell.addView(leftRailSlot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Center Desktop Workspace
        desktopView = new LinearLayout(this);
        desktopView.setOrientation(LinearLayout.VERTICAL);
        desktopView.setPadding(dp(14), dp(8), dp(14), dp(8));
        desktopView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showWidgetPickerDialog();
                return true;
            }
        });

        topBarSlot = new FrameLayout(this);
        topBarSlot.setVisibility(View.GONE);
        desktopView.addView(topBarSlot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Skinned Customizable Top Status / Action Capsule
        topCapsule = new LinearLayout(this);
        topCapsule.setOrientation(LinearLayout.HORIZONTAL);
        topCapsule.setGravity(Gravity.CENTER_VERTICAL);
        topCapsule.setPadding(dp(12), dp(6), dp(12), dp(6));
        topCapsule.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showAppBarCustomizerDialog();
                return true;
            }
        });

        clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.HORIZONTAL);
        clockCol.setGravity(Gravity.CENTER_VERTICAL);

        clockView = new TextView(this);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        clockView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7ClockActivity");
            }
        });
        clockCol.addView(clockView);

        dateView = new TextView(this);
        dateView.setTextColor(0xEEFFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        dateView.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        dateView.setPadding(dp(10), dp(4), 0, 0);
        dateView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7CalendarActivity");
            }
        });
        updateDateLabel();
        clockCol.addView(dateView);

        topCapsule.addView(clockCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View spacerFlex = new View(this);
        topCapsule.addView(spacerFlex, new LinearLayout.LayoutParams(dp(4), 1));

        searchChip = makeTopChip("Search", 0x991E293B);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.rightMargin = dp(6);
        searchChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
                if (searchBox != null) searchBox.requestFocus();
            }
        });
        topCapsule.addView(searchChip, srLp);

        appBarChip = makeTopChip("App Bar", 0xB30F2942);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.rightMargin = dp(6);
        appBarChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppBarCustomizerDialog();
            }
        });
        topCapsule.addView(appBarChip, abLp);

        skinChip = makeTopChip("Skin", 0x991E293B);
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        skLp.rightMargin = dp(6);
        skinChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLauncherSkinDialog();
            }
        });
        topCapsule.addView(skinChip, skLp);

        wallpaperChip = makeTopChip("Wallpaper", 0x991E293B);
        wallpaperChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7WallpaperPickerActivity")) {
                    Intent pick = new Intent(Intent.ACTION_SET_WALLPAPER);
                    try {
                        startActivity(Intent.createChooser(pick, "Choose Wallpaper"));
                    } catch (Throwable ignored) {}
                }
            }
        });
        topCapsule.addView(wallpaperChip);

        desktopView.addView(topCapsule);

        // Live Home Screen Widgets area
        ScrollView widgetScroll = new ScrollView(this);
        widgetScroll.setVerticalScrollBarEnabled(false);
        widgetsContainer = new LinearLayout(this);
        widgetsContainer.setOrientation(LinearLayout.VERTICAL);
        widgetScroll.addView(widgetsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        desktopView.addView(widgetScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pinned Home Shortcuts Grid
        GridView desktopGrid = new GridView(this);
        desktopGridView = desktopGrid;
        desktopGrid.setNumColumns(PinnedShortcutsStore.getDesktopColumns(this));
        desktopGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        desktopGrid.setVerticalSpacing(dp(10));
        desktopGrid.setHorizontalSpacing(dp(8));
        desktopGrid.setGravity(Gravity.CENTER);
        desktopAdapter = new AppGridAdapter(currentPageShortcuts, true);
        desktopGrid.setAdapter(desktopAdapter);
        desktopGrid.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showWidgetPickerDialog();
                return true;
            }
        });
        desktopView.addView(desktopGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Multi-Page Dot Indicator Row (3..5 pages)
        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        dotsRow.setPadding(0, dp(3), 0, dp(5));
        for (int i = 0; i < MAX_PAGES; i++) {
            final int targetPage = i;
            TextView dot = new TextView(this);
            dot.setText("\u25CF");
            dot.setPadding(dp(10), dp(2), dp(10), dp(2));
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchToPage(targetPage);
                }
            });
            pageDots[i] = dot;
            dotsRow.addView(dot);
        }
        desktopView.addView(dotsRow);

        // Customizable App Bar Container + Inner Items Strip
        bottomBarSlot = new FrameLayout(this);
        desktopView.addView(bottomBarSlot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dockBar = new LinearLayout(this);
        dockBar.setGravity(Gravity.CENTER);
        dockBar.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showAppBarCustomizerDialog();
                return true;
            }
        });

        outerShell.addView(desktopView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        rightRailSlot = new FrameLayout(this);
        rightRailSlot.setVisibility(View.GONE);
        outerShell.addView(rightRailSlot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(outerShell);

        // 2. Full-screen All-Apps Drawer Overlay (always carries a direct `App Bar` customizer button too!)
        drawerOverlay = new LinearLayout(this);
        drawerOverlay.setOrientation(LinearLayout.VERTICAL);
        drawerOverlay.setBackgroundColor(0xEA11161D);
        drawerOverlay.setPadding(dp(18), dp(14), dp(18), dp(12));
        drawerOverlay.setVisibility(View.GONE);

        LinearLayout drawerHeader = new LinearLayout(this);
        drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("ARC7 OS  \u2022  ALL APPS");
        drawerTitle.setTextColor(0xFF33B5E5);
        drawerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        drawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        drawerTitleView = drawerTitle;
        drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchBox = new EditText(this);
        searchBox.setHint("Search apps (long-press any app to pin to Home or App Bar)...");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(0x88FFFFFF);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams sParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sParams.leftMargin = dp(12);
        sParams.rightMargin = dp(10);
        searchBox.setLayoutParams(sParams);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        drawerHeader.addView(searchBox);

        TextView drawerCustomizeBtn = makeTopChip("\u2699 App Bar", 0xCC0F2942);
        LinearLayout.LayoutParams dcbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dcbLp.rightMargin = dp(8);
        drawerCustomizeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppBarCustomizerDialog();
            }
        });
        drawerHeader.addView(drawerCustomizeBtn, dcbLp);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("\u2715  HOME");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        closeBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });
        drawerHeader.addView(closeBtn);
        drawerOverlay.addView(drawerHeader);

        View divider = new View(this);
        divider.setBackgroundColor(0xFF33B5E5);
        drawerDividerView = divider;
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        divParams.topMargin = dp(6);
        divParams.bottomMargin = dp(12);
        drawerOverlay.addView(divider, divParams);

        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(14));
        grid.setHorizontalSpacing(dp(10));
        grid.setGravity(Gravity.CENTER);
        drawerAdapter = new AppGridAdapter(filteredApps, false);
        grid.setAdapter(drawerAdapter);
        drawerOverlay.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(drawerOverlay);
        setContentView(root);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(pkgReceiver, filter);

        IntentFilter clockFilter = new IntentFilter();
        clockFilter.addAction(Intent.ACTION_TIME_TICK);
        clockFilter.addAction(Intent.ACTION_TIME_CHANGED);
        clockFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        clockFilter.addAction(Arc7LauncherSkin.ACTION_SKIN_CHANGED);
        clockFilter.addAction(AppBarConfigStore.ACTION_APP_BAR_CHANGED);
        registerReceiver(clockTickReceiver, clockFilter);

        applyLauncherSkinChrome();
        reloadApps();
    }

    private boolean isPointInsideView(View v, float rawX, float rawY) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
    }

    private TextView makeTopChip(String text, int bgArgb) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgArgb);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x55FFFFFF);
        tv.setBackgroundDrawable(bg);
        return tv;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (appWidgetHost != null) {
            try {
                appWidgetHost.startListening();
            } catch (Throwable ignored) {}
        }
        renderWidgetsForCurrentPage();
    }

    @Override
    protected void onStop() {
        if (appWidgetHost != null) {
            try {
                appWidgetHost.stopListening();
            } catch (Throwable ignored) {}
        }
        super.onStop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void switchToPage(int index) {
        int clamped = Math.max(0, Math.min(PinnedShortcutsStore.getPageCount(this) - 1, index));
        int dir = clamped > currentPage ? 1 : (clamped < currentPage ? -1 : 0);
        currentPage = clamped;
        refreshCurrentPageShortcuts();
        renderWidgetsForCurrentPage();
        if (dir != 0 && desktopView != null) {
            desktopView.setTranslationX(dir * dp(34));
            desktopView.setAlpha(0.72f);
            desktopView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /* -------------------- Home Screen AppWidget Host Engine -------------------- */

    private int estimateWidgetHeightDp(AppWidgetProviderInfo info) {
        if (info == null) return 125;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int rawPx = Math.max(info.minHeight, info.minResizeHeight);
        if (rawPx <= 0) return 125;
        int dpVal = Math.round(rawPx / dm.density);
        return Math.max(92, Math.min(240, dpVal + 18));
    }

    private void renderWidgetsForCurrentPage() {
        if (widgetsContainer == null) return;
        widgetsContainer.removeAllViews();
        List<PinnedShortcutsStore.WidgetRecord> records =
                PinnedShortcutsStore.loadWidgets(this, currentPage);

        for (int i = 0; i < records.size(); i++) {
            final PinnedShortcutsStore.WidgetRecord rec = records.get(i);
            AppWidgetProviderInfo info = null;
            try {
                info = appWidgetManager.getAppWidgetInfo(rec.appWidgetId);
            } catch (Throwable ignored) {}

            if (info == null) {
                PinnedShortcutsStore.removeWidgetEverywhere(this, rec.appWidgetId, MAX_PAGES);
                continue;
            }

            try {
                FrameLayout frame = new FrameLayout(this);
                LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(rec.heightDp));
                frameLp.bottomMargin = dp(8);
                frame.setLayoutParams(frameLp);

                AppWidgetHostView hv = appWidgetHost.createView(
                        getApplicationContext(), rec.appWidgetId, info);
                hv.setAppWidget(rec.appWidgetId, info);
                hv.setPadding(dp(2), dp(2), dp(2), dp(2));

                final String widgetLabel = info.label != null ? info.label : "Widget";
                hv.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        lastChildLongPressMs = System.currentTimeMillis();
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showWidgetManageDialog(rec, widgetLabel);
                        return true;
                    }
                });
                frame.addView(hv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                TextView gear = new TextView(this);
                gear.setText("\u22EF");
                gear.setTextColor(0xCCFFFFFF);
                gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                gear.setPadding(dp(7), dp(1), dp(7), dp(2));
                GradientDrawable gbg = new GradientDrawable();
                gbg.setColor(0x66000000);
                gbg.setCornerRadius(dp(9));
                gear.setBackgroundDrawable(gbg);
                FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.RIGHT);
                glp.topMargin = dp(3);
                glp.rightMargin = dp(4);
                gear.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showWidgetManageDialog(rec, widgetLabel);
                    }
                });
                frame.addView(gear, glp);
                widgetsContainer.addView(frame);
            } catch (Throwable t) {
                PinnedShortcutsStore.removeWidgetEverywhere(this, rec.appWidgetId, MAX_PAGES);
            }
        }
    }

    private void showWidgetPickerDialog() {
        List<AppWidgetProviderInfo> providers;
        try {
            providers = appWidgetManager.getInstalledProviders();
        } catch (Throwable t) {
            Toast.makeText(this, "Widget manager error: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (providers == null || providers.isEmpty()) {
            Toast.makeText(this, "No widgets installed yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<AppWidgetProviderInfo> sorted = new ArrayList<AppWidgetProviderInfo>(providers);
        Collections.sort(sorted, new Comparator<AppWidgetProviderInfo>() {
            @Override
            public int compare(AppWidgetProviderInfo a, AppWidgetProviderInfo b) {
                boolean aArc = a.provider != null && "ca.arc7freedom.app".equals(a.provider.getPackageName());
                boolean bArc = b.provider != null && "ca.arc7freedom.app".equals(b.provider.getPackageName());
                if (aArc != bArc) return aArc ? -1 : 1;
                String la = a.label != null ? a.label : "";
                String lb = b.label != null ? b.label : "";
                return la.compareToIgnoreCase(lb);
            }
        });

        PackageManager pm = getPackageManager();
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        sv.addView(list);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Add Widget to Page " + (currentPage + 1))
                .setView(sv)
                .setNeutralButton("Customize App Bar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showAppBarCustomizerDialog();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        for (int i = 0; i < sorted.size(); i++) {
            final AppWidgetProviderInfo info = sorted.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable rbg = new GradientDrawable();
            rbg.setColor(0xFF1E293B);
            rbg.setCornerRadius(dp(8));
            row.setBackgroundDrawable(rbg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(8);
            row.setLayoutParams(rlp);

            ImageView iv = new ImageView(this);
            Drawable d = null;
            try {
                if (info.icon != 0) d = pm.getDrawable(info.provider.getPackageName(), info.icon, null);
                if (d == null) d = pm.getApplicationIcon(info.provider.getPackageName());
            } catch (Throwable ignored) {}
            if (d != null) iv.setImageDrawable(d);
            row.addView(iv, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);

            TextView title = new TextView(this);
            title.setText(info.label != null ? info.label : info.provider.getShortClassName());
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(title);

            int wDp = Math.round(info.minWidth / dm.density);
            int hDp = Math.round(info.minHeight / dm.density);
            TextView sub = new TextView(this);
            sub.setText(info.provider.getPackageName() + "  \u2022  " + wDp + "\u00D7" + hDp + " dp");
            sub.setTextColor(0xFF94A3B8);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            col.addView(sub);

            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    beginAddWidget(info);
                }
            });
            list.addView(row);
        }

        dlg.show();
    }

    private void tryGrantBindAppWidgetViaSu() {
        final String[] candidates = { "/sbin/su", "/system/xbin/su", "/system/bin/su" };
        for (int i = 0; i < candidates.length; i++) {
            try {
                java.io.File f = new java.io.File(candidates[i]);
                if (!f.exists()) continue;
                Process p = new ProcessBuilder(
                        candidates[i], "-c",
                        "appwidget grantbind --package ca.arc7freedom.launcher --user 0 2>/dev/null || true"
                ).start();
                p.waitFor();
                return;
            } catch (Throwable ignored) {}
        }
    }

    private void beginAddWidget(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) return;
        int appWidgetId = appWidgetHost.allocateAppWidgetId();
        pendingWidgetId = appWidgetId;
        pendingWidgetInfo = info;

        boolean bound = false;
        try {
            bound = appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider);
        } catch (Throwable ignored) {}

        if (!bound) {
            tryGrantBindAppWidgetViaSu();
            try {
                bound = appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider);
            } catch (Throwable ignored) {}
        }

        if (bound) {
            completeConfigureOrFinishWidget(appWidgetId, info);
            return;
        }

        try {
            Intent bindIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
            startActivityForResult(bindIntent, REQ_BIND_APPWIDGET);
        } catch (Throwable t) {
            try { appWidgetHost.deleteAppWidgetId(appWidgetId); } catch (Throwable ignored) {}
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
            pendingWidgetInfo = null;
            Toast.makeText(this, "Could not bind widget: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void completeConfigureOrFinishWidget(int appWidgetId, AppWidgetProviderInfo info) {
        if (info != null && info.configure != null) {
            try {
                Intent cfg = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                cfg.setComponent(info.configure);
                cfg.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                startActivityForResult(cfg, REQ_CONFIGURE_APPWIDGET);
                return;
            } catch (Throwable ignored) {}
        }
        finalizeAddWidget(appWidgetId, info);
    }

    private void finalizeAddWidget(int appWidgetId, AppWidgetProviderInfo info) {
        int hDp = estimateWidgetHeightDp(info);
        PinnedShortcutsStore.addWidget(this, currentPage, appWidgetId, hDp);
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        pendingWidgetInfo = null;
        refreshCurrentPageShortcuts();
        renderWidgetsForCurrentPage();
        Toast.makeText(this, "Widget added to Page " + (currentPage + 1), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BIND_APPWIDGET) {
            int id = pendingWidgetId;
            AppWidgetProviderInfo info = pendingWidgetInfo;
            if (resultCode == RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                if (info == null) info = appWidgetManager.getAppWidgetInfo(id);
                completeConfigureOrFinishWidget(id, info);
            } else {
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    try { appWidgetHost.deleteAppWidgetId(id); } catch (Throwable ignored) {}
                }
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
                pendingWidgetInfo = null;
            }
        } else if (requestCode == REQ_CONFIGURE_APPWIDGET) {
            int id = pendingWidgetId;
            AppWidgetProviderInfo info = pendingWidgetInfo;
            if (resultCode == RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                finalizeAddWidget(id, info);
            } else {
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    try { appWidgetHost.deleteAppWidgetId(id); } catch (Throwable ignored) {}
                }
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
                pendingWidgetInfo = null;
            }
        }
    }

    private void showWidgetManageDialog(final PinnedShortcutsStore.WidgetRecord rec, String title) {
        final String[] items = {
                "Compact Height (96 dp)",
                "Standard Height (132 dp)",
                "Tall Height (185 dp)",
                "Extra-Tall Height (250 dp)",
                "Remove Widget from Home Screen"
        };
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            PinnedShortcutsStore.resizeWidget(VanillaHomeActivity.this, currentPage, rec.appWidgetId, 96);
                        } else if (which == 1) {
                            PinnedShortcutsStore.resizeWidget(VanillaHomeActivity.this, currentPage, rec.appWidgetId, 132);
                        } else if (which == 2) {
                            PinnedShortcutsStore.resizeWidget(VanillaHomeActivity.this, currentPage, rec.appWidgetId, 185);
                        } else if (which == 3) {
                            PinnedShortcutsStore.resizeWidget(VanillaHomeActivity.this, currentPage, rec.appWidgetId, 250);
                        } else {
                            PinnedShortcutsStore.removeWidgetEverywhere(VanillaHomeActivity.this, rec.appWidgetId, MAX_PAGES);
                            try { appWidgetHost.deleteAppWidgetId(rec.appWidgetId); } catch (Throwable ignored) {}
                        }
                        refreshCurrentPageShortcuts();
                        renderWidgetsForCurrentPage();
                    }
                })
                .show();
    }

    private void refreshCurrentPageShortcuts() {
        currentPageShortcuts.clear();
        List<String> saved = PinnedShortcutsStore.loadPage(this, currentPage);

        if (currentPage == 0 && saved.isEmpty() && !allApps.isEmpty()) {
            for (int i = 0; i < allApps.size() && saved.size() < 10; i++) {
                AppEntry e = allApps.get(i);
                if (e.pkg.equals("ca.arc7freedom.app")
                        || e.pkg.equals("com.aurora.store")
                        || e.pkg.equals("eu.bubu1.fdroidclassic.debug")
                        || e.pkg.contains("fdroid")) {
                    PinnedShortcutsStore.addShortcut(this, 0, e.pkg, e.cls);
                    saved.add(e.pkg + "/" + e.cls);
                }
            }
        }

        if (desktopGridView != null) {
            desktopGridView.setNumColumns(PinnedShortcutsStore.getDesktopColumns(this));
        }
        int totalPages = PinnedShortcutsStore.getPageCount(this);
        if (currentPage >= totalPages) currentPage = totalPages - 1;

        for (int i = 0; i < saved.size(); i++) {
            String key = saved.get(i);
            if (PinnedShortcutsStore.isFolderToken(key)) {
                AppEntry fe = new AppEntry();
                fe.folderToken = key;
                fe.pkg = "@folder";
                fe.cls = String.valueOf(i);
                fe.label = "📁 " + PinnedShortcutsStore.parseFolderName(key);
                fe.icon = getResources().getDrawable(R.drawable.ic_files);
                currentPageShortcuts.add(fe);
                continue;
            }
            for (int j = 0; j < allApps.size(); j++) {
                AppEntry e = allApps.get(j);
                if ((e.pkg + "/" + e.cls).equals(key)) {
                    currentPageShortcuts.add(e);
                    break;
                }
            }
        }

        Arc7LauncherSkin.ThemeSpec t = Arc7LauncherSkin.current(this);
        AppBarConfigStore.Config cfg = AppBarConfigStore.load(this);
        int activeDot = cfg.accentOverride != 0 ? cfg.accentOverride : t.accentColor;
        for (int i = 0; i < MAX_PAGES; i++) {
            if (pageDots[i] != null) {
                pageDots[i].setVisibility(i < totalPages ? View.VISIBLE : View.GONE);
                boolean active = (i == currentPage);
                pageDots[i].setTextColor(active ? activeDot : 0x66FFFFFF);
                pageDots[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, active ? 16 : 12);
                if (active) {
                    pageDots[i].animate().scaleX(1.22f).scaleY(1.22f).setDuration(160)
                            .setInterpolator(new OvershootInterpolator()).start();
                } else {
                    pageDots[i].animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                }
            }
        }
        if (desktopAdapter != null) desktopAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDateLabel();
        reloadApps();
        renderWidgetsForCurrentPage();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(clockTickReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (drawerOpen) {
                closeDrawer();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateDateLabel() {
        Date now = new Date();
        boolean use24 = false;
        try {
            use24 = DateFormat.is24HourFormat(this);
        } catch (Throwable ignored) {}
        SimpleDateFormat clockFmt = new SimpleDateFormat(use24 ? "HH:mm" : "h:mm a", Locale.US);
        if (clockView != null) {
            clockView.setText(clockFmt.format(now));
        }
        SimpleDateFormat fmt = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US);
        if (dateView != null) {
            dateView.setText(fmt.format(now).toUpperCase(Locale.US));
        }
    }

    private void openDrawer() {
        drawerOpen = true;
        searchBox.setText("");
        filterApps("");
        drawerOverlay.setVisibility(View.VISIBLE);
        drawerOverlay.setAlpha(0f);
        drawerOverlay.setScaleX(0.94f);
        drawerOverlay.setScaleY(0.94f);
        drawerOverlay.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void closeDrawer() {
        drawerOpen = false;
        if (drawerOverlay != null && drawerOverlay.getVisibility() == View.VISIBLE) {
            drawerOverlay.animate()
                    .alpha(0f).scaleX(0.95f).scaleY(0.95f)
                    .setDuration(150)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!drawerOpen) {
                                drawerOverlay.setVisibility(View.GONE);
                                drawerOverlay.setScaleX(1f);
                                drawerOverlay.setScaleY(1f);
                                drawerOverlay.setAlpha(1f);
                            }
                        }
                    }).start();
        } else if (drawerOverlay != null) {
            drawerOverlay.setVisibility(View.GONE);
        }
    }

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> raw = pm.queryIntentActivities(main, 0);

        allApps.clear();
        String myPkg = getPackageName();
        for (int i = 0; i < raw.size(); i++) {
            ResolveInfo ri = raw.get(i);
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(myPkg)) continue;
            if (pkg.startsWith("com.kobo.") || pkg.startsWith("com.kobobooks.")) continue;

            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.cls = ri.activityInfo.name;
            CharSequence cs = ri.loadLabel(pm);
            e.label = cs != null ? cs.toString() : pkg;
            e.icon = Arc7LauncherSkin.reskinAppIcon(this, pkg, e.cls, ri.loadIcon(pm));
            allApps.add(e);
        }

        Collections.sort(allApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        applyLauncherSkinChrome();
        filterApps(searchBox != null ? searchBox.getText().toString() : "");
        refreshCurrentPageShortcuts();
        rebuildDock();
    }

    private void applyLauncherSkinChrome() {
        Arc7LauncherSkin.ThemeSpec t = Arc7LauncherSkin.current(this);
        AppBarConfigStore.Config cfg = AppBarConfigStore.load(this);
        int accent = cfg.accentOverride != 0 ? cfg.accentOverride : t.accentColor;

        if (topCapsule != null) {
            if (cfg.topBarMode == AppBarConfigStore.TOP_HIDDEN) {
                topCapsule.setVisibility(View.GONE);
            } else {
                topCapsule.setVisibility(View.VISIBLE);
                if (cfg.topBarMode == AppBarConfigStore.TOP_MINIMAL_CLEAR) {
                    topCapsule.setBackgroundDrawable(null);
                    topCapsule.setPadding(dp(4), dp(2), dp(4), dp(4));
                } else {
                    GradientDrawable cap = new GradientDrawable();
                    cap.setColor(t.topCapsuleColor);
                    cap.setCornerRadius(dp(cfg.topBarMode == AppBarConfigStore.TOP_COMPACT ? 12 : 16));
                    cap.setStroke(dp(1), t.borderColor);
                    topCapsule.setBackgroundDrawable(cap);
                    int vPad = dp(cfg.topBarMode == AppBarConfigStore.TOP_COMPACT ? 3 : 6);
                    topCapsule.setPadding(dp(12), vPad, dp(12), vPad);
                }
                if (clockView != null) {
                    clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                            cfg.topBarMode == AppBarConfigStore.TOP_COMPACT ? 21 : 28);
                }
                if (clockCol != null) clockCol.setVisibility(cfg.topShowClock ? View.VISIBLE : View.INVISIBLE);
                if (searchChip != null) searchChip.setVisibility(cfg.topShowSearch ? View.VISIBLE : View.GONE);
                if (appBarChip != null) {
                    appBarChip.setVisibility(cfg.topShowAppBarBtn ? View.VISIBLE : View.GONE);
                    GradientDrawable abg = new GradientDrawable();
                    abg.setColor(0xBB10243A);
                    abg.setCornerRadius(dp(14));
                    abg.setStroke(dp(1), accent);
                    appBarChip.setBackgroundDrawable(abg);
                }
                if (skinChip != null) skinChip.setVisibility(cfg.topShowSkinBtn ? View.VISIBLE : View.GONE);
                if (wallpaperChip != null) wallpaperChip.setVisibility(cfg.topShowWallpaperBtn ? View.VISIBLE : View.GONE);
            }
        }
        if (drawerOverlay != null) {
            drawerOverlay.setBackgroundColor(t.drawerBgColor);
        }
        if (drawerTitleView != null) {
            drawerTitleView.setTextColor(accent);
        }
        if (drawerDividerView != null) {
            drawerDividerView.setBackgroundColor(accent);
        }
        int totalPages = PinnedShortcutsStore.getPageCount(this);
        for (int i = 0; i < MAX_PAGES; i++) {
            if (pageDots[i] != null) {
                pageDots[i].setVisibility(i < totalPages ? View.VISIBLE : View.GONE);
                pageDots[i].setTextColor(i == currentPage ? accent : 0x66FFFFFF);
            }
        }
    }

    private void showLauncherSkinDialog() {
        final String cur = Arc7LauncherSkin.currentSkinId(this);
        String[] names = new String[Arc7LauncherSkin.THEMES.length];
        int checked = 0;
        for (int i = 0; i < Arc7LauncherSkin.THEMES.length; i++) {
            names[i] = Arc7LauncherSkin.THEMES[i].title;
            if (Arc7LauncherSkin.THEMES[i].id.equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Arc7 Custom OS Skin")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Arc7LauncherSkin.ThemeSpec t = Arc7LauncherSkin.THEMES[which];
                        Arc7LauncherSkin.setSkin(VanillaHomeActivity.this, t.id);
                        sendBroadcast(new Intent(Arc7LauncherSkin.ACTION_SKIN_CHANGED));
                        applyLauncherSkinChrome();
                        reloadApps();
                        Toast.makeText(VanillaHomeActivity.this,
                                "Applied " + t.title + " across Home, App Bar & Apps!", Toast.LENGTH_SHORT).show();
                        d.dismiss();
                    }
                })
                .setNeutralButton("Customize App Bar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showAppBarCustomizerDialog();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void filterApps(String query) {
        filteredApps.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (int i = 0; i < allApps.size(); i++) {
            AppEntry e = allApps.get(i);
            if (q.isEmpty() || e.label.toLowerCase(Locale.US).contains(q)
                    || e.pkg.toLowerCase(Locale.US).contains(q)) {
                filteredApps.add(e);
            }
        }
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    /* ==================== Customizable Multi-Edge App Bar Engine ==================== */

    private ResolvedBarSlot resolveBarToken(final String token) {
        if (token == null) return null;
        if (PinnedShortcutsStore.isFolderToken(token)) {
            final String fname = PinnedShortcutsStore.parseFolderName(token);
            return makeBuiltinSlot(token, fname, R.drawable.ic_files, new View.OnClickListener() {
                @Override public void onClick(View v) { showFolderPopupDialog(token); }
            });
        }
        if ("@pdf".equals(token)) {
            return makeBuiltinSlot(token, "PDF Scan", R.drawable.ic_pdf, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7PdfViewerActivity")) openDrawer();
                }
            });
        }
        if ("@weather".equals(token)) {
            return makeBuiltinSlot(token, "Weather", R.drawable.ic_weather, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7WeatherActivity")) openDrawer();
                }
            });
        }
        if ("@radio".equals(token)) {
            return makeBuiltinSlot(token, "Radio/RSS", R.drawable.ic_radio, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7RadioRssActivity")) openDrawer();
                }
            });
        }
        if ("@recorder".equals(token)) {
            return makeBuiltinSlot(token, "Recorder", R.drawable.ic_recorder, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7RecorderActivity")) openDrawer();
                }
            });
        }
        if ("@arcade".equals(token)) {
            return makeBuiltinSlot(token, "Arcade", R.drawable.ic_arcade, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7ArcadeActivity")) openDrawer();
                }
            });
        }
        if ("@shield".equals(token)) {
            return makeBuiltinSlot(token, "Shield", R.drawable.ic_shield, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7ShieldActivity")) openDrawer();
                }
            });
        }
        if ("@boost".equals(token)) {
            return makeBuiltinSlot(token, "Boost", R.drawable.ic_boost, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7BoostActivity")) openDrawer();
                }
            });
        }
        if ("@secondscreen".equals(token)) {
            return makeBuiltinSlot(token, "2nd Screen", R.drawable.ic_display, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7SecondScreenActivity")) openDrawer();
                }
            });
        }
        if ("@splitview".equals(token)) {
            return makeBuiltinSlot(token, "Split View", R.drawable.ic_splitview, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7SplitViewActivity")) openDrawer();
                }
            });
        }
        if ("@wiki".equals(token)) {
            return makeBuiltinSlot(token, "Dict/Wiki", R.drawable.ic_wiki, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7DictionaryWikiActivity")) openDrawer();
                }
            });
        }
        if ("@browser".equals(token)) {
            return makeBuiltinSlot(token, "Browser", R.drawable.ic_browser, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7BrowserActivity")
                            && !launchByPackagePrefix("org.mozilla", "com.opera", "acr.browser", "com.android.browser", "com.android.chrome")) {
                        safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://html.duckduckgo.com/html/")));
                    }
                }
            });
        }
        if ("@calendar".equals(token)) {
            return makeBuiltinSlot(token, "Calendar", R.drawable.ic_calendar, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7CalendarActivity")) openDrawer();
                }
            });
        }
        if ("@photos".equals(token)) {
            return makeBuiltinSlot(token, "Photos", R.drawable.ic_photos, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7PhotosActivity")) openDrawer();
                }
            });
        }
        if ("@camera".equals(token)) {
            return makeBuiltinSlot(token, "Camera", R.drawable.ic_camera, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7CameraActivity")) openDrawer();
                }
            });
        }
        if ("@vlc".equals(token)) {
            return makeBuiltinSlot(token, "VLC", R.drawable.ic_vlc, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchByPackagePrefix("org.videolan.vlc")) {
                        launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7AudioPlayerActivity");
                    }
                }
            });
        }
        if ("@settings".equals(token)) {
            return makeBuiltinSlot(token, "Settings", R.drawable.ic_sys_settings, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    safeStart(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }
        if ("@files".equals(token)) {
            return makeBuiltinSlot(token, "Files", R.drawable.ic_files, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7FileManagerActivity")) openDrawer();
                }
            });
        }
        if ("@notes".equals(token)) {
            return makeBuiltinSlot(token, "Notes", R.drawable.ic_notes, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7NotesActivity")) openDrawer();
                }
            });
        }
        if ("@calculator".equals(token)) {
            return makeBuiltinSlot(token, "Calc", R.drawable.ic_calculator, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7CalculatorActivity")) openDrawer();
                }
            });
        }
        if ("@clock".equals(token)) {
            return makeBuiltinSlot(token, "Clock", R.drawable.ic_clock, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7ClockActivity")) openDrawer();
                }
            });
        }
        if ("@ereader".equals(token)) {
            return makeBuiltinSlot(token, "E-Reader", R.drawable.ic_ereader, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7EbookReaderActivity")) openDrawer();
                }
            });
        }
        if ("@audio".equals(token)) {
            return makeBuiltinSlot(token, "Music", R.drawable.ic_audio, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7AudioPlayerActivity")) openDrawer();
                }
            });
        }
        if ("@terminal".equals(token)) {
            return makeBuiltinSlot(token, "Terminal", R.drawable.ic_terminal, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7TerminalActivity")) openDrawer();
                }
            });
        }
        if ("@wired".equals(token)) {
            return makeBuiltinSlot(token, "USB Net", R.drawable.ic_wired_net, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7WiredInternetActivity")) openDrawer();
                }
            });
        }
        if ("@freedom".equals(token)) {
            return makeBuiltinSlot(token, "Freedom", R.drawable.ic_launcher, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.MainActivity");
                }
            });
        }

        // Arbitrary installed app component (`pkg/cls`)
        int slash = token.indexOf('/');
        if (slash > 0) {
            final String pkg = token.substring(0, slash);
            final String cls = token.substring(slash + 1);
            for (int i = 0; i < allApps.size(); i++) {
                final AppEntry e = allApps.get(i);
                if (pkg.equals(e.pkg) && cls.equals(e.cls)) {
                    ResolvedBarSlot s = new ResolvedBarSlot();
                    s.token = token;
                    s.label = e.label;
                    s.icon = e.icon;
                    s.action = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            launchEntry(e);
                        }
                    };
                    return s;
                }
            }
            // Fallback resolve straight from PackageManager even before `allApps` scan finishes
            try {
                PackageManager pm = getPackageManager();
                ComponentName cn = new ComponentName(pkg, cls);
                android.content.pm.ActivityInfo ai = pm.getActivityInfo(cn, 0);
                ResolvedBarSlot s = new ResolvedBarSlot();
                s.token = token;
                CharSequence cs = ai.loadLabel(pm);
                s.label = cs != null ? cs.toString() : pkg;
                s.icon = Arc7LauncherSkin.reskinAppIcon(this, pkg, cls, ai.loadIcon(pm));
                s.action = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchExplicitOrPackage(pkg, cls);
                    }
                };
                return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private ResolvedBarSlot makeBuiltinSlot(String token, String label, int resId, View.OnClickListener listener) {
        ResolvedBarSlot s = new ResolvedBarSlot();
        s.token = token;
        s.label = label;
        s.icon = getResources().getDrawable(resId);
        s.action = listener;
        return s;
    }

    private GradientDrawable buildAppBarBackground(Arc7LauncherSkin.ThemeSpec t, AppBarConfigStore.Config cfg) {
        int accent = cfg.accentOverride != 0 ? cfg.accentOverride : t.accentColor;
        int topCol = AppBarConfigStore.applyOpacityOverride(t.dockTopColor, cfg.opacityPct);
        int botCol = AppBarConfigStore.applyOpacityOverride(t.dockBottomColor, cfg.opacityPct);

        if (cfg.style == AppBarConfigStore.STYLE_MINIMAL_CLEAR) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x00000000);
            return g;
        }
        if (cfg.style == AppBarConfigStore.STYLE_SOLID_CHASSIS) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(AppBarConfigStore.applyOpacityOverride(t.dockBottomColor | 0xFF000000,
                    cfg.opacityPct < 0 ? 96 : cfg.opacityPct));
            g.setCornerRadius(cfg.style == AppBarConfigStore.STYLE_FULL_BLEED ? 0 : dp(16));
            g.setStroke(dp(1), t.borderColor | 0xAA000000);
            return g;
        }
        if (cfg.style == AppBarConfigStore.STYLE_NEON_ACCENT) {
            int tintedTop = ((accent & 0x00FFFFFF) | 0x44000000);
            GradientDrawable g = new GradientDrawable(
                    cfg.isVertical() ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{AppBarConfigStore.applyOpacityOverride(tintedTop, cfg.opacityPct), botCol});
            g.setCornerRadius(dp(18));
            g.setStroke(dp(2), accent);
            return g;
        }
        GradientDrawable g = new GradientDrawable(
                cfg.isVertical() ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topCol, botCol});
        g.setCornerRadius(cfg.style == AppBarConfigStore.STYLE_FULL_BLEED ? 0 : dp(18));
        g.setStroke(dp(1), cfg.accentOverride != 0 ? ((accent & 0x00FFFFFF) | 0x99000000) : t.borderColor);
        return g;
    }

    private void rebuildDock() {
        if (dockBar == null) return;
        leftRailSlot.removeAllViews();
        rightRailSlot.removeAllViews();
        topBarSlot.removeAllViews();
        bottomBarSlot.removeAllViews();
        leftRailSlot.setVisibility(View.GONE);
        rightRailSlot.setVisibility(View.GONE);
        topBarSlot.setVisibility(View.GONE);
        bottomBarSlot.setVisibility(View.GONE);

        if (dockBar.getParent() instanceof ViewGroup) {
            ((ViewGroup) dockBar.getParent()).removeView(dockBar);
        }
        dockBar.removeAllViews();

        final Arc7LauncherSkin.ThemeSpec t = Arc7LauncherSkin.current(this);
        final AppBarConfigStore.Config cfg = AppBarConfigStore.load(this);

        if (cfg.position == AppBarConfigStore.POS_HIDDEN) {
            return;
        }

        final boolean vertical = cfg.isVertical();
        dockBar.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dockBar.setGravity(Gravity.CENTER);
        dockBar.setBackgroundDrawable(null);

        List<ResolvedBarSlot> slots = new ArrayList<ResolvedBarSlot>();
        List<Integer> configIndices = new ArrayList<Integer>();
        for (int i = 0; i < cfg.items.size(); i++) {
            ResolvedBarSlot rs = resolveBarToken(cfg.items.get(i));
            if (rs != null) {
                slots.add(rs);
                configIndices.add(Integer.valueOf(i));
            }
        }

        boolean needsScroll = vertical ? (slots.size() > 5) : (slots.size() > 6);
        int iconDp = cfg.iconSizeDp();
        int accent = cfg.accentOverride != 0 ? cfg.accentOverride : t.accentColor;

        int drawerInsertIdx = -1;
        if (cfg.drawerBtnPos == AppBarConfigStore.DRAWER_START) {
            drawerInsertIdx = 0;
        } else if (cfg.drawerBtnPos == AppBarConfigStore.DRAWER_END) {
            drawerInsertIdx = slots.size();
        } else if (cfg.drawerBtnPos == AppBarConfigStore.DRAWER_CENTER) {
            drawerInsertIdx = slots.size() / 2;
        }

        for (int i = 0; i <= slots.size(); i++) {
            if (i == drawerInsertIdx) {
                addAllAppsJewel(accent, iconDp, vertical, needsScroll);
            }
            if (i < slots.size()) {
                final ResolvedBarSlot s = slots.get(i);
                final int slotConfigIdx = configIndices.get(i).intValue();
                addCustomizableBarSlot(s, slotConfigIdx, cfg.items.size(), iconDp, cfg.showLabels, vertical, needsScroll);
            }
        }

        // Outer chrome wrapper so scrollable bars keep their rounded pill frame cleanly
        FrameLayout chromeShell = new FrameLayout(this);
        chromeShell.setBackgroundDrawable(buildAppBarBackground(t, cfg));
        int hPad = dp(cfg.style == AppBarConfigStore.STYLE_FULL_BLEED ? 10 : 8);
        int vPad = dp(vertical ? 10 : 6);
        chromeShell.setPadding(vertical ? dp(6) : hPad, vPad, vertical ? dp(6) : hPad, vPad);
        chromeShell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showAppBarCustomizerDialog();
                return true;
            }
        });

        if (needsScroll) {
            if (vertical) {
                ScrollView sv = new ScrollView(this);
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(dockBar, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                chromeShell.addView(sv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                HorizontalScrollView hsv = new HorizontalScrollView(this);
                hsv.setHorizontalScrollBarEnabled(false);
                hsv.addView(dockBar, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                chromeShell.addView(hsv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        } else {
            chromeShell.addView(dockBar, new FrameLayout.LayoutParams(
                    vertical ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                    vertical ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        boolean fullBleed = (cfg.style == AppBarConfigStore.STYLE_FULL_BLEED);
        desktopView.setPadding(
                fullBleed ? dp(8) : dp(14),
                dp(8),
                fullBleed ? dp(8) : dp(14),
                fullBleed && cfg.position == AppBarConfigStore.POS_BOTTOM ? 0 : dp(8));

        FrameLayout.LayoutParams hostLp;
        if (cfg.position == AppBarConfigStore.POS_LEFT) {
            leftRailSlot.setVisibility(View.VISIBLE);
            hostLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (!fullBleed) {
                hostLp.setMargins(dp(8), dp(8), 0, dp(8));
            }
            leftRailSlot.addView(chromeShell, hostLp);
        } else if (cfg.position == AppBarConfigStore.POS_RIGHT) {
            rightRailSlot.setVisibility(View.VISIBLE);
            hostLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (!fullBleed) {
                hostLp.setMargins(0, dp(8), dp(8), dp(8));
            }
            rightRailSlot.addView(chromeShell, hostLp);
        } else if (cfg.position == AppBarConfigStore.POS_TOP) {
            topBarSlot.setVisibility(View.VISIBLE);
            hostLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hostLp.bottomMargin = dp(8);
            topBarSlot.addView(chromeShell, hostLp);
        } else {
            bottomBarSlot.setVisibility(View.VISIBLE);
            hostLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bottomBarSlot.addView(chromeShell, hostLp);
        }
    }

    private void addAllAppsJewel(int accentColor, int iconDp, boolean vertical, boolean scrollable) {
        TextView allAppsBtn = new TextView(this);
        allAppsBtn.setText(":::");
        allAppsBtn.setTextColor(Color.WHITE);
        allAppsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconDp <= 36 ? 15 : 19);
        allAppsBtn.setTypeface(Typeface.DEFAULT_BOLD);
        allAppsBtn.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(accentColor);
        circle.setStroke(dp(2), Color.WHITE);
        allAppsBtn.setBackgroundDrawable(circle);
        int jewelPx = dp(Math.max(40, iconDp + 6));
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(jewelPx, jewelPx);
        if (vertical) {
            centerParams.topMargin = dp(8);
            centerParams.bottomMargin = dp(8);
        } else {
            centerParams.leftMargin = dp(8);
            centerParams.rightMargin = dp(8);
        }
        allAppsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        });
        allAppsBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showAppBarCustomizerDialog();
                return true;
            }
        });
        dockBar.addView(allAppsBtn, centerParams);
    }

    private void addCustomizableBarSlot(final ResolvedBarSlot s, final int slotConfigIdx, final int totalSlots,
                                        int iconDp, boolean showLabels, boolean vertical, boolean scrollable) {
        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.VERTICAL);
        slot.setGravity(Gravity.CENTER);
        int cellPad = dp(vertical ? 6 : 4);
        slot.setPadding(cellPad, dp(3), cellPad, dp(3));

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(s.icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        slot.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        if (showLabels) {
            TextView tv = new TextView(this);
            tv.setText(s.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconDp <= 36 ? 10f : 11f);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setMaxWidth(dp(Math.max(62, iconDp + 24)));
            tv.setGravity(Gravity.CENTER);
            tv.setShadowLayer(3f, 0f, 1f, 0xCC000000);
            slot.addView(tv);
        }

        slot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(75).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                            .setInterpolator(new OvershootInterpolator(1.7f)).start();
                }
                return false;
            }
        });
        slot.setOnClickListener(s.action);
        slot.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lastChildLongPressMs = System.currentTimeMillis();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showBarSlotContextMenu(s, slotConfigIdx, totalSlots);
                return true;
            }
        });

        LinearLayout.LayoutParams lp;
        if (scrollable) {
            lp = new LinearLayout.LayoutParams(
                    vertical ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(Math.max(68, iconDp + 28)),
                    vertical ? dp(Math.max(64, iconDp + (showLabels ? 26 : 14))) : ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (vertical) {
            lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
        } else {
            lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        }
        dockBar.addView(slot, lp);
    }

    private void showBarSlotContextMenu(final ResolvedBarSlot s, final int slotIdx, final int totalSlots) {
        final List<String> actions = new ArrayList<String>();
        actions.add("Open " + s.label);
        if (slotIdx > 0) actions.add("\u25C0 Move Earlier in App Bar");
        if (slotIdx < totalSlots - 1) actions.add("Move Later in App Bar \u25B6");
        actions.add("\u21C4 Replace This Slot with Another App...");
        if (totalSlots < AppBarConfigStore.MAX_SLOTS) actions.add("\uFF0B Add Another App to App Bar...");
        actions.add("\u2715 Remove " + s.label + " from App Bar");
        actions.add("\u2699 Open Full App Bar Studio...");

        new AlertDialog.Builder(this)
                .setTitle("App Bar Slot #" + (slotIdx + 1) + "  \u2022  " + s.label)
                .setItems(actions.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String pick = actions.get(which);
                        if (pick.startsWith("Open ")) {
                            s.action.onClick(dockBar);
                        } else if (pick.startsWith("\u25C0")) {
                            AppBarConfigStore.moveSlot(VanillaHomeActivity.this, slotIdx, -1);
                            rebuildDock();
                        } else if (pick.endsWith("\u25B6")) {
                            AppBarConfigStore.moveSlot(VanillaHomeActivity.this, slotIdx, 1);
                            rebuildDock();
                        } else if (pick.startsWith("\u21C4")) {
                            showAppPickerForBar(slotIdx, null);
                        } else if (pick.startsWith("\uFF0B")) {
                            showAppPickerForBar(-1, null);
                        } else if (pick.startsWith("\u2715")) {
                            AppBarConfigStore.Config cfg = AppBarConfigStore.load(VanillaHomeActivity.this);
                            if (slotIdx >= 0 && slotIdx < cfg.items.size()) {
                                cfg.items.remove(slotIdx);
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                            }
                        } else {
                            showAppBarCustomizerDialog();
                        }
                    }
                })
                .show();
    }

    private void showAppPickerForBar(final int replaceSlotIdx, final Runnable afterPick) {
        final List<String> labels = new ArrayList<String>();
        final List<String> tokens = new ArrayList<String>();

        String[][] smartBuiltins = new String[][]{
                { "@browser", "Arc7 Browser (Modern TLS 1.3 Web)" },
                { "@calendar", "Arc7 Calendar & Agenda" },
                { "@photos", "Arc7 Photos & Filter Studio" },
                { "@camera", "Arc7 HD Camera & Screen Flash" },
                { "@vlc", "VLC Media Player" },
                { "@files", "Arc7 Files & ZIP Explorer" },
                { "@notes", "Arc7 Notes, Checklists & Sketchpad" },
                { "@calculator", "Arc7 Scientific Calculator & Converter" },
                { "@clock", "Arc7 Clock, Alarms & Chimes" },
                { "@ereader", "Arc7 E-Book Reader (VK / FB2 / EPUB / PDF / Gutenberg)" },
                { "@pdf", "Arc7 PDF Viewer & Document Scanner" },
                { "@weather", "Arc7 Weather (Open-Meteo Forecast)" },
                { "@radio", "Arc7 Radio, Podcasts & Offline RSS" },
                { "@recorder", "Arc7 Studio Voice Recorder" },
                { "@arcade", "Arc7 Offline Arcade (Chess / 2048 / Solitaire)" },
                { "@wiki", "Arc7 Offline Dictionary & Wikipedia" },
                { "@splitview", "Arc7 Dual-Pane Split View" },
                { "@secondscreen", "Arc7 Chromebook / PC Second Screen" },
                { "@shield", "Arc7 Shield (System AdBlock & Firewall)" },
                { "@boost", "Arc7 Boost (+384 MB Swap & CPU Profiles)" },
                { "@audio", "Arc7 Music & Audio Player" },
                { "@wired", "Chromebook Wired USB Internet" },
                { "@terminal", "Root Shell Terminal (Python 3 + apt)" },
                { "@freedom", "Kobo Arc 7 Freedom Control Center" },
                { "@settings", "Android System Settings" }
        };
        for (int i = 0; i < smartBuiltins.length; i++) {
            tokens.add(smartBuiltins[i][0]);
            labels.add("\u2605 " + smartBuiltins[i][1]);
        }
        for (int i = 0; i < allApps.size(); i++) {
            AppEntry e = allApps.get(i);
            tokens.add(e.pkg + "/" + e.cls);
            labels.add(e.label + "  (" + e.pkg + ")");
        }

        new AlertDialog.Builder(this)
                .setTitle(replaceSlotIdx >= 0
                        ? "Replace App Bar Slot #" + (replaceSlotIdx + 1)
                        : "Add App to App Bar")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AppBarConfigStore.Config cfg = AppBarConfigStore.load(VanillaHomeActivity.this);
                        String chosen = tokens.get(which);
                        if (replaceSlotIdx >= 0 && replaceSlotIdx < cfg.items.size()) {
                            cfg.items.set(replaceSlotIdx, chosen);
                        } else if (!cfg.items.contains(chosen) && cfg.items.size() < AppBarConfigStore.MAX_SLOTS) {
                            cfg.items.add(chosen);
                        } else if (cfg.items.size() >= AppBarConfigStore.MAX_SLOTS) {
                            Toast.makeText(VanillaHomeActivity.this,
                                    "Maximum " + AppBarConfigStore.MAX_SLOTS + " App Bar slots reached.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                        rebuildDock();
                        if (afterPick != null) afterPick.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ==================== Full Visual App Bar Studio Dialog ==================== */

    public void showAppBarCustomizerDialog() {
        final Arc7LauncherSkin.ThemeSpec skin = Arc7LauncherSkin.current(this);
        ScrollView sv = new ScrollView(this);
        final LinearLayout rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setBackgroundColor(0xFF0E1520);
        rootBox.setPadding(dp(14), dp(12), dp(14), dp(14));
        sv.addView(rootBox);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Customize App Bar (Home & In-App)")
                .setView(sv)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyLauncherSkinChrome();
                        rebuildDock();
                    }
                })
                .setNeutralButton("Reset Defaults", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AppBarConfigStore.resetDefaults(VanillaHomeActivity.this);
                        applyLauncherSkinChrome();
                        rebuildDock();
                        Toast.makeText(VanillaHomeActivity.this,
                                "Restored default App Bar layout & appearance.", Toast.LENGTH_SHORT).show();
                    }
                })
                .create();

        final Runnable[] renderRef = new Runnable[1];
        renderRef[0] = new Runnable() {
            @Override
            public void run() {
                rootBox.removeAllViews();
                final AppBarConfigStore.Config cfg = AppBarConfigStore.load(VanillaHomeActivity.this);
                final int accent = cfg.accentOverride != 0 ? cfg.accentOverride : skin.accentColor;

                // Section 1: Pinned App Bar Slots (live reorder / replace / remove / add)
                addStudioHeader(rootBox, "1. PINNED APP BAR SLOTS (" + cfg.items.size() + " / " + AppBarConfigStore.MAX_SLOTS + ")", accent);

                for (int i = 0; i < cfg.items.size(); i++) {
                    final int idx = i;
                    ResolvedBarSlot slot = resolveBarToken(cfg.items.get(i));
                    final String title = slot != null ? slot.label : cfg.items.get(i);

                    LinearLayout row = new LinearLayout(VanillaHomeActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(6), dp(8), dp(6));
                    GradientDrawable rbg = new GradientDrawable();
                    rbg.setColor(0xFF172233);
                    rbg.setCornerRadius(dp(9));
                    rbg.setStroke(dp(1), 0xFF283A52);
                    row.setBackgroundDrawable(rbg);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = dp(6);
                    row.setLayoutParams(rlp);

                    if (slot != null && slot.icon != null) {
                        ImageView iv = new ImageView(VanillaHomeActivity.this);
                        iv.setImageDrawable(slot.icon);
                        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
                        ilp.rightMargin = dp(9);
                        row.addView(iv, ilp);
                    }

                    TextView tv = new TextView(VanillaHomeActivity.this);
                    tv.setText((idx + 1) + ".  " + title);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    row.addView(makeMiniStudioBtn("\u25B2", idx > 0 ? 0xFF243449 : 0x44243449, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (idx > 0) {
                                AppBarConfigStore.moveSlot(VanillaHomeActivity.this, idx, -1);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        }
                    }));
                    row.addView(makeMiniStudioBtn("\u25BC", idx < cfg.items.size() - 1 ? 0xFF243449 : 0x44243449, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (idx < cfg.items.size() - 1) {
                                AppBarConfigStore.moveSlot(VanillaHomeActivity.this, idx, 1);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        }
                    }));
                    row.addView(makeMiniStudioBtn("\u21C4", 0xFF1D4ED8, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showAppPickerForBar(idx, renderRef[0]);
                        }
                    }));
                    row.addView(makeMiniStudioBtn("\u2715", 0xFFB91C1C, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            cfg.items.remove(idx);
                            AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                            rebuildDock();
                            renderRef[0].run();
                        }
                    }));
                    rootBox.addView(row);
                }

                if (cfg.items.size() < AppBarConfigStore.MAX_SLOTS) {
                    TextView addAppBtn = makeTopChip("\uFF0B  Add Installed or Built-In App to App Bar", 0xFF0284C7);
                    addAppBtn.setGravity(Gravity.CENTER);
                    addAppBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    alp.topMargin = dp(2);
                    alp.bottomMargin = dp(10);
                    addAppBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showAppPickerForBar(-1, renderRef[0]);
                        }
                    });
                    rootBox.addView(addAppBtn, alp);
                }

                // Section 2: Home App Bar Position, Style & Sizing
                addStudioHeader(rootBox, "2. HOME APP BAR POSITION, STYLE & SIZING", accent);

                addSelectorRow(rootBox, "Screen Position", AppBarConfigStore.POSITION_NAMES[cfg.position],
                        AppBarConfigStore.POSITION_NAMES, cfg.position, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.position = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        });

                addSelectorRow(rootBox, "App Bar Style", AppBarConfigStore.STYLE_NAMES[cfg.style],
                        AppBarConfigStore.STYLE_NAMES, cfg.style, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.style = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        });

                addSelectorRow(rootBox, "All-Apps (:::) Button", AppBarConfigStore.DRAWER_BTN_NAMES[cfg.drawerBtnPos],
                        AppBarConfigStore.DRAWER_BTN_NAMES, cfg.drawerBtnPos, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.drawerBtnPos = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        });

                addSelectorRow(rootBox, "Icon Size", AppBarConfigStore.ICON_SIZE_NAMES[cfg.iconSizeMode],
                        AppBarConfigStore.ICON_SIZE_NAMES, cfg.iconSizeMode, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.iconSizeMode = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        });

                int opIdx = 0;
                for (int i = 0; i < AppBarConfigStore.OPACITY_PRESETS.length; i++) {
                    if (AppBarConfigStore.OPACITY_PRESETS[i] == cfg.opacityPct) opIdx = i;
                }
                addSelectorRow(rootBox, "Glass Opacity", AppBarConfigStore.OPACITY_NAMES[opIdx],
                        AppBarConfigStore.OPACITY_NAMES, opIdx, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.opacityPct = AppBarConfigStore.OPACITY_PRESETS[which];
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                                renderRef[0].run();
                            }
                        });

                int accIdx = 0;
                for (int i = 0; i < AppBarConfigStore.ACCENT_SWATCHES.length; i++) {
                    if (AppBarConfigStore.ACCENT_SWATCHES[i] == cfg.accentOverride) accIdx = i;
                }
                addSelectorRow(rootBox, "Accent Highlight", AppBarConfigStore.ACCENT_SWATCH_NAMES[accIdx],
                        AppBarConfigStore.ACCENT_SWATCH_NAMES, accIdx, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.accentOverride = AppBarConfigStore.ACCENT_SWATCHES[which];
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                                rebuildDock();
                                renderRef[0].run();
                            }
                        });

                addToggleRow(rootBox, "Show App Text Labels in App Bar", cfg.showLabels,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.showLabels = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                            }
                        });

                // Section 3: Home Top Status / Action Bar
                addStudioHeader(rootBox, "3. HOME TOP STATUS & ACTION BAR", accent);

                addSelectorRow(rootBox, "Top Bar Mode", AppBarConfigStore.TOP_BAR_NAMES[cfg.topBarMode],
                        AppBarConfigStore.TOP_BAR_NAMES, cfg.topBarMode, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.topBarMode = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                                renderRef[0].run();
                            }
                        });

                addToggleRow(rootBox, "Show Clock & Date on Top Bar", cfg.topShowClock,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.topShowClock = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                            }
                        });
                addToggleRow(rootBox, "Show Quick Search Button on Top Bar", cfg.topShowSearch,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.topShowSearch = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                            }
                        });
                addToggleRow(rootBox, "Show 'App Bar' Button on Top Bar", cfg.topShowAppBarBtn,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.topShowAppBarBtn = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                            }
                        });
                addToggleRow(rootBox, "Show 'Skin' Button on Top Bar", cfg.topShowSkinBtn,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.topShowSkinBtn = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                            }
                        });
                addToggleRow(rootBox, "Show 'Wallpaper' Button on Top Bar", cfg.topShowWallpaperBtn,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.topShowWallpaperBtn = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                applyLauncherSkinChrome();
                            }
                        });

                // Section 4: System-Wide In-App Top App Bar (synced across all built-in Arc7 apps!)
                addStudioHeader(rootBox, "4. IN-APP TOP APP BAR (ALL ARC7 APPS)", accent);

                addSelectorRow(rootBox, "In-App Bar Height", AppBarConfigStore.IN_APP_DENSITY_NAMES[cfg.inAppDensity],
                        AppBarConfigStore.IN_APP_DENSITY_NAMES, cfg.inAppDensity, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.inAppDensity = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                renderRef[0].run();
                            }
                        });

                addSelectorRow(rootBox, "In-App Header Style", AppBarConfigStore.IN_APP_STYLE_NAMES[cfg.inAppStyle],
                        AppBarConfigStore.IN_APP_STYLE_NAMES, cfg.inAppStyle, new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                cfg.inAppStyle = which;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                renderRef[0].run();
                            }
                        });

                addToggleRow(rootBox, "Center App Title in Built-In Apps", cfg.inAppCenterTitle,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.inAppCenterTitle = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                            }
                        });

                addToggleRow(rootBox, "Show Quick 'Apps' Switcher Pill Inside Apps", cfg.inAppShowQuickSwitch,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                cfg.inAppShowQuickSwitch = checked;
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                            }
                        });

                // Section 5: Multi-Page Desktops, Grid Density, Folders, PIN Lock & Layout Backup (v52)
                addStudioHeader(rootBox, "5. MULTI-PAGE DESKTOP, FOLDERS, PIN LOCK & BACKUP (v52)", accent);

                final String[] colOpts = new String[]{
                        "4 Columns (Large Tablet Icons)",
                        "5 Columns (Balanced Default)",
                        "6 Columns (Wide Landscape Grid)",
                        "7 Columns (Ultra-Dense Pro Grid)"
                };
                int curColIdx = PinnedShortcutsStore.getDesktopColumns(VanillaHomeActivity.this) - 4;
                addSelectorRow(rootBox, "Desktop Grid Density", colOpts[curColIdx], colOpts, curColIdx,
                        new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                PinnedShortcutsStore.setDesktopColumns(VanillaHomeActivity.this, 4 + which);
                                refreshCurrentPageShortcuts();
                                renderRef[0].run();
                            }
                        });

                final String[] pageOpts = new String[]{
                        "3 Home Desktop Pages",
                        "4 Home Desktop Pages",
                        "5 Home Desktop Pages"
                };
                int curPageIdx = PinnedShortcutsStore.getPageCount(VanillaHomeActivity.this) - 3;
                addSelectorRow(rootBox, "Desktop Page Count", pageOpts[curPageIdx], pageOpts, curPageIdx,
                        new IntChoiceCallback() {
                            @Override
                            public void onSelected(int which) {
                                PinnedShortcutsStore.setPageCount(VanillaHomeActivity.this, 3 + which);
                                refreshCurrentPageShortcuts();
                                renderRef[0].run();
                            }
                        });

                TextView mkFolderBtn = makeTopChip("📁 + Create App Folder (Desktop / App Bar)", 0xFF0284C7);
                mkFolderBtn.setGravity(Gravity.CENTER);
                mkFolderBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
                mkFolderBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showCreateFolderDialog(); }
                });
                LinearLayout.LayoutParams fbtnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fbtnLp.bottomMargin = dp(6);
                rootBox.addView(mkFolderBtn, fbtnLp);

                TextView pinBtn = makeTopChip("🔒 Set 4-Digit App Lock PIN ("
                        + (PinnedShortcutsStore.getPinCode(VanillaHomeActivity.this).isEmpty() ? "Not Set" : "Active") + ")", 0xFF7C3AED);
                pinBtn.setGravity(Gravity.CENTER);
                pinBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
                pinBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showSetPinDialog(renderRef[0]); }
                });
                LinearLayout.LayoutParams pbtnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                pbtnLp.bottomMargin = dp(6);
                rootBox.addView(pinBtn, pbtnLp);

                LinearLayout bkRow = new LinearLayout(VanillaHomeActivity.this);
                bkRow.setOrientation(LinearLayout.HORIZONTAL);
                TextView expBtn = makeTopChip("💾 Backup Layout JSON", 0xFF059669);
                expBtn.setGravity(Gravity.CENTER);
                expBtn.setPadding(dp(10), dp(9), dp(10), dp(9));
                expBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            java.io.File out = PinnedShortcutsStore.exportLayoutBackupJson(VanillaHomeActivity.this);
                            Toast.makeText(VanillaHomeActivity.this, "Saved: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Toast.makeText(VanillaHomeActivity.this, "Backup error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
                bkRow.addView(expBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView impBtn = makeTopChip("📂 Restore Layout JSON", 0xFF334155);
                impBtn.setGravity(Gravity.CENTER);
                impBtn.setPadding(dp(10), dp(9), dp(10), dp(9));
                impBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            PinnedShortcutsStore.importLayoutBackupJson(VanillaHomeActivity.this);
                            refreshCurrentPageShortcuts();
                            rebuildDock();
                            renderRef[0].run();
                            Toast.makeText(VanillaHomeActivity.this, "Restored Home & App Bar layout!", Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Toast.makeText(VanillaHomeActivity.this, "No backup JSON found yet.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ilp.leftMargin = dp(8);
                bkRow.addView(impBtn, ilp);
                rootBox.addView(bkRow);
            }
        };

        renderRef[0].run();
        dlg.show();
    }

    private interface IntChoiceCallback {
        void onSelected(int which);
    }

    private void addStudioHeader(LinearLayout parent, String title, int accentColor) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(accentColor);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(10), 0, dp(6));
        parent.addView(h);
    }

    private TextView makeMiniStudioBtn(String label, int color, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(7));
        b.setBackgroundDrawable(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private void addSelectorRow(LinearLayout parent, final String label, String currentValue,
                                final String[] options, final int currentIdx, final IntChoiceCallback cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF162030);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF25354B);
        row.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(0xFFE2E8F0);
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        left.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(currentValue + "  \u25BE");
        val.setTextColor(0xFF38BDF8);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(val);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(VanillaHomeActivity.this)
                        .setTitle(label)
                        .setSingleChoiceItems(options, currentIdx, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                cb.onSelected(which);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        parent.addView(row);
    }

    private void addToggleRow(LinearLayout parent, String label, boolean checked,
                              CompoundButton.OnCheckedChangeListener listener) {
        CheckBox cb = new CheckBox(this);
        cb.setText("  " + label);
        cb.setChecked(checked);
        cb.setTextColor(0xFFE2E8F0);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        cb.setPadding(dp(6), dp(6), dp(6), dp(6));
        cb.setOnCheckedChangeListener(listener);
        parent.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private boolean launchExplicitOrPackage(String pkg, String cls) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean launchByPackagePrefix(String... prefixes) {
        for (int p = 0; p < prefixes.length; p++) {
            for (int i = 0; i < allApps.size(); i++) {
                AppEntry e = allApps.get(i);
                if (e.pkg.startsWith(prefixes[p])) {
                    launchEntry(e);
                    return true;
                }
            }
        }
        return false;
    }

    private void launchEntry(final AppEntry e) {
        if (e.folderToken != null) {
            showFolderPopupDialog(e.folderToken);
            return;
        }
        Runnable go = new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(e.pkg, e.cls));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                safeStart(i);
                closeDrawer();
            }
        };
        if (PinnedShortcutsStore.isAppLocked(this, e.pkg)) {
            promptPinBeforeRun(e.label, go);
        } else {
            go.run();
        }
    }

    private void promptPinBeforeRun(String label, final Runnable onUnlocked) {
        final EditText pinInput = new EditText(this);
        pinInput.setSingleLine(true);
        pinInput.setHint("Enter 4-digit PIN");
        new AlertDialog.Builder(this)
                .setTitle("🔒 Unlock " + label)
                .setView(pinInput)
                .setPositiveButton("Unlock", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (PinnedShortcutsStore.getPinCode(VanillaHomeActivity.this)
                                .equals(pinInput.getText().toString().trim())) {
                            onUnlocked.run();
                        } else {
                            Toast.makeText(VanillaHomeActivity.this, "Incorrect PIN!", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSetPinDialog(final Runnable afterSet) {
        final EditText pinInput = new EditText(this);
        pinInput.setSingleLine(true);
        pinInput.setHint("4-digit PIN (leave blank to disable PIN lock)");
        pinInput.setText(PinnedShortcutsStore.getPinCode(this));
        new AlertDialog.Builder(this)
                .setTitle("Set 4-Digit App Lock PIN")
                .setView(pinInput)
                .setPositiveButton("Save PIN", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PinnedShortcutsStore.setPinCode(VanillaHomeActivity.this, pinInput.getText().toString());
                        if (afterSet != null) afterSet.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFolderPopupDialog(final String folderToken) {
        final String fname = PinnedShortcutsStore.parseFolderName(folderToken);
        final List<String> comps = PinnedShortcutsStore.parseFolderMembers(folderToken);
        final List<String> labels = new ArrayList<String>();
        for (int i = 0; i < comps.size(); i++) {
            String key = comps.get(i);
            String found = key;
            for (int j = 0; j < allApps.size(); j++) {
                AppEntry ae = allApps.get(j);
                if ((ae.pkg + "/" + ae.cls).equals(key)) { found = ae.label; break; }
            }
            labels.add(found);
        }
        new AlertDialog.Builder(this)
                .setTitle("📁 " + fname)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String key = comps.get(which);
                        int sl = key.indexOf('/');
                        if (sl > 0) launchExplicitOrPackage(key.substring(0, sl), key.substring(sl + 1));
                    }
                })
                .setNeutralButton("Delete Folder", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PinnedShortcutsStore.removeRawEntry(VanillaHomeActivity.this, currentPage, folderToken);
                        refreshCurrentPageShortcuts();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCreateFolderDialog() {
        if (allApps.isEmpty()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        final EditText nameEt = new EditText(this);
        nameEt.setHint("Folder Name (e.g. Reading & Study  or  Media)");
        nameEt.setSingleLine(true);
        box.addView(nameEt);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final CheckBox[] checks = new CheckBox[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText("  " + allApps.get(i).label);
            checks[i] = cb;
            list.addView(cb);
        }
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));

        new AlertDialog.Builder(this)
                .setTitle("Create App Folder")
                .setView(box)
                .setPositiveButton("Add to Home Page " + (currentPage + 1), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<String> sel = new ArrayList<String>();
                        for (int i = 0; i < checks.length; i++) {
                            if (checks[i].isChecked()) sel.add(allApps.get(i).pkg + "/" + allApps.get(i).cls);
                        }
                        if (!sel.isEmpty()) {
                            String tok = PinnedShortcutsStore.buildFolderToken(nameEt.getText().toString(), sel);
                            PinnedShortcutsStore.addRawEntry(VanillaHomeActivity.this, currentPage, tok);
                            refreshCurrentPageShortcuts();
                        }
                    }
                })
                .setNeutralButton("Add to App Bar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<String> sel = new ArrayList<String>();
                        for (int i = 0; i < checks.length; i++) {
                            if (checks[i].isChecked()) sel.add(allApps.get(i).pkg + "/" + allApps.get(i).cls);
                        }
                        if (!sel.isEmpty()) {
                            AppBarConfigStore.Config cfg = AppBarConfigStore.load(VanillaHomeActivity.this);
                            if (cfg.items.size() < AppBarConfigStore.MAX_SLOTS) {
                                cfg.items.add(PinnedShortcutsStore.buildFolderToken(nameEt.getText().toString(), sel));
                                AppBarConfigStore.save(VanillaHomeActivity.this, cfg);
                                rebuildDock();
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAppOptions(final AppEntry e, final boolean onDesktop) {
        if (e.folderToken != null) {
            showFolderPopupDialog(e.folderToken);
            return;
        }
        final AppBarConfigStore.Config barCfg = AppBarConfigStore.load(this);
        final boolean inBar = AppBarConfigStore.containsComponent(barCfg, e.pkg, e.cls);
        final boolean lockedNow = PinnedShortcutsStore.isAppLocked(this, e.pkg);
        final String barAction = inBar ? "Unpin from App Bar" : "Pin to App Bar";
        final String pageAction = onDesktop
                ? "Unpin from Page " + (currentPage + 1)
                : "Pin to Home Page " + (currentPage + 1);
        final String lockAction = lockedNow ? "🔓 Unlock App PIN Protection" : "🔒 Lock App Behind 4-Digit PIN";
        final String[] items = new String[]{
                "Open " + e.label,
                barAction,
                pageAction,
                lockAction,
                "Customize App Bar & Desktop...",
                "App Info"
        };
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            launchEntry(e);
                        } else if (which == 1) {
                            if (inBar) {
                                AppBarConfigStore.removeComponent(VanillaHomeActivity.this, e.pkg, e.cls);
                                rebuildDock();
                                Toast.makeText(VanillaHomeActivity.this,
                                        "Removed " + e.label + " from App Bar", Toast.LENGTH_SHORT).show();
                            } else if (AppBarConfigStore.addOrPinComponent(VanillaHomeActivity.this, e.pkg, e.cls)) {
                                rebuildDock();
                                closeDrawer();
                                Toast.makeText(VanillaHomeActivity.this,
                                        "Pinned " + e.label + " to App Bar!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(VanillaHomeActivity.this,
                                        "App Bar is full (" + AppBarConfigStore.MAX_SLOTS + " slots max).",
                                        Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 2) {
                            if (onDesktop) {
                                PinnedShortcutsStore.removeShortcut(VanillaHomeActivity.this, currentPage, e.pkg, e.cls);
                                refreshCurrentPageShortcuts();
                            } else {
                                PinnedShortcutsStore.addShortcut(VanillaHomeActivity.this, currentPage, e.pkg, e.cls);
                                refreshCurrentPageShortcuts();
                                closeDrawer();
                                Toast.makeText(VanillaHomeActivity.this,
                                        "Pinned " + e.label + " to Page " + (currentPage + 1), Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 3) {
                            if (PinnedShortcutsStore.getPinCode(VanillaHomeActivity.this).isEmpty()) {
                                showSetPinDialog(new Runnable() {
                                    @Override public void run() {
                                        PinnedShortcutsStore.setAppLocked(VanillaHomeActivity.this, e.pkg, true);
                                        Toast.makeText(VanillaHomeActivity.this, "Locked " + e.label, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            } else {
                                PinnedShortcutsStore.setAppLocked(VanillaHomeActivity.this, e.pkg, !lockedNow);
                                Toast.makeText(VanillaHomeActivity.this,
                                        (!lockedNow ? "Locked " : "Unlocked ") + e.label, Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 4) {
                            showAppBarCustomizerDialog();
                        } else {
                            Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + e.pkg));
                            info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStart(info);
                        }
                    }
                })
                .show();
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable t) {
            Toast.makeText(this, "Cannot launch: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private class AppGridAdapter extends BaseAdapter {
        private final List<AppEntry> source;
        private final boolean onDesktop;

        AppGridAdapter(List<AppEntry> source, boolean onDesktop) {
            this.source = source;
            this.onDesktop = onDesktop;
        }

        @Override public int getCount() { return source.size(); }
        @Override public Object getItem(int position) { return source.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AppEntry entry = source.get(position);
            LinearLayout cell = (LinearLayout) convertView;
            ImageView iconView;
            TextView labelView;
            if (cell == null) {
                cell = new LinearLayout(VanillaHomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(6), dp(8), dp(6), dp(8));

                iconView = new ImageView(VanillaHomeActivity.this);
                iconView.setId(1001);
                iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(iconView, new LinearLayout.LayoutParams(dp(50), dp(50)));

                labelView = new TextView(VanillaHomeActivity.this);
                labelView.setId(1002);
                labelView.setTextColor(Color.WHITE);
                labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                labelView.setGravity(Gravity.CENTER_HORIZONTAL);
                labelView.setMaxLines(2);
                labelView.setEllipsize(TextUtils.TruncateAt.END);
                labelView.setShadowLayer(4f, 0f, 1f, 0xCC000000);
                labelView.setPadding(0, dp(5), 0, 0);
                cell.addView(labelView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                iconView = (ImageView) cell.findViewById(1001);
                labelView = (TextView) cell.findViewById(1002);
            }

            iconView.setImageDrawable(entry.icon);
            labelView.setText(entry.label);
            cell.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN) {
                        v.animate().scaleX(0.91f).scaleY(0.91f).setDuration(80).start();
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                                .setInterpolator(new OvershootInterpolator(1.7f)).start();
                    }
                    return false;
                }
            });
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchEntry(entry);
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    lastChildLongPressMs = System.currentTimeMillis();
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    showAppOptions(entry, onDesktop);
                    return true;
                }
            });
            return cell;
        }
    }
}
