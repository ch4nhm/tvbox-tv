package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TvViewportTest {

    private static final float EPSILON = 0.05f;

    @Test
    public void standardTelevisionResolutionsShareTheSameLogicalViewport() {
        assertStandardViewport(TvViewport.of(1280, 720, 4f / 3f, 1f,
                0, 0, 0, 0, 0f));
        assertStandardViewport(TvViewport.of(1920, 1080, 2f, 1f,
                0, 0, 0, 0, 0f));
        assertStandardViewport(TvViewport.of(3840, 2160, 4f, 1f,
                0, 0, 0, 0, 0f));
    }

    @Test
    public void aspectRatiosRemainUsableInsteadOfBeingForcedTo16By9() {
        TvViewport wide = TvViewport.of(1920, 1200, 2f, 1f,
                0, 0, 0, 0, 0f);
        assertEquals(960f, wide.usableWidthDp, EPSILON);
        assertEquals(600f, wide.usableHeightDp, EPSILON);
        assertEquals(TvViewport.SizeClass.REGULAR, wide.sizeClass);

        TvViewport classic = TvViewport.of(1440, 1080, 2f, 1f,
                0, 0, 0, 0, 0f);
        assertEquals(720f, classic.usableWidthDp, EPSILON);
        assertEquals(540f, classic.usableHeightDp, EPSILON);
        assertEquals(TvViewport.SizeClass.COMPACT, classic.sizeClass);

        TvViewport ultrawide = TvViewport.of(2560, 1080, 2f, 1f,
                0, 0, 0, 0, 0f);
        assertEquals(1280f, ultrawide.usableWidthDp, EPSILON);
        assertEquals(540f, ultrawide.usableHeightDp, EPSILON);
        assertEquals(TvViewport.SizeClass.REGULAR, ultrawide.sizeClass);
    }

    @Test
    public void overscanAndSystemInsetsReduceTheUsableArea() {
        TvViewport overscan = TvViewport.of(1920, 1080, 2f, 1f,
                0, 0, 0, 0, 0.05f);
        assertEquals(96, overscan.safeLeftPx);
        assertEquals(54, overscan.safeTopPx);
        assertEquals(864f, overscan.usableWidthDp, EPSILON);
        assertEquals(486f, overscan.usableHeightDp, EPSILON);

        TvViewport insets = TvViewport.of(1920, 1080, 2f, 1f,
                40, 20, 30, 10, 0f);
        assertEquals(925f, insets.usableWidthDp, EPSILON);
        assertEquals(525f, insets.usableHeightDp, EPSILON);
    }

    @Test
    public void selectedSafetyMarginsUseTenthsOfAPercent() {
        TvViewport twoPointFivePercent = TvViewport.of(1920, 1080, 2f, 1f,
                0, 0, 0, 0, 25 / 1000f);
        TvViewport fivePercent = TvViewport.of(1920, 1080, 2f, 1f,
                0, 0, 0, 0, 50 / 1000f);

        assertEquals(48, twoPointFivePercent.safeLeftPx);
        assertEquals(27, twoPointFivePercent.safeTopPx);
        assertEquals(96, fivePercent.safeLeftPx);
        assertEquals(54, fivePercent.safeTopPx);
        assertEquals(912f, twoPointFivePercent.usableWidthDp, EPSILON);
        assertEquals(864f, fivePercent.usableWidthDp, EPSILON);
        assertEquals(TvViewport.SizeClass.REGULAR,
                twoPointFivePercent.sizeClass);
        assertEquals(TvViewport.SizeClass.REGULAR, fivePercent.sizeClass);
    }

    @Test
    public void largeFontScaleIsPreservedForResponsiveMetrics() {
        TvViewport viewport = TvViewport.of(1920, 1080, 2f, 1.3f,
                0, 0, 0, 0, 0f);
        assertEquals(1.3f, viewport.fontScale, EPSILON);
        assertTrue(TvLayoutMetrics.forViewport(viewport).posterCardWidthDp > 0);
    }

    @Test
    public void absurdInsetsNeverCollapseTheViewport() {
        TvViewport viewport = TvViewport.of(1920, 1080, 2f, 1f,
                2000, 2000, 2000, 2000, 0f);
        assertTrue(viewport.usableWidthDp > 0f);
        assertTrue(viewport.usableHeightDp > 0f);
    }

    private static void assertStandardViewport(TvViewport viewport) {
        assertEquals(960f, viewport.usableWidthDp, EPSILON);
        assertEquals(540f, viewport.usableHeightDp, EPSILON);
        assertEquals(TvViewport.SizeClass.REGULAR, viewport.sizeClass);
    }
}
