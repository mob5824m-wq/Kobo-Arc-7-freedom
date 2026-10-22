package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Unified Kobo Arc 7 OS Custom Skin & Full Default System App Icon Reskinning Engine (`v2.0.0`).
 *
 * Synchronizes seamlessly across `KoboArc7Freedom.apk` (`ca.arc7freedom.app`),
 * `VanillaAndroid4Home.apk` (`ca.arc7freedom.launcher`), and all 4 Home Screen Widgets via
 * shared `/sdcard/Arc7Freedom/skin.properties` + `ca.arc7freedom.action.SKIN_CHANGED`!
 */
public final class Arc7Skin {

    private Arc7Skin() {}

    public static final String ACTION_SKIN_CHANGED = "ca.arc7freedom.action.SKIN_CHANGED";
    public static final String PREFS = "arc7_skin_prefs";
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

        // Notify Launcher + refresh all 4 Home Screen AppWidgets immediately!
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
                .setNeutralButton("Apply Skin Only", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Palette p = ALL_PALETTES[picked[0]];
                        applySkinSelection(host, p.id, false);
                        Toast.makeText(host, "Applied " + p.name + "!", Toast.LENGTH_SHORT).show();
                        if (onApplied != null) onApplied.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ==================== Reusable Skinned UI Builders ==================== */

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

    public static GradientDrawable buttonDrawable(Context ctx, int fillColor, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setStroke(dp(ctx, 1), 0x44FFFFFF);
        return g;
    }

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

    public static LinearLayout buildAppTopBar(final Activity activity, Palette p, int iconResId,
                                              String titleText, String subtitleText, View rightExtra) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundDrawable(headerDrawable(p));
        bar.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconResId);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36));
        ilp.rightMargin = dp(activity, 11);
        bar.addView(icon, ilp);

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(activity);
        t.setText(titleText);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        titleCol.addView(t);

        if (subtitleText != null && subtitleText.length() > 0) {
            TextView sub = new TextView(activity);
            sub.setText(subtitleText);
            sub.setTextColor(0xCCDBEAFE);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            titleCol.addView(sub);
        }

        bar.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (rightExtra != null) {
            bar.addView(rightExtra);
        }
        return bar;
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
