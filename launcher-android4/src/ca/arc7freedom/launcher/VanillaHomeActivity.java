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
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.DigitalClock;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
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
 * Clean Vanilla Android 4.2.2 Jelly Bean Home Screen Launcher — upgraded in v1.8.0 with:
 *   - Full live Android Home Screen AppWidget Host (`AppWidgetHost` ID `1024` +
 *     `AppWidgetManager` bind, configure, multi-page persistence, resize height, & remove)!
 *   - Isolated task launching (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`)
 *     so every app opens in its own independent task stack!
 *   - 3 Swipeable Home Pages, All-Apps Drawer, Instant Search, & 5-Icon Favorites Dock.
 */
public class VanillaHomeActivity extends Activity {

    private static final int PAGE_COUNT = 3;
    private static final int APPWIDGET_HOST_ID = 1024;
    private static final int REQ_BIND_APPWIDGET = 4101;
    private static final int REQ_CONFIGURE_APPWIDGET = 4102;

    private static class AppEntry {
        String label;
        String pkg;
        String cls;
        Drawable icon;
    }

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> filteredApps = new ArrayList<AppEntry>();
    private final List<AppEntry> currentPageShortcuts = new ArrayList<AppEntry>();
    private final TextView[] pageDots = new TextView[PAGE_COUNT];

    private AppWidgetManager appWidgetManager;
    private AppWidgetHost appWidgetHost;
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private AppWidgetProviderInfo pendingWidgetInfo = null;

    private int currentPage = 0;
    private LinearLayout desktopView;
    private LinearLayout widgetsContainer;
    private LinearLayout drawerOverlay;
    private LinearLayout dockBar;
    private TextView dateView;
    private TextView desktopHintView;
    private EditText searchBox;
    private AppGridAdapter desktopAdapter;
    private AppGridAdapter drawerAdapter;
    private GestureDetector swipeDetector;
    private boolean drawerOpen = false;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appWidgetManager = AppWidgetManager.getInstance(getApplicationContext());
        appWidgetHost = new AppWidgetHost(getApplicationContext(), APPWIDGET_HOST_ID);

        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null || drawerOpen) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > dp(75) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    if (dx < 0 && currentPage < PAGE_COUNT - 1) {
                        switchToPage(currentPage + 1);
                        return true;
                    } else if (dx > 0 && currentPage > 0) {
                        switchToPage(currentPage - 1);
                        return true;
                    }
                }
                return false;
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Desktop workspace (top bar + live Home Screen Widgets + shortcuts grid + page dots + dock)
        desktopView = new LinearLayout(this);
        desktopView.setOrientation(LinearLayout.VERTICAL);
        desktopView.setPadding(dp(16), dp(8), dp(16), dp(8));

        // Top bar: Left = Date | Center = Clock | Right = [+ Widget] [Apps] [Wallpaper]
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(2), dp(4), dp(6));

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.HORIZONTAL);
        clockCol.setGravity(Gravity.CENTER_VERTICAL);

        DigitalClock clock = new DigitalClock(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        clockCol.addView(clock);

        dateView = new TextView(this);
        dateView.setTextColor(0xEEFFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dateView.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        dateView.setPadding(dp(12), dp(4), 0, 0);
        updateDateLabel();
        clockCol.addView(dateView);

        topBar.addView(clockCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Prominent [+ Widget] pill in top bar!
        TextView addWidgetChip = makeTopChip("+ Widget", 0xDD2563EB);
        addWidgetChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWidgetPickerDialog();
            }
        });
        topBar.addView(addWidgetChip);

        TextView wallpaperChip = makeTopChip("Wallpaper", 0x991E293B);
        LinearLayout.LayoutParams wpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wpLp.leftMargin = dp(8);
        wallpaperChip.setLayoutParams(wpLp);
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
        topBar.addView(wallpaperChip);

        desktopView.addView(topBar);

        // Live Home Screen Widgets area (scrollable if user adds multiple widgets on a page)
        ScrollView widgetScroll = new ScrollView(this);
        widgetScroll.setVerticalScrollBarEnabled(false);
        widgetsContainer = new LinearLayout(this);
        widgetsContainer.setOrientation(LinearLayout.VERTICAL);
        widgetScroll.addView(widgetsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        desktopView.addView(widgetScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        desktopHintView = new TextView(this);
        desktopHintView.setTextColor(0xCCFFFFFF);
        desktopHintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desktopHintView.setGravity(Gravity.CENTER_HORIZONTAL);
        desktopHintView.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        desktopHintView.setPadding(0, dp(2), 0, dp(4));
        desktopView.addView(desktopHintView);

        // Pinned Home Shortcuts Grid (middle workspace)
        GridView desktopGrid = new GridView(this);
        desktopGrid.setNumColumns(5);
        desktopGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        desktopGrid.setVerticalSpacing(dp(10));
        desktopGrid.setHorizontalSpacing(dp(8));
        desktopGrid.setGravity(Gravity.CENTER);
        desktopAdapter = new AppGridAdapter(currentPageShortcuts, true);
        desktopGrid.setAdapter(desktopAdapter);
        desktopView.addView(desktopGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 3-Page Dot Indicator Row (also clickable!)
        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        dotsRow.setPadding(0, dp(3), 0, dp(5));
        for (int i = 0; i < PAGE_COUNT; i++) {
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

        // Bottom 5-icon Favorites + All-Apps Dock
        dockBar = new LinearLayout(this);
        dockBar.setOrientation(LinearLayout.HORIZONTAL);
        dockBar.setGravity(Gravity.CENTER);
        dockBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0x66000000);
        dockBg.setCornerRadius(dp(14));
        dockBar.setBackgroundDrawable(dockBg);
        desktopView.addView(dockBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(desktopView);

        // 2. Full-screen All-Apps Drawer Overlay (Holo Dark)
        drawerOverlay = new LinearLayout(this);
        drawerOverlay.setOrientation(LinearLayout.VERTICAL);
        drawerOverlay.setBackgroundColor(0xEA11161D);
        drawerOverlay.setPadding(dp(18), dp(14), dp(18), dp(12));
        drawerOverlay.setVisibility(View.GONE);

        LinearLayout drawerHeader = new LinearLayout(this);
        drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("ALL APPS");
        drawerTitle.setTextColor(0xFF33B5E5);
        drawerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        drawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchBox = new EditText(this);
        searchBox.setHint("Search apps (long-press any app to pin to Home)...");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(0x88FFFFFF);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams sParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sParams.leftMargin = dp(12);
        sParams.rightMargin = dp(12);
        searchBox.setLayoutParams(sParams);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        drawerHeader.addView(searchBox);

        TextView addWidgetBtn2 = makeTopChip("+ Widget", 0xDD2563EB);
        addWidgetBtn2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
                showWidgetPickerDialog();
            }
        });
        drawerHeader.addView(addWidgetBtn2);

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

        reloadApps();
    }

    private TextView makeTopChip(String text, int bgArgb) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
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
        currentPage = Math.max(0, Math.min(PAGE_COUNT - 1, index));
        refreshCurrentPageShortcuts();
        renderWidgetsForCurrentPage();
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
                PinnedShortcutsStore.removeWidgetEverywhere(this, rec.appWidgetId, PAGE_COUNT);
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
                        showWidgetManageDialog(rec, widgetLabel);
                        return true;
                    }
                });
                frame.addView(hv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                // Subtle corner gear chip so touchscreen tap also lets users resize/remove any widget easily!
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
                PinnedShortcutsStore.removeWidgetEverywhere(this, rec.appWidgetId, PAGE_COUNT);
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
            sub.setText(info.provider.getPackageName() + "  •  " + wDp + "×" + hDp + " dp");
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
                            PinnedShortcutsStore.removeWidgetEverywhere(VanillaHomeActivity.this, rec.appWidgetId, PAGE_COUNT);
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

        // Auto-seed Page 1 with Arc7 apps on very first boot!
        if (currentPage == 0 && saved.isEmpty() && !allApps.isEmpty()) {
            for (int i = 0; i < allApps.size() && saved.size() < 8; i++) {
                AppEntry e = allApps.get(i);
                if (e.pkg.equals("ca.arc7freedom.app")
                        || e.pkg.equals("eu.bubu1.fdroidclassic.debug")
                        || e.pkg.contains("fdroid")) {
                    PinnedShortcutsStore.addShortcut(this, 0, e.pkg, e.cls);
                    saved.add(e.pkg + "/" + e.cls);
                }
            }
        }

        for (int i = 0; i < saved.size(); i++) {
            String key = saved.get(i);
            for (int j = 0; j < allApps.size(); j++) {
                AppEntry e = allApps.get(j);
                if ((e.pkg + "/" + e.cls).equals(key)) {
                    currentPageShortcuts.add(e);
                    break;
                }
            }
        }

        List<PinnedShortcutsStore.WidgetRecord> wList = PinnedShortcutsStore.loadWidgets(this, currentPage);
        if (desktopHintView != null) {
            desktopHintView.setText("Page " + (currentPage + 1) + " of " + PAGE_COUNT + "  •  " +
                    (wList.isEmpty()
                            ? "Tap '+ Widget' above to add clocks, audio controls & Android widgets, or long-press any app in All Apps to pin"
                            : (wList.size() + " live widget(s) active • Tap \u22EF or long-press a widget to resize/remove")));
        }
        for (int i = 0; i < PAGE_COUNT; i++) {
            if (pageDots[i] != null) {
                pageDots[i].setTextColor(i == currentPage ? 0xFF38BDF8 : 0x66FFFFFF);
                pageDots[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, i == currentPage ? 16 : 12);
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
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Throwable ignored) {}
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
        SimpleDateFormat fmt = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US);
        dateView.setText(fmt.format(new Date()).toUpperCase(Locale.US));
    }

    private void openDrawer() {
        drawerOpen = true;
        searchBox.setText("");
        filterApps("");
        drawerOverlay.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawerOverlay.setVisibility(View.GONE);
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
            e.icon = ri.loadIcon(pm);
            allApps.add(e);
        }

        Collections.sort(allApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        filterApps(searchBox != null ? searchBox.getText().toString() : "");
        refreshCurrentPageShortcuts();
        rebuildDock();
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

    private void rebuildDock() {
        dockBar.removeAllViews();

        addDockButton("Browser", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7BrowserActivity")
                        && !launchByPackagePrefix("org.mozilla", "com.opera", "acr.browser", "com.android.browser", "com.android.chrome")) {
                    safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://html.duckduckgo.com/html/")));
                }
            }
        });

        addDockButton("Reader", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7EbookReaderActivity")
                        && !launchByPackagePrefix("org.koreader", "org.coolreader", "com.foobnix", "org.readera")) {
                    openDrawer();
                }
            }
        });

        // Center All-Apps circular button
        TextView allAppsBtn = new TextView(this);
        allAppsBtn.setText(":::");
        allAppsBtn.setTextColor(Color.WHITE);
        allAppsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        allAppsBtn.setTypeface(Typeface.DEFAULT_BOLD);
        allAppsBtn.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xCC33B5E5);
        circle.setStroke(dp(2), Color.WHITE);
        allAppsBtn.setBackgroundDrawable(circle);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        centerParams.leftMargin = dp(18);
        centerParams.rightMargin = dp(18);
        allAppsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        });
        dockBar.addView(allAppsBtn, centerParams);

        addDockButton("F-Droid", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!launchByPackagePrefix("eu.bubu1.fdroidclassic.debug", "org.fdroid.fdroid", "eu.bubu1.fdroidclassic")) {
                    launchExplicitOrPackage("ca.arc7freedom.app", "ca.arc7freedom.app.Arc7AppManagerActivity");
                }
            }
        });

        addDockButton("Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                safeStart(new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }

    private void addDockButton(String title, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setOnClickListener(listener);
        dockBar.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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

    private void launchEntry(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(e.pkg, e.cls));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(i);
        closeDrawer();
    }

    private void showAppOptions(final AppEntry e, final boolean onDesktop) {
        final String[] items = onDesktop
                ? new String[] { "Open " + e.label, "Unpin from Page " + (currentPage + 1), "App Info" }
                : new String[] { "Open " + e.label, "Pin to Home Page " + (currentPage + 1), "App Info" };
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            launchEntry(e);
                        } else if (which == 1) {
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
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchEntry(entry);
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showAppOptions(entry, onDesktop);
                    return true;
                }
            });
            return cell;
        }
    }
}
