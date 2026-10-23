package ca.arc7freedom.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.TypedValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Custom Arc7 OS Skin + Complete Default System App Icon Pack Reskinner inside
 * `VanillaAndroid4Home.apk` (`v50`).
 *
 * Replaces EVERY outdated 2012 stock Android 4.2 / Kobo icon on the Home Screen,
 * Glass Dock, and All-Apps Drawer with our high-DPI custom squircle icon pack AND
 * wraps any third-party store APK in a matching glass squircle tile!
 */
public final class Arc7LauncherSkin {

    private Arc7LauncherSkin() {}

    public static final String ACTION_SKIN_CHANGED = "ca.arc7freedom.action.SKIN_CHANGED";

    public static final class ThemeSpec {
        public final String id;
        public final String title;
        public final int topCapsuleColor;
        public final int dockTopColor;
        public final int dockBottomColor;
        public final int drawerBgColor;
        public final int accentColor;
        public final int borderColor;

        public ThemeSpec(String id, String title, int topCapsuleColor,
                         int dockTopColor, int dockBottomColor, int drawerBgColor,
                         int accentColor, int borderColor) {
            this.id = id;
            this.title = title;
            this.topCapsuleColor = topCapsuleColor;
            this.dockTopColor = dockTopColor;
            this.dockBottomColor = dockBottomColor;
            this.drawerBgColor = drawerBgColor;
            this.accentColor = accentColor;
            this.borderColor = borderColor;
        }
    }

    public static final ThemeSpec[] THEMES = new ThemeSpec[]{
            new ThemeSpec("obsidian_cyan", "Obsidian Cyan",
                    0x9A0B1523, 0xCC111E30, 0xE609111C, 0xF00A1019, 0xFF22D3EE, 0x6638BDF8),
            new ThemeSpec("kobo_warm_ink", "Kobo Paper & Warm Ink",
                    0xAA241C16, 0xD62A211A, 0xEA171310, 0xF216120F, 0xFFF59E0B, 0x66FBBF24),
            new ThemeSpec("emerald_matrix", "Emerald Matrix Carbon",
                    0xAA0A2218, 0xD40F291E, 0xEA071610, 0xF207140E, 0xFF10B981, 0x6634D399),
            new ThemeSpec("royal_amethyst", "Royal Amethyst Nebula",
                    0xAA201238, 0xD6261542, 0xEA130A24, 0xF2120921, 0xFFC084FC, 0x66D8B4FE),
            new ThemeSpec("nordic_frost", "Nordic Daylight Frost",
                    0xBB0F172A, 0xCC1E293B, 0xDD0F172A, 0xF0111827, 0xFF60A5FA, 0x7793C5FD)
    };

    private static final Map<String, Drawable> ICON_CACHE = new HashMap<String, Drawable>();

    public static void clearCache() {
        ICON_CACHE.clear();
    }

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
        return ctx.getSharedPreferences("arc7_launcher_skin", Context.MODE_PRIVATE)
                .getString("skin_id", "obsidian_cyan");
    }

    public static ThemeSpec current(Context ctx) {
        String id = currentSkinId(ctx);
        for (int i = 0; i < THEMES.length; i++) {
            if (THEMES[i].id.equals(id)) return THEMES[i];
        }
        return THEMES[0];
    }

    public static void setSkin(Context ctx, String id) {
        ctx.getSharedPreferences("arc7_launcher_skin", Context.MODE_PRIVATE)
                .edit().putString("skin_id", id).commit();
        try {
            File f = sharedSkinFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("skin_id", id);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                p.store(fos, "Kobo Arc 7 Custom OS Skin");
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            f.setReadable(true, false);
        } catch (Throwable ignored) {}
        clearCache();
    }

    public static int resolveCustomIconRes(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        String c = cls == null ? "" : cls.toLowerCase(Locale.US);

        if ("ca.arc7freedom.app".equals(p)) {
            if (c.contains("youtubemusic")) return R.drawable.ic_ytmusic;
            if (c.contains("youtube")) return R.drawable.ic_youtube;
            if (c.contains("pdf")) return R.drawable.ic_pdf;
            if (c.contains("weather")) return R.drawable.ic_weather;
            if (c.contains("radio") || c.contains("rss")) return R.drawable.ic_radio;
            if (c.contains("recorder")) return R.drawable.ic_recorder;
            if (c.contains("arcade")) return R.drawable.ic_arcade;
            if (c.contains("streamdeck")) return R.drawable.ic_streamdeck;
            if (c.contains("vpn")) return R.drawable.ic_vpn;
            if (c.contains("shield")) return R.drawable.ic_shield;
            if (c.contains("boost")) return R.drawable.ic_boost;
            if (c.contains("secondscreen")) return R.drawable.ic_display;
            if (c.contains("splitview")) return R.drawable.ic_splitview;
            if (c.contains("dictionary") || c.contains("wiki")) return R.drawable.ic_wiki;
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
        if ("ca.arc7freedom.youtube".equals(p)) return R.drawable.ic_youtube;
        if ("ca.arc7freedom.ytmusic".equals(p)) return R.drawable.ic_ytmusic;
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

    public static Drawable reskinAppIcon(Context ctx, String pkg, String cls, Drawable systemFallback) {
        String key = currentSkinId(ctx) + ":" + pkg + "/" + cls;
        Drawable cached = ICON_CACHE.get(key);
        if (cached != null) return cached;

        int resId = resolveCustomIconRes(pkg, cls);
        if (resId != 0) {
            Drawable d = ctx.getResources().getDrawable(resId);
            ICON_CACHE.put(key, d);
            return d;
        }

        // Frame any third-party app icon inside a matching high-DPI glass squircle tile!
        try {
            int sz = Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 56, ctx.getResources().getDisplayMetrics()));
            Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ThemeSpec t = current(ctx);
            float m = sz * 0.055f;
            float rad = sz * 0.225f;
            RectF rect = new RectF(m, m, sz - m, sz - m);
            paint.setShader(new LinearGradient(0, m, 0, sz - m,
                    (t.dockTopColor | 0xFF000000), (t.dockBottomColor | 0xFF000000), Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, rad, rad, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, sz * 0.025f));
            paint.setColor(t.borderColor | 0xAA000000);
            c.drawRoundRect(rect, rad, rad, paint);
            if (systemFallback != null) {
                int pad = Math.round(sz * 0.19f);
                systemFallback.setBounds(pad, pad, sz - pad, sz - pad);
                systemFallback.draw(c);
            }
            Drawable framed = new BitmapDrawable(ctx.getResources(), bmp);
            ICON_CACHE.put(key, framed);
            return framed;
        } catch (Throwable t) {
            return systemFallback;
        }
    }
}
