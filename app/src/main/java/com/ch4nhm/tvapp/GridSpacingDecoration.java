package com.ch4nhm.tvapp;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Distributes one visual gap evenly between grid cells without fixed card
 * widths or asymmetric row margins.
 */
final class GridSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int gap;

    private GridSpacingDecoration(int gap) {
        this.gap = Math.max(0, gap);
    }

    static void add(RecyclerView recyclerView) {
        if (recyclerView == null) return;
        int gap = recyclerView.getResources().getDimensionPixelSize(R.dimen.card_gap);
        recyclerView.addItemDecoration(new GridSpacingDecoration(gap));
    }

    static void add(RecyclerView recyclerView, int gapResource) {
        if (recyclerView == null) return;
        int gap = recyclerView.getResources().getDimensionPixelSize(gapResource);
        recyclerView.addItemDecoration(new GridSpacingDecoration(gap));
    }

    static void addPx(RecyclerView recyclerView, int gapPixels) {
        if (recyclerView == null) return;
        recyclerView.addItemDecoration(new GridSpacingDecoration(gapPixels));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        RecyclerView.LayoutManager manager = parent.getLayoutManager();
        if (!(manager instanceof GridLayoutManager grid)) {
            outRect.set(0, 0, 0, gap);
            return;
        }

        GridLayoutManager.LayoutParams params =
                (GridLayoutManager.LayoutParams) view.getLayoutParams();
        int spanCount = Math.max(1, grid.getSpanCount());
        int spanSize = Math.max(1, params.getSpanSize());
        int spanIndex = params.getSpanIndex();
        if (spanIndex < 0) {
            int position = parent.getChildAdapterPosition(view);
            spanIndex = position < 0 ? 0 : position % spanCount;
        }

        int left = Math.round(gap * spanIndex / (float) spanCount);
        int right = Math.round(
                gap * (spanCount - spanIndex - spanSize) / (float) spanCount);
        outRect.set(Math.max(0, left), 0, Math.max(0, right), gap);
    }
}
