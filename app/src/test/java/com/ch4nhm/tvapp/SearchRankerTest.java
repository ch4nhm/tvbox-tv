package com.ch4nhm.tvapp;

import static org.junit.Assert.assertTrue;

import com.ch4nhm.tvapp.data.SiteConfig;

import org.junit.Test;

public class SearchRankerTest {

    @Test
    public void popularityAndRatingBreakTies() {
        ContentItem low = item("low", "凡人修仙传", 100L, 7.0d);
        ContentItem highHits = item("hits", "凡人修仙传", 120_000L, 7.0d);
        ContentItem highRating = item("rating", "凡人修仙传", 120_000L, 9.2d);

        long lowScore = score(low, false, false);
        long hitsScore = score(highHits, false, false);
        long ratingScore = score(highRating, false, false);

        assertTrue(hitsScore > lowScore);
        assertTrue(ratingScore > hitsScore);
    }

    @Test
    public void historyOutranksFavoriteAndFavoriteOutranksPlain() {
        ContentItem item = item("same", "凡人修仙传", 0L, 0d);
        long plain = score(item, false, false);
        long favorite = score(item, true, false);
        long history = score(item, false, true);

        assertTrue(favorite > plain);
        assertTrue(history > favorite);
    }

    @Test
    public void strongerTextMatchOutranksPopularity() {
        ContentItem exact = item("exact", "凡人修仙传", 0L, 0d);
        ContentItem partial = item("partial", "凡人修仙传之说", 10_000_000L, 10d);

        assertTrue(score(exact, false, false) > score(partial, false, false));
    }

    private static long score(ContentItem item, boolean favorite, boolean history) {
        return SearchRanker.score(item, "凡人修仙传", 1, favorite, history);
    }

    private static ContentItem item(String id, String title, long hits, double rating) {
        SiteConfig site = new SiteConfig();
        site.key = "site";
        site.name = "测试源";
        return new ContentItem(id, title, "剧集", "2025", 0, -1,
                "", "", site, hits, rating);
    }
}
