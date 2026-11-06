package com.ch4nhm.tvapp;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView does not reliably clip transformed or elevated item views on all
 * TV renderers. Keep its drawing inside the viewport so off-screen rows cannot
 * paint over fixed headers.
 */
public final class ClippedRecyclerView extends RecyclerView {

    public ClippedRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public ClippedRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ClippedRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs,
                               int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        super.draw(canvas);
        canvas.restoreToCount(save);
    }
}
