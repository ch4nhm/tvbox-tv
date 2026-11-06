package com.ch4nhm.tvapp;

final class SearchRanker {

    private static final long MATCH_WEIGHT = 1_000L;
    private static final long FAVORITE_BOOST = 24_000L;
    private static final long HISTORY_BOOST = 30_000L;
    private static final long SOURCE_BOOST = 5_000L;

    private SearchRanker() {
    }

    static long score(ContentItem item, String query, int sourceCount,
                      boolean favorite, boolean history) {
        if (item == null) return Long.MIN_VALUE;
        long score = SearchTextMatcher.matchScore(item.title, query) * MATCH_WEIGHT;

        if (item.hits > 0) {
            score += Math.min(60_000L,
                    Math.round(Math.log10(item.hits + 1d) * 8_000d));
        }
        if (item.rating > 0d) {
            score += Math.round(Math.min(10d, item.rating) * 1_500d);
        }
        if (item.pic != null && !item.pic.isBlank()) score += 300L;
        score += Math.max(0, sourceCount - 1) * SOURCE_BOOST;
        if (favorite) score += FAVORITE_BOOST;
        if (history) score += HISTORY_BOOST;
        return score;
    }

    static String key(ContentItem item) {
        if (item == null) return "";
        return key(item.site == null ? "" : item.site.key, item.id);
    }

    static String key(String siteKey, String itemId) {
        return safe(siteKey) + "\u0000" + safe(itemId);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
