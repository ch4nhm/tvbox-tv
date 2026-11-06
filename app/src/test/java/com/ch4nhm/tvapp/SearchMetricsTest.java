package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchMetricsTest {

    @Test
    public void parsesPopularityUnits() {
        assertEquals(12_000L, SearchMetrics.parseHits("1.2万"));
        assertEquals(32_000L, SearchMetrics.parseHits("3.2w次播放"));
        assertEquals(120_000_000L, SearchMetrics.parseHits("1.2亿"));
        assertEquals(15_000L, SearchMetrics.parseHits("15k"));
        assertEquals(12_345L, SearchMetrics.parseHits("12,345"));
    }

    @Test
    public void parsesScoresAndRejectsInvalidValues() {
        assertEquals(9.2d, SearchMetrics.parseRating("9.2分"), 0.0001d);
        assertEquals(8.0d, SearchMetrics.parseRating("评分：8"), 0.0001d);
        assertEquals(0L, SearchMetrics.parseHits("暂无"));
        assertEquals(0d, SearchMetrics.parseRating("暂无"), 0.0001d);
    }
}
