package com.ch4nhm.tvapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Fades poster artwork into the ambient scene instead of ending on a hard,
 * black strip. This is especially noticeable on TVBox posters that include
 * dense credit blocks along their bottom edge.
 */
public final class PosterFadeImageView extends AppCompatImageView {

    private static final float FADE_START = 0.72f;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode fadeMode =
            new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private Shader fadeShader;
    private int shaderHeight = -1;

    public PosterFadeImageView(Context context) {
        this(context, null);
    }

    public PosterFadeImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PosterFadeImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        int saveCount = canvas.saveLayer(0f, 0f, width, height, null);
        super.onDraw(canvas);

        if (fadeShader == null || shaderHeight != height) {
            float start = height * FADE_START;
            fadeShader = new LinearGradient(
                    0f, start, 0f, height,
                    new int[]{
                            Color.BLACK,
                            Color.argb(238, 0, 0, 0),
                            Color.argb(150, 0, 0, 0),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.34f, 0.72f, 1f},
                    Shader.TileMode.CLAMP);
            shaderHeight = height;
        }

        maskPaint.setShader(fadeShader);
        maskPaint.setXfermode(fadeMode);
        canvas.drawRect(0f, height * FADE_START, width, height, maskPaint);
        maskPaint.setXfermode(null);
        canvas.restoreToCount(saveCount);
    }
}
