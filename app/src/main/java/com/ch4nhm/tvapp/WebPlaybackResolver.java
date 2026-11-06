package com.ch4nhm.tvapp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves the small number of TVBox player responses that return an HTML
 * parser page instead of a media URL. HhJx exposes a JSON endpoint used by its
 * own player page, so resolving it directly is much faster and more reliable
 * than booting a WebView just to sniff the resulting media request.
 */
final class WebPlaybackResolver {

    private static final String HHJX_HOST = "hhjx.hhplayer.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private static final WebPlaybackResolver INSTANCE = new WebPlaybackResolver();

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tvbox-web-parse");
        thread.setDaemon(true);
        return thread;
    });
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
    private final Map<String, CachedPlayback> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private WebPlaybackResolver() {
    }

    static boolean shouldResolve(TvBoxRepository.Playback playback) {
        if (playback == null) return false;
        if (playback.requiresParsing) return true;
        HttpUrl url = HttpUrl.parse(playback.url);
        return url != null && HHJX_HOST.equalsIgnoreCase(url.host());
    }

    static CompletableFuture<TvBoxRepository.Playback> resolve(
            TvBoxRepository.Playback playback) {
        if (playback == null || playback.url == null || playback.url.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("网页线路地址为空"));
        }
        long now = System.currentTimeMillis();
        CachedPlayback cached = INSTANCE.cache.get(playback.url);
        if (cached != null && cached.expiresAt > now) {
            return CompletableFuture.completedFuture(cached.playback);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                TvBoxRepository.Playback resolved = INSTANCE.resolveBlocking(playback);
                INSTANCE.cache.entrySet().removeIf(entry ->
                        entry.getValue().expiresAt <= now);
                INSTANCE.cache.put(playback.url,
                        new CachedPlayback(resolved, now + CACHE_TTL_MS));
                return resolved;
            } catch (IOException error) {
                throw new IllegalStateException(readable(error), error);
            }
        }, INSTANCE.executor);
    }

    private TvBoxRepository.Playback resolveBlocking(TvBoxRepository.Playback playback)
            throws IOException {
        HttpUrl pageUrl = HttpUrl.parse(playback.url);
        if (pageUrl == null) throw new IOException("网页线路地址无效");
        if (!HHJX_HOST.equalsIgnoreCase(pageUrl.host())) {
            throw new IOException("当前网页线路暂不支持自动解析");
        }

        String html = getText(pageUrl, playback.headers);
        Bootstrap bootstrap = parseBootstrap(html);
        HttpUrl apiUrl = pageUrl.newBuilder()
                .encodedPath("/api/parse")
                .query(null)
                .fragment(null)
                .build();
        ApiResult result = postParse(apiUrl, pageUrl.toString(), bootstrap, false,
                playback.headers);
        if (result.needsFallback()) {
            result = postParse(apiUrl, pageUrl.toString(), bootstrap, true,
                    playback.headers);
        }
        if (result.url.isBlank() || !isHttpUrl(result.url)) {
            throw new IOException(result.message.isBlank()
                    ? "解析线路没有返回播放地址" : result.message);
        }
        return new TvBoxRepository.Playback(result.url, Map.of(), result.message);
    }

    private String getText(HttpUrl url, Map<String, String> headers) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        applyHeaders(request, headers, false);
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("网页线路加载失败 HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("网页线路返回内容为空");
            return body.string();
        }
    }

    private ApiResult postParse(HttpUrl apiUrl, String pageUrl, Bootstrap bootstrap,
                                boolean fallback, Map<String, String> headers)
            throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("url", bootstrap.resourceUrl);
        payload.addProperty("t", bootstrap.timestamp);
        payload.addProperty("key", bootstrap.key);
        if (fallback) payload.addProperty("client_fallback", true);

        Request.Builder request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(payload.toString(), JSON))
                .header("Accept", "application/json")
                .header("Origin", apiUrl.scheme() + "://" + apiUrl.host())
                .header("Referer", pageUrl);
        applyHeaders(request, headers, true);
        try (Response response = client.newCall(request.build()).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("解析接口返回 HTTP " + response.code()
                        + responseMessage(text));
            }
            return parseApiResult(text);
        }
    }

    private static void applyHeaders(Request.Builder request, Map<String, String> headers,
                                     boolean post) {
        if (headers == null || headers.isEmpty()) return;
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null || name.isBlank()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("content-length")
                    || lower.equals("connection") || lower.equals("origin")
                    || lower.equals("referer")) {
                continue;
            }
            if (post && lower.equals("accept")) continue;
            try {
                request.header(name, value);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    static Bootstrap parseBootstrap(String html) throws IOException {
        String marker = "window.__HHJX_BOOTSTRAP__=";
        int markerIndex = html == null ? -1 : html.indexOf(marker);
        if (markerIndex < 0) throw new IOException("解析页面缺少初始化数据");
        int start = html.indexOf('{', markerIndex + marker.length());
        if (start < 0) throw new IOException("解析页面初始化数据无效");
        int end = matchingBrace(html, start);
        if (end < 0) throw new IOException("解析页面初始化数据不完整");
        try {
            JsonObject root = JsonParser.parseString(
                    html.substring(start, end + 1)).getAsJsonObject();
            String resourceUrl = string(root, "url");
            long timestamp = root.has("t") ? root.get("t").getAsLong() : 0L;
            String key = string(root, "key");
            if (resourceUrl.isBlank() || timestamp <= 0L || key.isBlank()) {
                throw new IOException("解析页面初始化参数不完整");
            }
            return new Bootstrap(resourceUrl, timestamp, key);
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("解析页面初始化数据格式错误", error);
        }
    }

    static ApiResult parseApiResult(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : 0;
            String message = string(root, "msg");
            if (code != 200) return new ApiResult("", "", message, code);
            return new ApiResult(string(root, "url"), string(root, "ext"),
                    message, code);
        } catch (RuntimeException error) {
            throw new IOException("解析接口返回格式错误", error);
        }
    }

    private static int matchingBrace(String value, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String responseMessage(String json) {
        try {
            String message = string(JsonParser.parseString(json).getAsJsonObject(), "msg");
            return message.isBlank() ? "" : ": " + message;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || value.isJsonNull()) return "";
        try {
            return value.getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean isHttpUrl(String value) {
        HttpUrl url = HttpUrl.parse(value == null ? "" : value.trim());
        return url != null && ("http".equals(url.scheme()) || "https".equals(url.scheme()));
    }

    private static String readable(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isBlank() ? "网页线路解析失败" : message;
    }

    static final class Bootstrap {
        final String resourceUrl;
        final long timestamp;
        final String key;

        Bootstrap(String resourceUrl, long timestamp, String key) {
            this.resourceUrl = resourceUrl;
            this.timestamp = timestamp;
            this.key = key;
        }
    }

    static final class ApiResult {
        final String url;
        final String ext;
        final String message;
        final int code;

        ApiResult(String url, String ext, String message, int code) {
            this.url = url == null ? "" : url.trim();
            this.ext = ext == null ? "" : ext.trim();
            this.message = message == null ? "" : message.trim();
            this.code = code;
        }

        boolean needsFallback() {
            return url.isBlank() || "youku".equalsIgnoreCase(ext)
                    || "hls_rewrite".equalsIgnoreCase(ext);
        }
    }

    private record CachedPlayback(TvBoxRepository.Playback playback, long expiresAt) {
    }
}
