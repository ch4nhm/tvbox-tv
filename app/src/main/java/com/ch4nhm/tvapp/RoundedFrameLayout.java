package com.ch4nhm.tvapp;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

public final class RoundedFrameLayout extends FrameLayout {

    private float cornerRadius;
    private float aspectRatio;

    public RoundedFrameLayout(Context context) {
        this(context, null);
    }

    public RoundedFrameLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RoundedFrameLayout(Context context, @Nullable AttributeSet attrs,
                              int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        cornerRadius = context.getResources().getDimension(R.dimen.card_radius);
        if (attrs != null) {
            android.content.res.TypedArray values = context.obtainStyledAttributes(
                    attrs, R.styleable.RoundedFrameLayout, defStyleAttr, 0);
            cornerRadius = values.getDimension(
                    R.styleable.RoundedFrameLayout_surfaceCornerRadius, cornerRadius);
            aspectRatio = values.getFloat(
                    R.styleable.RoundedFrameLayout_surfaceAspectRatio, 0f);
            values.recycle();
        }
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (aspectRatio > 0f && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = Math.round(width * aspectRatio);
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        invalidateOutline();
    }
}
