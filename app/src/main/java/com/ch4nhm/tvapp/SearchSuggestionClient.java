package com.ch4nhm.tvapp;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches Chinese title candidates when the user submits an ASCII pinyin query.
 * It deliberately does not run while text is changing.
 */
final class SearchSuggestionClient {

    private static final String ENDPOINT = "https://suggestion.baidu.com/su";
    private static final Charset GBK = Charset.forName("GBK");
    private static final int MAX_SUGGESTIONS = 8;
    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "tvbox-search-suggestions");
                thread.setDaemon(true);
                return thread;
            });

    private SearchSuggestionClient() {
    }

    interface Callback {
        void onResult(List<String> suggestions, Throwable error);
    }

    static void suggest(String keyword, Callback callback) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty()) {
            callback.onResult(List.of(), null);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                callback.onResult(fetch(query), null);
            } catch (Throwable error) {
                callback.onResult(List.of(), error);
            }
        });
    }

    static List<String> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();

        try {
            JsonObject root = JsonParser.parseString(body.substring(start, end + 1))
                    .getAsJsonObject();
            JsonArray values = root.getAsJsonArray("s");
            if (values == null) return List.of();

            Set<String> seen = new LinkedHashSet<>();
            List<String> result = new ArrayList<>();
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive()) continue;
                String label = value.getAsString().trim()
                        .replaceAll("\\s+", " ");
                if (label.isEmpty() || label.length() > 80) continue;
                if (seen.add(SearchTextMatcher.normalize(label))) {
                    result.add(label);
                    if (result.size() >= MAX_SUGGESTIONS) break;
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<String> fetch(String keyword) throws Exception {
        HttpUrl url = HttpUrl.get(ENDPOINT).newBuilder()
                .addQueryParameter("wd", keyword)
                .addQueryParameter("json", "1")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 "
                                + "Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", "https://www.baidu.com/")
                .build();
        try (Response response = OkHttp.client(4_000L).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("联想服务响应 " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) return List.of();
            return parse(new String(body.bytes(), GBK));
        }
    }
}
