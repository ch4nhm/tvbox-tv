package com.ch4nhm.tvapp;

/**
 * Central layout dimensions derived from usable viewport height and width.
 * This class intentionally has no Android dependencies so the column and card
 * calculations can be unit tested without a TV or emulator.
 */
final class TvLayoutMetrics {

    private static final float POSTER_ASPECT = 1.5f;
    private static final float WIDE_ASPECT = 9f / 16f;

    final float contentMarginDp;
    final float cardGapDp;
    final int navHeightDp;
    final int sectionHeaderHeightDp;
    final int buttonHeightDp;

    final int posterColumns;
    final int posterCardWidthDp;
    final int posterVisualInsetDp;
    final float posterTitleTextSizeSp;

    final int channelColumns;
    final int channelCardWidthDp;
    final int channelCardHeightDp;
    final int channelGapDp;
    final float channelNameTextSizeSp;

    final int episodeColumns;
    final int episodeItemHeightDp;
    final int episodeGapDp;
    final float episodeTextSizeSp;

    final int continueCardWidthDp;
    final int continueImageHeightDp;
    final int continueRowHeightDp;

    final float pageTitleTextSizeSp;
    final float sectionTitleTextSizeSp;
    final float topNavTextSizeSp;
    final int topNavItemHeightDp;
    final int topActionSizeDp;
    final int detailHeroHeightDp;
    final int detailPosterWidthDp;
    final int detailPosterHeightDp;
    final int detailEpisodeHeaderHeightDp;
    final int liveHeaderHeightDp;
    final int settingsHeaderHeightDp;
    final int pageHeaderHeightDp;
    final int filterBarHeightDp;
    final int playerPanelWidthDp;
    final int dialogMaxWidthDp;
    final int dialogMaxHeightDp;

    private TvLayoutMetrics(float widthDp, float heightDp,
                            TvViewport.SizeClass sizeClass, float fontScale) {
        float width = Math.max(320f, widthDp);
        float height = Math.max(240f, heightDp);
        float textScale = clamp(fontScale, 0.85f, 1.3f);
        float controlScale = 1f + (textScale - 1f) * 0.35f;
        boolean compact = sizeClass == TvViewport.SizeClass.COMPACT;
        boolean large = sizeClass == TvViewport.SizeClass.LARGE;

        contentMarginDp = clamp(width * 0.0417f,
                compact ? 22f : 30f,
                large ? 58f : 48f);
        cardGapDp = clamp(width * 0.01875f, 12f, 24f);
        navHeightDp = Math.round(clamp(height * 0.178f,
                compact ? 78f : 88f,
                large ? 116f : 104f) + (textScale - 1f) * 14f);
        sectionHeaderHeightDp = Math.round(clamp(height * 0.115f,
                compact ? 48f : 56f,
                large ? 76f : 66f) + (textScale - 1f) * 8f);
        buttonHeightDp = Math.round(clamp(height * 0.096f,
                compact ? 44f : 48f,
                large ? 62f : 56f) * controlScale);

        posterTitleTextSizeSp = compact ? 16f : large ? 19f : 17.5f;
        float posterTitleAreaDp = posterTitleTextSizeSp * textScale * 1.45f + 10f;
        float posterHeightBudgetDp = Math.max(210f, height * 0.74f);
        float heightLimitedMinWidth = Math.max(104f,
                (posterHeightBudgetDp - posterTitleAreaDp - cardGapDp) / POSTER_ASPECT);
        float basePosterMinWidth = compact ? 132f : large ? 176f : 154f;
        float fontWidthAllowance = Math.max(0f, fontScale - 1f) * 26f;
        float posterMinWidth = Math.max(104f,
                Math.min(basePosterMinWidth + fontWidthAllowance,
                        heightLimitedMinWidth + fontWidthAllowance * 0.35f));
        int maxPosterColumns = compact ? 5 : large ? 7 : 6;
        posterColumns = columnCount(width - contentMarginDp * 2f, cardGapDp,
                posterMinWidth, 2, maxPosterColumns);
        posterCardWidthDp = cardWidth(width - contentMarginDp * 2f,
                cardGapDp, posterColumns);
        posterVisualInsetDp = Math.round(clamp(cardGapDp * 0.35f, 5f, 10f));

        channelGapDp = Math.round(clamp(cardGapDp * 0.78f, 9f, 18f));
        float channelListPadding = compact ? 14f : 22f;
        float channelAvailableWidth = width - contentMarginDp * 2f
                - channelListPadding * 2f;
        float baseChannelMinWidth = compact ? 140f : large ? 210f : 180f;
        float channelMinWidth = baseChannelMinWidth + Math.max(0f, fontScale - 1f) * 30f;
        int maxChannelColumns = compact ? 5 : 6;
        int channels = columnCount(channelAvailableWidth, channelGapDp,
                channelMinWidth, 2, maxChannelColumns);
        int channelWidth = cardWidth(channelAvailableWidth, channelGapDp, channels);
        channelNameTextSizeSp = compact ? 16f : large ? 20f : 18f;
        float channelNameArea = channelNameTextSizeSp * textScale * 1.45f + 20f;
        float channelHeightBudget = Math.max(132f, height * 0.63f);
        while (channels < maxChannelColumns
                && channelWidth * WIDE_ASPECT + channelNameArea > channelHeightBudget) {
            channels++;
            channelWidth = cardWidth(channelAvailableWidth, channelGapDp, channels);
        }
        channelColumns = channels;
        channelCardWidthDp = channelWidth;
        channelCardHeightDp = Math.max(72, Math.round(channelWidth * WIDE_ASPECT));

        episodeGapDp = Math.round(clamp(cardGapDp * 0.66f, 9f, 16f));
        float baseEpisodeMinWidth = compact ? 76f : large ? 116f : 96f;
        float episodeMinWidth = baseEpisodeMinWidth + Math.max(0f, fontScale - 1f) * 20f;
        int maxEpisodeColumns = compact ? 6 : large ? 10 : 8;
        episodeColumns = columnCount(width - contentMarginDp * 2f, episodeGapDp,
                episodeMinWidth, 3, maxEpisodeColumns);
        episodeItemHeightDp = Math.round(clamp(height * 0.099f,
                compact ? 42f : 46f,
                large ? 64f : 56f) * controlScale);
        episodeTextSizeSp = compact ? 14f : large ? 16.5f : 15f;

        continueCardWidthDp = Math.round(clamp(posterCardWidthDp * 1.18f,
                compact ? 158f : 174f,
                large ? 238f : 210f));
        continueImageHeightDp = Math.round(continueCardWidthDp * WIDE_ASPECT);
        continueRowHeightDp = continueImageHeightDp
                + Math.round(posterTitleTextSizeSp * textScale * 1.7f) + 18;

        pageTitleTextSizeSp = compact ? 31f : large ? 40f : 36f;
        sectionTitleTextSizeSp = compact ? 24f : large ? 32f : 28f;
        topNavTextSizeSp = compact ? 22f : large ? 26f : 24f;
        topNavItemHeightDp = Math.round(
                clamp(height * 0.085f, 42f, 56f) * controlScale);
        topActionSizeDp = Math.round(
                clamp(height * 0.096f, 44f, 62f) * controlScale);
        detailHeroHeightDp = Math.round(clamp(height * 0.52f
                + (textScale - 1f) * 60f, 224f, 380f));
        detailPosterWidthDp = Math.round(clamp(detailHeroHeightDp * 0.55f, 132f, 190f));
        detailPosterHeightDp = Math.round(detailPosterWidthDp * POSTER_ASPECT);
        detailEpisodeHeaderHeightDp = Math.round(clamp(height * 0.11f, 52f, 72f));
        liveHeaderHeightDp = Math.round(clamp(height * 0.118f, 54f, 78f)
                + (textScale - 1f) * 10f);
        settingsHeaderHeightDp = Math.round(clamp(height * 0.2f, 88f, 122f)
                + (textScale - 1f) * 12f);
        pageHeaderHeightDp = Math.round(clamp(height * 0.192f, 84f, 116f)
                + (textScale - 1f) * 10f);
        filterBarHeightDp = Math.round(clamp(height * 0.133f, 58f, 86f)
                + (textScale - 1f) * 6f);
        float panelMaximum = Math.max(240f, width - contentMarginDp * 2f);
        float panelMinimum = Math.min(520f, panelMaximum);
        playerPanelWidthDp = Math.round(clamp(width * 0.86f,
                panelMinimum, Math.min(1120f, panelMaximum)));
        dialogMaxWidthDp = Math.round(Math.max(240f,
                width - contentMarginDp * 2f));
        dialogMaxHeightDp = Math.round(Math.max(220f,
                height - contentMarginDp * 1.5f));
    }

    static TvLayoutMetrics forViewport(TvViewport viewport) {
        return new TvLayoutMetrics(viewport.usableWidthDp, viewport.usableHeightDp,
                viewport.sizeClass, viewport.fontScale);
    }

    static TvLayoutMetrics of(float widthDp, float heightDp,
                              TvViewport.SizeClass sizeClass, float fontScale) {
        return new TvLayoutMetrics(widthDp, heightDp, sizeClass, fontScale);
    }

    private static int columnCount(float availableWidthDp, float gapDp,
                                   float minCardWidthDp, int minimum, int maximum) {
        int byWidth = (int) Math.floor(
                (Math.max(1f, availableWidthDp) + gapDp)
                        / Math.max(1f, minCardWidthDp + gapDp));
        return Math.max(minimum, Math.min(maximum, byWidth));
    }

    private static int cardWidth(float availableWidthDp, float gapDp, int columns) {
        return Math.max(1, (int) Math.floor(
                (Math.max(1f, availableWidthDp) - gapDp * Math.max(0, columns - 1))
                        / Math.max(1, columns)));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
