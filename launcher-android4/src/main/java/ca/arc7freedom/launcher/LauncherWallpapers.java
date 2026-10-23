package ca.arc7freedom.launcher;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import java.util.Random;

/**
 * Built-in 12 HD procedural wallpaper themes shipped right inside `VanillaAndroid4Home.apk`
 * (`< 45 KB` APK size, zero extra assets!) so even standalone `Android 4 Home` can instantly
 * apply 12 crisp `1280x800` wallpapers offline at any time.
 */
public class LauncherWallpapers {

    public static final String[] TITLES = new String[]{
            "1. Holo Phase Beam (Android 4.2 Classic)",
            "2. Northern Aurora Borealis",
            "3. Misty Blue Ridge Mountains",
            "4. Nexus Jelly Bean Silk Ribbons",
            "5. Pacific Ocean Coral Dusk",
            "6. Outrun Neon Horizon Grid",
            "7. Kobo Zen Sumi-e Ink Wash",
            "8. Deep Cosmos & Spiral Nebula",
            "9. Emerald Forest Sunbeams",
            "10. Sahara Golden Twilight Dunes",
            "11. Low-Poly Sapphire Crystal",
            "12. Battery-Saver Pure Obsidian Glow"
    };

    public static void applyTheme(Context ctx, int idx) throws Exception {
        WallpaperManager wm = WallpaperManager.getInstance(ctx);
        int w = 1280, h = 800;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        switch (idx % TITLES.length) {
            case 0:
                grad(c, w, h, new int[]{0xFF04091A, 0xFF0B224C, 0xFF194B7E, 0xFF071226});
                orb(c, w * 0.28f, h * 0.62f, w * 0.36f, 0x6633B5E5);
                orb(c, w * 0.72f, h * 0.34f, w * 0.30f, 0x55AA66CC);
                break;
            case 1:
                grad(c, w, h, new int[]{0xFF020712, 0xFF071C2C, 0xFF0C3640, 0xFF040C14});
                stars(c, w, h, 202L, 160, 0.65f);
                wave(c, w, h, 0.34f, 0.08f, 1.4f, 0x662AF598, 0x11009EFD);
                ridge(c, w, h, 0.80f, 0.07f, 21L, 0xFF050E15);
                break;
            case 2:
                grad(c, w, h, new int[]{0xFF1A2A4A, 0xFF355C7D, 0xFF6C5B7B, 0xFFC06C84});
                orb(c, w * 0.76f, h * 0.24f, w * 0.08f, 0xEEFFF3CD);
                ridge(c, w, h, 0.52f, 0.10f, 31L, 0x88425E7C);
                ridge(c, w, h, 0.66f, 0.09f, 47L, 0xCC263B57);
                ridge(c, w, h, 0.80f, 0.07f, 79L, 0xFF121F31);
                break;
            case 3:
                grad(c, w, h, new int[]{0xFF0D0E15, 0xFF171926, 0xFF0E1019});
                wave(c, w, h, 0.38f, 0.14f, 1.2f, 0x99E53935, 0x22FF7043);
                wave(c, w, h, 0.52f, 0.15f, 2.5f, 0x88FB8C00, 0x22FFD54F);
                wave(c, w, h, 0.66f, 0.13f, 4.1f, 0x888E24AA, 0x2200ACC1);
                break;
            case 4:
                grad(c, w, h, new int[]{0xFF1D2B64, 0xFF7B4397, 0xFFDC2430, 0xFFF8B500});
                orb(c, w * 0.50f, h * 0.56f, w * 0.14f, 0xDDFFE082);
                wave(c, w, h, 0.66f, 0.035f, 1.0f, 0xEE183B59, 0xFF071626);
                wave(c, w, h, 0.80f, 0.040f, 3.3f, 0xFF091B2E, 0xFF040D17);
                break;
            case 5:
                grad(c, w, h, new int[]{0xFF09021C, 0xFF240B4C, 0xFF6B0F6C, 0xFF120428});
                orb(c, w * 0.50f, h * 0.44f, h * 0.22f, 0xFFFF6D00);
                p.setColor(0xFF0B0418);
                c.drawRect(0, h * 0.56f, w, h, p);
                p.setColor(0xAA00F5FF);
                p.setStrokeWidth(2f);
                for (int i = -12; i <= 12; i++) {
                    c.drawLine(w * 0.5f + i * 18f, h * 0.56f, w * 0.5f + i * 170f, h, p);
                }
                break;
            case 6:
                grad(c, w, h, new int[]{0xFFF4EFE6, 0xFFEAE2D3, 0xFFDCD2C0});
                orb(c, w * 0.74f, h * 0.26f, h * 0.11f, 0xCCCF3A2B);
                ridge(c, w, h, 0.56f, 0.10f, 111L, 0x5558646D);
                ridge(c, w, h, 0.74f, 0.08f, 333L, 0xBB232A30);
                break;
            case 7:
                grad(c, w, h, new int[]{0xFF02030A, 0xFF080D22, 0xFF050713});
                orb(c, w * 0.42f, h * 0.46f, w * 0.34f, 0x555E35B1);
                orb(c, w * 0.60f, h * 0.52f, w * 0.28f, 0x4400ACC1);
                stars(c, w, h, 808L, 240, 1.0f);
                break;
            case 8:
                grad(c, w, h, new int[]{0xFF0B261C, 0xFF154734, 0xFF091E16});
                orb(c, w * 0.30f, h * 0.20f, w * 0.35f, 0x44FFE082);
                ridge(c, w, h, 0.72f, 0.07f, 88L, 0xCC071C13);
                break;
            case 9:
                grad(c, w, h, new int[]{0xFF0F172A, 0xFF312E81, 0xFF9A3412, 0xFFF59E0B});
                stars(c, w, h, 909L, 90, 0.48f);
                wave(c, w, h, 0.64f, 0.06f, 1.1f, 0xFFD97706, 0xFF78350F);
                wave(c, w, h, 0.80f, 0.06f, 3.4f, 0xFF92400E, 0xFF451A03);
                break;
            case 10:
                grad(c, w, h, new int[]{0xFF0A192F, 0xFF112240, 0xFF136F8A, 0xFF0F2942});
                orb(c, w * 0.65f, h * 0.40f, w * 0.32f, 0x5538BDF8);
                break;
            default:
                grad(c, w, h, new int[]{0xFF020408, 0xFF060C16, 0xFF020408});
                orb(c, w * 0.50f, h * 0.88f, w * 0.42f, 0x3300ACC1);
                break;
        }
        wm.setBitmap(bmp);
    }

    private static void grad(Canvas c, int w, int h, int[] cols) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, cols, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    private static void orb(Canvas c, float cx, float cy, float r, int col) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(cx, cy, Math.max(4f, r),
                new int[]{col, col & 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    private static void stars(Canvas c, int w, int h, long seed, int n, float yf) {
        Random rnd = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < n; i++) {
            p.setColor(Color.argb(110 + rnd.nextInt(145), 240, 248, 255));
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * yf, 0.8f + rnd.nextFloat() * 1.8f, p);
        }
    }

    private static void ridge(Canvas c, int w, int h, float bf, float af, long seed, int col) {
        Random rnd = new Random(seed);
        Path path = new Path();
        path.moveTo(0, h);
        float y = h * bf;
        path.lineTo(0, y);
        for (int i = 1; i <= 24; i++) {
            y = Math.max(h * (bf - af * 1.5f), Math.min(h * (bf + af), y + (rnd.nextFloat() - 0.5f) * 2f * h * af));
            path.lineTo((w * i) / 24f, y);
        }
        path.lineTo(w, h);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(col);
        c.drawPath(path, p);
    }

    private static void wave(Canvas c, int w, int h, float yf, float af, float ph, int top, int bot) {
        Path path = new Path();
        path.moveTo(0, h);
        for (int i = 0; i <= 72; i++) {
            float t = i / 72f;
            path.lineTo(t * w, h * yf + (float) Math.sin(t * 5.2f + ph) * h * af);
        }
        path.lineTo(w, h);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, h * (yf - af * 2f), 0, h, top, bot, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
    }
}
