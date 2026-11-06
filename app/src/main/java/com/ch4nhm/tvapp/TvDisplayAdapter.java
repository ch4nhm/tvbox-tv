package com.ch4nhm.tvapp;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowMetrics;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Normalizes only outlier density configurations and exposes one viewport
 * model to layout, grid and image-decoding code.
 */
final class TvDisplayAdapter {

    private static final String TAG = "TvDisplay";
    private static final float MIN_COMFORTABLE_SHORT_SIDE_DP = 480f;
    private static final float MAX_COMFORTABLE_SHORT_SIDE_DP = 720f;
    private static final float DENSITY_EPSILON = 0.015f;
    private static final Map<Activity, TvViewport> VIEWPORT_CACHE =
            new WeakHashMap<>();

    private TvDisplayAdapter() {
    }

    /**
     * Keeps the legacy TCL density fallback for unusual panels, but no longer
     * forces every television to a 960 x 540 dp viewport.
     */
    static boolean apply(Activity activity) {
        if (activity == null) return false;
        clearViewportCache(activity);
        Display display = activity.getWindowManager().getDefaultDisplay();
        DisplayMetrics physical = new DisplayMetrics();
        display.getRealMetrics(physical);
        if (physical.widthPixels <= 0 || physical.heightPixels <= 0
                || !Float.isFinite(physical.density) || physical.density <= 0f) {
            return false;
        }

        int shortSide = Math.min(physical.widthPixels, physical.heightPixels);
        float naturalShortDp = shortSide / physical.density;
        float targetShortDp = Math.max(MIN_COMFORTABLE_SHORT_SIDE_DP,
                Math.min(MAX_COMFORTABLE_SHORT_SIDE_DP, naturalShortDp));
        float targetDensity = shortSide / targetShortDp;
        if (!Float.isFinite(targetDensity) || targetDensity < 0.5f) return false;

        DisplayMetrics resources = activity.getResources().getDisplayMetrics();
        float fontScale = resources.density > 0f
                ? resources.scaledDensity / resources.density : 1f;
        if (Math.abs(resources.density - targetDensity) <= DENSITY_EPSILON) {
            return false;
        }

        Log.i(TAG, "Adapt density " + physical.widthPixels + "x" + physical.heightPixels
                + " from " + resources.density + " to " + targetDensity
                + " (" + Math.round(naturalShortDp) + "dp -> "
                + Math.round(targetShortDp) + "dp short side)");
        resources.density = targetDensity;
        resources.scaledDensity = targetDensity * fontScale;
        resources.densityDpi = Math.round(targetDensity * DisplayMetrics.DENSITY_DEFAULT);
        return true;
    }

    static TvViewport viewport(Context context) {
        Activity activity = activityFor(context);
        if (activity != null) {
            synchronized (VIEWPORT_CACHE) {
                TvViewport cached = VIEWPORT_CACHE.get(activity);
                if (cached != null) return cached;
            }
        }
        TvViewport measured = measureViewport(context, activity);
        if (activity != null) {
            synchronized (VIEWPORT_CACHE) {
                VIEWPORT_CACHE.put(activity, measured);
            }
        }
        return measured;
    }

    private static TvViewport measureViewport(Context context, Activity activity) {
        if (context == null) {
            return TvViewport.of(1, 1, 1f, 1f, 0, 0, 0, 0, 0f);
        }
        DisplayMetrics resources = context.getResources().getDisplayMetrics();
        float fontScale = resources.density > 0f
                ? resources.scaledDensity / resources.density
                : context.getResources().getConfiguration().fontScale;

        int widthPx = resources.widthPixels;
        int heightPx = resources.heightPixels;
        int[] insets = new int[4];
        if (activity != null && !activity.isFinishing()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics window = activity.getWindowManager().getCurrentWindowMetrics();
                Rect bounds = window.getBounds();
                widthPx = bounds.width();
                heightPx = bounds.height();
                android.graphics.Insets safe = window.getWindowInsets()
                        .getInsets(
                                WindowInsets.Type.systemBars()
                                        | WindowInsets.Type.displayCutout());
                insets[0] = safe.left;
                insets[1] = safe.top;
                insets[2] = safe.right;
                insets[3] = safe.bottom;
            } else {
                Point size = new Point();
                activity.getWindowManager().getDefaultDisplay().getRealSize(size);
                widthPx = size.x;
                heightPx = size.y;
            }
        } else {
            Display display = context.getSystemService(Display.class);
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                widthPx = size.x;
                heightPx = size.y;
            }
        }

        int overscanPermille = new AppSettings(context).getOverscanPermille();
        return TvViewport.of(widthPx, heightPx, resources.density, fontScale,
                insets[0], insets[1], insets[2], insets[3],
                overscanPermille / 1000f);
    }

    static TvViewport applySafeArea(Activity activity) {
        if (activity == null) {
            return TvViewport.of(1, 1, 1f, 1f, 0, 0, 0, 0, 0f);
        }
        TvViewport viewport = refreshViewport(activity);
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup group) || group.getChildCount() == 0) {
            return viewport;
        }
        View root = group.getChildAt(0);
        int[] base = root.getTag(R.id.safe_area_base_padding) instanceof int[] values
                ? values : null;
        if (base == null) {
            base = new int[]{
                    root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), root.getPaddingBottom()
            };
            root.setTag(R.id.safe_area_base_padding, base);
        }
        int left = Math.max(base[0], viewport.safeLeftPx);
        int top = Math.max(base[1], viewport.safeTopPx);
        int right = Math.max(base[2], viewport.safeRightPx);
        int bottom = Math.max(base[3], viewport.safeBottomPx);
        if (root.getPaddingLeft() != left || root.getPaddingTop() != top
                || root.getPaddingRight() != right || root.getPaddingBottom() != bottom) {
            root.setPadding(left, top, right, bottom);
        }
        return viewport;
    }

    static TvLayoutMetrics metrics(Context context) {
        return TvLayoutMetrics.forViewport(viewport(context));
    }

    static int posterGridSpanCount(Context context) {
        return metrics(context).posterColumns;
    }

    static int posterCardWidthDp(Context context) {
        return metrics(context).posterCardWidthDp;
    }

    static int episodeGridSpanCount(Context context) {
        return metrics(context).episodeColumns;
    }

    static int channelGridSpanCount(Context context) {
        return metrics(context).channelColumns;
    }

    static int posterGapPx(Context context) {
        return TvLayout.dp(context, metrics(context).cardGapDp);
    }

    static int episodeGapPx(Context context) {
        return TvLayout.dp(context, metrics(context).episodeGapDp);
    }

    static int channelGapPx(Context context) {
        return TvLayout.dp(context, metrics(context).channelGapDp);
    }

    private static Activity activityFor(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper wrapper) {
            if (current instanceof Activity activity) return activity;
            current = wrapper.getBaseContext();
        }
        return current instanceof Activity activity ? activity : null;
    }

    private static TvViewport refreshViewport(Activity activity) {
        clearViewportCache(activity);
        return viewport(activity);
    }

    private static void clearViewportCache(Activity activity) {
        if (activity == null) return;
        synchronized (VIEWPORT_CACHE) {
            VIEWPORT_CACHE.remove(activity);
        }
    }
}
