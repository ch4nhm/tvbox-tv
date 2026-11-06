package com.ch4nhm.tvapp;

import android.os.Build;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class FocusAnimator {

    private static final PathInterpolator APPLE_FOCUS =
            new PathInterpolator(0.22f, 0.8f, 0.24f, 1f);
    private static final float CARD_SAFE_MARGIN_DP = 8f;

    private FocusAnimator() {
    }

    public static void scale(View view, float focusedScale) {
        scale(view, focusedScale, null);
    }

    public static void scale(View view, float focusedScale, Runnable onFocused) {
        disableDefaultHighlight(view);
        float initialScale = view.hasFocus() ? focusedScale : 1f;
        view.setScaleX(initialScale);
        view.setScaleY(initialScale);
        view.setOnFocusChangeListener((target, hasFocus) -> {
            target.animate().cancel();
            target.animate()
                    .scaleX(hasFocus ? focusedScale : 1f)
                    .scaleY(hasFocus ? focusedScale : 1f)
                    .setDuration(hasFocus ? 210 : 170)
                    .setInterpolator(APPLE_FOCUS)
                    .start();
            if (hasFocus && onFocused != null) onFocused.run();
        });
    }

    public static void card(View root, View card, float focusedScale) {
        card(root, card, focusedScale, null);
    }

    public static void card(View root, View card, float focusedScale,
                            Runnable onFocused) {
        disableDefaultHighlight(root);
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setTranslationZ(0f);
        View outline = card.findViewById(R.id.focus_outline);
        if (outline != null) outline.setAlpha(0f);
        float restingScale = 1f;
        root.setScaleX(restingScale);
        root.setScaleY(restingScale);
        root.setTranslationZ(0f);
        root.setOnFocusChangeListener((target, hasFocus) -> {
            root.animate().cancel();
            root.animate()
                    .scaleX(hasFocus ? focusedScale : restingScale)
                    .scaleY(hasFocus ? focusedScale : restingScale)
                    .translationZ(hasFocus ? dp(root, 16) : 0f)
                    .setDuration(hasFocus ? 210 : 170)
                    .setInterpolator(APPLE_FOCUS)
                    .start();
            card.animate()
                    .translationZ(hasFocus ? dp(root, 8) : 0f)
                    .setDuration(hasFocus ? 210 : 170)
                    .setInterpolator(APPLE_FOCUS)
                    .start();
            if (outline != null) {
                outline.animate()
                        .alpha(hasFocus ? 1f : 0f)
                        .setDuration(hasFocus ? 150 : 110)
                        .setInterpolator(APPLE_FOCUS)
                        .start();
            }
            if (hasFocus) {
                if (onFocused != null) onFocused.run();
                root.postOnAnimation(() -> keepCardInSafeVerticalBounds(root));
            }
        });
    }

    public static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    private static void keepCardInSafeVerticalBounds(View card) {
        if (card == null || !card.hasFocus() || !card.isShown()) return;

        View current = card;
        while (current.getParent() instanceof View parent) {
            if (parent instanceof RecyclerView recyclerView
                    && isVerticalList(recyclerView)) {
                scrollCardIntoView(card, recyclerView);
                return;
            }
            current = parent;
        }
    }

    private static boolean isVerticalList(RecyclerView recyclerView) {
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        return manager instanceof LinearLayoutManager linearManager
                && linearManager.getOrientation() == LinearLayoutManager.VERTICAL;
    }

    private static void scrollCardIntoView(View card, RecyclerView list) {
        int[] cardLocation = new int[2];
        int[] listLocation = new int[2];
        card.getLocationOnScreen(cardLocation);
        list.getLocationOnScreen(listLocation);

        int cardTop = cardLocation[1] - listLocation[1];
        int cardBottom = cardTop + Math.round(card.getHeight() * card.getScaleY());
        int safeMargin = Math.round(dp(card, CARD_SAFE_MARGIN_DP));
        int safeTop = list.getPaddingTop() + safeMargin;
        int safeBottom = list.getHeight() - list.getPaddingBottom() - safeMargin;
        int scrollDelta = 0;

        // A card taller than the viewport cannot fit on screen. Anchor it to
        // the top so focus scrolling never pushes it underneath a fixed header.
        if (cardBottom - cardTop > safeBottom - safeTop) {
            if (cardTop < safeTop || cardBottom > safeBottom) {
                scrollDelta = cardTop - safeTop;
            }
        } else if (cardTop < safeTop) {
            scrollDelta = cardTop - safeTop;
        } else if (cardBottom > safeBottom) {
            scrollDelta = cardBottom - safeBottom;
        }
        if (scrollDelta != 0) {
            list.stopScroll();
            list.smoothScrollBy(0, scrollDelta);
        }
    }

    private static void disableDefaultHighlight(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setDefaultFocusHighlightEnabled(false);
        }
    }
}
