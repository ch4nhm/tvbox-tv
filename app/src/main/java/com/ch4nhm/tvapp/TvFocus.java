package com.ch4nhm.tvapp;

import android.view.View;
import android.view.KeyEvent;
import android.widget.ScrollView;

/**
 * Applies the first meaningful focus after an activity transition has settled.
 * A delayed retry is required because views cannot always take focus while the
 * entering window is still being attached.
 */
public final class TvFocus {

    private static final int REQUEST_ATTEMPTS = 6;
    private static final long RETRY_INTERVAL_MS = 120L;

    private TvFocus() {
    }

    public static void requestInitial(View target) {
        requestInitial(target, null);
    }

    public static void requestInitial(View target, View replaceableFocus) {
        if (target == null) return;
        Runnable request = () -> {
            if (!target.isAttachedToWindow() || !target.isShown()) return;
            View current = target.getRootView().findFocus();
            if (current == target) return;
            if (current == null || current == target.getRootView()
                    || current == replaceableFocus
                    || current instanceof ScrollView
                    || current.getClass().getName().contains("ScrollView")) {
                target.requestFocus();
            }
        };
        for (int attempt = 0; attempt < REQUEST_ATTEMPTS; attempt++) {
            target.postDelayed(request, attempt * RETRY_INTERVAL_MS);
        }
    }

    /**
     * RecyclerViews placed below an overlay header often cannot move focus out
     * of their own bounds on the first row. If there is no viable upward
     * candidate, explicitly return to the page's top control.
     */
    public static boolean returnToTopOnUp(KeyEvent event, View contentRoot, View topTarget) {
        if (event == null || contentRoot == null || topTarget == null
                || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP) {
            return false;
        }
        View focused = contentRoot.getRootView().findFocus();
        if (!isDescendantOf(focused, contentRoot)) return false;

        View upward = focused.focusSearch(View.FOCUS_UP);
        if (isUsableCandidate(upward, focused) && !isDescendantOf(focused, upward)) {
            return false;
        }
        if (!topTarget.isShown() || !topTarget.isFocusable() || !topTarget.isEnabled()) {
            return false;
        }
        return topTarget.requestFocus();
    }

    private static boolean isUsableCandidate(View candidate, View focused) {
        return candidate != null
                && candidate != focused
                && candidate.isAttachedToWindow()
                && candidate.isShown()
                && candidate.isFocusable()
                && candidate.isEnabled();
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        if (child == null || ancestor == null) return false;
        if (child == ancestor) return true;
        View current = child;
        while (current.getParent() instanceof View parent) {
            if (parent == ancestor) return true;
            current = parent;
        }
        return false;
    }
}
