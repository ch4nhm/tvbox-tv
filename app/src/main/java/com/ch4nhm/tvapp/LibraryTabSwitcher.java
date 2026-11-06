package com.ch4nhm.tvapp;

import android.animation.ValueAnimator;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

/**
 * Treats the two library modes as one focusable segmented control. TV users
 * should move left/right inside the control instead of clicking two buttons.
 */
final class LibraryTabSwitcher {

    interface Listener {
        void onModeChanged(boolean history);
    }

    private final View container;
    private final View indicator;
    private final TextView favorites;
    private final TextView history;
    private final Listener listener;
    private boolean historySelected;
    private ValueAnimator indicatorAnimator;

    LibraryTabSwitcher(View root, boolean history, Listener listener) {
        this.listener = listener;
        container = root.findViewById(R.id.library_tabs);
        indicator = root.findViewById(R.id.library_tab_indicator);
        favorites = root.findViewById(R.id.library_favorites);
        this.history = root.findViewById(R.id.library_history);

        container.setFocusable(true);
        container.setFocusableInTouchMode(true);
        container.setClickable(true);
        favorites.setFocusable(false);
        favorites.setClickable(false);
        this.history.setFocusable(false);
        this.history.setClickable(false);
        FocusAnimator.scale(container, 1.025f);

        container.setOnClickListener(view -> select(!historySelected, true, true));
        container.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                    && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || event.getRepeatCount() != 0) {
                return false;
            }
            boolean nextHistory = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (nextHistory == historySelected) {
                // At the last segment, release the key so focus can move to
                // the action buttons on the right.
                return false;
            }
            select(nextHistory, true, true);
            return true;
        });

        select(history, false, false);
        container.post(() -> placeIndicator(false));
    }

    void setMode(boolean history, boolean animate) {
        if (historySelected == history && indicator.getWidth() > 0) return;
        select(history, animate, false);
    }

    void requestFocus() {
        container.requestFocus();
    }

    View view() {
        return container;
    }

    private void select(boolean nextHistory, boolean animate, boolean notify) {
        historySelected = nextHistory;
        favorites.setSelected(!nextHistory);
        history.setSelected(nextHistory);
        favorites.animate().alpha(nextHistory ? 0.64f : 1f).setDuration(170L).start();
        history.animate().alpha(nextHistory ? 1f : 0.64f).setDuration(170L).start();
        container.setContentDescription(String.format(Locale.getDefault(),
                "收藏和播放记录，按左右切换，当前为%s", nextHistory ? "播放记录" : "收藏"));
        placeIndicator(animate);
        if (notify && listener != null) listener.onModeChanged(nextHistory);
    }

    private void placeIndicator(boolean animate) {
        if (container.getWidth() <= 0 || indicator == null) {
            container.post(() -> placeIndicator(animate));
            return;
        }
        TextView target = historySelected ? history : favorites;
        int targetX = relativeLeft(target);
        int targetWidth = Math.max(1, target.getWidth());
        if (!animate || indicator.getWidth() <= 0) {
            cancelIndicatorAnimation();
            setIndicatorBounds(targetX, targetWidth);
            return;
        }

        cancelIndicatorAnimation();
        float startX = indicator.getX();
        int startWidth = Math.max(1, indicator.getWidth());
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f);
        indicatorAnimator.setDuration(220L);
        indicatorAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            int width = Math.round(startWidth + (targetWidth - startWidth) * progress);
            float x = startX + (targetX - startX) * progress;
            applyIndicatorBounds(Math.round(x), width);
        });
        indicatorAnimator.start();
    }

    private int relativeLeft(View target) {
        int[] targetLocation = new int[2];
        int[] containerLocation = new int[2];
        target.getLocationInWindow(targetLocation);
        container.getLocationInWindow(containerLocation);
        return targetLocation[0] - containerLocation[0];
    }

    private void setIndicatorBounds(int x, int width) {
        applyIndicatorBounds(x, width);
        indicator.setTranslationX(0f);
    }

    private void applyIndicatorBounds(int x, int width) {
        ViewGroup.LayoutParams params = indicator.getLayoutParams();
        params.width = Math.max(1, width);
        indicator.setLayoutParams(params);
        indicator.setX(x);
    }

    private void cancelIndicatorAnimation() {
        if (indicatorAnimator == null) return;
        indicatorAnimator.cancel();
        indicatorAnimator = null;
    }
}
