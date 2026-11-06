package com.ch4nhm.tvapp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Android-facing access point for viewport-aware dimensions.
 */
final class TvLayout {

    private TvLayout() {
    }

    static TvLayoutMetrics metrics(Context context) {
        return TvLayoutMetrics.forViewport(TvDisplayAdapter.viewport(context));
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int px(Context context, float value) {
        return dp(context, value);
    }

    static void applyPosterCard(View root, View card, TextView title, TextView meta) {
        if (root == null || root.getContext() == null) return;
        TvLayoutMetrics metrics = metrics(root.getContext());
        int horizontalInset = dp(root.getContext(), metrics.posterVisualInsetDp);
        root.setPadding(horizontalInset, root.getPaddingTop(),
                horizontalInset, root.getPaddingBottom());
        if (title != null) {
            title.setTextSize(metrics.posterTitleTextSizeSp);
            ViewGroup.LayoutParams params = title.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                margins.topMargin = dp(root.getContext(), 9);
                title.setLayoutParams(margins);
            }
        }
        if (card != null && card.getParent() instanceof View) {
            card.setScaleX(1f);
            card.setScaleY(1f);
        }
        if (meta != null) meta.setVisibility(View.GONE);
    }

    static void applyGridPadding(RecyclerView list, int topDp, int bottomDp) {
        if (list == null) return;
        TvLayoutMetrics metrics = metrics(list.getContext());
        int left = dp(list.getContext(), metrics.contentMarginDp);
        list.setPadding(left, dp(list.getContext(), topDp),
                left, dp(list.getContext(), bottomDp));
    }

    static void applyButtonHeight(View view, boolean useResponsiveHeight) {
        if (view == null || !useResponsiveHeight) return;
        TvLayoutMetrics metrics = metrics(view.getContext());
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = dp(view.getContext(), metrics.buttonHeightDp);
            view.setLayoutParams(params);
        }
    }
}
