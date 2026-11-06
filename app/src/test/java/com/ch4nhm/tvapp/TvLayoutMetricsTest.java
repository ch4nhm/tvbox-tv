package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TvLayoutMetricsTest {

    @Test
    public void regularTvKeepsFiveComfortablePosterColumns() {
        TvLayoutMetrics metrics = metrics(960f, 540f,
                TvViewport.SizeClass.REGULAR, 1f);
        assertEquals(5, metrics.posterColumns);
        assertTrue(metrics.posterCardWidthDp >= 150);
        assertTrue(metrics.posterCardWidthDp <= 175);
    }

    @Test
    public void columnsGrowOnLargeUsableWidthWithoutOversizedCards() {
        TvLayoutMetrics hd = metrics(1280f, 720f,
                TvViewport.SizeClass.LARGE, 1f);
        TvLayoutMetrics uhd = metrics(1920f, 1080f,
                TvViewport.SizeClass.LARGE, 1f);
        assertTrue(hd.posterColumns >= 5);
        assertTrue(uhd.posterColumns > hd.posterColumns);
        assertTrue(uhd.posterColumns <= 7);
        assertTrue(uhd.posterCardWidthDp < 300);
    }

    @Test
    public void compactTvUsesMoreButSmallerColumns() {
        TvLayoutMetrics metrics = metrics(640f, 360f,
                TvViewport.SizeClass.COMPACT, 1f);
        assertTrue(metrics.posterColumns >= 3);
        assertTrue(metrics.posterColumns <= 5);
        assertTrue(metrics.posterCardWidthDp < 160);
    }

    @Test
    public void fourByThreeAndUltraWideViewportsRemainComfortable() {
        TvLayoutMetrics fourByThree = metrics(720f, 540f,
                TvViewport.SizeClass.COMPACT, 1f);
        TvLayoutMetrics ultraWide = metrics(1280f, 540f,
                TvViewport.SizeClass.REGULAR, 1f);

        assertTrue(fourByThree.posterColumns >= 3);
        assertTrue(fourByThree.posterColumns <= 5);
        assertTrue(fourByThree.posterCardWidthDp >= 140);
        assertTrue(ultraWide.posterColumns >= 5);
        assertTrue(ultraWide.posterColumns <= 6);
        assertTrue(ultraWide.posterCardWidthDp < 220);
    }

    @Test
    public void portraitCardsFitTheUsableHeightBudget() {
        TvViewport.SizeClass[] classes = {
                TvViewport.SizeClass.COMPACT,
                TvViewport.SizeClass.REGULAR,
                TvViewport.SizeClass.LARGE
        };
        float[][] sizes = {{640, 360}, {960, 540}, {1920, 1080}};
        for (int index = 0; index < sizes.length; index++) {
            TvLayoutMetrics metrics = metrics(sizes[index][0], sizes[index][1],
                    classes[index], 1f);
            float approximateItemHeight = metrics.posterCardWidthDp * 1.5f
                    + metrics.posterTitleTextSizeSp * 2f;
            assertTrue(approximateItemHeight <= sizes[index][1] * 0.92f);
        }
    }

    @Test
    public void largerFontReducesColumnsInsteadOfClippingTitles() {
        TvLayoutMetrics normal = metrics(960f, 540f,
                TvViewport.SizeClass.REGULAR, 1f);
        TvLayoutMetrics large = metrics(960f, 540f,
                TvViewport.SizeClass.REGULAR, 1.3f);
        assertTrue(large.posterColumns <= normal.posterColumns);
        assertTrue(large.buttonHeightDp > normal.buttonHeightDp);
        assertTrue(large.episodeItemHeightDp > normal.episodeItemHeightDp);
        assertTrue(large.detailHeroHeightDp > normal.detailHeroHeightDp);
    }

    @Test
    public void channelAndEpisodeGridsStayInsideSensibleBounds() {
        TvLayoutMetrics metrics = metrics(960f, 540f,
                TvViewport.SizeClass.REGULAR, 1f);
        assertTrue(metrics.channelColumns >= 2);
        assertTrue(metrics.channelColumns <= 6);
        assertTrue(metrics.channelCardHeightDp < 150);
        assertTrue(metrics.episodeColumns >= 3);
        assertTrue(metrics.episodeColumns <= 10);
        assertTrue(metrics.episodeItemHeightDp >= 42);
    }

    @Test
    public void dimensionsScaleForLargePlayersAndDialogs() {
        TvLayoutMetrics regular = metrics(960f, 540f,
                TvViewport.SizeClass.REGULAR, 1f);
        TvLayoutMetrics large = metrics(1920f, 1080f,
                TvViewport.SizeClass.LARGE, 1f);
        assertTrue(large.playerPanelWidthDp > regular.playerPanelWidthDp);
        assertTrue(large.playerPanelWidthDp <= 1120);
        assertTrue(large.dialogMaxHeightDp > regular.dialogMaxHeightDp);
        assertTrue(regular.detailPosterWidthDp >= 132);
    }

    private static TvLayoutMetrics metrics(float widthDp, float heightDp,
                                           TvViewport.SizeClass sizeClass,
                                           float fontScale) {
        return TvLayoutMetrics.of(widthDp, heightDp, sizeClass, fontScale);
    }
}
