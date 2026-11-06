package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SearchPagingTest {

    @Test
    public void neverRequestsMoreItemsThanSourceHas() {
        assertEquals(5, SearchPaging.visibleCount(5, 24, 24));
        assertEquals(24, SearchPaging.visibleCount(24, 24, 24));
    }

    @Test
    public void growsInPagesWithoutExceedingSourceSize() {
        assertEquals(48, SearchPaging.visibleCount(100, 48, 24));
        assertEquals(100, SearchPaging.visibleCount(100, 120, 24));
        assertEquals(24, SearchPaging.visibleCount(25, 24, 24));
    }

    @Test
    public void handlesEmptySourceAndInvalidPageSize() {
        assertEquals(0, SearchPaging.visibleCount(0, 24, 24));
        assertEquals(0, SearchPaging.visibleCount(10, 24, 0));
        assertEquals(24, SearchPaging.visibleCount(100, 1, 24));
    }
}
