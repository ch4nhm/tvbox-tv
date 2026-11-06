package com.ch4nhm.tvapp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans provider metadata before it reaches the TV layout. TVBox sources
 * frequently use fields such as "评分：0" as promotional filler.
 */
final class ContentLabels {

    private static final Pattern RATING = Pattern.compile(
            "^(?:评分|豆瓣|score)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:分)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE = Pattern.compile(
            "^(?:免费|免费分享|高清|HD|蓝光|超清|推荐|热播|更新中|已完结)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE = Pattern.compile(
            "^(?:更新至|全)\\s*第?\\s*\\d+\\s*[集话期]?.*$|^已完结$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?i)^(?:HD|4K|8K|高码|高清|超清|蓝光|中字|国语|粤语|英语|双语|"
                    + "HDR|DV|WEB[- ]?DL|BluRay).*$");

    private ContentLabels() {
    }

    static String badge(ContentItem item) {
        if (item == null) return "";
        String meta = clean(item.meta);
        String rating = rating(meta);
        if (rating != null) return rating;
        String remark = clean(item.remark);
        rating = rating(remark);
        if (rating != null) return rating;
        return "";
    }

    static String heroEyebrow(ContentItem item) {
        if (item == null) return "TVBox 精选";
        String meta = clean(item.meta);
        if (isDisplayMeta(meta)) {
            return joinMeta(item.year, meta);
        }
        String remark = clean(item.remark);
        if (isDisplayMeta(remark)) {
            return joinMeta(item.year, shorten(remark, 28));
        }
        return joinMeta(item.year, "正在热播");
    }

    static String heroDescription(ContentItem item) {
        if (item == null) return "";
        String remark = clean(item.remark);
        if (isDisplayMeta(remark)) {
            return shorten(remark, 72);
        }
        return "进入详情选择线路，查看剧集与播放信息。";
    }

    private static boolean isDisplayMeta(String value) {
        return !value.isEmpty()
                && rating(value) == null
                && !NOISE.matcher(value).matches()
                && !UPDATE.matcher(value).matches()
                && !TECHNICAL.matcher(value).matches();
    }

    private static String joinMeta(String year, String label) {
        String cleanYear = clean(year);
        return cleanYear.isEmpty() ? label : cleanYear + " · " + label;
    }

    private static String rating(String value) {
        if (value.isEmpty()) return null;
        Matcher matcher = RATING.matcher(value);
        if (!matcher.matches()) return null;
        try {
            float score = Float.parseFloat(matcher.group(1));
            if (score <= 0f) return "";
            String number = score == Math.round(score)
                    ? String.format(Locale.getDefault(), "%.0f", score)
                    : String.format(Locale.getDefault(), "%.1f", score);
            return "评分 " + number;
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ')
                .replaceAll("[\\p{Cf}]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }
}
