package com.ch4nhm.tvapp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses popularity and score values found in TVBox site responses.
 */
final class SearchMetrics {

    private static final Pattern NUMBER =
            Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

    private SearchMetrics() {
    }

    static long parseHits(String value) {
        if (value == null || value.isBlank()) return 0L;
        String normalized = value.replace(",", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        Matcher matcher = NUMBER.matcher(normalized);
        if (!matcher.find()) return 0L;

        double amount;
        try {
            amount = Double.parseDouble(matcher.group());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
        String suffix = normalized.substring(matcher.end());
        if (suffix.startsWith("亿")) amount *= 100_000_000d;
        else if (suffix.startsWith("万") || suffix.startsWith("w")) amount *= 10_000d;
        else if (suffix.startsWith("千") || suffix.startsWith("k")) amount *= 1_000d;
        if (!Double.isFinite(amount) || amount <= 0d) return 0L;
        return amount >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(amount);
    }

    static double parseRating(String value) {
        if (value == null || value.isBlank()) return 0d;
        Matcher matcher = NUMBER.matcher(value.replace(",", ""));
        if (!matcher.find()) return 0d;
        try {
            double rating = Double.parseDouble(matcher.group());
            return Double.isFinite(rating) ? Math.max(0d, rating) : 0d;
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }
}
