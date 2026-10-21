package ca.arc7freedom.app;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import java.io.File;
import java.util.Random;

/**
 * 12 Crisp Zero-Banding Procedural HD Wallpapers (`1280x800` for Kobo Arc 7 `1024x600` & Arc 7HD
 * `1920x1200`) + MicroSD/Internal Custom Photo scaler & applier!
 */
public class Arc7WallpaperCatalog {

    public static final String[] TITLES = new String[]{
            "1. Holo Phase Beam (Android 4.2 Classic)",
            "2. Northern Aurora Borealis",
            "3. Misty Blue Ridge Mountains",
            "4. Nexus Jelly Bean Silk Ribbons",
            "5. Pacific Ocean Coral Dusk",
            "6. Outrun Neon Horizon Grid",
            "7. Kobo Zen Sumi-e Ink Wash (Daylight)",
            "8. Deep Cosmos & Spiral Nebula",
            "9. Emerald Forest Sunbeams",
            "10. Sahara Golden Twilight Dunes",
            "11. Low-Poly Sapphire Crystal",
            "12. Battery-Saver Pure Obsidian Glow"
    };

    public static final String[] SUBTITLES = new String[]{
            "Deep sapphire gradient + glowing cyan/violet bokeh beams",
            "Arctic starfield + emerald-teal northern lights + dark pines",
            "Atmospheric twilight sky + 5 layered misty mountain ridges",
            "Vibrant crimson, amber & magenta silk waves over dark slate",
            "Warm golden-coral horizon sun + calm layered ocean swells",
            "Retro sunset scanline orb + glowing cyan perspective grid",
            "Warm rice-paper sumi-e mountains — effortless daytime readability",
            "Rich violet-teal nebula cloud + 240 crisp starlight points",
            "Woodland canopy + diagonal warm sunbeams + layered ferns",
            "Starlit indigo sky + sweeping terracotta & gold sand dunes",
            "Crisp faceted geometric tessellation in cobalt, teal & slate",
            "Ultra-clean minimal dark canvas with subtle cyan horizon line"
    };

    public static Bitmap renderTheme(int index, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int idx = ((index % TITLES.length) + TITLES.length) % TITLES.length;

        switch (idx) {
            case 0: // Holo Phase Beam
                fillGrad(c, w, h, new int[]{0xFF04091A, 0xFF0B224C, 0xFF194B7E, 0xFF071226});
                glowOrb(c, w * 0.28f, h * 0.62f, w * 0.36f, 0x6633B5E5);
                glowOrb(c, w * 0.72f, h * 0.34f, w * 0.30f, 0x55AA66CC);
                diagonalBeam(c, w, h, 0.15f, 0.55f, 0x2638E2FF);
                diagonalBeam(c, w, h, 0.45f, 0.85f, 0x1EFFFFFF);
                scatterBokeh(c, w, h, 101L, 18, 0x2864D8FF);
                break;

            case 1: // Northern Aurora Borealis
                fillGrad(c, w, h, new int[]{0xFF020712, 0xFF071C2C, 0xFF0C3640, 0xFF040C14});
                scatterStars(c, w, h, 202L, 180, 0.65f);
                auroraCurtain(c, w, h, 0.22f, 0x552AF598, 7.0f, 0.0f);
                auroraCurtain(c, w, h, 0.34f, 0x44009EFD, 5.0f, 1.8f);
                auroraCurtain(c, w, h, 0.44f, 0x33B721FF, 4.0f, 3.1f);
                drawMountainLayer(c, w, h, 0.80f, 0.07f, 21L, 0xFF08151F);
                drawPineLine(c, w, h, 0.90f, 0xFF03090E);
                break;

            case 2: // Misty Blue Ridge
                fillGrad(c, w, h, new int[]{0xFF1A2A4A, 0xFF355C7D, 0xFF6C5B7B, 0xFFC06C84});
                glowOrb(c, w * 0.76f, h * 0.24f, w * 0.08f, 0xEEFFF3CD);
                drawMountainLayer(c, w, h, 0.48f, 0.11f, 31L, 0x88425E7C);
                drawMountainLayer(c, w, h, 0.58f, 0.10f, 47L, 0xAA2F4766);
                drawMountainLayer(c, w, h, 0.69f, 0.09f, 63L, 0xDD1F324B);
                drawMountainLayer(c, w, h, 0.80f, 0.07f, 79L, 0xFF121F31);
                break;

            case 3: // Nexus Jelly Bean Ribbons
                fillGrad(c, w, h, new int[]{0xFF0D0E15, 0xFF171926, 0xFF0E1019});
                silkWave(c, w, h, 0.36f, 0.14f, 1.2f, 0x99E53935, 0x22FF7043);
                silkWave(c, w, h, 0.50f, 0.16f, 2.5f, 0x88FB8C00, 0x22FFD54F);
                silkWave(c, w, h, 0.64f, 0.13f, 4.1f, 0x888E24AA, 0x2200ACC1);
                break;

            case 4: // Pacific Ocean Coral Dusk
                fillGrad(c, w, h, new int[]{0xFF1D2B64, 0xFF7B4397, 0xFFDC2430, 0xFFF8B500});
                glowOrb(c, w * 0.50f, h * 0.56f, w * 0.14f, 0xDDFFE082);
                silkWave(c, w, h, 0.62f, 0.030f, 0.7f, 0xEE183B59, 0xFF0B1D30);
                silkWave(c, w, h, 0.72f, 0.035f, 2.2f, 0xF2102A43, 0xFF071626);
                silkWave(c, w, h, 0.83f, 0.040f, 3.9f, 0xFF091B2E, 0xFF040D17);
                break;

            case 5: // Outrun Neon Horizon Grid
                fillGrad(c, w, h, new int[]{0xFF09021C, 0xFF240B4C, 0xFF6B0F6C, 0xFF120428});
                scatterStars(c, w, h, 505L, 120, 0.52f);
                drawRetroSun(c, w * 0.50f, h * 0.44f, h * 0.22f);
                p.setShader(null);
                p.setColor(0xFF0B0418);
                c.drawRect(0, h * 0.56f, w, h, p);
                drawPerspectiveGrid(c, w, h, 0.56f, 0xAA00F5FF, 0x66FF2A85);
                break;

            case 6: // Kobo Zen Sumi-e Ink Wash
                fillGrad(c, w, h, new int[]{0xFFF4EFE6, 0xFFEAE2D3, 0xFFDCD2C0});
                glowOrb(c, w * 0.74f, h * 0.26f, h * 0.11f, 0xCCCF3A2B);
                drawMountainLayer(c, w, h, 0.52f, 0.10f, 111L, 0x4458646D);
                drawMountainLayer(c, w, h, 0.65f, 0.09f, 222L, 0x773C464F);
                drawMountainLayer(c, w, h, 0.78f, 0.08f, 333L, 0xBB232A30);
                break;

            case 7: // Deep Cosmos & Spiral Nebula
                fillGrad(c, w, h, new int[]{0xFF02030A, 0xFF080D22, 0xFF050713});
                glowOrb(c, w * 0.42f, h * 0.46f, w * 0.34f, 0x555E35B1);
                glowOrb(c, w * 0.60f, h * 0.52f, w * 0.28f, 0x4400ACC1);
                glowOrb(c, w * 0.50f, h * 0.48f, w * 0.12f, 0x44FF80AB);
                scatterStars(c, w, h, 808L, 260, 1.0f);
                break;

            case 8: // Emerald Forest Sunbeams
                fillGrad(c, w, h, new int[]{0xFF0B261C, 0xFF154734, 0xFF091E16});
                diagonalBeam(c, w, h, 0.08f, 0.38f, 0x30FFE082);
                diagonalBeam(c, w, h, 0.32f, 0.66f, 0x26FFF59D);
                diagonalBeam(c, w, h, 0.58f, 0.90f, 0x1EFFE082);
                drawMountainLayer(c, w, h, 0.68f, 0.07f, 88L, 0xAA0C2B1E);
                drawPineLine(c, w, h, 0.84f, 0xFF06160F);
                break;

            case 9: // Sahara Golden Twilight Dunes
                fillGrad(c, w, h, new int[]{0xFF0F172A, 0xFF312E81, 0xFF9A3412, 0xFFF59E0B});
                scatterStars(c, w, h, 909L, 95, 0.48f);
                silkWave(c, w, h, 0.60f, 0.06f, 1.1f, 0xFFD97706, 0xFF92400E);
                silkWave(c, w, h, 0.73f, 0.07f, 2.9f, 0xFFB45309, 0xFF78350F);
                silkWave(c, w, h, 0.85f, 0.05f, 4.4f, 0xFF78350F, 0xFF451A03);
                break;

            case 10: // Low-Poly Sapphire Crystal
                drawLowPoly(c, w, h, 1010L);
                break;

            default: // 11: Battery-Saver Pure Obsidian Glow
                fillGrad(c, w, h, new int[]{0xFF020408, 0xFF060C16, 0xFF020408});
                glowOrb(c, w * 0.50f, h * 0.88f, w * 0.42f, 0x3300ACC1);
                p.setShader(null);
                p.setColor(0x6633B5E5);
                p.setStrokeWidth(Math.max(2f, h * 0.003f));
                c.drawLine(w * 0.12f, h * 0.84f, w * 0.88f, h * 0.84f, p);
                break;
        }
        return bmp;
    }

    public static void applyBuiltInTheme(Context ctx, int index) throws Exception {
        WallpaperManager wm = WallpaperManager.getInstance(ctx);
        int dw = Math.max(1280, wm.getDesiredMinimumWidth());
        int dh = Math.max(800, wm.getDesiredMinimumHeight());
        dw = Math.min(dw, 1600);
        dh = Math.min(dh, 1000);
        Bitmap bmp = renderTheme(index, dw, dh);
        wm.setBitmap(bmp);
    }

    public static void applyPhotoFile(Context ctx, File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1200) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (decoded == null) {
            throw new IllegalArgumentException("Could not decode image: " + file.getName());
        }
        WallpaperManager.getInstance(ctx).setBitmap(decoded);
    }

    /* ==================== PROCEDURAL VECTOR HELPERS ==================== */

    private static void fillGrad(Canvas c, int w, int h, int[] colors) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, colors, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    private static void glowOrb(Canvas c, float cx, float cy, float r, int centerColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int zero = centerColor & 0x00FFFFFF;
        p.setShader(new RadialGradient(cx, cy, Math.max(4f, r),
                new int[]{centerColor, zero}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    private static void diagonalBeam(Canvas c, int w, int h, float xStartFrac, float xEndFrac, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        Path path = new Path();
        path.moveTo(w * xStartFrac, 0);
        path.lineTo(w * (xStartFrac + 0.16f), 0);
        path.lineTo(w * (xEndFrac + 0.22f), h);
        path.lineTo(w * xEndFrac, h);
        path.close();
        c.drawPath(path, p);
    }

    private static void scatterBokeh(Canvas c, int w, int h, long seed, int count, int color) {
        Random rnd = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        for (int i = 0; i < count; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float r = (0.015f + rnd.nextFloat() * 0.055f) * h;
            c.drawCircle(x, y, r, p);
        }
    }

    private static void scatterStars(Canvas c, int w, int h, long seed, int count, float maxYFrac) {
        Random rnd = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < count; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h * maxYFrac;
            float r = 0.7f + rnd.nextFloat() * 1.9f;
            int alpha = 100 + rnd.nextInt(156);
            p.setColor(Color.argb(alpha, 240, 248, 255));
            c.drawCircle(x, y, r, p);
        }
    }

    private static void auroraCurtain(Canvas c, int w, int h, float yCenterFrac, int color, float freq, float phase) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int steps = 96;
        float barW = (w / (float) steps) + 2f;
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            float wave = (float) Math.sin(t * freq + phase) * h * 0.07f;
            float top = h * yCenterFrac + wave - h * 0.12f;
            float bot = top + h * 0.24f;
            p.setShader(new LinearGradient(0, top, 0, bot,
                    new int[]{color & 0x00FFFFFF, color, color & 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(t * w, top, t * w + barW, bot, p);
        }
    }

    private static void drawMountainLayer(Canvas c, int w, int h, float baseFrac, float ampFrac, long seed, int color) {
        Random rnd = new Random(seed);
        Path path = new Path();
        path.moveTo(0, h);
        int segs = 26;
        float curY = h * baseFrac;
        path.lineTo(0, curY);
        for (int i = 1; i <= segs; i++) {
            float x = (w * i) / (float) segs;
            float delta = (rnd.nextFloat() - 0.5f) * 2f * h * ampFrac;
            curY = Math.max(h * (baseFrac - ampFrac * 1.6f),
                    Math.min(h * (baseFrac + ampFrac * 1.2f), curY + delta));
            path.lineTo(x, curY);
        }
        path.lineTo(w, h);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private static void drawPineLine(Canvas c, int w, int h, float baseYFrac, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawRect(0, h * baseYFrac, w, h, p);
        Random rnd = new Random(77L);
        int trees = 44;
        for (int i = 0; i <= trees; i++) {
            float cx = (w * i) / (float) trees;
            float th = h * (0.06f + rnd.nextFloat() * 0.08f);
            float tw = w * 0.022f;
            Path t = new Path();
            t.moveTo(cx, h * baseYFrac - th);
            t.lineTo(cx - tw, h * baseYFrac + 4f);
            t.lineTo(cx + tw, h * baseYFrac + 4f);
            t.close();
            c.drawPath(t, p);
        }
    }

    private static void silkWave(Canvas c, int w, int h, float yFrac, float ampFrac, float phase, int topCol, int botCol) {
        Path path = new Path();
        path.moveTo(0, h);
        int steps = 80;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float y = h * yFrac
                    + (float) Math.sin(t * 5.2f + phase) * h * ampFrac
                    + (float) Math.cos(t * 2.7f - phase) * h * ampFrac * 0.5f;
            path.lineTo(t * w, y);
        }
        path.lineTo(w, h);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, h * (yFrac - ampFrac * 2f), 0, h,
                topCol, botCol, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
    }

    private static void drawRetroSun(Canvas c, float cx, float cy, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(cx, cy - r, cx, cy + r,
                new int[]{0xFFFFEA00, 0xFFFF6D00, 0xFFFF007F}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        cut.setColor(0xFF3E0A52);
        for (int i = 0; i < 6; i++) {
            float y = cy + r * (0.15f + i * 0.14f);
            c.drawRect(cx - r, y, cx + r, y + r * (0.025f + i * 0.008f), cut);
        }
    }

    private static void drawPerspectiveGrid(Canvas c, int w, int h, float horizonFrac, int lineCol, int glowCol) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float hy = h * horizonFrac;
        p.setStrokeWidth(Math.max(1.5f, h * 0.0025f));
        p.setColor(lineCol);
        // Radiating perspective lines
        for (int i = -12; i <= 12; i++) {
            c.drawLine(w * 0.5f + i * (w * 0.015f), hy, w * 0.5f + i * (w * 0.14f), h, p);
        }
        // Horizontal foreshortened lines
        for (int j = 0; j <= 10; j++) {
            float t = (float) Math.pow(j / 10.0, 2.1);
            float y = hy + t * (h - hy);
            c.drawLine(0, y, w, y, p);
        }
        p.setColor(glowCol);
        p.setStrokeWidth(Math.max(3f, h * 0.005f));
        c.drawLine(0, hy, w, hy, p);
    }

    private static void drawLowPoly(Canvas c, int w, int h, long seed) {
        Random rnd = new Random(seed);
        int cols = 10, rows = 7;
        float[][] px = new float[rows + 1][cols + 1];
        float[][] py = new float[rows + 1][cols + 1];
        for (int r = 0; r <= rows; r++) {
            for (int col = 0; col <= cols; col++) {
                float jx = (col == 0 || col == cols) ? 0f : (rnd.nextFloat() - 0.5f) * (w / (float) cols) * 0.68f;
                float jy = (r == 0 || r == rows) ? 0f : (rnd.nextFloat() - 0.5f) * (h / (float) rows) * 0.68f;
                px[r][col] = (w * col) / (float) cols + jx;
                py[r][col] = (h * r) / (float) rows + jy;
            }
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        int[] palette = new int[]{
                0xFF0A192F, 0xFF112240, 0xFF1B3A5C, 0xFF0E4D64,
                0xFF136F8A, 0xFF1D4ED8, 0xFF0F2942, 0xFF16324F
        };
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                drawTri(c, p, px[r][col], py[r][col], px[r][col + 1], py[r][col + 1],
                        px[r + 1][col], py[r + 1][col], palette[rnd.nextInt(palette.length)]);
                drawTri(c, p, px[r][col + 1], py[r][col + 1], px[r + 1][col + 1], py[r + 1][col + 1],
                        px[r + 1][col], py[r + 1][col], palette[rnd.nextInt(palette.length)]);
            }
        }
    }

    private static void drawTri(Canvas c, Paint p, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
    }
}
