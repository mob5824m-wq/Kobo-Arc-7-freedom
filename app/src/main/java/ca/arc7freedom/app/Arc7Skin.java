package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Unified Kobo Arc 7 OS Custom Skin, System-Wide Customizable App Bar Engine &
 * Full Default System App Icon Reskinning Engine (`v2.1.0`).
 *
 * Synchronizes seamlessly across `KoboArc7Freedom.apk` (`ca.arc7freedom.app`),
 * `VanillaAndroid4Home.apk` (`ca.arc7freedom.launcher`), and all 4 Home Screen Widgets via:
 *   - `/sdcard/Arc7Freedom/skin.properties` + `ca.arc7freedom.action.SKIN_CHANGED`
 *   - `/sdcard/Arc7Freedom/appbar.properties` + `ca.arc7freedom.action.APP_BAR_CHANGED`
 */
public final class Arc7Skin {

    private Arc7Skin() {}

    public static final String ACTION_SKIN_CHANGED = "ca.arc7freedom.action.SKIN_CHANGED";
    public static final String ACTION_APP_BAR_CHANGED = "ca.arc7freedom.action.APP_BAR_CHANGED";
    public static final String PREFS = "arc7_skin_prefs";
    public static final String APP_BAR_PREFS = "arc7_app_bar_prefs";
    public static final String KEY_SKIN_ID = "skin_id";

    public static final String SKIN_OBSIDIAN_CYAN = "obsidian_cyan";
    public static final String SKIN_KOBO_WARM_INK = "kobo_warm_ink";
    public static final String SKIN_EMERALD_MATRIX = "emerald_matrix";
    public static final String SKIN_ROYAL_AMETHYST = "royal_amethyst";
    public static final String SKIN_NORDIC_FROST = "nordic_frost";

    public static final class Palette {
        public final String id;
        public final String name;
        public final String subtitle;
        public final int bgPage;
        public final int headerTop;
        public final int headerBottom;
        public final int surface;
        public final int surfaceElevated;
        public final int elevated;
        public final int border;
        public final int accent;
        public final int accentLight;
        public final int accentDark;
        public final int accentSecondary;
        public final int textPrimary;
        public final int textMuted;
        public final boolean lightMode;

        public Palette(String id, String name, String subtitle,
                       int bgPage, int headerTop, int headerBottom,
                       int surface, int surfaceElevated, int border,
                       int accent, int accentDark, int accentSecondary,
                       int textPrimary, int textMuted, boolean lightMode) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.bgPage = bgPage;
            this.headerTop = headerTop;
            this.headerBottom = headerBottom;
            this.surface = surface;
            this.surfaceElevated = surfaceElevated;
            this.elevated = surfaceElevated;
            this.border = border;
            this.accent = accent;
            this.accentLight = accent;
            this.accentDark = accentDark;
            this.accentSecondary = accentSecondary;
            this.textPrimary = textPrimary;
            this.textMuted = textMuted;
            this.lightMode = lightMode;
        }
    }

    /* ==================== Shared Cross-Process App Bar Config ==================== */

    public static final String[] HOME_POS_NAMES = new String[]{
            "Bottom Dock (Default)",
            "Top App Bar",
            "Left Side Rail (Best for Landscape)",
            "Right Side Rail",
            "Hidden (Swipe Up for All Apps)"
    };
    public static final String[] HOME_DRAWER_NAMES = new String[]{
            "Center Jewel (:::)",
            "Start Edge (Left / Top)",
            "End Edge (Right / Bottom)",
            "Hidden (Swipe Up on Home)"
    };
    public static final String[] HOME_STYLE_NAMES = new String[]{
            "Floating Glass Pill",
            "Full-Width Edge-to-Edge Bar",
            "Minimal Transparent Icons",
            "Solid Matte Slate Bar",
            "Glowing Neon Accent Rim"
    };
    public static final String[] HOME_ICON_SIZE_NAMES = new String[]{
            "Compact (34 dp — fits more apps)",
            "Standard (42 dp — balanced)",
            "Large (50 dp — tablet touch)",
            "Extra-Large (58 dp — bold showcase)"
    };
    public static final String[] HOME_TOP_BAR_NAMES = new String[]{
            "Frosted Glass Capsule (Default)",
            "Minimal Floating Clock & Chips",
            "Compact Slim Bar",
            "Hidden (Maximum Wallpaper Space)"
    };
    public static final String[] IN_APP_DENSITY_NAMES = new String[]{
            "Compact App Bar (42 dp)",
            "Standard App Bar (54 dp)",
            "Tall Comfortable App Bar (66 dp)"
    };
    public static final String[] IN_APP_STYLE_NAMES = new String[]{
            "OS Skin Gradient Header",
            "Flat Solid Surface Header",
            "Tinted Accent Header",
            "Minimal Dark + Accent Underline"
    };
    public static final int[] ACCENT_SWATCHES = new int[]{
            0, 0xFF22D3EE, 0xFF10B981, 0xFFF59E0B, 0xFFC084FC, 0xFFF43F5E, 0xFFE2E8F0
    };
    public static final String[] ACCENT_SWATCH_NAMES = new String[]{
            "Match Active Arc7 OS Skin (Auto)",
            "Electric Cyan (#22D3EE)",
            "Emerald Mint (#10B981)",
            "Solaris Amber (#F59E0B)",
            "Royal Amethyst (#C084FC)",
            "Crimson Coral (#F43F5E)",
            "Platinum Ice (#E2E8F0)"
    };
    public static final int[] OPACITY_PRESETS = new int[]{ -1, 0, 35, 62, 82, 96 };
    public static final String[] OPACITY_NAMES = new String[]{
            "Theme Default Opacity",
            "0% (Crystal Clear)",
            "35% (Light Glass)",
            "62% (Medium Frosted)",
            "82% (Deep Tint)",
            "96% (Near Solid)"
    };

    public static final List<String> DEFAULT_BAR_ITEMS = Arrays.asList(
            "@browser", "@calendar", "@photos", "@camera", "@vlc", "@settings"
    );
    public static final int MAX_BAR_SLOTS = 12;

    public static final class AppBarPrefs {
        public final List<String> items = new ArrayList<String>();
        public int position = 0;
        public int drawerBtnPos = 0;
        public int style = 0;
        public int iconSizeMode = 1;
        public boolean showLabels = true;
        public int opacityPct = -1;
        public int accentOverride = 0;

        public int topBarMode = 0;
        public boolean topShowClock = true;
        public boolean topShowSearch = true;
        public boolean topShowAppBarBtn = true;
        public boolean topShowSkinBtn = true;
        public boolean topShowWallpaperBtn = true;

        public int inAppDensity = 1;
        public boolean inAppCenterTitle = false;
        public int inAppStyle = 0;
        public boolean inAppShowQuickSwitch = true;
    }

    public static File sharedAppBarFile() {
        String prop = System.getProperty("arc7.test.appbar.file");
        if (prop != null && prop.length() > 0) return new File(prop);
        File ext = null;
        try { ext = Environment.getExternalStorageDirectory(); } catch (Throwable ignored) {}
        return ext != null
                ? new File(ext, "Arc7Freedom/appbar.properties")
                : new File("/sdcard/Arc7Freedom/appbar.properties");
    }

    public static AppBarPrefs loadAppBarPrefs(Context ctx) {
        AppBarPrefs c = new AppBarPrefs();
        Properties p = null;
        try {
            File f = sharedAppBarFile();
            if (f.isFile() && f.canRead()) {
                p = new Properties();
                FileInputStream fis = new FileInputStream(f);
                try { p.load(fis); } finally { try { fis.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {
            p = null;
        }
        SharedPreferences sp = ctx != null
                ? ctx.getSharedPreferences(APP_BAR_PREFS, Context.MODE_PRIVATE)
                : null;

        String rawItems = readPropStr(p, sp, "items", null);
        if (rawItems == null) {
            c.items.addAll(DEFAULT_BAR_ITEMS);
        } else if (rawItems.trim().length() > 0) {
            String[] parts = rawItems.split("\\|");
            for (int i = 0; i < parts.length && c.items.size() < MAX_BAR_SLOTS; i++) {
                String tok = parts[i].trim();
                if (tok.length() > 0 && !c.items.contains(tok)) c.items.add(tok);
            }
        }
        c.position = clamp(readPropInt(p, sp, "position", 0), 0, HOME_POS_NAMES.length - 1);
        c.drawerBtnPos = clamp(readPropInt(p, sp, "drawer_btn_pos", 0), 0, HOME_DRAWER_NAMES.length - 1);
        c.style = clamp(readPropInt(p, sp, "style", 0), 0, HOME_STYLE_NAMES.length - 1);
        c.iconSizeMode = clamp(readPropInt(p, sp, "icon_size_mode", 1), 0, HOME_ICON_SIZE_NAMES.length - 1);
        c.showLabels = readPropBool(p, sp, "show_labels", true);
        c.opacityPct = readPropInt(p, sp, "opacity_pct", -1);
        c.accentOverride = readPropInt(p, sp, "accent_override", 0);

        c.topBarMode = clamp(readPropInt(p, sp, "top_bar_mode", 0), 0, HOME_TOP_BAR_NAMES.length - 1);
        c.topShowClock = readPropBool(p, sp, "top_show_clock", true);
        c.topShowSearch = readPropBool(p, sp, "top_show_search", true);
        c.topShowAppBarBtn = readPropBool(p, sp, "top_show_appbar_btn", true);
        c.topShowSkinBtn = readPropBool(p, sp, "top_show_skin_btn", true);
        c.topShowWallpaperBtn = readPropBool(p, sp, "top_show_wallpaper_btn", true);

        c.inAppDensity = clamp(readPropInt(p, sp, "in_app_density", 1), 0, IN_APP_DENSITY_NAMES.length - 1);
        c.inAppCenterTitle = readPropBool(p, sp, "in_app_center_title", false);
        c.inAppStyle = clamp(readPropInt(p, sp, "in_app_style", 0), 0, IN_APP_STYLE_NAMES.length - 1);
        c.inAppShowQuickSwitch = readPropBool(p, sp, "in_app_quick_switch", true);
        return c;
    }

    public static void saveAppBarPrefs(Context ctx, AppBarPrefs c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.items.size() && i < MAX_BAR_SLOTS; i++) {
            if (i > 0) sb.append('|');
            sb.append(c.items.get(i));
        }
        String itemsStr = sb.toString();

        if (ctx != null) {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(APP_BAR_PREFS, Context.MODE_PRIVATE).edit();
            ed.putString("items", itemsStr);
            ed.putInt("position", c.position);
            ed.putInt("drawer_btn_pos", c.drawerBtnPos);
            ed.putInt("style", c.style);
            ed.putInt("icon_size_mode", c.iconSizeMode);
            ed.putBoolean("show_labels", c.showLabels);
            ed.putInt("opacity_pct", c.opacityPct);
            ed.putInt("accent_override", c.accentOverride);
            ed.putInt("top_bar_mode", c.topBarMode);
            ed.putBoolean("top_show_clock", c.topShowClock);
            ed.putBoolean("top_show_search", c.topShowSearch);
            ed.putBoolean("top_show_appbar_btn", c.topShowAppBarBtn);
            ed.putBoolean("top_show_skin_btn", c.topShowSkinBtn);
            ed.putBoolean("top_show_wallpaper_btn", c.topShowWallpaperBtn);
            ed.putInt("in_app_density", c.inAppDensity);
            ed.putBoolean("in_app_center_title", c.inAppCenterTitle);
            ed.putInt("in_app_style", c.inAppStyle);
            ed.putBoolean("in_app_quick_switch", c.inAppShowQuickSwitch);
            ed.commit();
        }

        try {
            File f = sharedAppBarFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("items", itemsStr);
            p.setProperty("position", String.valueOf(c.position));
            p.setProperty("drawer_btn_pos", String.valueOf(c.drawerBtnPos));
            p.setProperty("style", String.valueOf(c.style));
            p.setProperty("icon_size_mode", String.valueOf(c.iconSizeMode));
            p.setProperty("show_labels", String.valueOf(c.showLabels));
            p.setProperty("opacity_pct", String.valueOf(c.opacityPct));
            p.setProperty("accent_override", String.valueOf(c.accentOverride));
            p.setProperty("top_bar_mode", String.valueOf(c.topBarMode));
            p.setProperty("top_show_clock", String.valueOf(c.topShowClock));
            p.setProperty("top_show_search", String.valueOf(c.topShowSearch));
            p.setProperty("top_show_appbar_btn", String.valueOf(c.topShowAppBarBtn));
            p.setProperty("top_show_skin_btn", String.valueOf(c.topShowSkinBtn));
            p.setProperty("top_show_wallpaper_btn", String.valueOf(c.topShowWallpaperBtn));
            p.setProperty("in_app_density", String.valueOf(c.inAppDensity));
            p.setProperty("in_app_center_title", String.valueOf(c.inAppCenterTitle));
            p.setProperty("in_app_style", String.valueOf(c.inAppStyle));
            p.setProperty("in_app_quick_switch", String.valueOf(c.inAppShowQuickSwitch));
            FileOutputStream fos = new FileOutputStream(f);
            try { p.store(fos, "Kobo Arc 7 Customizable App Bar Configuration"); }
            finally { try { fos.close(); } catch (Throwable ignored) {} }
            f.setReadable(true, false);
        } catch (Throwable ignored) {}

        if (ctx != null) {
            try {
                ctx.sendBroadcast(new Intent(ACTION_APP_BAR_CHANGED));
                ctx.sendBroadcast(new Intent(ACTION_SKIN_CHANGED));
            } catch (Throwable ignored) {}
        }
    }

    public static AppBarPrefs resetAppBarDefaults(Context ctx) {
        AppBarPrefs d = new AppBarPrefs();
        d.items.addAll(DEFAULT_BAR_ITEMS);
        saveAppBarPrefs(ctx, d);
        return d;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String readPropStr(Properties p, SharedPreferences sp, String k, String d) {
        if (p != null && p.containsKey(k)) return p.getProperty(k);
        if (sp != null && sp.contains(k)) return sp.getString(k, d);
        return d;
    }

    private static int readPropInt(Properties p, SharedPreferences sp, String k, int d) {
        if (p != null && p.containsKey(k)) {
            try { return Integer.parseInt(p.getProperty(k).trim()); } catch (Throwable ignored) {}
        }
        if (sp != null && sp.contains(k)) {
            try { return sp.getInt(k, d); } catch (Throwable ignored) {}
        }
        return d;
    }

    private static boolean readPropBool(Properties p, SharedPreferences sp, String k, boolean d) {
        if (p != null && p.containsKey(k)) return Boolean.parseBoolean(p.getProperty(k).trim());
        if (sp != null && sp.contains(k)) return sp.getBoolean(k, d);
        return d;
    }

    /* ==================== Skin Palettes ==================== */

    public static final String[] THEME_NAMES = new String[]{
            "Arc7 Obsidian Cyan (Signature)",
            "Kobo Paper & Warm Ink",
            "Emerald Matrix Carbon",
            "Royal Amethyst Nebula",
            "Nordic Daylight Frost"
    };

    public static Palette paletteByIndex(int idx) {
        return ALL_PALETTES[((idx % ALL_PALETTES.length) + ALL_PALETTES.length) % ALL_PALETTES.length];
    }

    public static final Palette[] ALL_PALETTES = new Palette[]{
            new Palette(
                    SKIN_OBSIDIAN_CYAN,
                    "Arc7 Obsidian Cyan (Signature)",
                    "Anodized midnight titanium, electric cyan edge & frosted glass",
                    0xFF090E16, 0xFF102033, 0xFF0B1523,
                    0xFF131C28, 0xFF1B2738, 0xFF293B52,
                    0xFF22D3EE, 0xFF0284C7, 0xFF10B981,
                    0xFFF8FAFC, 0xFF94A3B8, false),
            new Palette(
                    SKIN_KOBO_WARM_INK,
                    "Kobo Paper & Warm Ink",
                    "E-ink inspired warm espresso slate & golden amber reading accents",
                    0xFF14110F, 0xFF271F19, 0xFF1B1612,
                    0xFF211C18, 0xFF2C2520, 0xFF44382F,
                    0xFFF59E0B, 0xFFD97706, 0xFF34D399,
                    0xFFFEFCE8, 0xFFD6D3D1, false),
            new Palette(
                    SKIN_EMERALD_MATRIX,
                    "Emerald Matrix Carbon",
                    "Deep forest carbon chassis with luminous phosphor emerald trim",
                    0xFF06110D, 0xFF0B251B, 0xFF081912,
                    0xFF0E1D17, 0xFF152A21, 0xFF234738,
                    0xFF10B981, 0xFF059669, 0xFF38BDF8,
                    0xFFECFDF5, 0xFF94A3B8, false),
            new Palette(
                    SKIN_ROYAL_AMETHYST,
                    "Royal Amethyst Nebula",
                    "Velvet cosmos violet chrome with magenta-iris glass highlights",
                    0xFF0E091A, 0xFF22133B, 0xFF150C26,
                    0xFF19112B, 0xFF24193D, 0xFF3E2C66,
                    0xFFC084FC, 0xFF9333EA, 0xFFEC4899,
                    0xFFFAF5FF, 0xFFC4B5FD, false),
            new Palette(
                    SKIN_NORDIC_FROST,
                    "Nordic Daylight Frost",
                    "Crisp sunlit silver-ice OS skin with high-contrast cobalt typography",
                    0xFFEFF4FA, 0xFF1E3A8A, 0xFF1D4ED8,
                    0xFFFFFFFF, 0xFFE2E8F0, 0xFFCBD5E1,
                    0xFF2563EB, 0xFF1D4ED8, 0xFF059669,
                    0xFF0F172A, 0xFF475569, true)
    };

    private static File sharedSkinFile() {
        return new File(Environment.getExternalStorageDirectory(), "Arc7Freedom/skin.properties");
    }

    public static String currentSkinId(Context ctx) {
        try {
            File f = sharedSkinFile();
            if (f.isFile() && f.canRead()) {
                Properties p = new Properties();
                FileInputStream fis = new FileInputStream(f);
                try {
                    p.load(fis);
                    String id = p.getProperty("skin_id");
                    if (id != null && id.trim().length() > 0) return id.trim();
                } finally {
                    try { fis.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        if (ctx != null) {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SKIN_ID, SKIN_OBSIDIAN_CYAN);
        }
        return SKIN_OBSIDIAN_CYAN;
    }

    public static Palette current(Context ctx) {
        String id = currentSkinId(ctx);
        for (int i = 0; i < ALL_PALETTES.length; i++) {
            if (ALL_PALETTES[i].id.equals(id)) return ALL_PALETTES[i];
        }
        return ALL_PALETTES[0];
    }

    public static void applySkinSelection(Context ctx, String skinId, boolean syncMatchingWallpaper) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_SKIN_ID, skinId).commit();
        try {
            File f = sharedSkinFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("skin_id", skinId);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                p.store(fos, "Kobo Arc 7 Custom OS Skin");
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            f.setReadable(true, false);
        } catch (Throwable ignored) {}

        if (syncMatchingWallpaper) {
            try {
                int wpIdx = 0;
                if (SKIN_KOBO_WARM_INK.equals(skinId)) wpIdx = 6;
                else if (SKIN_EMERALD_MATRIX.equals(skinId)) wpIdx = 1;
                else if (SKIN_ROYAL_AMETHYST.equals(skinId)) wpIdx = 7;
                else if (SKIN_NORDIC_FROST.equals(skinId)) wpIdx = 10;
                Bitmap bmp = Arc7WallpaperCatalog.renderTheme(wpIdx, 1280, 800);
                android.app.WallpaperManager.getInstance(ctx).setBitmap(bmp);
            } catch (Throwable ignored) {}
        }

        try {
            ctx.sendBroadcast(new Intent(ACTION_SKIN_CHANGED));
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            Class<?>[] providers = new Class<?>[]{
                    Arc7ClockWidgetProvider.class,
                    Arc7AudioWidgetProvider.class,
                    Arc7PowerWidgetProvider.class,
                    Arc7CalendarWidgetProvider.class
            };
            for (int i = 0; i < providers.length; i++) {
                int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, providers[i]));
                if (ids != null && ids.length > 0) {
                    Intent upd = new Intent(ctx, providers[i]);
                    upd.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                    upd.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                    ctx.sendBroadcast(upd);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void showThemePickerDialog(final Activity host, final Runnable onApplied) {
        showSkinStudioDialog(host, onApplied);
    }

    @SuppressWarnings("deprecation")
    public static LinearLayout card(Context ctx, Palette pal) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 14);
        box.setPadding(p, p, p, p);
        box.setBackgroundDrawable(cardDrawable(ctx, pal, 14));
        return box;
    }

    public static void showSkinStudioDialog(final Activity host, final Runnable onApplied) {
        final String cur = currentSkinId(host);
        String[] labels = new String[ALL_PALETTES.length];
        int checked = 0;
        for (int i = 0; i < ALL_PALETTES.length; i++) {
            if (ALL_PALETTES[i].id.equals(cur)) checked = i;
            labels[i] = ALL_PALETTES[i].name + "\n  " + ALL_PALETTES[i].subtitle;
        }
        final int[] picked = new int[]{checked};
        new AlertDialog.Builder(host)
                .setTitle("Arc7 Custom OS Skin Studio")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        picked[0] = which;
                    }
                })
                .setPositiveButton("Apply Skin + Wallpaper", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Palette p = ALL_PALETTES[picked[0]];
                        applySkinSelection(host, p.id, true);
                        Toast.makeText(host, "Applied " + p.name + " across OS, Launcher & Widgets!", Toast.LENGTH_SHORT).show();
                        if (onApplied != null) onApplied.run();
                    }
                })
                .setNeutralButton("Customize App Bar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Palette p = ALL_PALETTES[picked[0]];
                        applySkinSelection(host, p.id, false);
                        showAppBarCustomizerDialog(host, onApplied);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ==================== Reusable Skinned UI & App Bar Builders ==================== */

    public static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable cardDrawable(Context ctx, Palette p, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(p.surface);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setStroke(dp(ctx, 1), p.border);
        return g;
    }

    public static GradientDrawable elevatedDrawable(Context ctx, Palette p, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(p.surfaceElevated);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setStroke(dp(ctx, 1), p.border);
        return g;
    }

    public static GradientDrawable headerDrawable(Palette p) {
        return new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{p.headerTop, p.headerBottom});
    }

    public static Drawable customAppBarDrawable(Context ctx, Palette p, AppBarPrefs prefs) {
        int accent = prefs.accentOverride != 0 ? prefs.accentOverride : p.accent;
        if (prefs.inAppStyle == 1) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(p.surface | 0xFF000000);
            return g;
        }
        if (prefs.inAppStyle == 2) {
            int r = (Color.red(accent) * 42 + Color.red(p.headerTop) * 58) / 100;
            int gCol = (Color.green(accent) * 42 + Color.green(p.headerTop) * 58) / 100;
            int b = (Color.blue(accent) * 42 + Color.blue(p.headerTop) * 58) / 100;
            return new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.rgb(r, gCol, b), p.headerBottom | 0xFF000000});
        }
        if (prefs.inAppStyle == 3) {
            GradientDrawable base = new GradientDrawable();
            base.setColor(0xFF0B111B);
            GradientDrawable line = new GradientDrawable();
            line.setColor(accent | 0xFF000000);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{line, base});
            ld.setLayerInset(1, 0, 0, 0, dp(ctx, 2));
            return ld;
        }
        return headerDrawable(p);
    }

    public static GradientDrawable buttonDrawable(Context ctx, int fillColor, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setStroke(dp(ctx, 1), 0x44FFFFFF);
        return g;
    }

    @SuppressWarnings("deprecation")
    public static Button actionButton(Context ctx, String text, int fillColor) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(ctx, 13), dp(ctx, 8), dp(ctx, 13), dp(ctx, 8));
        b.setBackgroundDrawable(buttonDrawable(ctx, fillColor, 10));
        return b;
    }

    @SuppressWarnings("deprecation")
    public static LinearLayout buildAppTopBar(final Activity activity, final Palette p, int iconResId,
                                              String titleText, String subtitleText, View rightExtra) {
        final AppBarPrefs prefs = loadAppBarPrefs(activity);
        final int accent = prefs.accentOverride != 0 ? prefs.accentOverride : p.accent;
        int vPadDp = prefs.inAppDensity == 0 ? 5 : (prefs.inAppDensity == 2 ? 15 : 10);
        int iconDp = prefs.inAppDensity == 0 ? 28 : (prefs.inAppDensity == 2 ? 42 : 36);
        float titleSp = prefs.inAppDensity == 0 ? 15.5f : (prefs.inAppDensity == 2 ? 18.5f : 17f);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundDrawable(customAppBarDrawable(activity, p, prefs));
        bar.setPadding(dp(activity, 14), dp(activity, vPadDp), dp(activity, 14), dp(activity, vPadDp));
        bar.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showAppBarCustomizerDialog(activity, new Runnable() {
                    @Override
                    public void run() {
                        activity.recreate();
                    }
                });
                return true;
            }
        });

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconResId);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(activity, iconDp), dp(activity, iconDp));
        ilp.rightMargin = dp(activity, 11);
        bar.addView(icon, ilp);

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        if (prefs.inAppCenterTitle) {
            titleCol.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        TextView t = new TextView(activity);
        t.setText(titleText);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        if (prefs.inAppCenterTitle) t.setGravity(Gravity.CENTER_HORIZONTAL);
        titleCol.addView(t);

        if (subtitleText != null && subtitleText.length() > 0 && prefs.inAppDensity != 0) {
            TextView sub = new TextView(activity);
            sub.setText(subtitleText);
            sub.setTextColor(0xCCDBEAFE);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            if (prefs.inAppCenterTitle) sub.setGravity(Gravity.CENTER_HORIZONTAL);
            titleCol.addView(sub);
        }

        bar.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (prefs.inAppShowQuickSwitch) {
            Button appsPill = actionButton(activity, "Apps \u25BE", (accent & 0x00FFFFFF) | 0xCC000000);
            appsPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            appsPill.setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.rightMargin = dp(activity, rightExtra != null ? 8 : 0);
            appsPill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showQuickAppBarSwitcher(activity);
                }
            });
            bar.addView(appsPill, qlp);
        }

        if (rightExtra != null) {
            bar.addView(rightExtra);
        }
        return bar;
    }

    public static void showQuickAppBarSwitcher(final Activity activity) {
        final AppBarPrefs prefs = loadAppBarPrefs(activity);
        final List<String> names = new ArrayList<String>();
        final List<String> tokens = new ArrayList<String>();

        for (int i = 0; i < prefs.items.size(); i++) {
            String tok = prefs.items.get(i);
            tokens.add(tok);
            names.add((i + 1) + ".  " + labelForBarToken(activity, tok));
        }
        names.add("\u2302  Home Screen");
        tokens.add("@__home__");
        names.add("\u2699  Customize App Bar...");
        tokens.add("@__customize__");

        new AlertDialog.Builder(activity)
                .setTitle("Quick App Bar Switcher")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String tok = tokens.get(which);
                        if ("@__home__".equals(tok)) {
                            Intent home = new Intent(Intent.ACTION_MAIN);
                            home.addCategory(Intent.CATEGORY_HOME);
                            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { activity.startActivity(home); } catch (Throwable ignored) {}
                        } else if ("@__customize__".equals(tok)) {
                            showAppBarCustomizerDialog(activity, new Runnable() {
                                @Override public void run() { activity.recreate(); }
                            });
                        } else {
                            launchBarToken(activity, tok);
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    public static String labelForBarToken(Context ctx, String tok) {
        if ("@browser".equals(tok)) return "Arc7 Browser";
        if ("@calendar".equals(tok)) return "Arc7 Calendar";
        if ("@photos".equals(tok)) return "Arc7 Photos";
        if ("@camera".equals(tok)) return "Arc7 Camera";
        if ("@vlc".equals(tok)) return "VLC Media Player";
        if ("@settings".equals(tok)) return "System Settings";
        if ("@files".equals(tok)) return "Arc7 Files";
        if ("@notes".equals(tok)) return "Arc7 Notes & Sketchpad";
        if ("@calculator".equals(tok)) return "Arc7 Calculator";
        if ("@clock".equals(tok)) return "Arc7 Clock & Alarms";
        if ("@ereader".equals(tok)) return "Arc7 E-Reader";
        if ("@audio".equals(tok)) return "Arc7 Music Player";
        if ("@terminal".equals(tok)) return "Root Shell Terminal";
        if ("@wired".equals(tok)) return "Chromebook USB Internet";
        if ("@freedom".equals(tok)) return "Kobo Arc 7 Freedom";
        int slash = tok != null ? tok.indexOf('/') : -1;
        if (slash > 0 && ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ComponentName cn = new ComponentName(tok.substring(0, slash), tok.substring(slash + 1));
                CharSequence cs = pm.getActivityInfo(cn, 0).loadLabel(pm);
                if (cs != null) return cs.toString();
            } catch (Throwable ignored) {}
            return tok.substring(0, slash);
        }
        return tok == null ? "App" : tok;
    }

    public static void launchBarToken(Context ctx, String tok) {
        try {
            Intent target = null;
            if ("@browser".equals(tok)) target = new Intent(ctx, Arc7BrowserActivity.class);
            else if ("@calendar".equals(tok)) target = new Intent(ctx, Arc7CalendarActivity.class);
            else if ("@photos".equals(tok)) target = new Intent(ctx, Arc7PhotosActivity.class);
            else if ("@camera".equals(tok)) target = new Intent(ctx, Arc7CameraActivity.class);
            else if ("@vlc".equals(tok)) {
                target = ctx.getPackageManager().getLaunchIntentForPackage("org.videolan.vlc");
                if (target == null) target = new Intent(ctx, Arc7AudioPlayerActivity.class);
            } else if ("@settings".equals(tok)) target = new Intent(Settings.ACTION_SETTINGS);
            else if ("@files".equals(tok)) target = new Intent(ctx, Arc7FileManagerActivity.class);
            else if ("@notes".equals(tok)) target = new Intent(ctx, Arc7NotesActivity.class);
            else if ("@calculator".equals(tok)) target = new Intent(ctx, Arc7CalculatorActivity.class);
            else if ("@clock".equals(tok)) target = new Intent(ctx, Arc7ClockActivity.class);
            else if ("@ereader".equals(tok)) target = new Intent(ctx, Arc7EbookReaderActivity.class);
            else if ("@audio".equals(tok)) target = new Intent(ctx, Arc7AudioPlayerActivity.class);
            else if ("@terminal".equals(tok)) target = new Intent(ctx, Arc7TerminalActivity.class);
            else if ("@wired".equals(tok)) target = new Intent(ctx, Arc7WiredInternetActivity.class);
            else if ("@freedom".equals(tok)) target = new Intent(ctx, MainActivity.class);
            else if (tok != null && tok.indexOf('/') > 0) {
                int s = tok.indexOf('/');
                target = new Intent(Intent.ACTION_MAIN);
                target.addCategory(Intent.CATEGORY_LAUNCHER);
                target.setComponent(new ComponentName(tok.substring(0, s), tok.substring(s + 1)));
            }
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(target);
            }
        } catch (Throwable t) {
            Toast.makeText(ctx, "Unable to launch: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /* ==================== Full Cross-OS App Bar Customizer Dialog ==================== */

    private interface IntPick { void onPick(int which); }

    @SuppressWarnings("deprecation")
    public static void showAppBarCustomizerDialog(final Activity host, final Runnable onApplied) {
        final Palette pal = current(host);
        ScrollView sv = new ScrollView(host);
        final LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF0E1520);
        box.setPadding(dp(host, 14), dp(host, 12), dp(host, 14), dp(host, 14));
        sv.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(host)
                .setTitle("Customize App Bar (Home Dock & All Apps)")
                .setView(sv)
                .setPositiveButton("Apply & Refresh", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onApplied != null) onApplied.run();
                    }
                })
                .setNeutralButton("Reset Defaults", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        resetAppBarDefaults(host);
                        Toast.makeText(host, "Restored default App Bar settings!", Toast.LENGTH_SHORT).show();
                        if (onApplied != null) onApplied.run();
                    }
                })
                .setNegativeButton("Close", null)
                .create();

        final Runnable[] refreshUi = new Runnable[1];
        refreshUi[0] = new Runnable() {
            @Override
            public void run() {
                box.removeAllViews();
                final AppBarPrefs cfg = loadAppBarPrefs(host);
                final int accent = cfg.accentOverride != 0 ? cfg.accentOverride : pal.accent;

                addSecHdr(host, box, "1. IN-APP TOP APP BAR (ALL BUILT-IN ARC7 APPS)", accent);
                addPickRow(host, box, "App Bar Height", IN_APP_DENSITY_NAMES[cfg.inAppDensity],
                        IN_APP_DENSITY_NAMES, cfg.inAppDensity, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.inAppDensity = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });
                addPickRow(host, box, "Header Chrome Style", IN_APP_STYLE_NAMES[cfg.inAppStyle],
                        IN_APP_STYLE_NAMES, cfg.inAppStyle, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.inAppStyle = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });
                addCheckRow(host, box, "Center App Title in Top Bar", cfg.inAppCenterTitle,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.inAppCenterTitle = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });
                addCheckRow(host, box, "Show Quick 'Apps ▾' Switcher Pill in Every App", cfg.inAppShowQuickSwitch,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.inAppShowQuickSwitch = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });

                addSecHdr(host, box, "2. HOME SCREEN PINNED APP BAR SLOTS (" + cfg.items.size() + " / " + MAX_BAR_SLOTS + ")", accent);
                for (int i = 0; i < cfg.items.size(); i++) {
                    final int idx = i;
                    LinearLayout r = new LinearLayout(host);
                    r.setOrientation(LinearLayout.HORIZONTAL);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(dp(host, 9), dp(host, 6), dp(host, 9), dp(host, 6));
                    GradientDrawable rbg = new GradientDrawable();
                    rbg.setColor(0xFF172233);
                    rbg.setCornerRadius(dp(host, 8));
                    rbg.setStroke(dp(host, 1), 0xFF283A52);
                    r.setBackgroundDrawable(rbg);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = dp(host, 5);
                    r.setLayoutParams(rlp);

                    TextView lbl = new TextView(host);
                    lbl.setText((idx + 1) + ".  " + labelForBarToken(host, cfg.items.get(idx)));
                    lbl.setTextColor(Color.WHITE);
                    lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    lbl.setTypeface(Typeface.DEFAULT_BOLD);
                    r.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    r.addView(miniChip(host, "\u25B2", idx > 0 ? 0xFF243449 : 0x44243449, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (idx > 0) {
                                String it = cfg.items.remove(idx);
                                cfg.items.add(idx - 1, it);
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        }
                    }));
                    r.addView(miniChip(host, "\u25BC", idx < cfg.items.size() - 1 ? 0xFF243449 : 0x44243449, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (idx < cfg.items.size() - 1) {
                                String it = cfg.items.remove(idx);
                                cfg.items.add(idx + 1, it);
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        }
                    }));
                    r.addView(miniChip(host, "\u21C4", 0xFF1D4ED8, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showAddOrReplaceAppDialog(host, idx, refreshUi[0]);
                        }
                    }));
                    r.addView(miniChip(host, "\u2715", 0xFFB91C1C, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            cfg.items.remove(idx);
                            saveAppBarPrefs(host, cfg);
                            refreshUi[0].run();
                        }
                    }));
                    box.addView(r);
                }

                if (cfg.items.size() < MAX_BAR_SLOTS) {
                    Button addBtn = actionButton(host, "\uFF0B  Add App to App Bar", 0xFF0284C7);
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    alp.topMargin = dp(host, 3);
                    alp.bottomMargin = dp(host, 8);
                    addBtn.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showAddOrReplaceAppDialog(host, -1, refreshUi[0]);
                        }
                    });
                    box.addView(addBtn, alp);
                }

                addSecHdr(host, box, "3. HOME SCREEN APP BAR LAYOUT & STYLE", accent);
                addPickRow(host, box, "Home App Bar Position", HOME_POS_NAMES[cfg.position],
                        HOME_POS_NAMES, cfg.position, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.position = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });
                addPickRow(host, box, "Home App Bar Style", HOME_STYLE_NAMES[cfg.style],
                        HOME_STYLE_NAMES, cfg.style, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.style = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });
                addPickRow(host, box, "All-Apps (:::) Placement", HOME_DRAWER_NAMES[cfg.drawerBtnPos],
                        HOME_DRAWER_NAMES, cfg.drawerBtnPos, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.drawerBtnPos = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });
                addPickRow(host, box, "Dock Icon Size", HOME_ICON_SIZE_NAMES[cfg.iconSizeMode],
                        HOME_ICON_SIZE_NAMES, cfg.iconSizeMode, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.iconSizeMode = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });

                int opIdx = 0;
                for (int i = 0; i < OPACITY_PRESETS.length; i++) {
                    if (OPACITY_PRESETS[i] == cfg.opacityPct) opIdx = i;
                }
                addPickRow(host, box, "Glass Opacity", OPACITY_NAMES[opIdx],
                        OPACITY_NAMES, opIdx, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.opacityPct = OPACITY_PRESETS[which];
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });

                int accIdx = 0;
                for (int i = 0; i < ACCENT_SWATCHES.length; i++) {
                    if (ACCENT_SWATCHES[i] == cfg.accentOverride) accIdx = i;
                }
                addPickRow(host, box, "Accent Highlight Color", ACCENT_SWATCH_NAMES[accIdx],
                        ACCENT_SWATCH_NAMES, accIdx, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.accentOverride = ACCENT_SWATCHES[which];
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });

                addCheckRow(host, box, "Show App Text Labels on Home App Bar", cfg.showLabels,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.showLabels = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });

                addSecHdr(host, box, "4. HOME TOP STATUS & ACTION CAPSULE", accent);
                addPickRow(host, box, "Home Top Bar Style", HOME_TOP_BAR_NAMES[cfg.topBarMode],
                        HOME_TOP_BAR_NAMES, cfg.topBarMode, new IntPick() {
                            @Override public void onPick(int which) {
                                cfg.topBarMode = which;
                                saveAppBarPrefs(host, cfg);
                                refreshUi[0].run();
                            }
                        });
                addCheckRow(host, box, "Show Clock & Date on Home Top Bar", cfg.topShowClock,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.topShowClock = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });
                addCheckRow(host, box, "Show Quick Search Chip on Home Top Bar", cfg.topShowSearch,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.topShowSearch = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });
                addCheckRow(host, box, "Show 'App Bar' Chip on Home Top Bar", cfg.topShowAppBarBtn,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.topShowAppBarBtn = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });
                addCheckRow(host, box, "Show 'Skin' Chip on Home Top Bar", cfg.topShowSkinBtn,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.topShowSkinBtn = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });
                addCheckRow(host, box, "Show 'Wallpaper' Chip on Home Top Bar", cfg.topShowWallpaperBtn,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean ch) {
                                cfg.topShowWallpaperBtn = ch;
                                saveAppBarPrefs(host, cfg);
                            }
                        });
            }
        };
        refreshUi[0].run();
        dlg.show();
    }

    private static void showAddOrReplaceAppDialog(final Activity host, final int replaceIdx, final Runnable afterPick) {
        final List<String> names = new ArrayList<String>();
        final List<String> toks = new ArrayList<String>();
        String[] builtins = new String[]{
                "@browser", "@calendar", "@photos", "@camera", "@vlc",
                "@files", "@notes", "@calculator", "@clock", "@ereader",
                "@audio", "@wired", "@terminal", "@freedom", "@settings"
        };
        for (int i = 0; i < builtins.length; i++) {
            toks.add(builtins[i]);
            names.add("\u2605 " + labelForBarToken(host, builtins[i]));
        }
        try {
            PackageManager pm = host.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            Collections.sort(list, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    String la = String.valueOf(a.loadLabel(host.getPackageManager()));
                    String lb = String.valueOf(b.loadLabel(host.getPackageManager()));
                    return la.compareToIgnoreCase(lb);
                }
            });
            for (int i = 0; i < list.size(); i++) {
                ResolveInfo ri = list.get(i);
                String pkg = ri.activityInfo.packageName;
                if ("ca.arc7freedom.launcher".equals(pkg)) continue;
                toks.add(pkg + "/" + ri.activityInfo.name);
                names.add(ri.loadLabel(pm) + "  (" + pkg + ")");
            }
        } catch (Throwable ignored) {}

        new AlertDialog.Builder(host)
                .setTitle(replaceIdx >= 0 ? "Replace App Bar Slot #" + (replaceIdx + 1) : "Pin App to App Bar")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AppBarPrefs cfg = loadAppBarPrefs(host);
                        String chosen = toks.get(which);
                        if (replaceIdx >= 0 && replaceIdx < cfg.items.size()) {
                            cfg.items.set(replaceIdx, chosen);
                        } else if (!cfg.items.contains(chosen) && cfg.items.size() < MAX_BAR_SLOTS) {
                            cfg.items.add(chosen);
                        }
                        saveAppBarPrefs(host, cfg);
                        if (afterPick != null) afterPick.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void addSecHdr(Context ctx, LinearLayout p, String text, int accent) {
        TextView h = new TextView(ctx);
        h.setText(text);
        h.setTextColor(accent);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(ctx, 10), 0, dp(ctx, 6));
        p.addView(h);
    }

    @SuppressWarnings("deprecation")
    private static TextView miniChip(Context ctx, String label, int color, View.OnClickListener click) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, 7));
        b.setBackgroundDrawable(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 5);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    @SuppressWarnings("deprecation")
    private static void addPickRow(final Activity host, LinearLayout parent, final String label,
                                   String valText, final String[] opts, final int curIdx, final IntPick cb) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(host, 10), dp(host, 9), dp(host, 10), dp(host, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF162030);
        bg.setCornerRadius(dp(host, 8));
        bg.setStroke(dp(host, 1), 0xFF25354B);
        row.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(host, 6);
        row.setLayoutParams(lp);

        TextView l = new TextView(host);
        l.setText(label);
        l.setTextColor(0xFFE2E8F0);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(host);
        v.setText(valText + "  \u25BE");
        v.setTextColor(0xFF38BDF8);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(v);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new AlertDialog.Builder(host)
                        .setTitle(label)
                        .setSingleChoiceItems(opts, curIdx, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                cb.onPick(which);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        parent.addView(row);
    }

    private static void addCheckRow(Context ctx, LinearLayout p, String label, boolean checked,
                                    CompoundButton.OnCheckedChangeListener listener) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText("  " + label);
        cb.setChecked(checked);
        cb.setTextColor(0xFFE2E8F0);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        cb.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        cb.setOnCheckedChangeListener(listener);
        p.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /* ==================== Full System App Icon Pack Reskinner ==================== */

    public static int resolveReskinnedDrawableRes(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        String c = cls == null ? "" : cls.toLowerCase(Locale.US);

        if ("ca.arc7freedom.app".equals(p)) {
            if (c.contains("browser")) return R.drawable.ic_browser;
            if (c.contains("ebook")) return R.drawable.ic_ereader;
            if (c.contains("audio")) return R.drawable.ic_audio;
            if (c.contains("clock") || c.contains("alarm")) return R.drawable.ic_clock;
            if (c.contains("wallpaper")) return R.drawable.ic_wallpaper;
            if (c.contains("appmanager")) return R.drawable.ic_appmgr;
            if (c.contains("terminal")) return R.drawable.ic_terminal;
            if (c.contains("wired") || c.contains("chromebook")) return R.drawable.ic_wired_net;
            if (c.contains("calendar")) return R.drawable.ic_calendar;
            if (c.contains("calculator")) return R.drawable.ic_calculator;
            if (c.contains("notes")) return R.drawable.ic_notes;
            if (c.contains("file")) return R.drawable.ic_files;
            if (c.contains("photo")) return R.drawable.ic_photos;
            if (c.contains("camera")) return R.drawable.ic_camera;
            if (c.contains("installer")) return R.drawable.ic_apk_installer;
            return R.drawable.ic_launcher;
        }
        if ("ca.arc7freedom.launcher".equals(p)) return R.drawable.ic_launcher_home;
        if ("org.videolan.vlc".equals(p) || p.contains("videolan")) return R.drawable.ic_vlc;
        if ("com.aurora.store".equals(p) || p.contains("vending") || p.contains("aurora")) return R.drawable.ic_aurora;
        if (p.contains("fdroid")) return R.drawable.ic_fdroid;
        if (p.contains("settings")) return R.drawable.ic_sys_settings;
        if (p.contains("downloads")) return R.drawable.ic_sys_downloads;
        if (p.contains("packageinstaller") || c.contains("packageinstaller")) return R.drawable.ic_apk_installer;
        if (p.contains("camera") || c.contains("camera")) return R.drawable.ic_camera;
        if (p.contains("gallery") || p.contains("photos")) return R.drawable.ic_photos;
        if (p.contains("calendar")) return R.drawable.ic_calendar;
        if (p.contains("calculator")) return R.drawable.ic_calculator;
        if (p.contains("deskclock") || p.contains("alarmclock")) return R.drawable.ic_clock;
        if (p.contains("browser") || p.contains("chrome") || p.contains("mozilla")) return R.drawable.ic_browser;
        if (p.contains("email") || p.contains("gm")) return R.drawable.ic_sys_email;
        if (p.contains("contacts") || p.contains("people")) return R.drawable.ic_sys_contacts;
        if (p.contains("soundrecorder") || p.contains("recorder")) return R.drawable.ic_sys_recorder;
        if (p.contains("quicksearch") || p.contains("googlequicksearch")) return R.drawable.ic_sys_search;
        if (p.contains("music")) return R.drawable.ic_sys_music;
        if (p.contains("video")) return R.drawable.ic_sys_video;
        if (p.contains("filemanager") || p.contains("documentsui")) return R.drawable.ic_files;
        if (p.contains("kobo") || p.contains("koreader") || p.contains("reader")) return R.drawable.ic_ereader;
        if (p.contains("term")) return R.drawable.ic_terminal;
        return 0;
    }

    public static Drawable loadReskinnedAppIcon(Context ctx, String pkg, String cls, Drawable fallback) {
        int customRes = resolveReskinnedDrawableRes(pkg, cls);
        if (customRes != 0) {
            return ctx.getResources().getDrawable(customRes);
        }
        return frameArbitraryIconInSquircle(ctx, fallback);
    }

    public static Drawable frameArbitraryIconInSquircle(Context ctx, Drawable raw) {
        if (raw == null) return ctx.getResources().getDrawable(R.drawable.ic_appmgr);
        try {
            int sz = dp(ctx, 56);
            Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float m = sz * 0.055f;
            float rad = sz * 0.225f;
            RectF tile = new RectF(m, m, sz - m, sz - m);
            Palette pal = current(ctx);
            p.setShader(new LinearGradient(0, m, 0, sz - m,
                    pal.surfaceElevated | 0xFF000000, pal.surface | 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(tile, rad, rad, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, sz * 0.025f));
            p.setColor((pal.accent & 0x00FFFFFF) | 0xAA000000);
            canvas.drawRoundRect(tile, rad, rad, p);

            int pad = Math.round(sz * 0.19f);
            raw.setBounds(pad, pad, sz - pad, sz - pad);
            raw.draw(canvas);
            return new BitmapDrawable(ctx.getResources(), out);
        } catch (Throwable t) {
            return raw;
        }
    }
}
