package com.ch4nhm.tvapp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A deterministic poster fallback for providers that return a broken or
 * missing image. It keeps grids visually complete without inventing artwork.
 */
final class PosterPlaceholderDrawable extends Drawable {

    private static final int[][] PALETTES = {
            {0xff353538, 0xff141416},
            {0xff3b3b3e, 0xff19191b},
            {0xff303236, 0xff111214},
            {0xff38383b, 0xff171719}
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyph = new Path();
    private final int seed;
    private final int accent;
    private int alpha = 255;

    PosterPlaceholderDrawable(String title, int accent) {
        String value = title == null ? "" : title.trim();
        seed = value.hashCode();
        this.accent = accent;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int width = bounds.width();
        int height = bounds.height();
        if (width <= 0 || height <= 0) return;

        int[] palette = PALETTES[Math.floorMod(seed, PALETTES.length)];
        paint.setShader(new LinearGradient(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                withAlpha(palette[0]), withAlpha(palette[1]), Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, paint);

        float glowX = bounds.left + width * (0.3f + Math.floorMod(seed, 41) / 100f);
        float glowY = bounds.top + height * 0.18f;
        paint.setShader(new RadialGradient(
                glowX, glowY,
                Math.max(width, height) * 0.7f,
                new int[]{withAlpha(Color.argb(42, Color.red(accent),
                        Color.green(accent), Color.blue(accent))),
                        withAlpha(Color.TRANSPARENT)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, paint);

        paint.setShader(null);
        drawPlayGlyph(canvas, bounds, width, height);
    }

    private void drawPlayGlyph(Canvas canvas, Rect bounds, int width, int height) {
        float centerX = bounds.left + width * 0.5f;
        float centerY = bounds.top + height * 0.49f;
        float radius = width * 0.155f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0x14FFFFFF));
        canvas.drawCircle(centerX, centerY, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.2f, width * 0.004f));
        paint.setColor(withAlpha(0x58FFFFFF));
        canvas.drawCircle(centerX, centerY, radius, paint);

        glyph.reset();
        glyph.moveTo(centerX - radius * 0.22f, centerY - radius * 0.36f);
        glyph.lineTo(centerX + radius * 0.42f, centerY);
        glyph.lineTo(centerX - radius * 0.22f, centerY + radius * 0.36f);
        glyph.close();
        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(withAlpha(0xA8FFFFFF));
        canvas.drawPath(glyph, glyphPaint);
    }

    private int withAlpha(int color) {
        return Color.argb(Color.alpha(color) * alpha / 255,
                Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        glyphPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 520;
    }

    @Override
    public int getIntrinsicHeight() {
        return 780;
    }
}
