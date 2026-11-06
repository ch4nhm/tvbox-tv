package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchTextMatcherTest {

    @Test
    public void matchesChineseFullPinyinAndInitials() {
        assertTrue(SearchTextMatcher.matchScore("凡人修仙传", "凡人修仙传") > 0);
        assertTrue(SearchTextMatcher.matchScore("凡人修仙传", "fanren") > 0);
        assertTrue(SearchTextMatcher.matchScore("凡人修仙传", "frxxz") > 0);
        assertTrue(SearchTextMatcher.matchScore("凡人修仙传", "fanrenxiuxianzhuan") > 0);
    }

    @Test
    public void matchesOrderedPinyinFragmentsAcrossSkippedSyllables() {
        assertTrue(SearchTextMatcher.matchScore("凡人修仙传", "renxian") > 0);
        assertTrue(SearchTextMatcher.matchScore("凡人修仙传", "fanrenxian") > 0);
        assertFalse(SearchTextMatcher.matchScore("凡人修仙传", "xianren") > 0);
        assertFalse(SearchTextMatcher.matchScore("凡人修仙传", "abc") > 0);
        assertFalse(SearchTextMatcher.matchScore("The Last of Us", "teus") > 0);
    }

    @Test
    public void normalizesSpacesPunctuationWidthAndCase() {
        assertEquals("凡人修仙传2025",
                SearchTextMatcher.normalize(" 凡 人·修仙传（２０２５） "));
        assertEquals("thelastofus",
                SearchTextMatcher.normalize("The Last of Us"));
        assertEquals(SearchTextMatcher.matchScore("凡人修仙传", "凡人修仙传"),
                SearchTextMatcher.matchScore("凡 人·修仙传", "凡人修仙传"));
    }

    @Test
    public void detectsAsciiPinyinQueries() {
        assertTrue(SearchTextMatcher.isPinyinLike("frxxz"));
        assertTrue(SearchTextMatcher.isPinyinLike(" fan ren "));
        assertFalse(SearchTextMatcher.isPinyinLike("凡人"));
        assertFalse(SearchTextMatcher.isPinyinLike(""));
    }
}
