package com.ch4nhm.tvapp;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.ch4nhm.tvapp.data.TvBoxConfig;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Loads TVBox live sources without involving the Java Spider process. Type 0
 * sources are text feeds in M3U, TXT or JSON form, which covers the live source
 * shape used by most public TVBox configurations.
 */
public final class LiveRepository {

    private static final String TAG = "LiveRepository";
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern HEADER_SPLIT = Pattern.compile("&");
    private static final LiveRepository INSTANCE = new LiveRepository();
    private static final ExecutorService NETWORK = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tvbox-live");
        thread.setDaemon(true);
        return thread;
    });

    private volatile LiveSource lastSource;

    private LiveRepository() {
    }

    public static LiveRepository get() {
        return INSTANCE;
    }

    public LiveSource getCached(String sourceUrl) {
        LiveSource source = lastSource;
        return source != null && source.sourceUrl.equals(sourceUrl) ? source : null;
    }

    public LiveSource getLastSource() {
        return lastSource;
    }

    public void load(Context context, TvBoxConfig config, TvBoxConfig.Live live,
                     Callback callback) {
        Context appContext = context.getApplicationContext();
        NETWORK.execute(() -> {
            long startedAt = System.currentTimeMillis();
            LiveSource cached = readCachedSource(appContext, config, live);
            if (cached != null) {
                lastSource = cached;
                Log.i(TAG, "Showing cached live source: " + live.url
                        + " (" + cached.channels.size() + " channels)");
                callback.onLoaded(cached);
            }
            try {
                LiveSource loaded = fetchAndParse(appContext, config, live);
                lastSource = loaded;
                Log.i(TAG, "Live source ready in " + (System.currentTimeMillis() - startedAt)
                        + " ms: " + loaded.finalUrl + " (" + loaded.channels.size()
                        + " channels)");
                callback.onLoaded(loaded);
            } catch (Throwable error) {
                Log.w(TAG, "Live source failed: " + live.url, error);
                if (cached == null) callback.onError(error);
            }
        });
    }

    private LiveSource readCachedSource(Context context, TvBoxConfig config,
                                        TvBoxConfig.Live live) {
        String configuredUrl = safe(live.url).trim();
        String cached = readCache(context, configuredUrl);
        if (cached.isBlank()) return null;
        try {
            Parsed parsed = parse(cached, configuredUrl);
            if (parsed.channels.isEmpty()) return null;
            applyHeaders(parsed.channels, config, live);
            return new LiveSource(safe(live.name).isBlank() ? "直播" : live.name.trim(),
                    configuredUrl, configuredUrl, parsed.format, parsed.channels,
                    parsed.epgUrls, true,
                    "正在显示上次成功加载的频道，后台同步最新列表");
        } catch (Throwable error) {
            Log.w(TAG, "Cached live source is invalid: " + configuredUrl, error);
            return null;
        }
    }

    private LiveSource fetchAndParse(Context context, TvBoxConfig config,
                                     TvBoxConfig.Live live) throws Exception {
        String configuredUrl = safe(live.url).trim();
        if (configuredUrl.isEmpty()) {
            throw new IllegalArgumentException("直播源没有填写地址");
        }
        List<String> candidates = candidates(configuredUrl);
        List<String> failures = new ArrayList<>();
        CompletionService<CandidateResult> completion =
                new ExecutorCompletionService<>(NETWORK);
        List<Future<CandidateResult>> requests = new ArrayList<>();
        for (String candidate : candidates) {
            requests.add(completion.submit(() -> {
                try {
                    Downloaded downloaded = download(config, live, candidate);
                    Parsed parsed = parse(downloaded.text, downloaded.finalUrl);
                    if (parsed.channels.isEmpty()) {
                        throw new IllegalStateException("返回内容中没有识别到频道");
                    }
                    return new CandidateResult(candidate, downloaded, parsed, null);
                } catch (Throwable error) {
                    return new CandidateResult(candidate, null, null, error);
                }
            }));
        }
        try {
            for (int index = 0; index < candidates.size(); index++) {
                CandidateResult result;
                try {
                    result = completion.take().get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw error;
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failures.add(friendlyNetworkError(rootMessage(cause)));
                    continue;
                }
                if (result.error != null) {
                    String detail = friendlyNetworkError(rootMessage(result.error));
                    failures.add(result.candidate + "：" + detail);
                    Log.w(TAG, "Live candidate failed: " + result.candidate + ": " + detail);
                    continue;
                }
                Downloaded downloaded = result.downloaded;
                Parsed parsed = result.parsed;
                cache(context, configuredUrl, downloaded.text);
                applyHeaders(parsed.channels, config, live);
                String warning = result.candidate.equals(configuredUrl) ? ""
                        : "源地址不可用，已自动切换到直连镜像";
                return new LiveSource(safe(live.name).isBlank() ? "直播" : live.name.trim(),
                        configuredUrl, downloaded.finalUrl, parsed.format, parsed.channels,
                        parsed.epgUrls, false, warning);
            }
        } finally {
            for (Future<CandidateResult> request : requests) request.cancel(true);
        }

        String summary = failures.isEmpty() ? "没有可用的直播源" : String.join("\n", failures);
        throw new IllegalStateException("连接失败：" + summary);
    }

    private Downloaded download(TvBoxConfig config, TvBoxConfig.Live live,
                                String url) throws Exception {
        Map<String, String> headers = headersForSource(config, live, url);
        Request.Builder request = new Request.Builder().url(url).tag("tvbox-live-source");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            request.header(entry.getKey(), entry.getValue());
        }
        OkHttpClient client = OkHttp.client().newBuilder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException("响应内容为空");
            byte[] data = readLimited(body.byteStream(), MAX_SOURCE_BYTES);
            if (data.length == 0) throw new IllegalStateException("响应内容为空");
            if (isGzip(data)) data = gunzip(data, MAX_SOURCE_BYTES);
            Charset charset = charset(body.contentType());
            String text = decode(data, charset);
            return new Downloaded(text, response.request().url().toString());
        }
    }

    private static Map<String, String> headersForSource(TvBoxConfig config,
                                                        TvBoxConfig.Live live,
                                                        String url) {
        Map<String, String> headers = headersForHost(config, url);
        String userAgent = safe(live.ua).trim();
        if (!userAgent.isEmpty()) headers.put("User-Agent", userAgent);
        return headers;
    }

    private static Map<String, String> headersForHost(TvBoxConfig config, String url) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null || config.headers == null) return result;
        String host = host(url);
        for (TvBoxConfig.Header rule : config.headers) {
            if (rule == null || rule.header == null) continue;
            String ruleHost = safe(rule.host).trim().toLowerCase(Locale.ROOT);
            if (!ruleHost.isEmpty() && !hostMatches(host, ruleHost)) continue;
            for (Map.Entry<String, String> entry : rule.header.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    private static boolean hostMatches(String host, String ruleHost) {
        return host.equals(ruleHost) || host.endsWith("." + ruleHost);
    }

    private static void applyHeaders(List<LiveChannel> channels, TvBoxConfig config,
                                     TvBoxConfig.Live live) {
        String sourceUserAgent = safe(live.ua).trim();
        for (LiveChannel channel : channels) {
            for (LiveStream stream : channel.streams) {
                Map<String, String> defaults = headersForHost(config, stream.url);
                if (!sourceUserAgent.isEmpty()) defaults.putIfAbsent("User-Agent", sourceUserAgent);
                for (Map.Entry<String, String> entry : defaults.entrySet()) {
                    stream.putHeaderIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static List<String> candidates(String url) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String direct = directMirror(url);
        // Known TVBox proxy domains frequently disappear while the underlying
        // GitHub raw URL stays available, so try the direct route first.
        if (!direct.equals(url)) result.add(direct);
        result.add(url);
        return new ArrayList<>(result);
    }

    private static String directMirror(String url) {
        String marker = "raw.githubusercontent.com/";
        int index = url.indexOf(marker);
        if (index >= 0 && (url.contains("down.nigx.cn/")
                || url.contains("gh.aptv.app/")
                || url.contains("ghproxy.net/")
                || url.contains("gh-proxy.com/")
                || url.contains("ghfast.top/"))) {
            return "https://" + url.substring(index).replace("//", "/");
        }
        if (url.startsWith("https://gh.aptv.app/https://")) {
            return url.substring("https://gh.aptv.app/".length());
        }
        return url;
    }

    private static Parsed parse(String raw, String sourceUrl) {
        String text = raw == null ? "" : raw;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = text.trim();
        if (trimmed.isEmpty()) throw new IllegalStateException("直播源内容为空");
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            Parsed result = parseJson(trimmed);
            if (!result.channels.isEmpty()) return result;
        }
        if (text.contains("#EXTINF") || trimmed.startsWith("#EXTM3U")) {
            return parseM3u(text, sourceUrl);
        }
        return parseTxt(text);
    }

    private static Parsed parseM3u(String text, String sourceUrl) {
        Parsed result = new Parsed("M3U");
        Map<String, LiveChannel> channels = new LinkedHashMap<>();
        PendingChannel pending = null;
        String pendingGroup = "";
        int sequence = 0;
        String[] lines = text.split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#EXT-X-")) continue;
            if (line.startsWith("#EXTM3U")) {
                Map<String, String> header = parseAttributes(line);
                addEpgUrls(result.epgUrls, header.get("x-tvg-url"));
                addEpgUrls(result.epgUrls, header.get("url-tvg"));
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                pending = parseExtInf(line);
                if (pending.group.isBlank()) pending.group = pendingGroup;
                continue;
            }
            if (line.startsWith("#EXTGRP:")) {
                pendingGroup = cleanGroup(line.substring(8));
                if (pending != null && pending.group.isBlank()) pending.group = pendingGroup;
                continue;
            }
            if (line.startsWith("#EXTVLCOPT:") || line.startsWith("#KODIPROP:")) {
                if (pending != null) {
                    int separator = line.indexOf(':');
                    if (separator >= 0) addHeaderOption(pending.headers,
                            line.substring(separator + 1));
                }
                continue;
            }
            if (line.startsWith("#EXTHTTP:")) {
                if (pending != null) {
                    addJsonHeaders(pending.headers, line.substring(9));
                }
                continue;
            }
            if (line.startsWith("#")) continue;
            if (pending == null) continue;
            UrlWithHeaders parsedUrl = parseStreamUrl(resolveUrl(sourceUrl, line));
            if (parsedUrl.url.isBlank()) continue;
            Map<String, String> headers = new LinkedHashMap<>(pending.headers);
            headers.putAll(parsedUrl.headers);
            String group = cleanGroup(pending.group);
            String name = cleanName(pending.name);
            if (name.isBlank()) name = "频道 " + (sequence + 1);
            String key = channelKey(group, pending.epgId, name);
            LiveChannel channel = channels.get(key);
            if (channel == null) {
                channel = new LiveChannel(key, name, group, pending.logo, pending.epgId);
                channels.put(key, channel);
            }
            channel.streams.add(new LiveStream(parsedUrl.url,
                    "线路 " + (channel.streams.size() + 1), headers));
            sequence++;
        }
        result.channels.addAll(channels.values());
        result.groups.addAll(groupsOf(result.channels));
        return result;
    }

    private static PendingChannel parseExtInf(String line) {
        PendingChannel pending = new PendingChannel();
        int metadataEnd = findMetadataComma(line);
        String metadata = metadataEnd >= 0 ? line.substring(0, metadataEnd) : line;
        String name = metadataEnd >= 0 ? line.substring(metadataEnd + 1).trim() : "";
        Map<String, String> attrs = parseAttributes(metadata);
        pending.name = name;
        pending.group = first(attrs, "group-title", "group", "groupname");
        pending.logo = first(attrs, "tvg-logo", "logo");
        // tvg-name is often shared by a whole series ("春晚"), so it must not
        // collapse dozens of distinct channels into one entry. Only a real
        // channel/EPG identifier is safe for multi-stream merging.
        pending.epgId = first(attrs, "tvg-id", "epg-id");
        if (pending.name.isBlank()) pending.name = first(attrs, "tvg-name", "tvg-id");
        addAttributeHeaders(pending.headers, attrs);
        return pending;
    }

    private static int findMetadataComma(String line) {
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"' || character == '\'') {
                if (quote == 0) quote = character;
                else if (quote == character) quote = 0;
            } else if (character == ',' && quote == 0) {
                return index;
            }
        }
        return -1;
    }

    private static Parsed parseTxt(String text) {
        Parsed result = new Parsed("TXT");
        Map<String, LiveChannel> channels = new LinkedHashMap<>();
        String group = "";
        int sequence = 0;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.endsWith(",#genre#")) {
                group = cleanGroup(line.substring(0, line.length() - 8));
                continue;
            }
            int separator = line.indexOf(',');
            if (separator <= 0) continue;
            String name = cleanName(line.substring(0, separator));
            String addresses = line.substring(separator + 1).trim();
            if (name.isBlank() || addresses.isBlank()) continue;
            List<String> urls = splitAddresses(addresses);
            if (urls.isEmpty()) continue;
            String effectiveGroup = cleanGroup(group);
            String key = channelKey(effectiveGroup, "", name);
            LiveChannel channel = channels.get(key);
            if (channel == null) {
                channel = new LiveChannel(key, name, effectiveGroup, "", "");
                channels.put(key, channel);
            }
            for (String address : urls) {
                UrlWithHeaders parsed = parseStreamUrl(address);
                if (parsed.url.isBlank()) continue;
                channel.streams.add(new LiveStream(parsed.url,
                        "线路 " + (channel.streams.size() + 1), parsed.headers));
            }
            sequence++;
        }
        result.channels.addAll(channels.values());
        result.groups.addAll(groupsOf(result.channels));
        return result;
    }

    private static Parsed parseJson(String text) {
        Parsed result = new Parsed("JSON");
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (Throwable error) {
            throw new IllegalStateException("JSON 解析失败：" + rootMessage(error));
        }
        Map<String, LiveChannel> channels = new LinkedHashMap<>();
        parseJsonElement(root, "", channels, new LinkedHashSet<>(), 0);
        result.channels.addAll(channels.values());
        collectJsonEpg(root, result.epgUrls);
        result.groups.addAll(groupsOf(result.channels));
        return result;
    }

    private static void parseJsonElement(JsonElement element, String inheritedGroup,
                                         Map<String, LiveChannel> channels,
                                         Set<JsonElement> visited, int depth) {
        if (element == null || element.isJsonNull() || depth > 8) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                parseJsonElement(child, inheritedGroup, channels, visited, depth + 1);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        if (!visited.add(element)) return;
        JsonObject object = element.getAsJsonObject();

        String group = firstString(object, "group", "group-title", "groupName",
                "category", "type");
        if (group.isBlank()) group = inheritedGroup;

        if (hasChannelUrl(object)) {
            addJsonChannel(object, cleanGroup(group), channels);
            return;
        }

        JsonElement groups = object.get("groups");
        if (groups != null) parseJsonElement(groups, group, channels, visited, depth + 1);
        JsonElement channelsElement = object.get("channels");
        if (channelsElement != null) {
            parseJsonElement(channelsElement, cleanGroup(firstString(object,
                    "name", "title", "group")), channels, visited, depth + 1);
        }
        for (String key : List.of("lives", "list", "items", "data", "result",
                "programs", "live")) {
            JsonElement child = object.get(key);
            if (child != null) parseJsonElement(child, group, channels, visited, depth + 1);
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (isKnownContainer(key)) continue;
            JsonElement child = entry.getValue();
            if (child != null && (child.isJsonArray() || child.isJsonObject())) {
                parseJsonElement(child, cleanGroup(key), channels, visited, depth + 1);
            }
        }

        // Some feeds use {"央视": [{"name":"CCTV1","url":"..."}]} without a
        // "groups" wrapper.
        if (channels.isEmpty() && object.entrySet().size() == 1) {
            Map.Entry<String, JsonElement> only = object.entrySet().iterator().next();
            parseJsonElement(only.getValue(), cleanGroup(only.getKey()),
                    channels, visited, depth + 1);
        }
    }

    private static void addJsonChannel(JsonObject object, String group,
                                       Map<String, LiveChannel> channels) {
        String name = firstString(object, "name", "title", "channelName", "tvg-name");
        if (name.isBlank()) return;
        String epgId = firstString(object, "tvg-id", "epgId", "id");
        String logo = firstString(object, "logo", "tvg-logo", "pic", "icon");
        String key = channelKey(group, epgId, name);
        LiveChannel channel = channels.get(key);
        if (channel == null) {
            channel = new LiveChannel(key, cleanName(name), group,
                    safe(logo).trim(), safe(epgId).trim());
            channels.put(key, channel);
        }
        JsonElement urls = firstElement(object, "urls", "streams", "links");
        if (urls == null) urls = firstElement(object, "url", "playUrl", "address", "path");
        addJsonStreams(channel, urls);
    }

    private static void addJsonStreams(LiveChannel channel, JsonElement urls) {
        if (urls == null || urls.isJsonNull()) return;
        if (urls.isJsonPrimitive()) {
            addAddresses(channel, urls.getAsString(), null);
            return;
        }
        if (urls.isJsonArray()) {
            for (JsonElement item : urls.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    addAddresses(channel, item.getAsString(), null);
                } else if (item.isJsonObject()) {
                    JsonObject stream = item.getAsJsonObject();
                    JsonElement url = firstElement(stream, "url", "address", "path");
                    if (url == null || !url.isJsonPrimitive()) continue;
                    Map<String, String> headers = new LinkedHashMap<>();
                    JsonElement header = stream.get("headers");
                    if (header == null) header = stream.get("header");
                    addJsonHeaderMap(headers, header);
                    addAddresses(channel, url.getAsString(),
                            firstString(stream, "name", "label", "quality"), headers);
                }
            }
        } else if (urls.isJsonObject()) {
            JsonObject stream = urls.getAsJsonObject();
            JsonElement url = firstElement(stream, "url", "address", "path");
            if (url != null && url.isJsonPrimitive()) {
                Map<String, String> headers = new LinkedHashMap<>();
                addJsonHeaderMap(headers, stream.get("headers"));
                addAddresses(channel, url.getAsString(),
                        firstString(stream, "name", "label"), headers);
            }
        }
    }

    private static void addAddresses(LiveChannel channel, String addresses,
                                     Map<String, String> headers) {
        addAddresses(channel, addresses, "", headers);
    }

    private static void addAddresses(LiveChannel channel, String addresses,
                                     String label, Map<String, String> additionalHeaders) {
        for (String address : splitAddresses(addresses)) {
            UrlWithHeaders parsed = parseStreamUrl(resolveUrl("", address));
            if (parsed.url.isBlank()) continue;
            Map<String, String> merged = new LinkedHashMap<>();
            if (additionalHeaders != null) merged.putAll(additionalHeaders);
            merged.putAll(parsed.headers);
            String finalLabel = label == null || label.isBlank()
                    ? "线路 " + (channel.streams.size() + 1) : label.trim();
            channel.streams.add(new LiveStream(parsed.url, finalLabel, merged));
        }
    }

    private static boolean hasChannelUrl(JsonObject object) {
        return firstElement(object, "url", "urls", "streams", "links",
                "playUrl", "address") != null;
    }

    private static boolean isKnownContainer(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "name", "title", "group", "group-title", "groupname", "category",
                    "type", "logo", "tvg-logo", "pic", "icon", "tvg-id", "epgid",
                    "epg", "epgs", "x-tvg-url", "url-tvg", "id",
                    "groups", "channels", "lives", "list", "items", "data",
                    "result", "programs", "live" -> true;
            default -> false;
        };
    }

    private static void collectJsonEpg(JsonElement element, List<String> result) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectJsonEpg(child, result);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        addEpgUrls(result, firstString(object, "epg", "epgs", "x-tvg-url", "url-tvg"));
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() != null && (entry.getValue().isJsonArray()
                    || entry.getValue().isJsonObject())) {
                collectJsonEpg(entry.getValue(), result);
            }
        }
    }

    private static List<String> splitAddresses(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        for (String part : value.split("#")) {
            String candidate = part.trim();
            if (!candidate.isEmpty()) result.add(candidate);
        }
        return result;
    }

    private static UrlWithHeaders parseStreamUrl(String raw) {
        String value = safe(raw).trim();
        Map<String, String> headers = new LinkedHashMap<>();
        int pipe = value.indexOf('|');
        if (pipe >= 0) {
            String headerPart = value.substring(pipe + 1);
            value = value.substring(0, pipe);
            for (String item : HEADER_SPLIT.split(headerPart)) {
                int equals = item.indexOf('=');
                if (equals <= 0) continue;
                String name = canonicalHeader(Uri.decode(item.substring(0, equals).trim()));
                String headerValue = Uri.decode(item.substring(equals + 1).trim());
                if (!name.isBlank() && !headerValue.isBlank()) {
                    headers.put(name, headerValue);
                }
            }
        }
        if (value.startsWith("//")) value = "https:" + value;
        return new UrlWithHeaders(value, headers);
    }

    private static void addHeaderOption(Map<String, String> headers, String option) {
        int equals = option.indexOf('=');
        if (equals <= 0) return;
        String name = canonicalHeader(option.substring(0, equals).trim());
        String value = option.substring(equals + 1).trim();
        if (!name.isBlank() && !value.isBlank()) headers.put(name, value);
    }

    private static void addAttributeHeaders(Map<String, String> headers,
                                            Map<String, String> attrs) {
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.startsWith("http-")) continue;
            String name = canonicalHeader(key.substring(5));
            if (!name.isBlank() && !entry.getValue().isBlank()) {
                headers.put(name, entry.getValue());
            }
        }
    }

    private static void addJsonHeaders(Map<String, String> headers, String json) {
        try {
            addJsonHeaderMap(headers, JsonParser.parseString(json));
        } catch (Throwable ignored) {
        }
    }

    private static void addJsonHeaderMap(Map<String, String> headers,
                                         JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                headers.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    private static String canonicalHeader(String raw) {
        String value = safe(raw).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "user-agent", "useragent", "ua" -> "User-Agent";
            case "referer", "referrer", "http-referer", "http-referrer" -> "Referer";
            case "origin" -> "Origin";
            case "cookie" -> "Cookie";
            default -> value.startsWith("http-") ? canonicalHeader(value.substring(5)) : raw.trim();
        };
    }

    private static Map<String, String> parseAttributes(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(line);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            result.put(matcher.group(1).toLowerCase(Locale.ROOT), value == null ? "" : value);
        }
        return result;
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static JsonElement firstElement(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) return value;
        }
        return null;
    }

    private static String firstString(JsonObject object, String... keys) {
        JsonElement value = firstElement(object, keys);
        try {
            return value == null || !value.isJsonPrimitive() ? "" : value.getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String resolveUrl(String baseUrl, String url) {
        String value = safe(url).trim();
        if (value.isEmpty() || value.startsWith("//")
                || value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
            return value;
        }
        try {
            URI base = URI.create(baseUrl);
            return base.resolve(value).toString();
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static String channelKey(String group, String epgId, String name) {
        String identity = safe(epgId).trim();
        if (identity.isEmpty()) identity = name.trim();
        String raw = cleanGroup(group) + "\u0000" + identity;
        return "channel-" + Integer.toUnsignedString(raw.hashCode(), 16);
    }

    private static List<String> groupsOf(Collection<LiveChannel> channels) {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (LiveChannel channel : channels) groups.add(channel.group);
        return new ArrayList<>(groups);
    }

    private static String cleanGroup(String value) {
        String group = safe(value).replaceAll("[\\p{Cf}]", "")
                .replaceAll("\\s+", " ").trim();
        return group.isEmpty() ? "其他频道" : group;
    }

    private static String cleanName(String value) {
        return safe(value).replaceAll("[\\p{Cf}]", "").replaceAll("\\s+", " ").trim();
    }

    private static void addEpgUrls(List<String> target, String value) {
        if (value == null || value.isBlank()) return;
        for (String part : value.split("[,\\s]+")) {
            String url = part.trim();
            if ((url.startsWith("http://") || url.startsWith("https://"))
                    && !target.contains(url)) {
                target.add(url);
            }
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > limit) {
                    throw new IOException("直播源文件超过 " + (limit / 1024 / 1024) + " MB");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    private static byte[] gunzip(byte[] data, int limit) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readLimited(input, limit);
        }
    }

    private static Charset charset(MediaType contentType) {
        try {
            return contentType == null ? null : contentType.charset();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String decode(byte[] data, Charset declared) {
        if (declared != null) return new String(data, declared);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException ignored) {
            return new String(data, Charset.forName("GB18030"));
        }
    }

    private static File cacheFile(Context context, String url) throws Exception {
        File directory = new File(context.getCacheDir(), "tvbox/live");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建直播缓存目录");
        }
        return new File(directory, sha256(url) + ".txt");
    }

    private static void cache(Context context, String url, String text) {
        try {
            File target = cacheFile(context, url);
            File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) return;
            temporary.renameTo(target);
        } catch (Throwable error) {
            Log.w(TAG, "Could not cache live source", error);
        }
    }

    private static String readCache(Context context, String url) {
        try {
            File file = cacheFile(context, url);
            if (!file.isFile()) return "";
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] data = readLimited(input, MAX_SOURCE_BYTES);
                return decode(data, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(safe(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }

    private static String host(String url) {
        try {
            String value = Uri.parse(url).getHost();
            return value == null ? "" : value.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String friendlyNetworkError(String message) {
        String value = safe(message).toLowerCase(Locale.ROOT);
        if (value.contains("not found: limit=0")
                || value.contains("unexpected end of stream")
                || value.contains("unexpected end")) {
            return "服务器返回空响应";
        }
        if (value.contains("connection reset")) return "连接被服务器重置";
        if (value.contains("timeout")) return "连接超时";
        if (value.contains("failed to connect")) return "无法连接服务器";
        return message;
    }

    public interface Callback {
        void onLoaded(LiveSource source);

        void onError(Throwable error);
    }

    public static final class LiveSource {
        public final String name;
        public final String sourceUrl;
        public final String finalUrl;
        public final String format;
        public final List<LiveChannel> channels;
        public final List<String> groups;
        public final List<String> epgUrls;
        public final boolean fromCache;
        public final String warning;

        LiveSource(String name, String sourceUrl, String finalUrl, String format,
                   List<LiveChannel> channels, List<String> epgUrls,
                   boolean fromCache, String warning) {
            this.name = name;
            this.sourceUrl = sourceUrl;
            this.finalUrl = finalUrl;
            this.format = format;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
            this.groups = Collections.unmodifiableList(groupsOf(channels));
            this.epgUrls = Collections.unmodifiableList(new ArrayList<>(epgUrls));
            this.fromCache = fromCache;
            this.warning = safe(warning);
        }

        public LiveChannel findChannel(String id) {
            if (id == null) return null;
            for (LiveChannel channel : channels) {
                if (id.equals(channel.id)) return channel;
            }
            return null;
        }

        public List<LiveChannel> channelsInGroup(String group) {
            if (group == null || group.isBlank()) return channels;
            List<LiveChannel> result = new ArrayList<>();
            for (LiveChannel channel : channels) {
                if (group.equals(channel.group)) result.add(channel);
            }
            return result;
        }
    }

    public static final class LiveChannel {
        public final String id;
        public final String name;
        public final String group;
        public final String logo;
        public final String epgId;
        public final List<LiveStream> streams = new ArrayList<>();

        LiveChannel(String id, String name, String group, String logo, String epgId) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.logo = safe(logo);
            this.epgId = safe(epgId);
        }
    }

    public static final class LiveStream {
        public final String url;
        public final String label;
        public final Map<String, String> headers;
        private final Map<String, String> mutableHeaders;

        LiveStream(String url, String label, Map<String, String> headers) {
            this.url = url;
            this.label = label;
            this.mutableHeaders = new LinkedHashMap<>(headers);
            this.headers = Collections.unmodifiableMap(mutableHeaders);
        }

        void putHeaderIfAbsent(String name, String value) {
            if (name != null && value != null) mutableHeaders.putIfAbsent(name, value);
        }
    }

    private static final class Parsed {
        final String format;
        final List<LiveChannel> channels = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        final List<String> epgUrls = new ArrayList<>();

        Parsed(String format) {
            this.format = format;
        }
    }

    private static final class PendingChannel {
        String name = "";
        String group = "";
        String logo = "";
        String epgId = "";
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    private record Downloaded(String text, String finalUrl) {
    }

    private record CandidateResult(String candidate, Downloaded downloaded, Parsed parsed,
                                   Throwable error) {
    }

    private record UrlWithHeaders(String url, Map<String, String> headers) {
    }
}
