package ca.arc7freedom.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Persistent, Cross-Process App Bar Configuration Engine (`v2.1.0`).
 *
 * Synchronizes user customizations across BOTH:
 *   1. `VanillaAndroid4Home.apk` (`ca.arc7freedom.launcher`) — Home Screen App Bar (Bottom / Top /
 *      Left Rail / Right Rail / Hidden), Pinned App Slots (add, remove, reorder, replace, up to 12
 *      apps with auto-scroll), All-Apps Drawer Jewel position, Visual Style, Icon Size, App Labels,
 *      Custom Opacity & Accent Color, and Home Top Capsule Bar widgets/chips.
 *   2. `KoboArc7Freedom.apk` (`ca.arc7freedom.app`) — System-Wide In-App Top App Bar density,
 *      title alignment, header surface style, and live `Apps` quick-switcher ribbon via shared
 *      `/sdcard/Arc7Freedom/appbar.properties` + `ca.arc7freedom.action.APP_BAR_CHANGED`.
 */
public final class AppBarConfigStore {

    private AppBarConfigStore() {}

    public static final String ACTION_APP_BAR_CHANGED = "ca.arc7freedom.action.APP_BAR_CHANGED";
    public static final String PREFS = "arc7_app_bar_prefs";
    public static final int MAX_SLOTS = 12;

    // Home App Bar Screen Edge Position
    public static final int POS_BOTTOM = 0;
    public static final int POS_TOP    = 1;
    public static final int POS_LEFT   = 2;
    public static final int POS_RIGHT  = 3;
    public static final int POS_HIDDEN = 4;

    public static final String[] POSITION_NAMES = new String[]{
            "Bottom Dock (Default)",
            "Top App Bar",
            "Left Side Rail (Best for Landscape)",
            "Right Side Rail",
            "Hidden (Swipe Up for All Apps)"
    };

    // All-Apps Drawer Button Placement inside App Bar
    public static final int DRAWER_CENTER = 0;
    public static final int DRAWER_START  = 1;
    public static final int DRAWER_END    = 2;
    public static final int DRAWER_HIDDEN = 3;

    public static final String[] DRAWER_BTN_NAMES = new String[]{
            "Center Jewel (:::)",
            "Start Edge (Left / Top)",
            "End Edge (Right / Bottom)",
            "Hidden (Swipe Up on Home)"
    };

    // Home App Bar Visual Chrome Style
    public static final int STYLE_FLOATING_PILL = 0;
    public static final int STYLE_FULL_BLEED    = 1;
    public static final int STYLE_MINIMAL_CLEAR = 2;
    public static final int STYLE_SOLID_CHASSIS = 3;
    public static final int STYLE_NEON_ACCENT   = 4;

    public static final String[] STYLE_NAMES = new String[]{
            "Floating Glass Pill",
            "Full-Width Edge-to-Edge Bar",
            "Minimal Transparent Icons",
            "Solid Matte Slate Bar",
            "Glowing Neon Accent Rim"
    };

    // Home App Bar Icon Sizes
    public static final int ICON_COMPACT  = 0;
    public static final int ICON_STANDARD = 1;
    public static final int ICON_LARGE    = 2;
    public static final int ICON_XL       = 3;

    public static final String[] ICON_SIZE_NAMES = new String[]{
            "Compact (34 dp — fits more apps)",
            "Standard (42 dp — balanced)",
            "Large (50 dp — tablet touch)",
            "Extra-Large (58 dp — bold showcase)"
    };

    // Home Top Capsule Bar Modes
    public static final int TOP_GLASS_CAPSULE = 0;
    public static final int TOP_MINIMAL_CLEAR = 1;
    public static final int TOP_COMPACT       = 2;
    public static final int TOP_HIDDEN        = 3;

    public static final String[] TOP_BAR_NAMES = new String[]{
            "Frosted Glass Capsule (Default)",
            "Minimal Floating Clock & Chips",
            "Compact Slim Bar",
            "Hidden (Maximum Wallpaper Space)"
    };

    // In-App Top App Bar Options (synced across all Arc7 built-in apps)
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

    // Optional Accent Color Overrides (`0` = follow active Arc7 OS Skin automatically)
    public static final int[] ACCENT_SWATCHES = new int[]{
            0,
            0xFF22D3EE,
            0xFF10B981,
            0xFFF59E0B,
            0xFFC084FC,
            0xFFF43F5E,
            0xFFE2E8F0
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

    public static final List<String> DEFAULT_ITEMS = Arrays.asList(
            "@browser",
            "@calendar",
            "@photos",
            "@camera",
            "@vlc",
            "@settings"
    );

    public static final class Config {
        public final List<String> items = new ArrayList<String>();
        public int position = POS_BOTTOM;
        public int drawerBtnPos = DRAWER_CENTER;
        public int style = STYLE_FLOATING_PILL;
        public int iconSizeMode = ICON_STANDARD;
        public boolean showLabels = true;
        public int opacityPct = -1;
        public int accentOverride = 0;

        // Home Top Status / Action Bar
        public int topBarMode = TOP_GLASS_CAPSULE;
        public boolean topShowClock = true;
        public boolean topShowSearch = true;
        public boolean topShowAppBarBtn = true;
        public boolean topShowSkinBtn = true;
        public boolean topShowWallpaperBtn = true;

        // System-wide In-App Top App Bar (used by Arc7Skin across all built-in apps)
        public int inAppDensity = 1;
        public boolean inAppCenterTitle = false;
        public int inAppStyle = 0;
        public boolean inAppShowQuickSwitch = true;

        public int iconSizeDp() {
            switch (iconSizeMode) {
                case ICON_COMPACT: return 34;
                case ICON_LARGE:   return 50;
                case ICON_XL:      return 58;
                default:           return 42;
            }
        }

        public boolean isVertical() {
            return position == POS_LEFT || position == POS_RIGHT;
        }
    }

    public static File sharedConfigFile() {
        String prop = System.getProperty("arc7.test.appbar.file");
        if (prop != null && prop.length() > 0) return new File(prop);
        File ext = null;
        try { ext = Environment.getExternalStorageDirectory(); } catch (Throwable ignored) {}
        return ext != null
                ? new File(ext, "Arc7Freedom/appbar.properties")
                : new File("/sdcard/Arc7Freedom/appbar.properties");
    }

    public static Config load(Context ctx) {
        Config c = new Config();
        Properties p = null;
        try {
            File f = sharedConfigFile();
            if (f.isFile() && f.canRead()) {
                p = new Properties();
                FileInputStream fis = new FileInputStream(f);
                try {
                    p.load(fis);
                } finally {
                    try { fis.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
            p = null;
        }

        SharedPreferences sp = ctx != null
                ? ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                : null;

        String rawItems = readStr(p, sp, "items", null);
        if (rawItems == null) {
            c.items.addAll(DEFAULT_ITEMS);
        } else if (rawItems.trim().length() > 0) {
            String[] parts = rawItems.split("\\|");
            for (int i = 0; i < parts.length && c.items.size() < MAX_SLOTS; i++) {
                String tok = parts[i].trim();
                if (tok.length() > 0 && !c.items.contains(tok)) {
                    c.items.add(tok);
                }
            }
        }

        c.position = clamp(readInt(p, sp, "position", POS_BOTTOM), 0, POSITION_NAMES.length - 1);
        c.drawerBtnPos = clamp(readInt(p, sp, "drawer_btn_pos", DRAWER_CENTER), 0, DRAWER_BTN_NAMES.length - 1);
        c.style = clamp(readInt(p, sp, "style", STYLE_FLOATING_PILL), 0, STYLE_NAMES.length - 1);
        c.iconSizeMode = clamp(readInt(p, sp, "icon_size_mode", ICON_STANDARD), 0, ICON_SIZE_NAMES.length - 1);
        c.showLabels = readBool(p, sp, "show_labels", true);
        c.opacityPct = readInt(p, sp, "opacity_pct", -1);
        c.accentOverride = readInt(p, sp, "accent_override", 0);

        c.topBarMode = clamp(readInt(p, sp, "top_bar_mode", TOP_GLASS_CAPSULE), 0, TOP_BAR_NAMES.length - 1);
        c.topShowClock = readBool(p, sp, "top_show_clock", true);
        c.topShowSearch = readBool(p, sp, "top_show_search", true);
        c.topShowAppBarBtn = readBool(p, sp, "top_show_appbar_btn", true);
        c.topShowSkinBtn = readBool(p, sp, "top_show_skin_btn", true);
        c.topShowWallpaperBtn = readBool(p, sp, "top_show_wallpaper_btn", true);

        c.inAppDensity = clamp(readInt(p, sp, "in_app_density", 1), 0, IN_APP_DENSITY_NAMES.length - 1);
        c.inAppCenterTitle = readBool(p, sp, "in_app_center_title", false);
        c.inAppStyle = clamp(readInt(p, sp, "in_app_style", 0), 0, IN_APP_STYLE_NAMES.length - 1);
        c.inAppShowQuickSwitch = readBool(p, sp, "in_app_quick_switch", true);
        return c;
    }

    public static void save(Context ctx, Config c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.items.size() && i < MAX_SLOTS; i++) {
            if (i > 0) sb.append('|');
            sb.append(c.items.get(i));
        }
        String serializedItems = sb.toString();

        if (ctx != null) {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.putString("items", serializedItems);
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
            File f = sharedConfigFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("items", serializedItems);
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
            try {
                p.store(fos, "Kobo Arc 7 Customizable App Bar Configuration");
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            f.setReadable(true, false);
        } catch (Throwable ignored) {}

        if (ctx != null) {
            try {
                ctx.sendBroadcast(new Intent(ACTION_APP_BAR_CHANGED));
                ctx.sendBroadcast(new Intent(Arc7LauncherSkin.ACTION_SKIN_CHANGED));
            } catch (Throwable ignored) {}
        }
    }

    public static Config resetDefaults(Context ctx) {
        Config def = new Config();
        def.items.addAll(DEFAULT_ITEMS);
        save(ctx, def);
        return def;
    }

    public static boolean containsComponent(Config c, String pkg, String cls) {
        String key = pkg + "/" + cls;
        if (c.items.contains(key)) return true;
        String alias = builtinTokenForComponent(pkg, cls);
        return alias != null && c.items.contains(alias);
    }

    public static boolean addOrPinComponent(Context ctx, String pkg, String cls) {
        Config c = load(ctx);
        if (containsComponent(c, pkg, cls) || c.items.size() >= MAX_SLOTS) return false;
        c.items.add(pkg + "/" + cls);
        save(ctx, c);
        return true;
    }

    public static boolean removeComponent(Context ctx, String pkg, String cls) {
        Config c = load(ctx);
        String key = pkg + "/" + cls;
        boolean removed = c.items.remove(key);
        String alias = builtinTokenForComponent(pkg, cls);
        if (alias != null) {
            removed = c.items.remove(alias) || removed;
        }
        if (removed) save(ctx, c);
        return removed;
    }

    public static void moveSlot(Context ctx, int index, int delta) {
        Config c = load(ctx);
        int target = index + delta;
        if (index < 0 || index >= c.items.size() || target < 0 || target >= c.items.size()) return;
        String item = c.items.remove(index);
        c.items.add(target, item);
        save(ctx, c);
    }

    public static String builtinTokenForComponent(String pkg, String cls) {
        if (pkg == null) return null;
        String c = cls == null ? "" : cls;
        if ("ca.arc7freedom.app".equals(pkg)) {
            if (c.endsWith("Arc7BrowserActivity")) return "@browser";
            if (c.endsWith("Arc7CalendarActivity")) return "@calendar";
            if (c.endsWith("Arc7PhotosActivity")) return "@photos";
            if (c.endsWith("Arc7CameraActivity")) return "@camera";
            if (c.endsWith("Arc7FilesActivity") || c.endsWith("Arc7FileManagerActivity")) return "@files";
            if (c.endsWith("Arc7NotesActivity")) return "@notes";
            if (c.endsWith("Arc7CalculatorActivity")) return "@calculator";
            if (c.endsWith("Arc7ClockActivity")) return "@clock";
            if (c.endsWith("Arc7EbookReaderActivity")) return "@ereader";
            if (c.endsWith("Arc7AudioPlayerActivity")) return "@audio";
            if (c.endsWith("Arc7TerminalActivity")) return "@terminal";
            if (c.endsWith("Arc7WiredInternetActivity")) return "@wired";
            if (c.endsWith("MainActivity")) return "@freedom";
        }
        if ("org.videolan.vlc".equals(pkg)) return "@vlc";
        if ("com.android.settings".equals(pkg)) return "@settings";
        return null;
    }

    public static int applyOpacityOverride(int argb, int opacityPct) {
        if (opacityPct < 0) return argb;
        int alpha = Math.max(0, Math.min(255, Math.round(opacityPct * 255f / 100f)));
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String readStr(Properties p, SharedPreferences sp, String key, String def) {
        if (p != null && p.containsKey(key)) return p.getProperty(key);
        if (sp != null && sp.contains(key)) return sp.getString(key, def);
        return def;
    }

    private static int readInt(Properties p, SharedPreferences sp, String key, int def) {
        if (p != null && p.containsKey(key)) {
            try { return Integer.parseInt(p.getProperty(key).trim()); } catch (Throwable ignored) {}
        }
        if (sp != null && sp.contains(key)) {
            try { return sp.getInt(key, def); } catch (Throwable ignored) {}
        }
        return def;
    }

    private static boolean readBool(Properties p, SharedPreferences sp, String key, boolean def) {
        if (p != null && p.containsKey(key)) {
            return Boolean.parseBoolean(p.getProperty(key).trim());
        }
        if (sp != null && sp.contains(key)) {
            return sp.getBoolean(key, def);
        }
        return def;
    }
}
