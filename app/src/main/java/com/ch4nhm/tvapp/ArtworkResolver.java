package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a higher-resolution equivalent for provider thumbnails. TVBox sources
 * commonly expose 270px artwork even when a 1080px poster exists elsewhere.
 */
final class ArtworkResolver {

    private static final String TAG = "TVBoxArtwork";
    interface Callback {
        void onResolved(String url);
    }

    private static final String PREFS = "artwork_resolution";
    private static final String SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q=";
    private static final String CINEMETA_MOVIE_URL =
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=";
    private static final String CINEMETA_SERIES_URL =
            "https://v3-cinemeta.strem.io/catalog/series/top/search=";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/140.0.0.0 Safari/537.36";
    private static final int GOOD_ARTWORK_SCORE = 600;
    private static final Pattern BRACKETS = Pattern.compile(
            "[\\[\\(（【「『]([^\\]\\)）】」』]{1,28})[\\]\\)）】」』]");
    private static final Pattern QUALITY_MARKERS = Pattern.compile(
            "(?i)(?:^|[\\s._-]+)(?:4K|8K|2160P|1080P|720P|HDR|DV|"
                    + "WEB[- ]?DL|BluRay|REMUX|HEVC|AVC|HD|TC|TS|高清|超清|蓝光)(?:$|[\\s._-]+)");
    private static final Pattern STATUS_MARKERS = Pattern.compile(
            "(?i)(?:更新至\\s*\\d+\\s*[集话期]?|全\\s*\\d+\\s*[集话期]|"
                    + "共\\s*\\d+\\s*[集话期]|第\\s*\\d+\\s*[集话期]|"
                    + "已完结|完结|国语|粤语|英语|中字|双语|无删减|未删减|"
                    + "完整版|高清版|蓝光版)");
    private static final Pattern EDITION_PREFIX = Pattern.compile(
            "(?i)^(?:电影|电视剧|剧集|动漫|动画|综艺|短剧|纪录片|"
                    + "4K|8K|1080P|720P|HD|高清|超清|蓝光|免费|热播)\\s*[:：|·-]\\s*");
    private static final Pattern YEAR_SUFFIX = Pattern.compile(
            "(?i)[\\s._|·-]*(?:19|20)\\d{2}\\s*$");
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mkv|mp4|avi|ts|m3u8|flv|webm)$");
    private static final Pattern DIMENSION = Pattern.compile(
            "(?i)(?:^|[/_\\-,])([1-9]\\d{2,4})[xX]([1-9]\\d{2,4})(?:[/_\\-.,]|$)");
    private static final Pattern IMAGE_RESIZE_SUFFIX = Pattern.compile(
            "(?i)@[^/?#]*(?:w_|h_|resize|quality|webp|avif|1c)[^/?#]*$");
    private static final Pattern IMAGE_DIMENSION_SUFFIX = Pattern.compile(
            "(?i)_\\d{2,4}x\\d{2,4}(?=\\.[a-z0-9]+(?:$|\\?))");
    private static final Pattern AMAZON_RESIZE_SUFFIX = Pattern.compile(
            "(?i)(\\._V1_)[^/?#]+\\.(jpe?g|png|webp)(?=$|[?#])");

    private static volatile ArtworkResolver instance;

    private final SharedPreferences cache;
    private final Map<String, String> memory = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Callback>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Long> misses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private ArtworkResolver(Context context) {
        cache = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static ArtworkResolver get(Context context) {
        if (instance == null) {
            synchronized (ArtworkResolver.class) {
                if (instance == null) instance = new ArtworkResolver(context);
            }
        }
        return instance;
    }

    String cached(String title, String year) {
        String primary = cacheKey(title, year);
        if (primary.isEmpty()) return null;
        String best = best(null, memory.get(primary), cache.getString(primary, null));
        String withoutYear = cacheKey(title, null);
        if (!withoutYear.isEmpty()) {
            best = best(best, memory.get(withoutYear), cache.getString(withoutYear, null));
        }
        if (!TextUtils.isEmpty(best)) memory.putIfAbsent(primary, best);
        return best;
    }

    String cachedFast(String title, String year) {
        String primary = cacheKey(title, year);
        if (primary.isEmpty()) return null;
        String best = memory.get(primary);
        String withoutYear = cacheKey(title, null);
        if (!withoutYear.isEmpty()) best = best(best, memory.get(withoutYear));
        return best;
    }

    void remember(List<ContentItem> items) {
        if (items == null || items.isEmpty()) return;
        for (ContentItem item : items) {
            if (item != null) remember(item.title, item.year, item.pic);
        }
    }

    /**
     * Warm the resolver before rows scroll into view. This avoids showing the
     * provider's 270px thumbnail first and replacing it seconds later.
     */
    void prefetch(List<ContentItem> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) return;
        Set<String> seen = new LinkedHashSet<>();
        int started = 0;
        for (ContentItem item : items) {
            if (item == null || TextUtils.isEmpty(item.title)
                    || !hasRemoteArtwork(item.pic)) continue;
            String key = normalize(item.title) + "|" + normalize(item.year);
            if (!seen.add(key)) continue;
            upgrade(item.title, item.year, resolved -> {
            });
            if (++started >= limit) break;
        }
    }

    void remember(String title, String year, String rawUrl) {
        if (!hasRemoteArtwork(rawUrl) || normalize(title).isEmpty()) return;
        String upgraded = upgradeUrl(rawUrl);
        rememberKey(cacheKey(title, year), upgraded);
        if (year != null && !year.isBlank()) rememberKey(cacheKey(title, null), upgraded);
        if (imageScore(upgraded) >= GOOD_ARTWORK_SCORE) misses.remove(cacheKey(title, year));
    }

    String preferred(String title, String year, String rawUrl) {
        String known = cached(title, year);
        if (TextUtils.isEmpty(known)) return rawUrl;
        return imageScore(known) + 8 >= imageScore(rawUrl) ? known : rawUrl;
    }

    String preferredFast(String title, String year, String rawUrl) {
        String known = cachedFast(title, year);
        if (TextUtils.isEmpty(known)) return rawUrl;
        return imageScore(known) + 8 >= imageScore(rawUrl) ? known : rawUrl;
    }

    void upgrade(String title, String year, Callback callback) {
        String key = cacheKey(title, year);
        if (key.isEmpty() || callback == null) return;

        String fastKnown = cachedFast(title, year);
        if (fastKnown != null && imageScore(fastKnown) >= GOOD_ARTWORK_SCORE) {
            main.post(() -> callback.onResolved(fastKnown));
            return;
        }
        Long retryAt = misses.get(key);
        if (retryAt != null && retryAt > System.currentTimeMillis()) return;

        synchronized (this) {
            listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(callback);
            if (!pending.add(key)) return;
        }

        Log.i(TAG, "Resolving artwork: " + title + " " + year);
        executor.execute(() -> {
            String resolved = null;
            try {
                String cached = cached(title, year);
                if (cached != null && imageScore(cached) >= GOOD_ARTWORK_SCORE) {
                    resolved = cached;
                } else {
                    // A tiny gap keeps large grids from bursting the suggestion endpoint.
                    Thread.sleep(90L);
                    resolved = query(title, year);
                    if (resolved == null) {
                        Thread.sleep(320L);
                        resolved = query(title, year);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                List<Callback> waiting;
                synchronized (ArtworkResolver.this) {
                    pending.remove(key);
                    waiting = listeners.remove(key);
                }
                if (resolved == null || resolved.isEmpty()) {
                    misses.put(key, System.currentTimeMillis() + 30 * 60_000L);
                    Log.i(TAG, "No artwork match: " + title + " " + year);
                } else {
                    misses.remove(key);
                    memory.put(key, resolved);
                    cache.edit().putString(key, resolved).apply();
                    String image = resolved;
                    main.post(() -> {
                        for (Callback item : waiting) item.onResolved(image);
                    });
                }
            }
        });
    }

    static boolean hasRemoteArtwork(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int headers = value.indexOf("@referer=");
        int agent = value.indexOf("@user-agent=");
        int marker = headers < 0 ? agent : agent < 0 ? headers : Math.min(headers, agent);
        if (marker >= 0) value = value.substring(0, marker);
        boolean remote = value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("//");
        return remote && !isKnownBrokenArtworkSupplier(value);
    }

    /**
     * Some providers proxy an unavailable origin through Baidu image search.
     * Baidu answers those requests with a valid 4x8 GIF, which Glide would
     * otherwise scale into a black poster.
     */
    static boolean isKnownBrokenArtworkSupplier(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.contains("baidu.com/gimg/")) return false;
        try {
            value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // The unescaped form still catches the common provider URLs.
        }
        int source = value.indexOf("src=");
        while (source >= 0) {
            String origin = value.substring(source + 4);
            if (origin.startsWith("http://")) origin = origin.substring(7);
            else if (origin.startsWith("https://")) origin = origin.substring(8);
            else if (origin.startsWith("//")) origin = origin.substring(2);
            if (origin.matches("(?i)(?:www\\.)?(?:pub|img)\\d*\\.bfzy\\.tv/.*")) {
                return true;
            }
            source = value.indexOf("src=", source + 4);
        }
        return false;
    }

    static boolean hasLowResolutionMarker(String raw) {
        if (raw == null) return false;
        String value = raw.toLowerCase(Locale.ROOT);
        return value.contains("/s_ratio_poster/")
                || value.contains("/m_ratio_poster/")
                || value.contains("/t/p/w185/")
                || value.contains("/t/p/w300/")
                || value.contains("/poster/small/")
                || value.matches(".*\\._v1_(?:[^/?#]*?)(?:sx|sy|ux|uy)\\d+.*")
                || value.contains("/small/")
                || value.contains("thumbnail")
                || value.contains("thumb/")
                || value.matches(".*@[^/?#]*(?:w_|h_|resize|quality)[^/?#]*$");
    }

    static boolean isGoodArtwork(String raw) {
        return imageScore(raw) >= GOOD_ARTWORK_SCORE;
    }

    private String query(String title, String year) {
        for (String candidate : candidateTitles(title)) {
            String resolved = queryOnce(candidate, title, year);
            if (!TextUtils.isEmpty(resolved)) return resolved;
        }
        if (hasLatinTitle(title)) {
            String candidate = candidateTitles(title).stream()
                    .filter(ArtworkResolver::hasLatinTitle)
                    .findFirst()
                    .orElse(title);
            String resolved = queryCinemeta(candidate, year);
            if (!TextUtils.isEmpty(resolved)) return resolved;
        }
        return null;
    }

    private String queryOnce(String queryTitle, String originalTitle, String year) {
        HttpURLConnection connection = null;
        try {
            String endpoint = SUGGEST_URL
                    + URLEncoder.encode(queryTitle, StandardCharsets.UTF_8.name());
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(6_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("Referer", "https://movie.douban.com/");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Artwork suggest HTTP " + status + ": " + queryTitle);
                return null;
            }

            String body;
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder value = new StringBuilder();
                char[] buffer = new char[2048];
                int count;
                while ((count = reader.read(buffer)) >= 0 && value.length() < 65_536) {
                    value.append(buffer, 0, count);
                }
                body = value.toString();
            }

            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonArray()) return null;
            JsonArray suggestions = parsed.getAsJsonArray();
            String normalized = normalize(queryTitle);
            String best = null;
            int bestScore = 0;
            for (JsonElement element : suggestions) {
                if (!element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                String candidateTitle = string(candidate, "title");
                String alternateTitle = string(candidate, "sub_title");
                String candidateYear = string(candidate, "year");
                int score = matchScore(normalized, normalize(candidateTitle));
                score = Math.max(score,
                        matchScore(normalized, normalize(alternateTitle)));
                if (score == 0) {
                    score = matchScore(normalize(originalTitle), normalize(candidateTitle));
                    score = Math.max(score,
                            matchScore(normalize(originalTitle), normalize(alternateTitle)));
                }
                if (score == 0) continue;
                if (year != null && !year.isBlank() && year.equals(candidateYear)) {
                    score += 24;
                }
                if (score <= bestScore) continue;
                String image = upgradeUrl(string(candidate, "img"));
                if (image.isEmpty()) continue;
                best = image;
                bestScore = score;
            }
            return best;
        } catch (Throwable error) {
            Log.w(TAG, "Artwork suggest failed: " + queryTitle + " / " + error);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String queryCinemeta(String title, String year) {
        String movie = queryCinemeta(title, year, CINEMETA_MOVIE_URL);
        if (!TextUtils.isEmpty(movie)) return movie;
        return queryCinemeta(title, year, CINEMETA_SERIES_URL);
    }

    private String queryCinemeta(String title, String year, String endpoint) {
        HttpURLConnection connection = null;
        try {
            String query = endpoint
                    + URLEncoder.encode(title, StandardCharsets.UTF_8.name()) + ".json";
            connection = (HttpURLConnection) new URL(query).openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_500);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            String body;
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder value = new StringBuilder();
                char[] buffer = new char[2048];
                int count;
                while ((count = reader.read(buffer)) >= 0 && value.length() < 131_072) {
                    value.append(buffer, 0, count);
                }
                body = value.toString();
            }

            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return null;
            JsonElement metasElement = parsed.getAsJsonObject().get("metas");
            if (metasElement == null || !metasElement.isJsonArray()) return null;
            JsonArray metas = metasElement.getAsJsonArray();
            String wanted = normalize(title);
            String best = null;
            int bestScore = 0;
            for (JsonElement element : metas) {
                if (!element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                String candidateName = string(candidate, "name");
                if (matchScore(wanted, normalize(candidateName)) < 100) continue;
                int score = 100;
                String candidateYear = yearPart(string(candidate, "year"));
                String requestedYear = yearPart(year);
                if (!requestedYear.isEmpty() && !candidateYear.isEmpty()) {
                    score += requestedYear.equals(candidateYear) ? 24 : -18;
                }
                if (score <= bestScore) continue;
                String metadataId = string(candidate, "id");
                String image = metadataId.matches("tt\\d+")
                        ? "https://images.metahub.space/poster/large/" + metadataId + "/img"
                        : upgradeUrl(string(candidate, "poster"));
                if (image.isEmpty()) continue;
                best = image;
                bestScore = score;
            }
            return best;
        } catch (Throwable error) {
            Log.w(TAG, "Artwork metadata failed: " + title + " / "
                    + error.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String upgradeUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("//")) value = "https:" + value;
        if (value.contains("doubanio.com/view/photo/")) {
            value = value.replace("/s_ratio_poster/", "/l_ratio_poster/")
                    .replace("/m_ratio_poster/", "/l_ratio_poster/");
        }
        if (value.contains("image.tmdb.org/t/p/")) {
            value = value.replaceFirst(
                    "(?i)/t/p/(?:w\\d+|h\\d+)(?:_and_h\\d+_bestv2)?/",
                    "/t/p/original/");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("hdslb.com") || lower.contains("bilibili.com")
                || lower.contains("zhimg.com") || lower.contains("meituan.net")) {
            value = IMAGE_RESIZE_SUFFIX.matcher(value).replaceFirst("");
        }
        if (lower.contains("alicdn.com") || lower.contains("bdstatic.com")) {
            value = IMAGE_DIMENSION_SUFFIX.matcher(value).replaceFirst("");
        }
        if (lower.contains("aliyuncs.com") || lower.contains("myqcloud.com")
                || lower.contains("qiniucdn.com") || lower.contains("qbox.me")
                || lower.contains("bdstatic.com")) {
            value = stripResizeQuery(value);
        }
        if (lower.contains("media-amazon.com/images/")) {
            value = AMAZON_RESIZE_SUFFIX.matcher(value)
                    .replaceFirst("$1.$2");
        }
        if (lower.contains("images.metahub.space/poster/small/")) {
            value = value.replace("/poster/small/", "/poster/large/");
        } else if (lower.contains("images.metahub.space/poster/medium/")) {
            value = value.replace("/poster/medium/", "/poster/large/");
        }
        return value;
    }

    private static String stripResizeQuery(String value) {
        int query = value.indexOf('?');
        if (query < 0 || query == value.length() - 1) return value;
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        for (String part : value.substring(query + 1).split("&")) {
            String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.equals("x-oss-process") || name.equals("imageview2")
                    || name.equals("imagemogr2") || name.equals("thumbnail")
                    || name.equals("resize") || name.equals("x-bce-process")) continue;
            if (!part.isBlank()) keep.add(part);
        }
        return value.substring(0, query) + (keep.isEmpty() ? "" : "?" + String.join("&", keep));
    }

    private void rememberKey(String key, String url) {
        if (key.isEmpty() || TextUtils.isEmpty(url)) return;
        memory.compute(key, (ignored, current) ->
                imageScore(url) > imageScore(current) ? url : current);
    }

    private static String best(String current, String... candidates) {
        String best = current;
        for (String candidate : candidates) {
            if (TextUtils.isEmpty(candidate)) continue;
            if (TextUtils.isEmpty(best) || imageScore(candidate) > imageScore(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static int imageScore(String raw) {
        if (TextUtils.isEmpty(raw)) return -1;
        String value = raw.toLowerCase(Locale.ROOT);
        int score = 50;
        if (value.contains("doubanio.com/view/photo/l_ratio_poster/")) score = 960;
        else if (value.contains("doubanio.com/view/photo/m_ratio_poster/")) score = 640;
        else if (value.contains("doubanio.com/view/photo/s_ratio_poster/")) score = 280;
        else if (value.contains("image.tmdb.org/t/p/original/")) score = 940;
        else if (value.contains("image.tmdb.org/t/p/w780/")) score = 820;
        else if (value.contains("image.tmdb.org/t/p/w500/")) score = 680;
        else if (value.contains("image.tmdb.org/t/p/w342/")) score = 520;
        else if (value.contains("image.tmdb.org/t/p/w185/")) score = 300;
        else if (value.contains("images.metahub.space/poster/large/")) score = 900;
        else if (value.contains("images.metahub.space/poster/medium/")) score = 650;
        else if (value.contains("images.metahub.space/poster/small/")) score = 300;
        else if (value.contains("media-amazon.com/images/")
                && value.matches(".*\\._v1_\\.(?:jpe?g|png|webp)(?:$|[?#]).*")) score = 900;
        else if (hasLowResolutionMarker(value)) score = 120;
        if (value.contains("/original/") || value.contains("/origin/")) {
            score = Math.max(score, 900);
        }
        if (value.matches(".*(?:/large/|/xl/|/xxl/|/raw/|/full/).*")) {
            score = Math.max(score, 760);
        }

        Matcher dimension = DIMENSION.matcher(value);
        if (dimension.find()) {
            try {
                int width = Integer.parseInt(dimension.group(1));
                int height = Integer.parseInt(dimension.group(2));
                score = Math.max(score, Math.max(width, height));
            } catch (NumberFormatException ignored) {
            }
        }
        if (value.contains("/upload/") && !hasLowResolutionMarker(value)) {
            score = Math.max(score, 360);
        }
        return score;
    }

    private static List<String> candidateTitles(String title) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String original = title == null ? "" : title
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        addCandidate(values, FILE_EXTENSION.matcher(original).replaceFirst(""));
        String withoutBrackets = BRACKETS.matcher(original).replaceAll(" ");
        addCandidate(values, withoutBrackets);
        String cleaned = STATUS_MARKERS.matcher(
                QUALITY_MARKERS.matcher(withoutBrackets).replaceAll(" ")).replaceAll(" ");
        cleaned = EDITION_PREFIX.matcher(cleaned).replaceFirst("");
        cleaned = YEAR_SUFFIX.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.replaceAll("^[\\s._|/:-]+|[\\s._|/:-]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        addCandidate(values, cleaned);

        for (String separator : new String[]{":", "：", "|"}) {
            int index = cleaned.indexOf(separator);
            if (index >= 2) addCandidate(values, cleaned.substring(0, index));
        }
        String seasonless = cleaned.replaceFirst(
                "(?i)\\s*第?\\s*(?:[一二三四五六七八九十]+|\\d{1,2})\\s*季\\s*$", "");
        addCandidate(values, seasonless);
        return new ArrayList<>(values);
    }

    private static void addCandidate(Set<String> values, String value) {
        if (value == null) return;
        String candidate = value.replaceAll("\\s+", " ").trim();
        if (candidate.length() >= 2 && candidate.length() <= 80) values.add(candidate);
    }

    private static int matchScore(String wanted, String candidate) {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0;
        if (wanted.equals(candidate)) return 100;
        if (weakerMatch(wanted, candidate) || weakerMatch(candidate, wanted)) {
            return Math.min(wanted.length(), candidate.length()) >= 4 ? 72 : 0;
        }
        return 0;
    }

    private static boolean weakerMatch(String left, String right) {
        return left.contains(right) && right.length() >= Math.max(3, left.length() * 2 / 3);
    }

    private static boolean hasLatinTitle(String title) {
        if (title == null) return false;
        boolean latin = false;
        for (int index = 0; index < title.length(); ) {
            int codePoint = title.codePointAt(index);
            index += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) return false;
            if (Character.isLetter(codePoint)) latin = true;
        }
        return latin;
    }

    private static String yearPart(String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile("(?:19|20)\\d{2}").matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static String cacheKey(String title, String year) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) return "";
        String identity = normalized + "|" + (year == null ? "" : year.trim());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (Exception ignored) {
            return "artwork_" + Integer.toHexString(identity.hashCode());
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
