package com.ch4nhm.tvapp;

import com.ch4nhm.tvapp.data.SiteConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups search results by their playback source. The result page only binds
 * one source at a time and paginates that source, so the complete search result
 * remains available without creating a large cross-source card list.
 */
final class SearchResultAggregator {

    private SearchResultAggregator() {
    }

    static Result aggregate(List<ContentItem> raw) {
        Map<String, SourceBuilder> grouped = new LinkedHashMap<>();
        Set<String> exact = new HashSet<>();

        if (raw != null) {
            for (ContentItem item : raw) {
                if (!usable(item)) continue;
                String identity = exactIdentity(item);
                if (!exact.add(identity)) continue;

                String sourceKey = sourceKey(item);
                SourceBuilder group = grouped.get(sourceKey);
                if (group == null) {
                    group = new SourceBuilder(sourceKey, sourceTitle(item));
                    grouped.put(sourceKey, group);
                }
                group.items.add(item);
            }
        }

        List<SourceGroup> sources = new ArrayList<>(grouped.size());
        int itemCount = 0;
        for (SourceBuilder builder : grouped.values()) {
            if (builder.items.isEmpty()) continue;
            itemCount += builder.items.size();
            sources.add(new SourceGroup(builder.key, builder.title, builder.items));
        }
        sources.sort((left, right) -> {
            int order = left.title.compareToIgnoreCase(right.title);
            if (order != 0) return order;
            return left.key.compareTo(right.key);
        });
        return new Result(sources, itemCount);
    }

    private static boolean usable(ContentItem item) {
        return item != null && item.title != null && !item.title.isBlank()
                && (item.id != null && !item.id.isBlank()
                || item.pic != null && !item.pic.isBlank());
    }

    private static String exactIdentity(ContentItem item) {
        String source = sourceKey(item);
        String id = safe(item.id).trim();
        if (!id.isEmpty()) return source + "\u0000id\u0000" + id;
        return source + "\u0000fallback\u0000"
                + SearchTextMatcher.normalize(item.title) + "\u0000"
                + SearchTextMatcher.normalize(item.year) + "\u0000"
                + safe(item.pic);
    }

    private static String sourceKey(ContentItem item) {
        SiteConfig site = item == null ? null : item.site;
        String key = site == null ? "" : safe(site.key).trim();
        if (!key.isEmpty()) return key;
        String name = site == null ? "" : safe(site.name).trim();
        return name.isEmpty() ? "tvbox" : "name:" + name;
    }

    private static String sourceTitle(ContentItem item) {
        SiteConfig site = item == null ? null : item.site;
        if (site == null) return "TVBox";
        String title = safe(site.displayName()).trim();
        return title.isEmpty() ? "TVBox" : title;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class SourceGroup {
        final String key;
        final String title;
        final List<ContentItem> items;

        SourceGroup(String key, String title, List<ContentItem> items) {
            this.key = key == null ? "" : key;
            this.title = title == null || title.isBlank() ? "TVBox" : title;
            this.items = List.copyOf(items);
        }

        int size() {
            return items.size();
        }
    }

    static final class Result {
        final List<SourceGroup> sources;
        final int itemCount;

        Result(List<SourceGroup> sources, int itemCount) {
            this.sources = List.copyOf(sources);
            this.itemCount = Math.max(0, itemCount);
        }
    }

    private static final class SourceBuilder {
        final String key;
        final String title;
        final List<ContentItem> items = new ArrayList<>();

        SourceBuilder(String key, String title) {
            this.key = key;
            this.title = title;
        }
    }
}
