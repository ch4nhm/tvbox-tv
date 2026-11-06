package com.ch4nhm.tvapp;

/**
 * Pure description of the usable TV viewport after system insets and optional
 * overscan protection have been applied.
 */
final class TvViewport {

    enum SizeClass {
        COMPACT,
        REGULAR,
        LARGE
    }

    final int rawWidthPx;
    final int rawHeightPx;
    final float density;
    final float fontScale;
    final int safeLeftPx;
    final int safeTopPx;
    final int safeRightPx;
    final int safeBottomPx;
    final float widthDp;
    final float heightDp;
    final float usableWidthDp;
    final float usableHeightDp;
    final float aspectRatio;
    final SizeClass sizeClass;

    private TvViewport(int rawWidthPx, int rawHeightPx, float density, float fontScale,
                       int safeLeftPx, int safeTopPx, int safeRightPx, int safeBottomPx) {
        this.rawWidthPx = Math.max(1, rawWidthPx);
        this.rawHeightPx = Math.max(1, rawHeightPx);
        this.density = clamp(density, 0.1f, 16f);
        this.fontScale = clamp(fontScale, 0.5f, 2f);
        this.safeLeftPx = Math.max(0, safeLeftPx);
        this.safeTopPx = Math.max(0, safeTopPx);
        this.safeRightPx = Math.max(0, safeRightPx);
        this.safeBottomPx = Math.max(0, safeBottomPx);

        widthDp = this.rawWidthPx / this.density;
        heightDp = this.rawHeightPx / this.density;
        usableWidthDp = Math.max(1f,
                (this.rawWidthPx - this.safeLeftPx - this.safeRightPx) / this.density);
        usableHeightDp = Math.max(1f,
                (this.rawHeightPx - this.safeTopPx - this.safeBottomPx) / this.density);
        aspectRatio = usableWidthDp / Math.max(1f, usableHeightDp);
        // Safety margins reduce the drawable area but do not change the
        // physical panel class or the intended ten-foot typography.
        sizeClass = classify(widthDp, heightDp);
    }

    static TvViewport of(int widthPx, int heightPx, float density, float fontScale,
                         int systemLeftPx, int systemTopPx,
                         int systemRightPx, int systemBottomPx,
                         float overscanFraction) {
        int safeWidth = Math.max(1, widthPx);
        int safeHeight = Math.max(1, heightPx);
        float fraction = clamp(overscanFraction, 0f, 0.12f);
        int overscanX = Math.round(safeWidth * fraction);
        int overscanY = Math.round(safeHeight * fraction);
        int left = Math.max(Math.max(0, systemLeftPx), overscanX);
        int top = Math.max(Math.max(0, systemTopPx), overscanY);
        int right = Math.max(Math.max(0, systemRightPx), overscanX);
        int bottom = Math.max(Math.max(0, systemBottomPx), overscanY);

        // Never let combined insets consume the complete viewport.
        if (left + right >= safeWidth) {
            left = 0;
            right = 0;
        }
        if (top + bottom >= safeHeight) {
            top = 0;
            bottom = 0;
        }
        return new TvViewport(safeWidth, safeHeight, density, fontScale,
                left, top, right, bottom);
    }

    static TvViewport ofDp(float widthDp, float heightDp, SizeClass sizeClass,
                           float fontScale) {
        int density = 100;
        TvViewport measured = new TvViewport(
                Math.round(Math.max(1f, widthDp) * density),
                Math.round(Math.max(1f, heightDp) * density),
                density, fontScale, 0, 0, 0, 0);
        if (sizeClass == null || sizeClass == measured.sizeClass) return measured;
        return new TvViewport(measured.rawWidthPx, measured.rawHeightPx, density,
                fontScale, 0, 0, 0, 0, sizeClass);
    }

    private TvViewport(int rawWidthPx, int rawHeightPx, float density, float fontScale,
                       int safeLeftPx, int safeTopPx, int safeRightPx, int safeBottomPx,
                       SizeClass forcedClass) {
        this.rawWidthPx = rawWidthPx;
        this.rawHeightPx = rawHeightPx;
        this.density = density;
        this.fontScale = fontScale;
        this.safeLeftPx = safeLeftPx;
        this.safeTopPx = safeTopPx;
        this.safeRightPx = safeRightPx;
        this.safeBottomPx = safeBottomPx;
        widthDp = rawWidthPx / density;
        heightDp = rawHeightPx / density;
        usableWidthDp = widthDp;
        usableHeightDp = heightDp;
        aspectRatio = usableWidthDp / Math.max(1f, usableHeightDp);
        sizeClass = forcedClass;
    }

    String signature() {
        return rawWidthPx + "x" + rawHeightPx
                + "@" + Math.round(density * 1000f)
                + "/" + Math.round(fontScale * 1000f)
                + ":" + safeLeftPx + "," + safeTopPx + ","
                + safeRightPx + "," + safeBottomPx;
    }

    private static SizeClass classify(float widthDp, float heightDp) {
        float shortSide = Math.min(widthDp, heightDp);
        float longSide = Math.max(widthDp, heightDp);
        if (shortSide < 520f || longSide < 880f) return SizeClass.COMPACT;
        if (shortSide >= 650f || longSide >= 1400f) return SizeClass.LARGE;
        return SizeClass.REGULAR;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
