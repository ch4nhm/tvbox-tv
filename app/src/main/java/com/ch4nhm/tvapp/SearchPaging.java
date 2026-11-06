package com.ch4nhm.tvapp;

final class SearchPaging {

    private SearchPaging() {
    }

    static int visibleCount(int sourceSize, int requested, int pageSize) {
        if (sourceSize <= 0 || pageSize <= 0) return 0;
        return Math.min(sourceSize, Math.max(pageSize, requested));
    }
}
