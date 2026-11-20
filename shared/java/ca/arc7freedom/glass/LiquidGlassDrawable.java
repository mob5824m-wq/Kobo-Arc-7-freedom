package ca.arc7freedom.glass;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * Liquid-Glass-inspired lens material for API 15, not Apple's proprietary renderer.
 * Real transparency + cached wallpaper frost + two-tone specular rims, without
 * RenderEffect, live screen captures, shaders compiled at runtime, or a redraw loop.
 * Extends GradientDrawable to retain the existing skin factory API.
 */
public final class LiquidGlassDrawable extends GradientDrawable {
    private final Paint body = new Paint(3), sheen = new Paint(3), rim = new Paint(3), inner = new Paint(3), shadow = new Paint(3), frost = new Paint(3);
    private final RectF rect = new RectF(), inset = new RectF();
    private final Matrix backdropMatrix = new Matrix();
    private final int[] screenPosition = new int[2];
    private final int tint, accent, opacity;
    private final float density;
    private float radius;
    private int alpha = 255;
    private boolean pressed;
    private BitmapShader backdrop;
    private int backdropWidth, backdropHeight, screenWidth, screenHeight;

    public LiquidGlassDrawable(int tint, int accent, float radius, float density, int opacity) {
        this.tint = tint; this.accent = accent; this.radius = radius;
        this.density = Math.max(1f, density); this.opacity = Math.max(0, Math.min(255, opacity));
        rim.setStyle(Paint.Style.STROKE); inner.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(this.density); inner.setStrokeWidth(this.density * 0.65f);
    }

    public LiquidGlassDrawable withBackdrop(Bitmap bitmap, DisplayMetrics metrics) {
        if (bitmap != null && !bitmap.isRecycled()) {
            backdrop = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            backdropWidth = bitmap.getWidth(); backdropHeight = bitmap.getHeight();
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;
            frost.setShader(backdrop); frost.setAlpha(Math.min(130, opacity * 2));
        }
        return this;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float d = density;
        rect.set(bounds.left + d * 2, bounds.top + d, bounds.right - d * 2, bounds.bottom - d * 3);
        if (rect.width() <= 0 || rect.height() <= 0) return;
        body.setColor((tint & 0xffffff) | (opacity << 24));
        sheen.setShader(new LinearGradient(rect.left, rect.top, rect.left + rect.width() * 0.45f, rect.bottom,
                new int[]{0x48FFFFFF, 0x0AFFFFFF, 0x02FFFFFF, (accent & 0xffffff) | 0x1C000000},
                new float[]{0, 0.28f, 0.65f, 1}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xC4FFFFFF, 0x26FFFFFF, 0x12FFFFFF, 0x70FFFFFF},
                new float[]{0, 0.35f, 0.67f, 1}, Shader.TileMode.CLAMP));
        inner.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x4DFFFFFF, 0x00FFFFFF, 0x2AFFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        int checkpoint = alpha < 255 ? canvas.saveLayerAlpha(rect.left - density, rect.top - density, rect.right + density, rect.bottom + density * 3, alpha, Canvas.ALL_SAVE_FLAG) : -1;
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        // Soft lift kept inside drawable bounds (no API-21 elevation / software shadow layer).
        inset.set(rect); inset.offset(0, density * 2);
        shadow.setColor(0x16000000); canvas.drawRoundRect(inset, r, r, shadow);
        inset.offset(0, -density); shadow.setColor(0x18000000); canvas.drawRoundRect(inset, r, r, shadow);
        if (backdrop != null) {
            Drawable.Callback callback = getCallback();
            if (callback instanceof View) ((View) callback).getLocationOnScreen(screenPosition);
            else { screenPosition[0] = 0; screenPosition[1] = 0; }
            float scale = Math.max((float) screenWidth / backdropWidth, (float) screenHeight / backdropHeight);
            backdropMatrix.setScale(scale, scale);
            backdropMatrix.postTranslate((screenWidth - backdropWidth * scale) / 2f - screenPosition[0],
                    (screenHeight - backdropHeight * scale) / 2f - screenPosition[1]);
            backdrop.setLocalMatrix(backdropMatrix);
            canvas.drawRoundRect(rect, r, r, frost);
        }
        canvas.drawRoundRect(rect, r, r, body);
        canvas.drawRoundRect(rect, r, r, sheen);
        canvas.drawRoundRect(rect, r, r, rim);
        inset.set(rect); inset.inset(density * 1.4f, density * 1.4f);
        canvas.drawRoundRect(inset, Math.max(0, r - density), Math.max(0, r - density), inner);
        if (pressed) { shadow.setColor(0x26FFFFFF); canvas.drawRoundRect(rect, r, r, shadow); }
        if (checkpoint >= 0) canvas.restoreToCount(checkpoint);
    }
    @Override public void setCornerRadius(float r) { radius = r; invalidateSelf(); }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] state) {
        boolean down = false;
        for (int s : state) if (s == android.R.attr.state_pressed || s == android.R.attr.state_focused) down = true;
        if (down == pressed) return false; pressed = down; invalidateSelf(); return true;
    }
    @Override public void setAlpha(int a) { alpha = Math.max(0, Math.min(255, a)); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) {
        body.setColorFilter(f); sheen.setColorFilter(f); rim.setColorFilter(f); inner.setColorFilter(f); frost.setColorFilter(f); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** Small, static ambient backdrop so translucent app surfaces have something to transmit. */
    public static Drawable ambient(final int background, final int accent) {
        return new Drawable() {
            private final Paint paint = new Paint(3);
            private Shader glow;
            @Override protected void onBoundsChange(Rect b) {
                glow = new RadialGradient(b.left + b.width() * 0.16f, b.top + b.height() * 0.12f,
                        Math.max(1, Math.max(b.width(), b.height()) * 0.85f),
                        new int[]{blend(background, accent, 0.27f), blend(background, accent, 0.07f), background},
                        new float[]{0, 0.58f, 1}, Shader.TileMode.CLAMP);
            }
            @Override public void draw(Canvas c) { paint.setShader(glow); c.drawRect(getBounds(), paint); }
            @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }
    private static int blend(int a, int b, float amount) {
        return Color.rgb((int)(Color.red(a) * (1 - amount) + Color.red(b) * amount),
                (int)(Color.green(a) * (1 - amount) + Color.green(b) * amount),
                (int)(Color.blue(a) * (1 - amount) + Color.blue(b) * amount));
    }

    /** Bounded wallpaper-only blur. Never captures app content; caller caches until wallpaper change. */
    public static Bitmap frostedWallpaper(Context context) {
        try {
            Drawable wallpaper = WallpaperManager.getInstance(context).getDrawable();
            if (wallpaper == null) return null;
            int width = Math.max(1, wallpaper.getIntrinsicWidth()), height = Math.max(1, wallpaper.getIntrinsicHeight());
            float scale = 160f / Math.max(width, height);
            width = Math.max(1, Math.round(width * scale)); height = Math.max(1, Math.round(height * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Rect old = new Rect(wallpaper.getBounds());
            try { wallpaper.setBounds(0, 0, width, height); wallpaper.draw(new Canvas(bitmap)); }
            finally { wallpaper.setBounds(old); }
            int[] pixels = new int[width * height], temp = new int[pixels.length];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int red = 0, green = 0, blue = 0;
                    for (int k = -3; k <= 3; k++) {
                        int sx = pass == 1 ? x : Math.max(0, Math.min(width - 1, x + k));
                        int sy = pass == 1 ? Math.max(0, Math.min(height - 1, y + k)) : y;
                        int c = pixels[sy * width + sx]; red += Color.red(c); green += Color.green(c); blue += Color.blue(c);
                    }
                    temp[y * width + x] = Color.rgb(red / 7, green / 7, blue / 7);
                }
                int[] swap = pixels; pixels = temp; temp = swap;
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height); return bitmap;
        } catch (RuntimeException unavailable) { return null; }
        catch (OutOfMemoryError lowMemory) { return null; }
    }
}
