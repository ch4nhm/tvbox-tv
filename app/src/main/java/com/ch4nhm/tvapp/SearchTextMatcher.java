package com.ch4nhm.tvapp;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Matches Chinese titles through their normalised text, full pinyin and pinyin
 * initials. Polyphonic characters are expanded into a bounded set of variants.
 */
final class SearchTextMatcher {

    private static final int MAX_PINYIN_VARIANTS = 64;
    private static final int MAX_CACHE_ENTRIES = 4096;
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = createPinyinFormat();
    private static final Map<String, PinyinForms> PINYIN_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PinyinForms> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    private SearchTextMatcher() {
    }

    static int matchScore(String title, String query) {
        String normalTitle = normalize(title);
        String normalQuery = normalize(query);
        if (normalTitle.isEmpty() || normalQuery.isEmpty()) return 0;

        int score = scoreAgainst(normalTitle, normalQuery, 1200, 1080, 920);
        PinyinForms pinyin = pinyinForms(title);
        score = Math.max(score, scorePinyinSet(pinyin.full, normalQuery, 1150, 1020, 840));
        score = Math.max(score, scorePinyinSet(pinyin.initials, normalQuery, 1100, 980, 800));
        score = Math.max(score, scoreOrderedPinyin(
                pinyin.syllables, normalQuery, pinyin.hasIdeographic));
        return score;
    }

    static boolean isPinyinLike(String value) {
        if (value == null) return false;
        String compact = value.trim()
                .replace(" ", "")
                .replace("'", "")
                .replace("’", "")
                .toLowerCase(Locale.ROOT);
        return !compact.isEmpty() && compact.matches("[a-z]+");
    }

    static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(decomposed.length());
        for (int offset = 0; offset < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint);
        }
        return result.toString();
    }

    private static int scorePinyinSet(Set<String> values, String query,
                                      int exact, int prefix, int containment) {
        int best = 0;
        for (String value : values) {
            best = Math.max(best,
                    scoreAgainst(value, query, exact, prefix, containment));
        }
        return best;
    }

    private static int scoreOrderedPinyin(List<List<String>> variants, String query,
                                          boolean hasIdeographic) {
        if (!hasIdeographic || query.length() < 4
                || !isAsciiLettersOrDigits(query)) {
            return 0;
        }
        for (List<String> syllables : variants) {
            if (matchesOrderedSyllables(syllables, query)) return 760;
        }
        return 0;
    }

    /**
     * Matches query fragments at syllable boundaries while allowing complete
     * title syllables to be skipped. This supports inputs such as "renxian"
     * for 凡人修仙传 without accepting arbitrary character subsequences.
     */
    private static boolean matchesOrderedSyllables(List<String> syllables, String query) {
        Set<Integer> reachable = new LinkedHashSet<>();
        reachable.add(0);
        for (String syllable : syllables) {
            Set<Integer> next = new LinkedHashSet<>(reachable);
            for (int queryOffset : reachable) {
                if (queryOffset >= query.length()) continue;
                int maxLength = Math.min(syllable.length(), query.length() - queryOffset);
                for (int length = 1; length <= maxLength; length++) {
                    if (!syllable.regionMatches(0, query, queryOffset, length)) break;
                    next.add(queryOffset + length);
                }
            }
            reachable = next;
            if (reachable.contains(query.length())) return true;
        }
        return false;
    }

    private static boolean isAsciiLettersOrDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!isAsciiLetterOrDigit(value.charAt(index))) return false;
        }
        return true;
    }

    private static int scoreAgainst(String value, String query,
                                    int exact, int prefix, int containment) {
        if (value.equals(query)) return exact;
        if (value.startsWith(query)) return prefix;
        if (query.length() >= 2 && value.contains(query)) return containment;
        return 0;
    }

    private static PinyinForms pinyinForms(String value) {
        String cacheKey = value == null ? "" : value;
        PinyinForms cached = PINYIN_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Set<String> full = new LinkedHashSet<>();
        Set<String> initials = new LinkedHashSet<>();
        full.add("");
        initials.add("");
        List<List<String>> syllables = new ArrayList<>();
        syllables.add(List.of());
        boolean hasIdeographic = false;
        if (value != null) {
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (Character.isIdeographic(codePoint)) hasIdeographic = true;
                List<String> options = pinyinOptions(codePoint);
                if (options.isEmpty()) continue;
                full = expand(full, options, false);
                initials = expand(initials, options, true);
                syllables = expandSyllables(syllables, options);
            }
        }
        PinyinForms forms = new PinyinForms(full, initials, syllables, hasIdeographic);
        PINYIN_CACHE.put(cacheKey, forms);
        return forms;
    }

    private static Set<String> expand(Set<String> prefixes, List<String> options,
                                      boolean initialOnly) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String prefix : prefixes) {
            for (String option : options) {
                String part = initialOnly ? option.substring(0, 1) : option;
                expanded.add(prefix + part);
                if (expanded.size() >= MAX_PINYIN_VARIANTS) return expanded;
            }
        }
        return expanded;
    }

    private static List<List<String>> expandSyllables(
            List<List<String>> variants, List<String> options) {
        List<List<String>> expanded = new ArrayList<>();
        for (List<String> prefix : variants) {
            for (String option : options) {
                List<String> variant = new ArrayList<>(prefix);
                variant.add(option);
                expanded.add(variant);
                if (expanded.size() >= MAX_PINYIN_VARIANTS) return expanded;
            }
        }
        return expanded;
    }

    private static List<String> pinyinOptions(int codePoint) {
        String character = new String(Character.toChars(codePoint));
        if (isAsciiLetterOrDigit(codePoint)) {
            return List.of(character.toLowerCase(Locale.ROOT));
        }
        if (!Character.isIdeographic(codePoint)) return List.of();
        try {
            String[] values = PinyinHelper.toHanyuPinyinStringArray(
                    Character.toChars(codePoint)[0], PINYIN_FORMAT);
            if (values == null || values.length == 0) return List.of();
            Set<String> options = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null) continue;
                String cleaned = normalize(value);
                if (!cleaned.isEmpty()) options.add(cleaned);
            }
            return new ArrayList<>(options);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static boolean isAsciiLetterOrDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9'
                || codePoint >= 'a' && codePoint <= 'z'
                || codePoint >= 'A' && codePoint <= 'Z';
    }

    private static HanyuPinyinOutputFormat createPinyinFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    private static final class PinyinForms {
        final Set<String> full;
        final Set<String> initials;
        final List<List<String>> syllables;
        final boolean hasIdeographic;

        PinyinForms(Set<String> full, Set<String> initials,
                    List<List<String>> syllables, boolean hasIdeographic) {
            this.full = Set.copyOf(full);
            this.initials = Set.copyOf(initials);
            List<List<String>> immutable = new ArrayList<>();
            for (List<String> syllablesVariant : syllables) {
                immutable.add(List.copyOf(syllablesVariant));
            }
            this.syllables = List.copyOf(immutable);
            this.hasIdeographic = hasIdeographic;
        }
    }
}
