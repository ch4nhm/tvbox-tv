package com.ch4nhm.tvapp;

import java.util.Objects;

final class SearchSuggestion {

    final String title;
    final String detail;
    final ContentItem localItem;

    SearchSuggestion(String title, String detail, ContentItem localItem) {
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.localItem = localItem;
    }

    boolean isLocal() {
        return localItem != null;
    }

    String identity() {
        if (localItem != null) {
            return "local\u0000" + SearchRanker.key(localItem)
                    + "\u0000" + SearchTextMatcher.normalize(title);
        }
        return "remote\u0000" + SearchTextMatcher.normalize(title);
    }

    boolean sameContent(SearchSuggestion other) {
        return other != null
                && Objects.equals(title, other.title)
                && Objects.equals(detail, other.detail)
                && isLocal() == other.isLocal();
    }
}
