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
 * Liquid-Glass-inspired optical material for API 15 (not Apple's renderer).
 * Transmitted colour, a thick Fresnel edge, opposing specular highlights and a
 * subtle prismatic rim replace flat gradients. Home additionally samples its
 * cached wallpaper at two magnifications: the centre and a stronger refractive
 * edge. No live app-content capture, runtime shader compilation or redraw loop.
 * All shaders are allocated on bounds changes, never in draw().
 */
public final class LiquidGlassDrawable extends GradientDrawable {
    private final Paint body = new Paint(3), sheen = new Paint(3), rim = new Paint(3), inner = new Paint(3),
            shadow = new Paint(3), frost = new Paint(3), edgeFrost = new Paint(3),
            thickness = new Paint(3), prism = new Paint(3), caustic = new Paint(3), outline = new Paint(3);
    private final RectF rect = new RectF(), inset = new RectF();
    private final Matrix backdropMatrix = new Matrix(), edgeMatrix = new Matrix();
    private final int[] screenPosition = new int[2];
    private int tint;
    private final int accent, opacity;
    private final float density;
    private float radius, outlineWidth;
    private int alpha = 255;
    private boolean pressed;
    private BitmapShader backdrop, refractedEdge;
    private int backdropWidth, backdropHeight, screenWidth, screenHeight;

    public LiquidGlassDrawable(int tint, int accent, float radius, float density, int opacity) {
        this.tint = tint; this.accent = accent; this.radius = radius;
        this.density = Math.max(1f, density); this.opacity = Math.max(0, Math.min(255, opacity));
        rim.setStyle(Paint.Style.STROKE); inner.setStyle(Paint.Style.STROKE);
        thickness.setStyle(Paint.Style.STROKE); prism.setStyle(Paint.Style.STROKE);
        edgeFrost.setStyle(Paint.Style.STROKE); outline.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(this.density * 0.85f); inner.setStrokeWidth(this.density * 0.7f);
        thickness.setStrokeWidth(this.density * 5f); prism.setStrokeWidth(this.density * 1.2f);
        edgeFrost.setStrokeWidth(this.density * 6f);
    }

    public LiquidGlassDrawable withBackdrop(Bitmap bitmap, DisplayMetrics metrics) {
        if (bitmap != null && !bitmap.isRecycled() && metrics != null) {
            backdrop = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            refractedEdge = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            backdropWidth = bitmap.getWidth(); backdropHeight = bitmap.getHeight();
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;
            frost.setShader(backdrop); frost.setAlpha(Math.min(180, opacity * 2));
            edgeFrost.setShader(refractedEdge); edgeFrost.setAlpha(Math.min(210, opacity * 2));
        }
        return this;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float d = density;
        rect.set(bounds.left + d * 2, bounds.top + d, bounds.right - d * 2, bounds.bottom - d * 4);
        if (rect.width() <= 0 || rect.height() <= 0) return;
        body.setColor((tint & 0xffffff) | (opacity << 24));
        sheen.setShader(new LinearGradient(rect.left, rect.top, rect.left + rect.width() * 0.38f, rect.bottom,
                new int[]{0x38FFFFFF, 0x09FFFFFF, 0x00FFFFFF, 0x05FFFFFF},
                new float[]{0, 0.25f, 0.60f, 1}, Shader.TileMode.CLAMP));
        // Broad soft Fresnel band, then a hairline glint: perceived thickness without an opaque fill.
        thickness.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0x38FFFFFF, 0x02FFFFFF, 0x00FFFFFF, 0x22FFFFFF},
                new float[]{0, 0.25f, 0.70f, 1}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xE8FFFFFF, 0x45FFFFFF, 0x10FFFFFF, 0xA8FFFFFF},
                new float[]{0, 0.32f, 0.65f, 1}, Shader.TileMode.CLAMP));
        inner.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x0CFFFFFF, 0x00FFFFFF, 0x58FFFFFF}, null, Shader.TileMode.CLAMP));
        prism.setShader(new LinearGradient(rect.left, rect.bottom, rect.right, rect.top,
                new int[]{0x489CEBFF, 0x00FFFFFF, 0x00FFFFFF, 0x38F7D4FF},
                new float[]{0, 0.25f, 0.72f, 1}, Shader.TileMode.CLAMP));
        caustic.setShader(new RadialGradient(rect.right, rect.bottom + rect.height() * 0.4f,
                Math.max(1, rect.width() * 0.8f), new int[]{0x22FFFFFF, (accent & 0xffffff) | 0x08000000, 0},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
    }

    private void align(Matrix matrix, float magnification) {
        float base = Math.max((float) screenWidth / backdropWidth, (float) screenHeight / backdropHeight);
        float x = (screenWidth - backdropWidth * base) / 2f - screenPosition[0];
        float y = (screenHeight - backdropHeight * base) / 2f - screenPosition[1];
        matrix.setScale(base * magnification, base * magnification);
        matrix.postTranslate(x * magnification + (rect.left + rect.right) * 0.5f * (1 - magnification),
                y * magnification + (rect.top + rect.bottom) * 0.5f * (1 - magnification));
    }
    @Override public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        int checkpoint = alpha < 255 ? canvas.saveLayerAlpha(rect.left - density, rect.top - density,
                rect.right + density, rect.bottom + density * 4, alpha, Canvas.ALL_SAVE_FLAG) : -1;
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        // A three-step contact shadow works on Android 4 hardware canvases; no software shadow layer.
        inset.set(rect); inset.offset(0, density * 3);
        shadow.setColor(0x0C000000); canvas.drawRoundRect(inset, r, r, shadow);
        inset.offset(0, -density); shadow.setColor(0x10000000); canvas.drawRoundRect(inset, r, r, shadow);
        inset.offset(0, -density); shadow.setColor(0x16000000); canvas.drawRoundRect(inset, r, r, shadow);
        if (backdrop != null) {
            Drawable.Callback callback = getCallback();
            if (callback instanceof View) ((View) callback).getLocationOnScreen(screenPosition);
            else { screenPosition[0] = 0; screenPosition[1] = 0; }
            align(backdropMatrix, 1.018f); align(edgeMatrix, 1.075f);
            backdrop.setLocalMatrix(backdropMatrix); refractedEdge.setLocalMatrix(edgeMatrix);
            canvas.drawRoundRect(rect, r, r, frost);
            inset.set(rect); inset.inset(density * 3.2f, density * 3.2f);
            if (inset.width() > 0 && inset.height() > 0) canvas.drawRoundRect(inset, Math.max(0, r-density*3), Math.max(0, r-density*3), edgeFrost);
        }
        canvas.drawRoundRect(rect, r, r, body);
        canvas.drawRoundRect(rect, r, r, sheen);
        canvas.drawRoundRect(rect, r, r, caustic);
        inset.set(rect); inset.inset(density * 2.7f, density * 2.7f);
        if (inset.width() > 0 && inset.height() > 0) canvas.drawRoundRect(inset, Math.max(0, r-density*2), Math.max(0, r-density*2), thickness);
        canvas.drawRoundRect(rect, r, r, rim);
        inset.set(rect); inset.inset(density * 1.25f, density * 1.25f);
        if (inset.width() > 0 && inset.height() > 0) {
            canvas.drawRoundRect(inset, Math.max(0, r-density), Math.max(0, r-density), inner);
            canvas.drawRoundRect(inset, Math.max(0, r-density), Math.max(0, r-density), prism);
        }
        if (outlineWidth > 0) canvas.drawRoundRect(rect, r, r, outline);
        if (pressed) { shadow.setColor(0x2AFFFFFF); canvas.drawRoundRect(rect, r, r, shadow); }
        if (checkpoint >= 0) canvas.restoreToCount(checkpoint);
    }
    @Override public void setColor(int color) {
        tint = color;
        if (body != null) { body.setColor((tint & 0xffffff) | (opacity << 24)); invalidateSelf(); }
    }
    @Override public void setStroke(int width, int color) {
        outlineWidth = Math.max(0, width);
        if (outline != null) { outline.setStrokeWidth(outlineWidth); outline.setColor(color); invalidateSelf(); }
    }
    @Override public void setCornerRadius(float r) { radius = Math.max(0, r); invalidateSelf(); }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] state) {
        boolean down = false;
        for (int s : state) if (s == android.R.attr.state_pressed || s == android.R.attr.state_focused) down = true;
        if (down == pressed) return false; pressed = down; invalidateSelf(); return true;
    }
    @Override public void setAlpha(int a) { alpha = Math.max(0, Math.min(255, a)); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) {
        for (Paint p : new Paint[]{body, sheen, rim, inner, frost, edgeFrost, thickness, prism, caustic, outline}) p.setColorFilter(f);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** Static multi-light field gives every app's transparent surfaces colour to transmit. */
    public static Drawable ambient(final int background, final int accent) {
        return new Drawable() {
            private final Paint paint = new Paint(3);
            private Shader glow, pearl, pool;
            private int alpha = 255;
            @Override protected void onBoundsChange(Rect b) {
                float size = Math.max(1, Math.max(b.width(), b.height()));
                glow = new RadialGradient(b.left + b.width() * 0.04f, b.top + b.height() * 0.10f, size * 0.85f,
                        new int[]{blend(background, accent, 0.38f), blend(background, accent, 0.10f), background},
                        new float[]{0, 0.53f, 1}, Shader.TileMode.CLAMP);
                pearl = new RadialGradient(b.right, b.top + b.height() * 0.26f, size * 0.72f,
                        new int[]{0x20DDD6FF, 0x08D4DFFF, 0}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP);
                pool = new RadialGradient(b.left + b.width() * 0.3f, b.bottom, size * 0.64f,
                        new int[]{(accent & 0xffffff) | 0x20000000, 0}, null, Shader.TileMode.CLAMP);
            }
            @Override public void draw(Canvas c) {
                paint.setAlpha(alpha); paint.setShader(glow); c.drawRect(getBounds(), paint);
                paint.setShader(pearl); c.drawRect(getBounds(), paint);
                paint.setShader(pool); c.drawRect(getBounds(), paint);
            }
            @Override public void setAlpha(int a) { alpha = Math.max(0, Math.min(255, a)); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
            @Override public int getOpacity() { return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT; }
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
