package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.ch4nhm.tvapp.data.SiteConfig;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SearchResultAggregatorTest {

    @Test
    public void groupsResultsBySourceAndPreservesOrder() {
        ContentItem first = item("1", "凡人修仙传", "source-a");
        ContentItem second = item("2", "牧神记", "source-a");
        ContentItem third = item("3", "凡人修仙传", "source-b");

        SearchResultAggregator.Result result = SearchResultAggregator.aggregate(
                List.of(first, third, second));

        assertEquals(2, result.sources.size());
        assertEquals(3, result.itemCount);
        SearchResultAggregator.SourceGroup sourceA = source(result, "source-a");
        assertEquals(2, sourceA.items.size());
        assertSame(first, sourceA.items.get(0));
        assertSame(second, sourceA.items.get(1));
        assertEquals(1, source(result, "source-b").items.size());
    }

    @Test
    public void removesDuplicateWithinSourceButKeepsSameTitleAcrossSources() {
        ContentItem first = item("same-id", "凡人修仙传", "source-a");
        ContentItem duplicate = item("same-id", "凡人修仙传", "source-a");
        ContentItem otherSource = item("same-id", "凡人修仙传", "source-b");

        SearchResultAggregator.Result result = SearchResultAggregator.aggregate(
                List.of(first, duplicate, otherSource));

        assertEquals(2, result.sources.size());
        assertEquals(2, result.itemCount);
        assertEquals(1, source(result, "source-a").items.size());
        assertEquals(1, source(result, "source-b").items.size());
    }

    @Test
    public void keepsDistinctItemsWithoutIdsWithinSource() {
        ContentItem first = item("", "凡人修仙传", "source-a");
        ContentItem second = item("", "牧神记", "source-a");

        SearchResultAggregator.Result result =
                SearchResultAggregator.aggregate(List.of(first, second));

        assertEquals(1, result.sources.size());
        assertEquals(2, result.itemCount);
        assertEquals(2, result.sources.get(0).items.size());
    }

    @Test
    public void keepsAllSourcesAndItems() {
        List<ContentItem> items = new ArrayList<>();
        for (int source = 0; source < 70; source++) {
            for (int index = 0; index < 180; index++) {
                items.add(item("id-" + index, "影片 " + index, "source-" + source));
            }
        }

        SearchResultAggregator.Result result =
                SearchResultAggregator.aggregate(items);

        assertEquals(70, result.sources.size());
        assertEquals(70 * 180, result.itemCount);
        assertEquals(180, result.sources.get(0).items.size());
    }

    @Test
    public void ignoresNullAndUnusableItems() {
        SearchResultAggregator.Result result =
                SearchResultAggregator.aggregate(null);

        assertEquals(0, result.sources.size());
        assertEquals(0, result.itemCount);
    }

    private static SearchResultAggregator.SourceGroup source(
            SearchResultAggregator.Result result, String key) {
        for (SearchResultAggregator.SourceGroup source : result.sources) {
            if (source.key.equals(key)) return source;
        }
        throw new AssertionError("Missing source " + key);
    }

    private static ContentItem item(String id, String title, String siteKey) {
        SiteConfig site = new SiteConfig();
        site.key = siteKey;
        site.name = siteKey;
        return new ContentItem(id, title, "剧集", "2025", 0, -1,
                id == null || id.isBlank() ? "https://example.com/poster.jpg" : "", "",
                site, 0L, 0d);
    }
}
